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
}
