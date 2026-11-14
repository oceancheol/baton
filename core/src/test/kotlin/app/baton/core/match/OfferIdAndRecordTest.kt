package app.baton.core.match

import app.baton.core.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 안내 ID·예고 ID, 양보 기록 단일 저장소, 대기 시간 보존(검수 (3)-6), 안내 결과 기록과 측정값. */
class OfferIdAndRecordTest {
    private val clock = FakeClock()
    private val c = GuidanceCoordinator(clock)

    private fun openSpot(id: String, row: Int = 3, owner: String = "owner-$id") {
        c.announceSpot(id, "C", row, owner)
        c.openGuidance(id)
    }

    // ---- 안내 ID ----------------------------------------------------------

    @Test
    fun `지난 안내의 늦은 주차 보고는 새 안내 자리를 완료 처리하지 못한다`() {
        c.registerWaiting("a", "C", 30.0)
        openSpot("s1")
        val old = assertNotNull(c.offerFor("a"))
        assertTrue(c.reportPassedWithoutParking("a", old.offerId))

        openSpot("s2", row = 5)
        c.markVacated("s2")
        val current = assertNotNull(c.offerFor("a"))
        assertNotEquals(old.offerId, current.offerId)
        assertEquals("s2", current.announcementId)

        assertNull(c.reportParked("a", old.offerId), "지난 안내 ID 로는 받지 않는다")
        assertFalse(c.reportPassedWithoutParking("a", old.offerId))
        assertNotNull(c.spot("s2"))
        assertEquals(current.offerId, c.offerFor("a")?.offerId)

        val result = assertNotNull(c.reportParked("a", current.offerId))
        assertEquals(current.offerId, result.offerId)
        assertEquals("s2", result.spot.announcementId)
    }

    @Test
    fun `같은 예고 ID 로 두 번 예고해도 하나만 생기고, 같은 차의 새 예고는 진행 중이던 예고를 취소한다`() {
        c.registerWaiting("a", "C", 30.0)
        assertTrue(c.announceSpot("me#1", "C", 3, "me"))
        assertFalse(c.announceSpot("me#1", "C", 3, "me"))
        c.openGuidance("me#1")
        val first = assertNotNull(c.offerFor("a"))

        assertTrue(c.announceSpot("me#2", "C", 3, "me"))
        assertNull(c.spot("me#1"))
        assertNull(c.offerFor("a"), "새 예고는 아직 안내를 열지 않았다")
        assertEquals(GuidanceOutcome.ANNOUNCEMENT_CANCELLED, c.record(first.offerId)?.outcome)
        assertEquals(1, c.zoneSummaries().first { it.zoneId == "C" }.upcomingSpots)

        c.openGuidance("me#1") // 지난 세대의 늦은 통보
        c.markVacated("me#1")
        assertNull(c.offerFor("a"))
    }

    // ---- 양보 기록 단일 저장소 ---------------------------------------------

    @Test
    fun `자리를 내줘 얻은 양보 기록이 그 차의 다음 대기 순서에 그대로 반영된다`() {
        // giver 가 자리를 내준다.
        c.registerWaiting("taker", "C", 10.0)
        c.announceSpot("s1", "C", 3, "giver")
        c.markVacated("s1")
        assertNotNull(c.reportParked("taker", c.offerFor("taker")!!.offerId))
        assertEquals(1, c.yieldCount("giver"))

        // giver 와 조건이 같은 other 가 함께 기다린다. 기록이 있는 giver 가 먼저다.
        c.registerWaiting("other", "C", 28.0)
        c.registerWaiting("giver", "C", 28.0)
        assertEquals(1, c.rankInZone("giver"))
        assertEquals(2, c.rankInZone("other"))
        openSpot("s2")
        assertEquals("giver", c.offerForSpot("s2")?.carId)
    }

    @Test
    fun `양보 기록이 많아도 더 가까운 차를 앞지르지 못한다`() {
        c.setYieldCount("far", 9)
        c.registerWaiting("near", "C", 28.0)
        c.registerWaiting("far", "C", 42.0)
        openSpot("s1")
        assertEquals("near", c.offerForSpot("s1")?.carId)
    }

    // ---- 대기 시간 보존 ----------------------------------------------------

