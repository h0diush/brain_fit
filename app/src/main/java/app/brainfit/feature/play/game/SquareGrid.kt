package app.brainfit.feature.play.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** Квадратная сетка size × size на всю ширину. Клетки нумеруются по строкам с нуля. */
@Composable
fun SquareGrid(
    size: Int,
    gap: Dp,
    modifier: Modifier = Modifier,
    cell: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().aspectRatio(1f),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        repeat(size) { row ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                repeat(size) { col ->
                    cell(row * size + col, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/** Вспышка клетки после нажатия, мс. ТЗ: анимации не длиннее 200 мс. */
const val FLASH_MS = 180L
