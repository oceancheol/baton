package app.baton.parking.signal

import app.baton.core.signal.SignalAvailability
import app.baton.core.signal.SignalEvent
import app.baton.core.signal.SignalKind
import app.baton.core.signal.SignalKind.DOOR
import app.baton.core.signal.SignalKind.PARKING_BRAKE
import app.baton.core.signal.SignalKind.SEAT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 구독 값과 조회 답을 순번으로 정리하는 논리(SDK 없이). */
class SignalSequencerTest {
    private val seq = SignalSequencer()
    private var now = 1_000L
    private var generation: Long? = 1L

    private fun stamp(): ObservationStamp {
        now += 10
        return seq.stamp(now, generation)
    }

    private fun on(value: Boolean) = VehicleReading.of(value)

    /** 기준값 조회 한 번(조회 중에 들어온 값 없음). */
    private fun baseline(vararg answers: Pair<SignalKind, VehicleReading>): BaselineResult {
        seq.beginBaseline(stamp())
        return seq.completeBaseline(answers.toMap())
    }

    @Test
    fun `조회 중에 들어온 구독 값은 모아 두었다가 기준값 뒤에 순번대로 내보낸다 - 문 여닫힘이 사라지지 않는다`() {
        val start = stamp()
        seq.beginBaseline(start)
        // 문 조회의 답은 이미 "닫힘"으로 읽혔고, 다른 신호의 답을 기다리는 동안 문이 열렸다 닫혔다.
        assertNull(seq.onListener(DOOR, on(true), stamp()))
        assertNull(seq.onListener(DOOR, on(false), stamp()))

        val result = seq.completeBaseline(mapOf(DOOR to on(false), SEAT to on(false)))

        assertEquals(on(false), result.levels[DOOR])
        assertEquals(listOf(true, false), result.pending.map { it.reading.value })
        assertTrue(result.pending.all { it.kind == DOOR })
        assertTrue(result.pending.all { it.stamp.atMillis >= result.start.atMillis }, "기준값보다 나중 관측으로 나간다")
        assertEquals(result.pending.map { it.stamp.seq }.sorted(), result.pending.map { it.stamp.seq })
    }

    @Test
    fun `조회 답보다 늦게 반영된 구독 값이 마지막 값이 된다 - 옛 답이 최신 값을 덮어쓰지 못한다`() {
        baseline(SEAT to on(false))
        seq.beginBaseline(stamp())
        // 조회는 착좌 전에 읽혔고(false), 그 답이 돌아오기 전에 구독으로 착좌(true)가 들어왔다.
        seq.onListener(SEAT, on(true), stamp())
        val result = seq.completeBaseline(mapOf(SEAT to on(false)))

        assertEquals(listOf(true), result.pending.map { it.reading.value })
        assertEquals(on(true), seq.latestOf(SEAT))
        // 이후 실제로 내리면(false) 중복으로 버려지지 않고 나간다.
        assertEquals(on(false), seq.onListener(SEAT, on(false), stamp())?.reading)
    }

    @Test
    fun `조회 답과 같은 구독 값은 다시 내보내지 않는다`() {
        seq.beginBaseline(stamp())
        seq.onListener(DOOR, on(false), stamp()) // 구독 등록 직후 현재 값이 한 번 더 온 경우
        val result = seq.completeBaseline(mapOf(DOOR to on(false)))
        assertTrue(result.pending.isEmpty())
        assertNull(seq.onListener(DOOR, on(false), stamp()))
        assertEquals(on(true), seq.onListener(DOOR, on(true), stamp())?.reading)
    }

    @Test
    fun `조회 시작보다 앞서 찍힌 구독 값은 답보다 오래된 관측이라 버린다`() {
        baseline(DOOR to on(false))
        val before = stamp() // 콜백은 조회 시작 전에 들어왔지만 처리는 조회가 끝난 뒤에 됐다
        val result = baseline(DOOR to on(false))
        assertTrue(result.pending.isEmpty())

        assertNull(seq.onListener(DOOR, on(true), before))
        assertEquals(on(false), seq.latestOf(DOOR))
    }

