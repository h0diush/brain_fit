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

    /** Русское множественное число: plural(1, "день", "дня", "дней") — «день», 3 — «дня», 11 — «дней». */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val mod100 = Math.floorMod(n, 100)
        val mod10 = Math.floorMod(n, 10)
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    /** «1 день», «3 дня», «5 дней». */
    fun days(n: Int): String = "$n ${plural(n, "день", "дня", "дней")}"

    /** «1 сессия», «3 сессии», «5 сессий». */
    fun sessions(n: Int): String = "$n ${plural(n, "сессия", "сессии", "сессий")}"
}
