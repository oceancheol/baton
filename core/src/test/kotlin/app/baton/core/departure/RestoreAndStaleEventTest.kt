package app.baton.core.departure

import app.baton.core.FakeClock
import app.baton.core.departure.DepartureState.ANNOUNCING
import app.baton.core.departure.DepartureState.BOARDING
import app.baton.core.departure.DepartureState.DEPARTING
import app.baton.core.departure.DepartureState.PARKED
import app.baton.core.departure.DepartureState.VACATED
import app.baton.core.signal.LocationFix
import app.baton.core.signal.LocationQuality
import app.baton.core.signal.SignalAvailability
import app.baton.core.signal.SignalEvent.BrakePedal
import app.baton.core.signal.SignalEvent.DistanceFromSpot
import app.baton.core.signal.SignalEvent.DriverDoor
import app.baton.core.signal.SignalEvent.DriverSeat
import app.baton.core.signal.SignalEvent.ParkingBrakeTelltale
import app.baton.core.signal.SignalReading
import app.baton.core.signal.SignalSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 스냅샷 복원(초기값을 변화로 세지 않음), 오래된 신호 거부, 주행 중 '예고 안 함' 거부, 로그 보관. */
class RestoreAndStaleEventTest {
    private val clock = FakeClock()

    @Test
    fun `복원 - 아무도 타지 않은 주차 상태의 초기값은 어떤 전이도 만들지 않는다`() {
        val sm = DepartureStateMachine(clock)
        val produced = sm.restore(snapshotOf(clock))
        assertEquals(PARKED, sm.currentState)
        assertFalse(produced.any { it.isTransition })
        assertFalse(sm.snapshot.value.doorCycleSeen, "닫힌 문 초기값은 '열렸다 닫힘'이 아니다")
        assertTrue(sm.snapshot.value.autoAnnounce.enabled)
        assertEquals(SignalAvailability.AVAILABLE, sm.snapshot.value.door.availability)
    }

    @Test
    fun `복원 - 이미 착좌, 문 닫힘, 주차 브레이크 해제 상태에서 구독을 시작한 경우`() {
        val sm = DepartureStateMachine(clock)
        val produced = sm.restore(
            snapshotOf(clock, doorOpen = false, seated = true, belted = true, parkingBrakeOn = false),
        )
        // 착좌는 상태 조건이라 탑승으로 복원되고 예고가 시작된다(120초는 지금부터).
        assertEquals(
            listOf(PARKED to BOARDING, BOARDING to ANNOUNCING),
            produced.filter { it.isTransition }.map { it.from to it.to },
        )
        assertEquals(TransitionReason.RESTORED_OCCUPIED, produced.first { it.isTransition }.reason)
        val snap = sm.snapshot.value
        assertEquals(clock.nowMillis() + 120_000L, snap.cancelAtMillis)
        // 초기값은 변화가 아니다.
        assertFalse(snap.doorCycleSeen)
        assertFalse(snap.brakeReleaseSeen, "이미 꺼져 있던 경고등은 '방금 해제'가 아니다")
        assertEquals(ANNOUNCING, sm.currentState, "해제 변화를 못 봤으므로 꺼져 있다는 것만으로 출차 임박이 아니다")

        // 꺼진 상태 + 이동 시작으로 출차 임박을 판정한다.
        clock.advanceSeconds(5)
        sm.onSignal(DistanceFromSpot(1.5))
        assertEquals(DEPARTING, sm.currentState)
        assertEquals(TransitionReason.BRAKE_OFF_AND_MOVE, sm.log().last { it.isTransition }.reason)
        sm.onSignal(DistanceFromSpot(11.0))
        assertEquals(VACATED, sm.currentState)
    }

    @Test
    fun `복원 - 이미 착좌했고 경고등이 꺼져 있어도 이후의 페달 밟음은 변화로 받는다`() {
        val sm = DepartureStateMachine(clock)
        sm.restore(snapshotOf(clock, seated = true, belted = true, parkingBrakeOn = false))
        sm.onSignal(BrakePedal(pressed = true))
        assertEquals(DEPARTING, sm.currentState)
        assertEquals(TransitionReason.BRAKE_PEDAL, sm.log().last { it.isTransition }.reason)
    }

    @Test
    fun `복원 - 앉아 있지만 벨트를 매지 않았으면 탑승 확인까지만 간다`() {
        val sm = DepartureStateMachine(clock)
        sm.restore(snapshotOf(clock, seated = true, belted = false))
        assertEquals(BOARDING, sm.currentState)
    }

