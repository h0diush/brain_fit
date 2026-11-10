package app.brainfit.feature.play

import app.brainfit.domain.model.GameId
import app.brainfit.games.engine.START_LEVEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameRegistryTest {

    private val registry = GameRegistry()

    @Test
    fun allSixGamesArePlayable() {
        assertEquals(GameId.entries.toSet(), registry.playable)
    }

    @Test
    fun everyGameHasConsistentLevelsAndInstructions() {
        for (id in registry.playable) {
            val module = registry.get(id)
            assertNotNull(module)
            module!!
            val game = module.game
            assertEquals(id, game.id)
            assertEquals("$id: уровни нумеруются с 1 подряд", (1..game.levels.size).toList(), game.levels.map { it.level })
            assertTrue("$id: стартового уровня нет в таблице", START_LEVEL in 1..game.levels.size)
            game.levels.forEach { assertTrue(game.roundsPerSession(it.level) > 0) }
            assertTrue("$id: нет инструкции", module.howTo.isNotEmpty())
            assertTrue(module.howTo.none { it.isBlank() })
        }
    }

    @Test
    fun demoOfEveryGameStartsUnfinished() {
        for (id in registry.playable) {
            val demo = Demo.of(registry.get(id)!!.game)
            assertFalse("$id: пробная попытка закончена сразу", demo.isFinishedAny(Random(1)))
        }
    }

    // Типы модуля из реестра стёрты — проверяем через обобщённую функцию.
    private fun <S : Any, I : Any> Demo<S, I>.isFinishedAny(rng: Random): Boolean = isFinished(initial(rng))
}
