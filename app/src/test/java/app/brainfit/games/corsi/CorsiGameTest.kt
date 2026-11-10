package app.brainfit.games.corsi

import app.brainfit.core.util.Json
import app.brainfit.games.engine.GameSession
import app.brainfit.games.engine.TrialResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CorsiGameTest {

    private val game = CorsiGame()
    private val ms = 1_000_000L

    private fun trial(correct: Boolean, len: Int = 4) = TrialResult(
        paramsJson = Json.obj("len" to len),
        answerJson = Json.obj("orderErrors" to if (correct) 0 else 2),
        correct = correct,
        rtMs = 1_000,
    )

    private fun history(vararg correct: Boolean) = correct.map { trial(it) }

    @Test
    fun levelTable() {
        assertEquals((1..6).toList(), game.levels.map { it.level })
        assertEquals(listOf(false, false, false, true, true, true), game.levels.map { it.reverse })
        game.levels.forEach {
            assertEquals(8, game.roundsPerSession(it.level))
            assertTrue(it.startLength in CorsiGame.MIN_LENGTH..CorsiGame.MAX_LENGTH)
            assertTrue(it.startLength <= it.gridSize * it.gridSize)
        }
    }

    @Test
    fun lengthAdaptsWithinSession() {
        // Уровень 2 начинается с 4.
        assertEquals(4, game.sequenceLength(2, emptyList()))
        assertEquals(4, game.sequenceLength(2, history(true)))
        assertEquals(5, game.sequenceLength(2, history(true, true)))
        // Успех, потом ошибка: счётчики сбрасываются.
        assertEquals(4, game.sequenceLength(2, history(true, false, true)))
        assertEquals(3, game.sequenceLength(2, history(false, false)))
        assertEquals(4, game.sequenceLength(2, history(false, false, true, true)))
        assertEquals(6, game.sequenceLength(2, history(true, true, true, true)))
        // Границы: не короче 3 и не длиннее 9.
        assertEquals(3, game.sequenceLength(1, history(false, false, false, false)))
        assertEquals(9, game.sequenceLength(2, List(40) { trial(true) }))
    }

    @Test
    fun generation_10000PerLevel_noImmediateRepeats() {
        for (cfg in game.levels) {
            val total = cfg.gridSize * cfg.gridSize
            val used = HashSet<Int>()
            repeat(10_000) { seed ->
                val p = game.generate(cfg.level, Random(seed), emptyList())
                assertEquals(cfg.startLength, p.cells.size)
                assertEquals(cfg.gridSize, p.gridSize)
                assertEquals(cfg.reverse, p.reverse)
                assertEquals(cfg.highlightMs, p.highlightMs)
                assertTrue(p.cells.all { it in 0 until total })
                p.cells.zipWithNext().forEach { (a, b) -> assertTrue("повтор подряд: ${p.cells}", a != b) }
                used += p.cells
            }
            // Выпадают все клетки сетки.
            assertEquals(total, used.size)
        }
        // Длина берётся из истории.
        assertEquals(6, game.generate(2, Random(1), history(true, true, true, true)).cells.size)
        assertEquals(game.generate(3, Random(9), emptyList()), game.generate(3, Random(9), emptyList()))
    }

    @Test
    fun showingTimelineThenInput() {
        val params = CorsiParams(gridSize = 3, cells = listOf(4, 0, 8), reverse = false, highlightMs = 700)
        // 700 мс до первой клетки, клетка горит 700, пауза 300: шаг 1000 мс.
        assertEquals(3_400L, game.showEndMs(params))
        var s = game.initialState(params, 1)
        fun litAt(tMs: Long): Int? {
            s = game.tick(s, tMs * ms)
            return s.lit
        }
        assertNull(litAt(0))
        assertNull(litAt(699))
        assertEquals(4, litAt(700))
        assertEquals(4, litAt(1_399))
        assertNull(litAt(1_400))
        assertNull(litAt(1_699))
        assertEquals(0, litAt(1_700))
        assertEquals(8, litAt(2_700))
        assertEquals(8, litAt(3_399))
        assertEquals(CorsiPhase.SHOWING, s.phase)

        // Нажатия во время показа не принимаются.
        assertSame(s, game.reduce(s, CorsiTap(4), 3_399 * ms))

        // Кадр пришёл позже конца показа — ввод всё равно считается с 3400 мс.
        s = game.tick(s, 3_450 * ms)
        assertEquals(CorsiPhase.INPUT, s.phase)
        assertNull(s.lit)
        assertEquals(3_400 * ms, s.inputStartNanos)
        assertSame(s, game.tick(s, 5_000 * ms))
    }

    private fun inputState(cells: List<Int>, reverse: Boolean = false): CorsiState {
        val params = CorsiParams(gridSize = 4, cells = cells, reverse = reverse, highlightMs = 600)
        return game.tick(game.initialState(params, 2), game.showEndMs(params) * ms)
    }

    private fun CorsiState.tapAll(cells: List<Int>, stepMs: Long = 500): CorsiState {
        var s = this
        var now = inputStartNanos
        cells.forEach {
            now += stepMs * ms
            s = game.reduce(s, CorsiTap(it), now)
        }
        return s
    }

    @Test
    fun forwardInputCorrect() {
        val s = inputState(listOf(1, 5, 9, 13)).tapAll(listOf(1, 5, 9, 13))
        assertTrue(game.isFinished(s))
        val t = game.trials(s).single()
        assertTrue(t.correct)
        assertNull(t.errorType)
        // Время — от конца показа до последнего нажатия.
        assertEquals(2_000L, t.rtMs)
        assertEquals("""{"len":4,"cells":[1,5,9,13],"grid":4,"reverse":false,"highlightMs":600}""", t.paramsJson)
        assertEquals("""{"taps":[1,5,9,13],"orderErrors":0}""", t.answerJson)
        // Лишние нажатия после конца не принимаются.
        assertSame(s, game.reduce(s, CorsiTap(0), 99_999 * ms))
    }

    @Test
    fun reverseInputExpectsBackwardsOrder() {
        val cells = listOf(2, 7, 11)
        assertTrue(game.trials(inputState(cells, reverse = true).tapAll(cells.reversed())).single().correct)
        val forwardByMistake = game.trials(inputState(cells, reverse = true).tapAll(cells)).single()
        assertFalse(forwardByMistake.correct)
        assertEquals("order", forwardByMistake.errorType)
        // Средняя клетка совпала, крайние — нет.
        assertEquals(2, Json.int(Json.parseObject(forwardByMistake.answerJson), "orderErrors"))
    }

    @Test
    fun partialInputHasNoTrialsAndBadCellsAreIgnored() {
        val s = inputState(listOf(0, 1, 2)).tapAll(listOf(0, 1))
        assertFalse(game.isFinished(s))
        assertTrue(game.trials(s).isEmpty())
        assertSame(s, game.reduce(s, CorsiTap(16), 9_000 * ms))
        assertSame(s, game.reduce(s, CorsiTap(-1), 9_000 * ms))
    }

    @Test
    fun scoreIsLongestCorrectSpanOverTarget() {
        // Уровень 2: цель 6.
        val trials = listOf(trial(true, 4), trial(true, 4), trial(false, 5), trial(true, 5), trial(false, 6))
        val score = game.score(trials, 2)
        assertEquals(5.0 / 6, score.score, 1e-9)
        assertEquals(5.0, score.metrics.getValue("span"), 1e-9)
        assertEquals(3.0, score.metrics.getValue("correct"), 1e-9)
        assertEquals(5.0, score.metrics.getValue("sequences"), 1e-9)
        assertEquals(0.6, score.metrics.getValue("correctShare"), 1e-9)
        assertEquals(4.0, score.metrics.getValue("orderErrors"), 1e-9)

        assertEquals(1.0, game.score(listOf(trial(true, 8)), 2).score, 1e-9)
        assertEquals(0.0, game.score(listOf(trial(false, 4), trial(false, 4)), 2).score, 1e-9)
        assertEquals(0.0, game.score(emptyList(), 2).score, 1e-9)

        assertEquals("верно, длина 4", game.roundSummary(listOf(trial(true, 4))))
        assertEquals("ошибка в порядке: 2 из 5", game.roundSummary(listOf(trial(false, 5))))
    }

    @Test
    fun fullSessionThroughEngineGrowsTheSequence() {
        val session = GameSession(game, level = 2, seed = 3)
        var now = 0L
        val lengths = mutableListOf<Int>()
        repeat(8) {
            session.startRound(now)
            val roundStart = now
            // Кадры раз в 16 мс, пока не начнётся ввод.
            while (session.state!!.phase == CorsiPhase.SHOWING) {
                now += 16 * ms
                session.tick(now)
                if (now - roundStart > 20_000 * ms) error("показ не кончился")
            }
            val s = session.state!!
            lengths += s.length
            var completed = false
            s.params.expected.forEach {
                now += 400 * ms
                completed = session.input(CorsiTap(it), now) != null
            }
            assertTrue(completed)
        }
        assertEquals(listOf(4, 4, 5, 5, 6, 6, 7, 7), lengths)
        assertEquals(GameSession.Phase.FINISHED, session.phase)
        val score = session.score()
        assertEquals(1.0, score.score, 1e-9)
        assertEquals(7.0, score.metrics.getValue("span"), 1e-9)
    }
}
