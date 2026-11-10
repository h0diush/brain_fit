package app.brainfit.games.engine

import app.brainfit.core.time.Stopwatch
import kotlin.math.max
import kotlin.random.Random

/** Сыгранный до конца раунд. */
data class CompletedRound(
    val trials: List<TrialResult>,
    /** Активное время раунда без пауз. */
    val activeNanos: Long,
    /** Уровень, на котором раунд игрался. */
    val level: Int,
)

/**
 * Одна сессия игры: раунды по очереди, пауза, перезапуск раунда. Чистый Kotlin, без потоков:
 * все вызовы — с одного потока. Время приходит снаружи (монотонные часы или время кадра).
 *
 * Уровень обычно один на всю сессию. На калибровке хост меняет его между раундами через [changeLevel].
 *
 * @param level уровень первого раунда; число раундов берётся по нему.
 * @param completed уже сыгранные раунды — когда сессию продолжают после смерти процесса.
 * @param previous пробы прошлой сессии этой игры — для того, что переносится между сессиями (длина в Корси).
 */
class GameSession<P, S : Any, I : Any>(
    private val game: Game<P, S, I>,
    level: Int,
    private val seed: Long,
    completed: List<CompletedRound> = emptyList(),
    private val previous: List<TrialResult> = emptyList(),
) {
    enum class Phase { READY, RUNNING, PAUSED, FINISHED }

    init {
        requireLevel(level)
    }

    val startLevel: Int = level

    /** Уровень текущего раунда, а между раундами — следующего. */
    var level: Int = level
        private set

    val totalRounds: Int = game.roundsPerSession(level).also {
        require(it > 0) { "В сессии должен быть хотя бы один раунд" }
    }

    private val rounds = completed.toMutableList()
    val completedRounds: List<CompletedRound> get() = rounds

    /** Номер текущего раунда с нуля; равен числу завершённых. */
    val roundIndex: Int get() = rounds.size

    var phase: Phase = if (rounds.size >= totalRounds) Phase.FINISHED else Phase.READY
        private set

    /** Состояние идущего раунда; null между раундами. */
    var state: S? = null
        private set

    private val stopwatch = Stopwatch()
    private var lastActive = 0L
    private var attempt = 0

    /** Уровень следующего раунда. Только между раундами: посреди раунда задание уже выдано. */
    fun changeLevel(newLevel: Int) {
        check(phase == Phase.READY || phase == Phase.FINISHED) { "Уровень меняется только между раундами, сейчас $phase" }
        requireLevel(newLevel)
        level = newLevel
    }

    fun startRound(nowNanos: Long) {
        check(phase == Phase.READY) { "Раунд можно начать только из READY, сейчас $phase" }
        val params = game.generate(level, Random(roundSeed()), history(), previous)
        state = game.initialState(params, level)
        stopwatch.restart(nowNanos)
        lastActive = 0L
        phase = Phase.RUNNING
    }

    /** Ответ игрока. Возвращает раунд, если этот ответ его завершил. Вне RUNNING ничего не делает. */
    fun input(input: I, nowNanos: Long): CompletedRound? {
        val current = runningState() ?: return null
        state = game.reduce(current, input, active(nowNanos))
        return completeIfFinished()
    }

    /** Ход времени (каждый кадр). Возвращает раунд, если он закончился по времени. */
    fun tick(nowNanos: Long): CompletedRound? {
        val current = runningState() ?: return null
        state = game.tick(current, active(nowNanos))
        return completeIfFinished()
    }

    fun pause(nowNanos: Long) {
        if (phase != Phase.RUNNING) return
        stopwatch.pause(nowNanos)
        phase = Phase.PAUSED
    }

    fun resume(nowNanos: Long) {
        if (phase != Phase.PAUSED) return
        stopwatch.resume(nowNanos)
        phase = Phase.RUNNING
    }

    /** Бросить текущий раунд: следующий startRound даст новое задание. Пробы раунда не сохраняются. */
    fun restartRound() {
        if (phase != Phase.RUNNING && phase != Phase.PAUSED) return
        attempt++
        state = null
        phase = Phase.READY
    }

    fun history(): List<TrialResult> = rounds.flatMap { it.trials }

    /**
     * Оценка сессии. Если уровень не менялся — оценка игры по всем пробам. Если менялся (калибровка) —
     * каждый отрезок на одном уровне оценивается по своему уровню, score — среднее по раундам,
     * а метрики — по всем пробам с уровнем последнего раунда.
     */
    fun score(): SessionScore {
        check(phase == Phase.FINISHED) { "Оценка считается только у завершённой сессии" }
        val runs = mutableListOf<MutableList<CompletedRound>>()
        for (round in rounds) {
            if (runs.lastOrNull()?.last()?.level == round.level) runs.last() += round else runs += mutableListOf(round)
        }
        if (runs.size <= 1) return game.score(history(), rounds.firstOrNull()?.level ?: startLevel)
        val weighted = runs.sumOf { run -> game.score(run.flatMap { it.trials }, run.first().level).score * run.size }
        val all = game.score(history(), rounds.last().level)
        return SessionScore((weighted / rounds.size).coerceIn(0.0, 1.0), all.metrics)
    }

    private fun requireLevel(level: Int) {
        require(level in 1..game.levels.size) { "Уровень $level вне таблицы уровней игры ${game.id}" }
    }

    private fun runningState(): S? = if (phase == Phase.RUNNING) state else null

    /** Активное время раунда. Нажатие и кадр могут прийти не по порядку — назад время не отдаём. */
    private fun active(nowNanos: Long): Long {
        lastActive = max(lastActive, stopwatch.elapsed(nowNanos))
        return lastActive
    }

    private fun completeIfFinished(): CompletedRound? {
        val current = state ?: return null
        if (!game.isFinished(current)) return null
        val round = CompletedRound(game.trials(current), lastActive, level)
        rounds += round
        state = null
        attempt = 0
        phase = if (rounds.size >= totalRounds) Phase.FINISHED else Phase.READY
        return round
    }

    /** Свой seed у каждого раунда и каждой попытки: «Заново» не повторяет то же задание. */
    private fun roundSeed(): Long = (seed * 31 + roundIndex) * 31 + attempt
}