    @Test
    fun `조회 시작 전에 찍혀 조회 중에 처리된 값도 기준값 뒤로 내보내지 않는다`() {
        val before = stamp()
        seq.beginBaseline(stamp())
        seq.onListener(DOOR, on(true), before)
        val result = seq.completeBaseline(mapOf(DOOR to on(false)))
        assertTrue(result.pending.isEmpty())
        assertEquals(on(false), seq.latestOf(DOOR))
    }

    @Test
    fun `순번이 뒤바뀌어 처리된 더 오래된 구독 값은 버린다`() {
        baseline(DOOR to on(false))
        val older = stamp()
        val newer = stamp()
        assertEquals(on(true), seq.onListener(DOOR, on(true), newer)?.reading)
        assertNull(seq.onListener(DOOR, on(false), older))
        assertEquals(on(true), seq.latestOf(DOOR))
    }

    @Test
    fun `조회는 한 번에 하나만 연다`() {
        seq.beginBaseline(stamp())
        assertFailsWith<IllegalStateException> { seq.beginBaseline(stamp()) }
    }

    @Test
    fun `취소된 조회가 모아 둔 변경은 버리지 않고 내보낸다`() {
        baseline(DOOR to on(false))
        seq.beginBaseline(stamp())
        seq.onListener(DOOR, on(true), stamp())
        val flushed = seq.abortBaseline()
        assertEquals(listOf(true), flushed.map { it.reading.value })
        // 다시 열 수 있다.
        seq.beginBaseline(stamp())
        assertTrue(seq.completeBaseline(mapOf(DOOR to on(true))).pending.isEmpty())
    }

    @Test
    fun `관측의 시각과 주차 세대는 콜백에 들어온 순간의 것이다 - 새 주차 전에 받은 값은 옛 세대로 나간다`() {
        baseline(SEAT to on(false))
        val entered = stamp() // 콜백 진입: 세대 1
        val enteredAt = now
        generation = 2L // 처리되기 전에 새 주차가 시작됐다
        now += 5_000

        val change = checkNotNull(seq.onListener(SEAT, on(true), entered))
        val event = change.toEvent() as SignalEvent.DriverSeat
        assertEquals(1L, event.parkingGeneration)
        assertEquals(enteredAt, event.observedAtMillis)
        assertTrue(event.occupied)

        // 새 주차 뒤에 들어온 값은 새 세대다.
        val after = checkNotNull(seq.onListener(SEAT, on(false), stamp())).toEvent()
        assertEquals(2L, after.parkingGeneration)
    }

    @Test
    fun `읽지 못한 값은 상태 변경으로 나간다`() {
        baseline(DOOR to on(false))
        val event = checkNotNull(seq.onListener(DOOR, VehicleReading.of(null), stamp())).toEvent()
        assertEquals(SignalEvent.AvailabilityChanged(DOOR, SignalAvailability.UNAVAILABLE, now, 1L), event)
    }

    // ---- 주기적 확인(단절 감지) --------------------------------------------------

    @Test
    fun `주기적 확인에서 하나도 읽지 못하면 모든 차량 신호를 일시 불가로 내보내고 연결 불가가 된다`() {
        baseline(DOOR to on(false), SEAT to on(true), PARKING_BRAKE to on(true))
        assertEquals(LinkStatus.RECEIVING, seq.status)

        val start = stamp()
        val dead = mapOf(DOOR to VehicleReading.UNAVAILABLE, SEAT to VehicleReading.UNAVAILABLE, PARKING_BRAKE to VehicleReading.UNAVAILABLE)
        val result = seq.onProbe(start, dead, stamp())

        assertEquals(LinkStatus.UNAVAILABLE, seq.status)
        assertFalse(result.recovered)
        assertEquals(setOf(DOOR, SEAT, PARKING_BRAKE), result.changes.map { it.kind }.toSet())
        assertTrue(result.changes.all { it.toEvent() is SignalEvent.AvailabilityChanged })

        // 계속 끊겨 있으면 같은 것을 되풀이해 내보내지 않는다.
        assertTrue(seq.onProbe(stamp(), dead, stamp()).changes.isEmpty())
    }

