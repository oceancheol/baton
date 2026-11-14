package app.baton.core.departure

import app.baton.core.FakeClock
import app.baton.core.departure.DepartureState.ANNOUNCING
import app.baton.core.departure.DepartureState.BOARDING
import app.baton.core.departure.DepartureState.PARKED
import app.baton.core.signal.SignalAvailability
import app.baton.core.signal.SignalCapabilities
import app.baton.core.signal.SignalEvent.AvailabilityChanged
import app.baton.core.signal.SignalEvent.DriverDoor
import app.baton.core.signal.SignalEvent.DriverSeat
import app.baton.core.signal.SignalKind
import app.baton.core.signal.SignalReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실제 신호 소스처럼 현재 값을 비동기로 읽는 경우의 새 주차:
 * `startNewParking(awaitBaseline = true)` 뒤에 오는 `restore()`는 "최초 연결 복원"이 아니라 "새 주차의 기준값"이다.
 */
class NewParkingBaselineTest {
    private val clock = FakeClock()

    /** 벨트를 판정에서 뺀 차(에뮬레이터 시연 설정과 같다): 착좌만으로 예고한다. */
    private fun announcingWithoutBelt(): DepartureStateMachine {
        val sm = parkedMachine(clock, SignalCapabilities(seatBelt = false))
        sm.doorCycle(clock)
        sm.onSignal(DriverSeat(occupied = true))
        assertEquals(ANNOUNCING, sm.currentState)
        return sm
    }

    private fun DepartureStateMachine.restoreSeated(
        doorOpen: Boolean = false,
        reconnect: Boolean = false,
    ): List<DepartureLogEvent> {
        val request = newSnapshotRequest()
        clock.advanceSeconds(1)
        return restore(
            snapshotOf(clock, doorOpen = doorOpen, seated = true)
                .copy(seatBelt = SignalReading.unsupported())
                .answering(request),
            reconnect,
        )
    }

    @Test
    fun `새 주차의 기준값 복원은 계속 앉아 있다는 이유만으로 예고하지 않는다`() {
        val sm = announcingWithoutBelt()
        sm.startNewParking(awaitBaseline = true)
        val produced = sm.restoreSeated()

        assertEquals(PARKED, sm.currentState)
        assertTrue(produced.none { it.isTransition }, "기준값 복원은 전이를 내지 않는다")
        clock.advanceSeconds(600)
        sm.tick()
        assertEquals(PARKED, sm.currentState)

        sm.doorCycle(clock)
        assertEquals(ANNOUNCING, sm.currentState, "문 여닫힘 뒤에는 착좌 조건을 다시 본다")
    }

    @Test
    fun `기준값을 받은 뒤 재연결 표시 없이 넣는 다음 복원은 연결 복원이다 - 앉아 있으면 탑승으로 복원한다`() {
        val sm = announcingWithoutBelt()
        sm.startNewParking(awaitBaseline = true)
        sm.restoreSeated()
        assertEquals(PARKED, sm.currentState)

        // 기준값 대기는 한 번의 복원으로 끝난다. 실행 중 재연결은 호출하는 쪽이 reconnect = true 로 구분한다(아래 시험).
        val produced = sm.restoreSeated()
        assertEquals(TransitionReason.RESTORED_OCCUPIED, produced.first { it.isTransition }.reason)
        assertEquals(ANNOUNCING, sm.currentState)
    }

    @Test
    fun `재연결 복원은 앉아만 있어서는 예고하지 않고 문 여닫힘 뒤에 예고한다`() {
        val sm = announcingWithoutBelt()
        // 서비스 단절: 착좌를 읽지 못해 예고가 취소된다.
        sm.onSignal(AvailabilityChanged(SignalKind.SEAT, SignalAvailability.UNAVAILABLE))
        assertEquals(PARKED, sm.currentState)

        val produced = sm.restoreSeated(reconnect = true)
        assertEquals(PARKED, sm.currentState, "끊긴 동안 취소된 예고가 복구만으로 다시 시작되지 않는다")
        assertTrue(produced.none { it.isTransition })
        clock.advanceSeconds(600)
        sm.tick()
        assertEquals(PARKED, sm.currentState)

        sm.doorCycle(clock)
        assertEquals(ANNOUNCING, sm.currentState)
    }

