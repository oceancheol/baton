package app.baton.core.match

import app.baton.core.FakeClock
import app.baton.core.departure.DepartureState.ANNOUNCING
import app.baton.core.departure.DepartureState.BOARDING
import app.baton.core.departure.DepartureState.DEPARTING
import app.baton.core.departure.DepartureState.PARKED
import app.baton.core.departure.DepartureState.VACATED
import app.baton.core.departure.DepartureStateMachine
import app.baton.core.departure.TransitionReason
import app.baton.core.departure.doorCycle
import app.baton.core.departure.parkedMachine
import app.baton.core.departure.sitAndBelt
import app.baton.core.signal.LocationQuality
import app.baton.core.signal.SignalEvent.BrakePedal
import app.baton.core.signal.SignalEvent.DistanceFromSpot
import app.baton.core.signal.SignalEvent.DriverDoor
import app.baton.core.signal.SignalEvent.DriverSeat
import app.baton.core.signal.SignalEvent.DriverSeatBelt
import app.baton.core.signal.SignalEvent.ParkingBrakeTelltale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 검수 (3)-2: 상태기계 → coordinator 통합. 한 입력이 낸 전이 목록을 순서대로 적용한다. */
class DepartureGuidanceIntegrationTest {
    private val clock = FakeClock()
    private val coordinator = GuidanceCoordinator(clock)
    private val bridge = DepartureGuidanceBridge(coordinator, ownerId = "me", zoneId = "C", row = 3)
    private val sm: DepartureStateMachine = parkedMachine(clock)

    private fun zoneC() = coordinator.zoneSummaries().first { it.zoneId == "C" }

    /** 입력 하나를 넣고 그 입력이 낸 기록을 그대로 다리에 넘긴다. */
    private fun feed(block: DepartureStateMachine.() -> List<app.baton.core.departure.DepartureLogEvent>) =
        sm.block().also(bridge::apply)

    private fun boardAndAnnounce() {
        feed { onSignal(DriverDoor(open = true)) }
        clock.advanceSeconds(3)
        feed { onSignal(DriverDoor(open = false)) }
        feed { onSignal(DriverSeat(occupied = true)) }
        feed { onSignal(DriverSeatBelt(fastened = true)) }
    }

    @Test
    fun `검수 재현 - 마지막 입력 하나에서 예고, 안내 시작, 비움이 연달아 일어나도 모두 전달된다`() {
        coordinator.registerWaiting("peer", "C", 40.0)
        feed { onSignal(DriverDoor(open = true)) }
        feed { onSignal(DriverDoor(open = false)) }
        feed { onSignal(DriverSeat(occupied = true)) }
        feed { onSignal(BrakePedal(pressed = true)) }
        feed { onSignal(DistanceFromSpot(20.0)) }
        assertEquals(BOARDING, sm.currentState)
        assertEquals(0, zoneC().upcomingSpots)

        val produced = feed { onSignal(DriverSeatBelt(fastened = true)) } // 벨트가 마지막
        assertEquals(
            listOf(BOARDING to ANNOUNCING, ANNOUNCING to DEPARTING, DEPARTING to VACATED),
            produced.filter { it.isTransition }.map { it.from to it.to },
        )
        assertTrue(produced.all { it.announcementGeneration == 1L })

        val spot = assertNotNull(coordinator.spot("me#1"), "예고 자리가 만들어졌다")
        assertTrue(spot.guidanceOpen)
        assertTrue(spot.vacated)
        val offer = assertNotNull(coordinator.offerFor("peer"))
        assertEquals("me#1", offer.announcementId)
        assertEquals(1, coordinator.metrics().vacatedAnnouncements)

        assertNotNull(coordinator.reportParked("peer", offer.offerId))
        assertEquals(1, coordinator.yieldCount("me"))
    }

