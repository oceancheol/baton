package app.baton.core

/** 시계 주입 지점. 단위 시험에서는 가짜 시계를 넣는다. */
fun interface Clock {
    fun nowMillis(): Long
}

/**
 * 지난 구간을 예정 시각 순서대로 다시 밟을 때 쓰는 시계.
 *
 * 평소에는 [base]의 시각을 그대로 돌려주고, [at] 안에서는 지정한 시각으로 고정된다.
 * 모의 상대 차([app.baton.core.match.MockPeerService])가 건너뛴 시간 속의 사건을
 * "그 사건이 일어났어야 할 시각"으로 처리하려고 쓴다. 한 스레드(모의 진행 스레드)에서만 [at]을 부른다.
 */
class SteppedClock(private val base: Clock) : Clock {
    @Volatile
    private var pinned: Long? = null

    override fun nowMillis(): Long = pinned ?: base.nowMillis()

    fun <T> at(millis: Long, block: () -> T): T {
        val previous = pinned
        pinned = millis
        try {
            return block()
        } finally {
            pinned = previous
        }
    }
}
