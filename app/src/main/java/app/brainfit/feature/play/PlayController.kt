package app.brainfit.feature.play

import app.brainfit.core.sensors.ShakeConfig
import app.brainfit.core.sensors.ShakeMonitor
import app.brainfit.core.time.FrameClock
import app.brainfit.core.time.MonotonicClock
import app.brainfit.core.time.WallClock
import app.brainfit.core.util.Json
import app.brainfit.domain.model.NewSession
import app.brainfit.domain.model.SessionDraft
import app.brainfit.domain.model.SessionMode
import app.brainfit.domain.model.TrialRecord
import app.brainfit.domain.repository.DifficultyRepository
import app.brainfit.domain.repository.SessionRepository
import app.brainfit.domain.repository.SettingsRepository
import app.brainfit.games.engine.CompletedRound
import app.brainfit.games.engine.Game
import app.brainfit.games.engine.GameSession
import app.brainfit.games.engine.ResumePolicy
import app.brainfit.games.engine.START_LEVEL
import app.brainfit.games.engine.SessionScore
import app.brainfit.games.engine.TrialResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlayPhase {
    LOADING,

    /** Между раундами: ждём, пока игрок нажмёт «Начать» или «Дальше». */
    READY,
    RUNNING,
    PAUSED,

    /** Идёт запись в базу; ввод не принимается. */
    SAVING,
    FINISHED,
}

data class PlayUiState<S>(
    val phase: PlayPhase = PlayPhase.LOADING,
    /** Номер текущего раунда с нуля. */
    val round: Int = 0,
    val totalRounds: Int = 0,
    val level: Int = 0,
    val gameState: S? = null,
    /** Сессия продолжена после смерти процесса с последнего завершённого раунда. */
    val restored: Boolean = false,
    /** После паузы раунд начинается заново (см. ResumePolicy.RESTART_ROUND). */
    val restartOnResume: Boolean = false,
    val result: SessionScore? = null,
    val shakySession: Boolean = false,
    /** Ошибка сохранения или загрузки. Пока она показана, ввод не принимается; есть «Повторить». */
    val error: String? = null,
    val closed: Boolean = false,
)

/**
 * Ведёт одну сессию игры: раунды, паузу, детектор тряски и запись в базу. От Android не зависит,
 * поэтому целиком проверяется JVM-тестами. Все вызовы — с одного потока ([scope] на главном).
 *
 * Запись: черновик сессии создаётся при старте первого раунда, пробы пишутся после каждого
 * завершённого раунда, итог — в конце. Недоигранный раунд не сохраняется.
 *
 * @param draftId черновик, который надо продолжить (из SavedStateHandle), или null.
 * @param onDraftId куда запомнить id черновика, чтобы он пережил смерть процесса.
 */
