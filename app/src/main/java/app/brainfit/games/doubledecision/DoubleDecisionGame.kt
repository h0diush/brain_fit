package app.brainfit.games.doubledecision

import app.brainfit.core.time.FrameClock
import app.brainfit.core.util.Json
import app.brainfit.domain.model.GameId
import app.brainfit.games.engine.Fmt
import app.brainfit.games.engine.Game
import app.brainfit.games.engine.LevelConfig
import app.brainfit.games.engine.SessionScore
import app.brainfit.games.engine.Stats
import app.brainfit.games.engine.TrialResult
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * «Двойное решение» (ТЗ, раздел 4.6): на короткое время показываются фигура в центре и цель на периферии,
 * потом маска. Ответ — какая была фигура (2 кнопки) и где была цель (8 позиций по кругу).
 * Экспозиция подбирается лестницей 2-down/1-up в кадрах, шаг ×0,8, через все раунды сессии.
 */
data class DdLevel(
    override val level: Int,
    val distractors: Int,
    /** Во сколько раз длинная сторона центральной фигуры больше короткой: чем ближе к 1, тем фигуры похожее. */
    val aspect: Double,
    /** Целевой порог экспозиции, мс: score = min(1, цель / порог). Стартовое значение. */
    val targetMs: Double,
) : LevelConfig

/** Центральная фигура: вытянутая по горизонтали или по вертикали. Номер хранится в пробах. */
enum class DdShape { WIDE, TALL }

data class DdTrialSpec(
    val center: DdShape,
    /** Позиция цели 0..7 на среднем кольце, по часовой от «на 12 часов». */
    val target: Int,
    /** Слоты отвлекающих фигур (см. [DdLayout]), без слота цели. */
    val distractors: List<Int>,
    val maskSeed: Int,
)

data class DdParams(
    val trials: List<DdTrialSpec>,
    val aspect: Double,
    /** Экспозиция и счётчик верных подряд, с которых лестница продолжается в этом раунде. */
    val startExposureMs: Double,
    val startStreak: Int,
    val isDemo: Boolean = false,
)

sealed interface DdInput {
    data class Center(val shape: DdShape) : DdInput
    data class Periphery(val position: Int) : DdInput
}

enum class DdPhase { FIXATION, STIMULUS, MASK, CENTER, PERIPHERY, FEEDBACK }

data class DdFeedback(val centerOk: Boolean, val peripheryOk: Boolean, val target: Int)

data class DdState(
    val params: DdParams,
    val index: Int = 0,
    val phase: DdPhase = DdPhase.FIXATION,
    val phaseStartNanos: Long = 0,
    /** Текущая экспозиция по лестнице, мс, и сколько верных подряд накоплено. */
    val exposureMs: Double,
    val streak: Int,
    /** Длительности последних кадров — по ним узнаём частоту экрана. */
    val frameDeltas: List<Long> = emptyList(),
    val lastFrameNanos: Long? = null,
    /** Кадров в показе текущей пробы и сколько уже показано. */
    val exposureFrames: Int = 0,
    val framesShown: Int = 0,
    val frameMs: Double = DEFAULT_FRAME_MS,
    val actualMs: Double = 0.0,
    val centerAnswer: DdShape? = null,
    val centerRtMs: Long = 0,
    val trials: List<TrialResult> = emptyList(),
    val feedback: DdFeedback? = null,
) {
    val spec: DdTrialSpec? get() = params.trials.getOrNull(index)

    companion object {
        const val DEFAULT_FRAME_MS = 1000.0 / 60
    }
}

/**
 * Расположение на поле в долях половины стороны: центр поля — (0, 0), y вниз.
 * Слоты 0..7 — среднее кольцо (на нём и цель), 8..15 — внутреннее, 16..31 — внешнее.
 */
object DdLayout {
    const val MIDDLE = 0.62
    const val INNER = 0.38
    const val OUTER = 0.88
    const val TARGET_POSITIONS = 8
    const val SLOTS = 32

    fun slot(index: Int): Pair<Double, Double> = when (index) {
        in 0..7 -> polar(MIDDLE, index * 45.0)
        in 8..15 -> polar(INNER, (index - 8) * 45.0 + 22.5)
        in 16..31 -> polar(OUTER, (index - 16) * 22.5 + 11.25)
        else -> throw IllegalArgumentException("Нет слота $index")
    }

    /** Позиция цели — тот же слот среднего кольца. */
    fun target(position: Int) = slot(position)

