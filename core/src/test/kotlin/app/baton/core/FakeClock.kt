package app.baton.core

/** 시험용 가짜 시계. 손으로만 흐른다. */
class FakeClock(private var now: Long = 1_000_000L) : Clock {
    override fun nowMillis(): Long = now

    fun advanceSeconds(seconds: Long) {
        now += seconds * 1000L
    }

    fun advanceMillis(millis: Long) {
        now += millis
    }
}