class PlayController<P, S : Any, I : Any>(
    private val game: Game<P, S, I>,
    private val mode: SessionMode,
    private val scope: CoroutineScope,
    private val sessions: SessionRepository,
    private val difficulty: DifficultyRepository,
    private val settings: SettingsRepository,
    private val shake: ShakeMonitor,
    private val clock: MonotonicClock,
    private val wallClock: WallClock,
    private val seed: Long,
    private val draftId: Long? = null,
    private val onDraftId: (Long?) -> Unit = {},
    private val shakeConfig: ShakeConfig = ShakeConfig(),
) {
    private val _state = MutableStateFlow(
        PlayUiState<S>(restartOnResume = game.resumePolicy == ResumePolicy.RESTART_ROUND),
    )
    val state: StateFlow<PlayUiState<S>> = _state.asStateFlow()

    private var session: GameSession<P, S, I>? = null
    private var sessionId: Long? = null
    private var shakeEnabled = true
    private val shakyRounds = mutableListOf<Boolean>()
    private var activeNanos = 0L
    private var retryAction: (suspend () -> Unit)? = null

    init {
        launchGuarded("Не удалось открыть игру") { load() }
    }

    fun startRound() {
        val s = session ?: return
        if (!accepts(PlayPhase.READY)) return
        _state.update { it.copy(phase = PlayPhase.SAVING) }
        launchGuarded("Не удалось начать сессию") {
            if (sessionId == null) {
                val id = sessions.startSession(
                    NewSession(
                        gameId = game.id,
                        mode = mode,
                        level = s.level,
                        // Калибровка и подбор уровня — этап 5.
                        isCalibration = false,
                        scoringVersion = game.scoringVersion,
                        startedAt = wallClock.nowMillis(),
                    ),
                )
                sessionId = id
                onDraftId(id)
            }
            beginRound(s)
        }
    }

    fun input(input: I) {
        val s = session ?: return
        if (!accepts(PlayPhase.RUNNING)) return
        afterStep(s, s.input(input, clock.nowNanos()))
    }

    /** Кадр экрана: [frameNanos] — время кадра на шкале монотонных часов. */
    fun frame(frameNanos: Long) {
        val s = session ?: return
        if (!accepts(PlayPhase.RUNNING)) return
        afterStep(s, s.tick(frameNanos))
    }

    fun pause() {
        val s = session ?: return
        if (!accepts(PlayPhase.RUNNING)) return
        s.pause(clock.nowNanos())
        shake.pause()
        publish(s, PlayPhase.PAUSED)
    }

    fun resume() {
        val s = session ?: return
        if (!accepts(PlayPhase.PAUSED)) return
        if (game.resumePolicy == ResumePolicy.RESTART_ROUND) {
            restartRound()
            return
        }
        s.resume(clock.nowNanos())
        if (shakeEnabled) shake.resume()
        publish(s, PlayPhase.RUNNING)
    }

    /** «Заново»: текущий раунд отбрасывается и начинается с новым заданием. Это не ошибка и в базу не пишется. */
    fun restartRound() {
        val s = session ?: return
        if (!accepts(PlayPhase.PAUSED) && !accepts(PlayPhase.RUNNING)) return
        shake.stop()
        s.restartRound()
        beginRound(s)
    }

    /** Выход. Незавершённая сессия помечается брошенной; сыгранные раунды остаются в истории. */
    fun exit() {
        val current = _state.value
        if (current.error != null || current.phase == PlayPhase.SAVING) return
        val id = sessionId
        if (id == null || current.phase == PlayPhase.FINISHED || current.phase == PlayPhase.LOADING) {
            close()
            return
        }
        shake.stop()
        _state.update { it.copy(phase = PlayPhase.SAVING, gameState = null) }
        launchGuarded("Не удалось сохранить выход") {
            sessions.abandonSession(id, wallClock.nowMillis(), activeMs())
            close()
        }
    }

    /** Повторить действие, которое упало с ошибкой. */
    fun retry() {
        val action = retryAction ?: return
        retryAction = null
        _state.update { it.copy(error = null) }
        launchGuarded(null, action)
    }

    /** Выйти, не дожидаясь сохранения. Черновик останется в базе и будет помечен брошенным позже. */
    fun forceClose() {
        shake.stop()
        close()
    }

    private suspend fun load() {
        shakeEnabled = settings.settings.first().shakeDetectorEnabled
        val draft = draftId?.let { sessions.loadDraft(it) }?.takeIf { it.gameId == game.id }
        val s = if (draft != null) restore(draft) else fresh()
        session = s
        if (s.phase == GameSession.Phase.FINISHED) {
            // Процесс убили между записью последнего раунда и записью итога.
            finish(s)
        } else {
            publish(s, PlayPhase.READY, restored = draft != null)
        }
    }

    private suspend fun fresh(): GameSession<P, S, I> {
        onDraftId(null)
        val level = (difficulty.observeStates().first()[game.id]?.level ?: START_LEVEL)
            .coerceIn(1, game.levels.size)
        return GameSession(game, level, seed)
    }

    private fun restore(draft: SessionDraft): GameSession<P, S, I> {
        val byRound = draft.trials.groupBy { it.round }.toSortedMap()
        val rounds = byRound.values.map { records ->
            // Длительность отдельных раундов не хранится: общее активное время берём из черновика.
            CompletedRound(records.sortedBy { it.index }.map { it.toResult() }, activeNanos = 0L)
        }
        shakyRounds += byRound.values.map { records -> records.any { it.isShaky } }
        activeNanos = draft.activeMs * FrameClock.NANOS_PER_MS
        sessionId = draft.id
        return GameSession(game, draft.level, seed, rounds)
    }

    private fun beginRound(s: GameSession<P, S, I>) {
        s.startRound(clock.nowNanos())
        // Детектор выключен в настройках — датчик не трогаем вовсе.
        if (shakeEnabled) shake.start()
        publish(s, PlayPhase.RUNNING)
    }

    private fun afterStep(s: GameSession<P, S, I>, completed: CompletedRound?) {
        if (completed != null) {
            onRoundCompleted(s, completed)
        } else if (s.state !== _state.value.gameState) {
            _state.update { it.copy(gameState = s.state) }
        }
    }

    private fun onRoundCompleted(s: GameSession<P, S, I>, round: CompletedRound) {
        val id = checkNotNull(sessionId) { "Раунд завершён, а черновика сессии нет" }
        // stop() вызываем всегда, чтобы датчик не остался включённым.
        val rms = shake.stop()
        val shaky = shakeEnabled && shakeConfig.isShaky(rms)
        shakyRounds += shaky
        activeNanos += round.activeNanos
        val roundIndex = s.completedRounds.size - 1
        val firstIndex = s.completedRounds.dropLast(1).sumOf { it.trials.size }
        val records = round.trials.mapIndexed { i, t ->
            TrialRecord(
                round = roundIndex,
                index = firstIndex + i,
                paramsJson = t.paramsJson,
                answerJson = t.answerJson,
                correct = t.correct,
                rtMs = t.rtMs,
                errorType = t.errorType,
                isShaky = shaky,
            )
        }
        val activeMs = activeMs()
        _state.update { it.copy(phase = PlayPhase.SAVING, gameState = null) }
        launchGuarded("Не удалось сохранить раунд") {
            sessions.saveRound(id, records, activeMs)
            // Итог пишется отдельным шагом: если он упадёт, «Повторить» не запишет раунд второй раз.
            if (s.phase == GameSession.Phase.FINISHED) {
                guarded("Не удалось сохранить результат") { finish(s) }
            } else {
                publish(s, PlayPhase.READY)
            }
        }
    }

    private suspend fun finish(s: GameSession<P, S, I>) {
        val id = checkNotNull(sessionId) { "Сессия завершена, а черновика нет" }
        val score = s.score()
        // Больше половины раундов в тряске — вся сессия не влияет на уровень и рекорды скорости.
        val shakySession = shakyRounds.count { it } * 2 > shakyRounds.size
        sessions.finishSession(
            sessionId = id,
            endedAt = wallClock.nowMillis(),
            activeMs = activeMs(),
            score = score.score,
            metricsJson = Json.encode(score.metrics),
            isShaky = shakySession,
        )
        onDraftId(null)
        _state.update {
            it.copy(
                phase = PlayPhase.FINISHED,
                round = s.totalRounds,
                totalRounds = s.totalRounds,
                level = s.level,
                gameState = null,
                restored = false,
                result = score,
                shakySession = shakySession,
            )
        }
    }

    private fun close() {
        onDraftId(null)
        _state.update { it.copy(closed = true) }
    }

    private fun publish(s: GameSession<P, S, I>, phase: PlayPhase, restored: Boolean = false) {
        _state.update {
            it.copy(
                phase = phase,
                round = s.roundIndex,
                totalRounds = s.totalRounds,
                level = s.level,
                gameState = s.state,
                restored = restored,
            )
        }
    }

    private fun accepts(phase: PlayPhase): Boolean =
        _state.value.let { it.phase == phase && it.error == null }

    private fun activeMs(): Long = FrameClock.nanosToMs(activeNanos)

    private fun launchGuarded(what: String?, block: suspend () -> Unit) {
        scope.launch { guarded(what, block) }
    }

    /** Ошибку не глотаем: показываем и запоминаем действие для «Повторить». */
    private suspend fun guarded(what: String?, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            retryAction = { guarded(what, block) }
            val details = e.message ?: e.javaClass.simpleName
            _state.update { it.copy(error = if (what != null) "$what: $details" else details) }
        }
    }

    private fun TrialRecord.toResult() = TrialResult(paramsJson, answerJson, correct, rtMs, errorType)
}
