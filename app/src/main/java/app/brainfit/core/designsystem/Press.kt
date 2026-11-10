package app.brainfit.core.designsystem

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * Реакция на касание в момент нажатия, а не отпускания: в играх на скорость это даёт
 * честное время реакции (обычный click срабатывает на отпускании, на 50–100 мс позже).
 * Для TalkBack добавлено обычное действие «нажать».
 */
fun Modifier.onPressDown(onPress: () -> Unit): Modifier = composed {
    val current = rememberUpdatedState(onPress)
    this
        .semantics(mergeDescendants = true) {
            role = Role.Button
            onClick {
                current.value()
                true
            }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown().consume()
                current.value()
                waitForUpOrCancellation()
            }
        }
}

/**
 * Размер текста в dp, не зависящий от системного масштаба шрифта: стимулы игр (числа в таблице,
 * пример в спринте) имеют фиксированный размер, иначе масштаб ломает сложность (ТЗ, раздел 8).
 */
@Composable
fun fixedSp(dp: Int): TextUnit = with(LocalDensity.current) { dp.dp.toSp() }
