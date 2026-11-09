package app.brainfit.games.engine

import app.brainfit.core.time.Stopwatch
import kotlin.math.max
import kotlin.random.Random

/** Сыгранный до конца раунд. */
data class CompletedRound(
    val trials: List<TrialResult>,
    /** Активное время раунда без пауз. */
    val activeNanos: Long,
)

/**
 * Одна сессия игры: раунды по очереди, пауза, перезапуск раунда. Чистый Kotlin, без потоков:
 * все вызовы — с одного потока. Время приходит снаружи (монотонные часы или время кадра).
 *
 * @param completed уже сыгранные раунды — когда сессию продолжают после смерти процесса.
 */
class GameSession<P, S : Any, I : Any>(
    private val game: Game<P, S, I>,
    val level: Int,
    private val seed: Long,
    completed: List<CompletedRound> = emptyList(),
) {
    enum class Phase { READY, RUNNING, PAUSED, FINISHED }

    init {
        require(level in 1..game.levels.size) { "Уровень $level вне таблицы уровней игры ${game.id}" }
    }

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

    fun startRound(nowNanos: Long) {
        check(phase == Phase.READY) { "Раунд можно начать только из READY, сейчас $phase" }
        val params = game.generate(level, Random(roundSeed()), history())
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

    fun score(): SessionScore {
        check(phase == Phase.FINISHED) { "Оценка считается только у завершённой сессии" }
        return game.score(history(), level)
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
        val round = CompletedRound(game.trials(current), lastActive)
        rounds += round
        state = null
        attempt = 0
        phase = if (rounds.size >= totalRounds) Phase.FINISHED else Phase.READY
        return round
    }

    /** Свой seed у каждого раунда и каждой попытки: «Заново» не повторяет то же задание. */
    private fun roundSeed(): Long = (seed * 31 + roundIndex) * 31 + attempt
}
