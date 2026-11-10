package app.brainfit.feature.play.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.brainfit.core.designsystem.BrainTheme
import app.brainfit.core.designsystem.MutedText
import app.brainfit.core.designsystem.fixedSp
import app.brainfit.core.designsystem.onPressDown
import app.brainfit.games.nback.NBackPress
import app.brainfit.games.nback.NBackState
import kotlinx.coroutines.delay

/** «1 шаг», «2 шага», «5 шагов». */
private fun steps(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "$n шаг"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "$n шага"
    else -> "$n шагов"
}

@Composable
fun NBackContent(state: NBackState, onInput: (NBackPress) -> Unit) {
    val colors = BrainTheme.colors
    val params = state.params

    // Нажатие принято — кнопка коротко темнеет. Верно или нет, не показываем: это видно в итоге раунда.
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(state.presses.size) {
        if (state.presses.isEmpty()) return@LaunchedEffect
        flash = true
        delay(FLASH_MS)
        flash = false
    }

    Column(Modifier.fillMaxSize().padding(bottom = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Та же клетка, что ${steps(params.n)} назад?",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            MutedText(
                when {
                    state.current < 0 -> "Сейчас начнётся"
                    state.current < params.n -> "Запоминай"
                    else -> "Клетка ${state.current + 1} из ${params.count}"
                },
            )
        }
        Spacer(Modifier.weight(1f))
        SquareGrid(size = 3, gap = 8.dp) { index, modifier ->
            Box(
                modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(if (state.lit == index) colors.accent else colors.surfaceSecondary),
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(if (flash) colors.accent.copy(alpha = 0.6f) else colors.accent)
                .onPressDown { onInput(NBackPress) },
            contentAlignment = Alignment.Center,
        ) {
            Text("Совпадает", color = colors.onAccent, fontSize = fixedSp(24), fontWeight = FontWeight.SemiBold)
        }
    }
}
