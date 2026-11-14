package app.baton.core.match

import app.baton.core.Clock

/**
 * 한 주차장의 예고 자리와 대기 차를 잇는다 (PRD F2). 서버 역할의 순수 Kotlin 구현.
 *
 * 불변 조건
 * - 차 한 대와 예고 하나에는 유효한 안내가 하나뿐이다.
 * - 안내는 min(배정 시각 + 180초, 자리 이탈 시각 + 180초)까지만 유효하다.
 * - 앞 차가 떠난 뒤 180초가 지난 자리는 안내·집계·추천에서 뺀다. 중복 비움 통보는 이 시각을 늘리지 않는다.
 * - 예고가 취소되면 안내도 바로 끝난다.
 * - 유효성은 타이머가 아니라 **호출 시점의 시각**으로 판단한다. 모든 공개 함수는 먼저 만료를 걸러 낸 뒤 일한다
 *   ([tick]은 그 청소만 하는 함수다).
 * - 주차·지나침 보고는 그 차의 현재 유효한 안내 ID 와 일치할 때만 받는다.
 * - 실패 복귀(지나침·만료·취소) 때 대기 시작 시각을 그대로 둔다.
 * - 양보 기록은 이 클래스의 기록 하나만 쓴다(대기 등록·순서 계산·화면 표시 모두).
 * - '잘못 보낸 안내'는 안내가 어떻게 끝났는지가 아니라 **그 예고가 결국 취소됐는지**로 센다. 예고별로 보낸 안내 수를
 *   따로 세어 두었다가, 예고가 취소로 확정될 때 그 수(이미 지나침·만료로 끝난 안내 포함)를 한 번만 더한다.
 *   누적 지표는 보관량이 제한된 결과 기록([records])을 뒤지지 않고 전용 카운터로만 계산한다.
 */
