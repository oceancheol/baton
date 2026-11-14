package app.baton.core.departure

import app.baton.core.FakeClock
import app.baton.core.departure.DepartureState.ANNOUNCING
import app.baton.core.departure.DepartureState.BOARDING
import app.baton.core.departure.DepartureState.DEPARTING
import app.baton.core.departure.DepartureState.PARKED
import app.baton.core.signal.SignalCapabilities
import app.baton.core.signal.SignalEvent.DistanceFromSpot
import app.baton.core.signal.SignalEvent.DriverDoor
import app.baton.core.signal.SignalEvent.DriverSeat
import app.baton.core.signal.SignalEvent.ParkingBrakeTelltale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 검수 (3)-4: 취소 경계. */
class CancelBoundaryTest {
    private val clock = FakeClock()

    @Test
    fun `BOARDING 중에 다시 열린 문도 추적한다 - 열린 채 예고가 시작되면 그때부터 60초 뒤 취소`() {
        val sm = parkedMachine(clock)
        sm.doorCycle(clock)
        assertEquals(BOARDING, sm.currentState)
        sm.onSignal(DriverDoor(open = true))
        clock.advanceSeconds(200) // BOARDING 에서 오래 열어 둬도 예고 전에는 세지 않는다
        sm.sitAndBelt()
        assertEquals(ANNOUNCING, sm.currentState)
        assertEquals(clock.nowMillis() + 60_000L, sm.snapshot.value.cancelAtMillis)

        clock.advanceMillis(59_999)
        sm.tick()
        assertEquals(ANNOUNCING, sm.currentState)
        clock.advanceMillis(1)
        sm.tick()
        assertEquals(PARKED, sm.currentState)
        assertEquals(TransitionReason.DOOR_REOPEN_TIMEOUT, sm.log().last { it.isTransition }.reason)
    }

    @Test
    fun `BOARDING 중에 열린 문을 예고 뒤 60초 안에 닫으면 예고를 유지한다`() {
        val sm = parkedMachine(clock)
        sm.doorCycle(clock)
        sm.onSignal(DriverDoor(open = true))
        sm.sitAndBelt()
        clock.advanceSeconds(30)
        sm.onSignal(DriverDoor(open = false))
        clock.advanceSeconds(60)
        sm.tick()
        assertEquals(ANNOUNCING, sm.currentState)
    }

    @Test
    fun `문과 착좌 타이머가 겹치면 먼저 닿는 쪽으로 취소한다`() {
        val sm = parkedMachine(clock)
        sm.doorCycle(clock)
        sm.sitAndBelt()
        val t0 = clock.nowMillis()
        clock.advanceSeconds(10)
        sm.onSignal(DriverSeat(occupied = false)) // 취소 예정 t0+70
        clock.advanceSeconds(10)
        sm.onSignal(DriverDoor(open = true)) // 취소 예정 t0+80
        assertEquals(t0 + 70_000L, sm.snapshot.value.cancelAtMillis)

        clock.advanceSeconds(49) // t0+69
        sm.tick()
        assertEquals(ANNOUNCING, sm.currentState)
        clock.advanceSeconds(1) // t0+70
        sm.tick()
        assertEquals(PARKED, sm.currentState)
        assertEquals(TransitionReason.SEAT_VACATED_TIMEOUT, sm.log().last { it.isTransition }.reason)
    }

    @Test
    fun `60초 직전에 돌아오면 유지하고 남은 타이머만 이어진다`() {
        val sm = parkedMachine(clock)
        sm.doorCycle(clock)
        sm.sitAndBelt()
        val t0 = clock.nowMillis()
        sm.onSignal(DriverSeat(occupied = false))
        sm.onSignal(DriverDoor(open = true))
        clock.advanceMillis(59_999)
        sm.onSignal(DriverSeat(occupied = true))
        assertEquals(ANNOUNCING, sm.currentState)
        assertEquals(t0 + 60_000L, sm.snapshot.value.cancelAtMillis, "문 타이머는 그대로 남는다")
        sm.onSignal(DriverDoor(open = false))
        assertEquals(t0 + 120_000L, sm.snapshot.value.cancelAtMillis)
        clock.advanceSeconds(30)
        sm.tick()
        assertEquals(ANNOUNCING, sm.currentState)
    }

    @Test
    fun `정확히 60초에 들어온 복귀 신호보다 취소가 먼저다`() {
        val sm = parkedMachine(clock)
        sm.doorCycle(clock)
        sm.sitAndBelt()
        sm.onSignal(DriverSeat(occupied = false))
        clock.advanceMillis(60_000)
        val produced = sm.onSignal(DriverSeat(occupied = true))
        assertEquals(PARKED, sm.currentState)
        assertEquals(
            listOf(TransitionReason.SEAT_VACATED_TIMEOUT),
            produced.filter { it.isTransition }.map { it.reason },
        )
    }

