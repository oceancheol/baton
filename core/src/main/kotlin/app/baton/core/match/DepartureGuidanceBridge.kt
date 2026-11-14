package app.baton.core.match

import app.baton.core.departure.DepartureLogEvent
import app.baton.core.departure.DepartureState
import app.baton.core.departure.TransitionReason
import app.baton.core.signal.LocationQuality

/**
 * 상태기계가 한 입력에서 낸 전이 목록을 **순서대로** [GuidanceCoordinator]에 옮긴다.
 *
 * ANNOUNCING 진입 → 예고 생성, DEPARTING 진입 → 안내 시작, VACATED → 비움, 예고 중 PARKED 복귀 → 취소.
 * 마지막 상태만 보지 않으므로 한 입력에서 예고·안내·비움이 연달아 일어나거나 취소와 재예고가 함께 일어나도
 * 빠뜨리지 않는다. 전이마다 실린 예고 세대로 예고 ID 를 만들기 때문에, 지난 세대의 전이가 새 예고를 건드리지 못한다.
 *
 * 위치 품질은 상태 전이와 무관하게 옮긴다: 예고가 살아 있는 동안 품질이 LOW 로 바뀌었다는 기록
 * ([TransitionReason.LOCATION_QUALITY_CHANGED])이 오면 그 예고의 안내를 **즉시** 구역 안내로 낮춘다.
 * 정책: 한 번 구역으로 낮춘 예고는 품질이 다시 HIGH 가 돼도 줄 안내로 되돌리지 않는다. 낮았던 동안의 줄 위치를
 * 믿을 수 없고, 안내받는 차의 화면이 줄 ↔ 구역으로 오락가락하지 않게 하기 위해서다. 다음 예고는 그때의 품질로 새로 정한다.
 */
class DepartureGuidanceBridge(
    private val coordinator: GuidanceCoordinator,
    val ownerId: String,
    val zoneId: String,
    val row: Int,
) {
    /** 가장 최근에 만든 예고의 ID. 없으면 null. */
    @Volatile
    var latestAnnouncementId: String? = null
        private set

    fun announcementId(generation: Long): String = "$ownerId#$generation"

    @Synchronized
    fun apply(events: List<DepartureLogEvent>) {
        events.forEach(::applyOne)
    }

    private fun applyOne(event: DepartureLogEvent) {
        val generation = event.announcementGeneration ?: return
        val id = announcementId(generation)
        val lowQuality = event.locationQuality == LocationQuality.LOW
        if (!event.isTransition) {
            // 전이 없는 품질 저하: 진행 중인 안내를 바로 구역으로 낮춘다(이미 끝난 예고면 아무 일도 없다).
            // HIGH 로 돌아온 기록은 일부러 무시한다(위 정책).
            if (event.reason == TransitionReason.LOCATION_QUALITY_CHANGED && lowQuality) coordinator.limitToZone(id)
            return
        }
        val announcing = event.from == DepartureState.ANNOUNCING || event.from == DepartureState.DEPARTING
        when (event.to) {
            DepartureState.ANNOUNCING -> {
                val resolution = if (lowQuality) GuidanceResolution.ZONE else GuidanceResolution.ROW
                coordinator.announceSpot(id, zoneId, row, ownerId, resolution)
                latestAnnouncementId = id
            }
            DepartureState.DEPARTING -> {
                if (lowQuality) coordinator.limitToZone(id)
                coordinator.openGuidance(id)
            }
            DepartureState.VACATED -> if (announcing) {
                if (lowQuality) coordinator.limitToZone(id)
                coordinator.markVacated(id)
            }
            DepartureState.PARKED -> if (announcing) coordinator.cancelSpot(id)
            DepartureState.BOARDING -> Unit
        }
    }
}
