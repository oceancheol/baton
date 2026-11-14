package app.baton.core.match

import app.baton.core.FakeClock
import app.baton.core.SteppedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 검수 (3)-7: 시간을 한 번에 건너뛰든 조금씩 흐르든 결과가 같다. */
class TimeProgressEquivalenceTest {

    private class World {
        val clock = FakeClock()
        val stepped = SteppedClock(clock)
        val coordinator = GuidanceCoordinator(stepped)
        val peers = MockPeerService(stepped, coordinator)

        init {
            peers.seedDepartingPeer()
            peers.trackCar("me", "C", 98.0)
        }

        fun advance(totalSeconds: Int, tickEverySeconds: Int) {
            repeat(totalSeconds / tickEverySeconds) {
                clock.advanceSeconds(tickEverySeconds.toLong())
                peers.tick()
            }
        }

        /** 비교할 모든 결과. */
        fun state(): List<Any?> = listOf(
            coordinator.announcedSpots(),
            coordinator.activeOffers(),
            coordinator.records(),
            coordinator.metrics(),
            coordinator.waitingCars(),
            peers.trackedCarIds().associateWith { peers.distanceOf(it) },
            coordinator.yieldCount(MockPeerService.PEER_OWNER_ID),
        )
    }

    @Test
    fun `검수 재현 - 30초를 한 번에 건너뛰어도 출차 임박은 6초, 비움은 14초 시각으로 처리된다`() {
        val w = World()
        val start = w.clock.nowMillis()
        w.advance(30, tickEverySeconds = 30)

        val spot = assertNotNull(w.coordinator.spot(w.peers.peerSpotId!!))
        assertEquals(start + 6_000L, spot.guidanceOpenedAtMillis)
        assertEquals(start + 14_000L, spot.vacatedAtMillis)
        val offer = assertNotNull(w.coordinator.offerFor("me"))
        assertEquals(start + 6_000L, offer.offeredAtMillis, "안내도 건너뛴 구간 안의 제 시각에 받는다")
        // 안내받은 6초부터 24초 동안 서행: 98 − 24 × 2.8
        assertEquals(98.0 - 24 * 2.8, w.peers.distanceOf("me")!!, 1e-6)
    }

    @Test
    fun `30초 한 번과 1초 30번의 결과가 같다`() {
        val once = World().apply { advance(30, tickEverySeconds = 30) }
        val stepped = World().apply { advance(30, tickEverySeconds = 1) }
        assertEquals(stepped.state(), once.state())
    }

    @Test
    fun `60초 한 번과 1초 60번, 3초 20번의 주차 결과가 같다`() {
        val once = World().apply { advance(60, tickEverySeconds = 60) }
        val bySecond = World().apply { advance(60, tickEverySeconds = 1) }
        val byThree = World().apply { advance(60, tickEverySeconds = 3) }
        assertEquals(bySecond.state(), once.state())
        assertEquals(bySecond.state(), byThree.state())

        // 내 차는 6초에 안내받아 35초 뒤(41초)에 주차했다.
        val record = once.coordinator.records().single()
        assertEquals(GuidanceOutcome.PARKED, record.outcome)
        assertEquals("me", record.carId)
        assertEquals(41_000L, record.endedAtMillis!! - 1_000_000L)
        assertNull(once.peers.distanceOf("me"))
        assertEquals(6, once.coordinator.yieldCount(MockPeerService.PEER_OWNER_ID))
        assertEquals(14_000L, once.coordinator.metrics().lastLeadTimeMillis)
    }

    @Test
    fun `눈금에 맞지 않는 간격으로 불러도 같은 시각까지의 결과가 같다`() {
        val once = World().apply {
            clock.advanceMillis(45_000)
            peers.tick()
        }
        val uneven = World().apply {
            for (ms in listOf(1L, 249L, 333L, 17L, 5_400L, 9_000L, 12_345L, 655L, 17_000L)) {
                clock.advanceMillis(ms)
                peers.tick()
            }
        }
        assertEquals(45_000L, uneven.clock.nowMillis() - 1_000_000L)
        assertEquals(once.state(), uneven.state())
    }

