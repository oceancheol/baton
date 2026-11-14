package app.baton.core.match

import app.baton.core.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuidanceTest {
    private val clock = FakeClock()
    private val config = MatchConfig()
    private val order = GuidanceOrder(config)

    private fun car(id: String, distance: Double, waitedSeconds: Long = 0) =
        WaitingCar(id, "C", distance, clock.nowMillis() - waitedSeconds * 1000L)

    // ---- 안내 순서 --------------------------------------------------------

    @Test
    fun `거리 - 서행 기준 도착 예상 시간이 짧은 차가 먼저다`() {
        val near = car("near", 28.0)
        val far = car("far", 84.0)
        assertEquals(10.0, order.etaSeconds(28.0), 1e-9)
        assertEquals(listOf("near", "far"), order.rank(listOf(far, near), clock.nowMillis()).map { it.id })
    }

    @Test
    fun `대기 보정 - 오래 기다린 차는 조금 멀어도 먼저다`() {
        val near = car("near", 28.0) // 10초
        val waited = car("waited", 56.0, waitedSeconds = 150) // 20초 − 15초 = 5초
        assertEquals(15.0, order.waitBonusSeconds(waited, clock.nowMillis()), 1e-9)
        assertEquals("waited", order.pick(listOf(near, waited), clock.nowMillis())?.id)
    }

    @Test
    fun `대기 보정에는 상한이 있다`() {
        val forever = car("forever", 0.0, waitedSeconds = 3600)
        assertEquals(config.maxWaitBonusSeconds, order.waitBonusSeconds(forever, clock.nowMillis()), 1e-9)
    }

    @Test
    fun `양보 기록은 순위를 뒤집지 못한다 - 도착 예상에서 대기 보정을 뺀 값이 다르면 그 값이 먼저다`() {
        val near = car("near", 28.0) // 10초, 양보 0회
        val giver = car("giver", 42.0) // 15초, 양보 4회
        val yields = mapOf("giver" to 4)
        assertEquals(listOf("near", "giver"), order.rank(listOf(giver, near), clock.nowMillis(), yields).map { it.id })
    }

    @Test
    fun `양보 기록은 1초 단위로 반올림한 값이 같을 때만 비교한다`() {
        val a = car("a", 28.0) // 10.0초
        val b = car("b", 29.0) // 10.36초 → 10초
        val c = car("c", 29.5) // 10.54초 → 11초
        assertEquals(10L, order.orderKeySeconds(b, clock.nowMillis()))
        assertEquals(11L, order.orderKeySeconds(c, clock.nowMillis()))
        val yields = mapOf("b" to 2, "c" to 9)
        assertEquals(
            listOf("b", "a", "c"),
            order.rank(listOf(a, c, b), clock.nowMillis(), yields).map { it.id },
            "b 는 a 와 같은 10초라 양보 기록으로 앞서고, c 는 양보가 많아도 11초라 뒤다",
        )
    }

    @Test
    fun `값도 양보 기록도 같으면 먼저 기다린 차가 먼저다`() {
        val a = WaitingCar("a", "C", 28.0, clock.nowMillis())
        val b = WaitingCar("b", "C", 28.0, clock.nowMillis() - 1)
        assertEquals("b", order.pick(listOf(a, b), clock.nowMillis())?.id)
    }

    // ---- 한 대에게만 안내 --------------------------------------------------

    private fun coordinator() = GuidanceCoordinator(clock, config)

    @Test
    fun `예고만으로는 집계에만 잡히고 안내는 출차 임박부터 한 대에게만 간다`() {
        val c = coordinator()
        c.registerWaiting("near", "C", 30.0)
        c.registerWaiting("far", "C", 90.0)
        c.registerWaiting("other-zone", "A", 5.0)

        c.announceSpot("s1", "C", 3, "owner")
        assertNull(c.offerForSpot("s1"))
        assertEquals(0, c.guidedCarCount("s1"))
        assertEquals(1, c.zoneSummaries().first { it.zoneId == "C" }.upcomingSpots)

        c.openGuidance("s1")
        val offer = assertNotNull(c.offerForSpot("s1"))
        assertEquals("near", offer.carId)
        assertEquals(3, offer.row)
        assertEquals(clock.nowMillis() + 180_000L, offer.expiresAtMillis)
        assertEquals(1, c.guidedCarCount("s1"))
        assertNull(c.offerFor("far"))
        assertNull(c.offerFor("other-zone"), "다른 구역 차에는 안내하지 않는다")
    }

    @Test
    fun `구역별 집계 - 곧 날 자리 수와 대기 수`() {
        val c = coordinator()
        c.registerWaiting("a1", "A", 10.0)
        c.registerWaiting("a2", "A", 20.0)
        c.registerWaiting("a3", "A", 30.0)
        c.registerWaiting("b1", "B", 10.0)
        c.registerWaiting("me", "C", 10.0)
        c.announceSpot("s1", "C", 3, "owner")

        assertEquals(
            listOf(ZoneSummary("A", 0, 3), ZoneSummary("B", 0, 1), ZoneSummary("C", 1, 0)),
            c.zoneSummaries(excludeCarId = "me"),
        )
        assertEquals(1, c.zoneSummaries().first { it.zoneId == "C" }.waitingCars)
        assertEquals("C", c.recommendZone(excludeCarId = "me"))
        assertEquals(1, c.rankInZone("me"))
        assertEquals(2, c.rankInZone("a2"))
    }

    @Test
    fun `안내는 180초 동안만 유효하고 넘기면 다음 차로 넘어간다`() {
        val c = coordinator()
        c.registerWaiting("near", "C", 30.0)
        c.registerWaiting("far", "C", 90.0)
        c.announceSpot("s1", "C", 3, "owner")
        c.openGuidance("s1")

        clock.advanceSeconds(179)
        c.tick()
        assertEquals("near", c.offerForSpot("s1")?.carId)

        clock.advanceSeconds(1)
        c.tick()
        val next = assertNotNull(c.offerForSpot("s1"))
        assertEquals("far", next.carId)
        assertEquals(clock.nowMillis() + 180_000L, next.expiresAtMillis)
        assertNull(c.offerFor("near"))
        assertNotNull(c.waitingCar("near"), "만료된 차는 대기에 남는다")

        clock.advanceSeconds(180)
        c.tick()
        assertNull(c.offerForSpot("s1"), "더 넘길 차가 없으면 안내가 비어 있다")
    }

    @Test
    fun `지나쳤는데 주차하지 않으면 대기로 되돌리고 다음 예고에서 다시 순서에 든다`() {
        val c = coordinator()
        c.registerWaiting("near", "C", 30.0)
        c.registerWaiting("far", "C", 90.0)
        val nearSince = c.waitingCar("near")!!.waitingSinceMillis
        c.announceSpot("s1", "C", 3, "owner1")
        c.openGuidance("s1")
        assertEquals("near", c.offerForSpot("s1")?.carId)

        clock.advanceSeconds(20)
        assertTrue(c.reportPassedWithoutParking("near", c.offerFor("near")!!.offerId))
        assertEquals("far", c.offerForSpot("s1")?.carId, "자리는 다음 차에게 넘어간다")
        assertNull(c.offerFor("near"))
        assertEquals(nearSince, c.waitingCar("near")?.waitingSinceMillis, "기다린 시간은 그대로다")

        c.tick()
        assertNull(c.offerFor("near"), "지나친 자리로는 다시 안내하지 않는다")

        c.announceSpot("s2", "C", 5, "owner2")
        c.openGuidance("s2")
        assertEquals("near", c.offerForSpot("s2")?.carId)
    }

    @Test
    fun `주차하면 자리가 집계에서 빠지고 내준 차의 양보 기록이 오른다`() {
        val c = coordinator()
        c.setYieldCount("owner", 12)
        c.registerWaiting("near", "C", 30.0)
        c.announceSpot("s1", "C", 3, "owner")
        c.openGuidance("s1")
        c.markVacated("s1")

        val offerId = c.offerFor("near")!!.offerId
        val result = assertNotNull(c.reportParked("near", offerId))
        assertEquals(13, result.ownerYieldCount)
        assertEquals(13, c.yieldCount("owner"))
        assertEquals(ZoneSummary("C", 0, 0), c.zoneSummaries().first { it.zoneId == "C" })
        assertNull(c.reportParked("near", offerId), "안내받지 않은 차의 주차 보고는 무시한다")
    }

    @Test
    fun `예고가 취소되면 안내받던 차는 대기로 돌아간다`() {
        val c = coordinator()
        c.registerWaiting("near", "C", 30.0)
        c.announceSpot("s1", "C", 3, "owner")
        c.openGuidance("s1")
        c.cancelSpot("s1")
        assertNull(c.offerFor("near"))
        assertNotNull(c.waitingCar("near"))
        assertEquals(0, c.zoneSummaries().first { it.zoneId == "C" }.upcomingSpots)
    }

    @Test
    fun `대기 차가 나중에 등록돼도 열려 있는 안내를 받는다`() {
        val c = coordinator()
        c.announceSpot("s1", "C", 3, "owner")
        c.openGuidance("s1")
        assertNull(c.offerForSpot("s1"))
        c.registerWaiting("late", "C", 40.0)
        assertEquals("late", c.offerForSpot("s1")?.carId)
    }
}
