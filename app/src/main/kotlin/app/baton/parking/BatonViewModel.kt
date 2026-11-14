package app.baton.parking

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.baton.core.SteppedClock
import app.baton.core.departure.DepartureLogEvent
import app.baton.core.departure.DepartureSnapshot
import app.baton.core.departure.DepartureStateMachine
import app.baton.core.match.DepartureGuidanceBridge
import app.baton.core.match.GuidanceCoordinator
import app.baton.core.match.GuidanceOffer
import app.baton.core.match.GuidanceOutcome
import app.baton.core.match.GuidanceRecord
import app.baton.core.match.GuidanceResolution
import app.baton.core.match.MatchConfig
import app.baton.core.match.MockPeerService
import app.baton.core.match.ZoneSummary
import app.baton.core.signal.LocationQuality
import app.baton.core.signal.SignalAvailability
import app.baton.core.signal.SignalReading
import app.baton.core.signal.SignalSource
import app.baton.parking.signal.FakeSignalSource
import app.baton.parking.signal.FakeSignalState
import app.baton.parking.signal.PleosSignalSource
import app.baton.parking.signal.SignalSourceProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.ceil

enum class Role(val label: String) {
    LEAVING("나가는 차"),
    ENTERING("들어오는 차"),
}

enum class EnteringPhase(val label: String) {
    WAITING("대기"),
    GUIDED("우선 안내"),
    PARKED("주차 완료"),
}

/** 시연 패널의 고정 시나리오. label 이 버튼 글자다. */
enum class DemoScenario(val label: String) {
    NORMAL_DEPARTURE("정상 출차"),
    LUGGAGE_ONLY("짐만 싣기"),
    NO_MOVE_CANCEL("예고 후 무이동 취소"),
    PASS_AND_RETURN("지나침 복귀"),
}

/** '읽은 신호' 한 줄. 상태 순서로 합성하지 않은 실제 관측값이다. */
data class SignalLine(
    val label: String,
    val value: String,
    /** 점을 채울지(지금 그 조건이 실제로 관측되는지). */
    val on: Boolean,
    /** 미지원·미수신·일시 불가라서 회색으로 보일지. */
    val muted: Boolean,
)

/** 시연 패널의 관찰 영역. */
data class ObserveState(
    val waitingPeers: String = "",
    val activeLines: List<String> = emptyList(),
    val recordLines: List<String> = emptyList(),
    val leadTime: String = "",
    val misguided: String = "",
    val parked: String = "",
)

data class BatonUiState(
    val role: Role = Role.LEAVING,
    /** 맨 위 줄에 따로따로 보이는 모의 요소(신호 / 위치 / 상대 차 / 시간 전진). */
    val mockChips: List<String> = emptyList(),
    val signalSourceName: String = "",
    val panelOpen: Boolean = true,
    // 나가는 차
    val departure: DepartureSnapshot = DepartureSnapshot(),
    val myZone: String = "C",
    val myRow: Int = 3,
    val guidedCars: Int = 0,
    val yieldCount: Int = 0,
    val cancelInSeconds: Int? = null,
    val signalLines: List<SignalLine> = emptyList(),
    // 들어오는 차
    val enteringPhase: EnteringPhase = EnteringPhase.WAITING,
    val zones: List<ZoneSummary> = emptyList(),
    val recommendedZone: String = "C",
    val carsAheadOfMe: Int = 0,
    val guidedZone: String = "C",
    /** 안내받은 줄. 구역까지만 안내하면 null. */
    val guidedRow: Int? = null,
    val etaSeconds: Int = 0,
    /** 이 안내가 유효한 남은 시간(도착 예상과는 다른 값). */
    val offerRemainingSeconds: Int = 0,
    /** 경로 위 내 차 위치 (0 = 출발, 1 = 예고 자리 앞). */
    val routeProgress: Float = 0f,
    val parkedElapsedSeconds: Int = 0,
    val parkedZone: String = "C",
    val parkedRow: Int = 3,
    // 시연 패널
    val fakeSignals: FakeSignalState? = null,
    /** 차량 신호(문·착좌·벨트·브레이크)가 실제 SDK 값이면 true. 이때 패널에는 모의 위치 스위치만 남는다. */
    val realVehicleSignals: Boolean = false,
    val runningScenario: String? = null,
    val observe: ObserveState = ObserveState(),
    val log: List<String> = emptyList(),
)

