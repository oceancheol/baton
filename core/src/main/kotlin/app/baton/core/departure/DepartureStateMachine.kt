package app.baton.core.departure

import app.baton.core.Clock
import app.baton.core.signal.LocationFix
import app.baton.core.signal.LocationQuality
import app.baton.core.signal.SignalAvailability
import app.baton.core.signal.SignalCapabilities
import app.baton.core.signal.SignalEvent
import app.baton.core.signal.SignalKind
import app.baton.core.signal.SignalReading
import app.baton.core.signal.SignalSnapshot
import app.baton.core.signal.SnapshotRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.EnumMap

/**
 * 출차 판정 상태기계 (PRD F1).
 *
 * PARKED → BOARDING → ANNOUNCING → DEPARTING → VACATED
 *
 * - Android·SDK에 의존하지 않는다. 시간은 [Clock]으로만 읽는다.
 * - 입력 함수([restore], [onSignal], [tick], [suppressThisParking], [startNewParking])는 그 입력 하나로 생긴
 *   기록을 순서대로 돌려준다. 예고·안내·취소는 이 반환값을 차례대로 적용해 전달한다.
 * - 최소 신호(운전석 착좌, 위치)를 읽지 못하면 자동 예고를 끈다. 보조 신호(문, 벨트, 주차 브레이크 경고등, 페달)는
 *   미지원·일시 불가이면 빼고 대체 규칙(PRD 6장)으로 판정한다. 아직 값을 받지 못한(UNKNOWN) 신호는 기다린다.
 * - 예고 시작(ANNOUNCING 진입)부터 120초를 한 번만 센다. 단계가 바뀌어도 다시 세지 않는다.
 * - 스냅샷([restore], [startNewParking])도 개별 신호와 같은 검사를 거친다: 다른 주차 세대·지난 조회 요청의 스냅샷은
 *   통째로 버리고, 신호마다 이미 반영한 관측보다 오래된 값은 덮어쓰지 않는다. 새 주차를 시작한 시각 이전의 관측은
 *   신호든 스냅샷이든 받지 않는다.
 * - 위치 품질이 바뀌면 상태 전이가 없어도 [TransitionReason.LOCATION_QUALITY_CHANGED] 기록을 낸다
 *   (안내 쪽이 진행 중인 안내를 바로 구역 안내로 낮추는 근거).
 */
