package app.baton.parking.signal

/**
 * 차량 환경별로 값 해석이 확인됐는지. 확인된 환경에서만 그 환경에서 본 사실에 기댄 해석을 쓴다.
 *
 * 확인한 환경은 Pleos Connect v2.1.0 에뮬레이터 하나다(`Build.FINGERPRINT`가 `Pleos/crp_avd`로 시작).
 * 그 밖의 환경(실차 포함)은 확인하지 못했으므로 착좌와 주차 브레이크 경고등을 쓰지 않는다.
 */
data class VehicleProfile(
    val label: String,
    /** 이 환경에서 값 해석을 직접 확인했는지. */
    val verified: Boolean,
    /** 착좌를 판정에 쓸지. false 면 일시 불가로 두어 자동 예고를 끈다(최소 신호). */
    val useSeatOccupancy: Boolean,
    /** SDK 가 준 착좌 값을 뒤집어 읽을지(에뮬레이터에서 확인한 방향). */
    val seatOccupancyInverted: Boolean,
    /** 주차 브레이크 경고등을 판정에 쓸지. false 면 빼고 페달·이동 대체 규칙으로 판정한다. */
    val useParkingBrakeTelltale: Boolean,
    /** 안전벨트를 시연 설정으로 뺄지(에뮬레이터의 Virtual Console 에 벨트 조작이 없다). false 면 SDK 지원 확인 결과를 따른다. */
    val beltExcludedForDemo: Boolean,
    /**
     * 앱이 처음 연결될 때 읽은 착좌 값을 "이미 앉아 있음"으로 믿을지.
     * 에뮬레이터를 새로 부팅한 직후에는 Virtual Console 의 좌석 값이 미설정이라 SDK 가 false 를 주고, 뒤집어 읽으면
     * "앉음"이 된다(Boolean 값으로는 미설정과 앉음을 가릴 수 없다). 그래서 이 환경에서는 믿지 않는다:
     * 처음 연결 복원에서도 탑승으로 복원하지 않고 문 여닫힘을 기다린다.
     */
    val trustInitialSeatOccupancy: Boolean,
) {
    companion object {
        private const val PLEOS_EMULATOR_FINGERPRINT_PREFIX = "Pleos/crp_avd"

        /** Pleos Connect 에뮬레이터: 착좌 반전, 벨트 제외, "경고등 목록에 없음 = 꺼짐"을 여기서만 쓴다. */
        val PLEOS_CONNECT_EMULATOR = VehicleProfile(
            label = "Pleos Connect 에뮬레이터",
            verified = true,
            useSeatOccupancy = true,
            seatOccupancyInverted = true,
            useParkingBrakeTelltale = true,
            beltExcludedForDemo = true,
            trustInitialSeatOccupancy = false,
        )

        /** 확인하지 못한 환경. */
        val UNVERIFIED = VehicleProfile(
            label = "검증되지 않은 차량 환경",
            verified = false,
            useSeatOccupancy = false,
            seatOccupancyInverted = false,
            useParkingBrakeTelltale = false,
            beltExcludedForDemo = false,
            trustInitialSeatOccupancy = false,
        )

        fun forFingerprint(fingerprint: String?): VehicleProfile =
            if (fingerprint?.startsWith(PLEOS_EMULATOR_FINGERPRINT_PREFIX) == true) PLEOS_CONNECT_EMULATOR else UNVERIFIED
    }
}
