package app.brainfit.feature.play.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.brainfit.core.designsystem.BrainTheme
import app.brainfit.core.designsystem.MutedText
import app.brainfit.core.designsystem.onPressDown
import app.brainfit.games.doubledecision.DdInput
import app.brainfit.games.doubledecision.DdLayout
import app.brainfit.games.doubledecision.DdPhase
import app.brainfit.games.doubledecision.DdShape
import app.brainfit.games.doubledecision.DdState
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Размеры фигур в долях половины поля. */
private const val CENTER_LONG = 0.30f
private const val TARGET_RADIUS = 0.065f
private const val DISTRACTOR_RADIUS = 0.075f
private const val MASK_CELLS = 28
private val TAP_SIZE = 64.dp

private val DdShape.title: String get() = if (this == DdShape.WIDE) "Вытянута поперёк" else "Вытянута вдоль"

@Composable
fun DoubleDecisionContent(state: DdState, onInput: (DdInput) -> Unit) {
    val colors = BrainTheme.colors
    Column(Modifier.fillMaxSize().padding(bottom = 8.dp)) {
        MutedText(
            "Показ ${minOf(state.index + 1, state.params.trials.size)} из ${state.params.trials.size}",
            Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        Field(state, onInput)
        // Подсказка и кнопки — в области постоянной высоты, чтобы поле не прыгало между фазами.
        Column(
            modifier = Modifier.fillMaxWidth().height(150.dp).padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val feedback = state.feedback
            when (state.phase) {
                DdPhase.CENTER -> {
                    Text("Какая фигура была в центре?", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DdShape.entries.forEach { shape ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(80.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(colors.surfaceSecondary)
                                    .semantics { contentDescription = shape.title }
                                    .onPressDown { onInput(DdInput.Center(shape)) },
                                contentAlignment = Alignment.Center,
                            ) {
                                // На кнопке фигура крупнее: длинная сторона — 80 % значка.
                                Canvas(Modifier.size(56.dp)) {
                                    drawCenterShape(shape, state.params.aspect.toFloat(), size.width * 0.8f / (2 * CENTER_LONG), colors.textPrimary)
                                }
                            }
                        }
                    }
                }

                DdPhase.PERIPHERY -> Text("Где был кружок?", style = MaterialTheme.typography.titleMedium)

                DdPhase.FEEDBACK -> if (feedback != null) {
                    Text(
                        text = when {
                            feedback.centerOk && feedback.peripheryOk -> "Верно"
                            !feedback.centerOk && !feedback.peripheryOk -> "Ошибка: и фигура, и кружок"
                            !feedback.centerOk -> "Ошибка: фигура в центре"
                            else -> "Ошибка: кружок был здесь"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (feedback.centerOk && feedback.peripheryOk) colors.accent else colors.error,
                        textAlign = TextAlign.Center,
                    )
                }

                else -> MutedText("Смотри в центр")
            }
        }
    }
}

@Composable
private fun Field(state: DdState, onInput: (DdInput) -> Unit) {
    val colors = BrainTheme.colors
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.large)
            .background(colors.card),
    ) {
        val side = maxWidth
        val spec = state.spec
        Canvas(Modifier.fillMaxSize()) {
            val half = size.width / 2
            val c = Offset(half, half)
            fun at(p: Pair<Double, Double>) = Offset(c.x + p.first.toFloat() * half, c.y + p.second.toFloat() * half)
            when (state.phase) {
                DdPhase.FIXATION, DdPhase.CENTER -> drawCross(c, half * 0.06f, colors.textSecondary)

                DdPhase.STIMULUS -> if (spec != null) {
                    drawCenterShape(spec.center, state.params.aspect.toFloat(), half, colors.textPrimary)
                    drawCircle(colors.textPrimary, TARGET_RADIUS * half, at(DdLayout.target(spec.target)))
                    spec.distractors.forEach { drawTriangle(at(DdLayout.slot(it)), DISTRACTOR_RADIUS * half, colors.textPrimary) }
                }

                // Маска-шум: своя для каждой пробы, закрывает и центр, и периферию.
                DdPhase.MASK -> if (spec != null) {
                    val rng = Random(spec.maskSeed)
                    val cell = size.width / MASK_CELLS
                    val tones = listOf(colors.surfaceSecondary, colors.outline, colors.textSecondary)
                    for (row in 0 until MASK_CELLS) for (col in 0 until MASK_CELLS) {
                        drawRect(tones[rng.nextInt(tones.size)], Offset(col * cell, row * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                }

                DdPhase.PERIPHERY -> Unit

                DdPhase.FEEDBACK -> state.feedback?.let {
                    drawCircle(if (it.peripheryOk) colors.accent else colors.error, TARGET_RADIUS * half, at(DdLayout.target(it.target)))
                }
            }
        }
        if (state.phase == DdPhase.PERIPHERY) {
            repeat(DdLayout.TARGET_POSITIONS) { position ->
                val (x, y) = DdLayout.target(position)
                val cx = side / 2 + side / 2 * x.toFloat()
                val cy = side / 2 + side / 2 * y.toFloat()
                Box(
                    Modifier
                        .offset(cx - TAP_SIZE / 2, cy - TAP_SIZE / 2)
                        .size(TAP_SIZE)
                        .clip(CircleShape)
                        .border(2.dp, colors.textSecondary, CircleShape)
                        .semantics { contentDescription = "Позиция ${position + 1}" }
                        .onPressDown { onInput(DdInput.Periphery(position)) },
                )
            }
        }
    }
}

/** Скруглённый прямоугольник: [half] — половина стороны области, в которой рисуем. */
private fun DrawScope.drawCenterShape(shape: DdShape, aspect: Float, half: Float, color: Color) {
    val long = CENTER_LONG * half * 2
    val short = long / aspect
    val w = if (shape == DdShape.WIDE) long else short
    val h = if (shape == DdShape.WIDE) short else long
    drawRoundRect(color, Offset(center.x - w / 2, center.y - h / 2), Size(w, h), CornerRadius(short * 0.2f))
}

private fun DrawScope.drawCross(c: Offset, arm: Float, color: Color) {
    val stroke = arm * 0.35f
    drawLine(color, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), stroke)
    drawLine(color, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), stroke)
}

private fun DrawScope.drawTriangle(c: Offset, r: Float, color: Color) {
    val path = Path()
    for (k in 0 until 3) {
        val a = Math.toRadians(-90.0 + k * 120.0)
        val x = c.x + r * cos(a).toFloat()
        val y = c.y + r * sin(a).toFloat()
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}
