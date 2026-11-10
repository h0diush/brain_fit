package app.brainfit.feature.play.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.brainfit.core.designsystem.BrainTheme
import app.brainfit.core.designsystem.MutedText
import app.brainfit.core.designsystem.onPressDown
import app.brainfit.games.corsi.CorsiPhase
import app.brainfit.games.corsi.CorsiState
import app.brainfit.games.corsi.CorsiTap
import kotlinx.coroutines.delay

@Composable
fun CorsiContent(state: CorsiState, onInput: (CorsiTap) -> Unit) {
    val colors = BrainTheme.colors
    val params = state.params
    val n = params.gridSize
    val input = state.phase == CorsiPhase.INPUT

    // Нажатая клетка коротко подсвечивается, чтобы было видно, что нажатие принято.
    var flashCell by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.taps.size) {
        val cell = state.taps.lastOrNull() ?: return@LaunchedEffect
        flashCell = cell
        delay(FLASH_MS)
        flashCell = null
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = when {
                    !input -> "Запоминай"
                    params.reverse -> "Повтори в обратном порядке"
                    else -> "Повтори по порядку"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            MutedText(if (input) "Нажато ${state.taps.size} из ${state.length}" else "Клеток: ${state.length}")
        }
        Spacer(Modifier.weight(1f))
        SquareGrid(size = n, gap = 8.dp) { index, modifier ->
            val color = when {
                state.lit == index -> colors.accent
                flashCell == index -> colors.accent.copy(alpha = 0.55f)
                else -> colors.surfaceSecondary
            }
            Box(
                modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(color)
                    .semantics { contentDescription = "Клетка ${index / n + 1}, ${index % n + 1}" }
                    .onPressDown { if (input) onInput(CorsiTap(index)) },
            )
        }
        Spacer(Modifier.weight(0.25f))
    }
}
