package app.brainfit.games.schulte

import app.brainfit.games.engine.GameSession
import app.brainfit.games.engine.TrialResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SchulteGameTest {

    private val game = SchulteGame()
    private val ms = 1_000_000L

    private fun SchulteState.cellOf(number: Int) = params.layout.indexOf(number)

    /** Пройти таблицу без ошибок: по [stepMs] на число. */
    private fun solve(state: SchulteState, stepMs: Long, startNanos: Long = 0): SchulteState {
        var s = state
        var now = startNanos
        while (!game.isFinished(s)) {
            now += stepMs * ms
            s = game.reduce(s, SchulteTap(s.cellOf(s.target)), now)
        }
        return s
    }

    @Test
    fun levelTableMatchesSpec() {
        assertEquals((1..6).toList(), game.levels.map { it.level })
        assertEquals(listOf(3, 4, 5, 5, 6, 6), game.levels.map { it.size })
        assertEquals(listOf(false, false, false, true, false, true), game.levels.map { it.reverse })
        game.levels.forEach { assertEquals(3, game.roundsPerSession(it.level)) }
    }

    @Test
    fun layoutIsAlwaysAPermutation_10000PerLevel() {
        for (cfg in game.levels) {
            val expected = (1..cfg.cells).toList()
            var identity = 0
            repeat(10_000) { seed ->
                val p = game.generate(cfg.level, Random(seed), emptyList())
                assertEquals(cfg.size, p.size)
                assertEquals(cfg.reverse, p.reverse)
                assertEquals(expected, p.layout.sorted())
                if (p.layout == expected) identity++
            }
            // Неперемешанная таблица 3×3 выпадает с шансом 1/9!, то есть практически никогда.
            assertTrue("уровень ${cfg.level}: $identity неперемешанных", identity <= 1)
        }
    }

    @Test
    fun sameSeedSameLayout() {
        val a = game.generate(3, Random(42), emptyList())
        val b = game.generate(3, Random(42), emptyList())
        val c = game.generate(3, Random(43), emptyList())
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test
    fun forwardRoundRecordsTimeBetweenCorrectTaps() {
        val start = game.initialState(game.generate(2, Random(1), emptyList()), 2)
        assertEquals(1, start.target)
        val done = solve(start, stepMs = 800)
        assertTrue(game.isFinished(done))
        assertEquals(16, done.found)
        val trials = game.trials(done)
        assertEquals(16, trials.size)
        assertTrue(trials.all { it.correct && it.rtMs == 800L && it.errorType == null })
        assertEquals("""{"target":1}""", trials.first().paramsJson)
        // После конца раунда нажатия ничего не меняют.
        assertSame(done, game.reduce(done, SchulteTap(0), 99_999 * ms))
    }

    @Test
    fun reverseRoundGoesFromTheLargestNumber() {
        val start = game.initialState(game.generate(4, Random(2), emptyList()), 4)
        assertEquals(25, start.target)
        val afterOne = game.reduce(start, SchulteTap(start.cellOf(25)), 500 * ms)
        assertEquals(24, afterOne.target)
        // Тап по 1 в обратной таблице — ошибка.
        val wrong = game.reduce(afterOne, SchulteTap(afterOne.cellOf(1)), 600 * ms)
        assertEquals(1, wrong.wrongTaps)
        val done = solve(wrong, stepMs = 100, startNanos = 600 * ms)
        assertEquals(25, done.found)
        assertEquals(26, done.trials.size)
    }

    @Test
    fun wrongTapIsRecordedButDoesNotCountOrResetTime() {
        val s0 = game.initialState(game.generate(1, Random(3), emptyList()), 1)
        val wrongCell = s0.cellOf(5)
        val s1 = game.reduce(s0, SchulteTap(wrongCell), 300 * ms)
        assertEquals(0, s1.found)
        assertEquals(1, s1.wrongTaps)
        assertEquals(wrongCell, s1.wrongCell)
        val wrong = s1.trials.single()
        assertFalse(wrong.correct)
        assertNull(wrong.rtMs)
        assertEquals("wrong_tap", wrong.errorType)
        assertEquals("""{"cell":$wrongCell,"number":5}""", wrong.answerJson)
        // Верный тап считает время от начала раунда, а не от ошибки.
        val s2 = game.reduce(s1, SchulteTap(s1.cellOf(1)), 1_000 * ms)
        assertEquals(1_000L, s2.trials.last().rtMs)
        // Клетка вне сетки игнорируется.
        assertSame(s2, game.reduce(s2, SchulteTap(9), 1_100 * ms))
        assertSame(s2, game.reduce(s2, SchulteTap(-1), 1_100 * ms))
    }

    private fun correct(rt: Long) = TrialResult("{}", "{}", correct = true, rtMs = rt)
    private val wrong = TrialResult("{}", "{}", correct = false, rtMs = null, errorType = "wrong_tap")

    @Test
    fun scoreIsAccuracyTimesSpeed() {
        // Уровень 2: цель 1300 мс на клетку.
        val fast = game.score(List(48) { correct(1_000) }, 2)
        assertEquals(1.0, fast.score, 1e-9)
        assertEquals(1_000.0, fast.metrics.getValue("msPerCell"), 1e-9)

        val slow = game.score(List(48) { correct(2_600) }, 2)
        assertEquals(0.5, slow.score, 1e-9)

        val sloppy = game.score(List(48) { correct(1_000) } + List(16) { wrong }, 2)
        assertEquals(0.75, sloppy.score, 1e-9)
        assertEquals(16.0, sloppy.metrics.getValue("wrongTaps"), 1e-9)
        assertEquals(0.75, sloppy.metrics.getValue("accuracy"), 1e-9)

        assertEquals(0.0, game.score(emptyList(), 2).score, 1e-9)
        assertEquals(0.0, game.score(List(3) { wrong }, 2).score, 1e-9)
    }

    @Test
    fun fullSessionThroughEngine() {
        val session = GameSession(game, level = 1, seed = 7)
        var now = 0L
        repeat(3) {
            session.startRound(now)
            var completed = false
            while (!completed) {
                now += 900 * ms
                val s = session.state!!
                completed = session.input(SchulteTap(s.cellOf(s.target)), now) != null
            }
        }
        assertEquals(GameSession.Phase.FINISHED, session.phase)
        assertEquals(27, session.history().size)
        val score = session.score()
        assertEquals(1.0, score.score, 1e-9)
        assertEquals(900.0, score.metrics.getValue("msPerCell"), 1e-9)
        assertEquals("время 8,1 с, ошибок: 0", game.roundSummary(session.completedRounds.last().trials))
        assertEquals(
            listOf("Время на ячейку: 0,9 с", "Точность: 100 %", "Ошибочных нажатий: 0"),
            game.resultLines(score),
        )
    }
}
