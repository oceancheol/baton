package app.baton.parking.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** 시안(screens.html)의 색. 강조색은 [Go] 하나만 쓴다. */
object BatonColors {
    val Bg = Color(0xFF0B0F14)
    val Panel = Color(0xFF141B24)
    val Line = Color(0xFF243040)
    val Ink = Color(0xFFF2F5F8)
    val Sub = Color(0xFF8B99A8)
    /** 미지원·미수신 신호처럼 '값이 없음'을 나타내는 회색. */
    val Mute = Color(0xFF566270)
    val Go = Color(0xFF3DDC97)
    val GoDark = Color(0xFF12382A)
    val Slot = Color(0xFF1B2430)
    val SlotLine = Color(0xFF2C3A4C)
}

/** 시안 기준 화면 크기(1920×1080). 실제 화면에는 비율을 지켜 맞춘다. */
const val DESIGN_WIDTH = 1920f
const val DESIGN_HEIGHT = 1080f

/** 시안 1px 이 몇 dp 인지. */
val LocalDesignUnit = compositionLocalOf { 0.5f }

/** 시안 px → dp. */
val Int.u: Dp
    @Composable @ReadOnlyComposable
    get() = (this * LocalDesignUnit.current).dp

/** 시안 px → 글자 크기. 시스템 글자 크기 설정과 무관하게 화면 비율만 따른다. */
val Int.us: TextUnit
    @Composable @ReadOnlyComposable
    get() {
        val dp = (this * LocalDesignUnit.current).dp
        return with(LocalDensity.current) { dp.toSp() }
    }

/** 주어진 영역 안에 16:9 시안 화면을 비율대로 넣는다. */
@Composable
fun DesignCanvas(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(BatonColors.Bg),
        contentAlignment = Alignment.Center,
    ) {
        val unit = min(maxWidth.value / DESIGN_WIDTH, maxHeight.value / DESIGN_HEIGHT)
        CompositionLocalProvider(LocalDesignUnit provides unit) {
            Column(
                modifier = Modifier
                    .size((DESIGN_WIDTH * unit).dp, (DESIGN_HEIGHT * unit).dp)
                    .padding(horizontal = 72.u, vertical = 56.u),
                content = content,
            )
        }
    }
}

/**
 * 한글이 낱말 중간에서 줄바꿈되지 않게 한다(띄어쓰기 단위로만 줄을 나눈다).
 * API 34 에서는 줄바꿈 설정으로 되지 않아, 낱말 안 글자 사이에 폭 없는 이음 문자(U+2060)를 넣는다.
 */
fun keepWords(text: String): String =
    text.split(' ').joinToString(" ") { word -> word.toList().joinToString("\u2060") }

@Composable
fun T(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    color: Color = BatonColors.Ink,
    weight: FontWeight = FontWeight.SemiBold,
    lineHeight: Float = 1.3f,
    tracking: Float = 0f,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = keepWords(text),
        modifier = modifier,
        color = color,
        fontSize = size.us,
        fontWeight = weight,
        lineHeight = (size * lineHeight).toInt().us,
        letterSpacing = (size * tracking).toInt().us,
        textAlign = align,
        maxLines = maxLines,
    )
}

@Composable
fun T(
    text: AnnotatedString,
    size: Int,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.ExtraBold,
    lineHeight: Float = 1.2f,
) {
    Text(
        text = text,
        modifier = modifier,
        color = BatonColors.Ink,
        fontSize = size.us,
        fontWeight = weight,
        lineHeight = (size * lineHeight).toInt().us,
    )
}

/** 작은 설명 글 (.cap). */
@Composable
fun Cap(text: String, modifier: Modifier = Modifier) = T(text, 32, modifier, color = BatonColors.Sub)

/** 아래 주석 글 (.foot). */
@Composable
fun Foot(text: String, modifier: Modifier = Modifier) = T(text, 28, modifier, color = BatonColors.Sub)

/** 두 줄 큰 제목 (h1). 둘째 줄이 강조색. */
@Composable
fun Headline(first: String, second: String, secondColor: Color = BatonColors.Go) {
    Column(modifier = Modifier.padding(top = 14.u)) {
        T(first, 104, weight = FontWeight.ExtraBold, lineHeight = 1.1f, tracking = -0.03f)
        T(second, 104, color = secondColor, weight = FontWeight.ExtraBold, lineHeight = 1.1f, tracking = -0.03f)
    }
}

/** 큰 숫자 하나와 단위 (.big + .unit). */
@Composable
fun BigNumber(parts: List<Pair<String, String>>, color: Color = BatonColors.Ink) {
    Row {
        parts.forEachIndexed { index, (number, unit) ->
            if (index > 0) Spacer(Modifier.width(24.u))
            T(
                number, 200,
                modifier = Modifier.alignByBaseline(),
                color = color, weight = FontWeight.ExtraBold, lineHeight = 1.0f, tracking = -0.04f,
            )
            T(
                unit, 56,
                modifier = Modifier.alignByBaseline().padding(start = 10.u),
                color = BatonColors.Sub, weight = FontWeight.Bold, lineHeight = 1.0f,
            )
        }
    }
}

/** 화면 위 줄: "Baton · 역할" 과 오른쪽 알약 표시. */
@Composable
fun TopBar(roleLabel: String, pill: String, pillActive: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row {
            T("Baton", 30, weight = FontWeight.Bold)
            T(" · $roleLabel", 30, color = BatonColors.Sub)
        }
        val color = if (pillActive) BatonColors.Go else BatonColors.Sub
        Box(
            modifier = Modifier
                .border(2.u, if (pillActive) BatonColors.Go else BatonColors.Line, CircleShape)
                .padding(horizontal = 26.u, vertical = 10.u),
        ) {
            T(pill, 26, color = color)
        }
    }
    Spacer(Modifier.height(44.u))
}

/** 내용 상자 (.panel). */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Int = 48,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(36.u)
    Column(
        modifier = modifier
            .clip(shape)
            .background(BatonColors.Panel)
            .border(2.u, BatonColors.Line, shape)
            .padding(padding.u),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** 구분선이 있는 한 줄 (.row). */
@Composable
fun InfoRow(last: Boolean = false, left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 26.u),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        left()
        right()
    }
    if (!last) {
        Box(Modifier.fillMaxWidth().height(2.u).background(BatonColors.Line))
    }
}