    @Test
    fun `정확히 120초에 들어온 이탈 신호보다 취소가 먼저다`() {
        val sm = parkedMachine(clock)
        sm.doorCycle(clock)
        sm.sitAndBelt()
        sm.onSignal(ParkingBrakeTelltale(on = false))
        clock.advanceMillis(119_999)
        sm.tick()
        assertEquals(DEPARTING, sm.currentState)
        clock.advanceMillis(1)
        sm.onSignal(DistanceFromSpot(30.0))
        assertEquals(PARKED, sm.currentState)
    }

    @Test
    fun `문 미지원 차량 - 하차로 취소된 뒤 다시 앉으면 재탑승으로 받는다`() {
        val sm = parkedMachine(clock, SignalCapabilities(door = false, seatBelt = false))
        sm.onSignal(DriverSeat(occupied = true))
        assertEquals(ANNOUNCING, sm.currentState)
        sm.onSignal(DriverSeat(occupied = false))
        clock.advanceSeconds(60)
        sm.tick()
        assertFalse(sm.currentState == ANNOUNCING)
        assertEquals(TransitionReason.SEAT_VACATED_TIMEOUT, sm.log().last { it.reason.name.endsWith("TIMEOUT") }.reason)

        sm.onSignal(DriverSeat(occupied = true))
        assertEquals(ANNOUNCING, sm.currentState, "예고 중에 본 하차를 보존해 재탑승을 받는다")
        assertEquals(2L, sm.snapshot.value.announcementGeneration)
    }

    @Test
    fun `문 미지원 차량 - 앉은 채 120초로 취소되면 앉아만 있어서는 되풀이하지 않는다`() {
        val sm = parkedMachine(clock, SignalCapabilities(door = false, seatBelt = false))
        sm.onSignal(DriverSeat(occupied = true))
        clock.advanceSeconds(120)
        sm.tick()
        assertEquals(PARKED, sm.currentState)
        clock.advanceSeconds(300)
        sm.tick()
        sm.onSignal(DriverSeat(occupied = true))
        assertEquals(PARKED, sm.currentState)
    }

    @Test
    fun `새 주차 - 남아 있는 착좌와 벨트 값은 탑승으로 세지 않는다`() {
        val sm = parkedMachine(clock)
        sm.doorCycle(clock)
        sm.sitAndBelt()
        sm.onSignal(ParkingBrakeTelltale(on = false))
        sm.onSignal(DistanceFromSpot(30.0))
        assertEquals(DepartureState.VACATED, sm.currentState)

        // 다른 자리에 주차했다. 운전자는 아직 앉아 있고 벨트도 매고 있다.
        val produced = sm.startNewParking()
        assertEquals(PARKED, sm.currentState)
        assertEquals(2L, sm.snapshot.value.parkingGeneration)
        assertEquals(TransitionReason.NEW_PARKING, produced.first().reason)
        assertFalse(sm.snapshot.value.movedSeen, "이전 주차 지점 기준 거리는 버린다")
        sm.onSignal(ParkingBrakeTelltale(on = true))
        clock.advanceSeconds(600)
        sm.tick()
        assertEquals(PARKED, sm.currentState)

        sm.doorCycle(clock) // 내리며 문을 여닫음(앉은 값이 아직 남아 있는 순간)
        assertEquals(ANNOUNCING, sm.currentState, "문 여닫힘 뒤에는 착좌 조건을 다시 본다")
    }

    @Test
    fun `새 주차 - 문 미지원 차량은 앉은 값이 남아 있으면 한 번 내렸다 타야 예고한다`() {
        val sm = parkedMachine(clock, SignalCapabilities(door = false))
        sm.sitAndBelt()
        assertEquals(ANNOUNCING, sm.currentState)
        sm.startNewParking()
        clock.advanceSeconds(10)
        sm.tick()
        assertEquals(PARKED, sm.currentState)
        sm.onSignal(DriverSeat(occupied = false))
        assertEquals(BOARDING, sm.currentState)
        sm.onSignal(DriverSeat(occupied = true))
        assertEquals(ANNOUNCING, sm.currentState)
    }

    @Test
    fun `새 주차 - 예고 중이었다면 끝나는 예고의 세대를 실어 취소를 알린다`() {
        val sm = parkedMachine(clock)
        sm.doorCycle(clock)
        sm.sitAndBelt()
        val produced = sm.startNewParking()
        val event = produced.first()
        assertTrue(event.isTransition)
        assertEquals(ANNOUNCING, event.from)
        assertEquals(PARKED, event.to)
        assertEquals(1L, event.announcementGeneration)
    }
}
