package app.baton.core.match

import app.baton.core.SteppedClock

/**
 * 상대 차와 차의 이동을 흉내 낸다. 실제 서버·통신 없이 같은 프로세스 안에서 [GuidanceCoordinator]에 차를 넣고 움직인다.
 *
 * - 대기 차: 안내받으면 서행으로 다가오고, 자리가 비어 있으면 도착해서 주차한다(안내 ID 를 붙여 보고).
 * - 출차하는 상대 차: 예고 → 출차 임박 → 비움을 정해진 시각에 한다.
 *
 * 시간 진행: 지난 구간을 [Script.stepMillis] 간격의 눈금으로 나눠 **예정 시각 순서대로** 처리한다.
 * 눈금 구간마다 이동·도착 → 그 구간에 예정된 사건(출차 임박·비움, 정확히 그 예정 시각으로) → 만료 넘김 순서로 한다.
 * 그래서 `tick()`을 30초 뒤에 한 번 부르든 1초마다 30번 부르든 결과(안내·시각·거리·주차)가 같다.
 *
 * 지난 구간을 다시 밟을 때 **소급하지 않는다**: 차는 안내가 발급된(그리고 그 자리의 안내가 열린) 시각 이후의 눈금
 * 구간에서만 움직이고, 자리가 빈 시각 이후의 구간에서만 도착·주차한다. 그래서 진행하지 않은 30초 뒤에 막 생긴 안내가
 * 그 30초를 이미 달려온 것처럼 처리되지 않는다.
 *
 * 외부 입력(예고·안내 시작·비움·취소·지나침 등)과의 순서: 입력을 적용하기 **전에** 그 시각까지 `tick()`으로 세계를
 * 먼저 진행해야, 입력 사이에 `tick()`을 몇 번 불렀든 결과가 같다. 이 클래스의 입력 함수들은 스스로 먼저 진행하고,
 * [GuidanceCoordinator]에 직접 넣는 입력은 부르는 쪽(ViewModel)이 먼저 `tick()`을 부른다.
 * [clock]은 [GuidanceCoordinator]에 준 것과 같은 [SteppedClock]이어야 한다.
 */
