package app.brainfit.games.engine

import java.util.Locale
import kotlin.math.roundToInt

/** Числа для текста интерфейса: десятичная запятая, как принято по-русски. */
object Fmt {
    fun decimal(value: Double, digits: Int = 1): String =
        String.format(Locale.ROOT, "%.${digits}f", value).replace('.', ',')

    fun percent(share: Double): String = "${(share * 100).roundToInt()} %"

    fun seconds(ms: Double): String = decimal(ms / 1000.0) + " с"

    /** Длительность «7 мин 05 с» или «45 с». */
    fun duration(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 500) / 1000
        val minutes = total / 60
        val seconds = total % 60
        return if (minutes == 0L) "$seconds с" else "$minutes мин ${seconds.toString().padStart(2, '0')} с"
    }
}