class DepartureStateMachine(
    private val clock: Clock,
    capabilities: SignalCapabilities = SignalCapabilities.ALL,
    private val config: DepartureConfig = DepartureConfig(),
) {
    private var state = DepartureState.PARKED
    private var stateEnteredAt = clock.nowMillis()
    private var suppressed = false

    /** 주차 세대. 새 주차마다 1 오른다. 다른 세대의 신호는 거부한다. */
    private var parkingGeneration = 1L

    /** 예고 세대. ANNOUNCING 에 들어갈 때마다 1 오른다(주차가 바뀌어도 이어 센다). */
    private var announcementCounter = 0L
    private var activeAnnouncement: Long? = null
    private var announcedAt: Long? = null

    // 신호별 상태·현재 값·갱신 시각
    private val readings = EnumMap<SignalKind, SignalReading<Boolean>>(SignalKind::class.java).apply {
        put(SignalKind.DOOR, initial(capabilities.door))
        put(SignalKind.SEAT, initial(capabilities.seatOccupancy))
        put(SignalKind.SEAT_BELT, initial(capabilities.seatBelt))
        put(SignalKind.PARKING_BRAKE, initial(capabilities.parkingBrakeTelltale))
        put(SignalKind.BRAKE_PEDAL, initial(capabilities.brakePedal))
    }
    private var location: SignalReading<LocationFix> =
        if (capabilities.location) SignalReading() else SignalReading.unsupported()
    private val lastObservedAt = EnumMap<SignalKind, Long>(SignalKind::class.java)
    private var rejectedEvents = 0
    private var rejectedSnapshots = 0

    // 현재 값 조회 요청: 내준 번호와, 답을 반영한 마지막 번호
    private var snapshotRequestCounter = 0L
    private var lastAppliedSnapshotRequest = 0L

    // 이번 주차에서 실제로 본 변화
    private var doorOpenedWhileParked = false
    private var doorCycled = false
    private var restoredOccupied = false
    private var parkingBrakeReleasedSeen = false
    private var pedalSeen = false

    // 새 주차를 시작했지만 그 주차의 기준값(현재 신호 값)을 아직 받지 못했다. 받을 때까지 PARKED 에 머문다.
    private var awaitingParkingBaseline = false

    // 문 신호를 읽지 못할 때: 착좌가 풀려 있던 적이 있어야(=내렸다 다시 탔어야) 탑승으로 본다.
    private var doorlessArmed = true

    // 취소 유예 타이머
    private var doorReopenedAt: Long? = null
    private var seatVacatedAt: Long? = null

    // 로그: 보관량 제한 + 순번
    private val history = ArrayDeque<DepartureLogEvent>()
    private var nextSeq = 1L
    private val produced = ArrayList<DepartureLogEvent>()

    private val _snapshot = MutableStateFlow(buildSnapshot())

    /** 현재 상태와 읽은 신호(화면 표시용. 제어 전달에는 입력 함수의 반환값을 쓴다). */
    val snapshot: StateFlow<DepartureSnapshot> = _snapshot.asStateFlow()

    val currentState: DepartureState
        @Synchronized get() = state

    /** 보관 중인 로그 (최근 [DepartureConfig.maxLogEvents]줄). */
    @Synchronized
    fun log(): List<DepartureLogEvent> = history.toList()

    /** [afterSeq]보다 순번이 큰 기록만. 화면 로그는 마지막으로 읽은 순번을 기억했다가 이걸로 새 줄만 읽는다. */
    @Synchronized
    fun eventsSince(afterSeq: Long): List<DepartureLogEvent> = history.filter { it.seq > afterSeq }

    // ---- 입력 --------------------------------------------------------------

    /**
     * 구독을 시작할 때(또는 다시 연결됐을 때) 현재 신호 값으로 상태를 복원한다. 이 값들은 '변화'로 세지 않는다.
     *
     * - 문이 닫혀 있다고 "열렸다 닫힘"으로 보지 않고, 경고등이 꺼져 있다고 "방금 해제"로 보지 않는다.
     * - 주차 상태에서 이미 운전석에 앉아 있으면 탑승한 것으로 복원한다(BOARDING). 착좌(읽히면 벨트까지)는
     *   상태 조건이므로 바로 예고(ANNOUNCING)가 시작될 수 있고, 120초는 이때부터 센다.
     * - 경고등이 이미 꺼져 있었다면 해제 변화를 볼 수 없으므로, 이후 페달 밟음이나 "꺼진 상태 + 이동 시작"으로
     *   출차 임박을 판정한다.
     * - 예고 중에 다시 읽었는데 문이 열려 있거나 착좌가 풀려 있으면 그 시점부터 60초 유예를 센다.
     *
     * 오래된 스냅샷은 적용하지 않는다(늦게 돌아온 조회가 새 주차를 '비었음'으로 만들지 않게):
     * - [SignalSnapshot.parkingGeneration]이 현재 주차 세대와 다르거나, [SignalSnapshot.requestId]가 이미 반영한
     *   요청보다 앞서면 통째로 거부한다([TransitionReason.STALE_SNAPSHOT_REJECTED] 기록만 남는다).
     * - 신호마다 스냅샷의 관측 시각(takenAtMillis)이 이미 반영한 관측보다 오래됐으면 그 신호는 지금 값을 그대로 둔다.
     *
     * 새 주차의 기준값을 기다리는 중이면(`startNewParking(awaitBaseline = true)`) 이 복원은 "최초 연결 복원"이 아니라
     * "새 주차의 기준값"이다. 이때는 앉아 있어도 탑승으로 복원하지 않는다: 방금 주차한 운전자가 아직 앉아 있을 뿐이므로
     * 문 여닫힘(문 신호가 없으면 하차 후 재착좌)을 기다린다.
     *
     * [reconnect]가 true 이면 실행 중에 끊겼다 다시 붙어 읽은 값이다(앱이 처음 연결될 때의 복원이 아니다).
     * 지금 상태를 그대로 두고 신호 값만 다시 맞춘다: PARKED 였으면 앉아 있어도 탑승으로 복원하지 않고, 새 주차의 기준값과
     * 같이 문 여닫힘(문 신호가 없으면 하차 후 재착좌)을 기다린다. BOARDING 이었고 이미 앉아 있으면 PARKED 로 되돌린다
     * (끊긴 동안 문 신호를 읽지 못해 대체 규칙으로 BOARDING 이 됐을 수 있다). 끊긴 동안 취소된 예고가 복구만으로 다시
     * 시작되지 않는다.
     */
    @Synchronized
    fun restore(snapshot: SignalSnapshot, reconnect: Boolean = false): List<DepartureLogEvent> = input {
        checkTimeouts()
        val previousQuality = location.usableValue?.quality
        if (applyLevels(snapshot) == null) {
            evaluate()
            return@input
        }
        noteQualityChange(previousQuality)
        val newParkingBaseline = awaitingParkingBaseline
        awaitingParkingBaseline = false
        when (state) {
            DepartureState.PARKED -> {
                doorOpenedWhileParked = value(SignalKind.DOOR) == true
                if (newParkingBaseline || reconnect) {
                    // 새 주차의 기준값·재연결 복원: 남아 있는 착좌는 탑승으로 세지 않는다.
                    doorlessArmed = value(SignalKind.SEAT) != true
                } else if (value(SignalKind.SEAT) == true) {
                    restoredOccupied = true
                }
            }
            DepartureState.ANNOUNCING, DepartureState.DEPARTING -> {
                val now = clock.nowMillis()
                doorReopenedAt = if (value(SignalKind.DOOR) == true) doorReopenedAt ?: now else null
                seatVacatedAt = if (value(SignalKind.SEAT) == false) seatVacatedAt ?: now else null
            }
            DepartureState.BOARDING ->
                // 끊긴 동안에는 문 신호도 읽지 못해 "문 없이 착좌로 판정"하는 대체 규칙으로 BOARDING 에 와 있을 수 있다.
                // 다시 붙었을 때 이미 앉아 있으면 그 착좌는 본 변화가 아니므로 PARKED 로 되돌려 문 여닫힘(하차 후 재착좌)을 기다린다.
                if (reconnect && value(SignalKind.SEAT) == true) cancelToParked(TransitionReason.RECONNECTED_OCCUPIED)
            DepartureState.VACATED -> Unit
        }
        note(TransitionReason.RESTORED)
        evaluate()
    }

    @Synchronized
    fun onSignal(event: SignalEvent): List<DepartureLogEvent> = input {
        checkTimeouts()
        if (accept(event)) applyEvent(event)
        evaluate()
    }

    /** 시간 경과만으로 일어나는 취소를 판정한다. 주기적으로 부른다. */
    @Synchronized
    fun tick(): List<DepartureLogEvent> = input {
        checkTimeouts()
        evaluate()
    }

    /**
     * "이번엔 예고 안 함". 이번 주차가 끝날 때까지 예고하지 않는다.
     * 차가 움직이는 중(DEPARTING 이후, 또는 이동이 관측된 뒤)에는 받지 않는다. 받았는지는 스냅샷의 suppressed 로 안다.
     */
    @Synchronized
    fun suppressThisParking(): List<DepartureLogEvent> = input {
        checkTimeouts()
        when {
            suppressed || state == DepartureState.VACATED -> Unit
            !canSuppressNow() -> note(TransitionReason.SUPPRESS_REJECTED)
            else -> {
                suppressed = true
                if (state != DepartureState.PARKED) {
                    cancelToParked(TransitionReason.SUPPRESSED_BY_DRIVER)
                } else {
                    note(TransitionReason.SUPPRESSED_BY_DRIVER)
                }
            }
        }
    }

    /**
     * 새로 주차했을 때 부른다. 주차 세대를 올리고 "예고 안 함"과 이번 주차에서 본 변화를 지운다.
     * [snapshot]을 주면 그 값을 현재 상태로 삼는다. 남아 있는 최신 값(앉아 있음, 벨트 맴)은 탑승으로 세지 않는다:
     * 방금 주차한 운전자가 아직 앉아 있을 뿐이므로, 문을 열었다 닫거나(문 신호가 없으면 착좌가 한 번 풀려야) 다시 판정한다.
     *
     * 세대는 이 호출에서 **한 번만** 오른다. 현재 값을 비동기로 읽어야 하면 스냅샷 없이 이 함수를 부른 뒤
     * [newSnapshotRequest]로 받은 표를 붙여 [restore]로 넣는다(이 함수를 두 번 부르지 않는다).
     * [snapshot]에 주차 세대가 적혀 있으면 새 세대와 같아야 하고, 이미 반영한 관측보다 오래된 값은 쓰지 않는다.
     * 이 시각 이전의 관측은 이후에 신호로 오든 스냅샷으로 오든 받지 않는다.
     *
     * [awaitBaseline]이 true 이면(스냅샷 없이 부를 때만 뜻이 있다) 다음에 반영되는 [restore]를 이 주차의 기준값으로 삼고,
     * 그때까지 PARKED 에 머문다. 그 복원에서는 "이미 앉아 있음"을 탑승으로 보지 않는다(스냅샷을 함께 준 경우와 같은 규칙).
     * 기다리는 동안 본 문 여닫힘은 기준값을 받은 뒤 그대로 탑승 판정에 쓴다.
     */
    @Synchronized
    fun startNewParking(
        snapshot: SignalSnapshot? = null,
        awaitBaseline: Boolean = false,
    ): List<DepartureLogEvent> = input {
        val from = state
        val endedAnnouncement = activeAnnouncement
        val now = clock.nowMillis()
        parkingGeneration += 1
        suppressed = false
        val applied = snapshot?.let(::applyLevels)
        if (applied == null || SignalKind.LOCATION !in applied) {
            // 주차 지점이 바뀌었으므로 이전 기준점의 거리는 버린다.
            location.usableValue?.let { location = SignalReading.available(it.copy(metersFromSpot = 0.0), now) }
        }
        // 새 주차 이전의 관측은 어떤 신호든 더 받지 않는다.
        for (kind in SignalKind.entries) lastObservedAt[kind] = maxOf(lastObservedAt[kind] ?: now, now)
        resetParkingObservations()
        doorlessArmed = value(SignalKind.SEAT) != true
        awaitingParkingBaseline = awaitBaseline && applied == null
        state = DepartureState.PARKED
        stateEnteredAt = now
        emit(from, DepartureState.PARKED, TransitionReason.NEW_PARKING, announcement = endedAnnouncement)
        activeAnnouncement = null
        announcedAt = null
        evaluate()
    }

    /**
     * 현재 값 조회를 시작할 때 받아 두는 표. 답으로 온 스냅샷에 [SignalSnapshot.answering]으로 붙여 [restore]에 넣으면,
     * 그 사이 주차가 바뀌었거나 더 나중 조회의 답이 먼저 반영된 경우 그 스냅샷은 버려진다.
     */
    @Synchronized
    fun newSnapshotRequest(): SnapshotRequest = SnapshotRequest(parkingGeneration, ++snapshotRequestCounter)

    private inline fun input(block: () -> Unit): List<DepartureLogEvent> {
        produced.clear()
        block()
        _snapshot.value = buildSnapshot()
        return produced.toList()
    }

    // ---- 신호 처리 -------------------------------------------------------

    /** 다른 주차 세대의 신호, 같은 신호의 더 오래된 관측은 받지 않는다. */
    private fun accept(event: SignalEvent): Boolean {
        val generation = event.parkingGeneration
        if (generation != null && generation != parkingGeneration) {
            reject("${event.kind.label}: 주차 세대 $generation (현재 $parkingGeneration)")
            return false
        }
        val observedAt = event.observedAtMillis
        val last = lastObservedAt[event.kind]
        if (observedAt != null && last != null && observedAt < last) {
            reject("${event.kind.label}: 이미 반영한 값보다 ${last - observedAt}ms 오래된 관측")
            return false
        }
        lastObservedAt[event.kind] = observedAt ?: clock.nowMillis()
        return true
    }

    private fun reject(detail: String) {
        rejectedEvents += 1
        note(TransitionReason.STALE_EVENT_REJECTED, "오래된 신호 거부 · $detail")
    }

    private fun applyEvent(event: SignalEvent) {
        val at = event.observedAtMillis ?: clock.nowMillis()
        when (event) {
            is SignalEvent.DriverDoor -> applyBoolean(SignalKind.DOOR, event.open, at)
            is SignalEvent.DriverSeat -> applyBoolean(SignalKind.SEAT, event.occupied, at)
            is SignalEvent.DriverSeatBelt -> applyBoolean(SignalKind.SEAT_BELT, event.fastened, at)
            is SignalEvent.ParkingBrakeTelltale -> applyBoolean(SignalKind.PARKING_BRAKE, event.on, at)
            is SignalEvent.BrakePedal -> applyBoolean(SignalKind.BRAKE_PEDAL, event.pressed, at)
            is SignalEvent.DistanceFromSpot ->
                // 미지원으로 확정된 신호는 값이 들어와도 쓰지 않는다.
                if (location.availability != SignalAvailability.UNSUPPORTED) {
                    val previousQuality = location.usableValue?.quality
                    location = SignalReading.available(LocationFix(event.meters, event.quality), at)
                    noteQualityChange(previousQuality)
                }
            is SignalEvent.AvailabilityChanged -> applyAvailability(event.kind, event.availability, at)
        }
    }

    private fun applyBoolean(kind: SignalKind, new: Boolean, at: Long) {
        val current = readings.getValue(kind)
        if (current.availability == SignalAvailability.UNSUPPORTED) return
        val previous = current.usableValue
        readings[kind] = SignalReading.available(new, at)
        when (kind) {
            SignalKind.DOOR -> onDoor(previous, new)
            SignalKind.SEAT -> onSeat(previous, new)
            SignalKind.PARKING_BRAKE ->
                // 켜져 있던 경고등이 탑승 이후에 꺼진 경우만 "해제"로 본다.
                if (previous == true && !new && state != DepartureState.PARKED) parkingBrakeReleasedSeen = true
            SignalKind.BRAKE_PEDAL ->
                if (new && previous != true && state != DepartureState.PARKED) pedalSeen = true
            SignalKind.SEAT_BELT, SignalKind.LOCATION -> Unit
        }
    }

    private fun applyAvailability(kind: SignalKind, availability: SignalAvailability, at: Long) {
        if (kind == SignalKind.LOCATION) {
            location = location.copy(availability = normalize(availability, location.value), updatedAtMillis = at)
        } else {
            val current = readings.getValue(kind)
            readings[kind] = current.copy(availability = normalize(availability, current.value), updatedAtMillis = at)
            // 읽지 못하게 된 신호로는 유예 타이머를 돌리지 않는다.
            if (readings.getValue(kind).excluded) {
                if (kind == SignalKind.DOOR) doorReopenedAt = null
                if (kind == SignalKind.SEAT) seatVacatedAt = null
            }
        }
        note(TransitionReason.NOTE, "${kind.label} 신호 상태: ${availability.label}")
    }

    /** 값 없이 "사용 가능"이라고만 알려 오면 값이 올 때까지 대기로 둔다. */
    private fun normalize(availability: SignalAvailability, value: Any?): SignalAvailability =
        if (availability == SignalAvailability.AVAILABLE && value == null) SignalAvailability.UNKNOWN else availability

    private fun onDoor(previous: Boolean?, open: Boolean) {
        when (state) {
            DepartureState.PARKED -> {
                if (open) doorOpenedWhileParked = true
                else if (doorOpenedWhileParked) doorCycled = true
            }
            DepartureState.ANNOUNCING, DepartureState.DEPARTING -> {
                if (open && previous != true) {
                    doorReopenedAt = clock.nowMillis()
                    note(TransitionReason.NOTE, "문이 다시 열림: 60초 안에 닫히지 않으면 취소")
                } else if (!open && doorReopenedAt != null) {
                    doorReopenedAt = null
                    note(TransitionReason.NOTE, "문이 다시 닫힘: 예고 유지")
                }
            }
            // BOARDING 중의 문 상태는 값으로 계속 추적하고, 열린 채 예고가 시작되면 그때부터 60초를 센다.
            DepartureState.BOARDING, DepartureState.VACATED -> Unit
        }
    }

    private fun onSeat(previous: Boolean?, occupied: Boolean) {
        if (!occupied && state == DepartureState.PARKED) doorlessArmed = true
        if (state == DepartureState.ANNOUNCING || state == DepartureState.DEPARTING) {
            if (!occupied && previous != false) {
                seatVacatedAt = clock.nowMillis()
                note(TransitionReason.NOTE, "착좌가 풀림: 60초 안에 돌아오지 않으면 취소")
            } else if (occupied && seatVacatedAt != null) {
                seatVacatedAt = null
                note(TransitionReason.NOTE, "다시 착좌: 예고 유지")
            }
        }
    }

    /**
     * 위치 품질이 바뀌었으면 전이와 무관한 기록을 낸다. 처음 받은 값이 HIGH 인 것은 변화로 치지 않는다.
     * 안내 쪽 정책: LOW 가 되면 진행 중인 안내를 바로 구역으로 낮추고, 다시 HIGH 가 돼도 이미 낮춘 안내는 줄로 되돌리지 않는다.
     */
    private fun noteQualityChange(previous: LocationQuality?) {
        val current = location.usableValue?.quality ?: return
        if (current == previous) return
        if (previous == null && current == LocationQuality.HIGH) return
        note(
            TransitionReason.LOCATION_QUALITY_CHANGED,
            if (current == LocationQuality.LOW) "위치 정확도 낮아짐: 줄 대신 구역까지만 안내"
            else "위치 정확도 회복: 이미 구역으로 낮춘 안내는 그대로 둠",
        )
    }

    private fun rejectSnapshot(detail: String) {
        rejectedSnapshots += 1
        note(TransitionReason.STALE_SNAPSHOT_REJECTED, "오래된 스냅샷 거부 · $detail")
    }

    /**
     * 스냅샷의 값을 현재 상태로만 넣는다. 변화(열림→닫힘, 해제, 밟음)로는 세지 않는다.
     *
     * 다른 주차 세대이거나 이미 반영한 조회 요청보다 앞선 스냅샷은 통째로 거부한다. 통과한 스냅샷도 신호마다
     * 관측 시각(takenAtMillis)이 이미 반영한 관측보다 오래됐으면 그 신호는 건드리지 않는다.
     * @return 값을 반영한 신호들. 통째로 거부했으면(반영한 신호가 하나도 없는 경우 포함) null.
     */
    private fun applyLevels(snapshot: SignalSnapshot): Set<SignalKind>? {
        val generation = snapshot.parkingGeneration
        if (generation != null && generation != parkingGeneration) {
            rejectSnapshot("주차 세대 $generation (현재 $parkingGeneration)")
            return null
        }
        val requestId = snapshot.requestId
        if (requestId != null && requestId <= lastAppliedSnapshotRequest) {
            rejectSnapshot("조회 요청 $requestId (이미 반영한 요청 $lastAppliedSnapshotRequest)")
            return null
        }
        val at = snapshot.takenAtMillis
        val fresh = SignalKind.entries.filterTo(HashSet()) { kind -> lastObservedAt[kind]?.let { at >= it } ?: true }
        if (fresh.isEmpty()) {
            rejectSnapshot("모든 신호가 이미 반영한 관측보다 오래됨")
            return null
        }
        if (requestId != null) lastAppliedSnapshotRequest = requestId

        fun put(kind: SignalKind, reading: SignalReading<Boolean>) {
            if (kind !in fresh) return
            readings[kind] = reading.copy(
                availability = normalize(reading.availability, reading.value),
                updatedAtMillis = reading.updatedAtMillis ?: at,
            )
            lastObservedAt[kind] = at
        }
        put(SignalKind.DOOR, snapshot.door)
        put(SignalKind.SEAT, snapshot.seat)
        put(SignalKind.SEAT_BELT, snapshot.seatBelt)
        put(SignalKind.PARKING_BRAKE, snapshot.parkingBrake)
        put(SignalKind.BRAKE_PEDAL, snapshot.brakePedal)
        if (SignalKind.LOCATION in fresh) {
            location = snapshot.location.copy(
                availability = normalize(snapshot.location.availability, snapshot.location.value),
                updatedAtMillis = snapshot.location.updatedAtMillis ?: at,
            )
            lastObservedAt[SignalKind.LOCATION] = at
        }
        val stale = SignalKind.entries.filter { it !in fresh }
        if (stale.isNotEmpty()) {
            note(TransitionReason.NOTE, "스냅샷에서 더 새 관측이 있는 신호는 그대로 둠: " + stale.joinToString(", ") { it.label })
        }
        return fresh
    }

    // ---- 판정 ------------------------------------------------------------

    private fun value(kind: SignalKind): Boolean? = readings.getValue(kind).usableValue

    private fun excluded(kind: SignalKind): Boolean = readings.getValue(kind).excluded

    private fun distance(): Double? = location.usableValue?.metersFromSpot

    private fun movedAtLeast(meters: Double): Boolean = (distance() ?: 0.0) >= meters

    /** 최소 신호 조건: 운전석 착좌와 위치를 둘 다 읽을 수 있어야 자동 예고를 한다. 보조 신호 대체 규칙과는 별개다. */
    private fun autoAnnounceStatus(): AutoAnnounceStatus {
        val problems = listOfNotNull(
            minimumProblem(SignalKind.SEAT, readings.getValue(SignalKind.SEAT).availability),
            minimumProblem(SignalKind.LOCATION, location.availability),
        )
        return if (problems.isEmpty()) AutoAnnounceStatus(true, null)
        else AutoAnnounceStatus(false, "자동 예고 꺼짐 · " + problems.joinToString(", "))
    }

    private fun minimumProblem(kind: SignalKind, availability: SignalAvailability): String? = when (availability) {
        SignalAvailability.AVAILABLE -> null
        SignalAvailability.UNKNOWN -> "${kind.label} 신호 수신 대기"
        SignalAvailability.UNSUPPORTED -> "${kind.label} 신호 미지원"
        SignalAvailability.UNAVAILABLE -> "${kind.label} 신호 일시 불가"
    }

    private fun canSuppressNow(): Boolean =
        !suppressed && state != DepartureState.DEPARTING && state != DepartureState.VACATED &&
            !movedAtLeast(config.moveStartMeters)

    private fun evaluate() {
        while (true) {
            val next = nextTransition() ?: return
            if (next.first == DepartureState.PARKED) cancelToParked(next.second)
            else transition(next.first, next.second)
        }
    }

    private fun nextTransition(): Pair<DepartureState, TransitionReason>? {
        val auto = autoAnnounceStatus().enabled
        return when (state) {
            DepartureState.PARKED -> when {
                suppressed ->
                    if (movedAtLeast(config.vacateMeters)) {
                        DepartureState.VACATED to TransitionReason.SUPPRESSED_LEFT
                    } else null
                awaitingParkingBaseline -> null // 새 주차의 기준값을 받을 때까지 판정하지 않는다
                excluded(SignalKind.DOOR) ->
                    if (doorlessArmed) DepartureState.BOARDING to TransitionReason.DOOR_UNSUPPORTED else null
                doorCycled -> DepartureState.BOARDING to TransitionReason.DOOR_OPEN_CLOSE
                restoredOccupied -> DepartureState.BOARDING to TransitionReason.RESTORED_OCCUPIED
                else -> null
            }
            DepartureState.BOARDING ->
                if (auto) boardingReason()?.let { DepartureState.ANNOUNCING to it } else null
            DepartureState.ANNOUNCING ->
                if (!auto) DepartureState.PARKED to TransitionReason.MIN_SIGNAL_LOST
                else departReason()?.let { DepartureState.DEPARTING to it }
            DepartureState.DEPARTING -> when {
                !auto -> DepartureState.PARKED to TransitionReason.MIN_SIGNAL_LOST
                movedAtLeast(config.vacateMeters) -> DepartureState.VACATED to TransitionReason.LEFT_SPOT
                else -> null
            }
            DepartureState.VACATED -> null
        }
    }

    /** 2단계: 운전석 착좌(벨트 체결이 읽히면 함께). 착좌는 최소 신호라 대체하지 않는다. */
    private fun boardingReason(): TransitionReason? {
        if (value(SignalKind.SEAT) != true) return null
        return when {
            excluded(SignalKind.SEAT_BELT) -> TransitionReason.SEATED
            value(SignalKind.SEAT_BELT) == true -> TransitionReason.SEATED_AND_BELTED
            else -> null // 벨트를 안 맸거나 아직 값을 받지 못함
        }
    }

    /**
     * 3단계: 주차 브레이크 경고등 꺼짐 또는 브레이크 페달.
     * 경고등을 읽지 못하면 페달 + 이동 시작, 페달도 읽지 못하면 이동 시작만으로 판정한다. 위치는 최소 신호라 항상 읽힌다.
     */
    private fun departReason(): TransitionReason? {
        val moved = movedAtLeast(config.moveStartMeters)
        val pedalUsable = !excluded(SignalKind.BRAKE_PEDAL)
        return when {
            !excluded(SignalKind.PARKING_BRAKE) -> when {
                parkingBrakeReleasedSeen -> TransitionReason.PARKING_BRAKE_RELEASED
                pedalUsable && pedalSeen -> TransitionReason.BRAKE_PEDAL
                // 해제하는 변화는 못 봤지만(구독 전부터 꺼져 있었거나 주차 브레이크를 안 건 차) 꺼진 채로 움직이기 시작했다.
                value(SignalKind.PARKING_BRAKE) == false && moved -> TransitionReason.BRAKE_OFF_AND_MOVE
                else -> null
            }
            pedalUsable -> if (pedalSeen && moved) TransitionReason.FALLBACK_PEDAL_AND_MOVE else null
            else -> if (moved) TransitionReason.FALLBACK_MOVE_ONLY else null
        }
    }

    private fun checkTimeouts() {
        if (state != DepartureState.ANNOUNCING && state != DepartureState.DEPARTING) return
        val now = clock.nowMillis()
        val startedAt = announcedAt ?: stateEnteredAt
        val doorAt = doorReopenedAt
        val seatAt = seatVacatedAt
        when {
            now - startedAt >= config.noProgressTimeoutMillis ->
                cancelToParked(TransitionReason.TIMEOUT_NO_PROGRESS)
            doorAt != null && now - doorAt >= config.reopenGraceMillis ->
                cancelToParked(TransitionReason.DOOR_REOPEN_TIMEOUT)
            seatAt != null && now - seatAt >= config.reopenGraceMillis ->
                cancelToParked(TransitionReason.SEAT_VACATED_TIMEOUT)
        }
    }

    private fun cancelToParked(reason: TransitionReason) {
        transition(DepartureState.PARKED, reason)
        resetParkingObservations()
        // 문 신호가 없는 차: 취소 시점에 이미 내려 있으면(착좌 풀림) 다음 착좌를 재탑승으로 받는다.
        // 앉은 채로 취소됐다면 한 번 내렸다 타야 한다(앉아만 있다고 예고가 되풀이되지 않게).
        doorlessArmed = value(SignalKind.SEAT) != true
    }

    private fun resetParkingObservations() {
        // 문이 열린 채로 되돌아왔다면, 닫힐 때 다시 한 번의 "열림 → 닫힘"으로 본다.
        doorOpenedWhileParked = value(SignalKind.DOOR) == true
        doorCycled = false
        restoredOccupied = false
        parkingBrakeReleasedSeen = false
        pedalSeen = false
        doorReopenedAt = null
        seatVacatedAt = null
    }

    private fun transition(to: DepartureState, reason: TransitionReason) {
        val from = state
        val now = clock.nowMillis()
        state = to
        stateEnteredAt = now
        when (to) {
            DepartureState.BOARDING -> {
                // 탑승 이전에 읽힌 브레이크 변화는 이번 출차의 근거로 쓰지 않는다. 지금 밟고 있는 페달은 쓴다.
                parkingBrakeReleasedSeen = false
                pedalSeen = value(SignalKind.BRAKE_PEDAL) == true
            }
            DepartureState.ANNOUNCING -> {
                announcementCounter += 1
                activeAnnouncement = announcementCounter
                announcedAt = now
                seatVacatedAt = null
                // 문이 열린 채 예고가 시작되면 예고 시점부터 60초 안에 닫혀야 한다.
                doorReopenedAt = if (value(SignalKind.DOOR) == true) now else null
            }
            else -> Unit
        }
        val announcement = if (from == DepartureState.PARKED && to == DepartureState.VACATED) null else activeAnnouncement
        emit(from, to, reason, announcement = announcement)
        if (to == DepartureState.ANNOUNCING && doorReopenedAt != null) {
            note(TransitionReason.NOTE, "문이 열린 채 예고 시작: 60초 안에 닫히지 않으면 취소")
        }
        if (to == DepartureState.PARKED) {
            activeAnnouncement = null
            announcedAt = null
        }
    }

    private fun note(reason: TransitionReason, detail: String = reason.label) {
        emit(state, state, reason, detail, activeAnnouncement)
    }

    private fun emit(
        from: DepartureState,
        to: DepartureState,
        reason: TransitionReason,
        detail: String = reason.label,
        announcement: Long? = null,
    ) {
        val event = DepartureLogEvent(
            seq = nextSeq++,
            atMillis = clock.nowMillis(),
            from = from,
            to = to,
            reason = reason,
            detail = detail,
            parkingGeneration = parkingGeneration,
            announcementGeneration = announcement,
            locationQuality = location.usableValue?.quality,
        )
        history.addLast(event)
        while (history.size > config.maxLogEvents) history.removeFirst()
        produced.add(event)
    }

    private fun buildSnapshot(): DepartureSnapshot {
        val announcing = state == DepartureState.ANNOUNCING || state == DepartureState.DEPARTING
        val cancelAt = if (!announcing) null else listOfNotNull(
            announcedAt?.plus(config.noProgressTimeoutMillis),
            doorReopenedAt?.plus(config.reopenGraceMillis),
            seatVacatedAt?.plus(config.reopenGraceMillis),
        ).minOrNull()
        return DepartureSnapshot(
            state = state,
            suppressed = suppressed,
            canSuppress = canSuppressNow(),
            autoAnnounce = autoAnnounceStatus(),
            parkingGeneration = parkingGeneration,
            announcementGeneration = activeAnnouncement,
            door = readings.getValue(SignalKind.DOOR),
            seat = readings.getValue(SignalKind.SEAT),
            seatBelt = readings.getValue(SignalKind.SEAT_BELT),
            parkingBrake = readings.getValue(SignalKind.PARKING_BRAKE),
            brakePedal = readings.getValue(SignalKind.BRAKE_PEDAL),
            location = location,
            doorCycleSeen = doorCycled,
            brakeReleaseSeen = parkingBrakeReleasedSeen,
            pedalPressSeen = pedalSeen,
            movedSeen = movedAtLeast(config.moveStartMeters),
            stateEnteredAtMillis = stateEnteredAt,
            announcedAtMillis = if (announcing) announcedAt else null,
            cancelAtMillis = cancelAt,
            rejectedEventCount = rejectedEvents,
            rejectedSnapshotCount = rejectedSnapshots,
        )
    }

    private companion object {
        fun initial(supported: Boolean): SignalReading<Boolean> =
            if (supported) SignalReading() else SignalReading.unsupported()
    }
}