    @Test
    fun `정상 흐름 - 예고는 집계만, 출차 임박에 한 대에게 안내, 비움 뒤 주차까지의 이력`() {
        coordinator.registerWaiting("near", "C", 30.0)
        coordinator.registerWaiting("far", "C", 90.0)
        boardAndAnnounce()
        assertEquals(1, zoneC().upcomingSpots)
        assertNull(coordinator.offerFor("near"), "예고만으로는 안내하지 않는다")

        clock.advanceSeconds(10)
        feed { onSignal(ParkingBrakeTelltale(on = false)) }
        val offer = assertNotNull(coordinator.offerFor("near"))
        assertNull(coordinator.offerFor("far"))
        assertEquals(3, offer.row)

        clock.advanceSeconds(5)
        feed { onSignal(DistanceFromSpot(12.0)) }
        assertEquals(VACATED, sm.currentState)
        assertEquals(15_000L, coordinator.metrics().lastLeadTimeMillis, "예고부터 이탈까지 15초")
        assertNotNull(coordinator.reportParked("near", offer.offerId))

        val m = coordinator.metrics()
        assertEquals(Triple(1, 0, 1), Triple(m.totalOffers, m.misguidedOffers, m.parkedOffers))
        assertEquals(GuidanceOutcome.PARKED, coordinator.records().single().outcome)
        assertEquals(0, zoneC().upcomingSpots)
    }

    @Test
    fun `예고 후 120초 무이동 취소 - 진행 중이던 안내도 함께 끝나고 대기 시간은 그대로다`() {
        coordinator.registerWaiting("near", "C", 30.0)
        val since = coordinator.waitingCar("near")!!.waitingSinceMillis
        boardAndAnnounce()
        clock.advanceSeconds(100)
        feed { onSignal(ParkingBrakeTelltale(on = false)) }
        val offer = assertNotNull(coordinator.offerFor("near"))

        clock.advanceSeconds(20) // 예고 시작부터 120초
        val produced = feed { tick() }
        assertEquals(TransitionReason.TIMEOUT_NO_PROGRESS, produced.single { it.isTransition }.reason)
        assertNull(coordinator.offerFor("near"))
        assertNull(coordinator.spot("me#1"))
        assertEquals(0, zoneC().upcomingSpots)
        val record = assertNotNull(coordinator.record(offer.offerId))
        assertEquals(GuidanceOutcome.ANNOUNCEMENT_CANCELLED, record.outcome)
        assertEquals(true, record.waitPreserved)
        assertEquals(since, coordinator.waitingCar("near")?.waitingSinceMillis)
        assertEquals(1, coordinator.metrics().misguidedOffers)
    }

    @Test
    fun `한 입력에서 취소와 재예고가 함께 일어나면 옛 안내는 끝나고 새 예고는 새 ID 로 생긴다`() {
        coordinator.registerWaiting("near", "C", 30.0)
        boardAndAnnounce()
        feed { onSignal(ParkingBrakeTelltale(on = false)) }
        val oldOffer = assertNotNull(coordinator.offerFor("near"))
        feed { onSignal(DriverDoor(open = true)) }

        clock.advanceSeconds(60)
        // 60초 뒤에야 문을 닫았다: 취소가 먼저 일어나고, 앉은 채 문을 여닫은 것이므로 곧바로 다시 예고한다.
        val produced = feed { onSignal(DriverDoor(open = false)) }
        assertEquals(
            listOf(DEPARTING to PARKED, PARKED to BOARDING, BOARDING to ANNOUNCING),
            produced.filter { it.isTransition }.map { it.from to it.to },
        )
        assertEquals(listOf(1L, null, 2L), produced.filter { it.isTransition }.map { it.announcementGeneration })
        assertEquals(ANNOUNCING, sm.currentState)

        assertNull(coordinator.spot("me#1"), "옛 예고는 취소됐다")
        assertEquals(GuidanceOutcome.ANNOUNCEMENT_CANCELLED, coordinator.record(oldOffer.offerId)?.outcome)
        val fresh = assertNotNull(coordinator.spot("me#2"))
        assertEquals(false, fresh.guidanceOpen, "새 예고는 아직 안내를 열지 않았다")
        assertNull(coordinator.offerFor("near"))
        assertEquals(1, zoneC().upcomingSpots)
        assertEquals("me#2", bridge.latestAnnouncementId)

        // 새 예고의 진행은 새 ID 로만 전달된다.
        feed { onSignal(BrakePedal(pressed = true)) }
        assertEquals(DEPARTING, sm.currentState)
        assertEquals("me#2", coordinator.offerFor("near")?.announcementId)
    }