    @Test
    fun `예고 취소, 안내 만료, 곧바로 다른 자리 재배정에서도 대기 시작 시각이 그대로다`() {
        c.registerWaiting("a", "C", 30.0)
        val since = c.waitingCar("a")!!.waitingSinceMillis

        // 1) 예고 취소
        clock.advanceSeconds(10)
        openSpot("s1")
        val o1 = assertNotNull(c.offerFor("a"))
        clock.advanceSeconds(5)
        c.cancelSpot("s1")
        assertEquals(since, c.waitingCar("a")?.waitingSinceMillis)
        assertEquals(true, c.record(o1.offerId)?.waitPreserved)

        // 2) 안내 만료
        openSpot("s2")
        val o2 = assertNotNull(c.offerFor("a"))
        clock.advanceSeconds(180)
        assertNull(c.offerFor("a"))
        assertEquals(since, c.waitingCar("a")?.waitingSinceMillis)
        assertEquals(GuidanceOutcome.EXPIRED, c.record(o2.offerId)?.outcome)
        assertEquals(true, c.record(o2.offerId)?.waitPreserved)

        // 3) 지나치자마자 열려 있던 다른 자리로 재배정
        openSpot("s3")
        openSpot("s4", row = 7)
        val o3 = assertNotNull(c.offerFor("a"))
        assertEquals("s3", o3.announcementId)
        assertTrue(c.reportPassedWithoutParking("a", o3.offerId))
        val o4 = assertNotNull(c.offerFor("a"))
        assertEquals("s4", o4.announcementId)
        assertEquals(since, o4.waitingSinceMillis)
        assertEquals(since, c.waitingCar("a")?.waitingSinceMillis)

        // 다시 등록해도(거리 갱신) 대기 시작 시각은 유지된다.
        c.registerWaiting("a", "C", 12.0)
        assertEquals(since, c.waitingCar("a")?.waitingSinceMillis)
    }

    // ---- 결과 기록과 측정값 ------------------------------------------------

    @Test
    fun `세 측정값 - 예고부터 이탈까지 시간, 잘못 보낸 안내 수, 주차 성사 수`() {
        c.registerWaiting("a", "C", 30.0)
        c.registerWaiting("b", "C", 60.0)

        // 예고 1: 안내했지만 취소됨 → 잘못 보낸 안내
        openSpot("s1")
        clock.advanceSeconds(20)
        c.cancelSpot("s1")

        // 예고 2: 12초 뒤 이탈, 안내받은 차가 주차
        c.announceSpot("s2", "C", 4, "owner2")
        clock.advanceSeconds(4)
        c.openGuidance("s2")
        clock.advanceSeconds(8)
        c.markVacated("s2")
        val offer = assertNotNull(c.offerFor("a"))
        assertNotNull(c.reportParked("a", offer.offerId))

        // 예고 3: 20초 뒤 이탈, 안내 진행 중
        c.announceSpot("s3", "C", 5, "owner3")
        clock.advanceSeconds(20)
        c.markVacated("s3")

        val m = c.metrics()
        assertEquals(3, m.totalOffers)
        assertEquals(1, m.misguidedOffers)
        assertEquals(1, m.parkedOffers)
        assertEquals(1, m.activeOffers)
        assertEquals(2, m.vacatedAnnouncements)
        assertEquals(20_000L, m.lastLeadTimeMillis)
        assertEquals(16_000L, m.averageLeadTimeMillis)

        val outcomes = c.records().map { it.carId to it.outcome }
        assertEquals(
            listOf("a" to GuidanceOutcome.ANNOUNCEMENT_CANCELLED, "a" to GuidanceOutcome.PARKED, "b" to null),
            outcomes,
        )
    }

    @Test
    fun `결과 기록은 보관량을 넘기면 끝난 것부터 버리지만 누적 측정값은 그대로다`() {
        val small = GuidanceCoordinator(clock, MatchConfig(maxRecords = 5))
        small.registerWaiting("a", "C", 30.0)
        repeat(20) { i ->
            small.announceSpot("s$i", "C", 3, "owner$i")
            small.openGuidance("s$i")
            small.cancelSpot("s$i")
        }
        assertEquals(5, small.records().size)
        assertEquals(20, small.metrics().totalOffers)
        assertEquals(20, small.metrics().misguidedOffers)
    }

    @Test
    fun `위치 신뢰도가 낮은 예고는 구역까지만 안내한다`() {
        c.registerWaiting("a", "C", 30.0)
        c.announceSpot("s1", "C", 3, "owner", GuidanceResolution.ZONE)
        c.openGuidance("s1")
        val offer = assertNotNull(c.offerFor("a"))
        assertEquals(GuidanceResolution.ZONE, offer.resolution)
        assertNull(offer.row, "줄은 알려 주지 않는다")

        c.registerWaiting("b", "C", 30.0)
        openSpot("s2", row = 9)
        assertEquals(9, c.offerFor("b")?.row)
        c.limitToZone("s2") // 안내 도중에 신뢰도가 떨어짐
        assertNull(c.offerFor("b")?.row)
        assertEquals(GuidanceResolution.ZONE, c.offerFor("b")?.resolution)
    }

    // ---- 잘못 보낸 안내 집계 (2차 검수 (2)-4) -------------------------------