class MockPeerService(
    private val clock: SteppedClock,
    val coordinator: GuidanceCoordinator,
    private val config: MatchConfig = MatchConfig(),
    private val script: Script = Script(),
) {
    data class Script(
        /** 상대 차가 예고를 시작한 뒤 출차 임박이 되기까지. */
        val peerDepartAfterMillis: Long = 6_000L,
        /** 출차 임박 뒤 자리를 비우기까지. */
        val peerVacateAfterMillis: Long = 8_000L,
        val peerSpotZone: String = "C",
        val peerSpotRow: Int = 3,
        /** 이동·도착을 계산하는 눈금 간격. */
        val stepMillis: Long = 100L,
    )

    private class PeerDeparture(
        val announcementId: String,
        val announcedAt: Long,
        var departedAt: Long? = null,
        var vacatedAt: Long? = null,
    )

    private val distances = LinkedHashMap<String, Double>() // 움직임을 흉내 내는 차 → 남은 거리
    private val departures = ArrayList<PeerDeparture>()
    private var simulatedUntil = clock.nowMillis()
    private var departureSeq = 0
    private var peerSeq = 0

    /** 가장 최근에 시작한 상대 차 예고의 ID. */
    val peerSpotId: String?
        @Synchronized get() = departures.lastOrNull()?.announcementId

    // ---- 차 넣기 ---------------------------------------------------------

    /** 기본 대기 차: A구역 3대, B구역 2대, ([includeMyZone]이면) C구역 1대. */
    @Synchronized
    fun seedWaitingPeers(includeMyZone: Boolean = true) {
        addPeer("peer-a1", "A", 120.0, 1)
        addPeer("peer-a2", "A", 150.0, 0)
        addPeer("peer-a3", "A", 180.0, 4)
        addPeer("peer-b1", "B", 90.0, 2)
        addPeer("peer-b2", "B", 140.0, 0)
        if (includeMyZone) addPeer("peer-c1", "C", 60.0, 3)
    }

    /** 내가 들어오는 차일 때: A·B구역에는 대기 차만, C구역에는 예고 중인 상대 차가 있다. */
    @Synchronized
    fun seedDepartingPeer() {
        seedWaitingPeers(includeMyZone = false)
        coordinator.setYieldCount(PEER_OWNER_ID, 5)
        startPeerDeparture()
    }

    /** 상대 차 한 대가 예고를 시작한다. 정해진 시간 뒤 출차 임박 → 비움으로 간다. @return 예고 ID */
    @Synchronized
    fun startPeerDeparture(zone: String = script.peerSpotZone, row: Int = script.peerSpotRow): String {
        tick() // 입력보다 먼저 지금까지의 세계를 진행한다
        departureSeq += 1
        val id = "$PEER_OWNER_ID#$departureSeq"
        // 상대 차마다 다른 차다(같은 차의 진행 중인 예고는 하나뿐이므로 소유자를 나눈다).
        val owner = if (departureSeq == 1) PEER_OWNER_ID else "$PEER_OWNER_ID-$departureSeq"
        coordinator.announceSpot(id, zone, row, owner)
        departures.add(PeerDeparture(id, clock.nowMillis()))
        return id
    }

    /** 움직임을 흉내 낼 차를 대기에 넣는다(시연에서는 내 차도 이렇게 넣는다: 모의 위치). */
    @Synchronized
    fun trackCar(carId: String, zone: String, distanceMeters: Double) {
        tick() // 입력보다 먼저 지금까지의 세계를 진행한다
        distances[carId] = distanceMeters
        coordinator.registerWaiting(carId, zone, distanceMeters)
    }

    /** 흉내를 그만두고 대기에서도 뺀다. */
    @Synchronized
    fun untrack(carId: String) {
        tick() // 입력보다 먼저 지금까지의 세계를 진행한다
        distances.remove(carId)
        coordinator.leave(carId)
    }

    /** 다시 진입 지점에서 기다리게 한다(지나침·만료 뒤). 대기 시작 시각은 건드리지 않는다. */
    @Synchronized
    fun resetDistance(carId: String, distanceMeters: Double) {
        if (!distances.containsKey(carId)) return
        tick() // 입력보다 먼저 지금까지의 세계를 진행한다
        distances[carId] = distanceMeters
        coordinator.updateDistance(carId, distanceMeters)
    }

    @Synchronized
    fun distanceOf(carId: String): Double? = distances[carId]

    @Synchronized
    fun trackedCarIds(): List<String> = distances.keys.toList()

    /**
     * [zone]에 [exceptCarId] 말고 **아직 안내받지 않은** 모의 대기 차가 한 대는 있게 한다(다음 예고가 안내할 차).
     *
     * 이미 안내받는 차는 대기 차로 치지 않는다. 새로 넣은 차가 그 구역의 열려 있는 자리(예: 비운 뒤 180초 동안
     * 유효한 옛 자리)로 곧바로 안내받으면 다시 확인해 한 대 더 넣는다. 넣는 횟수는 그 구역의 안내가 열린 자리 수 + 1
     * 까지다(열린 자리마다 한 대씩 가져가도 한 대가 남는 수). 배정 순서와 자리 유효 시간은 건드리지 않는다.
     *
     * @return 넣었으면 처음 넣은 차의 id, 넣지 않았으면 null
     */
    @Synchronized
    fun ensureWaitingPeer(zone: String, exceptCarId: String? = null, distanceMeters: Double = 60.0): String? {
        tick() // 입력보다 먼저 지금까지의 세계를 진행한다
        val maxAdds = coordinator.announcedSpots().count { it.zoneId == zone && it.guidanceOpen } + 1
        var firstAdded: String? = null
        repeat(maxAdds) {
            if (hasUnguidedPeer(zone, exceptCarId)) return firstAdded
            val id = "peer-${zone.lowercase()}n${++peerSeq}"
            addPeer(id, zone, distanceMeters, 0) // 등록과 동시에 열린 자리로 배정될 수 있다
            if (firstAdded == null) firstAdded = id
        }
        return firstAdded
    }

    /** [zone]에 [exceptCarId] 말고, 흉내 중이면서 지금 유효한 안내가 없는 대기 차가 있는지. */
    private fun hasUnguidedPeer(zone: String, exceptCarId: String?): Boolean =
        coordinator.waitingCars().any {
            it.zoneId == zone && it.id != exceptCarId && distances.containsKey(it.id) &&
                coordinator.offerFor(it.id) == null
        }

    /** 아직 안내·집계에 남아 있는 상대 차 예고가 있는지. */
    @Synchronized
    fun hasLiveDeparture(): Boolean = departures.any { coordinator.spot(it.announcementId) != null }

    // ---- 시연용 당기기 ---------------------------------------------------

    /** 아직 출차 임박이 아닌 상대 차의 출차 임박을 지금 당긴다. */
    @Synchronized
    fun peerStartsDepartingNow() {
        tick() // 입력보다 먼저 지금까지의 세계를 진행한다
        val now = clock.nowMillis()
        departures.filter { it.departedAt == null }.forEach { depart(it, now) }
    }

    /** 상대 차가 지금 자리를 비운다. [announcementId]가 null 이면 아직 비우지 않은 상대 차 모두. */
    @Synchronized
    fun peerVacatesNow(announcementId: String? = null) {
        tick() // 입력보다 먼저 지금까지의 세계를 진행한다
        val now = clock.nowMillis()
        departures
            .filter { it.vacatedAt == null && (announcementId == null || it.announcementId == announcementId) }
            .forEach {
                if (it.departedAt == null) depart(it, now)
                vacate(it, now)
            }
    }

    // ---- 시간 진행 -------------------------------------------------------

    @Synchronized
    fun tick() {
        val now = clock.nowMillis()
        while (simulatedUntil + script.stepMillis <= now) {
            val stepEnd = simulatedUntil + script.stepMillis
            // 1) 이 눈금 구간 동안의 이동(구간이 시작될 때 이미 안내받은 차만 움직인다)
            val stepStart = simulatedUntil
            clock.at(stepEnd) { moveCars(stepStart, script.stepMillis / 1000.0) }
            // 2) 구간 안에 예정된 출차 임박·비움을 그 시각으로
            runScriptedEvents(upTo = stepEnd)
            // 3) 눈금 시각의 만료 넘김
            clock.at(stepEnd) { coordinator.tick() }
            simulatedUntil = stepEnd
        }
        // 끝나지 않은 눈금 구간의 사건은 그 구간이 끝날 때 처리한다(부르는 간격과 무관하게 순서가 같도록).
    }

    /** 예정 시각이 [upTo] 이하인 출차 임박·비움을 이른 것부터 그 시각으로 처리한다. */
    private fun runScriptedEvents(upTo: Long) {
        while (true) {
            val next = departures
                .mapNotNull { d -> nextEventTime(d)?.let { it to d } }
                .filter { it.first <= upTo }
                .minByOrNull { it.first } ?: return
            val (time, departure) = next
            if (departure.departedAt == null) depart(departure, time) else vacate(departure, time)
        }
    }

    private fun nextEventTime(d: PeerDeparture): Long? {
        val departedAt = d.departedAt
        return when {
            d.vacatedAt != null -> null
            departedAt == null -> d.announcedAt + script.peerDepartAfterMillis
            else -> departedAt + script.peerVacateAfterMillis
        }
    }

    private fun depart(d: PeerDeparture, at: Long) {
        d.departedAt = at
        clock.at(at) { coordinator.openGuidance(d.announcementId) }
    }

    private fun vacate(d: PeerDeparture, at: Long) {
        d.vacatedAt = at
        clock.at(at) { coordinator.markVacated(d.announcementId) }
    }

    /**
     * 안내받은 차는 서행으로 다가오고, 자리가 비어 있으면 도착해서 주차한다. 비기 전에는 근처에서 기다린다.
     *
     * [stepStart]는 이 눈금 구간이 시작된 시각이다. 구간이 시작될 때 이미 유효했던 것만 쓴다:
     * - 안내 발급 시각(과 그 자리의 안내 시작 시각)이 구간 시작보다 늦으면 이 구간에서는 움직이지 않는다.
     * - 자리가 빈 시각이 구간 시작보다 늦으면 이 구간에서는 아직 안 빈 것으로 본다(근처 대기, 도착 처리 없음).
     */
    private fun moveCars(stepStart: Long, elapsedSeconds: Double) {
        for (id in distances.keys.toList()) {
            val offer = coordinator.offerFor(id) ?: continue
            val spot = coordinator.spot(offer.announcementId) ?: continue
            val guidedFrom = maxOf(offer.offeredAtMillis, spot.guidanceOpenedAtMillis ?: Long.MAX_VALUE)
            if (guidedFrom > stepStart) continue
            val vacated = spot.vacatedAtMillis?.let { it <= stepStart } == true
            val holdAt = if (vacated) 0.0 else HOLD_DISTANCE_METERS
            val current = distances.getValue(id)
            val moved = (current - elapsedSeconds * config.slowSpeedMps).coerceAtLeast(minOf(holdAt, current))
            // 부동소수점 찌꺼기 때문에 도착이 한 눈금 밀리지 않게 한다.
            val next = if (moved < ARRIVAL_EPSILON_METERS) 0.0 else moved
            distances[id] = next
            coordinator.updateDistance(id, next)
            if (vacated && next <= 0.0 && coordinator.reportParked(id, offer.offerId) != null) {
                distances.remove(id)
            }
        }
    }

    private fun addPeer(id: String, zone: String, distance: Double, yields: Int) {
        coordinator.setYieldCount(id, yields)
        trackCar(id, zone, distance)
    }

    companion object {
        const val PEER_OWNER_ID = "peer-owner"

        /** 자리가 비기 전에는 이 거리에서 기다린다. */
        const val HOLD_DISTANCE_METERS = 8.0
        private const val ARRIVAL_EPSILON_METERS = 1e-6
    }
}
