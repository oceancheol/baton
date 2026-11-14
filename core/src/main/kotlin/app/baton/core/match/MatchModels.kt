package app.baton.core.match

/** 자리를 기다리는 차 한 대. 양보 기록은 여기 두지 않고 [GuidanceCoordinator]의 기록 하나만 읽는다. */
data class WaitingCar(
    val id: String,
    val zoneId: String,
    /** 예고 자리까지 남은 주행 거리(m). */
    val distanceMeters: Double,
    val waitingSinceMillis: Long,
)

/** 안내 해상도. 자리 위치의 신뢰도가 낮으면 구역까지만 알려 준다. */
enum class GuidanceResolution { ROW, ZONE }

/** 예고된 자리. 위치(줄)는 안내받는 한 대에게만 알려 준다. */
data class AnnouncedSpot(
    /** 예고 ID. 같은 차가 취소 뒤 다시 예고하면 새 ID 가 된다. */
    val announcementId: String,
    val zoneId: String,
    val row: Int,
    val ownerId: String,
    val announcedAtMillis: Long,
    val resolution: GuidanceResolution = GuidanceResolution.ROW,
    /** 나가는 차가 출차 임박(DEPARTING)이 되어 안내를 시작한 시각. */
    val guidanceOpenedAtMillis: Long? = null,
    /** 나가는 차가 자리를 비운 시각. 처음 통보된 시각만 남기며, 이 시각 + 180초가 자리의 절대 만료다. */
    val vacatedAtMillis: Long? = null,
) {
    val guidanceOpen: Boolean get() = guidanceOpenedAtMillis != null
    val vacated: Boolean get() = vacatedAtMillis != null
}

/** 한 대에게만 주는 우선 안내. */
data class GuidanceOffer(
    /** 안내 ID. 주차·지나침 보고는 현재 유효한 안내의 ID 와 일치해야 받는다. */
    val offerId: String,
    val announcementId: String,
    val zoneId: String,
    /** 줄. 해상도가 [GuidanceResolution.ZONE]이면 null(알려 주지 않는다). */
    val row: Int?,
    val resolution: GuidanceResolution,
    val carId: String,
    val offeredAtMillis: Long,
    /** min(배정 시각 + 180초, 자리 이탈 시각 + 180초). */
    val expiresAtMillis: Long,
    /** 안내를 받을 때의 대기 시작 시각. 실패 복귀 뒤에도 그대로인지 확인하는 데 쓴다. */
    val waitingSinceMillis: Long,
)

/** 안내가 어떻게 끝났는지. */
enum class GuidanceOutcome(val label: String) {
    PARKED("주차 성사"),
    EXPIRED("안내 180초 만료"),
    SPOT_EXPIRED("자리 만료(이탈 후 180초)"),
    ANNOUNCEMENT_CANCELLED("예고 취소"),
    PASSED("지나침"),
    CAR_LEFT("대기 이탈"),
}

/** 안내 한 건의 결과 기록(측정용). outcome 이 null 이면 진행 중이다. */
data class GuidanceRecord(
    val offerId: String,
    val announcementId: String,
    val ownerId: String,
    val carId: String,
    val zoneId: String,
    val row: Int,
    val offeredAtMillis: Long,
    val endedAtMillis: Long? = null,
    val outcome: GuidanceOutcome? = null,
    /** 주차하지 못하고 끝났을 때 대기 시작 시각이 그대로 남았는지. 주차·이탈이면 null. */
    val waitPreserved: Boolean? = null,
    /**
     * 이 안내를 보낸 예고가 나중에 취소로 확정됐는지(= 잘못 보낸 안내). [outcome]과는 별개다:
     * 지나침·만료로 먼저 끝난 안내도 예고가 취소되면 true 가 된다. 표시용이며, 누적 지표는 이 값에 기대지 않는다.
     */
    val announcementCancelled: Boolean = false,
)

/** 세 측정값(PRD 3장)의 분자·분모. 기록 보관량과 무관하게 누적한다. */
data class GuidanceMetrics(
    /** 지금까지 보낸 안내 수(진행 중 포함). */
    val totalOffers: Int = 0,
    /**
     * 잘못 보낸 안내: 보낸 뒤 그 예고가 취소된(앞 차가 나가지 않은) 안내 수.
     * 안내가 지나침·만료로 먼저 끝났더라도 그 예고가 나중에 취소되면 포함한다. 예고 하나는 한 번만 센다.
     */
    val misguidedOffers: Int = 0,
    /** 안내 뒤 주차가 성사된 안내 수. */
    val parkedOffers: Int = 0,
    val activeOffers: Int = 0,
    /** 예고부터 실제 이탈까지 걸린 시간을 잰 예고 수. */
    val vacatedAnnouncements: Int = 0,
    val lastLeadTimeMillis: Long? = null,
    val averageLeadTimeMillis: Long? = null,
)

/** 다른 차에 보이는 구역별 집계. 자리 위치는 담지 않는다. */
data class ZoneSummary(
    val zoneId: String,
    val upcomingSpots: Int,
    val waitingCars: Int,
)

data class ParkResult(
    val carId: String,
    val offerId: String,
    val spot: AnnouncedSpot,
    /** 자리를 내준 차의 새 양보 기록. */
    val ownerYieldCount: Int,
)

data class MatchConfig(
    /** 서행 기준 속도(m/s). 약 10km/h. */
    val slowSpeedMps: Double = 2.8,
    /** 기다린 1초당 깎아 주는 시간(초). */
    val waitBonusPerSecond: Double = 0.1,
    val maxWaitBonusSeconds: Double = 60.0,
    /** 안내 유효 시간. */
    val offerValidMillis: Long = 180_000L,
    /** 앞 차가 떠난 뒤 자리를 안내·집계에 두는 시간. */
    val spotValidAfterVacatedMillis: Long = 180_000L,
    /** 보관하는 안내 결과 기록 수. */
    val maxRecords: Int = 200,
    val zones: List<String> = listOf("A", "B", "C"),
)
