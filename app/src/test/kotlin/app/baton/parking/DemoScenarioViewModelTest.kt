package app.baton.parking

import app.baton.core.departure.DepartureState.BOARDING
import app.baton.core.departure.DepartureState.DEPARTING
import app.baton.core.departure.DepartureState.PARKED
import app.baton.core.departure.DepartureState.VACATED
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 시연 패널의 고정 시나리오 버튼([DemoScenario])을 눌러 끝까지 돌린 뒤의 화면 상태. */
class DemoScenarioViewModelTest {
    private val bench = ViewModelBench()
    private val vm get() = bench.vm
    private val ui get() = bench.ui

    @AfterTest
    fun tearDown() = bench.close()

    /** 버튼을 누르고 "진행 중" 표시가 꺼질 때까지 기다린다. */
    private fun play(scenario: DemoScenario) {
        vm.runScenario(scenario)
        assertEquals(scenario.label, ui.runningScenario)
        bench.waitUntil(limitSeconds = 60) { it.runningScenario == null }
    }

    @Test
    fun `정상 출차 시나리오 - VACATED 로 끝나고 한 대에만 안내한 뒤 그 차가 주차하면 양보 기록이 오른다`() {
        play(DemoScenario.NORMAL_DEPARTURE)
        assertEquals(Role.LEAVING, ui.role)
        assertEquals(VACATED, bench.state)
        assertEquals(1, ui.totalOffers)
        assertEquals(1, ui.guidedCars)
        assertTrue(ui.observe.activeLines.single().startsWith("안내 중 offer-1 → peer-c1 · C구역 3번 줄"))
        assertEquals("시간 전진 +14초", ui.mockChips.last())
        assertEquals(
            listOf("PARKED → BOARDING", "BOARDING → ANNOUNCING", "ANNOUNCING → DEPARTING", "DEPARTING → VACATED"),
            ui.log.reversed().mapNotNull { Regex("""[A-Z]+ → [A-Z]+""").find(it)?.value },
        )

        bench.waitUntil(limitSeconds = 30) { it.yieldCount == 13 }
        assertEquals(1, ui.totalOffers)
        assertEquals(1, ui.parkedOffers)
        assertEquals(0, ui.misguidedOffers)
        assertEquals(VACATED, bench.state)
    }

    @Test
    fun `짐만 싣기 시나리오 - BOARDING 으로 끝나고 예고도 안내도 없다`() {
        play(DemoScenario.LUGGAGE_ONLY)
        assertEquals(BOARDING, bench.state)
        assertNull(ui.departure.announcementGeneration)
        assertEquals(0, ui.totalOffers)
        assertEquals(0, ui.guidedCars)
        assertEquals("시간 전진 +30초", ui.mockChips.last())
        assertTrue(ui.log.first().endsWith("▶ 착좌가 없어 예고하지 않음 · 보낸 안내 0건"))
    }

    @Test
    fun `예고 후 무이동 취소 시나리오 - PARKED 로 끝나고 안내도 함께 취소된다`() {
        vm.runScenario(DemoScenario.NO_MOVE_CANCEL)
        bench.waitUntil(limitSeconds = 60) { it.departure.state == DEPARTING }
        assertEquals(1, ui.guidedCars)

        bench.waitUntil(limitSeconds = 60) { it.runningScenario == null }
        assertEquals(PARKED, bench.state)
        assertNull(ui.cancelInSeconds)
        assertEquals(0, ui.guidedCars)
        assertTrue(ui.observe.activeLines.isEmpty())
        assertEquals(listOf("offer-1 → peer-c1 · 예고 취소 · 대기 시간 보존 예"), ui.observe.recordLines)
        assertEquals(1, ui.misguidedOffers)
        assertEquals("시간 전진 +120초", ui.mockChips.last())
    }

    @Test
    fun `지나침 복귀 시나리오 - 다시 안내받은 상태로 끝나고 이어서 주차 완료 화면이 된다`() {
        play(DemoScenario.PASS_AND_RETURN)
        assertEquals(Role.ENTERING, ui.role)
        assertEquals(EnteringPhase.GUIDED, ui.enteringPhase)
        assertEquals(5, ui.guidedRow)
        assertEquals(listOf("offer-1 → 내 차 · 지나침 · 대기 시간 보존 예"), ui.observe.recordLines)

        bench.waitUntil(limitSeconds = 30) { it.enteringPhase == EnteringPhase.PARKED }
        assertEquals("C", ui.parkedZone)
        assertEquals(5, ui.parkedRow)
        assertEquals(2, ui.totalOffers)
        assertEquals(1, ui.parkedOffers)
    }

    @Test
    fun `지나침 복귀 시나리오 뒤 다음 바통 - 주차한 자리에서 PARKED 로 다시 시작한다`() {
        play(DemoScenario.PASS_AND_RETURN)
        bench.waitUntil(limitSeconds = 30) { it.enteringPhase == EnteringPhase.PARKED }
        vm.nextBaton()
        assertEquals(Role.LEAVING, ui.role)
        assertEquals(PARKED, bench.state)
        assertEquals(5, ui.myRow)
    }

    @Test
    fun `시나리오 도중 처음부터를 누르면 시나리오가 멈추고 더 조작하지 않는다`() {
        vm.runScenario(DemoScenario.NORMAL_DEPARTURE)
        bench.waitSeconds(2.5) // 문을 닫아 탑승까지 간 뒤
        assertEquals(BOARDING, bench.state)

        vm.restartScenario()
        assertNull(ui.runningScenario)
        assertEquals(PARKED, bench.state)
        bench.waitSeconds(30)
        assertEquals(PARKED, bench.state)
        assertEquals(FakeSignalStateDefaults, ui.fakeSignals)
        assertEquals(0, ui.totalOffers)
    }

    @Test
    fun `시나리오 도중 다른 시나리오를 누르면 앞의 것은 멈추고 새 것만 진행한다`() {
        vm.runScenario(DemoScenario.NORMAL_DEPARTURE)
        bench.waitSeconds(2.5)
        play(DemoScenario.LUGGAGE_ONLY)
        assertEquals(BOARDING, bench.state)
        assertEquals(0, ui.totalOffers)
    }
}
