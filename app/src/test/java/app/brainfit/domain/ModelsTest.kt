package app.brainfit.domain

import app.brainfit.domain.model.AppSettings
import app.brainfit.domain.model.GameCatalog
import app.brainfit.domain.model.GameGroup
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    @Test
    fun catalogHasEveryGameExactlyOnce() {
        assertEquals(6, GameCatalog.all.size)
        assertEquals(GameId.entries.toSet(), GameCatalog.all.map { it.id }.toSet())
        GameId.entries.forEach { assertEquals(it, GameCatalog.get(it).id) }
    }

    /** Стандартная тренировка берёт минимум по одной игре из каждой группы — в каждой их по две. */
    @Test
    fun everyGroupHasTwoGames() {
        val byGroup = GameCatalog.all.groupBy { it.group }
        assertEquals(GameGroup.entries.toSet(), byGroup.keys)
        byGroup.forEach { (group, games) -> assertEquals("группа $group", 2, games.size) }
        assertEquals(setOf(GameId.NBACK, GameId.CORSI), byGroup.getValue(GameGroup.MEMORY).map { it.id }.toSet())
        assertEquals(setOf(GameId.SCHULTE, GameId.STROOP), byGroup.getValue(GameGroup.ATTENTION).map { it.id }.toSet())
        assertEquals(setOf(GameId.MATH, GameId.DOUBLE_DECISION), byGroup.getValue(GameGroup.SPEED).map { it.id }.toSet())
    }

    @Test
    fun catalogTextsAreFilled() {
        GameCatalog.all.forEach {
            assertTrue(it.title.isNotBlank())
            assertTrue(it.trains.isNotBlank())
            assertTrue(it.mainMetric.isNotBlank())
        }
    }

    /** Раздел 3 ТЗ: звук выключен, вибрация включена, тема системная; детектор тряски включён. */
    @Test
    fun settingsDefaultsFollowSpec() {
        val s = AppSettings()
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertFalse(s.soundEnabled)
        assertTrue(s.vibrationEnabled)
        assertTrue(s.shakeDetectorEnabled)
    }
}
