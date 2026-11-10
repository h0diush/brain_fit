package app.brainfit.feature.play.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.brainfit.core.designsystem.BrainTheme
import app.brainfit.core.designsystem.MutedText
import app.brainfit.core.designsystem.fixedSp
import app.brainfit.core.designsystem.onPressDown
import app.brainfit.games.mathsprint.MathInput
import app.brainfit.games.mathsprint.MathSprintGame
import app.brainfit.games.mathsprint.MathState
import kotlinx.coroutines.delay

@Composable
fun MathContent(state: MathState, onInput: (MathInput) -> Unit) {
    val colors = BrainTheme.colors

    // Цвет примера после ответа: зелёный — верно, красный — нет.
    var flash by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(state.trials.size) {
        val last = state.trials.lastOrNull() ?: return@LaunchedEffect
        flash = last.correct
        delay(FLASH_MS)
        flash = null
    }

    Column(Modifier.fillMaxSize().padding(bottom = 8.dp)) {
        if (!state.params.isDemo) {
            val total = (state.params.durationNanos / 1_000_000_000L).toFloat()
            LinearProgressIndicator(
                progress = { if (total > 0f) state.secondsLeft / total else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.extraSmall),
                color = colors.accent,
                trackColor = colors.surfaceSecondary,
            )
            MutedText("Осталось ${state.secondsLeft} с", Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = state.current?.let { "${it.text} = ?" } ?: "",
            fontSize = fixedSp(36),
            fontWeight = FontWeight.Bold,
            color = when (flash) {
                true -> colors.accent
                false -> colors.error
                null -> colors.textPrimary
            },
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
                .height(64.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(colors.card),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.input.ifEmpty { "…" },
                fontSize = fixedSp(34),
                fontWeight = FontWeight.SemiBold,
                color = if (state.input.isEmpty()) colors.textSecondary else colors.textPrimary,
            )
        }
        Spacer(Modifier.weight(1f))
        Keypad(canAddDigit = state.input.length < MathSprintGame.MAX_DIGITS, onInput = onInput)
    }
}

@Composable
private fun Keypad(canAddDigit: Boolean, onInput: (MathInput) -> Unit) {
    val colors = BrainTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit ->
                    Key(Modifier.weight(1f), colors.surfaceSecondary, onPress = { if (canAddDigit) onInput(MathInput.Digit(digit)) }) {
                        KeyLabel(digit.toString(), colors.textPrimary)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key(
                modifier = Modifier.weight(1f).semantics { contentDescription = "Стереть" },
                container = colors.surfaceSecondary,
                onPress = { onInput(MathInput.Backspace) },
            ) {
                Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = null, tint = colors.textPrimary)
            }
            Key(Modifier.weight(1f), colors.surfaceSecondary, onPress = { if (canAddDigit) onInput(MathInput.Digit(0)) }) {
                KeyLabel("0", colors.textPrimary)
            }
            Key(Modifier.weight(1f), colors.accent, onPress = { onInput(MathInput.Submit) }) {
                KeyLabel("OK", colors.onAccent)
            }
        }
    }
}

@Composable
private fun Key(modifier: Modifier, container: Color, onPress: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .height(64.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .onPressDown(onPress),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun KeyLabel(text: String, color: Color) {
    Text(text, fontSize = fixedSp(28), fontWeight = FontWeight.SemiBold, color = color)
}
