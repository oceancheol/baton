package app.baton.parking.signal

import android.content.Context
import app.baton.core.Clock
import app.baton.core.signal.SignalSource

/**
 * 신호 소스를 정하는 단 한 곳. 앱의 나머지는 [SignalSource] 인터페이스만 본다.
 */
object SignalSourceProvider {

    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, clock: Clock): SignalSource {
        // TODO(M1 후속): Pleos SDK 사용권 동의 뒤 여기서 PleosSignalSource(context)를 먼저 만들고,
        //  SDK 초기화가 실패하면 아래 FakeSignalSource 로 내려간다. (PRD 6장)
        //  - PleosSignalSource 는 Door / Seat / Safety(주차 브레이크 경고등) / Brake / 위치를 구독한다.
        //  - currentSnapshot() 에서 신호마다 현재 값을 조회해 사용 가능/미지원/일시 불가를 가려 주고,
        //    events 로는 그 뒤의 변경만(관측 시각·주차 세대를 붙여) 내보낸다. 콜백 등록은 수집이 취소될 때 푼다.
        //  - SDK 호출은 PleosSignalSource 한 파일에만 둔다.
        return FakeSignalSource(clock)
    }
}
