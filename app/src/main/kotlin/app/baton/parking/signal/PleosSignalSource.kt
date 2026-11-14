package app.baton.parking.signal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import app.baton.core.Clock
import app.baton.core.signal.SignalAvailability
import app.baton.core.signal.SignalCapabilities
import app.baton.core.signal.SignalEvent
import app.baton.core.signal.SignalKind
import app.baton.core.signal.SignalReading
import app.baton.core.signal.SignalSnapshot
import app.baton.core.signal.SignalSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pleos Connect 차량 SDK(Vehicle)에서 읽는 실제 차량 신호. SDK 호출은 이 파일 한 곳에만 둔다. 모두 조회 전용이다.
 *
 * 읽는 것(운전석 = 좌핸들 기준 1열 왼쪽):
 * - 문: `Door.getCurrentDoorPosition` / `registerDoorPosition` (0 이면 닫힘, 0 보다 크면 열림)
 * - 착좌: `Seat.getCurrentSeatOccupancy` / `registerSeatOccupancy`
 * - 안전벨트: `Seat.getCurrentSeatBeltBuckledState` / `registerSeatBeltBuckledState`
 *   (에뮬레이터에서는 값을 바꿀 방법이 없어 미지원으로 두고 착좌만 쓴다)
 * - 브레이크 페달: `Brake.getCurrentBrakePedalStatus` / `registerBrakePedalStatus`
 * - 주차 브레이크 경고등: `Safety.getTelltalesInfo` / `registerTelltales` 의 PARKING_BRAKE·ELECTRONIC_PARKING_BRAKE 항목.
 *   SDK 2.3.2 구현은 이 둘에 `pleos.car.permission.PRIVATE_CAR_SAFETY` 를 요구한다(CAR_SAFETY 만으로는 거부된다).
 *   권한이 없으면 "일시 불가"가 되고 상태기계가 대체 규칙(페달 + 이동 시작)으로 판정한다.
 *
 * 위치는 이 SDK 모듈에 없어 [mockLocation]의 모의 값을 그대로 쓴다([isMockLocation] = true).
 *
 * 순서는 [SignalSource] 계약대로다: [currentSnapshot]이 신호마다 현재 값을 조회해 사용 가능 / 미지원 / 일시 불가를
 * 가려 주고, [events]는 그 뒤의 변경만 내보낸다. 콜백은 화면 스레드로 옮겨 관측 시각을 붙인다.
 * 주차 세대는 붙이지 않는다(실제 값은 언제나 "지금 이 차"의 값이라 세대와 무관하게 유효하다).
 */
