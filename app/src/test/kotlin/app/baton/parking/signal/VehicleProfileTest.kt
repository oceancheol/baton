package app.baton.parking.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 에뮬레이터에서만 확인한 해석은 확인된 환경에서만 쓴다. */
class VehicleProfileTest {
    @Test
    fun `Pleos Connect 에뮬레이터 지문이면 확인된 프로필이다`() {
        val profile = VehicleProfile.forFingerprint(
            "Pleos/crp_avd_arm64/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys",
        )
        assertEquals(VehicleProfile.PLEOS_CONNECT_EMULATOR, profile)
        assertTrue(profile.verified)
        assertTrue(profile.useSeatOccupancy && profile.seatOccupancyInverted)
        assertTrue(profile.useParkingBrakeTelltale)
        assertTrue(profile.beltExcludedForDemo)
    }

    @Test
    fun `그 밖의 지문은 검증되지 않은 환경이다 - 착좌와 경고등을 쓰지 않고 벨트는 SDK 결과를 따른다`() {
        val others = listOf(
            null,
            "",
            "google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036/11228894:userdebug/dev-keys",
            "hyundai/some_car/some_car:14/RELEASE/1:user/release-keys",
            "pleos/crp_avd_arm64/x:14/a/b:user/test-keys", // 대소문자가 다르면 같은 환경으로 보지 않는다
        )
        for (fingerprint in others) {
            val profile = VehicleProfile.forFingerprint(fingerprint)
            assertEquals(VehicleProfile.UNVERIFIED, profile, "지문: $fingerprint")
            assertFalse(profile.verified)
            assertFalse(profile.useSeatOccupancy)
            assertFalse(profile.seatOccupancyInverted)
            assertFalse(profile.useParkingBrakeTelltale)
            assertFalse(profile.beltExcludedForDemo)
        }
    }
}
