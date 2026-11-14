package app.baton.parking

import app.baton.core.departure.DepartureState.ANNOUNCING
import app.baton.core.departure.DepartureState.BOARDING
import app.baton.core.departure.DepartureState.DEPARTING
import app.baton.core.departure.DepartureState.PARKED
import app.baton.core.departure.DepartureState.VACATED
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 나가는 차: 시연 패널의 모의 신호 스위치를 누른 결과가 화면 상태로 끝나는지. */
class LeavingCarViewModelTest {
    private val bench = ViewModelBench()
    private val vm get() = bench.vm
    private val ui get() = bench.ui

    @AfterTest
    fun tearDown() = bench.close()

    private fun upcomingSpotsInC() = ui.zones.single { it.zoneId == "C" }.upcomingSpots

    // ---- 처음 화면 ----------------------------------------------------------

    @Test
    fun `처음 화면은 나가는 차 - 주차 중이고 모의 요소를 따로따로 알린다`() {
        assertEquals(Role.LEAVING, ui.role)
        assertEquals(PARKED, bench.state)
        assertEquals("C", ui.myZone)
        assertEquals(3, ui.myRow)
        assertEquals(12, ui.yieldCount)
        assertEquals(0, ui.guidedCars)
        assertNull(ui.cancelInSeconds)
        assertTrue(ui.departure.autoAnnounce.enabled)
        assertEquals(listOf("모의 신호", "모의 위치", "모의 상대 차", "시간 전진 없음"), ui.mockChips)
        assertEquals("모의 신호", ui.signalSourceName)
        assertEquals("대기: peer-a1 peer-a2 peer-a3 peer-b1 peer-b2 peer-c1", ui.observe.waitingPeers)
        assertEquals(0, ui.totalOffers)
        // 읽은 신호 4줄: 값은 다 받았고(회색 아님) 아직 본 변화는 없다
        assertEquals(listOf("운전석 문", "착좌 · 안전벨트", "주차 브레이크 · 페달", "차량 이동"), ui.signalLines.map { it.label })
        assertTrue(ui.signalLines.none { it.muted || it.on })
    }

    @Test
    fun `패널 접기와 시간 전진은 화면 상태에 드러난다`() {
        assertTrue(ui.panelOpen)
        vm.togglePanel()
        assertFalse(ui.panelOpen)
        vm.skipTime(30)
        assertEquals("시간 전진 +30초", ui.mockChips.last())
        assertTrue(ui.log.first().endsWith("시간 +30초"))
    }

    // ---- 정상 출차 ----------------------------------------------------------

    @Test
    fun `정상 출차 - 문 여닫힘, 착좌, 주차 브레이크 해제, 위치 이탈이면 VACATED 이고 안내는 한 대에만 간다`() {
        bench.doorCycle()
        assertEquals(BOARDING, bench.state)
        assertTrue(ui.departure.doorCycleSeen)

        bench.sitAndBelt()
        assertEquals(ANNOUNCING, bench.state)
        assertEquals(1, upcomingSpotsInC(), "예고는 구역 집계에만 잡힌다")
        assertEquals(0, ui.guidedCars, "출차 임박 전에는 아무에게도 안내하지 않는다")
        assertEquals(0, ui.totalOffers)
        assertEquals(119, ui.cancelInSeconds, "예고 뒤 1초 지남")

        vm.setParkingBrakeOn(false)
        assertEquals(DEPARTING, bench.state)
        assertTrue(ui.departure.brakeReleaseSeen)
        assertEquals(1, ui.guidedCars)
        assertEquals(1, ui.observe.activeLines.size)
        assertTrue(ui.observe.activeLines.single().startsWith("안내 중 offer-1 → peer-c1 · C구역 3번 줄"))

        bench.waitSeconds(2)
        vm.setMoved(true)
        assertEquals(VACATED, bench.state)
        assertNull(ui.cancelInSeconds)
        assertTrue(ui.departure.movedSeen)

        // 안내받은 차가 도착해 주차할 때까지: 그동안 안내는 늘 한 건 이하, 받는 차는 한 대뿐
        var mostActive = 0
        var waited = 0
        while (ui.yieldCount == 12 && waited < 120) {
            bench.waitSeconds(0.25)
            waited += 1
            mostActive = maxOf(mostActive, ui.observe.activeLines.size, ui.guidedCars)
        }
        assertEquals(13, ui.yieldCount, "안내받은 차가 주차하면 내 양보 기록이 1 오른다")
        assertEquals(1, mostActive)
        assertEquals(1, ui.totalOffers, "보낸 안내는 한 건뿐")
        assertEquals(1, ui.parkedOffers)
        assertEquals(0, ui.misguidedOffers)
        assertEquals(listOf("offer-1 → peer-c1 · 주차 성사"), ui.observe.recordLines)
        assertEquals("대기: peer-a1 peer-a2 peer-a3 peer-b1 peer-b2", ui.observe.waitingPeers, "다른 구역 차는 그대로 대기")
        assertEquals(VACATED, bench.state)
        assertEquals(0, ui.guidedCars)
    }

