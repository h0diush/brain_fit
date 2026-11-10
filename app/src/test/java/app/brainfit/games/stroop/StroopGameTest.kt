package app.brainfit.games.stroop

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

class StroopGameTest {

    private val game = StroopGame()
    private val ms = 1_000_000L

    @Test
    fun levelTableFollowsSpec() {
        assertEquals((1..6).toList(), game.levels.map { it.level })
        // 3 → 4 цвета, доля конфликтных 30 % → 70 %, лимит 3000 → 1200 мс.
        assertEquals(3, game.levels.first().colors)
        assertEquals(4, game.levels.last().colors)
        assertEquals(0.3, game.levels.first().conflictShare, 1e-9)
        assertEquals(0.7, game.levels.last().conflictShare, 1e-9)
        assertEquals(3_000L, game.levels.first().limitMs)
        assertEquals(1_200L, game.levels.last().limitMs)
        game.levels.zipWithNext().forEach { (a, b) ->
            assertTrue(b.colors >= a.colors && b.conflictShare >= a.conflictShare && b.limitMs <= a.limitMs)
        }
        game.levels.forEach { assertEquals(2, game.roundsPerSession(it.level)) }
    }

    @Test
    fun generator_10000PerLevel() {
        for (cfg in game.levels) {
            val palette = StroopColor.entries.take(cfg.colors)
            val expectedConflicts = Math.round(StroopGame.STIMULI * cfg.conflictShare).toInt()
            val inksSeen = HashSet<StroopColor>()
            repeat(10_000) { seed ->
                val p = game.generate(cfg.level, Random(seed), emptyList())
                assertEquals(palette, p.colors)
                assertEquals(cfg.limitMs, p.limitMs)
                assertEquals(StroopGame.STIMULI, p.stimuli.size)
                assertEquals("уровень ${cfg.level}, seed $seed", expectedConflicts, p.stimuli.count { !it.congruent })
                p.stimuli.forEach {
                    assertTrue(it.word in palette && it.ink in palette)
                    inksSeen += it.ink
                }
                // Не больше трёх подряд одного цвета шрифта.
                p.stimuli.windowed(4).forEach { w -> assertTrue("4 подряд: $w", w.map { it.ink }.distinct().size > 1) }
            }
            assertEquals(palette.toSet(), inksSeen)
        }
        assertEquals(game.generate(4, Random(3), emptyList()), game.generate(4, Random(3), emptyList()))
    }

    private fun params(vararg stimuli: Pair<StroopColor, StroopColor>, limitMs: Long = 2_000) =
        StroopParams(StroopColor.entries.take(3), stimuli.map { StroopStimulus(it.first, it.second) }, limitMs)

    /** Дождаться показа стимула: пауза 500 мс после [from]. */
    private fun StroopState.showAt(nowMs: Long): StroopState = game.tick(this, nowMs * ms)

    @Test
    fun timelineAnswersAndTimeouts() {
        val p = params(
            StroopColor.RED to StroopColor.BLUE,
            StroopColor.YELLOW to StroopColor.YELLOW,
            StroopColor.BLUE to StroopColor.RED,
        )
        var s = game.initialState(p, 1)
        // Пока идёт пауза, ответы не принимаются и стимула нет.
        assertSame(s, game.tick(s, 499 * ms))
        assertSame(s, game.reduce(s, StroopAnswer(StroopColor.BLUE), 100 * ms))
        s = s.showAt(510)
        assertEquals(StroopPhase.STIMULUS, s.phase)
        assertEquals(510 * ms, s.phaseStartNanos)

        // Верный ответ — цвет шрифта, а не слово.
        s = game.reduce(s, StroopAnswer(StroopColor.BLUE), 1_310 * ms)
        val first = s.trials.single()
        assertTrue(first.correct)
        assertEquals(800L, first.rtMs)
        assertEquals("""{"i":0,"word":"RED","ink":"BLUE","congruent":false,"limitMs":2000}""", first.paramsJson)
        assertEquals("""{"pressed":"BLUE"}""", first.answerJson)
        assertEquals(StroopFeedback.CORRECT, s.feedback)
        assertEquals(StroopPhase.GAP, s.phase)

        // Неверный ответ.
        s = s.showAt(1_810)
        s = game.reduce(s, StroopAnswer(StroopColor.RED), 2_310 * ms)
        assertFalse(s.trials.last().correct)
        assertEquals("wrong", s.trials.last().errorType)
        assertEquals(StroopFeedback.WRONG, s.feedback)

        // Лимит 2000 мс истёк — промах без времени реакции.
        s = s.showAt(2_810)
        assertSame(s, game.tick(s, 4_809 * ms))
        s = game.tick(s, 4_810 * ms)
        val timeout = s.trials.last()
        assertFalse(timeout.correct)
        assertNull(timeout.rtMs)
        assertEquals("timeout", timeout.errorType)
        assertEquals("""{"pressed":null}""", timeout.answerJson)
        assertEquals(StroopFeedback.TIMEOUT, s.feedback)
        assertTrue(game.isFinished(s))
        assertSame(s, game.tick(s, 9_999 * ms))
    }

