package app.brainfit.games.doubledecision

import app.brainfit.core.util.Json
import app.brainfit.games.engine.GameSession
import app.brainfit.games.engine.TrialResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

class DoubleDecisionGameTest {

    private val game = DoubleDecisionGame()
    private val ms = 1_000_000L

    @Test
    fun levelTableFollowsSpec() {
        assertEquals((1..6).toList(), game.levels.map { it.level })
        // Без отвлекающих → 12 → 24; фигуры в центре всё более похожи.
        assertEquals(listOf(0, 0, 12, 12, 24, 24), game.levels.map { it.distractors })
        game.levels.zipWithNext().forEach { (a, b) -> assertTrue(b.aspect <= a.aspect) }
        assertTrue(game.levels.all { it.aspect > 1.0 })
        game.levels.forEach { assertEquals(3, game.roundsPerSession(it.level)) }
    }

    @Test
    fun layoutSlotsDoNotOverlapAndStayInsideTheField() {
        val points = (0 until DdLayout.SLOTS).map { DdLayout.slot(it) }
        points.forEach { (x, y) ->
            val r = hypot(x, y)
            // Не залезают на фигуру в центре и не выходят за поле.
            assertTrue(r > 0.3 && r < 0.92)
        }
        var minDistance = Double.MAX_VALUE
        for (i in points.indices) for (j in i + 1 until points.size) {
            minDistance = minOf(minDistance, hypot(points[i].first - points[j].first, points[i].second - points[j].second))
        }
        assertTrue("слоты слишком близко: $minDistance", minDistance > 0.25)
        // Позиция 0 — на 12 часов, 2 — на 3 часа.
        assertEquals(0.0, DdLayout.target(0).first, 1e-9)
        assertTrue(DdLayout.target(0).second < 0)
        assertEquals(DdLayout.MIDDLE, DdLayout.target(2).first, 1e-9)
    }

    @Test
    fun generator_10000PerLevel() {
        for (cfg in game.levels) {
            val targetsSeen = IntArray(8)
            repeat(10_000) { seed ->
                val p = game.generate(cfg.level, Random(seed), emptyList())
                assertEquals(10, p.trials.size)
                assertEquals(cfg.aspect, p.aspect, 1e-9)
                assertEquals(500.0, p.startExposureMs, 1e-9)
                assertEquals(0, p.startStreak)
                // Фигур поровну: угадывание даёт ровно половину.
                assertEquals(5, p.trials.count { it.center == DdShape.WIDE })
                p.trials.forEach { t ->
                    assertTrue(t.target in 0..7)
                    targetsSeen[t.target]++
                    assertEquals(cfg.distractors, t.distractors.size)
                    assertEquals(t.distractors.size, t.distractors.toSet().size)
                    assertTrue(t.distractors.all { it in 0 until DdLayout.SLOTS })
                    // Отвлекающая фигура никогда не стоит на месте цели: ответ однозначен.
                    assertTrue(t.target !in t.distractors)
                }
            }
            // Цель бывает во всех 8 позициях примерно поровну.
            targetsSeen.forEach { assertTrue("позиции цели: ${targetsSeen.toList()}", it in 11_000..14_000) }
        }
        assertEquals(game.generate(5, Random(2), emptyList()), game.generate(5, Random(2), emptyList()))
    }

    @Test
    fun staircaseTwoDownOneUpInFrames() {
        val f60 = 1000.0 / 60
        assertEquals(30, DdStaircase.framesFor(500.0, f60))
        assertEquals(60, DdStaircase.framesFor(500.0, 1000.0 / 120))
        assertEquals(1, DdStaircase.framesFor(1.0, f60))
        // Один верный — без шага, два подряд — ×0,8, ошибка — ÷0,8.
        assertEquals(30 to 1, DdStaircase.next(30, 0, true, f60))
        assertEquals(24 to 0, DdStaircase.next(30, 1, true, f60))
        assertEquals(30 to 0, DdStaircase.next(24, 1, false, f60))
        // Шаг — хотя бы на кадр, и не ниже одного кадра.
        assertEquals(1 to 0, DdStaircase.next(2, 1, true, f60))
        assertEquals(1 to 0, DdStaircase.next(1, 1, true, f60))
        assertEquals(2 to 0, DdStaircase.next(1, 0, false, f60))
        // Не дольше секунды.
        assertEquals(60 to 0, DdStaircase.next(60, 0, false, f60))
    }

