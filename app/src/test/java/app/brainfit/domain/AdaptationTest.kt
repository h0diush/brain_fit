package app.brainfit.domain

import app.brainfit.domain.adaptive.Adaptation
import app.brainfit.domain.adaptive.AdaptiveConfig
import app.brainfit.domain.adaptive.AdaptiveConfigs
import app.brainfit.domain.adaptive.LevelRule
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.LevelChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class AdaptationTest {

    private val cfg = AdaptiveConfig()
    private val dd = AdaptiveConfigs.of(GameId.DOUBLE_DECISION)

    @Test
    fun configsPerGame() {
        assertEquals(4, AdaptiveConfigs.of(GameId.CORSI).calibrationBlock)
        assertEquals(LevelRule.BELOW_TARGET_STREAK, dd.rule)
        for (id in GameId.entries) {
            val c = AdaptiveConfigs.of(id)
            assertEquals(0.85, c.upAverage, 0.0)
            assertEquals(0.65, c.downAverage, 0.0)
            assertEquals(3, c.window)
            assertEquals(2, c.calibrationSessions)
        }
    }

    @Test
    fun calibrationStepMovesByOneWithinTable() {
        assertEquals(3, Adaptation.calibrationStep(2, 0.85, 6, cfg))
        assertEquals(2, Adaptation.calibrationStep(2, 0.849, 6, cfg))
        assertEquals(2, Adaptation.calibrationStep(2, 0.65, 6, cfg))
        assertEquals(1, Adaptation.calibrationStep(2, 0.649, 6, cfg))
        assertEquals(6, Adaptation.calibrationStep(6, 1.0, 6, cfg))
        assertEquals(1, Adaptation.calibrationStep(1, 0.0, 6, cfg))
    }

    @Test
    fun windowNeedsThreeSessions() {
        assertNull(Adaptation.decide(2, 6, listOf(1.0, 1.0), cfg))
        assertNull(Adaptation.decide(2, 6, listOf(0.0, 0.0), cfg))
        assertEquals(LevelChange(2, 3, Adaptation.REASON_WINDOW_UP), Adaptation.decide(2, 6, listOf(1.0, 1.0, 1.0), cfg))
    }

    @Test
    fun upNeedsHighAverageAndNoWeakSession() {
        // Среднее 0,87, но одна сессия ниже 0,75.
        assertNull(Adaptation.decide(2, 6, listOf(1.0, 0.9, 0.7), cfg))
        assertEquals(3, Adaptation.decide(2, 6, listOf(0.85, 0.85, 0.85), cfg)!!.toLevel)
        // Решают только 3 последние (новые первыми): старая слабая сессия уже вне окна.
        assertEquals(3, Adaptation.decide(2, 6, listOf(0.9, 0.9, 0.9, 0.1), cfg)!!.toLevel)
        assertNull(Adaptation.decide(2, 6, listOf(0.84, 0.85, 0.85), cfg))
    }

    @Test
    fun downOnLowAverage() {
        assertEquals(LevelChange(3, 2, Adaptation.REASON_WINDOW_DOWN), Adaptation.decide(3, 6, listOf(0.6, 0.7, 0.6), cfg))
        assertNull(Adaptation.decide(3, 6, listOf(0.65, 0.65, 0.65), cfg))
    }

    @Test
    fun levelStaysInsideTable() {
        assertNull(Adaptation.decide(6, 6, listOf(1.0, 1.0, 1.0), cfg))
        assertNull(Adaptation.decide(1, 6, listOf(0.0, 0.0, 0.0), cfg))
    }

    @Test
    fun doubleDecisionGoesUpAfterTwoSessionsAtTarget() {
        assertNull(Adaptation.decide(2, 6, listOf(1.0), dd))
        assertEquals(LevelChange(2, 3, Adaptation.REASON_BELOW_TARGET), Adaptation.decide(2, 6, listOf(1.0, 1.0), dd))
        assertNull(Adaptation.decide(2, 6, listOf(1.0, 0.99), dd))
        // Две сессии на цели, хотя средняя за три ниже 0,85, — всё равно вверх: правило своё.
        assertEquals(3, Adaptation.decide(2, 6, listOf(1.0, 1.0, 0.2), dd)!!.toLevel)
        // Вниз — как у всех.
        assertEquals(1, Adaptation.decide(2, 6, listOf(0.5, 0.6, 0.6), dd)!!.toLevel)
    }

    @Test
    fun neverMoreThanOneStep_randomWindows() {
        val rng = Random(3)
        repeat(10_000) {
            val level = rng.nextInt(1, 7)
            val window = List(rng.nextInt(0, 6)) { rng.nextDouble() }
            for (c in listOf(cfg, dd)) {
                val change = Adaptation.decide(level, 6, window, c) ?: continue
                assertEquals(level, change.fromLevel)
                assertEquals(1, kotlin.math.abs(change.toLevel - level))
                assert(change.toLevel in 1..6)
            }
        }
    }
}
