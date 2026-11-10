package app.brainfit.games.nback

import app.brainfit.core.time.FrameClock
import app.brainfit.core.util.Json
import app.brainfit.domain.model.GameId
import app.brainfit.games.engine.Fmt
import app.brainfit.games.engine.Game
import app.brainfit.games.engine.LevelConfig
import app.brainfit.games.engine.ResumePolicy
import app.brainfit.games.engine.SessionScore
import app.brainfit.games.engine.Stats
import app.brainfit.games.engine.TrialResult
import kotlin.random.Random

/**
 * Пространственный N-back (ТЗ, раздел 4.3): в сетке 3×3 загорается клетка; нажать «Совпадает»,
 * если позиция та же, что N шагов назад. Первые N стимулов не оцениваются — их просто запоминают.
 * Проба — один оцениваемый стимул: попадание, пропуск, ложное нажатие или корректный отказ.
 */
data class NBackLevel(
    override val level: Int,
    val n: Int,
    /** Пауза после показа до следующего стимула, мс. */
    val intervalMs: Long,
) : LevelConfig

data class NBackParams(
    val n: Int,
    /** Клетка каждого стимула, 0..8 по строкам. */
    val cells: List<Int>,
    val targets: List<Boolean>,
    /** «Ловушки»: позиция совпадает с N−1 или N+1 шагов назад, но не с N. */
    val lures: List<Boolean>,
    val showMs: Long,
    val intervalMs: Long,
) {
    val count: Int get() = cells.size

    /** Время от начала одного стимула до начала следующего. */
    val stepMs: Long get() = showMs + intervalMs
}

data object NBackPress

data class NBackState(
    val params: NBackParams,
    /** Номер текущего стимула; −1 — до первого. */
    val current: Int = -1,
    val lit: Int? = null,
    /** Время нажатия (мс от начала стимула) по номеру стимула. Засчитывается первое нажатие. */
    val presses: Map<Int, Long> = emptyMap(),
    /** Сколько стимулов уже разобрано на пробы (их окно ответа закрыто). */
    val closed: Int = 0,
    val trials: List<TrialResult> = emptyList(),
)

class NBackGame : Game<NBackParams, NBackState, NBackPress> {

    override val id = GameId.NBACK

    override val scoringVersion = 1

    /** Пауза ломает ритм, а с ним и задание: после паузы раунд начинается заново (решено на этапе 2). */
    override val resumePolicy = ResumePolicy.RESTART_ROUND

    /** N от 1 до 5, внутри каждого N интервал 2500 → 2150 → 1800 мс: 15 уровней. */
    override val levels: List<NBackLevel> = (1..5).flatMap { n ->
        INTERVALS.mapIndexed { i, interval -> NBackLevel((n - 1) * INTERVALS.size + i + 1, n, interval) }
    }

    override fun roundsPerSession(level: Int) = ROUNDS

    override fun generate(level: Int, rng: Random, history: List<TrialResult>): NBackParams {
        val cfg = levels[level - 1]
        return build(cfg.n, cfg.intervalMs, SCORED, TARGETS, if (cfg.n >= 2) LURES else 0, rng)
    }

    /** Пробная попытка: 1-back, 10 стимулов, 3 цели. */
    override fun generateDemo(rng: Random) = build(n = 1, intervalMs = INTERVALS.first(), scored = 9, targetCount = 3, lureCount = 0, rng = rng)

    internal fun build(n: Int, intervalMs: Long, scored: Int, targetCount: Int, lureCount: Int, rng: Random): NBackParams {
        val count = n + scored
        val targets = BooleanArray(count)
        // Цели — среди оцениваемых стимулов, не больше двух подряд.
        while (true) {
            targets.fill(false)
            (n until count).shuffled(rng).take(targetCount).forEach { targets[it] = true }
            if ((0..count - 3).none { targets[it] && targets[it + 1] && targets[it + 2] }) break
        }
        val lures = BooleanArray(count)
        (n until count).filter { !targets[it] }.shuffled(rng).take(lureCount).forEach { lures[it] = true }

        val cells = IntArray(count)
        for (i in 0 until count) {
            if (i < n) {
                cells[i] = rng.nextInt(CELLS)
                continue
            }
            val nBack = cells[i - n]
            if (targets[i]) {
                cells[i] = nBack
                continue
            }
            // Позиции N−1 и N+1 шагов назад: в них ловушки, остальным не-целям туда нельзя.
            val near = buildList {
                if (n >= 2) add(cells[i - n + 1])
                if (n >= 2 && i - n - 1 >= 0) add(cells[i - n - 1])
            }.filter { it != nBack }.distinct()
            if (lures[i] && near.isNotEmpty()) {
                cells[i] = near.random(rng)
            } else {
                lures[i] = false
                cells[i] = (0 until CELLS).filter { it != nBack && it !in near }.random(rng)
            }
        }
        return NBackParams(n, cells.toList(), targets.toList(), lures.toList(), SHOW_MS, intervalMs)
    }

    override fun initialState(params: NBackParams, level: Int) = NBackState(params)

    /** Номер стимула, на который приходится момент [nowMs], или −1 до первого. */
    private fun stimulusAt(params: NBackParams, nowMs: Long): Int =
        if (nowMs < LEAD_MS) -1 else ((nowMs - LEAD_MS) / params.stepMs).toInt()

    private fun onsetMs(params: NBackParams, index: Int) = LEAD_MS + index * params.stepMs

