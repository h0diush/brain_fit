package app.brainfit.games.corsi

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
 * Корси (ТЗ, раздел 4.4): клетки загораются по одной, нужно повторить последовательность.
 * Раунд — одна последовательность, она же одна проба. Длина подстраивается внутри сессии:
 * два успеха подряд — на 1 длиннее, две неудачи подряд — на 1 короче.
 */
data class CorsiLevel(
    override val level: Int,
    val gridSize: Int,
    val highlightMs: Long,
    /** На старших уровнях последовательность повторяют в обратном порядке. */
    val reverse: Boolean,
    /** Длина первой последовательности сессии. */
    val startLength: Int,
    /** Цель по длине: score = достигнутая длина / цель. Стартовое значение, подгоняется после тестов на себе. */
    val targetSpan: Int,
) : LevelConfig

/** [cells] — в порядке показа. Клетки нумеруются по строкам с нуля. */
data class CorsiParams(
    val gridSize: Int,
    val cells: List<Int>,
    val reverse: Boolean,
    val highlightMs: Long,
) {
    /** Порядок, в котором игрок должен нажимать. */
    val expected: List<Int> get() = if (reverse) cells.reversed() else cells
}

enum class CorsiPhase { SHOWING, INPUT }

data class CorsiTap(val cell: Int)

data class CorsiState(
    val params: CorsiParams,
    val phase: CorsiPhase = CorsiPhase.SHOWING,
    /** Какая клетка горит сейчас; null — пауза между клетками. */
    val lit: Int? = null,
    val taps: List<Int> = emptyList(),
    val inputStartNanos: Long = 0,
    val lastTapNanos: Long = 0,
) {
    val length: Int get() = params.cells.size
}

class CorsiGame : Game<CorsiParams, CorsiState, CorsiTap> {

    override val id = GameId.CORSI

    override val scoringVersion = 1

    override val levels: List<CorsiLevel> = listOf(
        CorsiLevel(1, gridSize = 3, highlightMs = 700, reverse = false, startLength = 3, targetSpan = 5),
        CorsiLevel(2, gridSize = 4, highlightMs = 600, reverse = false, startLength = 4, targetSpan = 6),
        CorsiLevel(3, gridSize = 5, highlightMs = 500, reverse = false, startLength = 4, targetSpan = 6),
        CorsiLevel(4, gridSize = 4, highlightMs = 550, reverse = true, startLength = 3, targetSpan = 5),
        CorsiLevel(5, gridSize = 5, highlightMs = 450, reverse = true, startLength = 4, targetSpan = 5),
        CorsiLevel(6, gridSize = 5, highlightMs = 400, reverse = true, startLength = 4, targetSpan = 6),
    )

    override fun roundsPerSession(level: Int) = ROUNDS

    /**
     * Длина очередной последовательности: начинаем со [CorsiLevel.startLength] и проигрываем историю сессии.
     * Каждая проба в истории — одна последовательность.
     */
    fun sequenceLength(level: Int, history: List<TrialResult>): Int {
        var length = levels[level - 1].startLength
        var successes = 0
        var failures = 0
        for (trial in history) {
            if (trial.correct) {
                failures = 0
                if (++successes == 2) {
                    length = min(MAX_LENGTH, length + 1)
                    successes = 0
                }
            } else {
                successes = 0
                if (++failures == 2) {
                    length = max(MIN_LENGTH, length - 1)
                    failures = 0
                }
            }
        }
        return length
    }

    override fun generate(level: Int, rng: Random, history: List<TrialResult>): CorsiParams {
        val cfg = levels[level - 1]
        val length = sequenceLength(level, history)
        val total = cfg.gridSize * cfg.gridSize
        val cells = ArrayList<Int>(length)
        var previous = -1
        repeat(length) {
            // Одна клетка не повторяется подряд.
            var cell: Int
            do {
                cell = rng.nextInt(total)
            } while (cell == previous)
            cells += cell
            previous = cell
        }
        return CorsiParams(cfg.gridSize, cells, cfg.reverse, cfg.highlightMs)
    }

    override fun initialState(params: CorsiParams, level: Int) = CorsiState(params)

    /** Когда закончится показ последовательности (мс от старта раунда). */
    fun showEndMs(params: CorsiParams): Long =
        LEAD_MS + (params.cells.size - 1) * (params.highlightMs + GAP_MS) + params.highlightMs