    @Test
    fun `복원 - 문이 열린 채로 시작했다면 닫힐 때 한 번의 여닫힘으로 본다`() {
        val sm = DepartureStateMachine(clock)
        sm.restore(snapshotOf(clock, doorOpen = true))
        assertEquals(PARKED, sm.currentState)
        sm.onSignal(DriverDoor(open = false))
        assertEquals(BOARDING, sm.currentState)
        assertTrue(sm.snapshot.value.doorCycleSeen)
    }

    @Test
    fun `복원 - 스냅샷이 미지원과 일시 불가를 가려 주면 그대로 반영한다`() {
        val sm = DepartureStateMachine(clock)
        sm.restore(
            SignalSnapshot(
                door = SignalReading.unsupported(),
                seat = SignalReading.available(false),
                seatBelt = SignalReading.unavailable(),
                parkingBrake = SignalReading.unknown(),
                brakePedal = SignalReading.available(false),
                location = SignalReading.available(LocationFix(0.0, LocationQuality.LOW)),
                takenAtMillis = clock.nowMillis(),
            ),
        )
        val snap = sm.snapshot.value
        assertEquals(SignalAvailability.UNSUPPORTED, snap.door.availability)
        assertEquals(SignalAvailability.UNAVAILABLE, snap.seatBelt.availability)
        assertEquals(SignalAvailability.UNKNOWN, snap.parkingBrake.availability)
        assertEquals(LocationQuality.LOW, snap.location.usableValue?.quality)
        assertEquals(BOARDING, sm.currentState, "문 미지원: 착좌로 판정")
        sm.onSignal(DriverSeat(occupied = true))
        assertEquals(ANNOUNCING, sm.currentState, "벨트 일시 불가: 착좌만 사용")
        assertEquals(LocationQuality.LOW, sm.log().last { it.isTransition }.locationQuality)
    }

    @Test
    fun `복원 - 예고 중에 다시 읽었더니 착좌가 풀려 있으면 그때부터 60초를 센다`() {
        val sm = parkedMachine(clock)
        sm.doorCycle(clock)
        sm.sitAndBelt()
        clock.advanceSeconds(10)
        sm.restore(snapshotOf(clock, seated = false, belted = true))
        assertEquals(ANNOUNCING, sm.currentState)
        assertEquals(clock.nowMillis() + 60_000L, sm.snapshot.value.cancelAtMillis)
    }

    @Test
    fun `오래된 관측 시각의 신호는 거부한다`() {
        val sm = parkedMachine(clock)
        sm.doorCycle(clock)
        val seatedAt = clock.nowMillis()
        sm.onSignal(DriverSeat(occupied = true, observedAtMillis = seatedAt))
        clock.advanceSeconds(1)
        val produced = sm.onSignal(DriverSeat(occupied = false, observedAtMillis = seatedAt - 500))
        assertEquals(true, sm.snapshot.value.seat.usableValue, "늦게 도착한 옛 값이 최신 값을 덮지 못한다")
        assertEquals(TransitionReason.STALE_EVENT_REJECTED, produced.single().reason)
        assertEquals(1, sm.snapshot.value.rejectedEventCount)
    }

    @Test
    fun `이전 주차 세대의 신호는 거부한다 - 재주차 직후 옛 기준점의 거리가 와도 비웠다고 보지 않는다`() {
        val sm = parkedMachine(clock)
        val oldGeneration = sm.snapshot.value.parkingGeneration
        sm.startNewParking(snapshotOf(clock))
        sm.doorCycle(clock)
        sm.sitAndBelt()
        sm.onSignal(ParkingBrakeTelltale(on = false))
        assertEquals(DEPARTING, sm.currentState)

        sm.onSignal(DistanceFromSpot(40.0, parkingGeneration = oldGeneration))
        assertEquals(DEPARTING, sm.currentState)
        assertEquals(1, sm.snapshot.value.rejectedEventCount)

        sm.onSignal(DistanceFromSpot(40.0, parkingGeneration = sm.snapshot.value.parkingGeneration))
        assertEquals(VACATED, sm.currentState)
    }

    @Test
    fun `새 주차 이전에 관측한 거리는 세대 표시가 없어도 관측 시각으로 거부한다`() {
        val sm = parkedMachine(clock)
        val before = clock.nowMillis()
        clock.advanceSeconds(5)
        sm.startNewParking()
        sm.onSignal(DistanceFromSpot(40.0, observedAtMillis = before))
        assertFalse(sm.snapshot.value.movedSeen)
        assertEquals(1, sm.snapshot.value.rejectedEventCount)
    }

