package app.baton.core.match

import app.baton.core.FakeClock
import app.baton.core.SteppedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MockPeerServiceTest {
    private val clock = FakeClock()
    private val stepped = SteppedClock(clock)
    private val coordinator = GuidanceCoordinator(stepped)
    private val peers = MockPeerService(stepped, coordinator)

    private fun run(seconds: Int) = repeat(seconds) {
        clock.advanceSeconds(1)
        peers.tick()
    }

    @Test
    fun `내가 나갈 때 - 같은 구역 상대 차가 안내받고 자리가 빈 뒤에 주차해 양보 기록이 오른다`() {
        peers.seedWaitingPeers()
        assertEquals(
            listOf(ZoneSummary("A", 0, 3), ZoneSummary("B", 0, 2), ZoneSummary("C", 0, 1)),
            coordinator.zoneSummaries(),
        )
        coordinator.setYieldCount("me", 12)
        coordinator.announceSpot("my-spot", "C", 3, "me")
        run(3)
        assertEquals(0, coordinator.guidedCarCount("my-spot"))

        coordinator.openGuidance("my-spot")
        assertEquals("peer-c1", coordinator.offerForSpot("my-spot")?.carId)

        run(60)
        assertNotNull(coordinator.spot("my-spot"), "자리가 비기 전에는 상대 차가 근처에서 기다린다")
        assertEquals(MockPeerService.HOLD_DISTANCE_METERS, coordinator.waitingCar("peer-c1")!!.distanceMeters, 1e-9)

        coordinator.markVacated("my-spot")
        run(5)
        assertNull(coordinator.spot("my-spot"))
        assertEquals(13, coordinator.yieldCount("me"))
    }

    @Test
    fun `내가 들어갈 때 - 상대 차가 예고, 출차 임박, 비움 순서로 나가고 나에게만 안내한다`() {
        peers.seedDepartingPeer()
        assertEquals("C", coordinator.recommendZone())
        coordinator.registerWaiting("me", "C", 98.0)
        assertEquals(ZoneSummary("C", 1, 0), coordinator.zoneSummaries(excludeCarId = "me").last())
        assertNull(coordinator.offerFor("me"))

        run(6)
        val offer = assertNotNull(coordinator.offerFor("me"))
        assertEquals(peers.peerSpotId, offer.announcementId)
        assertEquals(3, offer.row)
        assertEquals(false, coordinator.spot(offer.announcementId)?.vacated)

        run(8)
        assertEquals(true, coordinator.spot(offer.announcementId)?.vacated)

        val result = assertNotNull(coordinator.reportParked("me", offer.offerId))
        assertEquals(6, result.ownerYieldCount)
    }

    // ---- 소급 이동 금지 (2차 검수 (2)-3) -----------------------------------

    @Test
    fun `검수 재현 - tick 없이 30초 뒤 예고와 안내를 시작하고 같은 시각에 tick 해도 거리는 98m 그대로다`() {
        peers.trackCar("car", "C", 98.0)
        clock.advanceSeconds(30) // tick 없이 시계만 30초

        coordinator.announceSpot("spot", "C", 3, "owner")
        coordinator.openGuidance("spot")
        val offer = assertNotNull(coordinator.offerFor("car"))
        assertEquals(clock.nowMillis(), offer.offeredAtMillis)

        peers.tick() // 같은 시각: 안내 이후 지난 시간이 없다

        assertEquals(98.0, peers.distanceOf("car")!!, 1e-9)
        assertEquals(98.0, coordinator.waitingCar("car")!!.distanceMeters, 1e-9)

        // 그 뒤 10초는 평소대로 서행한다.
        clock.advanceSeconds(10)
        peers.tick()
        assertEquals(98.0 - 10 * 2.8, peers.distanceOf("car")!!, 1e-6)
    }

    @Test
    fun `진행하지 않은 구간 뒤에 자리가 비어도 비움 이전 구간에서 도착 처리하지 않는다`() {
        peers.trackCar("car", "C", 5.0) // 자리 바로 앞
        coordinator.announceSpot("spot", "C", 3, "owner")
        coordinator.openGuidance("spot")
        val offer = assertNotNull(coordinator.offerFor("car"))
        clock.advanceSeconds(30) // tick 없이 30초
        coordinator.markVacated("spot") // 지금(30초)에야 비었다
        val vacatedAt = clock.nowMillis()

        peers.tick()

        assertNotNull(coordinator.spot("spot"), "비기 전 구간을 다시 밟으며 주차해 버리면 안 된다")
        assertEquals(5.0, peers.distanceOf("car")!!, 1e-9)
        assertNull(coordinator.record(offer.offerId)?.outcome)

        clock.advanceSeconds(3) // 5m ÷ 2.8m/s ≈ 1.8초
        peers.tick()
        val record = assertNotNull(coordinator.record(offer.offerId))
        assertEquals(GuidanceOutcome.PARKED, record.outcome)
        assertEquals(true, record.endedAtMillis!! > vacatedAt, "주차 시각은 비운 시각보다 뒤다")
        assertNull(peers.distanceOf("car"))
    }

    @Test
    fun `비운 시각보다 이른 시각의 주차 보고는 받지 않는다`() {
        coordinator.registerWaiting("car", "C", 5.0)
        coordinator.announceSpot("spot", "C", 3, "owner")
        coordinator.openGuidance("spot")
        val offer = assertNotNull(coordinator.offerFor("car"))
        val before = clock.nowMillis()
        clock.advanceSeconds(30)
        coordinator.markVacated("spot")
        assertNull(stepped.at(before + 2_000L) { coordinator.reportParked("car", offer.offerId) })
        assertNotNull(coordinator.reportParked("car", offer.offerId))
    }

    @Test
    fun `모의 차 입력 함수는 입력보다 먼저 세계를 진행한다 - 늦게 넣은 차가 지난 구간을 달리지 않는다`() {
        peers.seedDepartingPeer() // 6초에 출차 임박, 14초에 비움
        clock.advanceSeconds(20)
        peers.trackCar("late", "C", 98.0) // 20초에 대기 등록 → 바로 안내받음
        val offer = assertNotNull(coordinator.offerFor("late"))
        assertEquals(clock.nowMillis(), offer.offeredAtMillis)
        peers.tick()
        assertEquals(98.0, peers.distanceOf("late")!!, 1e-9)
    }

    // ---- ensureWaitingPeer: 안내받지 않은 대기 차 보장 -----------------------

    /** C구역에 안내가 열린 자리를 [count]개 만든다(대기 차가 없으면 아무에게도 안내되지 않은 채 열려 있다). */
    private fun openSpotsInC(count: Int) = repeat(count) { i ->
        coordinator.announceSpot("spot-$i", "C", i + 1, "owner-$i")
        coordinator.openGuidance("spot-$i")
    }

    private fun peersInC() = coordinator.waitingCars().filter { it.zoneId == "C" }.map { it.id }

    @Test
    fun `ensureWaitingPeer - 안내받지 않은 대기 차가 이미 있으면 추가하지 않는다`() {
        peers.trackCar("peer-c1", "C", 60.0)
        assertNull(coordinator.offerFor("peer-c1"))

        assertNull(peers.ensureWaitingPeer("C"))
        assertEquals(listOf("peer-c1"), peersInC())

        // 열린 자리가 있어도, 안내받는 차 말고 안내받지 않은 차가 한 대 있으면 그대로 둔다.
        openSpotsInC(1)
        assertEquals("peer-c1", coordinator.offerForSpot("spot-0")?.carId)
        peers.trackCar("peer-c2", "C", 80.0)
        assertNull(coordinator.offerFor("peer-c2"))
        assertNull(peers.ensureWaitingPeer("C"))
        assertEquals(listOf("peer-c1", "peer-c2"), peersInC())
    }

    @Test
    fun `ensureWaitingPeer - 제외한 차와 다른 구역 차는 대기 차로 치지 않는다`() {
        peers.trackCar("me", "C", 98.0)
        peers.trackCar("peer-a1", "A", 60.0)

        assertEquals("peer-cn1", peers.ensureWaitingPeer("C", exceptCarId = "me"))
        assertEquals(listOf("me", "peer-cn1"), peersInC())
    }

    @Test
    fun `ensureWaitingPeer - 대기 차가 모두 안내받는 중이면 안내받지 않은 차가 생길 때까지 추가한다`() {
        peers.trackCar("peer-c1", "C", 60.0)
        openSpotsInC(2) // spot-0 은 peer-c1 에게, spot-1 은 안내할 차가 없어 열린 채
        assertEquals("peer-c1", coordinator.offerForSpot("spot-0")?.carId)
        assertNull(coordinator.offerForSpot("spot-1"))

        // 처음 넣은 차는 spot-1 로 곧바로 안내받으므로 한 대 더 넣는다.
        assertEquals("peer-cn1", peers.ensureWaitingPeer("C"))
        assertEquals(listOf("peer-c1", "peer-cn1", "peer-cn2"), peersInC())
        assertEquals("peer-c1", coordinator.offerForSpot("spot-0")?.carId, "이미 있던 안내는 그대로다")
        assertEquals("peer-cn1", coordinator.offerForSpot("spot-1")?.carId)
        assertNull(coordinator.offerFor("peer-cn2"), "다음 예고가 안내할 차가 남는다")

        // 이어지는 예고는 그 차에게 안내한다.
        coordinator.announceSpot("next", "C", 9, "next-owner")
        coordinator.openGuidance("next")
        assertEquals("peer-cn2", coordinator.offerForSpot("next")?.carId)

        // 다시 부르면 또 모두 안내 중이므로 한 대만 더 넣는다.
        assertEquals("peer-cn3", peers.ensureWaitingPeer("C"))
        assertNull(coordinator.offerFor("peer-cn3"))
    }

    @Test
    fun `ensureWaitingPeer - 추가 횟수는 그 구역의 안내가 열린 자리 수 더하기 1 을 넘지 않는다`() {
        // 열린 자리 3개가 모두 비어 있을 때: 3대가 자리마다 안내받고 한 대가 남는다 = 꼭 4대.
        openSpotsInC(3)
        // 다른 구역의 열린 자리와, 아직 안내를 시작하지 않은 C구역 예고는 상한에 들지 않는다.
        coordinator.announceSpot("a-spot", "A", 1, "a-owner")
        coordinator.openGuidance("a-spot")
        coordinator.announceSpot("not-open", "C", 8, "quiet-owner")

        assertEquals("peer-cn1", peers.ensureWaitingPeer("C"))
        assertEquals(listOf("peer-cn1", "peer-cn2", "peer-cn3", "peer-cn4"), peersInC())
        assertEquals(3, coordinator.activeOffers().count { it.zoneId == "C" })
        assertNull(coordinator.offerFor("peer-cn4"))
        assertNull(coordinator.offerForSpot("a-spot"), "다른 구역 자리로는 가지 않는다")

        // 열린 자리가 없으면 한 대만 넣는다.
        assertEquals("peer-bn5", peers.ensureWaitingPeer("B"))
        assertEquals(listOf("peer-bn5"), coordinator.waitingCars().filter { it.zoneId == "B" }.map { it.id })
    }
}
