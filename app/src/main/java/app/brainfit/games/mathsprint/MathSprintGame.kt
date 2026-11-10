package app.brainfit.games.mathsprint

import app.brainfit.core.time.FrameClock
import app.brainfit.core.util.Json
import app.brainfit.domain.model.GameId
import app.brainfit.games.engine.Fmt
import app.brainfit.games.engine.Game
import app.brainfit.games.engine.LevelConfig
import app.brainfit.games.engine.SessionScore
import app.brainfit.games.engine.TrialResult
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Математический спринт (ТЗ, раздел 4.5): как можно больше примеров за 60 секунд.
 * Проба — один отвеченный пример. Пример, который не успели ввести к концу времени, не записывается.
 */
data class MathLevel(
    override val level: Int,
    /** Цель — правильных ответов в минуту. Стартовое значение: подгоняется после тестов на себе. */
    val targetPerMinute: Double,
) : LevelConfig

data class MathProblem(val text: String, val answer: Int)

data class MathParams(
    val problems: List<MathProblem>,
    val durationNanos: Long,
    /** Пробная попытка: без таймера на экране. */
    val isDemo: Boolean = false,
)

sealed interface MathInput {
    data class Digit(val value: Int) : MathInput
    data object Backspace : MathInput
    data object Submit : MathInput
}

data class MathState(
    val params: MathParams,
    val index: Int = 0,
    val input: String = "",
    val trials: List<TrialResult> = emptyList(),
    val shownAtNanos: Long = 0,
    val secondsLeft: Int,
    val timedOut: Boolean = false,
) {
    val current: MathProblem? get() = params.problems.getOrNull(index)
}

class MathSprintGame : Game<MathParams, MathState, MathInput> {

    override val id = GameId.MATH

    override val scoringVersion = 1

    override val levels: List<MathLevel> = listOf(
        MathLevel(1, targetPerMinute = 20.0), // сложение и вычитание до 20
        MathLevel(2, targetPerMinute = 14.0), // сложение и вычитание до 100
        MathLevel(3, targetPerMinute = 16.0), // таблица умножения
        MathLevel(4, targetPerMinute = 8.0), // двузначное на однозначное: × и ÷
        MathLevel(5, targetPerMinute = 7.0), // двухшаговые: a + b × c и похожие
    )

    override fun roundsPerSession(level: Int) = ROUNDS

    override fun generate(level: Int, rng: Random, history: List<TrialResult>): MathParams {
        val pool = MathPools.pool(level)
        // Пример не повторяется в пределах сессии: каждому раунду достаётся не больше доли пула.
        val count = min(MAX_PER_ROUND, pool.size / ROUNDS)
        val used = HashSet<String>()
        for (trial in history) {
            (Json.parseObject(trial.paramsJson)["q"] as? String)?.let(used::add)
        }
        val fresh = if (used.isEmpty()) pool else pool.filter { it.text !in used }
        val source = if (fresh.size >= count) fresh else pool
        return MathParams(source.shuffled(rng).take(count), durationNanos = ROUND_SECONDS * NANOS_PER_SECOND)
    }

    override fun generateDemo(rng: Random) = MathParams(
        problems = MathPools.pool(1).shuffled(rng).take(DEMO_PROBLEMS),
        durationNanos = DEMO_SECONDS * NANOS_PER_SECOND,
        isDemo = true,
    )

    override fun initialState(params: MathParams, level: Int) =
        MathState(params, secondsLeft = secondsLeft(params.durationNanos, 0))

    override fun reduce(state: MathState, input: MathInput, nowNanos: Long): MathState {
        if (isFinished(state)) return state
        // Нажатие пришло после конца времени, раньше кадра: не засчитываем, раунд кончается.
        if (nowNanos >= state.params.durationNanos) return tick(state, nowNanos)
        return when (input) {
            is MathInput.Digit -> when {
                input.value !in 0..9 -> state
                state.input.length >= MAX_DIGITS -> state
                state.input == "0" -> state.copy(input = input.value.toString())
                else -> state.copy(input = state.input + input.value)
            }

            MathInput.Backspace -> state.copy(input = state.input.dropLast(1))

            MathInput.Submit -> submit(state, nowNanos)
        }
    }

    private fun submit(state: MathState, nowNanos: Long): MathState {
        val problem = state.current ?: return state
        if (state.input.isEmpty()) return state
        val given = state.input.toInt()
        val correct = given == problem.answer
        val trial = TrialResult(
            paramsJson = Json.obj("q" to problem.text, "a" to problem.answer),
            answerJson = Json.obj("given" to given),
            correct = correct,
            rtMs = ((nowNanos - state.shownAtNanos) / FrameClock.NANOS_PER_MS).coerceAtLeast(0),
            errorType = if (correct) null else "wrong",
        )
        return state.copy(
            index = state.index + 1,
            input = "",
            trials = state.trials + trial,
            shownAtNanos = nowNanos,
        )
    }

    override fun tick(state: MathState, nowNanos: Long): MathState {
        if (state.timedOut) return state
        val limit = state.params.durationNanos
        if (nowNanos >= limit) return state.copy(timedOut = true, secondsLeft = 0)
        val left = secondsLeft(limit, nowNanos)
        return if (left == state.secondsLeft) state else state.copy(secondsLeft = left)
    }

    override fun isFinished(state: MathState) = state.timedOut || state.index >= state.params.problems.size

    override fun trials(state: MathState) = state.trials

    /**
     * Точность × min(1, правильных в минуту / цель уровня). Время сессии — ROUNDS × 60 с:
     * раунд кончается по таймеру. Если раунд кончился раньше (закончились примеры), скорость
     * слегка занижена, но на практике это недостижимо: примеров в раунде на порядок больше, чем успеть.
     */
    override fun score(trials: List<TrialResult>, level: Int): SessionScore {
        val target = levels[level - 1].targetPerMinute
        val answered = trials.size
        val correct = trials.count { it.correct }
        val perMinute = correct / (ROUNDS * ROUND_SECONDS / 60.0)
        val accuracy = if (answered == 0) 0.0 else correct.toDouble() / answered
        val speed = min(1.0, perMinute / target)
        return SessionScore(
            score = accuracy * speed,
            metrics = mapOf(
                "correctPerMinute" to perMinute,
                "accuracy" to accuracy,
                "medianRtMs" to median(trials.mapNotNull { it.rtMs }),
                "answered" to answered.toDouble(),
                "correct" to correct.toDouble(),
            ),
        )
    }

    override fun roundSummary(trials: List<TrialResult>) =
        "решено ${trials.size}, верных: ${trials.count { it.correct }}"

    override fun resultLines(score: SessionScore): List<String> = listOf(
        "Верных в минуту: ${Fmt.decimal(score.metrics.getValue("correctPerMinute"))}",
        "Точность: ${Fmt.percent(score.metrics.getValue("accuracy"))}",
        "Медиана времени ответа: ${Fmt.seconds(score.metrics.getValue("medianRtMs"))}",
    )

    private fun secondsLeft(limitNanos: Long, nowNanos: Long): Int =
        ((max(0L, limitNanos - nowNanos) + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND).toInt()

    private fun median(values: List<Long>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    companion object {
        /** Сессия — два раунда по 60 секунд. */
        const val ROUNDS = 2
        const val ROUND_SECONDS = 60L
        const val MAX_DIGITS = 4
        private const val MAX_PER_ROUND = 60
        private const val DEMO_PROBLEMS = 2
        private const val DEMO_SECONDS = 3_600L
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
