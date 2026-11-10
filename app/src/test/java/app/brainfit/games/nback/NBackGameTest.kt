package app.brainfit.games.nback

import app.brainfit.core.util.Json
import app.brainfit.games.engine.GameSession
import app.brainfit.games.engine.ResumePolicy
import app.brainfit.games.engine.Stats
import app.brainfit.games.engine.TrialResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NBackGameTest {

    private val game = NBackGame()
    private val ms = 1_000_000L

    @Test
    fun fifteenLevelsNFrom1To5WithThreeIntervals() {
        assertEquals((1..15).toList(), game.levels.map { it.level })
        assertEquals(listOf(1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 5), game.levels.map { it.n })
        assertEquals(listOf(2_500L, 2_150L, 1_800L), game.levels.take(3).map { it.intervalMs })
        assertEquals(ResumePolicy.RESTART_ROUND, game.resumePolicy)
        game.levels.forEach { assertEquals(2, game.roundsPerSession(it.level)) }
    }

    @Test
    fun generator_10000PerLevel_targetsAreExactlyWhatTheSequenceSays() {
        for (cfg in game.levels) {
            val n = cfg.n
            var lures = 0
            repeat(10_000) { seed ->
                val p = game.generate(cfg.level, Random(seed), emptyList())
                assertEquals(20 + n, p.count)
                assertEquals(n, p.n)
                assertEquals(500L, p.showMs)
                assertEquals(cfg.intervalMs, p.intervalMs)
                assertTrue(p.cells.all { it in 0..8 })
                // Цель — ровно тогда, когда позиция совпадает с N назад. Так ответ однозначен.
                for (i in 0 until p.count) {
                    val actual = i >= n && p.cells[i] == p.cells[i - n]
                    assertEquals("уровень ${cfg.level}, seed $seed, стимул $i", actual, p.targets[i])
                    if (p.lures[i]) {
                        assertFalse(p.targets[i])
                        assertTrue(n >= 2)
                        val nearMinus = p.cells[i] == p.cells[i - n + 1]
                        val nearPlus = i - n - 1 >= 0 && p.cells[i] == p.cells[i - n - 1]
                        assertTrue("ловушка не ловушка: ${p.cells}, $i", nearMinus || nearPlus)
                        lures++
                    } else if (n >= 2 && i >= n && !p.targets[i]) {
                        // Обычная не-цель не попадает на N−1 и N+1 назад: ловушек ровно столько, сколько помечено.
                        assertTrue(p.cells[i] != p.cells[i - n + 1])
                        if (i - n - 1 >= 0) assertTrue(p.cells[i] != p.cells[i - n - 1])
                    }
                }
                assertEquals(6, p.targets.count { it })
                // Не больше двух целей подряд.
                p.targets.windowed(3).forEach { assertFalse("три цели подряд: ${p.targets}", it.all { t -> t }) }
            }
            if (n >= 2) {
                // Около 10 %: в среднем почти 2 ловушки на раунд из 20.
                assertTrue("уровень ${cfg.level}: ловушек ${lures / 10_000.0}", lures / 10_000.0 in 1.8..2.0)
            } else {
                assertEquals(0, lures)
            }
        }
        assertEquals(game.generate(7, Random(1), emptyList()), game.generate(7, Random(1), emptyList()))
    }

    /** 2-back: шаг 500 + 2500 = 3000 мс, первый стимул на 1000 мс. */
    private val params = NBackParams(
        n = 2,
        cells = listOf(0, 4, 0, 4, 8, 8),
        targets = listOf(false, false, true, true, false, false),
        lures = listOf(false, false, false, false, false, true),
        showMs = 500,
        intervalMs = 2_500,
    )

    @Test
    fun timelineLightsCellsAndClosesWindows() {
        var s = game.initialState(params, 4)
        s = game.tick(s, 999 * ms)
        assertNull(s.lit)
        assertEquals(-1, s.current)
        s = game.tick(s, 1_000 * ms)
        assertEquals(0, s.lit)
        assertEquals(0, s.current)
        s = game.tick(s, 1_499 * ms)
        assertEquals(0, s.lit)
        s = game.tick(s, 1_500 * ms)
        assertNull(s.lit)
        assertSame(s, game.tick(s, 2_000 * ms))
        s = game.tick(s, 4_000 * ms)
        assertEquals(4, s.lit)
        assertEquals(1, s.current)
        // Окна первых двух стимулов закрылись, но они «для запоминания» и проб не дают.
        s = game.tick(s, 7_000 * ms)
        assertEquals(2, s.closed)
        assertTrue(s.trials.isEmpty())
        // Последний стимул (5) начинается на 16 000, его окно закрывается на 19 000.
        s = game.tick(s, 18_999 * ms)
        assertFalse(game.isFinished(s))
        s = game.tick(s, 19_000 * ms)
        assertTrue(game.isFinished(s))
        assertEquals(4, s.trials.size)
    }

    @Test
    fun pressesClassifiedAsHitMissFalseAlarmAndRejection() {
        var s = game.initialState(params, 4)
        // Нажатие до первого стимула и на стимулах «для запоминания» не считается.
        s = game.reduce(s, NBackPress, 500 * ms)
        s = game.reduce(s, NBackPress, 4_100 * ms)
        assertTrue(s.presses.isEmpty())
        // Стимул 2 (цель): нажатие через 650 мс; повторное нажатие не меняет время.
        s = game.reduce(s, NBackPress, 7_650 * ms)
        s = game.reduce(s, NBackPress, 8_000 * ms)
        assertEquals(mapOf(2 to 650L), s.presses)
        // Стимул 3 (цель) — пропуск. Стимул 4 (не цель) — молчим. Стимул 5 (ловушка) — нажатие в паузе после показа.
        s = game.reduce(s, NBackPress, 18_500 * ms)
        s = game.tick(s, 19_000 * ms)
        assertTrue(game.isFinished(s))
        val t = s.trials
        assertEquals(listOf(2, 3, 4, 5), t.map { Json.int(Json.parseObject(it.paramsJson), "i") })
        assertEquals(listOf(true, false, true, false), t.map { it.correct })
        assertEquals(listOf(null, "miss", null, "false_alarm"), t.map { it.errorType })
        assertEquals(listOf(650L, null, null, 2_500L), t.map { it.rtMs })
        assertEquals("""{"i":5,"cell":8,"target":false,"lure":true,"n":2,"intervalMs":2500}""", t.last().paramsJson)
        assertEquals("""{"pressed":true}""", t.last().answerJson)
        // После конца ничего не принимается.
        assertSame(s, game.reduce(s, NBackPress, 19_100 * ms))
    }

    private fun trial(target: Boolean, pressed: Boolean) = TrialResult(
        paramsJson = Json.obj("target" to target),
        answerJson = Json.obj("pressed" to pressed),
        correct = target == pressed,
        rtMs = if (pressed) 700L else null,
    )

    @Test
    fun scoreIsMeanOfHitRateAndCorrectRejectionRate() {
        val targets = { pressed: Int -> List(6) { trial(true, it < pressed) } }
        val nonTargets = { pressed: Int -> List(14) { trial(false, it < pressed) } }

        // «Никогда не нажимать» — 0,5, а не 0,7 как по формуле ТЗ (она лежит в метрике accuracy).
        val silent = game.score(targets(0) + nonTargets(0), 4)
        assertEquals(0.5, silent.score, 1e-9)
        assertEquals(0.7, silent.metrics.getValue("accuracy"), 1e-9)
        assertEquals(0.5, game.score(targets(6) + nonTargets(14), 4).score, 1e-9)

        val perfect = game.score(targets(6) + nonTargets(0), 4)
        assertEquals(1.0, perfect.score, 1e-9)
        // d′ с поправкой log-linear: H = 6,5/7, F = 0,5/15.
        assertEquals(Stats.normalQuantile(6.5 / 7) - Stats.normalQuantile(0.5 / 15), perfect.metrics.getValue("dPrime"), 1e-9)
        assertEquals(700.0, perfect.metrics.getValue("medianHitRtMs"), 1e-9)

        val mixed = game.score(targets(3) + nonTargets(7), 4)
        assertEquals((0.5 + 0.5) / 2, mixed.score, 1e-9)
        val good = game.score(targets(5) + nonTargets(1), 4)
        assertEquals((5.0 / 6 + 13.0 / 14) / 2, good.score, 1e-9)
        assertEquals(2.0, good.metrics.getValue("n"), 1e-9)
        assertEquals(
            listOf("N = 2", "Попаданий: 5 из 6", "Лишних нажатий: 1 из 14", "Верных ответов: 90 %", "d′: ${"%.2f".format(java.util.Locale.ROOT, good.metrics.getValue("dPrime")).replace('.', ',')}"),
            game.resultLines(good),
        )
        assertEquals("попаданий 5 из 6, лишних нажатий: 1", game.roundSummary(targets(5) + nonTargets(1)))
        assertEquals(0.0, game.score(emptyList(), 1).score, 1e-9)
    }

    @Test
    fun normalQuantileAndDPrimeKnownValues() {
        assertEquals(0.0, Stats.normalQuantile(0.5), 1e-9)
        assertEquals(1.959963985, Stats.normalQuantile(0.975), 1e-6)
        assertEquals(-2.326347874, Stats.normalQuantile(0.01), 1e-6)
        assertEquals(3.090232306, Stats.normalQuantile(0.999), 1e-6)
        assertEquals(0.0, Stats.dPrime(3, 6, 7, 14), 1e-9)
    }

    @Test
    fun fullSessionThroughEngineWithPerfectPlayer() {
        val session = GameSession(game, level = 4, seed = 8)
        var now = 0L
        repeat(2) {
            session.startRound(now)
            var done = false
            while (!done) {
                now += 16 * ms
                done = session.tick(now) != null
                val s = session.state ?: break
                val i = s.current
                if (i >= s.params.n && s.params.targets[i] && i !in s.presses && s.lit != null) {
                    done = session.input(NBackPress, now) != null
                }
            }
        }
        assertEquals(GameSession.Phase.FINISHED, session.phase)
        assertEquals(40, session.history().size)
        val score = session.score()
        assertEquals(1.0, score.score, 1e-9)
        assertEquals(12.0, score.metrics.getValue("hits"), 1e-9)
    }

    @Test
    fun demoIsShortOneBack() {
        val demo = game.generateDemo(Random(3))
        assertEquals(1, demo.n)
        assertEquals(10, demo.count)
        assertEquals(3, demo.targets.count { it })
    }
}
