package app.brainfit.feature.play.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.brainfit.core.designsystem.BrainTheme
import app.brainfit.core.designsystem.MutedText
import app.brainfit.core.designsystem.StroopPalette
import app.brainfit.core.designsystem.fixedSp
import app.brainfit.core.designsystem.onPressDown
import app.brainfit.games.stroop.StroopAnswer
import app.brainfit.games.stroop.StroopColor
import app.brainfit.games.stroop.StroopFeedback
import app.brainfit.games.stroop.StroopPhase
import app.brainfit.games.stroop.StroopState

fun StroopColor.ink(): Color = Color(
    when (this) {
        StroopColor.RED -> StroopPalette.red
        StroopColor.BLUE -> StroopPalette.blue
        StroopColor.YELLOW -> StroopPalette.yellow
        StroopColor.GREEN -> StroopPalette.green
    },
)

private val StroopColor.buttonLabel: String get() = word.lowercase().replaceFirstChar { it.uppercase() }

@Composable
fun StroopContent(state: StroopState, onInput: (StroopAnswer) -> Unit) {
    val params = state.params
    Column(Modifier.fillMaxSize().padding(bottom = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            MutedText("Жми цвет букв, а не слово")
            MutedText("Слово ${minOf(state.index + 1, params.stimuli.size)} из ${params.stimuli.size}")
        }
        Spacer(Modifier.weight(1f))
        // Табло тёмное в обеих темах: чернила одни и те же и яркие (см. StroopPalette).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(MaterialTheme.shapes.large)
                .background(Color(StroopPalette.panel))
                .border(1.dp, BrainTheme.colors.outline, MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) {
            val stimulus = state.current
            when {
                state.phase == StroopPhase.STIMULUS && stimulus != null -> Text(
                    text = stimulus.word.word,
                    color = stimulus.ink.ink(),
                    fontSize = fixedSp(40),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )

                state.feedback == StroopFeedback.WRONG -> Text("Ошибка", color = StroopColor.RED.ink(), fontSize = fixedSp(22))
                state.feedback == StroopFeedback.TIMEOUT -> Text("Не успел", color = StroopColor.RED.ink(), fontSize = fixedSp(22))
                else -> Unit
            }
        }
        Spacer(Modifier.weight(1f))
        // Кнопки на постоянных местах на всю сессию: на уровнях с тремя цветами место зелёного пустует.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StroopColor.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { color ->
                        if (color in params.colors) {
                            ColorKey(color, Modifier.weight(1f)) { onInput(StroopAnswer(color)) }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorKey(color: StroopColor, modifier: Modifier, onPress: () -> Unit) {
    Box(
        modifier = modifier
            .height(72.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(color.ink())
            .onPressDown(onPress),
        contentAlignment = Alignment.Center,
    ) {
        Text(color.buttonLabel, color = Color(StroopPalette.label), fontSize = fixedSp(20), fontWeight = FontWeight.SemiBold)
    }
}
