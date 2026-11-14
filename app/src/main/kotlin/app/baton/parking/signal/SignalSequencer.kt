package app.baton.parking.signal

import app.baton.core.signal.SignalAvailability
import app.baton.core.signal.SignalEvent
import app.baton.core.signal.SignalKind
import app.baton.core.signal.SignalReading
import java.util.EnumMap

/** 차량 신호 하나의 관측값(상태 + 값). */
data class VehicleReading(val availability: SignalAvailability, val value: Boolean? = null) {
    fun toSignal(atMillis: Long): SignalReading<Boolean> = when (availability) {
        SignalAvailability.AVAILABLE -> SignalReading.available(value == true, atMillis)
        SignalAvailability.UNSUPPORTED -> SignalReading.unsupported()
        else -> SignalReading.unavailable(atMillis)
    }

    fun brief(): String = if (availability == SignalAvailability.AVAILABLE) value.toString() else availability.label

    companion object {
        val UNAVAILABLE = VehicleReading(SignalAvailability.UNAVAILABLE)
        val UNSUPPORTED = VehicleReading(SignalAvailability.UNSUPPORTED)

        fun of(value: Boolean?): VehicleReading =
            if (value == null) UNAVAILABLE else VehicleReading(SignalAvailability.AVAILABLE, value)
    }
}

/**
 * 관측 하나의 표. 콜백에 들어온 순간(또는 조회를 시작한 순간)에 찍어 처리할 때까지 그대로 들고 간다.
 * 순번은 도착 순서, 시각은 그때의 시계, 세대는 그때의 주차 세대다.
 */
data class ObservationStamp(val seq: Long, val atMillis: Long, val parkingGeneration: Long?)

/** 밖으로 내보낼 변경 하나. */
data class VehicleChange(val kind: SignalKind, val reading: VehicleReading, val stamp: ObservationStamp) {
    fun toEvent(): SignalEvent {
        val at = stamp.atMillis
        val generation = stamp.parkingGeneration
        if (reading.availability != SignalAvailability.AVAILABLE) {
            return SignalEvent.AvailabilityChanged(kind, reading.availability, at, generation)
        }
        val value = reading.value == true
        return when (kind) {
            SignalKind.DOOR -> SignalEvent.DriverDoor(value, at, generation)
            SignalKind.SEAT -> SignalEvent.DriverSeat(value, at, generation)
            SignalKind.SEAT_BELT -> SignalEvent.DriverSeatBelt(value, at, generation)
            SignalKind.PARKING_BRAKE -> SignalEvent.ParkingBrakeTelltale(value, at, generation)
            SignalKind.BRAKE_PEDAL -> SignalEvent.BrakePedal(value, at, generation)
            SignalKind.LOCATION -> error("위치는 차량 신호 소스가 내보내지 않는다")
        }
    }
}

/** 차량 서비스에서 값을 읽고 있는지. */
enum class LinkStatus { CONNECTING, RECEIVING, UNAVAILABLE }

/** 현재 값 조회 한 번의 결과: 기준값(스냅샷)과, 조회하는 동안 들어와 그 뒤에 내보낼 변경들. */
data class BaselineResult(
    val start: ObservationStamp,
    val levels: Map<SignalKind, VehicleReading>,
    val pending: List<VehicleChange>,
)

/** 주기적 확인 한 번의 결과. [recovered]이면 호출한 쪽이 기준값을 다시 읽어야 한다. */
data class ProbeResult(val changes: List<VehicleChange>, val recovered: Boolean)

/**
 * 구독으로 들어온 변경과 현재 값 조회의 답을 도착 순번으로 정리한다. SDK·Android 와 무관한 순수 논리다.
 *
 * 규칙
 * - 현재 값 조회(기준값)는 한 번에 하나만 연다. 여는 동안 들어온 구독 변경은 내보내지 않고 모아 둔다.
 *   조회가 끝나면 답을 기준값으로 삼고, 조회 시작 뒤에 들어온 변경을 순번대로 그 뒤에 내보낸다.
 *   그래서 조회 중의 문 여닫힘이 사라지지 않고, 답보다 늦게 반영된 구독 값이 마지막 값이 된다.
 * - 조회 시작보다 앞선 순번의 구독 값은 그 답보다 오래된 관측이라 버린다.
 * - 주기적 확인의 답은 그 확인을 시작한 뒤에 구독 값이 들어온 신호에는 쓰지 않는다(구독 값이 더 새롭다).
 *   이미 더 나중 조회의 답을 반영한 신호도 건드리지 않는다. 오래된 조회가 최신 값과 중복 제거 기준을 덮어쓰지 못한다.
 * - 직전에 내보낸 값과 같은 값은 변경으로 내보내지 않는다.
 */
