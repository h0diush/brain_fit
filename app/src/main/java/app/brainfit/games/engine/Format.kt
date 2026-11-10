package app.brainfit.games.engine

import java.util.Locale
import kotlin.math.roundToInt

/** Числа для текста интерфейса: десятичная запятая, как принято по-русски. */
object Fmt {
    fun decimal(value: Double, digits: Int = 1): String =
        String.format(Locale.ROOT, "%.${digits}f", value).replace('.', ',')

    fun percent(share: Double): String = "${(share * 100).roundToInt()} %"

    fun seconds(ms: Double): String = decimal(ms / 1000.0) + " с"
}
