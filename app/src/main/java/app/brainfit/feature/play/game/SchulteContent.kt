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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.brainfit.core.designsystem.BrainTheme
import app.brainfit.core.designsystem.MutedText
import app.brainfit.core.designsystem.fixedSp
import app.brainfit.core.designsystem.onPressDown
import app.brainfit.games.schulte.SchulteState
import app.brainfit.games.schulte.SchulteTap
import kotlinx.coroutines.delay

@Composable
fun SchulteContent(state: SchulteState, onInput: (SchulteTap) -> Unit) {
    val colors = BrainTheme.colors
    val params = state.params
    val n = params.size

    // Ошибочный тап — короткая красная вспышка клетки. Вибро — этап 7.
    var flashCell by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.wrongTaps) {
        val cell = state.wrongCell ?: return@LaunchedEffect
        flashCell = cell
        delay(FLASH_MS)
        flashCell = null
    }

    val gap = when (n) {
        3 -> 8.dp
        4 -> 6.dp
        5 -> 4.dp
        else -> 3.dp
    }
    val textDp = when (n) {
        3 -> 40
        4 -> 34
        5 -> 28
        else -> 24
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MutedText(if (params.reverse) "В обратном порядке" else "По порядку")
            Text(
                text = "Найди: ${state.target.coerceIn(1, state.cells)}",
                style = MaterialTheme.typography.headlineLarge,
            )
        }
        Spacer(Modifier.weight(1f))
        SquareGrid(size = n, gap = gap) { index, modifier ->
            val flashing = flashCell == index
            Box(
                modifier = modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(if (flashing) colors.error else colors.surfaceSecondary)
                    .onPressDown { onInput(SchulteTap(index)) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = params.layout[index].toString(),
                    fontSize = fixedSp(textDp),
                    fontWeight = FontWeight.SemiBold,
                    color = if (flashing) colors.background else colors.textPrimary,
                )
            }
        }
        Spacer(Modifier.weight(0.25f))
    }
}
