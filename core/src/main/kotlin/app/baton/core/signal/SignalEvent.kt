package app.baton.core.signal

/** 읽는 신호의 종류. */
enum class SignalKind(val label: String) {
    DOOR("운전석 문"),
    SEAT("운전석 착좌"),
    SEAT_BELT("안전벨트"),
    PARKING_BRAKE("주차 브레이크 경고등"),
    BRAKE_PEDAL("브레이크 페달"),
    LOCATION("위치"),
}

/** 신호별 상태 (PRD F1). */
enum class SignalAvailability(val label: String) {
    /** 지원 여부는 모르거나 지원하지만 아직 값을 받지 못했다. 판정은 기다린다. */
    UNKNOWN("대기"),
    AVAILABLE("사용 가능"),
    /** 이 차에는 없는 신호. 보조 신호면 빼고 대체 규칙으로 판정한다. */
    UNSUPPORTED("미지원"),
    /** 지원하지만 지금은 읽지 못한다(연결 끊김 등). 보조 신호면 빼고 판정한다. */
    UNAVAILABLE("일시 불가"),
}

/** 위치 품질. 낮으면 줄 대신 구역까지만 안내한다. */
enum class LocationQuality { HIGH, LOW }

/**
 * 차량에서 읽는 신호 한 건. 모두 조회 전용이다.
 *
 * - [observedAtMillis]: 신호를 관측한 시각. null 이면 받은 시각으로 본다. 같은 신호의 더 오래된 관측은 거부한다.
 * - [parkingGeneration]: 이 값을 읽을 때 기준이던 주차 세대. null 이 아니고 현재 세대와 다르면 거부한다.
 */
sealed interface SignalEvent {
    val kind: SignalKind
    val observedAtMillis: Long?
    val parkingGeneration: Long?

    /** 운전석 문 열림(true) / 닫힘(false). */
    data class DriverDoor(
        val open: Boolean,
        override val observedAtMillis: Long? = null,
        override val parkingGeneration: Long? = null,
    ) : SignalEvent {
        override val kind: SignalKind get() = SignalKind.DOOR
    }

    /** 운전석 착좌 여부. */
    data class DriverSeat(
        val occupied: Boolean,
        override val observedAtMillis: Long? = null,
        override val parkingGeneration: Long? = null,
    ) : SignalEvent {
        override val kind: SignalKind get() = SignalKind.SEAT
    }

    /** 운전석 안전벨트 체결 여부. */
    data class DriverSeatBelt(
        val fastened: Boolean,
        override val observedAtMillis: Long? = null,
        override val parkingGeneration: Long? = null,
    ) : SignalEvent {
        override val kind: SignalKind get() = SignalKind.SEAT_BELT
    }

    /** 주차 브레이크 경고등 켜짐(true) / 꺼짐(false). */
    data class ParkingBrakeTelltale(
        val on: Boolean,
        override val observedAtMillis: Long? = null,
        override val parkingGeneration: Long? = null,
    ) : SignalEvent {
        override val kind: SignalKind get() = SignalKind.PARKING_BRAKE
    }

    /** 브레이크 페달 밟음(true) / 뗌(false). */
    data class BrakePedal(
        val pressed: Boolean,
        override val observedAtMillis: Long? = null,
        override val parkingGeneration: Long? = null,
    ) : SignalEvent {
        override val kind: SignalKind get() = SignalKind.BRAKE_PEDAL
    }

    /** 주차 지점에서 벗어난 거리(m)와 그 위치의 품질. */
    data class DistanceFromSpot(
        val meters: Double,
        val quality: LocationQuality = LocationQuality.HIGH,
        override val observedAtMillis: Long? = null,
        override val parkingGeneration: Long? = null,
    ) : SignalEvent {
        override val kind: SignalKind get() = SignalKind.LOCATION
    }

    /** 실행 중에 신호의 상태가 바뀌었다(연결 끊김·복구, 미지원 확인 등). */
    data class AvailabilityChanged(
        override val kind: SignalKind,
        val availability: SignalAvailability,
        override val observedAtMillis: Long? = null,
        override val parkingGeneration: Long? = null,
    ) : SignalEvent
}