    @Test
    fun `검수 재현 - 안내 뒤 지나침, 그다음 예고 취소면 전체 1 잘못 1 이다`() {
        c.registerWaiting("a", "C", 30.0)
        openSpot("s1")
        val offer = assertNotNull(c.offerFor("a"))
        assertTrue(c.reportPassedWithoutParking("a", offer.offerId)) // 자리가 비기 전에 지나침
        assertEquals(0, c.metrics().misguidedOffers, "예고의 진위가 정해지기 전에는 세지 않는다")

        c.cancelSpot("s1")

        val m = c.metrics()
        assertEquals(1, m.totalOffers)
        assertEquals(1, m.misguidedOffers)
        assertEquals(0, m.parkedOffers)
        val record = assertNotNull(c.record(offer.offerId))
        assertEquals(GuidanceOutcome.PASSED, record.outcome, "안내가 끝난 사유는 그대로 지나침이다")
        assertTrue(record.announcementCancelled, "예고의 최종 진위는 따로 남긴다")

        c.cancelSpot("s1") // 같은 예고를 다시 취소해도 한 번만 센다
        c.tick()
        assertEquals(1, c.metrics().misguidedOffers)
    }

    @Test
    fun `취소된 예고로 보낸 안내는 지나침, 만료, 진행 중을 모두 한 번씩 센다`() {
        c.registerWaiting("a", "C", 10.0)
        c.registerWaiting("b", "C", 40.0)
        c.registerWaiting("d", "C", 80.0)
        openSpot("s1")
        val first = assertNotNull(c.offerForSpot("s1"))
        assertEquals("a", first.carId)
        assertTrue(c.reportPassedWithoutParking("a", first.offerId)) // 지나침
        val second = assertNotNull(c.offerForSpot("s1"))
        clock.advanceSeconds(180) // 180초 만료 → 다음 차
        val third = assertNotNull(c.offerForSpot("s1"))
        assertEquals(3, setOf(first.offerId, second.offerId, third.offerId).size)
        assertEquals(GuidanceOutcome.EXPIRED, c.record(second.offerId)?.outcome)

        c.cancelSpot("s1")

        assertEquals(3, c.metrics().totalOffers)
        assertEquals(3, c.metrics().misguidedOffers)
        assertEquals(GuidanceOutcome.ANNOUNCEMENT_CANCELLED, c.record(third.offerId)?.outcome)
        assertTrue(c.records().all { it.announcementCancelled })
    }

    @Test
    fun `앞 차가 실제로 나간 예고는 지나침이 있었어도 잘못 보낸 안내가 아니다`() {
        c.registerWaiting("a", "C", 10.0)
        c.registerWaiting("b", "C", 40.0)
        openSpot("s1")
        val first = assertNotNull(c.offerFor("a"))
        assertTrue(c.reportPassedWithoutParking("a", first.offerId))
        c.markVacated("s1")
        val second = assertNotNull(c.offerFor("b"))
        assertNotNull(c.reportParked("b", second.offerId))

        c.cancelSpot("s1") // 이미 끝난 예고: 아무 일도 없다
        assertEquals(2, c.metrics().totalOffers)
        assertEquals(0, c.metrics().misguidedOffers)
        assertEquals(1, c.metrics().parkedOffers)
        assertFalse(c.records().any { it.announcementCancelled })

        // 비운 뒤 아무도 못 대고 180초가 지나 사라진 자리도 잘못 보낸 안내가 아니다.
        c.registerWaiting("e", "C", 20.0)
        openSpot("s2")
        assertTrue(c.reportPassedWithoutParking("a", assertNotNull(c.offerFor("a")).offerId))
        c.markVacated("s2")
        clock.advanceSeconds(181)
        assertNull(c.spot("s2"))
        assertEquals(0, c.metrics().misguidedOffers)
    }

    @Test
    fun `잘못 보낸 안내 수는 결과 기록이 보관량을 넘어 지워져도 맞다`() {
        val small = GuidanceCoordinator(clock, MatchConfig(maxRecords = 1))
        small.registerWaiting("a", "C", 10.0)
        small.registerWaiting("b", "C", 40.0)
        small.announceSpot("s1", "C", 3, "owner")
        small.openGuidance("s1")
        val first = assertNotNull(small.offerFor("a"))
        assertTrue(small.reportPassedWithoutParking("a", first.offerId))
        val second = assertNotNull(small.offerFor("b"))
        assertTrue(small.reportPassedWithoutParking("b", second.offerId))
        assertNull(small.record(first.offerId), "첫 안내의 기록은 보관량 때문에 이미 지워졌다")

        small.cancelSpot("s1")

        assertEquals(2, small.metrics().totalOffers)
        assertEquals(2, small.metrics().misguidedOffers)
    }

    @Test
    fun `같은 차의 새 예고가 밀어낸 이전 예고도 취소로 확정돼 그동안 보낸 안내를 센다`() {
        c.registerWaiting("a", "C", 10.0)
        c.announceSpot("me#1", "C", 3, "me")
        c.openGuidance("me#1")
        assertTrue(c.reportPassedWithoutParking("a", assertNotNull(c.offerFor("a")).offerId))
        c.announceSpot("me#2", "C", 3, "me")
        assertEquals(1, c.metrics().misguidedOffers)
    }
}
