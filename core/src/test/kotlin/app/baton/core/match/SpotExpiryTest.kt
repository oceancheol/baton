package app.baton.core.match

import app.baton.core.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 검수 (3)-1: 자리(이탈 후 180초)와 안내(배정 후 180초)의 이중 만료. 유효성은 조회·보고 시점의 시각으로 본다. */
class SpotExpiryTest {
    private val clock = FakeClock()
    private val c = GuidanceCoordinator(clock)

    private fun zoneC() = c.zoneSummaries().first { it.zoneId == "C" }

    private fun vacatedSpotWith(vararg cars: String) {
        cars.forEachIndexed { i, id -> c.registerWaiting(id, "C", 30.0 + 30.0 * i) }
        c.announceSpot("s1", "C", 3, "owner")
        c.openGuidance("s1")
        c.markVacated("s1")
    }

    @Test
    fun `이탈 후 179,999ms 까지는 유효하고 180,000ms 부터 자리와 안내가 함께 사라진다`() {
        vacatedSpotWith("a", "b")
        val offer = assertNotNull(c.offerFor("a"))
        assertEquals(clock.nowMillis() + 180_000L, offer.expiresAtMillis)

        clock.advanceMillis(179_999)
        assertEquals(offer.offerId, c.offerFor("a")?.offerId)
        assertEquals(1, zoneC().upcomingSpots)

        clock.advanceMillis(1) // 180,000: tick 없이 조회만 한다
        assertNull(c.offerFor("a"))
        assertNull(c.offerFor("b"), "오래된 자리를 다음 차에게 다시 안내하지 않는다")
        assertNull(c.spot("s1"))
        assertEquals(0, zoneC().upcomingSpots)
        assertEquals(GuidanceOutcome.SPOT_EXPIRED, c.record(offer.offerId)?.outcome)

        clock.advanceMillis(1) // 180,001
        c.tick()
        assertNull(c.offerForSpot("s1"))
        assertEquals(1, c.metrics().totalOffers, "새 안내가 만들어지지 않았다")
    }

    @Test
    fun `검수 재현 - 이탈 181초 뒤 tick 해도 다음 차에게 새 180초를 주지 않고 집계에서도 빠진다`() {
        vacatedSpotWith("a", "b")
        clock.advanceSeconds(181)
        c.tick()
        assertNull(c.offerFor("a"))
        assertNull(c.offerFor("b"))
        assertEquals(ZoneSummary("C", 0, 2), zoneC())
        assertNotNull(c.waitingCar("a"), "차는 대기에 남는다")
    }

    @Test
    fun `받을 차가 없던 자리도 이탈 후 180초가 지나면 집계와 추천에서 빠진다`() {
        c.announceSpot("s1", "C", 3, "owner")
        c.markVacated("s1")
        assertEquals("C", c.recommendZone())
        clock.advanceMillis(179_999)
        assertEquals(1, zoneC().upcomingSpots)
        clock.advanceMillis(1)
        assertEquals(0, zoneC().upcomingSpots)
        assertEquals("A", c.recommendZone(), "예고가 없으면 구역 순서대로")
    }

    @Test
    fun `늦게 등록한 차 - 만료 전이면 남은 시간만큼만, 만료 뒤면 안내받지 못한다`() {
        c.announceSpot("s1", "C", 3, "owner")
        c.openGuidance("s1")
        c.markVacated("s1")
        val vacatedAt = clock.nowMillis()

        clock.advanceSeconds(100)
        c.registerWaiting("late", "C", 20.0)
        val offer = assertNotNull(c.offerFor("late"))
        assertEquals(vacatedAt + 180_000L, offer.expiresAtMillis, "안내 종료는 min(배정+180초, 이탈+180초)")

        clock.advanceSeconds(80)
        assertNull(c.offerFor("late"))
        c.registerWaiting("later", "C", 10.0)
        assertNull(c.offerFor("later"))
        assertNull(c.spot("s1"))
    }

    @Test
    fun `중복 비움 통보는 만료를 늘리지 않는다`() {
        vacatedSpotWith("a")
        val vacatedAt = clock.nowMillis()
        clock.advanceSeconds(100)
        c.markVacated("s1")
        c.markVacated("s1")
        assertEquals(vacatedAt, c.spot("s1")?.vacatedAtMillis)
        assertEquals(vacatedAt + 180_000L, c.offerFor("a")?.expiresAtMillis)
        assertEquals(1, c.metrics().vacatedAnnouncements, "이탈 시간도 한 번만 잰다")

        clock.advanceMillis(79_999)
        assertNotNull(c.offerFor("a"))
        clock.advanceMillis(1)
        assertNull(c.offerFor("a"))
        assertNull(c.spot("s1"))
    }

    @Test
    fun `tick 전에 온 주차 보고도 만료된 안내면 받지 않는다`() {
        c.setYieldCount("owner", 4)
        vacatedSpotWith("a")
        val offer = assertNotNull(c.offerFor("a"))
        clock.advanceSeconds(181) // tick 을 부르지 않는다
        assertNull(c.reportParked("a", offer.offerId))
        assertEquals(4, c.yieldCount("owner"), "양보 기록이 오르지 않는다")
        assertNotNull(c.waitingCar("a"))
        assertEquals(0, c.metrics().parkedOffers)
    }

    @Test
    fun `안내만 만료되고 자리는 남았으면 다음 차로 넘기되 자리 만료를 넘지 않는다`() {
        c.registerWaiting("a", "C", 30.0)
        c.registerWaiting("b", "C", 60.0)
        c.announceSpot("s1", "C", 3, "owner")
        c.openGuidance("s1") // a 에게 배정 (t0)
        val t0 = clock.nowMillis()
        clock.advanceSeconds(170)
        c.markVacated("s1") // 이탈 t0+170, 자리 만료 t0+350
        assertEquals(t0 + 180_000L, c.offerFor("a")?.expiresAtMillis, "배정 후 180초가 더 이르다")

        clock.advanceSeconds(10) // t0+180
        val next = assertNotNull(c.offerFor("b"))
        assertEquals(t0 + 350_000L, next.expiresAtMillis, "새 안내는 180초가 아니라 자리 만료까지만")
        assertEquals(GuidanceOutcome.EXPIRED, c.records().first().outcome)

        clock.advanceSeconds(170) // t0+350
        assertNull(c.offerFor("b"))
        assertEquals(GuidanceOutcome.SPOT_EXPIRED, c.record(next.offerId)?.outcome)
    }

    @Test
    fun `자리가 비기 전에는 주차 보고를 받지 않는다`() {
        c.registerWaiting("a", "C", 30.0)
        c.announceSpot("s1", "C", 3, "owner")
        c.openGuidance("s1")
        val offer = assertNotNull(c.offerFor("a"))
        assertNull(c.reportParked("a", offer.offerId))
        c.markVacated("s1")
        assertNotNull(c.reportParked("a", offer.offerId))
    }
}
