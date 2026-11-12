package app.brainfit.feature.progress

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ActivityLayoutTest {

    @Test
    fun shortPeriodIsWeeksAsRowsFromMonday() {
        // Чт 2026-11-12 и шесть дней до него: с пятницы 6-го.
        val to = LocalDate.of(2026, 11, 12)
        val from = to.minusDays(6)
        val layout = activityLayout(from, to, counts = listOf(1, 0, 0, 2, 0, 3, 4), transpose = false)
        assertEquals(listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"), layout.columnLabels)
        assertEquals(
            listOf(
                listOf(null, null, null, null, 1, 0, 0),
                listOf(2, 0, 3, 4, null, null, null),
            ),
            layout.rows,
        )
    }

    @Test
    fun longPeriodIsTransposedWithWeekdaysAsRows() {
        val to = LocalDate.of(2026, 11, 12)
        val from = to.minusDays(89)
        val layout = activityLayout(from, to, counts = List(90) { 1 }, transpose = true)
        assertEquals(7, layout.rows.size)
        val weeks = layout.rows.first().size
        assertEquals(weeks, layout.columnLabels.size)
        // Каждый день периода попал ровно в одну клетку.
        assertEquals(90, layout.rows.sumOf { row -> row.count { it != null } })
        assertEquals(listOf("Пн", "", "Ср", "", "Пт", "", "Вс"), layout.rowLabels)
    }
}
