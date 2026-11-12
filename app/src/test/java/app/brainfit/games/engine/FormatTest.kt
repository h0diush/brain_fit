package app.brainfit.games.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun duration() {
        assertEquals("0 с", Fmt.duration(0))
        assertEquals("45 с", Fmt.duration(44_600))
        assertEquals("1 мин 00 с", Fmt.duration(60_000))
        assertEquals("7 мин 05 с", Fmt.duration(425_000))
        assertEquals("0 с", Fmt.duration(-5))
    }

    @Test
    fun plural() {
        val cases = mapOf(
            0 to "0 дней", 1 to "1 день", 2 to "2 дня", 4 to "4 дня", 5 to "5 дней", 11 to "11 дней",
            12 to "12 дней", 14 to "14 дней", 21 to "21 день", 22 to "22 дня", 25 to "25 дней", 101 to "101 день",
            111 to "111 дней",
        )
        cases.forEach { (n, text) -> assertEquals(text, Fmt.days(n)) }
        assertEquals("3 сессии", Fmt.sessions(3))
        assertEquals("1 сессия", Fmt.sessions(1))
        assertEquals("7 сессий", Fmt.sessions(7))
    }
}