    @Test
    fun `끊겼다 다시 읽히면 값은 반영하지 않고 기준값을 다시 읽으라고 알린다`() {
        baseline(SEAT to on(true))
        seq.onProbe(stamp(), mapOf(SEAT to VehicleReading.UNAVAILABLE), stamp())
        assertEquals(LinkStatus.UNAVAILABLE, seq.status)

        val result = seq.onProbe(stamp(), mapOf(SEAT to on(true)), stamp())
        assertTrue(result.recovered)
        assertTrue(result.changes.isEmpty(), "복구된 값은 변경이 아니라 기준값으로 다시 읽는다")
        assertEquals(LinkStatus.RECEIVING, seq.status)
        assertEquals(VehicleReading.UNAVAILABLE, seq.latestOf(SEAT))

        // 기준값을 다시 읽으면 그 값이 최신이 된다.
        val again = baseline(SEAT to on(true))
        assertEquals(on(true), again.levels[SEAT])
        assertEquals(on(true), seq.latestOf(SEAT))
    }

    @Test
    fun `첫 조회에서 하나도 읽지 못하면 연결 불가로 시작한다`() {
        assertEquals(LinkStatus.CONNECTING, seq.status)
        baseline(DOOR to VehicleReading.UNAVAILABLE, SEAT to VehicleReading.UNAVAILABLE)
        assertEquals(LinkStatus.UNAVAILABLE, seq.status)
    }

    @Test
    fun `주기적 확인이 놓친 변경을 찾으면 변경으로 내보내고 같은 값이면 내보내지 않는다`() {
        baseline(DOOR to on(false), SEAT to on(false))
        val same = seq.onProbe(stamp(), mapOf(DOOR to on(false), SEAT to on(false)), stamp())
        assertTrue(same.changes.isEmpty())

        val start = stamp()
        val answered = stamp()
        val missed = seq.onProbe(start, mapOf(DOOR to on(false), SEAT to on(true)), answered)
        assertEquals(listOf(SEAT), missed.changes.map { it.kind })
        assertEquals(answered, missed.changes.single().stamp)
    }

    @Test
    fun `주기적 확인을 시작한 뒤에 구독 값이 들어온 신호에는 그 답을 쓰지 않는다`() {
        baseline(SEAT to on(false), DOOR to on(false))
        val start = stamp() // 확인 시작: 이때 서비스는 "빈 좌석"으로 답할 참이다
        assertEquals(on(true), seq.onListener(SEAT, on(true), stamp())?.reading) // 그 사이 착좌
        val result = seq.onProbe(start, mapOf(SEAT to on(false), DOOR to on(false)), stamp())

        assertTrue(result.changes.isEmpty(), "오래된 답이 최신 값을 덮어쓰지 않는다")
        assertEquals(on(true), seq.latestOf(SEAT))
        // 중복 제거 기준도 그대로다: 실제로 내리면 변경으로 나간다.
        assertEquals(on(false), seq.onListener(SEAT, on(false), stamp())?.reading)
    }

    @Test
    fun `더 나중 조회의 답을 반영한 뒤에는 앞서 시작한 확인의 답을 쓰지 않는다`() {
        baseline(SEAT to on(false))
        val oldStart = stamp()
        baseline(SEAT to on(true)) // 더 나중에 시작한 조회가 먼저 반영됐다
        val result = seq.onProbe(oldStart, mapOf(SEAT to on(false)), stamp())
        assertTrue(result.changes.isEmpty())
        assertEquals(on(true), seq.latestOf(SEAT))
    }
}
