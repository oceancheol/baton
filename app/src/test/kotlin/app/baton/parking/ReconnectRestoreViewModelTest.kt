package app.baton.parking

import app.baton.core.Clock
import app.baton.core.departure.DepartureState.ANNOUNCING
import app.baton.core.departure.DepartureState.PARKED
import app.baton.core.signal.SignalAvailability
import app.baton.core.signal.SignalCapabilities
import app.baton.core.signal.SignalEvent
import app.baton.core.signal.SignalKind
import app.baton.core.signal.SignalReading
import app.baton.core.signal.SignalSnapshot
import app.baton.parking.signal.BatonSignalSource
import app.baton.parking.signal.FakeSignalSource
import app.baton.parking.signal.LinkStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 실제 차량 신호 소스 쪽 배선(스위치 없는 소스): 앱이 처음 연결될 때의 복원만 "이미 앉아 있으면 탑승"이고,
 * 실행 중에 끊겼다 다시 붙은 복원은 상태를 그대로 둔다.
 */
class ReconnectRestoreViewModelTest {
    /** 차량 SDK 소스 자리에 넣는 가짜: 스위치가 없고(manualSignals = null) 현재 값을 조회로만 준다. */
    private class VehicleLikeSource(
        private val clock: Clock,
        override val initialSeatTrusted: Boolean,
    ) : BatonSignalSource {
        override val name = "가짜 차량 신호"
        override val isMock = false
        override val isMockLocation = true
        override val capabilities = SignalCapabilities.ALL
        override val manualSignals: FakeSignalSource? = null
        override val mockLocation = FakeSignalSource(clock)
        override val link = MutableStateFlow(LinkStatus.CONNECTING)

        @Volatile
        override var parkingGeneration: Long? = null

        var doorOpen = false
        var seated = false
        var snapshotCount = 0
        private val channel = Channel<SignalEvent>(Channel.UNLIMITED)

        override val events: Flow<SignalEvent> =
            merge(channel.receiveAsFlow(), mockLocation.events.filter { it.kind == SignalKind.LOCATION })

        override suspend fun currentSnapshot(): SignalSnapshot {
            snapshotCount += 1
            val now = clock.nowMillis()
            link.value = LinkStatus.RECEIVING
            return SignalSnapshot(
                door = SignalReading.available(doorOpen, now),
                seat = SignalReading.available(seated, now),
                seatBelt = SignalReading.unsupported(),
                parkingBrake = SignalReading.available(true, now),
                brakePedal = SignalReading.available(false, now),
                location = mockLocation.snapshotNow().location,
                takenAtMillis = now,
            )
        }

        fun seat(occupied: Boolean) {
            seated = occupied
            channel.trySend(SignalEvent.DriverSeat(occupied, clock.nowMillis(), parkingGeneration))
        }

        fun door(open: Boolean) {
            doorOpen = open
            channel.trySend(SignalEvent.DriverDoor(open, clock.nowMillis(), parkingGeneration))
        }

        /** 서비스 단절: 차량 신호를 모두 읽지 못한다. */
        fun drop() {
            val now = clock.nowMillis()
            for (kind in listOf(SignalKind.DOOR, SignalKind.SEAT, SignalKind.PARKING_BRAKE, SignalKind.BRAKE_PEDAL)) {
                channel.trySend(SignalEvent.AvailabilityChanged(kind, SignalAvailability.UNAVAILABLE, now, parkingGeneration))
            }
            link.value = LinkStatus.UNAVAILABLE
        }

        /** 다시 읽힘: 받는 쪽(ViewModel)이 현재 값을 다시 읽는다. */
        fun recover() {
            link.value = LinkStatus.RECEIVING
        }

        override fun close() {
            channel.close()
            mockLocation.close()
        }
    }

    private lateinit var source: VehicleLikeSource
    private var bench: ViewModelBench? = null

