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
}