/**
 * core 의 상태기계·안내 순서 계산·모의 상대 차를 화면과 잇는다.
 * 신호는 [SignalSource] 인터페이스로만 받는다(현재 값 조회 → 복원 → 이후 변경 수신).
 *
 * 세계(주차장의 예고·대기·기록)는 "처음부터"나 고정 시나리오를 누를 때만 새로 만든다.
 * 역할 전환과 "다음 바통"은 세계를 그대로 두고 내 차의 처지만 바꾼다.
 */
class BatonViewModel internal constructor(
    application: Application,
    /** 시계 주입 지점. 앱은 아래 생성자로 기본 시계를 쓰고, 단위 시험에서만 가짜 시각의 시계를 넣는다. */
    private val demoClock: DemoClock,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, DemoClock())

    private val clock = SteppedClock(demoClock)
    private val startedAt = clock.nowMillis()
    private val matchConfig = MatchConfig()

    // 신호 소스 주입 지점은 SignalSourceProvider 한 곳이다.
    private val signalSource: SignalSource = SignalSourceProvider.create(application, clock)
    private val fakeSource: FakeSignalSource? = signalSource as? FakeSignalSource

    /** 모의 위치를 맡는 곳. 모의 소스면 그 자신, 실제 차량 신호 소스면 그 안의 위치 전용 모의 소스. */
    private val mockLocation: FakeSignalSource? = fakeSource ?: (signalSource as? PleosSignalSource)?.mockLocation

    private val machine = DepartureStateMachine(clock, signalSource.capabilities)
    private var coordinator = GuidanceCoordinator(clock, matchConfig)
    private var peers = MockPeerService(clock, coordinator, matchConfig)
    private var bridge = DepartureGuidanceBridge(coordinator, ME, DEFAULT_ZONE, DEFAULT_ROW)

    private var machineLogSeq = 0L
    private val logLines = ArrayDeque<String>()

    private var role = Role.LEAVING
    private var panelOpen = true
    private var myZone = DEFAULT_ZONE
    private var myRow = DEFAULT_ROW

    private var enteringPhase = EnteringPhase.WAITING
    private var enteringZone = DEFAULT_ZONE
    private var enteredAt = 0L
    private var currentOffer: GuidanceOffer? = null
    private var parkedElapsedSeconds = 0
    private var parkedZone = DEFAULT_ZONE
    private var parkedRow = DEFAULT_ROW
    private var peerRowSeq = 0

    private var skippedSeconds = 0
    private var scenarioJob: Job? = null
    private var scenarioName: String? = null

    private val _ui = MutableStateFlow(BatonUiState())
    val ui: StateFlow<BatonUiState> = _ui.asStateFlow()

    init {
        resetWorld(Role.LEAVING)
        viewModelScope.launch {
            // 계약: 현재 값을 먼저 읽어 복원하고(초기값은 변화로 세지 않는다), 그 뒤의 변경만 받는다.
            restoreFromSource()
            refresh()
            signalSource.events.collect { event ->
                if (role == Role.LEAVING) applyDeparture(machine.onSignal(event))
                refresh()
            }
        }
        viewModelScope.launch {
            mockLocation?.state?.collect { refresh() }
        }
        viewModelScope.launch {
            while (true) {
                delay(TICK_MILLIS)
                step()
                refresh()
            }
        }
    }

    // ---- 시연 패널에서 부르는 것 -------------------------------------------

    fun togglePanel() {
        panelOpen = !panelOpen
        refresh()
    }

    /** 역할 전환. 세계는 그대로 두고 내 차의 처지만 바꾼다. */
    fun selectRole(newRole: Role) {
        if (newRole == role) return
        cancelScenario()
        switchRole(newRole)
        refresh()
    }

    /** "다음 바통": 방금 주차한 내 차가 그 자리에서 다음 '나가는 차'가 된다. */
    fun nextBaton() {
        if (role != Role.ENTERING || enteringPhase != EnteringPhase.PARKED) {
            addLog("다음 바통은 들어오는 차가 주차를 마친 뒤에 누를 수 있어요")
            refresh()
            return
        }
        cancelScenario()
        addLog("다음 바통 · 방금 주차한 내 차가 다음 예고 차가 됨")
        switchRole(Role.LEAVING)
        refresh()
    }

    /** "처음부터": 세계를 새로 만든다. */
    fun restartScenario() {
        cancelScenario()
        resetWorld(role)
        refresh()
    }

    /**
     * 시간을 앞당긴다. 한 번에 건너뛰지 않고 평소 진행 간격으로 차례대로 밟아,
     * 그냥 기다렸을 때와 같은 결과가 나오게 한다.
     */
    fun skipTime(seconds: Int) {
        addLog("시간 +${seconds}초")
        repeat((seconds * 1000L / TICK_MILLIS).toInt()) {
            demoClock.skip(TICK_MILLIS)
            step()
        }
        skippedSeconds += seconds
        refresh()
    }

    fun setDoorOpen(v: Boolean) = fakeSource?.setDoorOpen(v)
    fun setSeated(v: Boolean) = fakeSource?.setSeated(v)
    fun setBelted(v: Boolean) = fakeSource?.setBelted(v)
    fun setParkingBrakeOn(v: Boolean) = fakeSource?.setParkingBrakeOn(v)
    fun setPedalPressed(v: Boolean) = fakeSource?.setPedalPressed(v)
    fun setMoved(v: Boolean) = mockLocation?.setMoved(v)
    fun setLowQualityLocation(v: Boolean) = mockLocation?.setLowQualityLocation(v)
    fun setLocationLost(v: Boolean) = mockLocation?.setLocationLost(v)

    /**
     * 나가는 차 화면의 "이번엔 예고 안 함". 차가 움직이는 중이면 화면에서 버튼이 꺼져 있고,
     * 여기와 상태기계에서도 한 번씩 더 거른다.
     */
    fun suppressAnnouncement() {
        if (role != Role.LEAVING) return
        if (!machine.snapshot.value.canSuppress) {
            addLog("움직이는 중에는 '예고 안 함'을 받지 않음")
            refresh()
            return
        }
        applyDeparture(machine.suppressThisParking())
        refresh()
    }

    fun peerDepartNow() {
        peers.peerStartsDepartingNow()
        addLog("상대 차 출차 임박을 당김")
        step()
        refresh()
    }

    fun addPeerDeparture() {
        advanceWorld()
        peerRowSeq += 1
        val row = 3 + peerRowSeq * 2
        val id = peers.startPeerDeparture(zone = DEFAULT_ZONE, row = row)
        addLog("상대 차 예고 추가 · $id (${DEFAULT_ZONE}구역)")
        refresh()
    }

    fun passWithoutParking() {
        val offer = currentOffer
        if (role != Role.ENTERING || enteringPhase != EnteringPhase.GUIDED || offer == null) return
        advanceWorld()
        if (coordinator.reportPassedWithoutParking(ME, offer.offerId)) {
            val preserved = coordinator.record(offer.offerId)?.waitPreserved == true
            addLog("자리를 지나쳤는데 주차하지 않음 → 대기 순서로 복귀 · 대기 시간 보존 ${yesNo(preserved)}")
            backToWaiting()
        }
        refresh()
    }

    fun parkNow() {
        val offer = currentOffer
        if (role != Role.ENTERING || enteringPhase != EnteringPhase.GUIDED || offer == null) return
        advanceWorld()
        peers.peerVacatesNow(offer.announcementId)
        if (coordinator.reportParked(ME, offer.offerId) != null) {
            peers.untrack(ME)
            advanceEntering()
        } else {
            addLog("주차 보고가 받아들여지지 않음 (안내 만료 또는 자리가 아직 비지 않음)")
        }
        refresh()
    }

    /** 고정 시나리오. 세계를 새로 만들고 정해진 순서대로 조작한다. */
    fun runScenario(scenario: DemoScenario) {
        cancelScenario()
        val fake = fakeSource
        if (fake == null) {
            addLog("실제 신호 소스에서는 고정 시나리오를 쓸 수 없음")
            refresh()
            return
        }
        scenarioName = scenario.label
        scenarioJob = viewModelScope.launch {
            val self = coroutineContext[Job]
            try {
                when (scenario) {
                    DemoScenario.NORMAL_DEPARTURE -> playNormalDeparture(fake)
                    DemoScenario.LUGGAGE_ONLY -> playLuggageOnly(fake)
                    DemoScenario.NO_MOVE_CANCEL -> playNoMoveCancel(fake)
                    DemoScenario.PASS_AND_RETURN -> playPassAndReturn()
                }
            } finally {
                if (scenarioJob === self) {
                    scenarioJob = null
                    scenarioName = null
                    refresh()
                }
            }
        }
    }

    // ---- 고정 시나리오 ------------------------------------------------------

    private fun say(text: String) {
        addLog("▶ $text")
        refresh()
    }

    private suspend fun boardAndAnnounce(fake: FakeSignalSource) {
        fake.setDoorOpen(true)
        delay(900)
        fake.setDoorOpen(false)
        delay(900)
        fake.setSeated(true)
        delay(600)
        fake.setBelted(true)
        delay(1800)
    }

    private suspend fun playNormalDeparture(fake: FakeSignalSource) {
        resetWorld(Role.LEAVING)
        say("시나리오 · 정상 출차: 탑승 → 예고 → 한 대에만 안내 → 이탈")
        delay(1200)
        boardAndAnnounce(fake)
        fake.setParkingBrakeOn(false)
        delay(2500)
        fake.setMoved(true)
        delay(1500)
        skipTime(14)
        say("안내받은 상대 차가 도착해 주차하면 내 양보 기록이 1 오름")
    }

    private suspend fun playLuggageOnly(fake: FakeSignalSource) {
        resetWorld(Role.LEAVING)
        say("시나리오 · 짐만 싣기: 문만 여닫고 앉지 않음")
        delay(1200)
        fake.setDoorOpen(true)
        delay(1200)
        fake.setDoorOpen(false)
        delay(2500)
        fake.setDoorOpen(true)
        delay(1200)
        fake.setDoorOpen(false)
        delay(800)
        skipTime(30)
        say("착좌가 없어 예고하지 않음 · 보낸 안내 0건")
    }

    private suspend fun playNoMoveCancel(fake: FakeSignalSource) {
        resetWorld(Role.LEAVING)
        say("시나리오 · 예고 후 무이동 취소: 예고·안내 뒤 움직이지 않음")
        delay(1200)
        boardAndAnnounce(fake)
        fake.setParkingBrakeOn(false)
        delay(2500)
        say("움직이지 않고 기다림")
        skipTime(60)
        delay(1500)
        skipTime(60)
        say("예고 시작 120초: 예고와 안내가 함께 취소됨 · 상대 차의 대기 시간은 그대로")
    }

    private suspend fun playPassAndReturn() {
        resetWorld(Role.ENTERING)
        say("시나리오 · 지나침 복귀: 안내받고 지나친 뒤 다음 예고에서 다시 안내")
        delay(1500)
        peerDepartNow()
        delay(3000)
        skipTime(5)
        delay(1000)
        passWithoutParking()
        delay(2500)
        addPeerDeparture()
        delay(1500)
        peerDepartNow()
        delay(2000)
        skipTime(25)
        say("기다린 시간을 보존한 채 다음 예고 자리로 다시 안내받음")
    }

    private fun cancelScenario() {
        scenarioJob?.cancel()
        scenarioJob = null
        scenarioName = null
    }

    // ---- 세계와 역할 --------------------------------------------------------

    /** 세계(예고·대기·기록)를 새로 만든다. "처음부터"와 고정 시나리오에서만 부른다. */
    private fun resetWorld(newRole: Role) {
        beginParking() // 진행 중이던 내 예고는 이전 세계에서 끝낸다
        coordinator = GuidanceCoordinator(clock, matchConfig)
        peers = MockPeerService(clock, coordinator, matchConfig)
        myZone = DEFAULT_ZONE
        myRow = DEFAULT_ROW
        peerRowSeq = 0
        bridge = DepartureGuidanceBridge(coordinator, ME, myZone, myRow)
        coordinator.setYieldCount(ME, INITIAL_YIELD_COUNT)
        role = newRole
        addLog("처음부터 · 새 세계")
        if (newRole == Role.LEAVING) {
            peers.seedWaitingPeers()
            addLog("역할: 나가는 차 · ${myZone}구역 ${myRow}번 줄에 주차 중")
        } else {
            peers.seedDepartingPeer()
            enterLot()
        }
    }

    /** 역할만 바꾼다. 예고·대기·기록·양보 기록은 그대로 남는다. */
    private fun switchRole(newRole: Role) {
        if (newRole == Role.ENTERING) {
            beginParking() // 내 예고가 진행 중이었다면 취소된다. 이미 비운 자리는 180초 동안 남는다.
            role = Role.ENTERING
            if (!peers.hasLiveDeparture()) {
                val id = peers.startPeerDeparture()
                addLog("모의 상대 차 예고 시작 · $id")
            }
            enterLot()
        } else {
            if (enteringPhase == EnteringPhase.PARKED) {
                myZone = parkedZone
                myRow = parkedRow
            } else {
                peers.untrack(ME)
            }
            role = Role.LEAVING
            beginParking()
            bridge = DepartureGuidanceBridge(coordinator, ME, myZone, myRow)
            peers.ensureWaitingPeer(myZone, exceptCarId = ME)?.let { addLog("모의 대기 차 추가 · $it (${myZone}구역)") }
            addLog("역할: 나가는 차 · ${myZone}구역 ${myRow}번 줄에 주차 중")
        }
    }

    /**
     * 새 주차를 시작한다: 모의 스위치를 주차 기본값으로 되돌리고, 그 현재 값을 다시 읽어 상태기계를 복원한다.
     * 진행 중이던 예고가 있으면 이때 나오는 전이로 취소가 전달된다.
     * 어느 경로든 주차 세대는 한 번만 오른다.
     */
    private fun beginParking() {
        val fake = fakeSource
        if (fake != null) {
            // 모의 소스: 현재 값을 바로(동기) 읽을 수 있으므로 세대 증가와 함께 넣는다.
            fake.resetToParked(generation = null)
            applyDeparture(machine.startNewParking(fake.snapshotNow()))
            fake.parkingGeneration = machine.snapshot.value.parkingGeneration
        } else {
            // 실제 신호 소스: 세대를 한 번 올려 옛 신호·옛 조회를 막고, 그 세대의 요청으로 현재 값을 읽어 복원한다.
            // 조회가 돌아오기 전에 주차가 또 바뀌면 세대가 달라져 그 답은 상태기계가 버린다.
            applyDeparture(machine.startNewParking())
            // 모의 위치는 주차 지점으로 되돌린다(실제 차량 신호는 건드리지 않는다).
            mockLocation?.resetToParked(machine.snapshot.value.parkingGeneration)
            viewModelScope.launch {
                restoreFromSource()
                refresh()
            }
        }
    }

    /**
     * 현재 신호 값을 읽어 상태기계를 복원한다. 조회 전에 받은 표(주차 세대 + 요청 번호)를 답에 붙이므로,
     * 늦게 돌아온 답이 새 주차나 더 나중 조회의 결과를 덮어쓰지 못한다.
     */
    private suspend fun restoreFromSource() {
        val request = machine.newSnapshotRequest()
        val snapshot = signalSource.currentSnapshot().answering(request)
        applyDeparture(machine.restore(snapshot))
    }

    private fun enterLot() {
        val zone = coordinator.recommendZone(excludeCarId = ME) ?: DEFAULT_ZONE
        enteringZone = zone
        enteredAt = clock.nowMillis()
        enteringPhase = EnteringPhase.WAITING
        currentOffer = null
        peers.trackCar(ME, zone, ENTER_DISTANCE_METERS)
        addLog("역할: 들어오는 차 · 주차장 진입, ${zone}구역 대기 등록")
    }

    // ---- 진행 --------------------------------------------------------------

    /**
     * 한 번의 진행. 먼저 모의 세계(상대 차의 이동·예정된 출차)를 지금 시각까지 밟고, 그다음에 상태기계가 낸 전이를
     * 순서대로 안내 쪽에 적용한다. 새로 생긴 안내로 차가 움직이는 것은 다음 진행부터다.
     */
    private fun step() {
        advanceWorld()
        if (role == Role.LEAVING) applyDeparture(machine.tick())
        if (role == Role.ENTERING) advanceEntering()
    }

    /**
     * 모의 세계를 지금 시각까지 진행한다. 외부 입력(신호, 버튼, 보고)을 안내 쪽에 적용하기 **전에** 부른다.
     * 순서가 반대면 방금 생긴 안내가 아직 밟지 않은 지난 구간에 소급 적용될 수 있다.
     */
    private fun advanceWorld() {
        peers.tick()
    }

    /** 상태기계의 입력 하나가 낸 기록을 그대로(순서대로) 예고·안내·취소로 옮긴다. 그 전에 세계를 지금까지 진행한다. */
    private fun applyDeparture(events: List<DepartureLogEvent>) {
        advanceWorld()
        bridge.apply(events)
    }

    private fun advanceEntering() {
        when (enteringPhase) {
            EnteringPhase.WAITING -> coordinator.offerFor(ME)?.let { offer ->
                currentOffer = offer
                enteringPhase = EnteringPhase.GUIDED
                val place = offer.row?.let { "${offer.zoneId}구역 ${it}번 줄" } ?: "${offer.zoneId}구역 (구역까지만)"
                addLog("우선 안내 받음 · ${offer.offerId} · $place")
            }
            EnteringPhase.GUIDED -> {
                val offer = currentOffer ?: return
                val record = coordinator.record(offer.offerId)
                when (val outcome = record?.outcome) {
                    null -> currentOffer = coordinator.offerFor(ME)?.takeIf { it.offerId == offer.offerId } ?: offer
                    GuidanceOutcome.PARKED -> completeParking(record)
                    else -> {
                        addLog(
                            "안내 종료(${outcome.label}) → 대기 복귀 · 대기 시간 보존 ${yesNo(record.waitPreserved == true)}",
                        )
                        backToWaiting()
                    }
                }
            }
            EnteringPhase.PARKED -> Unit
        }
    }

    private fun backToWaiting() {
        enteringPhase = EnteringPhase.WAITING
        currentOffer = null
        peers.resetDistance(ME, ENTER_DISTANCE_METERS)
    }

    private fun completeParking(record: GuidanceRecord) {
        enteringPhase = EnteringPhase.PARKED
        currentOffer = null
        parkedZone = record.zoneId
        parkedRow = record.row
        parkedElapsedSeconds = ((clock.nowMillis() - enteredAt) / 1000L).toInt()
        // M1: 주차 위치·걸린 시간은 메모리에만 둔다(이번 실행 동안). 기기 저장은 M2.
        addLog(
            "주차 완료 · ${record.zoneId}구역 ${record.row}번 줄 · " +
                "내준 차(${record.ownerId}) 양보 기록 ${coordinator.yieldCount(record.ownerId)}회",
        )
    }

    // ---- 화면 상태 ---------------------------------------------------------

    private fun refresh() {
        drainMachineLog()
        val now = clock.nowMillis()
        val snapshot = machine.snapshot.value
        val zones = coordinator.zoneSummaries(excludeCarId = ME)
        val offer = currentOffer
        val myDistance = peers.distanceOf(ME) ?: 0.0
        _ui.value = BatonUiState(
            role = role,
            mockChips = listOfNotNull(
                "모의 신호".takeIf { signalSource.isMock },
                "모의 위치".takeIf { signalSource.isMockLocation },
                "모의 상대 차",
                if (skippedSeconds > 0) "시간 전진 +${skippedSeconds}초" else "시간 전진 없음",
            ),
            signalSourceName = signalSource.name,
            panelOpen = panelOpen,
            departure = snapshot,
            myZone = myZone,
            myRow = myRow,
            guidedCars = bridge.latestAnnouncementId?.let { coordinator.guidedCarCount(it) } ?: 0,
            yieldCount = coordinator.yieldCount(ME),
            cancelInSeconds = snapshot.cancelAtMillis?.let { secondsUntil(it, now) },
            signalLines = signalLines(snapshot),
            enteringPhase = enteringPhase,
            zones = zones,
            recommendedZone = enteringZone,
            carsAheadOfMe = ((coordinator.rankInZone(ME) ?: 1) - 1).coerceAtLeast(0),
            guidedZone = offer?.zoneId ?: enteringZone,
            guidedRow = offer?.row?.takeIf { offer.resolution == GuidanceResolution.ROW },
            etaSeconds = ceil(myDistance / matchConfig.slowSpeedMps).toInt(),
            offerRemainingSeconds = offer?.let { secondsUntil(it.expiresAtMillis, now) } ?: 0,
            routeProgress = (1.0 - myDistance / ENTER_DISTANCE_METERS).toFloat().coerceIn(0f, 1f),
            parkedElapsedSeconds = parkedElapsedSeconds,
            parkedZone = parkedZone,
            parkedRow = parkedRow,
            fakeSignals = mockLocation?.state?.value,
            realVehicleSignals = fakeSource == null,
            runningScenario = scenarioName,
            observe = observe(now),
            log = logLines.toList(),
        )
    }

    private fun secondsUntil(atMillis: Long, now: Long): Int =
        ceil((atMillis - now) / 1000.0).toInt().coerceAtLeast(0)

    /** 실제 관측값으로 만든 '읽은 신호' 4줄. 미지원·미수신·일시 불가는 회색으로 따로 적는다. */
    private fun signalLines(s: DepartureSnapshot): List<SignalLine> {
        fun problem(r: SignalReading<*>): String? = when (r.availability) {
            SignalAvailability.AVAILABLE -> null
            SignalAvailability.UNKNOWN -> "대기"
            SignalAvailability.UNSUPPORTED -> "미지원"
            SignalAvailability.UNAVAILABLE -> "일시 불가"
        }

        val doorProblem = problem(s.door)
        val door = if (doorProblem != null) {
            SignalLine("운전석 문", doorProblem, on = false, muted = true)
        } else {
            val now = if (s.door.usableValue == true) "열림" else "닫힘"
            SignalLine("운전석 문", if (s.doorCycleSeen) "$now · 여닫힘 봄" else now, on = s.doorCycleSeen, muted = false)
        }

        val seatProblem = problem(s.seat)
        val beltProblem = problem(s.seatBelt)
        val seated = s.seat.usableValue == true
        val belted = s.seatBelt.usableValue == true
        val seat = if (seatProblem != null) {
            SignalLine("착좌 · 안전벨트", seatProblem, on = false, muted = true)
        } else {
            val beltText = beltProblem?.let { "벨트 $it" } ?: if (belted) "벨트 맴" else "벨트 안 맴"
            SignalLine(
                "착좌 · 안전벨트",
                "${if (seated) "앉음" else "비어 있음"} · $beltText",
                on = seated && (belted || s.seatBelt.excluded),
                muted = false,
            )
        }

        val brakeProblem = problem(s.parkingBrake)
        val pedalProblem = problem(s.brakePedal)
        val brakeOff = s.parkingBrake.usableValue == false
        val pedalDown = s.brakePedal.usableValue == true
        val brakeText = brakeProblem?.let { "경고등 $it" } ?: if (brakeOff) "경고등 꺼짐" else "경고등 켜짐"
        val pedalText = pedalProblem?.let { "페달 $it" } ?: if (pedalDown) "페달 밟음" else "페달 뗌"
        val brake = SignalLine(
            "주차 브레이크 · 페달",
            "$brakeText · $pedalText",
            on = brakeOff || pedalDown,
            muted = brakeProblem != null && pedalProblem != null,
        )

        val locationProblem = problem(s.location)
        val fix = s.location.usableValue
        val move = if (locationProblem != null || fix == null) {
            SignalLine("차량 이동", locationProblem ?: "대기", on = false, muted = true)
        } else {
            val quality = if (fix.quality == LocationQuality.LOW) " · 정확도 낮음" else ""
            SignalLine("차량 이동", "주차 지점에서 ${fix.metersFromSpot.toInt()}m$quality", on = s.movedSeen, muted = false)
        }
        return listOf(door, seat, brake, move)
    }

    private fun observe(now: Long): ObserveState {
        val metrics = coordinator.metrics()
        val waiting = coordinator.waitingCars().map { it.id }.filter { it != ME }
        val active = coordinator.activeOffers().map { o ->
            val place = o.row?.let { "${o.zoneId}구역 ${it}번 줄" } ?: "${o.zoneId}구역(구역만)"
            "안내 중 ${o.offerId} → ${carName(o.carId)} · $place · 유효 ${secondsUntil(o.expiresAtMillis, now)}초"
        }
        val ended = coordinator.records().filter { it.outcome != null }.takeLast(4).reversed().map { r ->
            val preserved = r.waitPreserved?.let { " · 대기 시간 보존 ${yesNo(it)}" } ?: ""
            // 지나침·만료로 먼저 끝났지만 그 예고가 나중에 취소된 안내(잘못 보낸 안내로 센다)
            val cancelledLater =
                if (r.announcementCancelled && r.outcome != GuidanceOutcome.ANNOUNCEMENT_CANCELLED) " · 이후 예고 취소" else ""
            "${r.offerId} → ${carName(r.carId)} · ${r.outcome?.label}$preserved$cancelledLater"
        }
        val lead = metrics.lastLeadTimeMillis
        val average = metrics.averageLeadTimeMillis
        return ObserveState(
            waitingPeers = if (waiting.isEmpty()) "대기 중인 상대 차 없음" else "대기: " + waiting.joinToString(" "),
            activeLines = active,
            recordLines = ended,
            leadTime = if (lead == null || average == null) {
                "예고→이탈 시간: 아직 없음"
            } else {
                String.format(
                    "예고→이탈 시간: 최근 %.1f초 · 평균 %.1f초 (%d건)",
                    lead / 1000.0, average / 1000.0, metrics.vacatedAnnouncements,
                )
            },
            misguided = "잘못 보낸 안내: ${metrics.misguidedOffers}/${metrics.totalOffers}건",
            parked = "안내 뒤 주차 성사: ${metrics.parkedOffers}/${metrics.totalOffers}건",
        )
    }

    private fun carName(id: String) = if (id == ME) "내 차" else id

    private fun yesNo(value: Boolean) = if (value) "예" else "아니오"

    /** 상태기계 로그는 마지막으로 읽은 순번 뒤의 줄만 가져온다. */
    private fun drainMachineLog() {
        val fresh = machine.eventsSince(machineLogSeq)
        for (e in fresh) {
            val text = if (e.isTransition) "${e.from} → ${e.to} · ${e.detail}" else "${e.from} · ${e.detail}"
            pushLog(e.atMillis, text)
        }
        fresh.lastOrNull()?.let { machineLogSeq = it.seq }
    }

    private fun addLog(text: String) {
        drainMachineLog() // 상태기계가 먼저 낸 기록이 아래에 오도록
        pushLog(clock.nowMillis(), text)
    }

    private fun pushLog(atMillis: Long, text: String) {
        val seconds = (atMillis - startedAt) / 1000.0
        logLines.addFirst(String.format("%6.1fs  %s", seconds, text))
        while (logLines.size > MAX_LOG_LINES) logLines.removeLast()
    }

    private companion object {
        const val ME = "me"
        const val DEFAULT_ZONE = "C"
        const val DEFAULT_ROW = 3
        const val INITIAL_YIELD_COUNT = 12
        const val ENTER_DISTANCE_METERS = 98.0 // 서행 기준 35초
        const val TICK_MILLIS = 250L
        const val MAX_LOG_LINES = 80
    }
}
