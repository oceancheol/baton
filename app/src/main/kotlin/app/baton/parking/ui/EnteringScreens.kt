package app.baton.parking.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import app.baton.core.match.ZoneSummary
import app.baton.parking.BatonUiState
import kotlin.math.min

// 들어오는 차 화면 세 가지에는 누르는 것을 두지 않는다 (주행 중 터치 금지).

/** 화면 2 — 들어오는 차, 대기. */
@Composable
fun EnteringWaitingScreen(state: BatonUiState) {
    DesignCanvas {
        TopBar(roleLabel = "들어오는 차", pill = "주차장 진입 감지 · 대기 등록됨")
        T(
            text = buildAnnotatedString {
                append("자리 위치는 공개하지 않아요. ")
                withStyle(SpanStyle(color = BatonColors.Go)) { append("차례가 오면 한 대만 안내해요.") }
            },
            size = 60,
        )
        Spacer(Modifier.height(48.u))
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.u)) {
            state.zones.forEach { zone ->
                ZoneCard(
                    zone = zone,
                    recommended = zone.zoneId == state.recommendedZone,
                    carsAhead = state.carsAheadOfMe,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        Spacer(Modifier.height(48.u))
        // 시안에서는 버튼 모양이지만 안내문이다. 누를 수 없다.
        val shape = RoundedCornerShape(28.u)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.u, BatonColors.Go, shape)
                .padding(horizontal = 44.u, vertical = 30.u),
            contentAlignment = Alignment.Center,
        ) {
            T(
                "${state.recommendedZone}구역으로 안내합니다 · 구역 변경은 주차(P) 상태에서만 할 수 있어요",
                38, color = BatonColors.Go, weight = FontWeight.Bold, align = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ZoneCard(zone: ZoneSummary, recommended: Boolean, carsAhead: Int, modifier: Modifier) {
    val shape = RoundedCornerShape(32.u)
    val accent = if (recommended) BatonColors.Go else BatonColors.Ink
    Column(
        modifier = modifier
            .clip(shape)
            .background(BatonColors.Panel)
            .border(2.u, if (recommended) BatonColors.Go else BatonColors.Line, shape)
            .padding(horizontal = 40.u, vertical = 36.u),
    ) {
        T(
            if (recommended) "${zone.zoneId}구역 · 추천" else "${zone.zoneId}구역",
            36, color = accent, weight = FontWeight.Bold,
        )
        Row {
            T(
                zone.upcomingSpots.toString(), 120,
                modifier = Modifier.alignByBaseline(),
                color = accent, weight = FontWeight.ExtraBold, lineHeight = 1.05f,
            )
            T(
                "자리", 56,
                modifier = Modifier.alignByBaseline().padding(start = 10.u),
                color = BatonColors.Sub, weight = FontWeight.Bold, lineHeight = 1.0f,
            )
        }
        // 건수는 실제 집계 값이다. 예고가 없으면 "관측된 출차 예고 없음".
        val spots = if (zone.upcomingSpots > 0) "출차 예고 ${zone.upcomingSpots}건" else "관측된 출차 예고 없음"
        val waiting = when {
            !recommended -> "대기 ${zone.waitingCars}대"
            carsAhead == 0 && zone.upcomingSpots > 0 -> "내 차가 안내 대상"
            else -> "내 차 앞 대기 ${carsAhead}대"
        }
        T("$spots · $waiting", 30, Modifier.padding(top = 6.u), color = BatonColors.Sub)
    }
}

/** 화면 3 — 들어오는 차, 우선 안내. */
@Composable
fun EnteringGuidedScreen(state: BatonUiState) {
    DesignCanvas {
        TopBar(roleLabel = "들어오는 차", pill = "우선 안내 중", pillActive = true)
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(48.u)) {
            Panel(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    val row = state.guidedRow
                    // 자리 위치의 신뢰도가 낮으면 줄은 알리지 않고 구역까지만 안내한다.
                    Cap(
                        if (row != null) "${state.guidedZone}구역 · ${row}번 줄"
                        else "${state.guidedZone}구역 · 구역까지만 안내 (위치 정확도 낮음)",
                    )
                    Headline("앞 차가", "나갑니다")
                }
                Column {
                    Cap("서행 기준 도착까지")
                    BigNumber(listOf(state.etaSeconds.toString() to "초"))
                }
                Foot(
                    "이 안내는 ${state.offerRemainingSeconds}초 더 유효해요 · 이 차에만 안내합니다 · " +
                        "자리 보장은 아니에요. 다른 차가 먼저 주차하면 다음 자리로 안내합니다",
                )
            }
            Panel(modifier = Modifier.weight(1.15f).fillMaxHeight(), padding = 28) {
                val row = state.guidedRow
                if (row != null) {
                    RouteMap(row = row, progress = state.routeProgress)
                } else {
                    ZoneOnly(state.guidedZone)
                }
            }
        }
    }
}

/** 구역까지만 안내할 때: 줄과 자리 그림을 보여 주지 않는다. */
@Composable
private fun ZoneOnly(zone: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        T("${zone}구역", 200, color = BatonColors.Go, weight = FontWeight.ExtraBold, lineHeight = 1.0f)
        Spacer(Modifier.height(24.u))
        T("이 구역 안에서 곧 자리가 납니다", 40, align = TextAlign.Center)
        T("줄 위치는 정확하지 않아 안내하지 않아요", 30, color = BatonColors.Sub, align = TextAlign.Center)
    }
}