    /** Кадры с частотой [hz], начиная с [fromNanos]; возвращает время последнего кадра. */
    private fun frames(state: DdState, hz: Int, fromNanos: Long, until: (DdState) -> Boolean): Pair<DdState, Long> {
        var s = state
        var frame = 0L
        val period = 1_000_000_000L / hz
        var now = fromNanos
        while (!until(s)) {
            now = fromNanos + frame++ * period
            s = game.tick(s, now)
            if (frame > 10_000) error("фаза не сменилась: ${s.phase}")
        }
        return s to now
    }

    private fun oneTrialAt(hz: Int): DdState {
        val params = DdParams(
            trials = listOf(DdTrialSpec(DdShape.TALL, target = 3, distractors = emptyList(), maskSeed = 1)),
            aspect = 1.6,
            startExposureMs = 500.0,
            startStreak = 0,
        )
        var (s, now) = frames(game.initialState(params, 1), hz, 0) { it.phase == DdPhase.STIMULUS }
        assertTrue(now >= 700 * ms)
        val onset = now
        // На показе ответы не принимаются.
        assertSame(s, game.reduce(s, DdInput.Center(DdShape.TALL), now))
        var stimulusFrames = 0
        while (s.phase == DdPhase.STIMULUS) {
            stimulusFrames++
            now += 1_000_000_000L / hz
            s = game.tick(s, now)
        }
        assertEquals(DdPhase.MASK, s.phase)
        assertEquals(s.exposureFrames, stimulusFrames)
        assertEquals(500.0, s.actualMs, 1.0)
        assertEquals((now - onset) / ms.toDouble(), s.actualMs, 1e-9)
        s = frames(s, hz, now) { it.phase == DdPhase.CENTER }.first
        return s
    }

    @Test
    fun oneTrialAt60And120Hz() {
        for ((hz, expectedFrames) in listOf(60 to 30, 120 to 60)) {
            var s = oneTrialAt(hz)
            assertEquals(1000.0 / hz, s.frameMs, 0.01)
            assertEquals(expectedFrames, s.exposureFrames)
            val t0 = s.phaseStartNanos
            // Позицию нельзя дать раньше фигуры.
            assertSame(s, game.reduce(s, DdInput.Periphery(3), t0))
            s = game.reduce(s, DdInput.Center(DdShape.TALL), t0 + 640 * ms)
            assertEquals(DdPhase.PERIPHERY, s.phase)
            assertSame(s, game.reduce(s, DdInput.Periphery(8), t0 + 900 * ms))
            s = game.reduce(s, DdInput.Periphery(3), t0 + 1_200 * ms)
            assertTrue(game.isFinished(s))
            val trial = s.trials.single()
            assertTrue(trial.correct)
            assertNull(trial.errorType)
            assertEquals(640L, trial.rtMs)
            val p = Json.parseObject(trial.paramsJson)
            assertEquals(expectedFrames.toLong(), p["frames"])
            assertEquals("TALL", p["center"])
            assertEquals("""{"center":"TALL","position":3}""", trial.answerJson)
            assertEquals(DdFeedback(centerOk = true, peripheryOk = true, target = 3), s.feedback)
            // Один верный — экспозиция та же, счётчик 1.
            assertEquals(1, s.streak)
        }
    }

    @Test
    fun droppedFramesDoNotStretchTheExposure() {
        val params = DdParams(
            trials = listOf(DdTrialSpec(DdShape.WIDE, target = 0, distractors = emptyList(), maskSeed = 1)),
            aspect = 1.6,
            startExposureMs = 100.0,
            startStreak = 0,
        )
        val period = 1_000_000_000L / 60
        var (s, now) = frames(game.initialState(params, 1), 60, 0) { it.phase == DdPhase.STIMULUS }
        assertEquals(6, s.exposureFrames)
        val onset = now
        // Экран отдаёт каждый второй кадр: 6 тиков — это уже 200 мс, а показ должен кончиться около 100.
        while (s.phase == DdPhase.STIMULUS) {
            now += 2 * period
            s = game.tick(s, now)
        }
        assertEquals(DdPhase.MASK, s.phase)
        assertEquals((now - onset) / ms.toDouble(), s.actualMs, 1e-9)
        assertTrue("показ шёл ${s.actualMs} мс", s.actualMs in 99.0..120.0)
    }

    @Test
    fun errorTypes() {
        fun play(center: DdShape, position: Int): TrialResult {
            var s = oneTrialAt(60)
            s = game.reduce(s, DdInput.Center(center), s.phaseStartNanos + 500 * ms)
            s = game.reduce(s, DdInput.Periphery(position), s.phaseStartNanos + 500 * ms)
            // Ошибка: 30 кадров ÷ 0,8 = 37,5 → 38 кадров по 16,67 мс.
            assertEquals(38 * 16.67, s.exposureMs, 1e-6)
            return s.trials.single()
        }
        assertEquals("center", play(DdShape.WIDE, 3).errorType)
        assertEquals("periphery", play(DdShape.TALL, 4).errorType)
        assertEquals("both", play(DdShape.WIDE, 0).errorType)
    }

