package app.baton.parking

import app.baton.core.departure.DepartureState.DEPARTING
import app.baton.core.departure.DepartureState.PARKED
import app.baton.core.departure.DepartureState.VACATED
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 들어오는 차와 역할 전환("다음 바통"): 시연 패널 버튼을 누른 결과가 화면 상태로 끝나는지. */
class EnteringCarViewModelTest {
    private val bench = ViewModelBench()
    private val vm get() = bench.vm
    private val ui get() = bench.ui

    @AfterTest
    fun tearDown() = bench.close()

    /** 들어오는 차로 새 세계를 시작한다: C구역에는 예고 중인 상대 차 한 대, 대기 차는 나뿐. */
    private fun enterFreshLot() {
        vm.selectRole(Role.ENTERING)
        vm.restartScenario()
        assertEquals(Role.ENTERING, ui.role)
        assertEquals(EnteringPhase.WAITING, ui.enteringPhase)
    }

    /** 안내받고 지나친 뒤, 다음 예고(5번 줄)에서 다시 안내받아 주차할 때까지. */
    private fun passThenParkAtNextSpot() {
        enterFreshLot()
        vm.peerDepartNow()
        bench.waitSeconds(5)
        vm.passWithoutParking()
        bench.waitSeconds(2)
        vm.addPeerDeparture()
        vm.peerDepartNow()
        bench.waitUntil { it.enteringPhase == EnteringPhase.PARKED }
    }

    @Test
    fun `들어오는 차 - 진입하면 대기이고 추천 구역과 도착 예상이 보인다`() {
        enterFreshLot()
        assertEquals("C", ui.recommendedZone)
        assertEquals(1, ui.zones.single { it.zoneId == "C" }.upcomingSpots)
        assertEquals(0, ui.carsAheadOfMe)
        assertEquals(35, ui.etaSeconds, "98m 를 서행으로")
        assertEquals(0f, ui.routeProgress)
        assertNull(ui.guidedRow)
        assertEquals(0, ui.offerRemainingSeconds)
    }

    @Test
    fun `들어오는 차 - 그냥 기다리면 상대 차 출차 임박에 안내받고 자리가 빈 뒤 도착해 주차 완료가 된다`() {
        enterFreshLot()
        bench.waitSeconds(5)
        assertEquals(EnteringPhase.WAITING, ui.enteringPhase, "출차 임박 전에는 안내가 없다")

        bench.waitUntil { it.enteringPhase == EnteringPhase.GUIDED }
        assertEquals("C", ui.guidedZone)
        assertEquals(3, ui.guidedRow)
        assertTrue(ui.offerRemainingSeconds in 179..180, "안내 유효 ${ui.offerRemainingSeconds}초")
        assertTrue(ui.observe.activeLines.single().startsWith("안내 중 offer-1 → 내 차 · C구역 3번 줄"))

        bench.waitSeconds(5)
        assertTrue(ui.routeProgress > 0f && ui.routeProgress < 1f)
        assertTrue(ui.etaSeconds < 35)

        bench.waitUntil { it.enteringPhase == EnteringPhase.PARKED }
        assertEquals("C", ui.parkedZone)
        assertEquals(3, ui.parkedRow)
        assertTrue(ui.parkedElapsedSeconds in 40..43, "진입부터 ${ui.parkedElapsedSeconds}초")
        assertEquals(1, ui.parkedOffers)
        assertTrue(ui.log.first().contains("주차 완료 · C구역 3번 줄 · 내준 차(peer-owner) 양보 기록 6회"), ui.log.first())
    }

    @Test
    fun `지금 주차 완료 - 안내받은 뒤 누르면 바로 주차 완료 화면이 된다`() {
        enterFreshLot()
        vm.parkNow()
        assertEquals(EnteringPhase.WAITING, ui.enteringPhase, "안내받기 전에는 아무 일도 없다")

        vm.peerDepartNow()
        assertEquals(EnteringPhase.GUIDED, ui.enteringPhase)
        vm.parkNow()
        assertEquals(EnteringPhase.PARKED, ui.enteringPhase)
        assertEquals(3, ui.parkedRow)
        assertEquals(listOf("offer-1 → 내 차 · 주차 성사"), ui.observe.recordLines)
    }