class SignalSequencer {
    private var nextSeq = 1L
    private val latest = EnumMap<SignalKind, VehicleReading>(SignalKind::class.java)
    private val lastListenerSeq = EnumMap<SignalKind, Long>(SignalKind::class.java)
    private val baselineSeq = EnumMap<SignalKind, Long>(SignalKind::class.java)
    private var open: ObservationStamp? = null
    private val buffer = ArrayList<VehicleChange>()

    var status: LinkStatus = LinkStatus.CONNECTING
        @Synchronized get
        private set

    /** 관측(콜백 진입, 조회 시작)마다 한 번 부른다. 어느 스레드에서 불러도 된다. */
    @Synchronized
    fun stamp(atMillis: Long, parkingGeneration: Long?): ObservationStamp =
        ObservationStamp(nextSeq++, atMillis, parkingGeneration)

    @Synchronized
    fun latestOf(kind: SignalKind): VehicleReading? = latest[kind]

    /** 현재 값 조회를 연다. 구독 등록보다 먼저 불러, 등록 직후 들어오는 값도 모아 둔다. */
    @Synchronized
    fun beginBaseline(start: ObservationStamp) {
        check(open == null) { "현재 값 조회는 한 번에 하나만 연다" }
        open = start
        buffer.clear()
    }

    /** 구독 콜백으로 받은 값. @return 지금 내보낼 변경(모아 두었거나, 오래됐거나, 같은 값이면 null). */
    @Synchronized
    fun onListener(kind: SignalKind, reading: VehicleReading, stamp: ObservationStamp): VehicleChange? {
        if (open != null) {
            buffer += VehicleChange(kind, reading, stamp)
            return null
        }
        return acceptListener(VehicleChange(kind, reading, stamp))
    }

    /** 조회가 끝났다. [answers]에는 조회한 신호의 답과, 판정에서 뺀 신호의 고정 상태를 함께 넣는다. */
    @Synchronized
    fun completeBaseline(answers: Map<SignalKind, VehicleReading>): BaselineResult {
        val start = checkNotNull(open) { "열린 조회가 없다" }
        open = null
        for ((kind, reading) in answers) {
            latest[kind] = reading
            baselineSeq[kind] = start.seq
        }
        status = if (alive(answers)) LinkStatus.RECEIVING else LinkStatus.UNAVAILABLE
        val pending = drainBuffer()
        return BaselineResult(start, answers.toMap(), pending)
    }

    /** 조회가 취소됐다. 모아 둔 변경은 버리지 않고 평소대로 내보낸다. */
    @Synchronized
    fun abortBaseline(): List<VehicleChange> {
        if (open == null) return emptyList()
        open = null
        return drainBuffer()
    }

    /**
     * 주기적 확인의 답. 하나도 읽지 못했으면 모든 신호를 일시 불가로 돌리고, 끊겼다가 다시 읽히면 값은 반영하지 않고
     * [ProbeResult.recovered]로 알린다(호출한 쪽이 기준값을 다시 읽는다).
     */
    @Synchronized
    fun onProbe(
        start: ObservationStamp,
        answers: Map<SignalKind, VehicleReading>,
        answeredAt: ObservationStamp,
    ): ProbeResult {
        check(open == null) { "현재 값 조회 중에는 주기적 확인을 하지 않는다" }
        val aliveNow = alive(answers)
        if (aliveNow && status == LinkStatus.UNAVAILABLE) {
            status = LinkStatus.RECEIVING
            return ProbeResult(emptyList(), recovered = true)
        }
        status = if (aliveNow) LinkStatus.RECEIVING else LinkStatus.UNAVAILABLE
        val changes = ArrayList<VehicleChange>()
        for ((kind, reading) in answers) {
            if ((lastListenerSeq[kind] ?: 0L) > start.seq) continue // 확인을 시작한 뒤의 구독 값이 더 새롭다
            if ((baselineSeq[kind] ?: 0L) > start.seq) continue // 더 나중 조회의 답이 이미 반영됐다
            baselineSeq[kind] = start.seq
            if (latest[kind] == reading) continue
            latest[kind] = reading
            changes += VehicleChange(kind, reading, answeredAt)
        }
        return ProbeResult(changes, recovered = false)
    }

    private fun drainBuffer(): List<VehicleChange> {
        val out = buffer.sortedBy { it.stamp.seq }.mapNotNull(::acceptListener)
        buffer.clear()
        return out
    }

    private fun acceptListener(change: VehicleChange): VehicleChange? {
        val seq = change.stamp.seq
        if (seq < (baselineSeq[change.kind] ?: 0L)) return null // 조회의 답보다 오래된 관측
        if (seq < (lastListenerSeq[change.kind] ?: 0L)) return null // 뒤바뀌어 도착한 더 오래된 구독 값
        lastListenerSeq[change.kind] = seq
        if (latest[change.kind] == change.reading) return null
        latest[change.kind] = change.reading
        return change
    }

    private fun alive(answers: Map<SignalKind, VehicleReading>): Boolean =
        answers.values.any { it.availability == SignalAvailability.AVAILABLE }
}
