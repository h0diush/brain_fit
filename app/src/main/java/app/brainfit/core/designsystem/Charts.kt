package app.brainfit.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Мини-график значений 0..1 без осей: линия проявляется слева направо, последняя точка выделена.
 * Описание для TalkBack обязательно — сама линия ничего не говорит.
 */
@Composable
fun Sparkline(values: List<Double>, description: String, modifier: Modifier = Modifier) {
    val colors = BrainTheme.colors
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(values) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(Motion.BASE, easing = Motion.Enter))
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        if (values.size < 2) return@Canvas
        val pad = 4.dp.toPx()
        val stepX = (size.width - 2 * pad) / (values.size - 1)
        val points = values.mapIndexed { i, v ->
            Offset(pad + i * stepX, pad + (1f - v.coerceIn(0.0, 1.0).toFloat()) * (size.height - 2 * pad))
        }
        // Опорная линия на 50 %, чтобы было видно, высоко или низко.
        drawLine(colors.surfaceSecondary, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
        clipRect(right = size.width * reveal.value) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, colors.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (reveal.value >= 1f) drawCircle(colors.accent, 3.dp.toPx(), points.last())
    }
}

/**
 * Клетки календаря активности: строки × столбцы, в клетке — число сессий за день; null — дня нет
 * в периоде. Насыщенность — 0, 1, 2, 3 и больше сессий. Подписи столбцов сверху.
 */
@Composable
fun ActivityGrid(
    rows: List<List<Int?>>,
    columnLabels: List<String>,
    description: String,
    modifier: Modifier = Modifier,
    rowLabels: List<String>? = null,
    /** Ширина клетки к высоте: 1 — квадрат, больше — низкая полоска (для одной недели). */
    cellAspect: Float = 1f,
) {
    val colors = BrainTheme.colors
    val shades = listOf(colors.surfaceSecondary, colors.accent.copy(alpha = 0.4f), colors.accent.copy(alpha = 0.7f), colors.accent)
    Column(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (rowLabels != null) Box(Modifier.width(LabelWidth))
            columnLabels.forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
        }
        rows.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (rowLabels != null) {
                    Text(
                        rowLabels.getOrElse(r) { "" },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        modifier = Modifier.width(LabelWidth),
                        maxLines = 1,
                    )
                }
                row.forEach { count ->
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(cellAspect)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .let { if (count != null) it.background(shades[count.coerceIn(0, 3)]) else it },
                    )
                }
            }
        }
        ShadeLegend(shades)
    }
}

private val LabelWidth = 36.dp

@Composable
private fun ShadeLegend(shades: List<Color>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("0", style = MaterialTheme.typography.labelSmall, color = BrainTheme.colors.textSecondary)
        shades.forEach { shade ->
            Canvas(Modifier.size(10.dp)) { drawRoundRect(shade, cornerRadius = CornerRadius(2.dp.toPx())) }
        }
        Text("3+ сессии", style = MaterialTheme.typography.labelSmall, color = BrainTheme.colors.textSecondary)
    }
}