    // ---- 지나침 복귀 --------------------------------------------------------

    @Test
    fun `지나침 복귀 - 안내받은 자리를 지나치면 대기 시간을 보존한 채 대기로 돌아가고 같은 자리로는 다시 안내받지 않는다`() {
        enterFreshLot()
        vm.passWithoutParking()
        assertTrue(ui.observe.recordLines.isEmpty(), "안내받기 전의 지나침은 아무 일도 없다")

        vm.peerDepartNow()
        assertEquals(EnteringPhase.GUIDED, ui.enteringPhase)
        bench.waitSeconds(5)
        assertTrue(ui.routeProgress > 0f)

        vm.passWithoutParking()
        assertEquals(EnteringPhase.WAITING, ui.enteringPhase)
        assertEquals(0f, ui.routeProgress, "진입 지점에서 다시 기다린다")
        assertNull(ui.guidedRow)
        assertEquals(listOf("offer-1 → 내 차 · 지나침 · 대기 시간 보존 예"), ui.observe.recordLines)
        assertTrue(ui.log.first().contains("대기 순서로 복귀 · 대기 시간 보존 예"))

        bench.waitSeconds(30) // 그 자리는 비었고 아직 유효하지만
        assertEquals(EnteringPhase.WAITING, ui.enteringPhase)
        assertEquals(1, ui.totalOffers)
    }

    @Test
    fun `지나침 복귀 - 다음 예고에서 다시 안내받아 주차 완료 화면으로 끝난다`() {
        passThenParkAtNextSpot()
        assertEquals(Role.ENTERING, ui.role)
        assertEquals(EnteringPhase.PARKED, ui.enteringPhase)
        assertEquals("C", ui.parkedZone)
        assertEquals(5, ui.parkedRow, "두 번째 예고 자리")
        assertTrue(ui.parkedElapsedSeconds > 35)
        assertEquals(2, ui.totalOffers)
        assertEquals(1, ui.parkedOffers)
        assertEquals(0, ui.misguidedOffers)
        assertEquals(
            listOf("offer-2 → 내 차 · 주차 성사", "offer-1 → 내 차 · 지나침 · 대기 시간 보존 예"),
            ui.observe.recordLines,
        )
        assertTrue(ui.observe.activeLines.isEmpty())
    }

    // ---- 다음 바통 ----------------------------------------------------------

    @Test
    fun `다음 바통 - 주차를 마친 내 차가 그 자리에서 PARKED 인 다음 예고 차가 된다`() {
        passThenParkAtNextSpot()
        val generation = ui.departure.parkingGeneration
        vm.nextBaton()

        assertEquals(Role.LEAVING, ui.role)
        assertEquals(PARKED, bench.state)
        assertEquals("C", ui.myZone)
        assertEquals(5, ui.myRow, "방금 주차한 자리")
        assertEquals(generation + 1, ui.departure.parkingGeneration)
        assertFalse(ui.departure.suppressed)
        assertTrue(ui.departure.autoAnnounce.enabled)
        assertEquals(0, ui.guidedCars)
        assertNull(ui.cancelInSeconds)
        assertEquals(12, ui.yieldCount, "양보 기록은 그대로")
        assertEquals(FakeSignalStateDefaults, ui.fakeSignals)
        assertTrue("offer-2 → 내 차 · 주차 성사" in ui.observe.recordLines, "세계(기록)는 그대로")
        assertTrue(ui.log.any { it.contains("다음 바통 · 방금 주차한 내 차가 다음 예고 차가 됨") })
        assertTrue(ui.log.any { it.contains("모의 대기 차 추가 · peer-cn1 (C구역)") })
        assertTrue(ui.log.first().endsWith("역할: 나가는 차 · C구역 5번 줄에 주차 중"))
    }

