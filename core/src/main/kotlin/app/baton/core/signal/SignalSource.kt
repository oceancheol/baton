package app.baton.core.signal

import kotlinx.coroutines.flow.Flow

/** 신호별 지원 여부의 초기 가정. false 면 [SignalAvailability.UNSUPPORTED]로 시작한다. */
data class SignalCapabilities(
    val door: Boolean = true,
    val seatOccupancy: Boolean = true,
    val seatBelt: Boolean = true,
    val parkingBrakeTelltale: Boolean = true,
    val brakePedal: Boolean = true,
    val location: Boolean = true,
) {
    companion object {
        val ALL = SignalCapabilities()
    }
}

/** 신호 하나의 상태·현재 값·갱신 시각. */
data class SignalReading<out T>(
    val availability: SignalAvailability = SignalAvailability.UNKNOWN,
    val value: T? = null,
    val updatedAtMillis: Long? = null,
) {
    /** 지금 판정에 쓸 수 있는 값. 사용 가능 상태가 아니면 null. */
    val usableValue: T? get() = if (availability == SignalAvailability.AVAILABLE) value else null

    /** 미지원이거나 일시 불가라서 판정에서 빼야 하는지. */
    val excluded: Boolean
        get() = availability == SignalAvailability.UNSUPPORTED || availability == SignalAvailability.UNAVAILABLE

    companion object {
        fun <T> available(value: T, atMillis: Long? = null): SignalReading<T> =
            SignalReading(SignalAvailability.AVAILABLE, value, atMillis)

        fun unsupported(): SignalReading<Nothing> = SignalReading(SignalAvailability.UNSUPPORTED)

        fun unavailable(atMillis: Long? = null): SignalReading<Nothing> =
            SignalReading(SignalAvailability.UNAVAILABLE, null, atMillis)

        fun unknown(): SignalReading<Nothing> = SignalReading(SignalAvailability.UNKNOWN)
    }
}

data class LocationFix(
    val metersFromSpot: Double,
    val quality: LocationQuality = LocationQuality.HIGH,
)

/**
 * 현재 값 조회 한 번의 표. 조회를 시작할 때 상태기계(`newSnapshotRequest()`)에서 받아 두었다가,
 * 돌아온 스냅샷에 [SignalSnapshot.answering]으로 붙인다. 조회가 늦게 돌아와 그 사이 주차가 바뀌었거나
 * 더 나중 조회의 답이 이미 반영됐으면 상태기계가 그 스냅샷을 버린다.
 */
data class SnapshotRequest(
    /** 조회를 시작할 때의 주차 세대. */
    val parkingGeneration: Long,
    /** 조회 요청 식별자. 요청마다 1씩 오른다. */
    val requestId: Long,
)

/**
 * 어느 한 시각에 읽은 모든 신호의 현재 값. '변화'가 아니라 '상태'다.
 *
 * 관측 시각은 [takenAtMillis] 하나다(그 시각에 "지금 값"으로 읽었다). 신호별 `updatedAtMillis`는 표시용이다.
 * 상태기계는 신호마다 "이미 반영한 관측보다 오래된 스냅샷 값"을 버린다.
 */
data class SignalSnapshot(
    val door: SignalReading<Boolean> = SignalReading(),
    val seat: SignalReading<Boolean> = SignalReading(),
    val seatBelt: SignalReading<Boolean> = SignalReading(),
    val parkingBrake: SignalReading<Boolean> = SignalReading(),
    val brakePedal: SignalReading<Boolean> = SignalReading(),
    val location: SignalReading<LocationFix> = SignalReading(),
    val takenAtMillis: Long,
    /** 이 값을 읽을 때 기준이던 주차 세대. null 이 아니고 현재 세대와 다르면 통째로 거부한다. */
    val parkingGeneration: Long? = null,
    /** 이 스냅샷을 낳은 조회 요청. null 이 아니고 이미 반영한 요청보다 앞선(또는 같은) 것이면 통째로 거부한다. */
    val requestId: Long? = null,
) {
    /** [request]의 답으로 표시한다(주차 세대와 요청 식별자를 붙인다). */
    fun answering(request: SnapshotRequest): SignalSnapshot =
        copy(parkingGeneration = request.parkingGeneration, requestId = request.requestId)
}

/**
 * 차량 신호를 내보내는 곳. 구현체는 모의(FakeSignalSource)와 차량 SDK(PleosSignalSource) 두 가지다.
 *
 * 계약: **현재 값 조회 → 이후 변경 수신** 순서로 쓴다.
 * 1. [currentSnapshot]으로 현재 값을 읽어 상태기계에 `restore()`(또는 `startNewParking(snapshot)`)로 넣는다.
 *    이 값은 초기 상태일 뿐 '변화'가 아니다(문 닫힘을 "방금 닫혔다"로, 경고등 꺼짐을 "방금 해제했다"로 세지 않는다).
 *    조회가 비동기이면 조회 전에 상태기계에서 [SnapshotRequest]를 받아 답에 붙인다([SignalSnapshot.answering]).
 *    새 주차 때는 `startNewParking(awaitBaseline = true)`로 세대를 한 번만 올리고, 그 세대의 요청으로 읽은 값을
 *    `restore()`로 넣는다(그 복원은 새 주차의 기준값이라 "이미 앉아 있음"을 탑승으로 세지 않는다).
 * 2. 그 뒤 [events]를 구독해 변경만 받는다.
 *
 * 구현체는 스냅샷에서 신호마다 사용 가능/미지원/일시 불가를 가려 줘야 한다. 조회가 실패하거나 시간 안에 오지 않은
 * 신호는 [SignalAvailability.UNAVAILABLE]로 돌려준다. [SignalAvailability.UNKNOWN]은 상태기계가 판정을
 * 기다리게 하므로 잠깐만 머물러야 한다.
 */
interface SignalSource {
    /** 화면 표시에 쓰는 이름. */
    val name: String

    /** 모의 신호면 true. 화면 상단에 "모의 신호"를 띄우는 근거. */
    val isMock: Boolean

    /** 위치가 모의 값이면 true. */
    val isMockLocation: Boolean get() = isMock

    val capabilities: SignalCapabilities

    /** 지금의 신호 값. 구독 전에, 그리고 주차 뒤 복귀할 때 다시 읽는다. */
    suspend fun currentSnapshot(): SignalSnapshot

    /** [currentSnapshot] 이후의 변경. */
    val events: Flow<SignalEvent>
}