    @Test
    fun `정상 출차 - 주차 브레이크 대신 브레이크 페달을 밟아도 출차 임박이 되고 한 대에 안내한다`() {
        bench.boardAndAnnounce()
        vm.setPedalPressed(true)
        assertEquals(DEPARTING, bench.state)
        assertTrue(ui.departure.pedalPressSeen)
        assertEquals(1, ui.guidedCars)
        vm.setMoved(true)
        assertEquals(VACATED, bench.state)
        assertEquals(1, ui.totalOffers)
        assertTrue(ui.observe.leadTime.startsWith("예고→이탈 시간: 최근 1.0초"), ui.observe.leadTime)
    }

    @Test
    fun `위치 정확도가 낮으면 안내는 줄 없이 구역까지만 간다`() {
        vm.setLowQualityLocation(true)
        bench.boardAndAnnounce()
        vm.setParkingBrakeOn(false)
        assertEquals(DEPARTING, bench.state)
        assertTrue(ui.observe.activeLines.single().contains("peer-c1 · C구역(구역만)"), ui.observe.activeLines.single())
        assertTrue(ui.signalLines.last().value.endsWith("정확도 낮음"))
    }

    // ---- 짐만 싣기 ----------------------------------------------------------

    @Test
    fun `짐만 싣기 - 문만 여닫고 앉지 않으면 BOARDING 에 머물고 예고도 안내도 없다`() {
        bench.doorCycle()
        bench.waitSeconds(3)
        bench.doorCycle()
        assertEquals(BOARDING, bench.state)

        bench.waitSeconds(30)
        vm.skipTime(30)
        assertEquals(BOARDING, bench.state, "착좌가 없으면 시간이 지나도 예고하지 않는다")
        assertNull(ui.departure.announcementGeneration)
        assertNull(ui.cancelInSeconds)
        assertEquals(0, upcomingSpotsInC())
        assertEquals(0, ui.guidedCars)
        assertEquals(0, ui.totalOffers)
        assertFalse(ui.log.any { it.contains("ANNOUNCING") })
    }

    @Test
    fun `문을 열기만 하고 닫지 않으면 PARKED 그대로다`() {
        vm.setDoorOpen(true)
        bench.waitSeconds(30)
        assertEquals(PARKED, bench.state)
        assertEquals("열림", ui.signalLines.first().value)
    }

    // ---- 예고 후 무이동 취소 -------------------------------------------------

    @Test
    fun `예고 후 무이동 120초 - 기다리면 PARKED 로 취소되고 안내도 함께 취소된다`() {
        bench.boardAndAnnounce() // 예고 뒤 1초 지남
        vm.setParkingBrakeOn(false)
        assertEquals(DEPARTING, bench.state)
        assertEquals(1, ui.guidedCars)

        bench.waitMillis(118_750) // 예고 뒤 119.75초
        assertEquals(DEPARTING, bench.state, "120초 직전까지는 유지")
        assertEquals(1, ui.cancelInSeconds)
        assertEquals(1, ui.guidedCars)

        bench.waitMillis(250) // 예고 뒤 정확히 120초
        assertEquals(PARKED, bench.state)
        assertNull(ui.cancelInSeconds)
        assertNull(ui.departure.announcementGeneration)
        assertEquals(0, ui.guidedCars)
        assertTrue(ui.observe.activeLines.isEmpty(), "안내도 함께 취소")
        assertEquals(listOf("offer-1 → peer-c1 · 예고 취소 · 대기 시간 보존 예"), ui.observe.recordLines)
        assertEquals(1, ui.misguidedOffers, "잘못 보낸 안내로 센다")
        assertEquals(1, ui.totalOffers)
        assertEquals(0, upcomingSpotsInC())
        assertTrue(ui.observe.waitingPeers.contains("peer-c1"), "안내받던 차는 대기로 돌아간다")
        assertTrue(ui.log.any { it.contains("DEPARTING → PARKED · 예고 시작 후 120초 안에 자리를 벗어나지 않음: 예고 취소") })

        // 앉아만 있다고 예고가 되풀이되지 않는다
        bench.waitSeconds(30)
        assertEquals(PARKED, bench.state)
        assertEquals(1, ui.totalOffers)
    }