    @Test
    fun lateAnswerBeforeFrameIsATimeoutAndForeignColorIsIgnored() {
        var s = game.initialState(params(StroopColor.RED to StroopColor.RED), 1).showAt(500)
        // Зелёного нет на уровне с тремя цветами.
        assertSame(s, game.reduce(s, StroopAnswer(StroopColor.GREEN), 600 * ms))
        s = game.reduce(s, StroopAnswer(StroopColor.RED), 2_500 * ms)
        assertEquals("timeout", s.trials.single().errorType)
        assertFalse(s.trials.single().correct)
    }

    private fun trial(congruent: Boolean, correct: Boolean, rt: Long?) = TrialResult(
        paramsJson = Json.obj("congruent" to congruent),
        answerJson = "{}",
        correct = correct,
        rtMs = rt,
        errorType = if (correct) null else if (rt == null) "timeout" else "wrong",
    )

    @Test
    fun scoreIsShareOfCorrectAndStroopEffectIsMedianDifference() {
        val trials = listOf(
            trial(true, true, 600), trial(true, true, 700), trial(true, true, 800),
            trial(false, true, 900), trial(false, true, 1_100),
            trial(false, false, 700), trial(false, false, null), trial(true, false, null),
        )
        val score = game.score(trials, 3)
        assertEquals(5.0 / 8, score.score, 1e-9)
        assertEquals(700.0, score.metrics.getValue("medianCongruentMs"), 1e-9)
        assertEquals(1_000.0, score.metrics.getValue("medianConflictMs"), 1e-9)
        assertEquals(300.0, score.metrics.getValue("stroopEffectMs"), 1e-9)
        assertEquals(2.0, score.metrics.getValue("timeouts"), 1e-9)
        assertEquals(
            listOf(
                "Точность: 63 %",
                "Медиана, цвет совпадает со словом: 0,7 с",
                "Медиана, цвет не совпадает: 1,0 с",
                "Струп-эффект: +300 мс",
                "Не успел ответить: 2",
            ),
            game.resultLines(score),
        )

        // Нет верных конфликтных — нет и медианы, и Струп-эффекта.
        val noConflict = game.score(listOf(trial(true, true, 500), trial(false, false, 400)), 3)
        assertFalse(noConflict.metrics.containsKey("medianConflictMs"))
        assertFalse(noConflict.metrics.containsKey("stroopEffectMs"))
        assertEquals(0.0, game.score(emptyList(), 1).score, 1e-9)
    }

    @Test
    fun fullSessionThroughEngine() {
        val session = GameSession(game, level = 2, seed = 4)
        var now = 0L
        repeat(2) {
            session.startRound(now)
            // Игра видит время от начала раунда.
            val roundStart = now
            var done = false
            var answered = 0
            while (!done) {
                now += 16 * ms
                done = session.tick(now) != null
                val s = session.state ?: break
                if (s.phase == StroopPhase.STIMULUS && (now - roundStart) - s.phaseStartNanos >= 600 * ms) {
                    // Верно на всё, кроме каждого пятого — там жмём цвет слова (у конфликтных это ошибка).
                    val color = if (answered++ % 5 == 4) s.current!!.word else s.current!!.ink
                    done = session.input(StroopAnswer(color), now) != null
                }
            }
        }
        assertEquals(GameSession.Phase.FINISHED, session.phase)
        val history = session.history()
        assertEquals(40, history.size)
        assertTrue(history.none { it.errorType == "timeout" })
        assertEquals(600.0, session.score().metrics.getValue("medianCongruentMs"), 20.0)
        assertTrue(session.score().score in 0.8..1.0)
    }

    @Test
    fun demoIsShortAndEasy() {
        val demo = game.generateDemo(Random(2))
        assertEquals(6, demo.stimuli.size)
        assertEquals(3, demo.colors.size)
        assertEquals(3_000L, demo.limitMs)
    }
}