    @Test
    fun `예고 안 함을 누르면 예고와 안내가 함께 취소되고, 예고 없이 떠난 자리는 만들지 않는다`() {
        coordinator.registerWaiting("near", "C", 30.0)
        boardAndAnnounce()
        feed { suppressThisParking() }
        assertEquals(PARKED, sm.currentState)
        assertEquals(0, zoneC().upcomingSpots)

        feed { onSignal(DistanceFromSpot(15.0)) }
        assertEquals(VACATED, sm.currentState)
        assertTrue(coordinator.announcedSpots().isEmpty())
        assertEquals(0, coordinator.metrics().totalOffers)
    }

    @Test
    fun `새 주차를 시작하면 진행 중이던 예고는 취소되고 이미 비운 자리는 그대로 안내된다`() {
        coordinator.registerWaiting("near", "C", 30.0)
        boardAndAnnounce()
        feed { startNewParking() }
        assertNull(coordinator.spot("me#1"))

        boardAndAnnounce() // 문 여닫힘 → 앉아 있으므로 다시 예고 (me#2)
        feed { onSignal(ParkingBrakeTelltale(on = false)) }
        feed { onSignal(DistanceFromSpot(30.0)) }
        assertEquals(VACATED, sm.currentState)
        feed { startNewParking() }
        assertNotNull(coordinator.spot("me#2"), "떠난 자리는 180초 동안 남는다")
        assertEquals("me#2", coordinator.offerFor("near")?.announcementId)
    }

    @Test
    fun `위치 품질이 낮으면 안내 해상도를 구역으로 내린다`() {
        coordinator.registerWaiting("near", "C", 30.0)
        feed { onSignal(DistanceFromSpot(0.0, LocationQuality.LOW)) }
        boardAndAnnounce()
        assertEquals(GuidanceResolution.ZONE, coordinator.spot("me#1")?.resolution)
        feed { onSignal(ParkingBrakeTelltale(on = false)) }
        val offer = assertNotNull(coordinator.offerFor("near"))
        assertEquals(GuidanceResolution.ZONE, offer.resolution)
        assertNull(offer.row)
        assertEquals("C", offer.zoneId)
    }

    @Test
    fun `예고 뒤에 위치 품질이 떨어져도 비울 때 구역 안내로 내린다`() {
        coordinator.registerWaiting("near", "C", 30.0)
        boardAndAnnounce()
        feed { onSignal(ParkingBrakeTelltale(on = false)) }
        assertEquals(3, coordinator.offerFor("near")?.row)
        feed { onSignal(DistanceFromSpot(12.0, LocationQuality.LOW)) }
        assertEquals(VACATED, sm.currentState)
        assertNull(coordinator.offerFor("near")?.row)
    }

    @Test
    fun `최소 신호를 잃어 취소되면 안내도 끝난다`() {
        coordinator.registerWaiting("near", "C", 30.0)
        boardAndAnnounce()
        feed { onSignal(ParkingBrakeTelltale(on = false)) }
        assertNotNull(coordinator.offerFor("near"))
        feed {
            onSignal(
                app.baton.core.signal.SignalEvent.AvailabilityChanged(
                    app.baton.core.signal.SignalKind.LOCATION,
                    app.baton.core.signal.SignalAvailability.UNAVAILABLE,
                ),
            )
        }
        assertEquals(PARKED, sm.currentState)
        assertNull(coordinator.offerFor("near"))
        assertTrue(coordinator.announcedSpots().isEmpty())
    }

    @Test
    fun `마지막 상태만 보던 방식이었다면 놓쳤을 전이 - 건너뛴 시간 뒤의 tick 한 번`() {
        // 예고 뒤 문을 열어 둔 채 70초가 지나 tick 한 번: 취소 하나만 생기고 자리는 사라진다.
        boardAndAnnounce()
        feed { onSignal(DriverDoor(open = true)) }
        clock.advanceSeconds(70)
        val produced = feed { tick() }
        assertEquals(listOf(TransitionReason.DOOR_REOPEN_TIMEOUT), produced.filter { it.isTransition }.map { it.reason })
        assertTrue(coordinator.announcedSpots().isEmpty())
        // 문을 닫으면(열린 채 취소됐으므로 한 번의 여닫힘) 새 예고가 새 ID 로 생긴다.
        feed { onSignal(DriverDoor(open = false)) }
        assertEquals(listOf("me#2"), coordinator.announcedSpots().map { it.announcementId })
    }

    // ---- 안내 중 위치 품질 변화 (2차 검수 (2)-2) ----------------------------

