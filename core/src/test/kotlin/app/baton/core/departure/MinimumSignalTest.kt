package app.baton.core.departure

import app.baton.core.FakeClock
import app.baton.core.departure.DepartureState.ANNOUNCING
import app.baton.core.departure.DepartureState.BOARDING
import app.baton.core.departure.DepartureState.DEPARTING
import app.baton.core.departure.DepartureState.PARKED
import app.baton.core.signal.SignalAvailability
import app.baton.core.signal.SignalCapabilities
import app.baton.core.signal.SignalEvent.AvailabilityChanged
import app.baton.core.signal.SignalEvent.BrakePedal
import app.baton.core.signal.SignalEvent.DistanceFromSpot
import app.baton.core.signal.SignalEvent.DriverDoor
import app.baton.core.signal.SignalEvent.DriverSeat
import app.baton.core.signal.SignalEvent.DriverSeatBelt
import app.baton.core.signal.SignalEvent.ParkingBrakeTelltale
import app.baton.core.signal.SignalKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 검수 (3)-3: 최소 신호(착좌·위치)와 부분 지원. */
class MinimumSignalTest {
    private val clock = FakeClock()

    @Test
    fun `착좌 미지원이면 문을 여닫고 벨트를 매도 예고하지 않고 이유를 스냅샷에 싣는다`() {
        val sm = parkedMachine(clock, SignalCapabilities(seatOccupancy = false))
        sm.doorCycle(clock)
        sm.onSignal(DriverSeat(occupied = true)) // 미지원 신호는 들어와도 쓰지 않는다
        sm.onSignal(DriverSeatBelt(fastened = true))
        assertEquals(BOARDING, sm.currentState)
        val auto = sm.snapshot.value.autoAnnounce
        assertFalse(auto.enabled)
        assertEquals("자동 예고 꺼짐 · 운전석 착좌 신호 미지원", auto.reason)
        assertFalse(sm.log().any { it.to == ANNOUNCING })
    }

    @Test
    fun `착좌와 벨트가 모두 미지원이면 문 닫힘만으로 예고하지 않는다`() {
        val sm = parkedMachine(clock, SignalCapabilities(seatOccupancy = false, seatBelt = false))
        sm.doorCycle(clock)
        assertEquals(BOARDING, sm.currentState)
        assertFalse(sm.snapshot.value.autoAnnounce.enabled)
    }

    @Test
    fun `위치 미지원이면 탑승해 브레이크를 풀어도 예고도 안내 단계도 없다`() {
        val sm = parkedMachine(clock, SignalCapabilities(location = false))
        sm.doorCycle(clock)
        sm.sitAndBelt()
        sm.onSignal(ParkingBrakeTelltale(on = false))
        sm.onSignal(DistanceFromSpot(50.0))
        assertEquals(BOARDING, sm.currentState)
        assertEquals("자동 예고 꺼짐 · 위치 신호 미지원", sm.snapshot.value.autoAnnounce.reason)
        assertNull(sm.snapshot.value.cancelAtMillis)
    }

    @Test
    fun `위치와 주차 브레이크가 모두 미지원이면 페달만으로 출차 임박이 되지 않는다`() {
        val sm = parkedMachine(clock, SignalCapabilities(location = false, parkingBrakeTelltale = false))
        sm.doorCycle(clock)
        sm.sitAndBelt()
        sm.onSignal(BrakePedal(pressed = true))
        assertEquals(BOARDING, sm.currentState)
        assertFalse(sm.log().any { it.to == DEPARTING })
    }

    @Test
    fun `지원하지만 값을 아직 받지 못한 최소 신호는 기다린다 - 값이 오면 예고한다`() {
        val sm = DepartureStateMachine(clock)
        sm.onSignal(DriverDoor(open = false))
        sm.onSignal(DriverSeatBelt(fastened = false))
        sm.doorCycle(clock)
        sm.sitAndBelt()
        assertEquals(BOARDING, sm.currentState, "위치 값을 한 번도 받지 못했다")
        assertEquals("자동 예고 꺼짐 · 위치 신호 수신 대기", sm.snapshot.value.autoAnnounce.reason)
        assertEquals(SignalAvailability.UNKNOWN, sm.snapshot.value.location.availability)

        sm.onSignal(DistanceFromSpot(0.0))
        assertEquals(ANNOUNCING, sm.currentState)
        assertTrue(sm.snapshot.value.autoAnnounce.enabled)
        assertNull(sm.snapshot.value.autoAnnounce.reason)
    }

