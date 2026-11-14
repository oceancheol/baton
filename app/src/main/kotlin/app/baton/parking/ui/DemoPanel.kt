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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.baton.parking.BatonUiState
import app.baton.parking.BatonViewModel
import app.baton.parking.DemoScenario
import app.baton.parking.Role

/** 시연 패널(개발용). 역할·고정 시나리오·모의 신호 스위치·관찰 영역·이벤트 로그. */
@Composable
fun DemoPanel(state: BatonUiState, vm: BatonViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(BatonColors.Panel)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label("시연 패널 (개발용)", BatonColors.Ink, bold = true)

        Section("역할 (바꿔도 세계는 그대로)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Role.entries.forEach { role ->
                PanelButton(role.label, Modifier.weight(1f), selected = state.role == role) { vm.selectRole(role) }
            }
        }

        Section("고정 시나리오" + (state.runningScenario?.let { " · 진행 중: $it" } ?: ""))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScenarioButton(DemoScenario.NORMAL_DEPARTURE, state, vm, Modifier.weight(1f))
            ScenarioButton(DemoScenario.LUGGAGE_ONLY, state, vm, Modifier.weight(1f))
        }
        ScenarioButton(DemoScenario.NO_MOVE_CANCEL, state, vm)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScenarioButton(DemoScenario.PASS_AND_RETURN, state, vm, Modifier.weight(1f))
            PanelButton("다음 바통", Modifier.weight(1f)) { vm.nextBaton() }
        }

        Section("현재 상태")
        if (state.role == Role.LEAVING) {
            val d = state.departure
            Label("${d.state} · ${d.state.label}", BatonColors.Go, bold = true)
            Label(
                buildString {
                    append("안내받는 차 ${state.guidedCars}대")
                    state.cancelInSeconds?.let { append(" · 자동 취소까지 ${it}초") }
                    if (d.suppressed) append(" · 예고 안 함")
                },
            )
            Label("주차 세대 ${d.parkingGeneration} · 예고 세대 ${d.announcementGeneration ?: "-"} · 거부한 옛 신호 ${d.rejectedEventCount}건")
            d.autoAnnounce.reason?.let { Label(it, BatonColors.Ink) }
        } else {
            Label("들어오는 차 · ${state.enteringPhase.label}", BatonColors.Go, bold = true)
            Label("도착 예상 ${state.etaSeconds}초 · 내 차 앞 대기 ${state.carsAheadOfMe}대")
        }

        if (state.role == Role.LEAVING) {
            Section("모의 신호 · 모의 위치")
            val s = state.fakeSignals
            if (s == null) {
                Label("실제 신호 소스 사용 중: 스위치 없음")
            } else {
                SwitchRow("운전석 문 열림", s.doorOpen) { vm.setDoorOpen(it) }
                SwitchRow("운전석 착좌", s.seated) { vm.setSeated(it) }
                SwitchRow("안전벨트 체결", s.belted) { vm.setBelted(it) }
                SwitchRow("주차 브레이크 경고등", s.parkingBrakeOn) { vm.setParkingBrakeOn(it) }
                SwitchRow("브레이크 페달", s.pedalPressed) { vm.setPedalPressed(it) }
                SwitchRow("이동 (20m 벗어남)", s.moved) { vm.setMoved(it) }
                SwitchRow("위치 정확도 낮음", s.lowQualityLocation) { vm.setLowQualityLocation(it) }
                SwitchRow("위치 신호 끊김", s.locationLost) { vm.setLocationLost(it) }
            }
        } else {
            Section("모의 상대 차 · 내 차")
            PanelButton("상대 차 출차 임박 당기기") { vm.peerDepartNow() }
            PanelButton("상대 차 예고 추가") { vm.addPeerDeparture() }
            PanelButton("지나침 (주차 안 함)") { vm.passWithoutParking() }
            PanelButton("지금 주차 완료") { vm.parkNow() }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton("시간 +30초", Modifier.weight(1f)) { vm.skipTime(30) }
            PanelButton("처음부터", Modifier.weight(1f)) { vm.restartScenario() }
        }

        Section("관찰 · 상대 차와 안내 결과")
        val o = state.observe
        Small(o.waitingPeers)
        if (o.activeLines.isEmpty()) Small("진행 중인 안내 없음")
        o.activeLines.forEach { Small(it, BatonColors.Go) }
        o.recordLines.forEach { Small(it) }
        Section("관찰 · 측정")
        Small(o.leadTime, BatonColors.Ink)
        Small(o.misguided, BatonColors.Ink)
        Small(o.parked, BatonColors.Ink)

        Section("이벤트 로그 (최신이 위)")
        state.log.forEach { line ->
            Text(
                text = line,
                color = BatonColors.Sub,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ScenarioButton(
    scenario: DemoScenario,
    state: BatonUiState,
    vm: BatonViewModel,
    modifier: Modifier = Modifier,
) {
    PanelButton(scenario.label, modifier, selected = state.runningScenario == scenario.label) {
        vm.runScenario(scenario)
    }
}

@Composable
private fun Small(text: String, color: Color = BatonColors.Sub) {
    Text(text = keepWords(text), color = color, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(4.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(BatonColors.Line))
    Label(title, BatonColors.Sub, bold = true)
}

@Composable
private fun Label(text: String, color: Color = BatonColors.Sub, bold: Boolean = false) {
    Text(
        text = text,
        color = color,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = BatonColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BatonColors.Bg,
                checkedTrackColor = BatonColors.Go,
                uncheckedThumbColor = BatonColors.Sub,
                uncheckedTrackColor = BatonColors.Bg,
                uncheckedBorderColor = BatonColors.Line,
            ),
        )
    }
}

@Composable
fun PanelButton(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, if (selected) BatonColors.Go else BatonColors.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) BatonColors.Go else BatonColors.Ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
