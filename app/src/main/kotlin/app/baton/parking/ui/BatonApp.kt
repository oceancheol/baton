package app.baton.parking.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.baton.parking.BatonUiState
import app.baton.parking.BatonViewModel
import app.baton.parking.EnteringPhase
import app.baton.parking.Role

@Composable
fun BatonApp(vm: BatonViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = BatonColors.Go,
            background = BatonColors.Bg,
            surface = BatonColors.Panel,
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize().background(BatonColors.Bg).safeDrawingPadding()) {
            SourceStrip(state, onTogglePanel = vm::togglePanel)
            Row(modifier = Modifier.weight(1f)) {
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        state.role == Role.LEAVING ->
                            LeavingScreen(state, onSuppress = vm::suppressAnnouncement)
                        state.enteringPhase == EnteringPhase.WAITING -> EnteringWaitingScreen(state)
                        state.enteringPhase == EnteringPhase.GUIDED -> EnteringGuidedScreen(state)
                        else -> ParkedScreen(state)
                    }
                }
                if (state.panelOpen) {
                    Box(Modifier.width(1.dp).fillMaxSize().background(BatonColors.Line))
                    DemoPanel(state, vm, Modifier.width(300.dp))
                }
            }
        }
    }
}

/**
 * 화면 맨 위의 얇은 줄. 모의 요소(신호 / 위치 / 상대 차 / 시간 전진)를 하나씩 따로 보여 준다.
 * 신호가 실제 SDK 로 바뀌어도 나머지 모의 요소는 계속 보인다.
 * 시연 패널 여닫기는 개발용이며, 들어오는 차 화면 본문에는 누르는 것이 없다.
 */
@Composable
private fun SourceStrip(state: BatonUiState, onTogglePanel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(34.dp).background(BatonColors.Panel).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.mockChips.forEach { chip ->
                Box(
                    modifier = Modifier
                        .border(1.dp, BatonColors.Ink, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(chip, color = BatonColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            if ("모의 신호" !in state.mockChips) {
                Text("신호: ${state.signalSourceName}", color = BatonColors.Sub, fontSize = 12.sp, maxLines = 1)
            }
        }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onTogglePanel)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = if (state.panelOpen) "시연 패널 닫기" else "시연 패널 열기 (개발용)",
                color = BatonColors.Sub,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}
