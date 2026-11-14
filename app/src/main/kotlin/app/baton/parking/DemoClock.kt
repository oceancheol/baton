package app.baton.parking

import android.os.SystemClock
import app.baton.core.Clock

/**
 * 앱에서 쓰는 시계. 시연 패널의 "시간 +30초"로 앞당길 수 있다.
 * [base]는 바탕 시각을 읽는 곳이다. 앱에서는 기본값(기기 부팅 후 경과 시간)을 쓰고, 단위 시험에서만 가짜 시각을 넣는다.
 */
class DemoClock(private val base: () -> Long = { SystemClock.elapsedRealtime() }) : Clock {
    @Volatile
    private var skewMillis = 0L

    override fun nowMillis(): Long = base() + skewMillis

    fun skip(millis: Long) {
        skewMillis += millis
    }
}