    @Test
    fun `예고 후 무이동 120초 - 시간 전진으로 건너도 그냥 기다린 것과 같은 화면 상태다`() {
        val waited = ViewModelBench()
        try {
            for (b in listOf(bench, waited)) {
                b.boardAndAnnounce()
                b.vm.setParkingBrakeOn(false)
            }
            vm.skipTime(118)
            waited.waitSeconds(118)
            assertEquals(DEPARTING, bench.state, "예고 뒤 119초")
            assertEquals(waited.ui.cancelInSeconds, ui.cancelInSeconds)
            assertEquals(waited.ui.observe, ui.observe)

            vm.skipTime(1)
            waited.waitSeconds(1)
            assertEquals(PARKED, bench.state)
            assertEquals(PARKED, waited.state)
            assertEquals(waited.ui.observe, ui.observe)
            assertEquals(waited.ui.guidedCars, ui.guidedCars)
        } finally {
            waited.close()
        }
    }

    @Test
    fun `예고만 하고 출차 임박 없이 120초가 지나도 취소된다 - 보낸 안내는 없다`() {
        bench.boardAndAnnounce()
        assertEquals(ANNOUNCING, bench.state)
        bench.waitSeconds(119)
        assertEquals(PARKED, bench.state)
        assertEquals(0, ui.totalOffers)
        assertEquals(0, upcomingSpotsInC())
    }

    @Test
    fun `예고 중 문을 다시 열고 60초 안에 닫지 않으면 취소되고 안내도 취소된다`() {
        bench.boardAndAnnounce()
        vm.setParkingBrakeOn(false)
        vm.setDoorOpen(true)
        assertEquals(60, ui.cancelInSeconds)
        bench.waitMillis(59_750)
        assertEquals(DEPARTING, bench.state)
        bench.waitMillis(250)
        assertEquals(PARKED, bench.state)
        assertEquals(0, ui.guidedCars)
        assertEquals(1, ui.misguidedOffers)
    }

    @Test
    fun `예고 중 문을 다시 열었다가 60초 안에 닫으면 예고와 안내가 유지된다`() {
        bench.boardAndAnnounce()
        vm.setParkingBrakeOn(false)
        vm.setDoorOpen(true)
        bench.waitSeconds(30)
        vm.setDoorOpen(false)
        bench.waitSeconds(40)
        assertEquals(DEPARTING, bench.state)
        assertEquals(1, ui.guidedCars)
        assertEquals(0, ui.misguidedOffers)
    }

    @Test
    fun `예고 중 착좌가 풀리고 60초 안에 돌아오지 않으면 취소된다`() {
        bench.boardAndAnnounce()
        vm.setSeated(false)
        assertEquals(60, ui.cancelInSeconds)
        bench.waitSeconds(60)
        assertEquals(PARKED, bench.state)
        assertTrue(ui.log.any { it.contains("착좌가 풀린 뒤 60초 안에 돌아오지 않음: 예고 취소") })
    }

    // ---- 이번엔 예고 안 함 ---------------------------------------------------

    @Test
    fun `이번엔 예고 안 함 - 누르면 이번 주차 동안 탑승해 출차해도 예고도 안내도 없다`() {
        vm.suppressAnnouncement()
        assertTrue(ui.departure.suppressed)
        assertFalse(ui.departure.canSuppress, "이미 받았으므로 다시 받지 않는다")

        bench.boardAndAnnounce()
        vm.setParkingBrakeOn(false)
        bench.waitSeconds(5)
        assertEquals(PARKED, bench.state)
        assertNull(ui.departure.announcementGeneration)
        assertEquals(0, upcomingSpotsInC())
        assertEquals(0, ui.guidedCars)

        vm.setMoved(true)
        assertEquals(VACATED, bench.state, "예고 없이 출차")
        bench.waitSeconds(30)
        assertEquals(0, ui.totalOffers)
        assertEquals(12, ui.yieldCount)
        assertEquals("예고→이탈 시간: 아직 없음", ui.observe.leadTime)
        assertTrue(ui.log.any { it.contains("PARKED → VACATED · 예고 없이 출차") })
    }

