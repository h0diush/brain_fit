package app.brainfit.core

import app.brainfit.core.designsystem.Contrast
import app.brainfit.core.designsystem.Palette
import app.brainfit.core.designsystem.Palettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Все пары «текст — фон», которыми пользуются экраны, читаются в обеих темах (WCAG AA, 4,5:1). */
class PaletteContrastTest {

    private fun Palette.textPairs(): List<Triple<String, Long, Long>> {
        val surfaces = listOf("background" to background, "card" to card, "surfaceSecondary" to surfaceSecondary)
        val texts = listOf(
            "textPrimary" to textPrimary,
            "textSecondary" to textSecondary,
            "accent" to accent,
            "error" to error,
            "warning" to warning,
        )
        return texts.flatMap { (tn, t) -> surfaces.map { (sn, s) -> Triple("$tn on $sn", t, s) } } +
            Triple("onAccent on accent", onAccent, accent)
    }

    private fun assertReadable(name: String, palette: Palette) {
        palette.textPairs().forEach { (pair, fg, bg) ->
            val ratio = Contrast.ratio(fg, bg)
            assertTrue("$name: $pair = ${"%.2f".format(ratio)}, нужно >= 4.5", ratio >= 4.5)
        }
    }

    @Test
    fun darkPaletteIsReadable() = assertReadable("dark", Palettes.Dark)

    @Test
    fun lightPaletteIsReadable() = assertReadable("light", Palettes.Light)

    @Test
    fun darkPaletteMatchesSpecTokens() {
        val p = Palettes.Dark
        assertEquals(0xFF101214, p.background)
        assertEquals(0xFF1A1D20, p.card)
        assertEquals(0xFF24282C, p.surfaceSecondary)
        assertEquals(0xFFF3F4F5, p.textPrimary)
        assertEquals(0xFF969DA5, p.textSecondary)
        assertEquals(0xFF4AC49A, p.accent)
        assertEquals(0xFFE66F76, p.error)
        assertEquals(0xFFE7B45C, p.warning)
    }

    @Test
    fun contrastOfBlackAndWhiteIs21() {
        assertEquals(21.0, Contrast.ratio(0xFF000000, 0xFFFFFFFF), 1e-9)
    }
}