    @Test
    fun `예고 안 함 - 차가 움직이는 중에는 받지 않는다`() {
        val sm = parkedMachine(clock)
        sm.doorCycle(clock)
        sm.sitAndBelt()
        assertTrue(sm.snapshot.value.canSuppress)

        // 브레이크 신호 없이 이동만 관측된 ANNOUNCING
        sm.onSignal(DistanceFromSpot(3.0))
        assertEquals(ANNOUNCING, sm.currentState)
        assertFalse(sm.snapshot.value.canSuppress)
        val produced = sm.suppressThisParking()
        assertEquals(TransitionReason.SUPPRESS_REJECTED, produced.single().reason)
        assertEquals(ANNOUNCING, sm.currentState)
        assertFalse(sm.snapshot.value.suppressed)

        // 멈춰 있어도 DEPARTING 이후에는 받지 않는다
        sm.onSignal(DistanceFromSpot(0.0))
        assertTrue(sm.snapshot.value.canSuppress)
        sm.onSignal(ParkingBrakeTelltale(on = false))
        assertEquals(DEPARTING, sm.currentState)
        assertFalse(sm.snapshot.value.canSuppress)
        sm.suppressThisParking()
        assertEquals(DEPARTING, sm.currentState)
        assertFalse(sm.snapshot.value.suppressed)
    }

    @Test
    fun `로그는 보관량을 넘기면 오래된 것부터 버리고 순번으로 새 줄만 읽는다`() {
        val sm = DepartureStateMachine(clock, config = DepartureConfig(maxLogEvents = 10))
        sm.restore(snapshotOf(clock))
        val readUpTo = sm.log().last().seq
        repeat(40) {
            sm.onSignal(DriverDoor(open = true))
            sm.onSignal(DriverDoor(open = false)) // BOARDING
            sm.suppressThisParking() // PARKED 로 취소
            sm.startNewParking()
        }
        assertEquals(10, sm.log().size)
        val seqs = sm.log().map { it.seq }
        assertEquals(seqs.sorted(), seqs)
        assertEquals((seqs.first()..seqs.last()).toList(), seqs, "순번은 빠짐없이 이어진다")
        assertTrue(seqs.first() > readUpTo)

        val last = sm.log().last().seq
        assertTrue(sm.eventsSince(last).isEmpty())
        val produced = sm.onSignal(DriverDoor(open = true)) + sm.onSignal(DriverDoor(open = false))
        assertEquals(produced, sm.eventsSince(last), "반환값과 순번 읽기가 같은 기록을 준다")
    }

    // ---- 오래된 스냅샷 (2차 검수 (2)-1) -----------------------------------

    @Test
    fun `검수 재현 - 새 주차 뒤 늦게 돌아온 이전 스냅샷은 비었음으로 만들지 못한다`() {
        val sm = parkedMachine(clock)
        // t=0: 이전 주차에서 읽어 둔 값(앉아 있고, 벨트 매고, 주차 브레이크 풀고, 40m 벗어남)
        val old = snapshotOf(clock, seated = true, belted = true, parkingBrakeOn = false, meters = 40.0)
        clock.advanceSeconds(5)
        // t=5초: 새 주차. 지금 값은 빈 좌석, 0m
        sm.startNewParking(snapshotOf(clock, seated = false, meters = 0.0))
        assertEquals(PARKED, sm.currentState)

        val produced = sm.restore(old)

        assertEquals(PARKED, sm.currentState, "늦은 옛 스냅샷으로 VACATED 까지 가면 안 된다")
        assertFalse(produced.any { it.isTransition })
        assertEquals(listOf(TransitionReason.STALE_SNAPSHOT_REJECTED), produced.map { it.reason })
        val snap = sm.snapshot.value
        assertEquals(false, snap.seat.usableValue)
        assertEquals(false, snap.seatBelt.usableValue)
        assertEquals(true, snap.parkingBrake.usableValue)
        assertEquals(0.0, snap.location.usableValue?.metersFromSpot)
        assertEquals(1, snap.rejectedSnapshotCount)
    }

    @Test
    fun `스냅샷 없이 새 주차를 시작한 뒤에도 그 이전에 읽은 스냅샷은 받지 않는다`() {
        val sm = parkedMachine(clock)
        val old = snapshotOf(clock, seated = true, belted = true, parkingBrakeOn = false, meters = 40.0)
        clock.advanceSeconds(5)
        sm.startNewParking()
        sm.restore(old)
        assertEquals(PARKED, sm.currentState)
        assertEquals(false, sm.snapshot.value.seat.usableValue)
        assertEquals(1, sm.snapshot.value.rejectedSnapshotCount)
    }