    @Test
    fun `이번엔 예고 안 함 - 예고 중에 누르면 예고가 취소되고 PARKED 로 돌아간다`() {
        bench.boardAndAnnounce()
        assertEquals(1, upcomingSpotsInC())
        vm.suppressAnnouncement()
        assertEquals(PARKED, bench.state)
        assertTrue(ui.departure.suppressed)
        assertEquals(0, upcomingSpotsInC())
        assertNull(ui.cancelInSeconds)

        vm.setParkingBrakeOn(false)
        bench.waitSeconds(10)
        assertEquals(PARKED, bench.state)
        assertEquals(0, ui.totalOffers)
    }

    @Test
    fun `이번엔 예고 안 함 - 출차 임박 뒤에는 받지 않고 안내를 유지한다`() {
        bench.boardAndAnnounce()
        vm.setParkingBrakeOn(false)
        assertFalse(ui.departure.canSuppress)
        vm.suppressAnnouncement()
        assertEquals(DEPARTING, bench.state)
        assertFalse(ui.departure.suppressed)
        assertEquals(1, ui.guidedCars)
        assertTrue(ui.log.first().endsWith("움직이는 중에는 '예고 안 함'을 받지 않음"))
    }

    @Test
    fun `이번엔 예고 안 함 - 이번 주차에만 적용되고 처음부터 다시 하면 풀린다`() {
        vm.suppressAnnouncement()
        vm.restartScenario()
        assertFalse(ui.departure.suppressed)
        assertTrue(ui.departure.canSuppress)
        bench.boardAndAnnounce()
        assertEquals(ANNOUNCING, bench.state)
    }

    // ---- 최소 신호 ----------------------------------------------------------

    @Test
    fun `최소 신호 - 위치를 읽지 못하면 자동 예고가 꺼지고 이유가 표시된다`() {
        vm.setLocationLost(true)
        val auto = ui.departure.autoAnnounce
        assertFalse(auto.enabled)
        assertEquals("자동 예고 꺼짐 · 위치 신호 일시 불가", auto.reason)
        val move = ui.signalLines.last()
        assertEquals("차량 이동", move.label)
        assertEquals("일시 불가", move.value)
        assertTrue(move.muted)

        bench.boardAndAnnounce()
        vm.setParkingBrakeOn(false)
        bench.waitSeconds(10)
        assertEquals(BOARDING, bench.state, "탑승까지만 가고 예고하지 않는다")
        assertNull(ui.departure.announcementGeneration)
        assertEquals(0, upcomingSpotsInC())
        assertEquals(0, ui.totalOffers)
    }

    @Test
    fun `최소 신호 - 위치가 돌아오면 이유가 사라지고 예고한다`() {
        vm.setLocationLost(true)
        bench.boardAndAnnounce()
        assertEquals(BOARDING, bench.state)
        vm.setLocationLost(false)
        assertTrue(ui.departure.autoAnnounce.enabled)
        assertNull(ui.departure.autoAnnounce.reason)
        assertEquals(ANNOUNCING, bench.state)
        assertNotNull(ui.cancelInSeconds)
    }

    @Test
    fun `최소 신호 - 안내 중에 위치가 끊기면 예고와 안내가 취소된다`() {
        bench.boardAndAnnounce()
        vm.setParkingBrakeOn(false)
        assertEquals(1, ui.guidedCars)
        vm.setLocationLost(true)
        assertEquals(PARKED, bench.state)
        assertEquals("자동 예고 꺼짐 · 위치 신호 일시 불가", ui.departure.autoAnnounce.reason)
        assertEquals(0, ui.guidedCars)
        assertTrue(ui.observe.activeLines.isEmpty())
        assertEquals(1, ui.misguidedOffers)
        assertTrue(ui.log.any { it.contains("최소 신호(착좌·위치)를 읽지 못함: 예고 취소") })
    }

    // ---- 처음부터 -----------------------------------------------------------

    @Test
    fun `처음부터 - 안내 중에 누르면 새 세계에서 PARKED 로 시작한다`() {
        bench.boardAndAnnounce()
        vm.setParkingBrakeOn(false)
        val generation = ui.departure.parkingGeneration
        vm.restartScenario()
        assertEquals(PARKED, bench.state)
        assertEquals(generation + 1, ui.departure.parkingGeneration, "주차 세대는 한 번만 오른다")
        assertEquals(0, ui.totalOffers)
        assertEquals(0, ui.guidedCars)
        assertEquals(FakeSignalStateDefaults, ui.fakeSignals)
        assertEquals("대기: peer-a1 peer-a2 peer-a3 peer-b1 peer-b2 peer-c1", ui.observe.waitingPeers)
    }
}