    private fun trial(frames: Int, frameMs: Double, correct: Boolean) = TrialResult(
        paramsJson = Json.obj("center" to "WIDE", "target" to 0, "frames" to frames, "frameMs" to frameMs, "exposureMs" to frames * frameMs),
        answerJson = Json.obj("center" to "WIDE", "position" to if (correct) 0 else 1),
        correct = correct,
        rtMs = 500,
        errorType = if (correct) null else "periphery",
    )

    @Test
    fun thresholdFromLastSixReversals() {
        // 50 кадров по 10 мс: ВВ (вниз до 40), О (вверх до 50) — и так по кругу.
        val pattern = listOf(50 to true, 50 to true, 40 to false)
        val trials = List(4) { pattern }.flatten().map { (f, c) -> trial(f, 10.0, c) }
        val (threshold, reversals) = game.threshold(trials)!!
        assertEquals(7, reversals)
        assertEquals(sqrt(500.0 * 400.0), threshold, 1e-9)
        val score = game.score(trials, 1)
        assertEquals(100.0 / sqrt(500.0 * 400.0), score.score, 1e-9)
        assertEquals(1.0, score.metrics.getValue("centerAccuracy"), 1e-9)
        assertEquals(8.0 / 12, score.metrics.getValue("peripheryAccuracy"), 1e-9)
        assertEquals(listOf("Порог экспозиции: 447 мс", "Центр верно: 100 %", "Цель верно: 67 %"), game.resultLines(score))
    }

    @Test
    fun thresholdFromLastTenTrialsWhenFewReversals() {
        val trials = (1..12).map { trial(it, 10.0, true) }
        val (threshold, reversals) = game.threshold(trials)!!
        assertEquals(0, reversals)
        val expected = Math.exp((3..12).sumOf { Math.log(it * 10.0) } / 10)
        assertEquals(expected, threshold, 1e-9)
        // Порог ниже цели — score 1.
        assertEquals(1.0, game.score(trials, 5).score, 1e-9)
        assertEquals(0.0, game.score(emptyList(), 1).score, 1e-9)
    }

    @Test
    fun replayContinuesStaircaseAcrossRounds() {
        val f = 16.67
        assertEquals(500.0 to 0, game.replay(emptyList()))
        assertEquals(30 * f to 1, game.replay(listOf(trial(30, f, true))))
        assertEquals(24 * f to 0, game.replay(listOf(trial(30, f, true), trial(30, f, true))))
        assertEquals(30 * f to 0, game.replay(listOf(trial(30, f, true), trial(30, f, true), trial(24, f, false))))
    }

    @Test
    fun fullSessionThroughEngineAt60Hz() {
        val session = GameSession(game, level = 1, seed = 5)
        val period = 1_000_000_000L / 60
        var now = 0L
        val framesByRound = mutableListOf<List<Int>>()
        repeat(3) {
            session.startRound(now)
            var done = false
            while (!done) {
                now += period
                done = session.tick(now) != null
                val s = session.state ?: break
                val spec = s.spec ?: continue
                when (s.phase) {
                    DdPhase.CENTER -> session.input(DdInput.Center(spec.center), now)
                    DdPhase.PERIPHERY -> done = session.input(DdInput.Periphery(spec.target), now) != null
                    else -> Unit
                }
            }
            framesByRound += session.completedRounds.last().trials.map { Json.int(Json.parseObject(it.paramsJson), "frames")!! }
        }
        // Всё верно: лестница идёт вниз через все раунды, с разворотов начинать не нужно.
        assertEquals(
            listOf(
                listOf(30, 30, 24, 24, 19, 19, 15, 15, 12, 12),
                listOf(10, 10, 8, 8, 6, 6, 5, 5, 4, 4),
                listOf(3, 3, 2, 2, 1, 1, 1, 1, 1, 1),
            ),
            framesByRound,
        )
        assertEquals(GameSession.Phase.FINISHED, session.phase)
        val score = session.score()
        assertEquals(1.0, score.score, 1e-9)
        assertEquals(1.0, score.metrics.getValue("accuracy"), 1e-9)
        assertEquals("верно 10 из 10, экспозиция 17 мс", game.roundSummary(session.completedRounds.last().trials))
    }

    @Test
    fun demoIsShortAndPlain() {
        val demo = game.generateDemo(Random(1))
        assertTrue(demo.isDemo)
        assertEquals(3, demo.trials.size)
        assertTrue(demo.trials.all { it.distractors.isEmpty() })
    }
}