    @Test
    fun `검수 재현 - 안내 중 위치 품질이 낮아지면 상태 전이가 없어도 즉시 구역 안내로 낮춘다`() {
        coordinator.registerWaiting("near", "C", 30.0)
        boardAndAnnounce()
        feed { onSignal(ParkingBrakeTelltale(on = false)) }
        assertEquals(DEPARTING, sm.currentState)
        val before = assertNotNull(coordinator.offerFor("near"))
        assertEquals(3, before.row)
        assertEquals(GuidanceResolution.ROW, before.resolution)

        // 제자리(0m)에서 품질만 LOW: 상태 전이는 없다.
        val produced = feed { onSignal(DistanceFromSpot(0.0, LocationQuality.LOW)) }

        assertTrue(produced.none { it.isTransition })
        assertEquals(listOf(TransitionReason.LOCATION_QUALITY_CHANGED), produced.map { it.reason })
        assertEquals(DEPARTING, sm.currentState)
        val after = assertNotNull(coordinator.offerFor("near"))
        assertEquals(before.offerId, after.offerId, "같은 안내를 낮춘다(새 안내가 아니다)")
        assertNull(after.row)
        assertEquals(GuidanceResolution.ZONE, after.resolution)
        assertEquals(GuidanceResolution.ZONE, coordinator.spot("me#1")?.resolution)
    }

    @Test
    fun `정책 - 한 번 구역으로 낮춘 안내는 품질이 다시 HIGH 가 돼도 줄로 되돌리지 않는다`() {
        coordinator.registerWaiting("near", "C", 30.0)
        coordinator.registerWaiting("far", "C", 90.0)
        boardAndAnnounce()
        feed { onSignal(ParkingBrakeTelltale(on = false)) }
        feed { onSignal(DistanceFromSpot(0.0, LocationQuality.LOW)) }
        assertNull(coordinator.offerFor("near")?.row)

        val recovered = feed { onSignal(DistanceFromSpot(0.0, LocationQuality.HIGH)) }
        assertEquals(listOf(TransitionReason.LOCATION_QUALITY_CHANGED), recovered.map { it.reason })
        assertNull(coordinator.offerFor("near")?.row, "HIGH 로 돌아와도 줄 안내로 되돌리지 않는다")
        assertEquals(GuidanceResolution.ZONE, coordinator.offerFor("near")?.resolution)

        // 같은 예고가 다음 차에게 넘어가도, HIGH 인 채로 자리를 비워도 구역 안내 그대로다.
        val first = assertNotNull(coordinator.offerFor("near"))
        assertTrue(coordinator.reportPassedWithoutParking("near", first.offerId))
        assertEquals(GuidanceResolution.ZONE, coordinator.offerFor("far")?.resolution)
        feed { onSignal(DistanceFromSpot(12.0, LocationQuality.HIGH)) }
        assertEquals(VACATED, sm.currentState)
        assertNull(coordinator.offerFor("far")?.row)
    }

    @Test
    fun `예고만 한 상태에서 품질이 낮아지면 안내를 열 때부터 구역 안내다`() {
        coordinator.registerWaiting("near", "C", 30.0)
        boardAndAnnounce()
        assertEquals(ANNOUNCING, sm.currentState)
        feed { onSignal(DistanceFromSpot(0.0, LocationQuality.LOW)) }
        feed { onSignal(DistanceFromSpot(0.0, LocationQuality.HIGH)) }
        feed { onSignal(ParkingBrakeTelltale(on = false)) }
        val offer = assertNotNull(coordinator.offerFor("near"))
        assertEquals(GuidanceResolution.ZONE, offer.resolution)
        assertNull(offer.row)
    }

    @Test
    fun `예고가 없을 때의 품질 변화는 안내 쪽에 아무 일도 일으키지 않는다`() {
        coordinator.registerWaiting("near", "C", 30.0)
        val produced = feed { onSignal(DistanceFromSpot(0.0, LocationQuality.LOW)) }
        assertEquals(listOf(TransitionReason.LOCATION_QUALITY_CHANGED), produced.map { it.reason })
        assertTrue(coordinator.announcedSpots().isEmpty())
        // 품질이 돌아온 뒤 시작한 다음 예고는 줄 안내다.
        feed { onSignal(DistanceFromSpot(0.0, LocationQuality.HIGH)) }
        boardAndAnnounce()
        feed { onSignal(ParkingBrakeTelltale(on = false)) }
        assertEquals(3, coordinator.offerFor("near")?.row)
    }
}
