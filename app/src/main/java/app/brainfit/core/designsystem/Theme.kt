package app.brainfit.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.brainfit.R

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Токены темы как цвета Compose. Экраны берут цвета отсюда или из MaterialTheme, не из hex. */
@Immutable
data class BrainColors(
    val background: Color,
    val card: Color,
    val surfaceSecondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val error: Color,
    val warning: Color,
    val outline: Color,
)

private fun Palette.toColors() = BrainColors(
    background = Color(background),
    card = Color(card),
    surfaceSecondary = Color(surfaceSecondary),
    textPrimary = Color(textPrimary),
    textSecondary = Color(textSecondary),
    accent = Color(accent),
    onAccent = Color(onAccent),
    error = Color(error),
    warning = Color(warning),
    outline = Color(outline),
)

private val DarkColors = Palettes.Dark.toColors()
private val LightColors = Palettes.Light.toColors()

private fun BrainColors.toScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = surfaceSecondary,
        onPrimaryContainer = textPrimary,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = surfaceSecondary,
        onSecondaryContainer = textPrimary,
        tertiary = accent,
        onTertiary = onAccent,
        background = background,
        onBackground = textPrimary,
        surface = background,
        onSurface = textPrimary,
        surfaceVariant = surfaceSecondary,
        onSurfaceVariant = textSecondary,
        surfaceContainerLowest = card,
        surfaceContainerLow = card,
        surfaceContainer = card,
        surfaceContainerHigh = surfaceSecondary,
        surfaceContainerHighest = surfaceSecondary,
        outline = outline,
        outlineVariant = surfaceSecondary,
        surfaceTint = Color.Transparent,
        error = error,
        onError = background,
    )
}

/** Inter с моноширинными цифрами (tnum): числа в статистике стоят ровно, а меняющееся значение не «прыгает». */
private fun TextStyle.inter(weight: FontWeight? = null, tracking: Float? = null) = copy(
    fontFamily = Inter,
    fontWeight = weight ?: fontWeight,
    letterSpacing = tracking?.sp ?: letterSpacing,
    fontFeatureSettings = "tnum",
)

private val AppTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.inter(FontWeight.Bold, -1.5f),
        displayMedium = t.displayMedium.inter(FontWeight.Bold, -1.2f),
        displaySmall = t.displaySmall.inter(FontWeight.Bold, -1f),
        headlineLarge = t.headlineLarge.inter(FontWeight.Bold, -0.8f),
        headlineMedium = t.headlineMedium.inter(FontWeight.SemiBold, -0.6f),
        headlineSmall = t.headlineSmall.inter(FontWeight.SemiBold, -0.4f),
        titleLarge = t.titleLarge.inter(FontWeight.SemiBold, -0.3f),
        titleMedium = t.titleMedium.inter(FontWeight.SemiBold, -0.1f),
        titleSmall = t.titleSmall.inter(FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.inter(tracking = 0f),
        bodyMedium = t.bodyMedium.inter(tracking = 0f),
        bodySmall = t.bodySmall.inter(tracking = 0.1f),
        labelLarge = t.labelLarge.inter(FontWeight.SemiBold, 0f),
        labelMedium = t.labelMedium.inter(FontWeight.Medium, 0.2f),
        labelSmall = t.labelSmall.inter(FontWeight.Medium, 0.2f),
    )
}

/** Строгие формы: небольшие радиусы, никаких «таблеток» у карточек и кнопок. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** Общие размеры и длительности. ТЗ: цели касания не меньше 48 dp, анимации интерфейса не длиннее 200 мс. */
object Dimens {
    val minTouch = 48.dp
    val screenPadding = 16.dp
    val cardPadding = 16.dp
    val gap = 12.dp

    /** Тонкая линия: рамки карточек и разделители. */
    val hairline = 1.dp
    const val ANIM_MS = Motion.BASE
}

val LocalBrainColors = staticCompositionLocalOf { DarkColors }

object BrainTheme {
    val colors: BrainColors
        @Composable get() = LocalBrainColors.current
}

@Composable
fun BrainFitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalBrainColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toScheme(darkTheme),
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
