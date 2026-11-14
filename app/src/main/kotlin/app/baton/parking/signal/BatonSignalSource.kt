package app.baton.parking.signal

import app.baton.core.signal.SignalSource
import kotlinx.coroutines.flow.StateFlow

/**
 * 앱이 신호 소스에서 더 쓰는 것들. 화면 쪽(ViewModel)은 구현 클래스를 모르고 이 인터페이스만 본다.
 */
interface BatonSignalSource : SignalSource {
    /** 시연 패널 스위치로 조작하는 모의 차량 신호. 실제 차량 신호 소스면 null. */
    val manualSignals: FakeSignalSource?

    /** 모의 위치를 맡는 곳(위치가 모의일 때). */
    val mockLocation: FakeSignalSource?

    /** 차량 서비스에서 값을 읽고 있는지. 모의 소스는 항상 [LinkStatus.RECEIVING]. */
    val link: StateFlow<LinkStatus>

    /** 지금 값을 읽는 기준인 주차 세대. 새 주차를 시작할 때 맞춘다. 이후의 관측에 이 세대가 붙는다. */
    var parkingGeneration: Long?

    /** 안전벨트를 시연 설정으로 판정에서 뺐는지(화면에 "미지원" 대신 사실대로 적는다). */
    val beltExcludedForDemo: Boolean get() = false

    /** 이 환경 때문에 자동 예고를 끈 이유. 없으면 null. */
    val environmentNotice: String? get() = null

    /** 시연 패널에 적는 "무엇을 읽는 중인지" 한 줄. 모의 소스는 null. */
    val readingSummary: String? get() = null

    /** 구독·연결을 푼다. 화면이 끝날 때 한 번 부른다. */
    fun close()
}
