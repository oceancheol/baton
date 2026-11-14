package app.baton.parking.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.baton.core.departure.DepartureState
import app.baton.parking.BatonUiState
import app.baton.parking.SignalLine

/** 화면 1 — 나가는 차. */
@Composable
fun LeavingScreen(state: BatonUiState, onSuppress: () -> Unit) {
    val snap = state.departure
    DesignCanvas {
        TopBar(roleLabel = "나가는 차", pill = "양보 기록 ${state.yieldCount}회")
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(48.u)) {
            Panel(
                modifier = Modifier.weight(1.25f).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Cap("${state.myZone}구역 · ${state.myRow}번 줄")
                    val (first, second, active) = headline(snap.state, snap.suppressed)
                    Headline(first, second, if (active) BatonColors.Go else BatonColors.Sub)
                    // 최소 신호(착좌·위치)를 읽지 못하면 자동 예고가 꺼진 이유를 한 줄로 알린다.
                    snap.autoAnnounce.reason?.let { Foot(it, Modifier.padding(top = 10.u)) }
                }
                Column {
                    Cap("이 자리로 안내받는 차")
                    BigNumber(listOf(state.guidedCars.toString() to "대"))
                }
                // 차가 움직이는 중(출차 임박 이후, 또는 이동이 관측된 뒤)에는 누를 수 없다.
                SuppressButton(enabled = snap.canSuppress, suppressed = snap.suppressed, onClick = onSuppress)
            }
            Panel(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Cap("차가 스스로 읽은 신호 (지금 값 · 조작 없음)", Modifier.padding(bottom = 10.u))
                state.signalLines.forEach { SignalRow(it) }
                Spacer(Modifier.height(28.u))
                Foot("예고를 시작하고 2분 안에 자리를 벗어나지 않으면 자동 취소됩니다 (단계가 바뀌어도 다시 세지 않아요)")
                state.cancelInSeconds?.let {
                    Foot("자동 취소까지 ${it}초", Modifier.padding(top = 8.u))
                }
            }
        }
    }
}

private fun headline(state: DepartureState, suppressed: Boolean): Triple<String, String, Boolean> = when {
    state == DepartureState.VACATED -> Triple("자리를", "비웠어요", true)
    suppressed -> Triple("이번엔", "예고 안 함", false)
    state == DepartureState.PARKED -> Triple("자리", "주차 중", false)
    state == DepartureState.BOARDING -> Triple("탑승", "확인 중", false)
    state == DepartureState.ANNOUNCING -> Triple("자리", "예고 중", true)
    else -> Triple("출차", "임박", true)
}

/** 읽은 신호 한 줄. 점은 그 조건이 지금 실제로 관측될 때만 채우고, 미지원·대기·일시 불가는 회색으로 적는다. */
@Composable
private fun SignalRow(line: SignalLine) {
    InfoRow(
        left = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dot = Modifier.size(26.u).clip(CircleShape)
                Box(
                    when {
                        line.muted -> dot.border(3.u, BatonColors.Mute, CircleShape)
                        line.on -> dot.background(BatonColors.Go)
                        else -> dot.border(3.u, BatonColors.Sub, CircleShape)
                    },
                )
                Spacer(Modifier.width(22.u))
                T(line.label, 36, color = if (line.muted) BatonColors.Mute else BatonColors.Ink, maxLines = 1)
            }
        },
        right = { T(line.value, 28, color = if (line.muted) BatonColors.Mute else BatonColors.Sub, maxLines = 1) },
    )
}

/** 정차 중일 때만 쓰는 버튼(움직이는 중에는 꺼진다). 이 화면에서 누를 수 있는 것은 이것 하나다. */
@Composable
private fun SuppressButton(enabled: Boolean, suppressed: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.u)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(2.u, BatonColors.Line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 44.u, vertical = 30.u),
        contentAlignment = Alignment.Center,
    ) {
        T(
            text = if (suppressed) "이번 주차는 예고하지 않습니다" else "이번엔 예고 안 함",
            size = 38,
            color = if (enabled) BatonColors.Ink else BatonColors.Sub,
            weight = FontWeight.Bold,
            align = TextAlign.Center,
        )
    }
}
