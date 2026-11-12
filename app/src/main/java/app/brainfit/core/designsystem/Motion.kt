package app.brainfit.core.designsystem

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Токены движения. ТЗ: анимации интерфейса не длиннее 200 мс — это потолок для всего, включая задержку
 * «лесенки». Плавность даёт кривая: появление замедляется к концу, уход ускоряется и короче появления.
 *
 * Системная настройка «Убрать анимацию» (масштаб анимации 0) Compose учитывает сам: все анимации ниже
 * в этом случае сразу показывают конечное состояние.
 */
object Motion {
    /** Отклик на нажатие. */
    const val FAST = 100

    /** Появление и смена содержимого. */
    const val BASE = 180

    /** Уход — короче появления. */
    const val EXIT = 120

    /** Шаг «лесенки» между соседними элементами и сколько шагов максимум: 140 + 2 × 30 = 200 мс. */
    const val STAGGER = 30
    const val STAGGER_STEPS = 2
    const val APPEAR = 140

    /** Замедление к концу (emphasized decelerate из Material 3). */
    val Enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Ускорение к концу — для ухода. */
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Обычная смена состояния. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Смена значения: новое въезжает снизу, старое уходит вверх. Для чисел статистики. */
    fun <T> valueChange(scope: AnimatedContentTransitionScope<T>): ContentTransform =
        (slideInVertically(tween(BASE, easing = Enter)) { it / 3 } + fadeIn(tween(BASE, easing = Enter))) togetherWith
            (slideOutVertically(tween(EXIT, easing = Exit)) { -it / 3 } + fadeOut(tween(EXIT, easing = Exit)))
}

/** Нажатый элемент чуть сжимается — видно, что касание принято. */
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.97f): Modifier = composed {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, tween(Motion.FAST, easing = Motion.Standard), label = "press")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Появление блока при первом показе: проявляется и чуть поднимается. [index] — место в «лесенке»;
 * дальше [Motion.STAGGER_STEPS] шагов задержка не растёт, чтобы весь экран уложился в 200 мс.
 */
fun Modifier.appear(index: Int = 0): Modifier = composed {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(Motion.APPEAR, delayMillis = min(index, Motion.STAGGER_STEPS) * Motion.STAGGER, easing = Motion.Enter),
        label = "appear",
    )
    val shift = with(LocalDensity.current) { 12.dp.toPx() }
    graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * shift
    }
}