    private fun polar(r: Double, degreesFromTop: Double): Pair<Double, Double> {
        val a = degreesFromTop * PI / 180
        return r * sin(a) to -r * cos(a)
    }
}

/** Лестница 2-down/1-up в кадрах. Общая для игры и для восстановления её состояния по истории. */
object DdStaircase {
    const val START_MS = 500.0
    const val MAX_MS = 1_000.0
    const val FACTOR = 0.8

    fun framesFor(ms: Double, frameMs: Double): Int = max(1, (ms / frameMs).roundToInt())

    /** Новые кадры и счётчик после пробы с [frames] кадрами. Шаг — хотя бы на один кадр. */
    fun next(frames: Int, streak: Int, correct: Boolean, frameMs: Double): Pair<Int, Int> {
        val maxFrames = framesFor(MAX_MS, frameMs)
        return if (correct) {
            if (streak + 1 >= 2) max(1, min(frames - 1, (frames * FACTOR).roundToInt())) to 0 else frames to streak + 1
        } else {
            min(maxFrames, max(frames + 1, (frames / FACTOR).roundToInt())) to 0
        }
    }

    /** Направление шага после пробы: −1 вниз, +1 вверх, 0 — без шага. */
    fun direction(frames: Int, next: Int) = next.compareTo(frames)
}

class DoubleDecisionGame : Game<DdParams, DdState, DdInput> {

    override val id = GameId.DOUBLE_DECISION

    override val scoringVersion = 1

    override val levels: List<DdLevel> = listOf(
        DdLevel(1, distractors = 0, aspect = 1.6, targetMs = 100.0),
        DdLevel(2, distractors = 0, aspect = 1.45, targetMs = 100.0),
        DdLevel(3, distractors = 12, aspect = 1.45, targetMs = 150.0),
        DdLevel(4, distractors = 12, aspect = 1.3, targetMs = 150.0),
        DdLevel(5, distractors = 24, aspect = 1.3, targetMs = 200.0),
        DdLevel(6, distractors = 24, aspect = 1.2, targetMs = 200.0),
    )

    override fun roundsPerSession(level: Int) = ROUNDS

    override fun generate(level: Int, rng: Random, history: List<TrialResult>): DdParams {
        val cfg = levels[level - 1]
        val (exposure, streak) = replay(history)
        return DdParams(specs(TRIALS, cfg.distractors, rng), cfg.aspect, exposure, streak)
    }

    override fun generateDemo(rng: Random) =
        DdParams(specs(DEMO_TRIALS, 0, rng), levels.first().aspect, DdStaircase.START_MS, 0, isDemo = true)

    private fun specs(count: Int, distractors: Int, rng: Random): List<DdTrialSpec> {
        // Фигур поровну, чтобы угадывание давало ровно 50 %.
        val shapes = List(count) { if (it % 2 == 0) DdShape.WIDE else DdShape.TALL }.shuffled(rng)
        return shapes.map { shape ->
            val target = rng.nextInt(DdLayout.TARGET_POSITIONS)
            val slots = (0 until DdLayout.SLOTS).filter { it != target }.shuffled(rng).take(distractors).sorted()
            DdTrialSpec(shape, target, slots, rng.nextInt())
        }
    }

    /** Экспозиция и счётчик после всех проб истории: лестница продолжается со следующего раунда. */
    fun replay(history: List<TrialResult>): Pair<Double, Int> {
        var exposure = DdStaircase.START_MS
        var streak = 0
        for (t in history) {
            val p = Json.parseObject(t.paramsJson)
            val frames = Json.int(p, "frames") ?: error("В пробе нет кадров: ${t.paramsJson}")
            val frameMs = (p["frameMs"] as Number).toDouble()
            val (next, s) = DdStaircase.next(frames, streak, t.correct, frameMs)
            exposure = next * frameMs
            streak = s
        }
        return exposure to streak
    }

    override fun initialState(params: DdParams, level: Int) =
        DdState(params, exposureMs = params.startExposureMs, streak = params.startStreak)

