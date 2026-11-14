package app.baton.core.departure

import app.baton.core.FakeClock
import app.baton.core.departure.DepartureState.ANNOUNCING
import app.baton.core.departure.DepartureState.BOARDING
import app.baton.core.departure.DepartureState.DEPARTING
import app.baton.core.departure.DepartureState.PARKED
import app.baton.core.departure.DepartureState.VACATED
import app.baton.core.signal.SignalCapabilities
import app.baton.core.signal.SignalEvent.BrakePedal
import app.baton.core.signal.SignalEvent.DistanceFromSpot
import app.baton.core.signal.SignalEvent.DriverDoor
import app.baton.core.signal.SignalEvent.DriverSeat
import app.baton.core.signal.SignalEvent.DriverSeatBelt
import app.baton.core.signal.SignalEvent.ParkingBrakeTelltale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DepartureStateMachineTest {
    private val clock = FakeClock()

    private fun machine(caps: SignalCapabilities = SignalCapabilities.ALL) =
        DepartureStateMachine(clock, caps).also { sm ->
            // 주차해 둔 차의 기본 모습
            sm.onSignal(ParkingBrakeTelltale(on = true))
            sm.onSignal(DriverDoor(open = false))
            sm.onSignal(DriverSeat(occupied = false))
            sm.onSignal(DriverSeatBelt(fastened = false))
            sm.onSignal(BrakePedal(pressed = false))
            sm.onSignal(DistanceFromSpot(0.0))
        }

    private fun DepartureStateMachine.board() {
        onSignal(DriverDoor(open = true))
        clock.advanceSeconds(3)
        onSignal(DriverDoor(open = false))
    }

    private fun DepartureStateMachine.sit() {
        onSignal(DriverSeat(occupied = true))
        onSignal(DriverSeatBelt(fastened = true))
    }

    private fun DepartureStateMachine.transitions() =
        log().filter { it.isTransition }.map { Triple(it.from, it.to, it.reason) }

    // ---- 정상 흐름 --------------------------------------------------------

    @Test
    fun `정상 흐름 - 문, 착좌, 주차 브레이크 해제, 이동 순서로 VACATED 까지 간다`() {
        val sm = machine()
        assertEquals(PARKED, sm.currentState)

        sm.onSignal(DriverDoor(open = true))
        assertEquals(PARKED, sm.currentState, "문이 열리기만 해서는 탑승이 아니다")
        sm.onSignal(DriverDoor(open = false))
        assertEquals(BOARDING, sm.currentState)

        sm.onSignal(DriverSeat(occupied = true))
        assertEquals(BOARDING, sm.currentState, "벨트가 읽히는 차는 벨트까지 매야 예고한다")
        sm.onSignal(DriverSeatBelt(fastened = true))
        assertEquals(ANNOUNCING, sm.currentState)

        clock.advanceSeconds(10)
        sm.onSignal(ParkingBrakeTelltale(on = false))
        assertEquals(DEPARTING, sm.currentState)

        clock.advanceSeconds(5)
        sm.onSignal(DistanceFromSpot(4.0))
        assertEquals(DEPARTING, sm.currentState, "조금 움직인 것으로는 비었다고 보지 않는다")
        sm.onSignal(DistanceFromSpot(12.0))
        assertEquals(VACATED, sm.currentState)

        assertEquals(
            listOf(
                Triple(PARKED, BOARDING, TransitionReason.DOOR_OPEN_CLOSE),
                Triple(BOARDING, ANNOUNCING, TransitionReason.SEATED_AND_BELTED),
                Triple(ANNOUNCING, DEPARTING, TransitionReason.PARKING_BRAKE_RELEASED),
                Triple(DEPARTING, VACATED, TransitionReason.LEFT_SPOT),
            ),
            sm.transitions(),
        )
    }

    @Test
    fun `정상 흐름 - 주차 브레이크 대신 브레이크 페달만 밟아도 출차 임박이 된다`() {
        val sm = machine()
        sm.board()
        sm.sit()
        sm.onSignal(BrakePedal(pressed = true))
        assertEquals(DEPARTING, sm.currentState)
        assertEquals(TransitionReason.BRAKE_PEDAL, sm.log().last().reason)
    }

    @Test
    fun `스냅샷에는 상태 순서로 합성한 값이 아니라 실제 관측값이 실린다`() {
        val sm = machine()
        sm.board()
        sm.sit()
        val snap = sm.snapshot.value
        assertEquals(ANNOUNCING, snap.state)
        assertTrue(snap.doorCycleSeen)
        assertEquals(true, snap.seat.usableValue)
        assertEquals(true, snap.seatBelt.usableValue)
        assertEquals(true, snap.parkingBrake.usableValue, "경고등은 아직 켜져 있다")
        assertFalse(snap.brakeReleaseSeen)
        assertFalse(snap.pedalPressSeen)
        assertFalse(snap.movedSeen)
        assertEquals(clock.nowMillis(), snap.announcedAtMillis)
        assertEquals(clock.nowMillis() + 120_000L, snap.cancelAtMillis)
        assertEquals(1L, snap.announcementGeneration)

        // 착좌가 풀린 유예 중에는 착좌 값도 풀린 그대로 보인다.
        sm.onSignal(DriverSeat(occupied = false))
        assertEquals(false, sm.snapshot.value.seat.usableValue)
        assertEquals(ANNOUNCING, sm.currentState)
    }

    // ---- 2분 취소 ---------------------------------------------------------

    @Test
    fun `예고 중 120초 동안 다음 단계가 없으면 PARKED 로 되돌린다`() {
        val sm = machine()
        sm.board()
        sm.sit()
        clock.advanceSeconds(119)
        sm.tick()
        assertEquals(ANNOUNCING, sm.currentState)
        clock.advanceSeconds(1)
        sm.tick()
        assertEquals(PARKED, sm.currentState)
        assertEquals(TransitionReason.TIMEOUT_NO_PROGRESS, sm.log().last().reason)
        assertNull(sm.snapshot.value.cancelAtMillis)
    }

    @Test
    fun `120초는 예고 시작부터 한 번만 센다 - 출차 임박으로 넘어가도 다시 세지 않는다`() {
        val sm = machine()
        sm.board()
        sm.sit()
        val announcedAt = clock.nowMillis()
        clock.advanceSeconds(100)
        sm.onSignal(ParkingBrakeTelltale(on = false))
        assertEquals(DEPARTING, sm.currentState)
        assertEquals(announcedAt + 120_000L, sm.snapshot.value.cancelAtMillis, "단계가 바뀌어도 취소 시각은 그대로다")
        clock.advanceSeconds(19)
        sm.tick()
        assertEquals(DEPARTING, sm.currentState)
        clock.advanceSeconds(1)
        sm.onSignal(DistanceFromSpot(20.0))
        assertEquals(PARKED, sm.currentState, "시간이 이미 지났으면 늦게 온 신호보다 취소가 먼저다")
        assertEquals(TransitionReason.TIMEOUT_NO_PROGRESS, sm.log().last { it.isTransition }.reason)
    }

    @Test
    fun `취소된 뒤에는 문을 다시 열었다 닫아야 예고가 다시 시작된다`() {
        val sm = machine()
        sm.board()
        sm.sit()
        clock.advanceSeconds(120)
        sm.tick()
        assertEquals(PARKED, sm.currentState)
        sm.onSignal(BrakePedal(pressed = true))
        assertEquals(PARKED, sm.currentState, "취소된 뒤 페달만 밟아서는 다시 예고하지 않는다")
        sm.onSignal(BrakePedal(pressed = false))
        sm.board()
        assertEquals(ANNOUNCING, sm.currentState, "앉아서 벨트를 맨 채로 문만 다시 닫으면 바로 예고")
    }

    // ---- 문 재개방 · 착좌 해제 취소 ----------------------------------------

    @Test
    fun `문이 다시 열리고 60초 안에 닫히지 않으면 취소한다`() {
        val sm = machine()
        sm.board()
        sm.sit()
        clock.advanceSeconds(10)
        sm.onSignal(DriverDoor(open = true))
        assertEquals(clock.nowMillis() + 60_000L, sm.snapshot.value.cancelAtMillis)
        clock.advanceSeconds(59)
        sm.tick()
        assertEquals(ANNOUNCING, sm.currentState)
        clock.advanceSeconds(1)
        sm.tick()
        assertEquals(PARKED, sm.currentState)
        assertEquals(TransitionReason.DOOR_REOPEN_TIMEOUT, sm.log().last().reason)
    }

    @Test
    fun `문이 다시 열려도 60초 안에 닫히면 예고를 유지한다`() {
        val sm = machine()
        sm.board()
        sm.sit()
        sm.onSignal(DriverDoor(open = true))
        clock.advanceSeconds(30)
        sm.onSignal(DriverDoor(open = false))
        clock.advanceSeconds(40)
        sm.tick()
        assertEquals(ANNOUNCING, sm.currentState)
        sm.onSignal(ParkingBrakeTelltale(on = false))
        assertEquals(DEPARTING, sm.currentState)
    }

    @Test
    fun `착좌가 풀리고 60초 안에 돌아오지 않으면 취소한다`() {
        val sm = machine()
        sm.board()
        sm.sit()
        sm.onSignal(DriverSeat(occupied = false))
        clock.advanceSeconds(60)
        sm.tick()
        assertEquals(PARKED, sm.currentState)
        assertEquals(TransitionReason.SEAT_VACATED_TIMEOUT, sm.log().last().reason)
    }

    // ---- 이번엔 예고 안 함 -------------------------------------------------

    @Test
    fun `예고 안 함을 누르면 예고를 취소하고 이번 주차 동안 다시 예고하지 않는다`() {
        val sm = machine()
        sm.board()
        sm.sit()
        assertEquals(ANNOUNCING, sm.currentState)

        sm.suppressThisParking()
        assertEquals(PARKED, sm.currentState)
        assertTrue(sm.snapshot.value.suppressed)
        assertEquals(TransitionReason.SUPPRESSED_BY_DRIVER, sm.log().last().reason)

        sm.board()
        sm.sit()
        sm.onSignal(ParkingBrakeTelltale(on = true))
        sm.onSignal(ParkingBrakeTelltale(on = false))
        sm.onSignal(BrakePedal(pressed = true))
        assertEquals(PARKED, sm.currentState, "신호가 다 들어와도 예고하지 않는다")

        sm.onSignal(DistanceFromSpot(15.0))
        assertEquals(VACATED, sm.currentState)
        assertEquals(TransitionReason.SUPPRESSED_LEFT, sm.log().last().reason)
        assertFalse(sm.log().any { it.isTransition && it.to == ANNOUNCING && it.atMillis > sm.log().first { e -> e.reason == TransitionReason.SUPPRESSED_BY_DRIVER }.atMillis })
    }

    @Test
    fun `주차 중에 미리 예고 안 함을 눌러도 되고 새 주차에서는 다시 예고한다`() {
        val sm = machine()
        sm.suppressThisParking()
        sm.board()
        sm.sit()
        assertEquals(PARKED, sm.currentState)

        sm.onSignal(DriverSeat(occupied = false))
        sm.onSignal(DriverSeatBelt(fastened = false))
        sm.startNewParking()
        assertFalse(sm.snapshot.value.suppressed)
        sm.board()
        sm.sit()
        assertEquals(ANNOUNCING, sm.currentState)
    }

    // ---- 신호 미지원 대체 규칙 (PRD 6장) -----------------------------------

    @Test
    fun `대체 규칙 - 주차 브레이크 경고등 미지원이면 페달만으로는 안 되고 페달과 이동 시작이 함께 있어야 한다`() {
        val sm = machine(SignalCapabilities(parkingBrakeTelltale = false))
        sm.board()
        sm.sit()
        sm.onSignal(ParkingBrakeTelltale(on = false))
        assertEquals(ANNOUNCING, sm.currentState, "미지원 신호는 들어와도 쓰지 않는다")
        sm.onSignal(BrakePedal(pressed = true))
        assertEquals(ANNOUNCING, sm.currentState)
        sm.onSignal(DistanceFromSpot(1.5))
        assertEquals(DEPARTING, sm.currentState)
        assertEquals(TransitionReason.FALLBACK_PEDAL_AND_MOVE, sm.log().last().reason)
        sm.onSignal(DistanceFromSpot(11.0))
        assertEquals(VACATED, sm.currentState)
    }

    @Test
    fun `대체 규칙 - 경고등 미지원에서 이동만 있고 페달이 없으면 출차 임박이 아니다`() {
        val sm = machine(SignalCapabilities(parkingBrakeTelltale = false))
        sm.board()
        sm.sit()
        sm.onSignal(DistanceFromSpot(3.0))
        assertEquals(ANNOUNCING, sm.currentState)
    }

    @Test
    fun `대체 규칙 - 경고등과 페달이 모두 미지원이면 이동 시작으로 판정한다`() {
        val sm = machine(SignalCapabilities(parkingBrakeTelltale = false, brakePedal = false))
        sm.board()
        sm.sit()
        sm.onSignal(BrakePedal(pressed = true))
        assertEquals(ANNOUNCING, sm.currentState)
        sm.onSignal(DistanceFromSpot(2.0))
        assertEquals(DEPARTING, sm.currentState)
        assertEquals(TransitionReason.FALLBACK_MOVE_ONLY, sm.log().last().reason)
    }

    @Test
    fun `대체 규칙 - 안전벨트 미지원이면 착좌만으로 예고한다`() {
        val sm = machine(SignalCapabilities(seatBelt = false))
        sm.board()
        sm.onSignal(DriverSeat(occupied = true))
        assertEquals(ANNOUNCING, sm.currentState)
        assertEquals(TransitionReason.SEATED, sm.log().last().reason)
    }

    @Test
    fun `대체 규칙 - 문 신호 미지원이면 착좌로 탑승을 판정하고 취소 뒤 되풀이하지 않는다`() {
        val sm = machine(SignalCapabilities(door = false))
        assertEquals(BOARDING, sm.currentState)
        sm.sit()
        assertEquals(ANNOUNCING, sm.currentState)
        clock.advanceSeconds(120)
        sm.tick()
        assertEquals(PARKED, sm.currentState, "앉아만 있다고 예고가 되풀이되면 안 된다")
        sm.onSignal(DriverSeat(occupied = false))
        sm.onSignal(DriverSeat(occupied = true))
        assertEquals(ANNOUNCING, sm.currentState)
    }

}
