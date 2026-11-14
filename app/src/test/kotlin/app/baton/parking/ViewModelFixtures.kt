package app.baton.parking

import android.app.Application
import androidx.lifecycle.viewModelScope
import app.baton.core.departure.DepartureState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 에뮬레이터 없이 [BatonViewModel]을 돌리는 시험대.
 *
 * - 화면 스레드(Dispatchers.Main)를 가짜 시간의 디스패처로 바꾼다. 앱의 Main.immediate 처럼 바로 이어서 실행한다.
 * - 시계는 가짜 시간을 그대로 읽는다. 그래서 [waitSeconds]로 기다리면 뷰모델의 진행 간격(250ms)과 시계가 함께 흐른다.
 * - 뷰모델의 진행 반복은 끝나지 않으므로 advanceUntilIdle 은 쓰지 않는다. 시험이 끝나면 [close]로 멈춘다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelBench {
    private val scheduler = TestCoroutineScheduler()

    val vm: BatonViewModel

    init {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        vm = BatonViewModel(Application(), DemoClock { CLOCK_BASE_MILLIS + scheduler.currentTime })
    }

    /** 지금 화면이 읽는 상태. */
    val ui: BatonUiState get() = vm.ui.value

    val state: DepartureState get() = ui.departure.state

    /** 그냥 기다린다(시연 패널의 "시간 +N초"가 아니라 실제로 시간이 흐른 경우). */
    fun waitSeconds(seconds: Double) = waitMillis((seconds * 1000).toLong())

    fun waitSeconds(seconds: Int) = waitMillis(seconds * 1000L)

    fun waitMillis(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    /** [condition]이 될 때까지 진행 간격만큼씩 기다린다. @return 기다린 시간(ms). [limitSeconds]를 넘기면 실패. */
    fun waitUntil(limitSeconds: Int = 300, condition: (BatonUiState) -> Boolean): Long {
        var waited = 0L
        while (!condition(ui)) {
            check(waited < limitSeconds * 1000L) { "${limitSeconds}초를 기다려도 조건이 되지 않음. 로그:\n" + ui.log.joinToString("\n") }
            waitMillis(STEP_MILLIS)
            waited += STEP_MILLIS
        }
        return waited
    }

    fun close() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    // ---- 시연 패널 스위치를 사람이 누르는 순서 그대로 --------------------------

    /** 운전석 문을 열었다 닫는다. */
    fun doorCycle() {
        vm.setDoorOpen(true)
        waitSeconds(1)
        vm.setDoorOpen(false)
        waitSeconds(1)
    }

    /** 운전석에 앉아 벨트를 맨다. */
    fun sitAndBelt() {
        vm.setSeated(true)
        waitSeconds(1)
        vm.setBelted(true)
        waitSeconds(1)
    }

    /** 문 여닫기 → 착좌·벨트: 예고(ANNOUNCING)까지. */
    fun boardAndAnnounce() {
        doorCycle()
        sitAndBelt()
    }

    private companion object {
        const val CLOCK_BASE_MILLIS = 1_000_000L
        const val STEP_MILLIS = 250L
    }
}

/** 관찰 영역의 "n/m건"에서 (n, m)을 읽는다. */
fun countsOf(line: String): Pair<Int, Int> {
    val match = checkNotNull(Regex("""(\d+)/(\d+)건""").find(line)) { "건수를 읽지 못함: $line" }
    return match.groupValues[1].toInt() to match.groupValues[2].toInt()
}

/** 지금까지 보낸 안내 수(진행 중 포함). */
val BatonUiState.totalOffers: Int get() = countsOf(observe.misguided).second

/** 잘못 보낸 안내 수(보낸 뒤 예고가 취소된 안내). */
val BatonUiState.misguidedOffers: Int get() = countsOf(observe.misguided).first

/** 안내 뒤 주차가 성사된 안내 수. */
val BatonUiState.parkedOffers: Int get() = countsOf(observe.parked).first

/** 주차해 둔 차의 모의 스위치 기본값. */
val FakeSignalStateDefaults = app.baton.parking.signal.FakeSignalState()
