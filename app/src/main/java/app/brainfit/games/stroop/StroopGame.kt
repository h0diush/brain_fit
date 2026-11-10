package app.brainfit.games.stroop

import app.brainfit.core.time.FrameClock
import app.brainfit.core.util.Json
import app.brainfit.domain.model.GameId
import app.brainfit.games.engine.Fmt
import app.brainfit.games.engine.Game
import app.brainfit.games.engine.LevelConfig
import app.brainfit.games.engine.SessionScore
import app.brainfit.games.engine.Stats
import app.brainfit.games.engine.TrialResult
import kotlin.math.roundToInt
import kotlin.random.Random

/** Цвета в фиксированном порядке: он же порядок кнопок на всю сессию. Имена хранятся в пробах — не переименовывать. */
enum class StroopColor(val word: String) {
    RED("КРАСНЫЙ"),
    BLUE("СИНИЙ"),
    YELLOW("ЖЁЛТЫЙ"),
    GREEN("ЗЕЛЁНЫЙ"),
}

/**
 * Струп (ТЗ, раздел 4.2): слово-цвет написано цветом; нажать кнопку цвета шрифта.
 * Проба — один стимул. Ответ после лимита или его отсутствие — промах.
 */
data class StroopLevel(
    override val level: Int,
    val colors: Int,
    /** Доля конфликтных стимулов (слово не совпадает с цветом). */
    val conflictShare: Double,
    val limitMs: Long,
) : LevelConfig

data class StroopStimulus(val word: StroopColor, val ink: StroopColor) {
    val congruent: Boolean get() = word == ink
}

data class StroopParams(
    val colors: List<StroopColor>,
    val stimuli: List<StroopStimulus>,
    val limitMs: Long,
)

data class StroopAnswer(val color: StroopColor)

enum class StroopPhase {
    /** Пустой экран между стимулами. */
    GAP,
    STIMULUS,
}

/** Итог прошлого стимула для короткой подсказки в паузе. */
enum class StroopFeedback { CORRECT, WRONG, TIMEOUT }

data class StroopState(
    val params: StroopParams,
    val index: Int = 0,
    val phase: StroopPhase = StroopPhase.GAP,
    /** Начало текущей фазы; у стимула — кадр, на котором он показан. */
    val phaseStartNanos: Long = 0,
    val trials: List<TrialResult> = emptyList(),
    val feedback: StroopFeedback? = null,
) {
    val current: StroopStimulus? get() = params.stimuli.getOrNull(index)
}

class StroopGame : Game<StroopParams, StroopState, StroopAnswer> {

    override val id = GameId.STROOP

    override val scoringVersion = 1

    override val levels: List<StroopLevel> = listOf(
        StroopLevel(1, colors = 3, conflictShare = 0.3, limitMs = 3_000),
        StroopLevel(2, colors = 3, conflictShare = 0.5, limitMs = 2_500),
        StroopLevel(3, colors = 4, conflictShare = 0.5, limitMs = 2_200),
        StroopLevel(4, colors = 4, conflictShare = 0.6, limitMs = 1_800),
        StroopLevel(5, colors = 4, conflictShare = 0.7, limitMs = 1_500),
        StroopLevel(6, colors = 4, conflictShare = 0.7, limitMs = 1_200),
    )

    override fun roundsPerSession(level: Int) = ROUNDS

    override fun generate(level: Int, rng: Random, history: List<TrialResult>): StroopParams {
        val cfg = levels[level - 1]
        return build(cfg.colors, STIMULI, cfg.conflictShare, cfg.limitMs, rng)
    }

    override fun generateDemo(rng: Random) = build(colors = 3, count = DEMO_STIMULI, conflictShare = 0.5, limitMs = 3_000, rng = rng)

    private fun build(colors: Int, count: Int, conflictShare: Double, limitMs: Long, rng: Random): StroopParams {
        val palette = StroopColor.entries.take(colors)
        // Доля конфликтных — ровно по уровню: число конфликтных фиксировано, перемешивается только порядок.
        val conflicts = (count * conflictShare).roundToInt()
        val isConflict = (List(conflicts) { true } + List(count - conflicts) { false }).shuffled(rng)
        val stimuli = ArrayList<StroopStimulus>(count)
        for (i in 0 until count) {
            // Не больше трёх стимулов подряд одного цвета шрифта.
            val lastThree = stimuli.takeLast(MAX_SAME_INK).map { it.ink }
            val banned = if (lastThree.size == MAX_SAME_INK && lastThree.distinct().size == 1) lastThree.first() else null
            val ink = palette.filter { it != banned }.random(rng)
            val word = if (isConflict[i]) palette.filter { it != ink }.random(rng) else ink
            stimuli += StroopStimulus(word, ink)
        }
        return StroopParams(palette, stimuli, limitMs)
    }

    override fun initialState(params: StroopParams, level: Int) = StroopState(params)

