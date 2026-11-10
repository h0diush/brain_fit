package app.brainfit.core.designsystem

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Токены цвета в формате 0xAARRGGBB. Чистый Kotlin без Compose, чтобы контраст
 * проверялся обычным JVM-тестом (PaletteContrastTest).
 */
data class Palette(
    val background: Long,
    val card: Long,
    val surfaceSecondary: Long,
    val textPrimary: Long,
    val textSecondary: Long,
    val accent: Long,
    /** Текст и значки поверх акцентной заливки. */
    val onAccent: Long,
    val error: Long,
    val warning: Long,
    val outline: Long,
)

object Palettes {
    /** Тёмная палитра — ровно токены из раздела 8 ТЗ. */
    val Dark = Palette(
        background = 0xFF101214,
        card = 0xFF1A1D20,
        surfaceSecondary = 0xFF24282C,
        textPrimary = 0xFFF3F4F5,
        textSecondary = 0xFF969DA5,
        accent = 0xFF4AC49A,
        onAccent = 0xFF101214,
        error = 0xFFE66F76,
        warning = 0xFFE7B45C,
        outline = 0xFF3A4046,
    )

    /**
     * Светлая палитра спроектирована отдельно, а не инверсией: акцент, ошибка и предупреждение
     * затемнены, чтобы читаться как текст на светлом фоне (контраст не ниже 4,5).
     */
    val Light = Palette(
        background = 0xFFF4F5F6,
        card = 0xFFFFFFFF,
        surfaceSecondary = 0xFFE9ECEE,
        textPrimary = 0xFF15181B,
        textSecondary = 0xFF5B636B,
        accent = 0xFF116B4E,
        onAccent = 0xFFFFFFFF,
        error = 0xFFB4232C,
        warning = 0xFF8A5A00,
        outline = 0xFFC4CACF,
    )
}

/**
 * Цвета Струпа. Слово показывается на тёмном табло в обеих темах: так чернила одни и те же
 * и яркие. В светлой теме тёмные чернила на светлом фоне почти сравнялись бы по яркости.
 * Красный и зелёный заметно различаются по яркости (ТЗ 4.2). Проверяет PaletteContrastTest.
 */
object StroopPalette {
    const val panel = 0xFF15181BL

    /** Подпись на цветной кнопке. */
    const val label = 0xFF15181BL
    const val red = 0xFFFF5C5CL
    const val blue = 0xFF5AA2FFL
    const val yellow = 0xFFFFD43BL
    const val green = 0xFF3DDC84L
    val inks = listOf(red, blue, yellow, green)
}

/** Контраст по WCAG 2.1 для непрозрачных цветов. */
object Contrast {
    fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
