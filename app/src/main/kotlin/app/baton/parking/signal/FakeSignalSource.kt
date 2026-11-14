package app.baton.parking.signal

import app.baton.core.Clock
import app.baton.core.signal.LocationFix
import app.baton.core.signal.LocationQuality
import app.baton.core.signal.SignalAvailability
import app.baton.core.signal.SignalCapabilities
import app.baton.core.signal.SignalEvent
import app.baton.core.signal.SignalKind
import app.baton.core.signal.SignalReading
import app.baton.core.signal.SignalSnapshot
import app.baton.core.signal.SignalSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** 시연 패널 스위치의 현재 값. */
data class FakeSignalState(
    val doorOpen: Boolean = false,
    val seated: Boolean = false,
    val belted: Boolean = false,
    val parkingBrakeOn: Boolean = true,
    val pedalPressed: Boolean = false,
    val moved: Boolean = false,
    /** 위치 품질 낮음(구역까지만 안내). */
    val lowQualityLocation: Boolean = false,
    /** 위치 신호 끊김(일시 불가 → 자동 예고 꺼짐). */
    val locationLost: Boolean = false,
)

/**
 * SDK 없이 돌아가는 모의 신호 소스. 시연 패널이 값을 바꾸면 그대로 신호로 내보낸다.
 *
 * 변경은 버리지 않는 채널로 전달한다(받는 쪽이 하나인 것을 전제로 한다). 관측 시각과 주차 세대를 붙여 보낸다.
 */
class FakeSignalSource(
    private val clock: Clock,
    override val capabilities: SignalCapabilities = SignalCapabilities.ALL,
) : SignalSource {
    override val name: String = "모의 신호"
    override val isMock: Boolean = true

    private val channel = Channel<SignalEvent>(Channel.UNLIMITED)
    override val events: Flow<SignalEvent> = channel.receiveAsFlow()

    private val _state = MutableStateFlow(FakeSignalState())
    val state: StateFlow<FakeSignalState> = _state.asStateFlow()

    /** 지금 값을 읽는 기준인 주차 세대. 새 주차를 시작할 때 맞춘다. */
    @Volatile
    var parkingGeneration: Long? = null

    override suspend fun currentSnapshot(): SignalSnapshot = snapshotNow()

    /** 현재 값(변화가 아닌 상태). */
    fun snapshotNow(): SignalSnapshot {
        val s = _state.value
        val now = clock.nowMillis()
        fun reading(supported: Boolean, value: Boolean): SignalReading<Boolean> =
            if (supported) SignalReading.available(value, now) else SignalReading.unsupported()
        return SignalSnapshot(
            door = reading(capabilities.door, s.doorOpen),
            seat = reading(capabilities.seatOccupancy, s.seated),
            seatBelt = reading(capabilities.seatBelt, s.belted),
            parkingBrake = reading(capabilities.parkingBrakeTelltale, s.parkingBrakeOn),
            brakePedal = reading(capabilities.brakePedal, s.pedalPressed),
            location = when {
                !capabilities.location -> SignalReading.unsupported()
                s.locationLost -> SignalReading.unavailable(now)
                else -> SignalReading.available(fix(s), now)
            },
            takenAtMillis = now,
        )
    }

    fun setDoorOpen(open: Boolean) = update({ copy(doorOpen = open) }) { at, gen ->
        SignalEvent.DriverDoor(open, at, gen)
    }

    fun setSeated(seated: Boolean) = update({ copy(seated = seated) }) { at, gen ->
        SignalEvent.DriverSeat(seated, at, gen)
    }

    fun setBelted(belted: Boolean) = update({ copy(belted = belted) }) { at, gen ->
        SignalEvent.DriverSeatBelt(belted, at, gen)
    }

    fun setParkingBrakeOn(on: Boolean) = update({ copy(parkingBrakeOn = on) }) { at, gen ->
        SignalEvent.ParkingBrakeTelltale(on, at, gen)
    }

    fun setPedalPressed(pressed: Boolean) = update({ copy(pedalPressed = pressed) }) { at, gen ->
        SignalEvent.BrakePedal(pressed, at, gen)
    }

    /** 이동 스위치: 켜면 주차 지점에서 [MOVED_METERS] 벗어난 것으로 본다. */
    fun setMoved(moved: Boolean) = update({ copy(moved = moved) }) { at, gen -> locationEvent(at, gen) }

    fun setLowQualityLocation(low: Boolean) = update({ copy(lowQualityLocation = low) }) { at, gen ->
        locationEvent(at, gen)
    }

    fun setLocationLost(lost: Boolean) = update({ copy(locationLost = lost) }) { at, gen -> locationEvent(at, gen) }

    /** 주차해 둔 차의 기본값으로 되돌린다. 신호로 내보내지 않는다: 받는 쪽이 [snapshotNow]로 다시 읽는다. */
    fun resetToParked(generation: Long?) {
        parkingGeneration = generation
        _state.value = FakeSignalState()
    }

    private fun locationEvent(at: Long, gen: Long?): SignalEvent {
        val s = _state.value
        return if (s.locationLost) {
            SignalEvent.AvailabilityChanged(SignalKind.LOCATION, SignalAvailability.UNAVAILABLE, at, gen)
        } else {
            val f = fix(s)
            SignalEvent.DistanceFromSpot(f.metersFromSpot, f.quality, at, gen)
        }
    }

    private fun fix(s: FakeSignalState) = LocationFix(
        metersFromSpot = if (s.moved) MOVED_METERS else 0.0,
        quality = if (s.lowQualityLocation) LocationQuality.LOW else LocationQuality.HIGH,
    )

    private fun update(change: FakeSignalState.() -> FakeSignalState, event: (Long, Long?) -> SignalEvent) {
        _state.value = _state.value.change()
        channel.trySend(event(clock.nowMillis(), parkingGeneration))
    }

    companion object {
        const val MOVED_METERS = 20.0
    }
}