    @Test
    fun `차량 신호가 모두 끊겼다 다시 붙으면 - 끊긴 동안 대체 규칙으로 탑승 단계가 됐어도 앉아만 있어서는 예고하지 않는다`() {
        val sm = announcingWithoutBelt()
        // 서비스 단절: 문·착좌를 차례로 읽지 못한다. 문을 읽지 못하면 "문 없이 착좌로 판정"하는 대체 규칙이 걸린다.
        sm.onSignal(AvailabilityChanged(SignalKind.DOOR, SignalAvailability.UNAVAILABLE))
        val lost = sm.onSignal(AvailabilityChanged(SignalKind.SEAT, SignalAvailability.UNAVAILABLE))
        assertEquals(TransitionReason.MIN_SIGNAL_LOST, lost.first { it.isTransition }.reason)
        assertEquals(BOARDING, sm.currentState)

        val produced = sm.restoreSeated(reconnect = true)
        assertEquals(TransitionReason.RECONNECTED_OCCUPIED, produced.single { it.isTransition }.reason)
        assertEquals(PARKED, sm.currentState)
        clock.advanceSeconds(600)
        sm.tick()
        assertEquals(PARKED, sm.currentState)

        sm.doorCycle(clock)
        assertEquals(ANNOUNCING, sm.currentState)
    }

    @Test
    fun `재연결 복원 때 내려 있으면 탑승 단계를 그대로 둔다 - 그 뒤의 착좌는 본 변화라 예고한다`() {
        val sm = parkedMachine(clock, SignalCapabilities(seatBelt = false))
        sm.doorCycle(clock)
        assertEquals(BOARDING, sm.currentState)
        val request = sm.newSnapshotRequest()
        clock.advanceSeconds(1)
        sm.restore(snapshotOf(clock).copy(seatBelt = SignalReading.unsupported()).answering(request), reconnect = true)
        assertEquals(BOARDING, sm.currentState)
        sm.onSignal(DriverSeat(occupied = true))
        assertEquals(ANNOUNCING, sm.currentState)
    }

    @Test
    fun `새 주차로 주차 중일 때의 재연결 복원도 앉아 있다는 이유로 탑승으로 복원하지 않는다`() {
        val sm = announcingWithoutBelt()
        sm.startNewParking(awaitBaseline = true)
        sm.restoreSeated()
        assertEquals(PARKED, sm.currentState)

        sm.restoreSeated(reconnect = true)
        assertEquals(PARKED, sm.currentState)
        sm.doorCycle(clock)
        assertEquals(ANNOUNCING, sm.currentState)
    }

    @Test
    fun `재연결 복원은 지금 상태를 유지한다 - 예고 중이면 예고 중 그대로다`() {
        val sm = announcingWithoutBelt()
        val produced = sm.restoreSeated(reconnect = true)
        assertTrue(produced.none { it.isTransition })
        assertEquals(ANNOUNCING, sm.currentState)
    }

    @Test
    fun `최초 연결 복원은 기존 규칙 그대로 - 이미 앉아 있으면 탑승한 상태로 복원한다`() {
        val sm = DepartureStateMachine(clock, SignalCapabilities(seatBelt = false))
        sm.startNewParking() // 기다리지 않는 기존 호출
        val produced = sm.restoreSeated()
        assertEquals(TransitionReason.RESTORED_OCCUPIED, produced.first { it.isTransition }.reason)
        assertEquals(ANNOUNCING, sm.currentState)
    }