    @Test
    fun `다음 바통 - 이어서 출차하면 다시 한 대에만 안내하고 양보 기록이 오른다`() {
        enterFreshLot()
        bench.waitUntil { it.enteringPhase == EnteringPhase.PARKED }
        assertEquals(3, ui.parkedRow)
        vm.nextBaton()
        assertEquals("대기: peer-a1 peer-a2 peer-a3 peer-b1 peer-b2 peer-cn1", ui.observe.waitingPeers)

        bench.boardAndAnnounce()
        vm.setParkingBrakeOn(false)
        assertEquals(DEPARTING, bench.state)
        assertEquals(1, ui.guidedCars)
        assertTrue(ui.observe.activeLines.single().contains("→ peer-cn1 · C구역 3번 줄"), ui.observe.activeLines.single())
        vm.setMoved(true)
        assertEquals(VACATED, bench.state)

        bench.waitUntil { it.yieldCount == 13 }
        assertEquals(2, ui.totalOffers, "내가 받은 안내 1건 + 내가 보낸 안내 1건")
        assertEquals(2, ui.parkedOffers)
        assertEquals(0, ui.misguidedOffers)
    }

    // 버그 의심(코드는 고치지 않음): "지나침 복귀" 뒤 "다음 바통"을 누르면 switchRole 이 넣어 준 모의 대기 차(peer-cn1)가
    // 내가 지나친 첫 자리(peer-owner#1, 비운 뒤 180초 동안 유효)로 곧바로 안내받는다(offer-3 → peer-cn1 · C구역 3번 줄).
    // 그래서 이어서 내가 출차 임박이 돼도 C구역에 안내받을 차가 없어 "안내받는 차 0대"로 남는다.
    // 안내 규칙 자체는 지켜지지만, ensureWaitingPeer 로 "다음 예고에 안내받을 차"를 보장하려던 의도와 어긋나 보인다.
    @Ignore("버그 의심 재현: 지나침 복귀 뒤 다음 바통에서는 모의 대기 차가 남은 자리로 먼저 안내받아 내 예고의 안내가 0대가 된다")
    @Test
    fun `재현 - 지나침 복귀 뒤 다음 바통으로 출차하면 내 예고를 안내받는 차가 있어야 한다`() {
        passThenParkAtNextSpot()
        vm.nextBaton()
        bench.boardAndAnnounce()
        vm.setParkingBrakeOn(false)
        assertEquals(DEPARTING, bench.state)
        assertEquals(1, ui.guidedCars, "active=${ui.observe.activeLines} waiting=${ui.observe.waitingPeers}")
    }

    @Test
    fun `다음 바통 - 주차를 마치기 전에는 받지 않는다`() {
        vm.nextBaton()
        assertEquals(Role.LEAVING, ui.role)
        assertTrue(ui.log.first().endsWith("다음 바통은 들어오는 차가 주차를 마친 뒤에 누를 수 있어요"))

        enterFreshLot()
        vm.nextBaton()
        assertEquals(Role.ENTERING, ui.role)
        assertEquals(EnteringPhase.WAITING, ui.enteringPhase)
    }

    // ---- 역할 전환 ----------------------------------------------------------

    @Test
    fun `역할 전환 - 안내 중에 들어오는 차로 바꾸면 내 예고와 안내가 취소되고 세계는 그대로다`() {
        bench.boardAndAnnounce()
        vm.setParkingBrakeOn(false)
        assertEquals(1, ui.guidedCars)

        vm.selectRole(Role.ENTERING)
        assertEquals(Role.ENTERING, ui.role)
        assertEquals(EnteringPhase.WAITING, ui.enteringPhase)
        assertEquals(PARKED, bench.state)
        assertEquals(1, ui.totalOffers, "기록은 남는다")
        assertEquals(1, ui.misguidedOffers)
        assertEquals(listOf("offer-1 → peer-c1 · 예고 취소 · 대기 시간 보존 예"), ui.observe.recordLines)
        assertTrue(ui.log.any { it.contains("모의 상대 차 예고 시작 · peer-owner#1") })
    }

    @Test
    fun `역할 전환 - 대기 중에 나가는 차로 돌아오면 원래 자리의 PARKED 다`() {
        vm.selectRole(Role.ENTERING)
        vm.selectRole(Role.LEAVING)
        assertEquals(Role.LEAVING, ui.role)
        assertEquals(PARKED, bench.state)
        assertEquals(3, ui.myRow)
        assertEquals("대기: peer-a1 peer-a2 peer-a3 peer-b1 peer-b2 peer-c1", ui.observe.waitingPeers)
    }
}