    override fun tick(state: CorsiState, nowNanos: Long): CorsiState {
        if (state.phase != CorsiPhase.SHOWING) return state
        val params = state.params
        val nowMs = nowNanos / FrameClock.NANOS_PER_MS
        val endMs = showEndMs(params)
        if (nowMs >= endMs) {
            // Ввод считается с момента окончания показа, а не с кадра, на котором мы это заметили.
            val inputStart = endMs * FrameClock.NANOS_PER_MS
            return state.copy(phase = CorsiPhase.INPUT, lit = null, inputStartNanos = inputStart, lastTapNanos = inputStart)
        }
        val relative = nowMs - LEAD_MS
        val step = params.highlightMs + GAP_MS
        val lit = if (relative < 0 || relative % step >= params.highlightMs) null else params.cells[(relative / step).toInt()]
        return if (lit == state.lit) state else state.copy(lit = lit)
    }

    override fun reduce(state: CorsiState, input: CorsiTap, nowNanos: Long): CorsiState {
        if (state.phase != CorsiPhase.INPUT || isFinished(state)) return state
        val total = state.params.gridSize * state.params.gridSize
        if (input.cell !in 0 until total) return state
        return state.copy(taps = state.taps + input.cell, lastTapNanos = nowNanos)
    }

    override fun isFinished(state: CorsiState) = state.phase == CorsiPhase.INPUT && state.taps.size >= state.length

    override fun trials(state: CorsiState): List<TrialResult> {
        if (!isFinished(state)) return emptyList()
        val params = state.params
        val orderErrors = params.expected.indices.count { params.expected[it] != state.taps[it] }
        val correct = orderErrors == 0
        return listOf(
            TrialResult(
                paramsJson = Json.obj(
                    "len" to state.length,
                    "cells" to params.cells,
                    "grid" to params.gridSize,
                    "reverse" to params.reverse,
                    "highlightMs" to params.highlightMs,
                ),
                answerJson = Json.obj("taps" to state.taps, "orderErrors" to orderErrors),
                correct = correct,
                rtMs = ((state.lastTapNanos - state.inputStartNanos) / FrameClock.NANOS_PER_MS).coerceAtLeast(0),
                errorType = if (correct) null else "order",
            ),
        )
    }

    /**
     * Score — от достигнутой длины, а не от доли верных: внутри сессии длина сама подстраивается
     * под игрока, и доля верных всегда около половины. Span — самая длинная верно повторённая последовательность.
     */
    override fun score(trials: List<TrialResult>, level: Int): SessionScore {
        val target = levels[level - 1].targetSpan
        val span = trials.filter { it.correct }.maxOfOrNull { lengthOf(it) } ?: 0
        val correct = trials.count { it.correct }
        val orderErrors = trials.sumOf { (Json.int(Json.parseObject(it.answerJson), "orderErrors") ?: 0) }
        return SessionScore(
            score = min(1.0, span.toDouble() / target),
            metrics = mapOf(
                "span" to span.toDouble(),
                "correct" to correct.toDouble(),
                "sequences" to trials.size.toDouble(),
                "correctShare" to if (trials.isEmpty()) 0.0 else correct.toDouble() / trials.size,
                "orderErrors" to orderErrors.toDouble(),
            ),
        )
    }

    override fun roundSummary(trials: List<TrialResult>): String? {
        val trial = trials.singleOrNull() ?: return null
        val len = lengthOf(trial)
        return if (trial.correct) {
            "верно, длина $len"
        } else {
            val errors = Json.int(Json.parseObject(trial.answerJson), "orderErrors") ?: 0
            "ошибка в порядке: $errors из $len"
        }
    }

    override fun resultLines(score: SessionScore): List<String> = listOf(
        "Длина последовательности: ${score.metrics.getValue("span").toInt()}",
        "Верных последовательностей: ${score.metrics.getValue("correct").toInt()} из ${score.metrics.getValue("sequences").toInt()}",
        "Ошибок в порядке: ${score.metrics.getValue("orderErrors").toInt()}",
        "Доля верных: ${Fmt.percent(score.metrics.getValue("correctShare"))}",
    )

    private fun lengthOf(trial: TrialResult): Int =
        Json.int(Json.parseObject(trial.paramsJson), "len") ?: error("В пробе Корси нет длины: ${trial.paramsJson}")

    companion object {
        /** Сессия — 8 последовательностей. */
        const val ROUNDS = 8
        const val MIN_LENGTH = 3
        const val MAX_LENGTH = 9

        /** Пауза перед первой клеткой и между клетками, мс. */
        const val LEAD_MS = 700L
        const val GAP_MS = 300L
    }
}