    private fun start(seated: Boolean, initialSeatTrusted: Boolean = true): ViewModelBench =
        ViewModelBench { _, clock ->
            VehicleLikeSource(clock, initialSeatTrusted).also { it.seated = seated; source = it }
        }.also { bench = it }

    private fun ViewModelBench.seatLine(): String = ui.signalLines.single { it.label == "착좌 · 안전벨트" }.value

    @AfterTest
    fun tearDown() {
        bench?.close()
    }

    private fun ViewModelBench.doorCycleOnVehicle() {
        source.door(true)
        waitSeconds(1)
        source.door(false)
        waitSeconds(1)
    }

    @Test
    fun `처음 연결할 때 이미 앉아 있으면 탑승으로 복원해 예고한다`() {
        val bench = start(seated = true)
        bench.waitSeconds(1)
        assertEquals(1, source.snapshotCount)
        assertEquals(ANNOUNCING, bench.state)
    }

    @Test
    fun `처음 읽은 착좌 값을 믿을 수 없는 환경 - 처음 연결 때 앉음으로 읽혀도 예고하지 않고 문 여닫힘 뒤에 예고한다`() {
        val bench = start(seated = true, initialSeatTrusted = false)
        bench.waitSeconds(1)
        assertEquals(PARKED, bench.state)
        assertTrue("초기값 미확정" in bench.seatLine(), bench.seatLine())
        bench.waitSeconds(60)
        assertEquals(PARKED, bench.state)

        bench.doorCycleOnVehicle()
        assertEquals(ANNOUNCING, bench.state)
    }

    @Test
    fun `처음 읽은 착좌 값을 믿을 수 없는 환경 - 착좌가 바뀌는 것을 본 뒤에는 앉음으로 적는다`() {
        val bench = start(seated = true, initialSeatTrusted = false)
        bench.waitSeconds(1)
        source.seat(false)
        bench.waitSeconds(1)
        assertTrue("비어 있음" in bench.seatLine(), bench.seatLine())
        assertEquals(PARKED, bench.state)

        bench.doorCycleOnVehicle()
        source.seat(true)
        bench.waitSeconds(1)
        assertTrue(bench.seatLine().startsWith("앉음"), bench.seatLine())
        assertEquals(ANNOUNCING, bench.state)
    }

    @Test
    fun `예고 중에 끊겼다 다시 붙으면 앉아만 있어서는 예고하지 않고 문 여닫힘 뒤에 예고한다`() {
        val bench = start(seated = true)
        bench.waitSeconds(1)
        assertEquals(ANNOUNCING, bench.state)

        source.drop()
        bench.waitSeconds(1)
        assertNotEquals(ANNOUNCING, bench.state, "착좌를 읽지 못해 예고가 취소된다")
        assertFalse(bench.ui.departure.autoAnnounce.enabled)

        source.recover()
        bench.waitSeconds(1)
        assertEquals(2, source.snapshotCount, "다시 읽히면 현재 값을 다시 읽는다")
        assertEquals(PARKED, bench.state, "복구만으로 예고가 다시 시작되지 않는다")
        bench.waitSeconds(30)
        assertEquals(PARKED, bench.state)

        bench.doorCycleOnVehicle()
        assertEquals(ANNOUNCING, bench.state)
    }

    @Test
    fun `주차 중에 앉은 채 끊겼다 다시 붙어도 탑승으로 복원하지 않는다`() {
        val bench = start(seated = false)
        bench.waitSeconds(1)
        assertEquals(PARKED, bench.state)

        source.drop()
        bench.waitSeconds(1)
        assertNotEquals(ANNOUNCING, bench.state)
        source.seated = true // 끊긴 동안 앉았다(변화는 보지 못했다)
        source.recover()
        bench.waitSeconds(5)
        assertEquals(2, source.snapshotCount)
        assertEquals(PARKED, bench.state)

        bench.doorCycleOnVehicle()
        assertEquals(ANNOUNCING, bench.state)
    }
}