    @Test
    fun `기준값을 기다리는 동안 본 문 여닫힘은 기준값을 받은 뒤 탑승으로 이어진다`() {
        val sm = announcingWithoutBelt()
        sm.startNewParking(awaitBaseline = true)
        clock.advanceSeconds(1)
        sm.onSignal(DriverDoor(open = true, observedAtMillis = clock.nowMillis()))
        clock.advanceSeconds(1)
        sm.onSignal(DriverDoor(open = false, observedAtMillis = clock.nowMillis()))
        assertEquals(PARKED, sm.currentState, "기준값을 받기 전에는 판정하지 않는다")

        // 조회는 문 여닫힘 전에 시작됐다: 문은 더 새 관측이 있어 그대로 두고, 나머지는 기준값으로 받는다.
        val request = sm.newSnapshotRequest()
        val snapshot = snapshotOf(clock, seated = true).copy(
            seatBelt = SignalReading.unsupported(),
            takenAtMillis = clock.nowMillis() - 1_500,
        )
        sm.restore(snapshot.answering(request))
        assertEquals(ANNOUNCING, sm.currentState)
        assertEquals(TransitionReason.DOOR_OPEN_CLOSE, sm.log().first { it.to == BOARDING && it.isTransition && it.parkingGeneration == sm.snapshot.value.parkingGeneration }.reason)
    }

    @Test
    fun `문 신호가 없는 차 - 새 주차의 기준값이 착좌이면 한 번 내렸다 타야 예고한다`() {
        val sm = parkedMachine(clock, SignalCapabilities(door = false, seatBelt = false))
        sm.onSignal(DriverSeat(occupied = true))
        assertEquals(ANNOUNCING, sm.currentState)
        sm.onSignal(DriverSeat(occupied = false)) // 옛 값: 내려 있음
        sm.startNewParking(awaitBaseline = true)
        assertEquals(PARKED, sm.currentState, "옛 값(내려 있음)으로 먼저 탑승 판정을 하지 않는다")

        val request = sm.newSnapshotRequest()
        clock.advanceSeconds(1)
        sm.restore(
            snapshotOf(clock, seated = true)
                .copy(door = SignalReading.unsupported(), seatBelt = SignalReading.unsupported())
                .answering(request),
        )
        assertEquals(PARKED, sm.currentState)
        sm.onSignal(DriverSeat(occupied = false))
        assertEquals(BOARDING, sm.currentState)
        sm.onSignal(DriverSeat(occupied = true))
        assertEquals(ANNOUNCING, sm.currentState)
    }

    @Test
    fun `거부된 스냅샷은 기준값으로 치지 않는다 - 다음에 반영되는 복원이 기준값이다`() {
        val sm = announcingWithoutBelt()
        val stale = sm.newSnapshotRequest() // 이전 주차에서 시작한 조회
        sm.startNewParking(awaitBaseline = true)
        clock.advanceSeconds(1)
        sm.restore(snapshotOf(clock, seated = true).copy(seatBelt = SignalReading.unsupported()).answering(stale))
        assertEquals(1, sm.snapshot.value.rejectedSnapshotCount)
        assertEquals(PARKED, sm.currentState)

        sm.restoreSeated()
        assertEquals(PARKED, sm.currentState, "이번 복원이 새 주차의 기준값이다")
    }

    @Test
    fun `예고 중에 착좌 신호를 읽지 못하게 되면 예고를 취소하고 돌아와도 앉아만 있어서는 다시 예고하지 않는다`() {
        val sm = announcingWithoutBelt()
        val produced = sm.onSignal(AvailabilityChanged(SignalKind.SEAT, SignalAvailability.UNAVAILABLE))
        assertEquals(TransitionReason.MIN_SIGNAL_LOST, produced.first { it.isTransition }.reason)
        assertEquals(PARKED, sm.currentState)

        sm.onSignal(DriverSeat(occupied = true)) // 신호가 돌아옴(개별 신호 복구)
        assertEquals(PARKED, sm.currentState)
    }
}