    @Test
    fun `건너뛴 구간에서 안내 만료와 다음 차 넘김도 제 시각에 일어난다`() {
        fun world() = World().apply {
            coordinator.announceSpot("far-spot", "A", 2, "someone")
            coordinator.openGuidance("far-spot") // peer-a1 에게. 비지 않아 8m 앞에서 기다리다 180초 뒤 만료
        }
        val once = world().apply { advance(400, tickEverySeconds = 400) }
        val bySecond = world().apply { advance(400, tickEverySeconds = 1) }
        assertEquals(bySecond.state(), once.state())
        val handed = once.coordinator.records().filter { it.announcementId == "far-spot" }
        assertEquals(listOf("peer-a1", "peer-a2", "peer-a3"), handed.map { it.carId })
        assertEquals(listOf(180_000L, 360_000L), handed.take(2).map { it.endedAtMillis!! - 1_000_000L })
    }

    // ---- 중간에 외부 입력이 낀 경우 (2차 검수 (2)-3) -------------------------

    /**
     * 30.25초(눈금에 맞지 않는 시각)에 A구역 예고·안내 시작, 50.25초에 비움이 **밖에서** 들어온다.
     * 입력을 넣기 전에 그 시각까지 tick 으로 세계를 먼저 진행한다는 규칙만 지키면, 그 사이에 tick 을 몇 번 불렀든 같아야 한다.
     */
    private fun worldWithExternalInput(tickEveryMillis: Long): World = World().apply {
        val start = clock.nowMillis()
        fun runTo(offsetMillis: Long) {
            val target = start + offsetMillis
            while (clock.nowMillis() < target) {
                clock.advanceMillis(minOf(tickEveryMillis, target - clock.nowMillis()))
                peers.tick()
            }
        }
        runTo(30_250)
        coordinator.announceSpot("ext", "A", 2, "ext-owner")
        coordinator.openGuidance("ext")
        runTo(50_250)
        coordinator.markVacated("ext")
        runTo(90_000)
    }

    @Test
    fun `중간에 외부 입력이 끼어도 입력 전에 세계를 진행하면 tick 간격과 무관하게 결과가 같다`() {
        val onlyAtInputs = worldWithExternalInput(tickEveryMillis = Long.MAX_VALUE / 4)
        val bySecond = worldWithExternalInput(tickEveryMillis = 1_000)
        val byQuarter = worldWithExternalInput(tickEveryMillis = 250)
        val uneven = worldWithExternalInput(tickEveryMillis = 7_300)
        assertEquals(bySecond.state(), onlyAtInputs.state())
        assertEquals(bySecond.state(), byQuarter.state())
        assertEquals(bySecond.state(), uneven.state())

        // 내용 확인: 밖에서 연 안내는 30.25초에 peer-a1 에게 갔고, 그때부터 움직여 비운 뒤(50.25초 이후)에 주차했다.
        val ext = onlyAtInputs.coordinator.records().single { it.announcementId == "ext" }
        assertEquals("peer-a1", ext.carId)
        assertEquals(30_250L, ext.offeredAtMillis - 1_000_000L)
        assertEquals(GuidanceOutcome.PARKED, ext.outcome)
        // 120m 를 30.3초 눈금부터 서행: 8m 앞 대기까지 40초(70.3초), 비어 있으므로 그대로 도착 = 120 ÷ 2.8 ≈ 42.9초 뒤
        assertEquals(73_200L, ext.endedAtMillis!! - 1_000_000L)
        assertEquals(20_000L, onlyAtInputs.coordinator.metrics().lastLeadTimeMillis)
    }

    @Test
    fun `외부 입력 직후의 tick 은 입력 이전 구간을 소급하지 않는다 - 입력 뒤 경과한 만큼만 움직인다`() {
        val w = World() // 진행 없이 30.25초 뒤에 입력부터 넣는 잘못된 순서
        w.clock.advanceMillis(30_250)
        w.coordinator.announceSpot("ext", "A", 2, "ext-owner")
        w.coordinator.openGuidance("ext")
        w.peers.tick()
        assertEquals(120.0, w.peers.distanceOf("peer-a1")!!, 1e-9)
        w.clock.advanceMillis(10_000)
        w.peers.tick()
        // 30.3초 눈금부터 40.2초 눈금까지 99눈금(9.9초)
        assertEquals(120.0 - 9.9 * 2.8, w.peers.distanceOf("peer-a1")!!, 1e-6)
    }
}