/** 단순 경로 그림. 시안의 SVG(920×800)를 그대로 옮겼다. */
@Composable
private fun RouteMap(row: Int, progress: Float) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier = Modifier.fillMaxSize()) {
        val k = min(size.width / 920f, size.height / 800f)
        val ox = (size.width - 920f * k) / 2f
        val oy = (size.height - 800f * k) / 2f
        fun p(x: Float, y: Float) = Offset(ox + x * k, oy + y * k)

        // 주차 줄 세 개
        val blocks = listOf(Triple(60f, 800f, 7), Triple(330f, 560f, 5), Triple(600f, 800f, 7))
        blocks.forEach { (top, width, dividers) ->
            drawRoundRect(BatonColors.Slot, p(60f, top), Size(width * k, 150f * k), CornerRadius(16f * k))
            drawRoundRect(
                BatonColors.SlotLine, p(60f, top), Size(width * k, 150f * k), CornerRadius(16f * k),
                style = Stroke(3f * k),
            )
            for (i in 1..dividers) {
                val x = 60f + 100f * i
                drawLine(BatonColors.SlotLine, p(x, top), p(x, top + 150f), 3f * k)
            }
        }

        // 예고 자리
        drawRoundRect(BatonColors.GoDark, p(664f, 64f), Size(92f * k, 142f * k), CornerRadius(12f * k))
        drawRoundRect(
            BatonColors.Go, p(664f, 64f), Size(92f * k, 142f * k), CornerRadius(12f * k),
            style = Stroke(5f * k),
        )
        centeredText(measurer, "예고", p(710f, 100f), 30f * k, BatonColors.Go, FontWeight.ExtraBold)
        centeredText(measurer, "자리", p(710f, 140f), 30f * k, BatonColors.Go, FontWeight.ExtraBold)

        // 경로: 내 차 → 오른쪽 → 위. 강조색을 하나만 쓰려고 시안의 파란색 대신 글자색으로 그린다.
        val route = Path().apply {
            moveTo(p(130f, 540f).x, p(130f, 540f).y)
            lineTo(p(710f, 540f).x, p(710f, 540f).y)
            lineTo(p(710f, 250f).x, p(710f, 250f).y)
        }
        drawPath(
            route, BatonColors.Ink,
            style = Stroke(
                width = 16f * k, cap = StrokeCap.Round, join = StrokeJoin.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * k, 34f * k)),
            ),
        )

        // 내 차: 경로(가로 580 + 세로 290)를 따라 움직인다.
        val travelled = progress.coerceIn(0f, 1f) * 870f
        val car = if (travelled <= 580f) p(130f + travelled, 540f) else p(710f, 540f - (travelled - 580f))
        drawCircle(BatonColors.Ink, 26f * k, car)
        centeredText(measurer, "내 차", Offset(car.x, car.y + 34f * k), 30f * k, BatonColors.Ink, FontWeight.Bold)
        leftText(measurer, "${row}번 줄", p(760f, 258f), 28f * k, BatonColors.Sub, FontWeight.SemiBold)
    }
}

private fun DrawScope.centeredText(
    measurer: TextMeasurer, text: String, top: Offset, sizePx: Float, color: Color, weight: FontWeight,
) {
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = sizePx.toSp(), fontWeight = weight))
    drawText(layout, topLeft = Offset(top.x - layout.size.width / 2f, top.y))
}

private fun DrawScope.leftText(
    measurer: TextMeasurer, text: String, topLeft: Offset, sizePx: Float, color: Color, weight: FontWeight,
) {
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = sizePx.toSp(), fontWeight = weight))
    drawText(layout, topLeft = topLeft)
}

/** 화면 4 — 주차 완료. */
@Composable
fun ParkedScreen(state: BatonUiState) {
    val place = "${state.parkedZone}구역 · ${state.parkedRow}번 줄"
    DesignCanvas {
        TopBar(roleLabel = "들어오는 차", pill = "주차 완료", pillActive = true)
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(48.u)) {
            Panel(
                modifier = Modifier.weight(1.2f).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Cap(place)
                    Headline("돌지 않고", "바로 주차했어요")
                }
                Column {
                    Cap("진입부터 주차까지")
                    val minutes = state.parkedElapsedSeconds / 60
                    val seconds = state.parkedElapsedSeconds % 60
                    BigNumber(
                        if (minutes > 0) listOf("$minutes" to "분", "$seconds" to "초")
                        else listOf("$seconds" to "초"),
                    )
                }
                Foot("이 주차장에서 내 지난 기록 평균 6분 20초 (모의 값)")
            }
            Panel(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Cap("내 차 위치를 이번 실행 동안 기억해요", Modifier.padding(bottom = 10.u))
                InfoRow(left = { T("구역 · 줄", 38) }, right = { T(place, 38, color = BatonColors.Go) })
                InfoRow(left = { T("나갈 때", 38) }, right = { T("자리 예고 자동", 38) })
                InfoRow(
                    left = { T("자리를 내준 앞 차에게", 38) },
                    right = { T("양보 기록 +1", 38, color = BatonColors.Go) },
                )
                Spacer(Modifier.height(28.u))
                Foot("앱을 끄면 지워집니다(기기 저장은 다음 단계) · 차량 번호와 운전자 정보는 다루지 않습니다")
            }
        }
    }
}
