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
import app.baton.core.signal.SignalSnapshot
import app.baton.parking.BuildConfig
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Pleos Connect 차량 SDK(Vehicle)에서 읽는 실제 차량 신호. SDK 호출은 이 파일 한 곳에만 둔다. 모두 조회 전용이다.
 *
 * 읽는 것(운전석 = 좌핸들 기준 1열 왼쪽):
 * - 문: `Door.getCurrentDoorPosition` / `registerDoorPosition` (0 이면 닫힘, 0 보다 크면 열림)
 * - 착좌: `Seat.getCurrentSeatOccupancy` / `registerSeatOccupancy`
 * - 안전벨트: `Seat.getCurrentSeatBeltBuckledState` / `registerSeatBeltBuckledState`
 * - 브레이크 페달: `Brake.getCurrentBrakePedalStatus` / `registerBrakePedalStatus`
 * - 주차 브레이크 경고등: `Safety.getTelltalesInfo` / `registerTelltales` 의 PARKING_BRAKE·ELECTRONIC_PARKING_BRAKE 항목.
 *   SDK 2.3.2 구현은 이 둘에 `pleos.car.permission.PRIVATE_CAR_SAFETY` 를 요구한다(CAR_SAFETY 만으로는 거부된다).
 *
 * 에뮬레이터에서만 확인한 해석(착좌 반전, 벨트 제외, "경고등 목록에 없음 = 꺼짐")은 [VehicleProfile]이 확인된 환경일 때만 쓴다.
 * 확인하지 못한 환경에서는 착좌를 일시 불가로 두어 자동 예고를 끄고, 주차 브레이크 경고등은 빼고(페달·이동 대체 규칙),
 * 벨트는 SDK 지원 확인 결과를 따른다.
 *
 * 위치는 이 SDK 모듈에 없어 [mockLocation]의 모의 값을 그대로 쓴다([isMockLocation] = true).
 *
 * 순서([SignalSequencer]가 정리한다)
 * 1. [currentSnapshot]: 조회를 열고 → 변경 구독을 등록하고(이때부터 들어오는 값은 모아 둔다) → 신호마다 현재 값을 묻는다.
 *    답을 기준값(스냅샷)으로 돌려주고, 조회하는 동안 모아 둔 변경은 그 뒤에 [events]로 내보낸다.
 *    조회는 한 번에 하나만 돈다(주기적 확인과도 겹치지 않는다).
 * 2. 콜백은 들어온 순간에 순번·관측 시각·주차 세대를 찍고, 화면 스레드로 옮겨 처리할 때까지 그 표를 그대로 쓴다.
 * 3. [PROBE_INTERVAL_MILLIS]마다 현재 값을 다시 묻는다. 하나도 읽지 못하면 차량 신호를 일시 불가로 내보내고
 *    [link]를 연결 불가로 바꾼다. 다시 읽히면 [link]가 수신 중으로 돌아오고, 받는 쪽이 기준값을 다시 읽는다.
 */
