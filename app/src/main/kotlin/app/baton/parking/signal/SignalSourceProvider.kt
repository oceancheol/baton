package app.baton.parking.signal

import android.content.Context
import android.util.Log
import app.baton.core.Clock

/**
 * 신호 소스를 정하는 단 한 곳. 앱의 나머지는 [BatonSignalSource] 인터페이스만 본다.
 */
object SignalSourceProvider {
    private const val TAG = "BatonPleos"

    /**
     * debug 빌드에서만 쓰는 확인용 스위치: 검증되지 않은 차량 환경의 동작(착좌·경고등 제외)을 에뮬레이터에서 보려고 둔다.
     * release 빌드에서는 켜는 곳이 없다(MainActivity 가 BuildConfig.DEBUG 일 때만 읽는다).
     */
    @Volatile
    var debugForceUnverifiedProfile: Boolean = false

    /**
     * Pleos 차량 서비스가 있는 기기에서는 실제 차량 신호([PleosSignalSource])를, 없거나 SDK 초기화가 실패하면
     * 모의 신호([FakeSignalSource])를 돌려준다(PRD 6장). SDK 호출은 PleosSignalSource 한 파일에만 둔다.
     *
     * SDK 클래스를 읽지 못해 PleosSignalSource 의 코드에 들어가기도 전에 나는 오류(LinkageError 계열)도
     * 이 바깥 경계에서 잡아 모의 신호로 내려간다.
     */
    fun create(context: Context, clock: Clock): BatonSignalSource {
        val real = try {
            PleosSignalSource.createOrNull(context, clock, debugForceUnverifiedProfile)
        } catch (e: LinkageError) {
            Log.w(TAG, "Pleos 차량 SDK 클래스를 읽지 못함 → 모의 신호 사용", e)
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "Pleos 차량 SDK 준비 실패 → 모의 신호 사용", e)
            null
        }
        return real ?: FakeSignalSource(clock)
    }
}
