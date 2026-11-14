package app.baton.core.departure

import app.baton.core.FakeClock
import app.baton.core.signal.LocationFix
import app.baton.core.signal.SignalCapabilities
import app.baton.core.signal.SignalEvent.BrakePedal
import app.baton.core.signal.SignalEvent.DistanceFromSpot
import app.baton.core.signal.SignalEvent.DriverDoor
import app.baton.core.signal.SignalEvent.DriverSeat
import app.baton.core.signal.SignalEvent.DriverSeatBelt
import app.baton.core.signal.SignalEvent.ParkingBrakeTelltale
import app.baton.core.signal.SignalReading
import app.baton.core.signal.SignalSnapshot

/** 주차해 둔 차의 기본 신호를 다 받은 상태기계. */
fun parkedMachine(clock: FakeClock, caps: SignalCapabilities = SignalCapabilities.ALL) =
    DepartureStateMachine(clock, caps).also { sm ->
        sm.onSignal(ParkingBrakeTelltale(on = true))
        sm.onSignal(DriverDoor(open = false))
        sm.onSignal(DriverSeat(occupied = false))
        sm.onSignal(DriverSeatBelt(fastened = false))
        sm.onSignal(BrakePedal(pressed = false))
        sm.onSignal(DistanceFromSpot(0.0))
    }

fun DepartureStateMachine.doorCycle(clock: FakeClock) {
    onSignal(DriverDoor(open = true))
    clock.advanceSeconds(3)
    onSignal(DriverDoor(open = false))
}

fun DepartureStateMachine.sitAndBelt() {
    onSignal(DriverSeat(occupied = true))
    onSignal(DriverSeatBelt(fastened = true))
}

fun DepartureStateMachine.transitionStates() = log().filter { it.isTransition }.map { it.from to it.to }

/** 모든 신호를 읽을 수 있는 스냅샷. */
fun snapshotOf(
    clock: FakeClock,
    doorOpen: Boolean = false,
    seated: Boolean = false,
    belted: Boolean = false,
    parkingBrakeOn: Boolean = true,
    pedalPressed: Boolean = false,
    meters: Double = 0.0,
) = SignalSnapshot(
    door = SignalReading.available(doorOpen),
    seat = SignalReading.available(seated),
    seatBelt = SignalReading.available(belted),
    parkingBrake = SignalReading.available(parkingBrakeOn),
    brakePedal = SignalReading.available(pedalPressed),
    location = SignalReading.available(LocationFix(meters)),
    takenAtMillis = clock.nowMillis(),
)