class PleosSignalSource private constructor(
    private val vehicle: Vehicle,
    private val clock: Clock,
    /** 위치만 맡는 모의 소스. 시연 패널의 위치 스위치가 이것을 바꾼다. */
    val mockLocation: FakeSignalSource,
) : SignalSource {
    override val name: String = "Pleos 차량 신호"
    override val isMock: Boolean = false
    override val isMockLocation: Boolean = true

    // 지원 여부는 조회해 봐야 안다. 처음에는 모두 지원한다고 보고, 조회 결과로 미지원 / 일시 불가를 알린다.
    override val capabilities: SignalCapabilities = SignalCapabilities.ALL

    private val main = Handler(Looper.getMainLooper())
    private val channel = Channel<SignalEvent>(Channel.UNLIMITED)

    override val events: Flow<SignalEvent> =
        merge(channel.receiveAsFlow(), mockLocation.events.filter { it.kind == SignalKind.LOCATION })

    private val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)

    private val lock = Any()
    private val latest = HashMap<SignalKind, Reading>()
    private val unsupported = HashSet<SignalKind>()
    private var supportChecked = false
    private var subscribed = false

    override suspend fun currentSnapshot(): SignalSnapshot = coroutineScope {
        checkSupportOnce()
        val takenAt = clock.nowMillis()
        val door = async {
            read(SignalKind.DOOR) { ok, fail -> vehicle.getDoor().getCurrentDoorPosition(driverDoor, { ok(isOpen(it)) }, fail) }
        }
        val seat = async {
            read(SignalKind.SEAT) { ok, fail -> vehicle.getSeat().getCurrentSeatOccupancy(driverSeat, { ok(occupied(it)) }, fail) }
        }
        val belt = async {
            read(SignalKind.SEAT_BELT) { ok, fail -> vehicle.getSeat().getCurrentSeatBeltBuckledState(driverSeat, { ok(it) }, fail) }
        }
        val pedal = async {
            read(SignalKind.BRAKE_PEDAL) { ok, fail -> vehicle.getBrake().getCurrentBrakePedalStatus({ ok(it) }, fail) }
        }
        val brake = async {
            read(SignalKind.PARKING_BRAKE) { ok, fail -> vehicle.getSafety().getTelltalesInfo({ ok(parkingBrakeOn(it)) }, fail) }
        }
        val snapshot = SignalSnapshot(
            door = door.await().toSignal(takenAt),
            seat = seat.await().toSignal(takenAt),
            seatBelt = belt.await().toSignal(takenAt),
            parkingBrake = brake.await().toSignal(takenAt),
            brakePedal = pedal.await().toSignal(takenAt),
            location = mockLocation.snapshotNow().location,
            takenAtMillis = takenAt,
        )
        Log.i(TAG, "현재 값: 문=${snapshot.door.brief()} 착좌=${snapshot.seat.brief()} 벨트=${snapshot.seatBelt.brief()} " +
            "주차브레이크=${snapshot.parkingBrake.brief()} 페달=${snapshot.brakePedal.brief()}")
        subscribeOnce()
        snapshot
    }

    // ---- 현재 값 조회 ---------------------------------------------------------

    /** 신호 하나의 현재 값을 SDK 에 묻는다. 실패·시간 초과·예외는 삼키지 않고 로그와 상태로 남긴다. */
    private suspend fun query(
        kind: SignalKind,
        call: (ok: (Boolean?) -> Unit, fail: (Exception) -> Unit) -> Unit,
    ): Reading {
        val result = withTimeoutOrNull(QUERY_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { cont ->
                try {
                    call(
                        { value ->
                            if (value == null) {
                                Log.w(TAG, "${kind.label}: 조회 값이 비어 있음")
                                cont.resumeOnce(Reading(SignalAvailability.UNAVAILABLE))
                            } else {
                                cont.resumeOnce(Reading(SignalAvailability.AVAILABLE, value))
                            }
                        },
                        { error -> cont.resumeOnce(Reading(failure(kind, "조회", error))) },
                    )
                } catch (e: Exception) {
                    cont.resumeOnce(Reading(failure(kind, "조회 호출", e)))
                }
            }
        }
        val reading = result ?: Reading(SignalAvailability.UNAVAILABLE).also {
            Log.w(TAG, "${kind.label}: ${QUERY_TIMEOUT_MILLIS}ms 안에 조회 응답 없음 → 일시 불가")
        }
        synchronized(lock) { latest[kind] = reading }
        return reading
    }

    /** 지원 확인에서 "이 차에 없음"으로 나온 신호는 묻지 않고 미지원으로 돌려준다. */
    private suspend fun read(
        kind: SignalKind,
        call: (ok: (Boolean?) -> Unit, fail: (Exception) -> Unit) -> Unit,
    ): Reading {
        if (synchronized(lock) { kind in unsupported }) {
            return Reading(SignalAvailability.UNSUPPORTED).also { synchronized(lock) { latest[kind] = it } }
        }
        return query(kind, call)
    }

    // ---- 지원 여부 확인 --------------------------------------------------------

    /**
     * 신호별 지원 여부를 한 번 확인한다(PRD F1). 운전석이 지원 영역에 없거나 읽기 권한이 없다고 나오면 미지원으로 둔다.
     * 확인 자체가 실패하면 미지원으로 단정하지 않고 값 조회 결과에 맡긴다.
     */
    private suspend fun checkSupportOnce() {
        if (synchronized(lock) { supportChecked }) return
        val results = coroutineScope {
            listOf(
                SignalKind.DOOR to async {
                    supported(SignalKind.DOOR) { ok, fail ->
                        vehicle.getDoor().checkDoorPositionCapabilities(
                            { areas: Map<DoorArea, *> -> ok(areas.keys.any { DoorValue.ROW_1_LEFT in it.values }) },
                            fail,
                        )
                    }
                },
                SignalKind.SEAT to async {
                    supported(SignalKind.SEAT) { ok, fail ->
                        vehicle.getSeat().checkSeatOccupancyCapabilities(
                            { areas: List<SeatArea> -> ok(areas.any { SeatValue.ROW_1_LEFT in it.values }) },
                            fail,
                        )
                    }
                },
                SignalKind.SEAT_BELT to async {
                    supported(SignalKind.SEAT_BELT) { ok, fail ->
                        vehicle.getSeat().checkSeatBeltBuckledCapabilities(
                            { areas: List<SeatArea> -> ok(areas.any { SeatValue.ROW_1_LEFT in it.values }) },
                            fail,
                        )
                    }
                },
                SignalKind.BRAKE_PEDAL to async {
                    supported(SignalKind.BRAKE_PEDAL) { ok, fail ->
                        vehicle.getBrake().checkBrakePedalStatusCapabilities({ ok(it.accessType.canRead()) }, fail)
                    }
                },
                SignalKind.PARKING_BRAKE to async {
                    supported(SignalKind.PARKING_BRAKE) { ok, fail ->
                        vehicle.getSafety().checkTelltalesCapabilities({ ok(it.accessType.canRead()) }, fail)
                    }
                },
            ).map { (kind, answer) -> kind to answer.await() }
        }
        synchronized(lock) {
            supportChecked = true
            results.filter { it.second == false }.forEach { unsupported += it.first }
            if (ON_EMULATOR) unsupported += SignalKind.SEAT_BELT
        }
        if (ON_EMULATOR) {
            // PRD 6장 대체 규칙: 벨트 체결을 시험할 수 없으면 착좌만 쓴다.
            Log.i(TAG, "안전벨트: 에뮬레이터의 Virtual Console 에 벨트 조작이 없어 판정에서 뺌(미지원 처리) → 착좌만 사용")
        }
        Log.i(TAG, "지원 확인: " + results.joinToString(" ") { (kind, ok) ->
            "${kind.label}=" + when (ok) { true -> "지원"; false -> "미지원"; null -> "확인 실패" }
        })
    }

    /** @return 지원하면 true, 이 차에 없으면 false, 확인하지 못했으면 null. */
    private suspend fun supported(
        kind: SignalKind,
        call: (ok: (Boolean) -> Unit, fail: (Exception) -> Unit) -> Unit,
    ): Boolean? = withTimeoutOrNull(QUERY_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { cont ->
            try {
                call(
                    { cont.resumeOnce(it) },
                    { error ->
                        Log.w(TAG, "${kind.label}: 지원 확인 실패 (${error.message})")
                        cont.resumeOnce(null)
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "${kind.label}: 지원 확인 호출 실패", e)
                cont.resumeOnce(null)
            }
        }
    }

    private fun AccessType.canRead(): Boolean = this == AccessType.READ || this == AccessType.READ_WRITE

    // ---- 변경 수신 ------------------------------------------------------------

    /** 변경 구독을 한 번만 등록한다. 첫 현재 값 조회가 끝난 뒤에 부른다. */
    private fun subscribeOnce() {
        synchronized(lock) {
            if (subscribed) return
            subscribed = true
        }
        registerSupported()
    }

    private fun registerSupported() {
        register(SignalKind.DOOR) {
            vehicle.getDoor().registerDoorPosition(
                driverDoor,
                object : DoorPositionListener {
                    override fun onPositionUpdated(position: Int?) = onValue(SignalKind.DOOR, position?.let { isOpen(it) })
                    override fun onFailed(e: Exception) = onListenerFailed(SignalKind.DOOR, e)
                },
            )
        }
        register(SignalKind.SEAT) {
            vehicle.getSeat().registerSeatOccupancy(
                driverSeat,
                object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(raw: Boolean?) = onValue(SignalKind.SEAT, occupied(raw))
                    override fun onFailed(e: Exception) = onListenerFailed(SignalKind.SEAT, e)
                },
            )
        }
        register(SignalKind.SEAT_BELT) {
            vehicle.getSeat().registerSeatBeltBuckledState(
                driverSeat,
                object : SeatBeltBuckledStateListener {
                    override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) = onValue(SignalKind.SEAT_BELT, buckled)
                    override fun onFailed(e: Exception) = onListenerFailed(SignalKind.SEAT_BELT, e)
                },
            )
        }
        register(SignalKind.BRAKE_PEDAL) {
            vehicle.getBrake().registerBrakePedalStatus(
                object : BrakePedalStatusListener {
                    override fun onBrakePedalStatusUpdated(pressed: Boolean?) = onValue(SignalKind.BRAKE_PEDAL, pressed)
                    override fun onFailed(e: Exception) = onListenerFailed(SignalKind.BRAKE_PEDAL, e)
                },
            )
        }
        register(SignalKind.PARKING_BRAKE) {
            vehicle.getSafety().registerTelltales(
                object : TelltaleInfoListener {
                    override fun onTelltaleInfoUpdated(telltales: Telltales?) =
                        onValue(SignalKind.PARKING_BRAKE, parkingBrakeOn(telltales))
                    override fun onFailed(e: Exception) = onListenerFailed(SignalKind.PARKING_BRAKE, e)
                },
            )
        }
    }

    private fun register(kind: SignalKind, call: () -> Unit) {
        if (synchronized(lock) { kind in unsupported }) {
            Log.i(TAG, "${kind.label}: 미지원 → 구독하지 않음")
            return
        }
        try {
            call()
            Log.i(TAG, "${kind.label}: 변경 구독 등록")
        } catch (e: Exception) {
            apply(kind, Reading(failure(kind, "구독 등록", e)))
        }
    }

    private fun onValue(kind: SignalKind, value: Boolean?) {
        main.post {
            if (value == null) {
                Log.w(TAG, "${kind.label}: 빈 값 수신")
                apply(kind, Reading(SignalAvailability.UNAVAILABLE))
            } else {
                apply(kind, Reading(SignalAvailability.AVAILABLE, value))
            }
        }
    }

    private fun onListenerFailed(kind: SignalKind, error: Exception) {
        main.post { apply(kind, Reading(failure(kind, "수신", error))) }
    }

    /** 새 관측을 반영한다. 직전과 같으면 내보내지 않는다(같은 값을 '변화'로 세지 않게). 화면 스레드에서만 부른다. */
    private fun apply(kind: SignalKind, reading: Reading) {
        val previous = synchronized(lock) { latest.put(kind, reading) }
        if (previous == reading) return
        val at = clock.nowMillis()
        val event = when (reading.availability) {
            SignalAvailability.AVAILABLE -> valueEvent(kind, reading.value == true, at)
            else -> SignalEvent.AvailabilityChanged(kind, reading.availability, at)
        }
        Log.i(TAG, "변경: ${kind.label} → ${reading.brief()}")
        channel.trySend(event)
    }

    private fun valueEvent(kind: SignalKind, value: Boolean, at: Long): SignalEvent = when (kind) {
        SignalKind.DOOR -> SignalEvent.DriverDoor(value, at)
        SignalKind.SEAT -> SignalEvent.DriverSeat(value, at)
        SignalKind.SEAT_BELT -> SignalEvent.DriverSeatBelt(value, at)
        SignalKind.PARKING_BRAKE -> SignalEvent.ParkingBrakeTelltale(value, at)
        SignalKind.BRAKE_PEDAL -> SignalEvent.BrakePedal(value, at)
        SignalKind.LOCATION -> error("위치는 이 소스가 내보내지 않는다")
    }

    // ---- 값 해석 --------------------------------------------------------------

    private fun isOpen(position: Int?): Boolean? = position?.let { it > 0 }

    /**
     * SDK 가 준 착좌 값을 실제 착좌 여부로 바꾼다.
     *
     * Vehicle SDK 2.3.2 는 원시 값이 1 일 때 true 를 준다. 그런데 Pleos Connect v2.1.0 에뮬레이터는
     * Virtual Console 의 "Occupied"를 2, "Empty"를 1 로 보낸다(Android 표준 VehicleSeatOccupancyState 와 같다:
     * VACANT = 1, OCCUPIED = 2). 그래서 콘솔에서 "Occupied"로 두면 SDK 값은 false 로 온다(에뮬레이터에서 확인).
     * 이 조합에서는 뒤집어 읽는다. 실차나 SDK 가 바뀌면 [SEAT_OCCUPANCY_INVERTED]부터 다시 확인한다.
     */
    private fun occupied(raw: Boolean?): Boolean? = raw?.let { if (SEAT_OCCUPANCY_INVERTED) !it else it }

    /**
     * 경고등 목록에서 주차 브레이크(기계식 또는 전자식) 항목을 찾는다. 켜짐·깜빡임이면 true.
     * 목록에는 켜진 경고등만 들어 있다(에뮬레이터에서 확인: 끄면 항목이 사라진다). 그래서 항목이 없으면 꺼짐이다.
     * 목록 자체를 받지 못했으면 null(값 없음 → 일시 불가).
     */
    private fun parkingBrakeOn(telltales: Telltales?): Boolean? {
        val items = telltales?.telltales ?: return null
        return items.any {
            (it.type == TelltaleType.PARKING_BRAKE || it.type == TelltaleType.ELECTRONIC_PARKING_BRAKE) &&
                (it.state == TelltaleState.ON || it.state == TelltaleState.BLINK)
        }
    }

    /** SDK 가 돌려준 실패를 로그에 남기고 상태로 바꾼다. "지원하지 않음" 계열만 미지원, 나머지는 일시 불가. */
    private fun failure(kind: SignalKind, step: String, error: Exception): SignalAvailability {
        val text = error.message.orEmpty()
        val availability =
            if ("NOT_SUPPORTED" in text) SignalAvailability.UNSUPPORTED else SignalAvailability.UNAVAILABLE
        Log.w(TAG, "${kind.label}: $step 실패 → ${availability.label} ($text)", error)
        return availability
    }

    private data class Reading(val availability: SignalAvailability, val value: Boolean? = null) {
        fun toSignal(at: Long): SignalReading<Boolean> = when (availability) {
            SignalAvailability.AVAILABLE -> SignalReading.available(value == true, at)
            SignalAvailability.UNSUPPORTED -> SignalReading.unsupported()
            else -> SignalReading.unavailable(at)
        }

        fun brief(): String = if (availability == SignalAvailability.AVAILABLE) value.toString() else availability.label
    }

    companion object {
        private const val TAG = "BatonPleos"
        private const val QUERY_TIMEOUT_MILLIS = 3_000L
        private const val SEAT_OCCUPANCY_INVERTED = true

        /** Android 에뮬레이터(Pleos Connect 에뮬레이터 포함)에서 돌고 있는지. */
        private val ON_EMULATOR: Boolean = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
        private const val SERVICE_PACKAGE = "ai.pleos.playground.service.vehicle"
        private const val SERVICE_CLASS = "ai.pleos.playground.service.vehicle.VehicleService"
        private const val SERVICE_ACTION = "ai.pleos.playground.vehicle.VEHICLE_START"

        /**
         * 차량 서비스가 이 기기에 있으면 SDK 를 초기화해 소스를 만든다. 없거나(일반 Android 기기·에뮬레이터)
         * 초기화가 실패하면 이유를 로그에 남기고 null 을 돌려준다. SDK 클래스를 읽지 못하는 경우(Error)도 포함한다.
         */
        fun createOrNull(context: Context, clock: Clock): PleosSignalSource? = try {
            val packages = context.packageManager
            val service = Intent(SERVICE_ACTION).setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS))
            if (packages?.resolveService(service, 0) == null) {
                Log.i(TAG, "Pleos 차량 서비스가 없음 → 모의 신호 사용")
                null
            } else {
                val vehicle = Vehicle(context.applicationContext)
                vehicle.initialize()
                Log.i(TAG, "Pleos 차량 SDK 초기화")
                PleosSignalSource(vehicle, clock, FakeSignalSource(clock))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Pleos 차량 SDK 초기화 실패 → 모의 신호 사용", t)
            null
        }
    }
}

private fun SignalReading<Boolean>.brief(): String =
    if (availability == SignalAvailability.AVAILABLE) value.toString() else availability.label

private fun <T> CancellableContinuation<T>.resumeOnce(value: T) {
    if (isActive) resume(value)
}
