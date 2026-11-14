package app.baton.core.match

import kotlin.math.roundToLong

/**
 * 안내 순서 계산 (PRD F2).
 *
 * 기준 값 = 서행 기준 도착 예상 시간 − 대기 보정. 이 값을 1초 단위로 반올림해 작은 차가 먼저다.
 * 반올림한 값이 같을 때만 양보 기록이 많은 차가 먼저다. 그래도 같으면 먼저 기다린 차, 그다음 id 순.
 */
class GuidanceOrder(private val config: MatchConfig = MatchConfig()) {

    /** 서행 기준 도착 예상 시간(초). */
    fun etaSeconds(distanceMeters: Double): Double =
        distanceMeters.coerceAtLeast(0.0) / config.slowSpeedMps

    fun waitBonusSeconds(car: WaitingCar, nowMillis: Long): Double {
        val waitedSeconds = (nowMillis - car.waitingSinceMillis).coerceAtLeast(0L) / 1000.0
        return (waitedSeconds * config.waitBonusPerSecond).coerceAtMost(config.maxWaitBonusSeconds)
    }

    /** 순서의 기준 값(초, 1초 단위 반올림): 도착 예상 − 대기 보정. */
    fun orderKeySeconds(car: WaitingCar, nowMillis: Long): Long =
        (etaSeconds(car.distanceMeters) - waitBonusSeconds(car, nowMillis)).roundToLong()

    /** 먼저 안내할 순서대로. [yieldCounts]는 차 id → 양보 기록(없으면 0). */
    fun rank(
        cars: Collection<WaitingCar>,
        nowMillis: Long,
        yieldCounts: Map<String, Int> = emptyMap(),
    ): List<WaitingCar> =
        cars.sortedWith(
            compareBy<WaitingCar> { orderKeySeconds(it, nowMillis) }
                .thenByDescending { yieldCounts[it.id] ?: 0 }
                .thenBy { it.waitingSinceMillis }
                .thenBy { it.id },
        )

    fun pick(
        cars: Collection<WaitingCar>,
        nowMillis: Long,
        yieldCounts: Map<String, Int> = emptyMap(),
    ): WaitingCar? = rank(cars, nowMillis, yieldCounts).firstOrNull()
}