    @Test
    fun `지원하지만 값을 받지 못한 벨트는 기다리고 일시 불가로 확인되면 착좌만으로 예고한다`() {
        val sm = DepartureStateMachine(clock)
        sm.onSignal(DriverDoor(open = false))
        sm.onSignal(DistanceFromSpot(0.0))
        sm.doorCycle(clock)
        sm.onSignal(DriverSeat(occupied = true))
        assertEquals(BOARDING, sm.currentState, "벨트 값이 아직 없다")
        sm.onSignal(AvailabilityChanged(SignalKind.SEAT_BELT, SignalAvailability.UNAVAILABLE))
        assertEquals(ANNOUNCING, sm.currentState)
        assertEquals(TransitionReason.SEATED, sm.log().last { it.isTransition }.reason)
    }

    @Test
    fun `벨트만 미지원이면 최소 조건은 충족이라 착좌만으로 예고하고 끝까지 간다`() {
        val sm = parkedMachine(clock, SignalCapabilities(seatBelt = false))
        sm.doorCycle(clock)
        sm.onSignal(DriverSeat(occupied = true))
        assertEquals(ANNOUNCING, sm.currentState)
        assertTrue(sm.snapshot.value.autoAnnounce.enabled)
        assertEquals(SignalAvailability.UNSUPPORTED, sm.snapshot.value.seatBelt.availability)
        sm.onSignal(ParkingBrakeTelltale(on = false))
        sm.onSignal(DistanceFromSpot(12.0))
        assertEquals(DepartureState.VACATED, sm.currentState)
    }

    @Test
    fun `예고 중에 위치를 읽지 못하게 되면 예고를 취소하고 돌아와도 다시 탑승해야 한다`() {
        val sm = parkedMachine(clock)
        sm.doorCycle(clock)
        sm.sitAndBelt()
        sm.onSignal(ParkingBrakeTelltale(on = false))
        assertEquals(DEPARTING, sm.currentState)

        val produced = sm.onSignal(AvailabilityChanged(SignalKind.LOCATION, SignalAvailability.UNAVAILABLE))
        assertEquals(PARKED, sm.currentState)
        val cancel = produced.single { it.isTransition }
        assertEquals(TransitionReason.MIN_SIGNAL_LOST, cancel.reason)
        assertEquals(1L, cancel.announcementGeneration, "취소되는 예고의 세대가 실린다")
        assertEquals("자동 예고 꺼짐 · 위치 신호 일시 불가", sm.snapshot.value.autoAnnounce.reason)

        sm.onSignal(DistanceFromSpot(0.0))
        assertTrue(sm.snapshot.value.autoAnnounce.enabled)
        assertEquals(PARKED, sm.currentState, "신호가 돌아왔다고 저절로 다시 예고하지 않는다")
        sm.doorCycle(clock)
        assertEquals(ANNOUNCING, sm.currentState)
        assertEquals(2L, sm.snapshot.value.announcementGeneration)
    }

    @Test
    fun `예고 중에 착좌 신호를 잃어도 취소한다`() {
        val sm = parkedMachine(clock)
        sm.doorCycle(clock)
        sm.sitAndBelt()
        assertEquals(ANNOUNCING, sm.currentState)
        sm.onSignal(AvailabilityChanged(SignalKind.SEAT, SignalAvailability.UNAVAILABLE))
        assertEquals(PARKED, sm.currentState)
        assertEquals(TransitionReason.MIN_SIGNAL_LOST, sm.log().last { it.isTransition }.reason)
        assertNotNull(sm.snapshot.value.autoAnnounce.reason)
    }

    @Test
    fun `보조 신호인 문을 실행 중에 잃으면 착좌로 판정하는 대체 규칙으로 넘어간다`() {
        val sm = parkedMachine(clock)
        sm.onSignal(AvailabilityChanged(SignalKind.DOOR, SignalAvailability.UNAVAILABLE))
        assertEquals(BOARDING, sm.currentState)
        assertEquals(TransitionReason.DOOR_UNSUPPORTED, sm.log().last { it.isTransition }.reason)
        assertTrue(sm.snapshot.value.autoAnnounce.enabled, "보조 신호는 자동 예고를 끄지 않는다")
        sm.sitAndBelt()
        assertEquals(ANNOUNCING, sm.currentState)
    }
}
