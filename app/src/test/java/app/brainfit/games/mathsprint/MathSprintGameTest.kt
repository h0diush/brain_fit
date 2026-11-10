package app.brainfit.games.mathsprint

import app.brainfit.core.util.Json
import app.brainfit.games.engine.CompletedRound
import app.brainfit.games.engine.GameSession
import app.brainfit.games.engine.TrialResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MathSprintGameTest {

    private val game = MathSprintGame()
    private val ms = 1_000_000L
    private val second = 1_000 * ms

    /**
     * Независимый вычислитель текста примера: сначала × и ÷, потом + и −, слева направо.
     * Деление должно быть нацело — иначе тест падает.
     */
    private fun evaluate(text: String): Int {
        val tokens = text.split(" ")
        require(tokens.size % 2 == 1) { "Странный пример: $text" }
        val numbers = mutableListOf(tokens[0].toInt())
        val ops = mutableListOf<String>()
        var i = 1
        while (i < tokens.size) {
            val op = tokens[i]
            val n = tokens[i + 1].toInt()
            when (op) {
                "×" -> numbers[numbers.lastIndex] = numbers.last() * n
                "÷" -> {
                    assertEquals("деление с остатком: $text", 0, numbers.last() % n)
                    numbers[numbers.lastIndex] = numbers.last() / n
                }
                "+", "−" -> {
                    ops += op
                    numbers += n
                }
                else -> error("Неизвестная операция '$op' в $text")
            }
            i += 2
        }
        var result = numbers[0]
        ops.forEachIndexed { k, op -> result = if (op == "+") result + numbers[k + 1] else result - numbers[k + 1] }
        return result
    }

    private fun answered(p: MathProblem, given: Int = p.answer) = TrialResult(
        paramsJson = Json.obj("q" to p.text, "a" to p.answer),
        answerJson = Json.obj("given" to given),
        correct = given == p.answer,
        rtMs = 1_000,
    )

    @Test
    fun levelTable() {
        assertEquals((1..5).toList(), game.levels.map { it.level })
        game.levels.forEach { assertEquals(2, game.roundsPerSession(it.level)) }
    }

    @Test
    fun everyProblemInEveryPoolIsCorrectPositiveAndFitsTheKeypad() {
        for (level in 1..5) {
            val pool = MathPools.pool(level)
            assertTrue("уровень $level: пул слишком мал (${pool.size})", pool.size >= 80)
            assertEquals("уровень $level: тексты повторяются", pool.size, pool.map { it.text }.toSet().size)
            pool.forEach { p ->
                assertEquals(p.text, evaluate(p.text), p.answer)
                assertTrue("ответ меньше 1: $p", p.answer >= 1)
                assertTrue("ответ длиннее клавиатуры: $p", p.answer.toString().length <= MathSprintGame.MAX_DIGITS)
                // Знак минуса — типографский, как на экране.
                assertFalse(p.text, p.text.contains('-'))
            }
        }
        // Уровень 1 — только сложение и вычитание до 20.
        assertTrue(MathPools.pool(1).all { p -> p.text.split(" ").filterIndexed { i, _ -> i % 2 == 0 }.all { it.toInt() <= 20 } && p.answer <= 20 })
        // Уровень 4 — деление только двузначного числа на однозначное.
        MathPools.pool(4).filter { "÷" in it.text }.forEach {
            val (a, _, d) = it.text.split(" ")
            assertTrue(it.text, a.toInt() in 10..99 && d.toInt() in 2..9)
        }
    }

    @Test
    fun rounds_10000SessionsPerLevel_noRepeatsWithinSession() {
        for (level in 1..5) {
            val pool = MathPools.pool(level).toSet()
            repeat(10_000) { seed ->
                val rng = Random(seed)
                val first = game.generate(level, rng, emptyList())
                assertEquals(minOf(60, pool.size / 2), first.problems.size)
                assertEquals(60 * second, first.durationNanos)
                assertFalse(first.isDemo)
                assertTrue(pool.containsAll(first.problems))
                val firstTexts = first.problems.map { it.text }.toSet()
                assertEquals(first.problems.size, firstTexts.size)

                // Второй раунд: история — все примеры первого (с запасом: обычно отвечают меньше).
                val second = game.generate(level, rng, first.problems.map { answered(it) })
                assertTrue("уровень $level, seed $seed: повтор во втором раунде", second.problems.none { it.text in firstTexts })
            }
        }
    }

    @Test
    fun sameSeedSameProblems() {
        assertEquals(game.generate(2, Random(5), emptyList()), game.generate(2, Random(5), emptyList()))
    }

    private fun start(level: Int = 1, seed: Int = 1) = game.initialState(game.generate(level, Random(seed), emptyList()), level)

    private fun MathState.type(text: String, now: Long): MathState {
        var s = this
        text.forEach { s = game.reduce(s, MathInput.Digit(it.digitToInt()), now) }
        return s
    }

    @Test
    fun typingBackspaceAndSubmit() {
        val s0 = start()
        assertEquals(60, s0.secondsLeft)
        val problem = s0.current!!
        // Пустой ответ не отправляется.
        assertSame(s0, game.reduce(s0, MathInput.Submit, second))
        // Ведущий ноль заменяется, больше четырёх цифр не набрать, неверная цифра игнорируется.
        assertEquals("7", s0.type("07", 0).input)
        assertEquals("1234", s0.type("12345", 0).input)
        assertSame(s0, game.reduce(s0, MathInput.Digit(10), 0))
        assertEquals("12", game.reduce(s0.type("123", 0), MathInput.Backspace, 0).input)
        assertEquals("", game.reduce(s0, MathInput.Backspace, 0).input)

        val s1 = game.reduce(s0.type(problem.answer.toString(), 0), MathInput.Submit, 2_500 * ms)
        assertEquals(1, s1.index)
        assertEquals("", s1.input)
        val right = s1.trials.single()
        assertTrue(right.correct)
        assertEquals(2_500L, right.rtMs)
        assertNull(right.errorType)
        assertEquals(problem.text, Json.parseObject(right.paramsJson)["q"])

        // Время ответа на следующий пример считается от предыдущего ответа.
        val wrongAnswer = (s1.current!!.answer + 1).toString()
        val s2 = game.reduce(s1.type(wrongAnswer, 0), MathInput.Submit, 4_000 * ms)
        val wrong = s2.trials.last()
        assertFalse(wrong.correct)
        assertEquals(1_500L, wrong.rtMs)
        assertEquals("wrong", wrong.errorType)
        assertEquals(wrongAnswer.toLong(), Json.parseObject(wrong.answerJson)["given"])
    }

    @Test
    fun timerCountsDownAndEndsTheRoundWithoutRecordingTheUnanswered() {
        val s0 = start().type("1", 0)
        assertSame(s0, game.tick(s0, 500 * ms))
        assertEquals(60, game.tick(s0, 0).secondsLeft)
        assertEquals(59, game.tick(s0, 1_000 * ms).secondsLeft)
        assertEquals(1, game.tick(s0, 59_100 * ms).secondsLeft)
        val over = game.tick(s0, 60 * second)
        assertTrue(over.timedOut)
        assertEquals(0, over.secondsLeft)
        assertTrue(game.isFinished(over))
        assertTrue(game.trials(over).isEmpty())
        assertSame(over, game.reduce(over, MathInput.Submit, 61 * second))

        // Ответ пришёл после конца времени, а кадр ещё не успел: не засчитан, раунд окончен.
        val late = game.reduce(s0.type(s0.current!!.answer.toString(), 0), MathInput.Submit, 60 * second)
        assertTrue(late.timedOut)
        assertTrue(game.trials(late).isEmpty())
    }

    @Test
    fun scoreIsAccuracyTimesCorrectPerMinute() {
        val p = MathProblem("2 + 2", 4)
        // Уровень 1: цель 20 верных в минуту, сессия — 2 минуты.
        val perfect = game.score(List(40) { answered(p) }, 1)
        assertEquals(1.0, perfect.score, 1e-9)
        assertEquals(20.0, perfect.metrics.getValue("correctPerMinute"), 1e-9)

        val half = game.score(List(20) { answered(p) } + List(5) { answered(p, 5) }, 1)
        assertEquals(10.0, half.metrics.getValue("correctPerMinute"), 1e-9)
        assertEquals(0.8, half.metrics.getValue("accuracy"), 1e-9)
        assertEquals(0.4, half.score, 1e-9)
        assertEquals(1_000.0, half.metrics.getValue("medianRtMs"), 1e-9)

        // Быстрее цели — не больше единицы.
        assertEquals(1.0, game.score(List(100) { answered(p) }, 1).score, 1e-9)
        assertEquals(0.0, game.score(emptyList(), 1).score, 1e-9)
    }

    @Test
    fun fullSessionThroughEngine() {
        val session = GameSession(game, level = 3, seed = 11)
        var now = 0L
        repeat(2) {
            session.startRound(now)
            val roundStart = now
            // Отвечаем раз в 2 секунды, пока таймер не кончится.
            while (true) {
                now += 2 * second
                val s = session.state!!
                var completed: CompletedRound? = null
                s.current!!.answer.toString().forEach {
                    completed = completed ?: session.input(MathInput.Digit(it.digitToInt()), now)
                }
                completed = completed ?: session.input(MathInput.Submit, now) ?: session.tick(now)
                if (completed != null) break
                if (now - roundStart > 120 * second) error("раунд не кончился")
            }
        }
        assertEquals(GameSession.Phase.FINISHED, session.phase)
        val history = session.history()
        // Ответы на 2-й … 58-й секунде; нажатие на 60-й уже не засчитывается.
        assertEquals(29 + 29, history.size)
        assertEquals(history.size, history.map { Json.parseObject(it.paramsJson)["q"] }.toSet().size)
        val score = session.score()
        assertEquals(29.0, score.metrics.getValue("correctPerMinute"), 1e-9)
        assertEquals(1.0, score.score, 1e-9)
    }

    @Test
    fun demoIsShortUntimedAndEasy() {
        val demo = game.generateDemo(Random(1))
        assertTrue(demo.isDemo)
        assertEquals(2, demo.problems.size)
        assertTrue(MathPools.pool(1).containsAll(demo.problems))
        val s = game.initialState(demo, 1)
        assertFalse(game.isFinished(game.tick(s, 600 * second)))
    }
}
