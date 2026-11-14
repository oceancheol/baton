package app.baton.parking.signal

import android.content.Context
import app.baton.core.Clock
import app.baton.core.signal.SignalSource

/**
 * 신호 소스를 정하는 단 한 곳. 앱의 나머지는 [SignalSource] 인터페이스만 본다.
 */
object SignalSourceProvider {

    /**
     * Pleos 차량 서비스가 있는 기기에서는 실제 차량 신호([PleosSignalSource])를, 없거나 SDK 초기화가 실패하면
     * 모의 신호([FakeSignalSource])를 돌려준다(PRD 6장). SDK 호출은 PleosSignalSource 한 파일에만 둔다.
     */
    fun create(context: Context, clock: Clock): SignalSource =
        PleosSignalSource.createOrNull(context, clock) ?: FakeSignalSource(clock)
}