    override fun tick(state: StroopState, nowNanos: Long): StroopState {
        if (isFinished(state)) return state
        val elapsed = nowNanos - state.phaseStartNanos
        return when (state.phase) {
            StroopPhase.GAP ->
                if (elapsed >= GAP_MS * FrameClock.NANOS_PER_MS) {
                    state.copy(phase = StroopPhase.STIMULUS, phaseStartNanos = nowNanos)
                } else {
                    state
                }

            StroopPhase.STIMULUS ->
                if (elapsed >= state.params.limitMs * FrameClock.NANOS_PER_MS) answer(state, null, nowNanos) else state
        }
    }

    override fun reduce(state: StroopState, input: StroopAnswer, nowNanos: Long): StroopState {
        if (isFinished(state) || state.phase != StroopPhase.STIMULUS) return state
        if (input.color !in state.params.colors) return state
        // Нажатие пришло после лимита раньше кадра — это всё равно промах по времени.
        val late = nowNanos - state.phaseStartNanos >= state.params.limitMs * FrameClock.NANOS_PER_MS
        return answer(state, if (late) null else input.color, nowNanos)
    }

    /** [pressed] == null — не успел. */
    private fun answer(state: StroopState, pressed: StroopColor?, nowNanos: Long): StroopState {
        val stimulus = state.current ?: return state
        val correct = pressed == stimulus.ink
        val trial = TrialResult(
            paramsJson = Json.obj(
                "i" to state.index,
                "word" to stimulus.word,
                "ink" to stimulus.ink,
                "congruent" to stimulus.congruent,
                "limitMs" to state.params.limitMs,
            ),
            answerJson = Json.obj("pressed" to pressed),
            correct = correct,
            rtMs = if (pressed == null) null else ((nowNanos - state.phaseStartNanos) / FrameClock.NANOS_PER_MS).coerceAtLeast(0),
            errorType = when {
                pressed == null -> "timeout"
                !correct -> "wrong"
                else -> null
            },
        )
        return state.copy(
            index = state.index + 1,
            phase = StroopPhase.GAP,
            phaseStartNanos = nowNanos,
            trials = state.trials + trial,
            feedback = when {
                pressed == null -> StroopFeedback.TIMEOUT
                correct -> StroopFeedback.CORRECT
                else -> StroopFeedback.WRONG
            },
        )
    }

    override fun isFinished(state: StroopState) = state.index >= state.params.stimuli.size

    override fun trials(state: StroopState) = state.trials

    /** Score — доля верных ответов в пределах лимита (ТЗ, раздел 6). Струп-эффект — в метриках. */
    override fun score(trials: List<TrialResult>, level: Int): SessionScore {
        val correct = trials.count { it.correct }
        val metrics = linkedMapOf(
            "accuracy" to if (trials.isEmpty()) 0.0 else correct.toDouble() / trials.size,
            "correct" to correct.toDouble(),
            "stimuli" to trials.size.toDouble(),
            "timeouts" to trials.count { it.errorType == "timeout" }.toDouble(),
        )
        val byCongruence = trials.filter { it.correct }.groupBy { Json.parseObject(it.paramsJson)["congruent"] == true }
        val congruent = Stats.median(byCongruence[true].orEmpty().mapNotNull { it.rtMs })
        val conflict = Stats.median(byCongruence[false].orEmpty().mapNotNull { it.rtMs })
        // Нет верных ответов в группе — медианы нет, и ключа тоже нет: ноль был бы неправдой.
        congruent?.let { metrics["medianCongruentMs"] = it }
        conflict?.let { metrics["medianConflictMs"] = it }
        if (congruent != null && conflict != null) metrics["stroopEffectMs"] = conflict - congruent
        return SessionScore(metrics.getValue("accuracy"), metrics)
    }

    override fun roundSummary(trials: List<TrialResult>) =
        "верно ${trials.count { it.correct }} из ${trials.size}, не успел: ${trials.count { it.errorType == "timeout" }}"

    override fun resultLines(score: SessionScore): List<String> {
        val m = score.metrics
        return listOfNotNull(
            "Точность: ${Fmt.percent(m.getValue("accuracy"))}",
            m["medianCongruentMs"]?.let { "Медиана, цвет совпадает со словом: ${Fmt.seconds(it)}" },
            m["medianConflictMs"]?.let { "Медиана, цвет не совпадает: ${Fmt.seconds(it)}" },
            m["stroopEffectMs"]?.let { "Струп-эффект: ${if (it >= 0) "+" else "−"}${kotlin.math.abs(it).roundToInt()} мс" },
            "Не успел ответить: ${m.getValue("timeouts").toInt()}",
        )
    }

    companion object {
        /** Сессия — 2 раунда по 20 стимулов (около 45 секунд каждый). */
        const val ROUNDS = 2
        const val STIMULI = 20
        const val GAP_MS = 500L
        const val MAX_SAME_INK = 3
        private const val DEMO_STIMULI = 6
    }
}
