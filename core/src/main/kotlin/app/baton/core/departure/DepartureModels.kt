package app.baton.core.departure

import app.baton.core.signal.LocationFix
import app.baton.core.signal.LocationQuality
import app.baton.core.signal.SignalReading

enum class DepartureState(val label: String) {
    PARKED("주차 중"),
    BOARDING("탑승"),
    ANNOUNCING("자리 예고 중"),
    DEPARTING("출차 임박"),
    VACATED("비었음"),
}

enum class TransitionReason(val label: String) {
    DOOR_OPEN_CLOSE("운전석 문 열림 → 닫힘"),
    DOOR_UNSUPPORTED("문 신호를 읽지 못함: 착좌로 바로 판정"),
    RESTORED_OCCUPIED("구독 시작 때 이미 착좌: 탑승한 상태로 복원"),
    RECONNECTED_OCCUPIED("다시 연결됐을 때 이미 착좌: 문 여닫힘(문 신호가 없으면 하차 후 재착좌)을 기다림"),
    SEATED_AND_BELTED("운전석 착좌 + 안전벨트 체결"),
    SEATED("운전석 착좌 (벨트 신호를 읽지 못함: 착좌만 사용)"),
    PARKING_BRAKE_RELEASED("주차 브레이크 경고등 꺼짐"),
    BRAKE_PEDAL("브레이크 페달 밟음"),
    BRAKE_OFF_AND_MOVE("주차 브레이크가 이미 꺼져 있음 + 이동 시작"),
    FALLBACK_PEDAL_AND_MOVE("대체 규칙: 브레이크 페달 + 이동 시작"),
    FALLBACK_MOVE_ONLY("대체 규칙: 이동 시작"),
    LEFT_SPOT("주차 지점에서 벗어남"),
    TIMEOUT_NO_PROGRESS("예고 시작 후 120초 안에 자리를 벗어나지 않음: 예고 취소"),
    DOOR_REOPEN_TIMEOUT("문이 열린 뒤 60초 안에 닫히지 않음: 예고 취소"),
    SEAT_VACATED_TIMEOUT("착좌가 풀린 뒤 60초 안에 돌아오지 않음: 예고 취소"),
    MIN_SIGNAL_LOST("최소 신호(착좌·위치)를 읽지 못함: 예고 취소"),
    SUPPRESSED_BY_DRIVER("이번엔 예고 안 함"),
    SUPPRESS_REJECTED("차가 움직이는 중이라 '예고 안 함'을 받지 않음"),
    SUPPRESSED_LEFT("예고 없이 출차"),
    NEW_PARKING("새 주차 시작"),
    RESTORED("현재 신호 값으로 복원 (변화로 세지 않음)"),
    STALE_EVENT_REJECTED("오래된 신호 거부"),
    STALE_SNAPSHOT_REJECTED("오래된 스냅샷 거부"),
    LOCATION_QUALITY_CHANGED("위치 정확도 변경"),
    NOTE("참고"),
}

/**
 * 상태기계가 내보내는 로그 한 줄. from == to 이면 전이가 아닌 참고 기록이다.
 *
 * 상태기계의 각 입력 함수는 그 입력 하나로 생긴 이 기록들을 **순서대로** 돌려준다.
 * 예고·안내·취소 전달은 그 반환값으로 한다(마지막 상태만 보면 중간 전이를 잃는다).
 */
data class DepartureLogEvent(
    /** 1부터 오르는 순번. [DepartureStateMachine.eventsSince]로 새 기록만 읽을 때 쓴다. */
    val seq: Long,
    val atMillis: Long,
    val from: DepartureState,
    val to: DepartureState,
    val reason: TransitionReason,
    val detail: String = reason.label,
    val parkingGeneration: Long = 0L,
    /** 이 전이가 속한 예고 세대. 예고와 무관한 기록이면 null. */
    val announcementGeneration: Long? = null,
    /** 기록 시점의 위치 품질. 위치를 읽지 못하면 null. */
    val locationQuality: LocationQuality? = null,
) {
    val isTransition: Boolean get() = from != to
}

/** 자동 예고를 쓸 수 있는지와, 못 쓰면 그 이유(PRD F1 최소 신호 조건). */
data class AutoAnnounceStatus(
    val enabled: Boolean = true,
    val reason: String? = null,
)

/** 화면이 읽는 현재 모습. 신호 값은 합성하지 않은 실제 관측값이다. */
data class DepartureSnapshot(
    val state: DepartureState = DepartureState.PARKED,
    val suppressed: Boolean = false,
    /** 지금 "이번엔 예고 안 함"을 받을 수 있는지. 차가 움직이는 중에는 false. */
    val canSuppress: Boolean = true,
    val autoAnnounce: AutoAnnounceStatus = AutoAnnounceStatus(),
    val parkingGeneration: Long = 1L,
    /** 진행 중인(또는 이번 주차에서 마지막으로 끝까지 간) 예고 세대. */
    val announcementGeneration: Long? = null,
    val door: SignalReading<Boolean> = SignalReading(),
    val seat: SignalReading<Boolean> = SignalReading(),
    val seatBelt: SignalReading<Boolean> = SignalReading(),
    val parkingBrake: SignalReading<Boolean> = SignalReading(),
    val brakePedal: SignalReading<Boolean> = SignalReading(),
    val location: SignalReading<LocationFix> = SignalReading(),
    /** 이번 주차에서 운전석 문이 열렸다 닫히는 변화를 실제로 봤는지. */
    val doorCycleSeen: Boolean = false,
    /** 탑승 이후 주차 브레이크 경고등이 켜짐 → 꺼짐으로 바뀌는 것을 봤는지. */
    val brakeReleaseSeen: Boolean = false,
    /** 탑승 이후 브레이크 페달 밟음을 봤는지. */
    val pedalPressSeen: Boolean = false,
    /** 지금 주차 지점에서 이동 시작 거리 이상 벗어나 있는지. */
    val movedSeen: Boolean = false,
    val stateEnteredAtMillis: Long = 0L,
    /** 예고를 시작한(ANNOUNCING 진입) 시각. 예고 중이 아니면 null. */
    val announcedAtMillis: Long? = null,
    /** 예고가 자동 취소되는 시각. 예고 중이 아니면 null. */
    val cancelAtMillis: Long? = null,
    /** 오래됐거나 다른 주차 세대라서 거부한 신호 수. */
    val rejectedEventCount: Int = 0,
    /** 다른 주차 세대·지난 조회 요청·오래된 관측이라서 통째로 거부한 스냅샷 수. */
    val rejectedSnapshotCount: Int = 0,
)

data class DepartureConfig(
    /** 예고 시작(ANNOUNCING 진입)부터 VACATED 까지 허용하는 시간. 단계가 바뀌어도 다시 세지 않는다. */
    val noProgressTimeoutMillis: Long = 120_000L,
    /** 문 재개방·착좌 해제 뒤 취소까지 기다리는 시간. */
    val reopenGraceMillis: Long = 60_000L,
    /** 이 거리 이상 움직이면 "이동 시작"으로 본다. */
    val moveStartMeters: Double = 1.0,
    /** 이 거리 이상 벗어나면 자리가 비었다고 본다. */
    val vacateMeters: Double = 10.0,
    /** 상태기계가 보관하는 로그 줄 수. 넘으면 오래된 것부터 버린다. */
    val maxLogEvents: Int = 200,
)