class PleosSignalSource private constructor(
    private val vehicle: Vehicle,
    private val clock: Clock,
    /** 위치만 맡는 모의 소스. 시연 패널의 위치 스위치가 이것을 바꾼다. */
    override val mockLocation: FakeSignalSource,
    private val profile: VehicleProfile,
) : BatonSignalSource {
    override val name: String = "Pleos 차량 신호"
    override val isMock: Boolean = false
    override val isMockLocation: Boolean = true
    override val manualSignals: FakeSignalSource? = null

    // 지원 여부는 조회해 봐야 안다. 처음에는 모두 지원한다고 보고, 조회 결과로 미지원 / 일시 불가를 알린다.
    override val capabilities: SignalCapabilities = SignalCapabilities.ALL

    override val beltExcludedForDemo: Boolean = profile.beltExcludedForDemo

    override val environmentNotice: String? =
        if (profile.useSeatOccupancy) null
        else "${profile.label}: 착좌 값 해석을 확인하지 못해 자동 예고를 끔"

    override val readingSummary: String =
        if (profile.verified) {
            "문·착좌·브레이크 페달·주차 브레이크 경고등은 ${name}에서 읽는 중 · 벨트는 시연 설정으로 제외 (스위치 없음)"
        } else {
            "문·브레이크 페달(지원하면 벨트)만 ${name}에서 읽는 중 · 착좌·주차 브레이크 경고등은 확인 전이라 쓰지 않음"
        }

    @Volatile
    override var parkingGeneration: Long? = null

    private val _link = MutableStateFlow(LinkStatus.CONNECTING)
    override val link: StateFlow<LinkStatus> = _link.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val channel = Channel<SignalEvent>(Channel.UNLIMITED)
    private val sequencer = SignalSequencer()
    private val queryMutex = Mutex()
    private val closed = AtomicBoolean(false)

    override val events: Flow<SignalEvent> =
        merge(channel.receiveAsFlow(), mockLocation.events.filter { it.kind == SignalKind.LOCATION })

    private val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)

    // 아래 상태는 화면 스레드에서만 만진다.
    private var unsupported: Set<SignalKind>? = null
    private val registered = HashSet<SignalKind>()
    private val registerAttempts = HashMap<SignalKind, Int>()
    private var probeJob: Job? = null

    // 해제할 수 있게 리스너를 필드로 들고 있는다.
    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(position: Int?) = onListenerValue(SignalKind.DOOR, isOpen(position))
        override fun onFailed(e: Exception) = onListenerFailed(SignalKind.DOOR, e)
    }
    private val seatListener = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(raw: Boolean?) = onListenerValue(SignalKind.SEAT, occupied(raw))
        override fun onFailed(e: Exception) = onListenerFailed(SignalKind.SEAT, e)
    }
    private val beltListener = object : SeatBeltBuckledStateListener {
        override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) = onListenerValue(SignalKind.SEAT_BELT, buckled)
        override fun onFailed(e: Exception) = onListenerFailed(SignalKind.SEAT_BELT, e)
    }
    private val pedalListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) = onListenerValue(SignalKind.BRAKE_PEDAL, pressed)
        override fun onFailed(e: Exception) = onListenerFailed(SignalKind.BRAKE_PEDAL, e)
    }
    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(telltales: Telltales?) =
            onListenerValue(SignalKind.PARKING_BRAKE, parkingBrakeOn(telltales))
        override fun onFailed(e: Exception) = onListenerFailed(SignalKind.PARKING_BRAKE, e)
    }

    // ---- 현재 값 조회(기준값) ----------------------------------------------------

    override suspend fun currentSnapshot(): SignalSnapshot = withContext(Dispatchers.Main.immediate) {
        queryMutex.withLock {
            checkSupportOnce()
            val start = stampNow()
            sequencer.beginBaseline(start)
            var completed = false
            try {
                registerMissing() // 구독이 먼저다: 조회하는 동안의 변경을 놓치지 않는다.
                val result = sequencer.completeBaseline(fixedReadings() + queryActive())
                completed = true
                setLink(sequencer.status)
                if (result.pending.isNotEmpty()) {
                    // 받는 쪽이 이 기준값을 먼저 반영하도록, 모아 둔 변경은 이 호출이 돌아간 뒤에 내보낸다.
                    main.post { result.pending.forEach(::emit) }
                }
                startProbing()
                val at = start.atMillis
                fun level(kind: SignalKind) = (result.levels[kind] ?: VehicleReading.UNAVAILABLE).toSignal(at)
                Log.i(TAG, "현재 값 조회 완료: ${sequencer.status}" + if (BuildConfig.DEBUG) " · " + describe(result.levels) else "")
                SignalSnapshot(
                    door = level(SignalKind.DOOR),
                    seat = level(SignalKind.SEAT),
                    seatBelt = level(SignalKind.SEAT_BELT),
                    parkingBrake = level(SignalKind.PARKING_BRAKE),
                    brakePedal = level(SignalKind.BRAKE_PEDAL),
                    location = mockLocation.snapshotNow().location,
                    takenAtMillis = at,
                )
            } finally {
                if (!completed) sequencer.abortBaseline().forEach(::emit)
            }
        }
    }

    /** 판정에서 뺀 신호의 고정 상태. 조회하지도 구독하지도 않는다. */
    private fun fixedReadings(): Map<SignalKind, VehicleReading> = VEHICLE_KINDS.mapNotNull { kind ->
        when {
            kind in unsupported.orEmpty() -> kind to VehicleReading.UNSUPPORTED
            kind == SignalKind.SEAT_BELT && profile.beltExcludedForDemo -> kind to VehicleReading.UNSUPPORTED
            kind == SignalKind.SEAT && !profile.useSeatOccupancy -> kind to VehicleReading.UNAVAILABLE
            kind == SignalKind.PARKING_BRAKE && !profile.useParkingBrakeTelltale -> kind to VehicleReading.UNAVAILABLE
            else -> null
        }
    }.toMap()

    /** 지금 묻고 구독하는 신호. */
    private fun activeKinds(): List<SignalKind> = VEHICLE_KINDS.filter { it !in fixedReadings() }

    /** 쓰는 신호의 현재 값을 한꺼번에 묻는다. 신호마다 [QUERY_TIMEOUT_MILLIS] 안에 답이 없으면 일시 불가. */
    private suspend fun queryActive(): Map<SignalKind, VehicleReading> = coroutineScope {
        activeKinds().map { kind -> kind to async { query(kind) } }.associate { (kind, answer) -> kind to answer.await() }
    }

    private suspend fun query(kind: SignalKind): VehicleReading = when (kind) {
        SignalKind.DOOR -> query(kind) { ok, fail ->
            vehicle.getDoor().getCurrentDoorPosition(driverDoor, { ok(isOpen(it)) }, fail)
        }
        SignalKind.SEAT -> query(kind) { ok, fail ->
            vehicle.getSeat().getCurrentSeatOccupancy(driverSeat, { ok(occupied(it)) }, fail)
        }
        SignalKind.SEAT_BELT -> query(kind) { ok, fail ->
            vehicle.getSeat().getCurrentSeatBeltBuckledState(driverSeat, { ok(it) }, fail)
        }
        SignalKind.BRAKE_PEDAL -> query(kind) { ok, fail -> vehicle.getBrake().getCurrentBrakePedalStatus({ ok(it) }, fail) }
        SignalKind.PARKING_BRAKE -> query(kind) { ok, fail ->
            vehicle.getSafety().getTelltalesInfo({ ok(parkingBrakeOn(it)) }, fail)
        }
        SignalKind.LOCATION -> error("위치는 이 소스가 읽지 않는다")
    }

    /** 신호 하나의 현재 값을 SDK 에 묻는다. 실패·시간 초과·예외는 삼키지 않고 로그와 상태로 남긴다. */
    private suspend fun query(
        kind: SignalKind,
        call: (ok: (Boolean?) -> Unit, fail: (Exception) -> Unit) -> Unit,
    ): VehicleReading {
        val result = withTimeoutOrNull(QUERY_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { cont ->
                val once = Once(cont)
                try {
                    call(
                        { value ->
                            if (value == null) Log.w(TAG, "${kind.label}: 조회 값이 비어 있음")
                            once.resume(VehicleReading.of(value))
                        },
                        { error -> once.resume(VehicleReading(failure(kind, "조회", error))) },
                    )
                } catch (e: Exception) {
                    once.resume(VehicleReading(failure(kind, "조회 호출", e)))
                }
            }
        }
        return result ?: VehicleReading.UNAVAILABLE.also {
            Log.w(TAG, "${kind.label}: ${QUERY_TIMEOUT_MILLIS}ms 안에 조회 응답 없음 → 일시 불가")
        }
    }

    // ---- 지원 여부 확인 --------------------------------------------------------

    /**
     * 신호별 지원 여부를 한 번 확인한다(PRD F1). 운전석이 지원 영역에 없거나 읽기 권한이 없다고 나오면 미지원으로 둔다.
     * 확인 자체가 실패하면 미지원으로 단정하지 않고 값 조회 결과에 맡긴다.
     */
    private suspend fun checkSupportOnce() {
        if (unsupported != null) return
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
        unsupported = results.filter { it.second == false }.map { it.first }.toSet()
        Log.i(TAG, "환경: ${profile.label} (검증 ${if (profile.verified) "됨" else "안 됨"})")
        if (profile.beltExcludedForDemo) {
            // PRD 6장 대체 규칙: 벨트 체결을 시험할 수 없으면 착좌만 쓴다. 이 에뮬레이터에서만 적용한다.
            Log.i(TAG, "안전벨트: 시연 설정으로 판정에서 뺌(Virtual Console 에 벨트 조작이 없음) → 착좌만 사용")
        }
        if (!profile.useSeatOccupancy) Log.w(TAG, "착좌: 이 환경에서 값 해석을 확인하지 못해 쓰지 않음 → 자동 예고 꺼짐")
        if (!profile.useParkingBrakeTelltale) Log.w(TAG, "주차 브레이크 경고등: 이 환경에서 확인하지 못해 뺌 → 페달·이동 대체 규칙")
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
            val once = Once(cont)
            try {
                call(
                    { once.resume(it) },
                    { error ->
                        Log.w(TAG, "${kind.label}: 지원 확인 실패 (${error.message})")
                        once.resume(null)
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "${kind.label}: 지원 확인 호출 실패", e)
                once.resume(null)
            }
        }
    }

    private fun AccessType.canRead(): Boolean = this == AccessType.READ || this == AccessType.READ_WRITE

    // ---- 변경 구독 ------------------------------------------------------------

    /** 쓰는 신호 가운데 아직 구독하지 못한 것을 등록한다. 실패한 등록은 다음 확인 때 다시 시도한다([MAX_REGISTER_ATTEMPTS]번까지). */
    private fun registerMissing() {
        for (kind in activeKinds()) {
            if (kind in registered) continue
            val attempts = registerAttempts[kind] ?: 0
            if (attempts >= MAX_REGISTER_ATTEMPTS) continue
            registerAttempts[kind] = attempts + 1
            try {
                register(kind)
                registered += kind
                Log.i(TAG, "${kind.label}: 변경 구독 등록")
            } catch (e: Exception) {
                Log.w(TAG, "${kind.label}: 구독 등록 실패 (${attempts + 1}번째) → 현재 값 확인으로만 읽음", e)
            }
        }
    }

    private fun register(kind: SignalKind) {
        when (kind) {
            SignalKind.DOOR -> vehicle.getDoor().registerDoorPosition(driverDoor, doorListener)
            SignalKind.SEAT -> vehicle.getSeat().registerSeatOccupancy(driverSeat, seatListener)
            SignalKind.SEAT_BELT -> vehicle.getSeat().registerSeatBeltBuckledState(driverSeat, beltListener)
            SignalKind.BRAKE_PEDAL -> vehicle.getBrake().registerBrakePedalStatus(pedalListener)
            SignalKind.PARKING_BRAKE -> vehicle.getSafety().registerTelltales(telltaleListener)
            SignalKind.LOCATION -> Unit
        }
    }

    private fun unregister(kind: SignalKind) {
        try {
            when (kind) {
                SignalKind.DOOR -> vehicle.getDoor().unregisterDoorPosition(driverDoor, doorListener)
                SignalKind.SEAT -> vehicle.getSeat().unregisterSeatOccupancy(driverSeat, seatListener)
                SignalKind.SEAT_BELT -> vehicle.getSeat().unregisterSeatBeltBuckledState(driverSeat, beltListener)
                SignalKind.BRAKE_PEDAL -> vehicle.getBrake().unregisterBrakePedalStatus(pedalListener)
                SignalKind.PARKING_BRAKE -> vehicle.getSafety().unregisterTelltales(telltaleListener)
                SignalKind.LOCATION -> Unit
            }
        } catch (e: Exception) {
            Log.w(TAG, "${kind.label}: 구독 해제 실패", e)
        }
    }

    /** 구독 콜백(어느 스레드든). 들어온 순간에 순번·시각·주차 세대를 찍고, 처리는 화면 스레드에서 한다. */
    private fun onListenerValue(kind: SignalKind, value: Boolean?) {
        val stamp = stampNow()
        main.post {
            if (closed.get()) return@post
            if (value == null) Log.w(TAG, "${kind.label}: 빈 값 수신")
            sequencer.onListener(kind, VehicleReading.of(value), stamp)?.let(::emit)
        }
    }

    private fun onListenerFailed(kind: SignalKind, error: Exception) {
        val stamp = stampNow()
        main.post {
            if (closed.get()) return@post
            val availability = failure(kind, "수신", error)
            // 이 구독은 더 믿지 않는다: 풀어 두고 다음 확인 때 다시 등록한다.
            if (registered.remove(kind)) unregister(kind)
            sequencer.onListener(kind, VehicleReading(availability), stamp)?.let(::emit)
        }
    }

    private fun stampNow(): ObservationStamp = sequencer.stamp(clock.nowMillis(), parkingGeneration)

    private fun emit(change: VehicleChange) {
        if (BuildConfig.DEBUG) Log.i(TAG, "변경: ${change.kind.label} → ${change.reading.brief()}")
        channel.trySend(change.toEvent())
    }

    // ---- 주기적 확인(단절 감지) ----------------------------------------------------

    private fun startProbing() {
        if (probeJob != null || closed.get()) return
        probeJob = scope.launch {
            while (isActive) {
                delay(PROBE_INTERVAL_MILLIS)
                probeOnce()
            }
        }
    }

    /**
     * 현재 값을 다시 묻는다. 하나도 읽지 못하면(실패·무응답) 차량 신호를 일시 불가로 내보내 진행 중인 예고가 취소되게 한다.
     * 읽지 못하는 동안에는 확인할 때마다 SDK 연결을 놓았다가 다시 잡는다(SDK 가 스스로 다시 붙지 않는 것을 에뮬레이터에서 봤다).
     * 다시 읽히면 [link]를 수신 중으로 돌린다. 받는 쪽이 기준값을 다시 읽고, 그 조회가 구독도 다시 건다.
     */
    private suspend fun probeOnce() = queryMutex.withLock {
        val wasDown = sequencer.status == LinkStatus.UNAVAILABLE
        if (!wasDown) registerMissing()
        val start = stampNow()
        val answers = queryActive()
        val result = sequencer.onProbe(start, answers, stampNow())
        result.changes.forEach(::emit)
        if (sequencer.status == LinkStatus.UNAVAILABLE) reconnect()
        setLink(sequencer.status)
    }

    /** 서비스 연결을 놓았다가 다시 잡는다. 예전 구독은 믿지 않고 모두 풀어, 다시 읽힌 뒤에 새로 건다. */
    private fun reconnect() {
        Log.w(TAG, "차량 서비스 다시 연결 시도")
        registered.toList().forEach(::unregister)
        registered.clear()
        registerAttempts.clear()
        try {
            vehicle.release()
            vehicle.initialize()
        } catch (e: Exception) {
            Log.w(TAG, "차량 서비스 다시 연결 실패", e)
        }
    }

    private fun setLink(status: LinkStatus) {
        if (_link.value == status) return
        when (status) {
            LinkStatus.UNAVAILABLE -> Log.w(TAG, "차량 서비스에서 값을 읽지 못함 → 차량 신호 일시 불가(자동 예고 정지)")
            LinkStatus.RECEIVING -> Log.i(TAG, "차량 서비스에서 값을 읽는 중")
            LinkStatus.CONNECTING -> Unit
        }
        _link.value = status
    }

    // ---- 수명 ----------------------------------------------------------------

    /** 구독을 풀고 SDK 연결을 놓는다. 그 뒤에 늦게 오는 콜백은 버린다. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        registered.toList().forEach(::unregister)
        registered.clear()
        try {
            vehicle.release()
        } catch (e: Exception) {
            Log.w(TAG, "Pleos 차량 SDK 해제 실패", e)
        }
        channel.close()
        mockLocation.close()
        Log.i(TAG, "Pleos 차량 SDK 해제")
    }

    // ---- 값 해석 --------------------------------------------------------------

    private fun isOpen(position: Int?): Boolean? = position?.let { it > 0 }

    /**
     * SDK 가 준 착좌 값을 실제 착좌 여부로 바꾼다.
     *
     * Vehicle SDK 2.3.2 는 원시 값이 1 일 때 true 를 준다. 그런데 Pleos Connect v2.1.0 에뮬레이터는
     * Virtual Console 의 "Occupied"를 2, "Empty"를 1 로 보낸다(Android 표준 VehicleSeatOccupancyState 와 같다:
     * VACANT = 1, OCCUPIED = 2). 그래서 콘솔에서 "Occupied"로 두면 SDK 값은 false 로 온다(에뮬레이터에서 확인).
     * 이 조합([VehicleProfile.PLEOS_CONNECT_EMULATOR])에서만 뒤집어 읽는다. 다른 환경에서는 착좌를 아예 쓰지 않는다.
     */
    private fun occupied(raw: Boolean?): Boolean? = raw?.let { if (profile.seatOccupancyInverted) !it else it }

    /**
     * 경고등 목록에서 주차 브레이크(기계식 또는 전자식) 항목을 찾는다. 켜짐·깜빡임이면 true.
     * 에뮬레이터에서는 목록에 켜진 경고등만 들어 있다(끄면 항목이 사라진다). 그래서 항목이 없으면 꺼짐으로 읽는다.
     * 이 해석은 확인된 환경에서만 쓰고, 다른 환경에서는 이 신호를 묻지도 구독하지도 않는다.
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

    private fun describe(levels: Map<SignalKind, VehicleReading>): String =
        VEHICLE_KINDS.joinToString(" ") { "${it.label}=${levels[it]?.brief() ?: "-"}" }

    /** 성공·실패 콜백이 겹쳐 들어와도 한 번만 이어 간다. */
    private class Once<T>(private val continuation: CancellableContinuation<T>) {
        private val done = AtomicBoolean(false)

        fun resume(value: T) {
            if (done.compareAndSet(false, true)) continuation.resume(value)
        }
    }

    companion object {
        private const val TAG = "BatonPleos"
        private const val QUERY_TIMEOUT_MILLIS = 3_000L
        private const val PROBE_INTERVAL_MILLIS = 10_000L
        private const val MAX_REGISTER_ATTEMPTS = 3
        private val VEHICLE_KINDS = listOf(
            SignalKind.DOOR, SignalKind.SEAT, SignalKind.SEAT_BELT, SignalKind.PARKING_BRAKE, SignalKind.BRAKE_PEDAL,
        )

        private const val SERVICE_PACKAGE = "ai.pleos.playground.service.vehicle"
        private const val SERVICE_CLASS = "ai.pleos.playground.service.vehicle.VehicleService"
        private const val SERVICE_ACTION = "ai.pleos.playground.vehicle.VEHICLE_START"

        /**
         * 차량 서비스가 이 기기에 있으면 SDK 를 초기화해 소스를 만든다. 없거나(일반 Android 기기·에뮬레이터)
         * 초기화가 실패하면 이유를 로그에 남기고 null 을 돌려준다.
         * SDK 클래스를 읽지 못해 이 함수에 들어오기도 전에 나는 오류는 [SignalSourceProvider]가 잡는다.
         */
        fun createOrNull(context: Context, clock: Clock, forceUnverifiedProfile: Boolean = false): PleosSignalSource? = try {
            val packages = context.packageManager
            val service = Intent(SERVICE_ACTION).setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS))
            if (packages?.resolveService(service, 0) == null) {
                Log.i(TAG, "Pleos 차량 서비스가 없음 → 모의 신호 사용")
                null
            } else {
                val profile =
                    if (forceUnverifiedProfile) VehicleProfile.UNVERIFIED else VehicleProfile.forFingerprint(Build.FINGERPRINT)
                val vehicle = Vehicle(context.applicationContext)
                vehicle.initialize()
                Log.i(TAG, "Pleos 차량 SDK 초기화")
                PleosSignalSource(vehicle, clock, FakeSignalSource(clock), profile)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pleos 차량 SDK 초기화 실패 → 모의 신호 사용", e)
            null
        } catch (e: LinkageError) {
            Log.w(TAG, "Pleos 차량 SDK 클래스를 읽지 못함 → 모의 신호 사용", e)
            null
        }
    }
}
