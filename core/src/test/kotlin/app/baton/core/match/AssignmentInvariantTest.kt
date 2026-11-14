package app.baton.core.match

import app.baton.core.FakeClock
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 검수 (3)-5: 여러 자리·여러 차에서 등록·취소·만료·탈퇴를 섞어도 차량별·예고별 유효 안내는 1 이하. */
class AssignmentInvariantTest {

    private fun check(c: GuidanceCoordinator, clock: FakeClock, step: String) {
        val offers = c.activeOffers()
        val now = clock.nowMillis()
        assertEquals(offers.size, offers.map { it.carId }.distinct().size, "차 한 대에 안내 둘 ($step)")
        assertEquals(offers.size, offers.map { it.announcementId }.distinct().size, "예고 하나에 안내 둘 ($step)")
        assertEquals(offers.size, offers.map { it.offerId }.distinct().size, "안내 ID 중복 ($step)")
        for (offer in offers) {
            val spot = assertNotNull(c.spot(offer.announcementId), "없는 예고의 안내 ($step)")
            val car = assertNotNull(c.waitingCar(offer.carId), "대기에 없는 차의 안내 ($step)")
            assertEquals(spot.zoneId, car.zoneId, "다른 구역 차에게 안내 ($step)")
            assertTrue(spot.guidanceOpen, "안내를 열지 않은 예고의 안내 ($step)")
            assertTrue(now < offer.expiresAtMillis, "만료된 안내가 남음 ($step)")
            assertTrue(offer.expiresAtMillis <= offer.offeredAtMillis + 180_000L, "안내가 180초를 넘음 ($step)")
            spot.vacatedAtMillis?.let {
                assertTrue(offer.expiresAtMillis <= it + 180_000L, "안내가 자리 만료를 넘음 ($step)")
            }
            assertEquals(offer.offerId, c.offerFor(offer.carId)?.offerId)
        }
        for (spot in c.announcedSpots()) {
            spot.vacatedAtMillis?.let { assertTrue(now < it + 180_000L, "만료된 자리가 남음 ($step)") }
        }
        val m = c.metrics()
        assertEquals(offers.size, m.activeOffers)
        assertTrue(m.misguidedOffers + m.parkedOffers + m.activeOffers <= m.totalOffers)
        assertEquals(m.activeOffers, c.records().count { it.outcome == null }, "진행 중 기록 수 ($step)")
    }

    @Test
    fun `무작위로 섞은 조작 뒤에도 배정 불변 조건이 지켜진다`() {
        for (seed in 1..20) {
            val random = Random(seed)
            val clock = FakeClock()
            val c = GuidanceCoordinator(clock)
            val zones = listOf("A", "B", "C")
            val cars = (1..9).map { "car$it" }
            var spotSeq = 0
            val liveSpots = ArrayList<String>()

            repeat(600) { i ->
                val op = random.nextInt(11)
                when (op) {
                    0, 1 -> c.registerWaiting(cars.random(random), zones.random(random), random.nextDouble(5.0, 150.0))
                    2 -> c.leave(cars.random(random))
                    3 -> {
                        val id = "spot${++spotSeq}"
                        c.announceSpot(id, zones.random(random), random.nextInt(1, 9), "owner${random.nextInt(4)}")
                        liveSpots.add(id)
                    }
                    4 -> liveSpots.randomOrNull(random)?.let { c.openGuidance(it) }
                    5 -> liveSpots.randomOrNull(random)?.let { c.markVacated(it) }
                    6 -> liveSpots.randomOrNull(random)?.let { c.cancelSpot(it) }
                    7 -> c.activeOffers().randomOrNull(random)?.let { c.reportPassedWithoutParking(it.carId, it.offerId) }
                    8 -> c.activeOffers().randomOrNull(random)?.let { c.reportParked(it.carId, it.offerId) }
                    9 -> clock.advanceSeconds(random.nextLong(1, 90))
                    else -> c.tick()
                }
                check(c, clock, "seed=$seed step=$i op=$op")
            }
            assertTrue(c.metrics().totalOffers > 0, "seed=$seed 에서 안내가 한 번도 없었다")
        }
    }
}