    override fun tick(state: DdState, nowNanos: Long): DdState {
        if (isFinished(state)) return state
        val s = trackFrame(state, nowNanos)
        val elapsedMs = (nowNanos - s.phaseStartNanos) / FrameClock.NANOS_PER_MS
        return when (s.phase) {
            DdPhase.FIXATION -> if (elapsedMs >= FIXATION_MS) {
                // Показ начинается на этом кадре и длится ровно exposureFrames кадров.
                s.copy(
                    phase = DdPhase.STIMULUS,
                    phaseStartNanos = nowNanos,
                    exposureFrames = DdStaircase.framesFor(s.exposureMs, s.frameMs),
                    framesShown = 0,
                )
            } else {
                s
            }

            DdPhase.STIMULUS -> {
                // Конец показа — по времени, а не по счёту кадров: если экран пропустит кадр, показ не растянется.
                // Без пропусков это ровно exposureFrames кадров: граница стоит посередине последнего кадра.
                val shown = s.framesShown + 1
                val elapsedNanos = nowNanos - s.phaseStartNanos
                val endNanos = (s.exposureFrames - 0.5) * s.frameMs * FrameClock.NANOS_PER_MS
                if (elapsedNanos >= endNanos) {
                    val actual = elapsedNanos / FrameClock.NANOS_PER_MS.toDouble()
                    s.copy(phase = DdPhase.MASK, phaseStartNanos = nowNanos, framesShown = shown, actualMs = actual)
                } else {
                    s.copy(framesShown = shown)
                }
            }

            DdPhase.MASK -> if (elapsedMs >= MASK_MS) s.copy(phase = DdPhase.CENTER, phaseStartNanos = nowNanos) else s

            DdPhase.FEEDBACK -> if (elapsedMs >= FEEDBACK_MS) {
                s.copy(phase = DdPhase.FIXATION, phaseStartNanos = nowNanos, feedback = null)
            } else {
                s
            }

            DdPhase.CENTER, DdPhase.PERIPHERY -> s
        }
    }

    /** Частота экрана — по медиане длительности последних кадров (паузы и пропуски кадров её не сбивают). */
    private fun trackFrame(state: DdState, nowNanos: Long): DdState {
        val last = state.lastFrameNanos ?: return state.copy(lastFrameNanos = nowNanos)
        val delta = nowNanos - last
        if (delta <= 0) return state
        if (delta > MAX_FRAME_NANOS) return state.copy(lastFrameNanos = nowNanos)
        val deltas = (state.frameDeltas + delta).takeLast(FRAME_WINDOW)
        // Пока идёт показ, частоту не трогаем: число кадров уже выбрано.
        val frameMs = if (state.phase == DdPhase.STIMULUS || deltas.size < MIN_FRAMES) {
            state.frameMs
        } else {
            Stats.median(deltas)!! / FrameClock.NANOS_PER_MS
        }
        return state.copy(lastFrameNanos = nowNanos, frameDeltas = deltas, frameMs = frameMs)
    }

    override fun reduce(state: DdState, input: DdInput, nowNanos: Long): DdState {
        if (isFinished(state)) return state
        return when (input) {
            is DdInput.Center -> if (state.phase != DdPhase.CENTER) {
                state
            } else {
                state.copy(
                    phase = DdPhase.PERIPHERY,
                    phaseStartNanos = nowNanos,
                    centerAnswer = input.shape,
                    centerRtMs = ((nowNanos - state.phaseStartNanos) / FrameClock.NANOS_PER_MS).coerceAtLeast(0),
                )
            }

            is DdInput.Periphery ->
                if (state.phase != DdPhase.PERIPHERY || input.position !in 0 until DdLayout.TARGET_POSITIONS) {
                    state
                } else {
                    answer(state, input.position, nowNanos)
                }
        }
    }

    private fun answer(state: DdState, position: Int, nowNanos: Long): DdState {
        val spec = state.spec ?: return state
        val center = checkNotNull(state.centerAnswer)
        val centerOk = center == spec.center
        val peripheryOk = position == spec.target
        val correct = centerOk && peripheryOk
        val frames = state.exposureFrames
        val trial = TrialResult(
            paramsJson = Json.obj(
                "i" to state.index,
                "center" to spec.center,
                "target" to spec.target,
                "distractors" to spec.distractors.size,
                "aspect" to state.params.aspect,
                "frames" to frames,
                "frameMs" to round2(state.frameMs),
                "exposureMs" to round2(frames * state.frameMs),
                // Сколько показ шёл на самом деле, по времени кадров.
                "actualMs" to round2(state.actualMs),
            ),
            answerJson = Json.obj("center" to center, "position" to position),
            correct = correct,
            rtMs = state.centerRtMs,
            errorType = when {
                correct -> null
                !centerOk && !peripheryOk -> "both"
                !centerOk -> "center"
                else -> "periphery"
            },
        )
        val (next, streak) = DdStaircase.next(frames, state.streak, correct, round2(state.frameMs))
        return state.copy(
            index = state.index + 1,
            phase = DdPhase.FEEDBACK,
            phaseStartNanos = nowNanos,
            exposureMs = next * round2(state.frameMs),
            streak = streak,
            centerAnswer = null,
            trials = state.trials + trial,
            feedback = DdFeedback(centerOk, peripheryOk, spec.target),
        )
    }

