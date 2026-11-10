package app.brainfit.games.schulte

import app.brainfit.core.time.FrameClock
import app.brainfit.core.util.Json
import app.brainfit.domain.model.GameId
import app.brainfit.games.engine.Fmt
import app.brainfit.games.engine.Game
import app.brainfit.games.engine.LevelConfig
import app.brainfit.games.engine.SessionScore
import app.brainfit.games.engine.TrialResult
import kotlin.math.min
import kotlin.random.Random

/**
 * Таблица Шульте: тапать числа по порядку в перемешанной сетке (ТЗ, раздел 4.1).
 * Каждый тап — одна проба: верный (время с прошлого верного тапа) или ошибочный (число не засчитано).
 */
data class SchulteLevel(
    override val level: Int,
    val size: Int,
    val reverse: Boolean,
    /** Целевое время на ячейку, мс. Стартовое значение: подгоняется после тестов на себе (ТЗ, раздел 6). */
    val targetMsPerCell: Long,
) : LevelConfig {
    val cells: Int get() = size * size
}

/** [layout] — число в каждой клетке сетки по строкам; не меняется до конца раунда. */
data class SchulteParams(val size: Int, val reverse: Boolean, val layout: List<Int>)

data class SchulteTap(val cell: Int)

data class SchulteState(
    val params: SchulteParams,
    /** Сколько чисел уже найдено. */
    val found: Int = 0,
    val trials: List<TrialResult> = emptyList(),
    val lastCorrectNanos: Long = 0,
    val wrongTaps: Int = 0,
    /** Клетка последнего ошибочного тапа — для вспышки на экране. */
    val wrongCell: Int? = null,
) {
    val cells: Int get() = params.layout.size

    /** Число, которое надо найти сейчас. После последнего найденного выходит за пределы сетки. */
    val target: Int get() = if (params.reverse) cells - found else found + 1
}

class SchulteGame : Game<SchulteParams, SchulteState, SchulteTap> {

    override val id = GameId.SCHULTE

    override val scoringVersion = 1

    override val levels: List<SchulteLevel> = listOf(
        SchulteLevel(1, size = 3, reverse = false, targetMsPerCell = 1_200),
        SchulteLevel(2, size = 4, reverse = false, targetMsPerCell = 1_300),
        SchulteLevel(3, size = 5, reverse = false, targetMsPerCell = 1_400),
        SchulteLevel(4, size = 5, reverse = true, targetMsPerCell = 1_800),
        SchulteLevel(5, size = 6, reverse = false, targetMsPerCell = 1_500),
        SchulteLevel(6, size = 6, reverse = true, targetMsPerCell = 1_900),
    )

    override fun roundsPerSession(level: Int) = ROUNDS

    override fun generate(level: Int, rng: Random, history: List<TrialResult>): SchulteParams {
        val cfg = levels[level - 1]
        return SchulteParams(cfg.size, cfg.reverse, (1..cfg.cells).shuffled(rng))
    }

    override fun initialState(params: SchulteParams, level: Int) = SchulteState(params)

    override fun reduce(state: SchulteState, input: SchulteTap, nowNanos: Long): SchulteState {
        if (isFinished(state)) return state
        val cell = input.cell
        if (cell !in state.params.layout.indices) return state
        val number = state.params.layout[cell]
        val answer = Json.obj("cell" to cell, "number" to number)
        return if (number == state.target) {
            val rtMs = ((nowNanos - state.lastCorrectNanos) / FrameClock.NANOS_PER_MS).coerceAtLeast(0)
            val trial = TrialResult(Json.obj("target" to number), answer, correct = true, rtMs = rtMs)
            state.copy(found = state.found + 1, trials = state.trials + trial, lastCorrectNanos = nowNanos)
        } else {
            // Ошибочный тап: число не засчитано, времени реакции у него нет.
            val trial = TrialResult(Json.obj("target" to state.target), answer, correct = false, rtMs = null, errorType = "wrong_tap")
            state.copy(trials = state.trials + trial, wrongTaps = state.wrongTaps + 1, wrongCell = cell)
        }
    }

    override fun isFinished(state: SchulteState) = state.found >= state.cells

    override fun trials(state: SchulteState) = state.trials

    /** Точность (верные тапы из всех) × min(1, целевое время на ячейку / фактическое). */
    override fun score(trials: List<TrialResult>, level: Int): SessionScore {
        val cfg = levels[level - 1]
        val correct = trials.filter { it.correct }
        val wrong = trials.size - correct.size
        if (correct.isEmpty()) {
            return SessionScore(0.0, mapOf("msPerCell" to 0.0, "accuracy" to 0.0, "wrongTaps" to wrong.toDouble(), "cells" to 0.0))
        }
        val accuracy = correct.size.toDouble() / trials.size
        val msPerCell = correct.sumOf { it.rtMs ?: 0L }.toDouble() / correct.size
        val speed = if (msPerCell <= 0.0) 1.0 else min(1.0, cfg.targetMsPerCell / msPerCell)
        return SessionScore(
            score = accuracy * speed,
            metrics = mapOf(
                "msPerCell" to msPerCell,
                "accuracy" to accuracy,
                "wrongTaps" to wrong.toDouble(),
                "cells" to correct.size.toDouble(),
            ),
        )
    }

    override fun roundSummary(trials: List<TrialResult>): String {
        val seconds = trials.filter { it.correct }.sumOf { it.rtMs ?: 0L } / 1000.0
        val wrong = trials.count { !it.correct }
        return "время ${Fmt.decimal(seconds)} с, ошибок: $wrong"
    }

    override fun resultLines(score: SessionScore): List<String> = listOf(
        "Время на ячейку: ${Fmt.seconds(score.metrics.getValue("msPerCell"))}",
        "Точность: ${Fmt.percent(score.metrics.getValue("accuracy"))}",
        "Ошибочных нажатий: ${score.metrics.getValue("wrongTaps").toInt()}",
    )

    companion object {
        /** Сессия — 3 таблицы (ТЗ, раздел 4.1). */
        const val ROUNDS = 3
    }
}
