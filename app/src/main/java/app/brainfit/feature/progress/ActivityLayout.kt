package app.brainfit.feature.progress

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Календарь активности, разложенный по клеткам. null — дня нет в периоде (до начала или в будущем). */
data class ActivityLayout(
    val rows: List<List<Int?>>,
    val columnLabels: List<String>,
    val rowLabels: List<String>?,
)

private val weekdays = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/**
 * Раскладка дней [from]..[to] по неделям с понедельника. [counts] — сессий в каждый день, по порядку с [from].
 * Короткий период — недели строками, дни недели столбцами, как в обычном календаре. Длинный ([transpose]) —
 * наоборот: недели столбцами, иначе календарь вышел бы выше экрана.
 */
fun activityLayout(from: LocalDate, to: LocalDate, counts: List<Int>, transpose: Boolean): ActivityLayout {
    val start = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val end = to.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    val weeks = generateSequence(start) { it.plusWeeks(1) }.takeWhile { !it.isAfter(end) }.map { monday ->
        (0 until 7).map { d ->
            val day = monday.plusDays(d.toLong())
            if (day.isBefore(from) || day.isAfter(to)) null else counts.getOrNull((day.toEpochDay() - from.toEpochDay()).toInt())
        }
    }.toList()
    return if (!transpose) {
        ActivityLayout(weeks, weekdays, rowLabels = null)
    } else {
        ActivityLayout(
            rows = (0 until 7).map { d -> weeks.map { it[d] } },
            columnLabels = weeks.map { "" },
            // Через строку, чтобы подписи не налезали.
            rowLabels = weekdays.mapIndexed { i, label -> if (i % 2 == 0) label else "" },
        )
    }
}