class GuidanceCoordinator(
    private val clock: Clock,
    private val config: MatchConfig = MatchConfig(),
) {
    private val order = GuidanceOrder(config)
    private val waiting = LinkedHashMap<String, WaitingCar>()
    private val spots = LinkedHashMap<String, AnnouncedSpot>() // 예고 ID → 자리
    private val offers = LinkedHashMap<String, GuidanceOffer>() // 예고 ID → 안내
    private val excluded = HashMap<String, MutableSet<String>>() // 예고 ID → 이 자리 안내에서 빠진 차
    private val yieldCounts = HashMap<String, Int>()

    // 측정
    private val records = LinkedHashMap<String, GuidanceRecord>() // 안내 ID → 결과
    private var offerSeq = 0L
    private var totalOffers = 0
    private var misguidedOffers = 0
    private val offersSentBySpot = HashMap<String, Int>() // 살아 있는 예고 ID → 그 예고로 지금까지 보낸 안내 수
    private var parkedOffers = 0
    private var leadTimeCount = 0
    private var leadTimeSumMillis = 0L
    private var lastLeadTimeMillis: Long? = null

    // ---- 대기 차 ---------------------------------------------------------

    /**
     * 대기 등록. 이미 대기 중이면 대기 시작 시각은 그대로 두고 구역·거리만 바꾼다.
     * 구역을 바꾸면 이전 구역에서 받은 안내는 끝난다(다른 구역 자리로 안내가 남지 않게).
     */
    @Synchronized
    fun registerWaiting(carId: String, zoneId: String, distanceMeters: Double) {
        val now = sweep()
        val previous = waiting[carId]
        if (previous != null && previous.zoneId != zoneId) {
            offers.values.filter { it.carId == carId }.forEach { endOffer(it, GuidanceOutcome.CAR_LEFT, now) }
        }
        val since = previous?.waitingSinceMillis ?: now
        waiting[carId] = WaitingCar(carId, zoneId, distanceMeters, since)
        assign(now)
    }

    @Synchronized
    fun updateDistance(carId: String, distanceMeters: Double) {
        val car = waiting[carId] ?: return
        waiting[carId] = car.copy(distanceMeters = distanceMeters)
    }

    /** 대기를 그만둔다. 받은 안내가 있으면 다음 차로 넘어간다. */
    @Synchronized
    fun leave(carId: String) {
        val now = sweep()
        offers.values.filter { it.carId == carId }.forEach { endOffer(it, GuidanceOutcome.CAR_LEFT, now) }
        waiting.remove(carId)
        assign(now)
    }

    // ---- 예고 자리 -------------------------------------------------------

    /**
     * 예고 시작(ANNOUNCING). 구역 집계에만 잡히고 아직 아무에게도 안내하지 않는다.
     * 같은 차의 아직 비우지 않은 이전 예고가 남아 있으면 그것부터 취소한다(차 한 대에 진행 중인 예고는 하나).
     * @return 새로 만들었으면 true, 같은 ID 가 이미 있으면 false.
     */
    @Synchronized
    fun announceSpot(
        announcementId: String,
        zoneId: String,
        row: Int,
        ownerId: String,
        resolution: GuidanceResolution = GuidanceResolution.ROW,
    ): Boolean {
        val now = sweep()
        if (spots.containsKey(announcementId)) return false
        spots.values.filter { it.ownerId == ownerId && !it.vacated }.forEach { removeSpot(it, cancelled = true, now) }
        spots[announcementId] = AnnouncedSpot(announcementId, zoneId, row, ownerId, now, resolution)
        assign(now)
        return true
    }

    /** 안내 시작(DEPARTING). 순서가 가장 앞선 한 대에게 안내한다. */
    @Synchronized
    fun openGuidance(announcementId: String) {
        val now = sweep()
        val spot = spots[announcementId] ?: return
        if (!spot.guidanceOpen) spots[announcementId] = spot.copy(guidanceOpenedAtMillis = now)
        assign(now)
    }

    /**
     * 자리가 비었다(VACATED). 안내는 그대로 이어지되 이 시각 + 180초를 넘지 못한다.
     * 처음 통보만 시각으로 남긴다. 다시 통보해도 만료는 늘어나지 않는다.
     */
    @Synchronized
    fun markVacated(announcementId: String) {
        val now = sweep()
        val spot = spots[announcementId] ?: return
        if (!spot.vacated) {
            spots[announcementId] = spot.copy(
                guidanceOpenedAtMillis = spot.guidanceOpenedAtMillis ?: now,
                vacatedAtMillis = now,
            )
            val spotExpiry = now + config.spotValidAfterVacatedMillis
            offers[announcementId]?.let {
                if (it.expiresAtMillis > spotExpiry) offers[announcementId] = it.copy(expiresAtMillis = spotExpiry)
            }
            val lead = now - spot.announcedAtMillis
            leadTimeCount += 1
            leadTimeSumMillis += lead
            lastLeadTimeMillis = lead
        }
        assign(now)
    }

    /**
     * 자리 위치의 신뢰도가 낮다: 이 예고는 구역까지만 안내한다(진행 중인 안내에서도 줄을 지운다).
     * 되돌리는 함수는 일부러 두지 않는다: 한 번 구역으로 낮춘 예고는 끝날 때까지 구역 안내다.
     */
    @Synchronized
    fun limitToZone(announcementId: String) {
        sweep()
        val spot = spots[announcementId] ?: return
        spots[announcementId] = spot.copy(resolution = GuidanceResolution.ZONE)
        offers[announcementId]?.let {
            offers[announcementId] = it.copy(row = null, resolution = GuidanceResolution.ZONE)
        }
    }

    /** 예고 취소. 안내도 바로 끝나고, 안내받던 차는 대기 시작 시각 그대로 대기로 돌아간다. */
    @Synchronized
    fun cancelSpot(announcementId: String) {
        val now = sweep()
        spots[announcementId]?.let { removeSpot(it, cancelled = true, now) }
        assign(now)
    }

    // ---- 안내받은 차의 결과 ----------------------------------------------

    /**
     * 안내받은 자리에 주차했다. 자리를 내준 차의 양보 기록이 1 오른다.
     * [offerId]가 이 차의 현재 유효한 안내와 다르거나(지난 안내의 늦은 보고), 안내가 만료됐거나,
     * 자리가 아직 비지 않았으면 받지 않고 null 을 돌려준다.
     */
    @Synchronized
    fun reportParked(carId: String, offerId: String): ParkResult? {
        val now = sweep()
        val offer = currentOffer(carId, offerId) ?: return null
        val spot = spots[offer.announcementId] ?: return null
        // 비우기 전(비운 시각보다 이른 시각의 보고 포함)에는 도착을 받지 않는다.
        val vacatedAt = spot.vacatedAtMillis ?: return null
        if (now < vacatedAt) return null
        endOffer(offer, GuidanceOutcome.PARKED, now)
        forgetSpot(spot.announcementId)
        waiting.remove(carId)
        val count = (yieldCounts[spot.ownerId] ?: 0) + 1
        yieldCounts[spot.ownerId] = count
        return ParkResult(carId, offer.offerId, spot, count)
    }

    /**
     * 자리 위치를 지나쳤는데 주차하지 않았다. 대기 순서로 되돌리고(대기 시작 시각 유지) 다음 차에게 안내한다.
     * @return 현재 유효한 안내와 일치해 받아들였으면 true.
     */
    @Synchronized
    fun reportPassedWithoutParking(carId: String, offerId: String): Boolean {
        val now = sweep()
        val offer = currentOffer(carId, offerId) ?: return false
        endOffer(offer, GuidanceOutcome.PASSED, now)
        assign(now)
        return true
    }

    /** 만료된 안내·자리를 걷어 내고 다음 차로 넘긴다. 다른 함수도 같은 일을 먼저 하므로 이것은 청소용이다. */
    @Synchronized
    fun tick() {
        assign(sweep())
    }

    // ---- 조회 (모두 지금 시각으로 유효성을 본다) ---------------------------

    @Synchronized
    fun offerFor(carId: String): GuidanceOffer? {
        assign(sweep())
        return offers.values.firstOrNull { it.carId == carId }
    }

    @Synchronized
    fun offerForSpot(announcementId: String): GuidanceOffer? {
        assign(sweep())
        return offers[announcementId]
    }

    @Synchronized
    fun activeOffers(): List<GuidanceOffer> {
        assign(sweep())
        return offers.values.toList()
    }

    @Synchronized
    fun spot(announcementId: String): AnnouncedSpot? {
        sweep()
        return spots[announcementId]
    }

    @Synchronized
    fun announcedSpots(): List<AnnouncedSpot> {
        sweep()
        return spots.values.toList()
    }

    @Synchronized
    fun waitingCar(carId: String): WaitingCar? = waiting[carId]

    @Synchronized
    fun waitingCars(): List<WaitingCar> = waiting.values.toList()

    /** 이 예고 자리로 안내받는 차 수 (0 또는 1). */
    @Synchronized
    fun guidedCarCount(announcementId: String): Int = if (offerForSpot(announcementId) != null) 1 else 0

    @Synchronized
    fun yieldCount(carId: String): Int = yieldCounts[carId] ?: 0

    @Synchronized
    fun setYieldCount(carId: String, count: Int) {
        yieldCounts[carId] = count
    }

    /** 구역별 "출차 예고 N · 대기 N대". [excludeCarId]는 대기 수에서 뺀다(내 차). 만료된 자리는 세지 않는다. */
    @Synchronized
    fun zoneSummaries(excludeCarId: String? = null): List<ZoneSummary> {
        sweep()
        val zoneIds = (config.zones + spots.values.map { it.zoneId } + waiting.values.map { it.zoneId }).distinct()
        return zoneIds.map { zone ->
            ZoneSummary(
                zoneId = zone,
                upcomingSpots = spots.values.count { it.zoneId == zone },
                waitingCars = waiting.values.count { it.zoneId == zone && it.id != excludeCarId },
            )
        }
    }

    /** 구역 안에서 내 차례(1부터). 대기 중이 아니면 null. */
    @Synchronized
    fun rankInZone(carId: String): Int? {
        val me = waiting[carId] ?: return null
        val ranked = order.rank(waiting.values.filter { it.zoneId == me.zoneId }, clock.nowMillis(), yieldCounts)
        return ranked.indexOfFirst { it.id == carId } + 1
    }

    /** 자동 추천 구역: 출차 예고가 많은 곳, 같으면 대기가 적은 곳. */
    @Synchronized
    fun recommendZone(excludeCarId: String? = null): String? =
        zoneSummaries(excludeCarId)
            .sortedWith(compareByDescending<ZoneSummary> { it.upcomingSpots }.thenBy { it.waitingCars })
            .firstOrNull()?.zoneId

    // ---- 측정 ------------------------------------------------------------

    /** 안내 단위 결과 기록(오래된 순). 진행 중인 안내는 outcome 이 null 이다. */
    @Synchronized
    fun records(): List<GuidanceRecord> {
        sweep()
        return records.values.toList()
    }

    @Synchronized
    fun record(offerId: String): GuidanceRecord? {
        sweep()
        return records[offerId]
    }

    @Synchronized
    fun metrics(): GuidanceMetrics {
        sweep()
        return GuidanceMetrics(
            totalOffers = totalOffers,
            misguidedOffers = misguidedOffers,
            parkedOffers = parkedOffers,
            activeOffers = offers.size,
            vacatedAnnouncements = leadTimeCount,
            lastLeadTimeMillis = lastLeadTimeMillis,
            averageLeadTimeMillis = if (leadTimeCount == 0) null else leadTimeSumMillis / leadTimeCount,
        )
    }

    // ---- 내부 ------------------------------------------------------------

    private fun currentOffer(carId: String, offerId: String): GuidanceOffer? =
        offers.values.firstOrNull { it.carId == carId }?.takeIf { it.offerId == offerId }

    /** 지금 시각 기준으로 만료된 자리와 안내를 걷어 낸다. 지금 시각을 돌려준다. */
    private fun sweep(): Long {
        val now = clock.nowMillis()
        spots.values
            .filter { spot -> spot.vacatedAtMillis?.let { now >= it + config.spotValidAfterVacatedMillis } == true }
            .forEach { removeSpot(it, cancelled = false, now) }
        offers.values
            .filter { now >= it.expiresAtMillis }
            .forEach { endOffer(it, GuidanceOutcome.EXPIRED, now) }
        return now
    }

    private fun removeSpot(spot: AnnouncedSpot, cancelled: Boolean, now: Long) {
        offers[spot.announcementId]?.let {
            endOffer(it, if (cancelled) GuidanceOutcome.ANNOUNCEMENT_CANCELLED else GuidanceOutcome.SPOT_EXPIRED, now)
        }
        if (cancelled) {
            // 예고가 취소로 확정됐다: 이 예고로 보낸 안내는 지나침·만료로 먼저 끝난 것까지 모두 잘못 보낸 안내다.
            // 자리를 여기서 지우므로 한 예고는 한 번만 센다.
            misguidedOffers += offersSentBySpot[spot.announcementId] ?: 0
            // 아직 보관 중인 결과 기록에는 표시만 남긴다(지표 계산에는 쓰지 않는다).
            records.values
                .filter { it.announcementId == spot.announcementId }
                .forEach { records[it.offerId] = it.copy(announcementCancelled = true) }
        }
        forgetSpot(spot.announcementId)
    }

    private fun forgetSpot(announcementId: String) {
        spots.remove(announcementId)
        excluded.remove(announcementId)
        offersSentBySpot.remove(announcementId)
    }

    private fun endOffer(offer: GuidanceOffer, outcome: GuidanceOutcome, now: Long) {
        offers.remove(offer.announcementId)
        if (outcome == GuidanceOutcome.PASSED || outcome == GuidanceOutcome.EXPIRED) {
            // 같은 자리로 같은 차에게 다시 안내하지 않는다.
            excluded.getOrPut(offer.announcementId) { HashSet() }.add(offer.carId)
        }
        val preserved = when (outcome) {
            GuidanceOutcome.PARKED, GuidanceOutcome.CAR_LEFT -> null
            else -> waiting[offer.carId]?.waitingSinceMillis == offer.waitingSinceMillis
        }
        records[offer.offerId]?.let {
            records[offer.offerId] = it.copy(endedAtMillis = now, outcome = outcome, waitPreserved = preserved)
        }
        // 잘못 보낸 안내는 여기서 세지 않는다: 예고가 취소로 확정될 때 removeSpot 이 예고 단위로 센다.
        if (outcome == GuidanceOutcome.PARKED) parkedOffers += 1
    }

    private fun assign(now: Long) {
        for (spot in spots.values.sortedBy { it.announcedAtMillis }) {
            if (!spot.guidanceOpen || offers.containsKey(spot.announcementId)) continue
            val busy = offers.values.map { it.carId }.toSet()
            val skip = excluded[spot.announcementId].orEmpty()
            val candidates = waiting.values.filter {
                it.zoneId == spot.zoneId && it.id !in busy && it.id !in skip && it.id != spot.ownerId
            }
            val car = order.pick(candidates, now, yieldCounts) ?: continue
            val spotExpiry = spot.vacatedAtMillis?.plus(config.spotValidAfterVacatedMillis) ?: Long.MAX_VALUE
            val zoneOnly = spot.resolution == GuidanceResolution.ZONE
            val offer = GuidanceOffer(
                offerId = "offer-${++offerSeq}",
                announcementId = spot.announcementId,
                zoneId = spot.zoneId,
                row = if (zoneOnly) null else spot.row,
                resolution = spot.resolution,
                carId = car.id,
                offeredAtMillis = now,
                expiresAtMillis = minOf(now + config.offerValidMillis, spotExpiry),
                waitingSinceMillis = car.waitingSinceMillis,
            )
            offers[spot.announcementId] = offer
            totalOffers += 1
            offersSentBySpot.merge(spot.announcementId, 1, Int::plus)
            records[offer.offerId] = GuidanceRecord(
                offerId = offer.offerId,
                announcementId = spot.announcementId,
                ownerId = spot.ownerId,
                carId = car.id,
                zoneId = spot.zoneId,
                row = spot.row,
                offeredAtMillis = now,
            )
            trimRecords()
        }
    }

    private fun trimRecords() {
        while (records.size > config.maxRecords) {
            val oldestEnded = records.values.firstOrNull { it.outcome != null } ?: return
            records.remove(oldestEnded.offerId)
        }
    }
}