    override fun tick(state: NBackState, nowNanos: Long): NBackState {
        if (isFinished(state)) return state
        val params = state.params
        val nowMs = nowNanos / FrameClock.NANOS_PER_MS
        val index = stimulusAt(params, nowMs)
        // Окно ответа стимула закрывается с началом следующего.
        val closeUpTo = index.coerceIn(0, params.count)
        var trials = state.trials
        for (i in state.closed until closeUpTo) {
            if (i >= params.n) trials = trials + trialFor(params, i, state.presses[i])
        }
        val lit = if (index in 0 until params.count && (nowMs - onsetMs(params, index)) < params.showMs) params.cells[index] else null
        val current = index.coerceAtMost(params.count - 1)
        if (closeUpTo == state.closed && lit == state.lit && current == state.current) return state
        return state.copy(current = current, lit = lit, closed = maxOf(state.closed, closeUpTo), trials = trials)
    }

    override fun reduce(state: NBackState, input: NBackPress, nowNanos: Long): NBackState {
        if (isFinished(state)) return state
        val params = state.params
        val nowMs = nowNanos / FrameClock.NANOS_PER_MS
        val index = stimulusAt(params, nowMs)
        // До первого стимула, после последнего и на стимулах «для запоминания» нажатие ничего не значит.
        if (index < params.n || index >= params.count || index < state.closed || index in state.presses) return state
        return state.copy(presses = state.presses + (index to (nowMs - onsetMs(params, index))))
    }

    private fun trialFor(params: NBackParams, i: Int, pressMs: Long?): TrialResult {
        val target = params.targets[i]
        val pressed = pressMs != null
        return TrialResult(
            paramsJson = Json.obj(
                "i" to i,
                "cell" to params.cells[i],
                "target" to target,
                "lure" to params.lures[i],
                "n" to params.n,
                "intervalMs" to params.intervalMs,
            ),
            answerJson = Json.obj("pressed" to pressed),
            correct = pressed == target,
            rtMs = pressMs,
            errorType = when {
                target && !pressed -> "miss"
                !target && pressed -> "false_alarm"
                else -> null
            },
        )
    }

    override fun isFinished(state: NBackState) = state.closed >= state.params.count

    override fun trials(state: NBackState) = state.trials

    private data class Counts(val hits: Int, val misses: Int, val falseAlarms: Int, val correctRejections: Int) {
        val targets get() = hits + misses
        val nonTargets get() = falseAlarms + correctRejections
    }

    private fun counts(trials: List<TrialResult>): Counts {
        var hits = 0
        var misses = 0
        var fa = 0
        var cr = 0
        for (t in trials) {
            val target = Json.parseObject(t.paramsJson)["target"] == true
            val pressed = t.rtMs != null
            when {
                target && pressed -> hits++
                target -> misses++
                pressed -> fa++
                else -> cr++
            }
        }
        return Counts(hits, misses, fa, cr)
    }

    /**
     * Score — среднее доли попаданий и доли корректных отказов (решено на этапе 4 вместо формулы ТЗ):
     * «никогда не нажимать» и «нажимать всегда» дают 0,5, а не 0,7. Доля верных по ТЗ и d′ — в метриках.
     */
    override fun score(trials: List<TrialResult>, level: Int): SessionScore {
        val c = counts(trials)
        val hitRate = if (c.targets == 0) 0.0 else c.hits.toDouble() / c.targets
        val crRate = if (c.nonTargets == 0) 0.0 else c.correctRejections.toDouble() / c.nonTargets
        val metrics = linkedMapOf(
            "hits" to c.hits.toDouble(),
            "misses" to c.misses.toDouble(),
            "falseAlarms" to c.falseAlarms.toDouble(),
            "correctRejections" to c.correctRejections.toDouble(),
            "accuracy" to if (trials.isEmpty()) 0.0 else (c.hits + c.correctRejections).toDouble() / trials.size,
            "dPrime" to Stats.dPrime(c.hits, c.targets, c.falseAlarms, c.nonTargets),
            "n" to levels[level - 1].n.toDouble(),
        )
        Stats.median(trials.filter { it.correct }.mapNotNull { it.rtMs })?.let { metrics["medianHitRtMs"] = it }
        return SessionScore(if (trials.isEmpty()) 0.0 else (hitRate + crRate) / 2, metrics)
    }

    override fun roundSummary(trials: List<TrialResult>): String {
        val c = counts(trials)
        return "попаданий ${c.hits} из ${c.targets}, лишних нажатий: ${c.falseAlarms}"
    }

    override fun resultLines(score: SessionScore): List<String> {
        val m = score.metrics
        val targets = (m.getValue("hits") + m.getValue("misses")).toInt()
        val nonTargets = (m.getValue("falseAlarms") + m.getValue("correctRejections")).toInt()
        return listOf(
            "N = ${m.getValue("n").toInt()}",
            "Попаданий: ${m.getValue("hits").toInt()} из $targets",
            "Лишних нажатий: ${m.getValue("falseAlarms").toInt()} из $nonTargets",
            "Верных ответов: ${Fmt.percent(m.getValue("accuracy"))}",
            "d′: ${Fmt.decimal(m.getValue("dPrime"), 2)}",
        )
    }

    companion object {
        /** Сессия — 2 раунда по 20 + N стимулов. */
        const val ROUNDS = 2
        const val SCORED = 20

        /** 30 % от 20 оцениваемых. */
        const val TARGETS = 6

        /** Около 10 % от 20 — при N ≥ 2. */
        const val LURES = 2
        const val SHOW_MS = 500L
        const val LEAD_MS = 1_000L
        const val CELLS = 9
        val INTERVALS = listOf(2_500L, 2_150L, 1_800L)
    }
}