    /** Частота кадра пишется в пробу округлённой — и лестница считается от того же числа, что и при восстановлении. */
    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0

    override fun isFinished(state: DdState) = state.index >= state.params.trials.size

    override fun trials(state: DdState) = state.trials

    /**
     * Порог — среднее геометрическое экспозиций в последних 6 разворотах лестницы;
     * если разворотов меньше, то последних 10 проб. Score = min(1, цель уровня / порог).
     */
    fun threshold(trials: List<TrialResult>): Pair<Double, Int>? {
        if (trials.isEmpty()) return null
        val exposures = ArrayList<Double>()
        val reversals = ArrayList<Double>()
        var streak = 0
        var lastDirection = 0
        for (t in trials) {
            val p = Json.parseObject(t.paramsJson)
            val frames = Json.int(p, "frames")!!
            val frameMs = (p["frameMs"] as Number).toDouble()
            val exposure = frames * frameMs
            exposures += exposure
            val (next, s) = DdStaircase.next(frames, streak, t.correct, frameMs)
            streak = s
            val direction = DdStaircase.direction(frames, next)
            if (direction != 0) {
                if (lastDirection != 0 && direction != lastDirection) reversals += exposure
                lastDirection = direction
            }
        }
        val basis = if (reversals.size >= REVERSALS) reversals.takeLast(REVERSALS) else exposures.takeLast(LAST_TRIALS)
        return Stats.geometricMean(basis)!! to reversals.size
    }

    override fun score(trials: List<TrialResult>, level: Int): SessionScore {
        val cfg = levels[level - 1]
        val (threshold, reversals) = threshold(trials) ?: return SessionScore(0.0, mapOf("trials" to 0.0))
        val parsed = trials.map { Json.parseObject(it.answerJson) to Json.parseObject(it.paramsJson) }
        val centerOk = parsed.count { (a, p) -> a["center"] == p["center"] }
        val peripheryOk = parsed.count { (a, p) -> a["position"] == p["target"] }
        return SessionScore(
            score = min(1.0, cfg.targetMs / threshold),
            metrics = mapOf(
                "thresholdMs" to threshold,
                "centerAccuracy" to centerOk.toDouble() / trials.size,
                "peripheryAccuracy" to peripheryOk.toDouble() / trials.size,
                "accuracy" to trials.count { it.correct }.toDouble() / trials.size,
                "reversals" to reversals.toDouble(),
                "trials" to trials.size.toDouble(),
            ),
        )
    }

    override fun roundSummary(trials: List<TrialResult>): String {
        val last = trials.lastOrNull()?.let { Json.parseObject(it.paramsJson)["exposureMs"] as? Number }
        val exposure = last?.let { ", экспозиция ${it.toDouble().roundToInt()} мс" } ?: ""
        return "верно ${trials.count { it.correct }} из ${trials.size}$exposure"
    }

    override fun resultLines(score: SessionScore): List<String> {
        val m = score.metrics
        val threshold = m["thresholdMs"] ?: return listOf("Проб нет")
        return listOf(
            "Порог экспозиции: ${threshold.roundToInt()} мс",
            "Центр верно: ${Fmt.percent(m.getValue("centerAccuracy"))}",
            "Цель верно: ${Fmt.percent(m.getValue("peripheryAccuracy"))}",
        )
    }

    companion object {
        /** Сессия — 3 раунда по 10 проб. */
        const val ROUNDS = 3
        const val TRIALS = 10
        const val FIXATION_MS = 700L
        const val MASK_MS = 300L
        const val FEEDBACK_MS = 600L
        const val REVERSALS = 6
        const val LAST_TRIALS = 10
        private const val DEMO_TRIALS = 3
        private const val FRAME_WINDOW = 31
        private const val MIN_FRAMES = 5

        /** Кадр длиннее 100 мс — это не частота экрана, а пропуск или пауза. */
        private const val MAX_FRAME_NANOS = 100 * FrameClock.NANOS_PER_MS
    }
}