    @Test
    fun `조회 중에 주차가 바뀌면 그 답은 관측 시각이 새로워도 세대가 달라 거부한다`() {
        val sm = parkedMachine(clock)
        val request = sm.newSnapshotRequest() // 이전 주차에서 시작한 조회
        clock.advanceSeconds(5)
        sm.startNewParking()
        clock.advanceSeconds(1)
        // 답은 새 주차 뒤에 만들어졌지만(관측 시각은 최신) 이전 주차 기준으로 읽은 값이다.
        val late = snapshotOf(clock, seated = true, belted = true, parkingBrakeOn = false, meters = 40.0)
            .answering(request)
        val produced = sm.restore(late)
        assertEquals(PARKED, sm.currentState)
        assertEquals(TransitionReason.STALE_SNAPSHOT_REJECTED, produced.single().reason)
        assertEquals(0.0, sm.snapshot.value.location.usableValue?.metersFromSpot)
    }

    @Test
    fun `새 주차는 세대를 한 번만 올리고 같은 세대의 조회 답으로 복원한다`() {
        val sm = parkedMachine(clock)
        val before = sm.snapshot.value.parkingGeneration
        sm.startNewParking() // 세대 증가는 여기 한 번
        val request = sm.newSnapshotRequest()
        assertEquals(before + 1, request.parkingGeneration)
        clock.advanceSeconds(1)
        val produced = sm.restore(snapshotOf(clock, seated = true, belted = false).answering(request))
        assertEquals(before + 1, sm.snapshot.value.parkingGeneration, "복원은 세대를 올리지 않는다")
        assertEquals(TransitionReason.RESTORED_OCCUPIED, produced.first { it.isTransition }.reason)
        assertEquals(BOARDING, sm.currentState)
        assertEquals(0, sm.snapshot.value.rejectedSnapshotCount)
    }

    @Test
    fun `더 나중 조회의 답을 반영한 뒤에 도착한 앞선 조회의 답은 거부한다`() {
        val sm = parkedMachine(clock)
        val first = sm.newSnapshotRequest()
        val second = sm.newSnapshotRequest()
        clock.advanceSeconds(1)
        sm.restore(snapshotOf(clock, doorOpen = false).answering(second))
        clock.advanceSeconds(1)
        val produced = sm.restore(snapshotOf(clock, seated = true, belted = true).answering(first))
        assertEquals(PARKED, sm.currentState)
        assertEquals(TransitionReason.STALE_SNAPSHOT_REJECTED, produced.single().reason)
        // 같은 답을 두 번 넣어도 두 번째는 받지 않는다.
        sm.restore(snapshotOf(clock).answering(second))
        assertEquals(2, sm.snapshot.value.rejectedSnapshotCount)
    }

    @Test
    fun `스냅샷보다 나중에 관측한 신호는 스냅샷이 덮어쓰지 못하고 나머지 신호만 반영한다`() {
        val sm = parkedMachine(clock)
        val taken = snapshotOf(clock, doorOpen = true, seated = false) // t=0 에 읽음
        clock.advanceSeconds(2)
        sm.onSignal(DriverSeat(occupied = true, observedAtMillis = clock.nowMillis())) // t=2 의 더 새 관측
        clock.advanceSeconds(1)

        sm.restore(taken) // t=3 에 늦게 도착

        val snap = sm.snapshot.value
        assertEquals(true, snap.seat.usableValue, "착좌의 관측 시각이 역행하지 않는다")
        assertEquals(true, snap.door.usableValue, "문은 스냅샷이 더 새 관측이라 반영한다")
        assertEquals(0, snap.rejectedSnapshotCount)
        // 그 스냅샷 시각보다 오래된 문 신호는 이제 받지 않는다.
        sm.onSignal(DriverDoor(open = false, observedAtMillis = taken.takenAtMillis - 1))
        assertEquals(true, sm.snapshot.value.door.usableValue)
    }

    @Test
    fun `새 주차에 딸려 온 스냅샷도 세대가 다르면 쓰지 않고 스냅샷 없는 새 주차로 처리한다`() {
        val sm = parkedMachine(clock)
        sm.onSignal(DistanceFromSpot(6.0))
        val stale = sm.newSnapshotRequest() // 지금(이전) 세대의 조회
        clock.advanceSeconds(1)
        val produced = sm.startNewParking(snapshotOf(clock, seated = true, belted = true, meters = 40.0).answering(stale))
        assertEquals(PARKED, sm.currentState)
        assertTrue(produced.any { it.reason == TransitionReason.STALE_SNAPSHOT_REJECTED })
        assertEquals(0.0, sm.snapshot.value.location.usableValue?.metersFromSpot, "이전 기준점의 거리는 버린다")
        assertEquals(false, sm.snapshot.value.seat.usableValue)
    }
}
