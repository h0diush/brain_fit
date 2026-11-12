package app.brainfit.feature.play

import app.brainfit.core.sensors.ShakeConfig
import app.brainfit.core.sensors.ShakeMonitor
import app.brainfit.core.time.FrameClock
import app.brainfit.core.time.MonotonicClock
import app.brainfit.core.time.WallClock
import app.brainfit.core.util.Json
import app.brainfit.domain.adaptive.Adaptation
import app.brainfit.domain.adaptive.AdaptiveConfig
import app.brainfit.domain.adaptive.AdaptiveConfigs
import app.brainfit.domain.model.AdaptationInput
import app.brainfit.domain.model.DifficultyUpdate
import app.brainfit.domain.model.LevelChange
import app.brainfit.domain.model.NewSession
import app.brainfit.domain.model.SessionDraft
import app.brainfit.domain.model.SessionFinish
import app.brainfit.domain.model.SessionMode
import app.brainfit.domain.model.TrialRecord
import app.brainfit.domain.progress.Progress
import app.brainfit.domain.progress.ProgressMetrics
import app.brainfit.domain.repository.DifficultyRepository
import app.brainfit.domain.repository.ProgressRepository
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
    /** Короткий итог прошлого раунда — показывается между раундами. */
    val roundSummary: String? = null,
    /** Строки результата сессии от игры: время, точность и т. п. */
    val resultLines: List<String> = emptyList(),
    /** Сессия калибровочная: уровень меняется между раундами. */
    val calibration: Boolean = false,
    /** Что калибровка сделала с уровнем после прошлого раунда — показывается между раундами. */
    val levelNote: String? = null,
    /** Что стало с уровнем по итогам сессии — на экране результата. Скрытых изменений нет (ТЗ, раздел 6). */
    val levelLines: List<String> = emptyList(),
    /** Сравнение с прошлыми сессиями на том же уровне — на экране результата. */
    val compareLines: List<String> = emptyList(),
    /** Ошибка сохранения или загрузки. Пока она показана, ввод не принимается; есть «Повторить». */
    val error: String? = null,
    val closed: Boolean = false,
)

/**
 * Ведёт одну сессию игры: раунды, паузу, детектор тряски и запись в базу. От Android не зависит,
 * поэтому целиком проверяется JVM-тестами. Все вызовы — с одного потока ([scope] на главном).
 *
 * Запись: черновик сессии создаётся при старте первого раунда, пробы пишутся после каждого
 * завершённого раунда, итог — в конце вместе с уровнем игры. Недоигранный раунд не сохраняется.
 *
 * Уровень (ТЗ, раздел 6). Пока игра не откалибрована, сессия калибровочная: после каждого блока раундов
 * уровень сдвигается на 1 по результату блока, тряский блок уровень не трогает. После калибровки уровень
 * меняется только по окну сессий ([Adaptation.decide]). Новый уровень пишется в базу с итогом сессии;
 * после смерти процесса уровень калибровки восстанавливается из уровней сохранённых раундов.
 *
 * @param workoutId тренировка, из которой запущена игра, или null для свободной игры.
 * @param draftId черновик, который надо продолжить (из SavedStateHandle), или null.
 * @param onDraftId куда запомнить id черновика, чтобы он пережил смерть процесса.
 */
class PlayController<P, S : Any, I : Any>(
    private val game: Game<P, S, I>,
    private val mode: SessionMode,
    private val scope: CoroutineScope,
    private val sessions: SessionRepository,
    private val difficulty: DifficultyRepository,
    private val progress: ProgressRepository,
    private val settings: SettingsRepository,
    private val shake: ShakeMonitor,
    private val clock: MonotonicClock,
    private val wallClock: WallClock,
    private val seed: Long,
    private val workoutId: Long? = null,
    private val draftId: Long? = null,
    private val onDraftId: (Long?) -> Unit = {},
    private val shakeConfig: ShakeConfig = ShakeConfig(),
    private val adaptive: AdaptiveConfig = AdaptiveConfigs.of(game.id),
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
    private var lastRoundSummary: String? = null
    private var levelNote: String? = null
    private var calibration = false
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
                        level = s.startLevel,
                        isCalibration = calibration,
                        scoringVersion = game.scoringVersion,
                        startedAt = wallClock.nowMillis(),
                        workoutId = workoutId,
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
        val state = difficulty.adaptationInput(game.id, windowLimit()).state
        val level = (state?.level ?: START_LEVEL).coerceIn(1, game.levels.size)
        calibration = state?.calibrated != true
        return GameSession(game, level, seed, previous = previousTrials())
    }

    private suspend fun restore(draft: SessionDraft): GameSession<P, S, I> {
        val byRound = draft.trials.groupBy { it.round }.toSortedMap()
        val rounds = byRound.values.map { records ->
            // Длительность отдельных раундов не хранится: общее активное время берём из черновика.
            val sorted = records.sortedBy { it.index }
            CompletedRound(sorted.map { it.toResult() }, activeNanos = 0L, level = sorted.first().level.coerceIn(1, game.levels.size))
        }
        shakyRounds += byRound.values.map { records -> records.any { it.isShaky } }
        activeNanos = draft.activeMs * FrameClock.NANOS_PER_MS
        sessionId = draft.id
        calibration = draft.isCalibration
        lastRoundSummary = rounds.lastOrNull()?.let { game.roundSummary(it.trials) }
        val s = GameSession(game, draft.level.coerceIn(1, game.levels.size), seed, rounds, previousTrials())
        if (calibration) s.changeLevel(calibrationLevelAfter(s))
        return s
    }

    /** Пробы прошлой завершённой сессии игры: через них переносится то, что игра переносит между сессиями. */
    private suspend fun previousTrials(): List<TrialResult> = sessions.lastCompletedTrials(game.id).map { it.toResult() }

    private fun windowLimit(): Int = maxOf(adaptive.window, adaptive.streak)

    /**
     * Уровень следующего раунда калибровки по уже сыгранным: в конце каждого блока — шаг по score блока,
     * если блок не тряский (тряских раундов больше половины). Внутри блока уровень не меняется.
     */
    private fun calibrationLevelAfter(s: GameSession<P, S, I>): Int {
        val rounds = s.completedRounds
        val last = rounds.lastOrNull() ?: return s.level
        if (rounds.size % adaptive.calibrationBlock != 0) return last.level
        val shaky = shakyRounds.takeLast(adaptive.calibrationBlock)
        if (shaky.count { it } * 2 > shaky.size) return last.level
        val block = rounds.takeLast(adaptive.calibrationBlock).flatMap { it.trials }
        return Adaptation.calibrationStep(last.level, game.score(block, last.level).score, game.levels.size, adaptive)
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
        lastRoundSummary = game.roundSummary(round.trials)
        val roundIndex = s.completedRounds.size - 1
        val firstIndex = s.completedRounds.dropLast(1).sumOf { it.trials.size }
        val records = round.trials.mapIndexed { i, t ->
            TrialRecord(
                round = roundIndex,
                level = round.level,
                index = firstIndex + i,
                paramsJson = t.paramsJson,
                answerJson = t.answerJson,
                correct = t.correct,
                rtMs = t.rtMs,
                errorType = t.errorType,
                isShaky = shaky,
            )
        }
        if (calibration) {
            val next = calibrationLevelAfter(s)
            val blockEnded = s.completedRounds.size % adaptive.calibrationBlock == 0
            val blockShaky = shakyRounds.takeLast(adaptive.calibrationBlock).let { b -> b.count { it } * 2 > b.size }
            levelNote = when {
                next > round.level -> "Калибровка: уровень повышен до $next."
                next < round.level -> "Калибровка: уровень понижен до $next."
                blockEnded && blockShaky -> "Трясло — на калибровке уровень не меняем."
                else -> null
            }
            s.changeLevel(next)
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
        val input = difficulty.adaptationInput(game.id, windowLimit())
        val (update, levelLines) = if (calibration) {
            calibrationResult(s, input, shakySession)
        } else {
            adaptationResult(s, input, score.score, shakySession)
        }
        // Прошлые сессии читаем до записи итога: эта в них не попадёт.
        val metric = ProgressMetrics.main(game.id)
        val compareLines = Progress.comparison(
            metric = metric,
            value = score.metrics[metric.key],
            level = s.startLevel,
            isCalibration = calibration,
            isShaky = shakySession,
            previous = progress.completed(game.id),
        )
        sessions.finishSession(
            SessionFinish(
                sessionId = id,
                endedAt = wallClock.nowMillis(),
                activeMs = activeMs(),
                score = score.score,
                metricsJson = Json.encode(score.metrics),
                isShaky = shakySession,
                difficulty = update,
            ),
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
                roundSummary = null,
                levelNote = null,
                resultLines = game.resultLines(score),
                levelLines = levelLines,
                compareLines = compareLines,
            )
        }
    }

    /** Калибровка: уровень — тот, к которому пришли раунды; калибровка кончается на N-й нетряской сессии. */
    private fun calibrationResult(
        s: GameSession<P, S, I>,
        input: AdaptationInput,
        shakySession: Boolean,
    ): Pair<DifficultyUpdate, List<String>> {
        val levels = s.completedRounds.map { it.level } + s.level
        val changes = levels.zipWithNext()
            .filter { (from, to) -> from != to }
            .map { (from, to) -> LevelChange(from, to, Adaptation.REASON_CALIBRATION) }
        val counted = input.calibrationSessions + if (shakySession) 0 else 1
        val done = counted >= adaptive.calibrationSessions
        val lines = listOfNotNull(
            levelLine(s.startLevel, s.level) ?: "Уровень ${s.level}: калибровка его не изменила.",
            when {
                shakySession -> "Из-за тряски сессия не засчитана в калибровку."
                done -> "Калибровка закончена. Дальше уровень меняется по итогам нескольких сессий подряд."
                else -> "Калибровка: сыграно $counted из ${adaptive.calibrationSessions}."
            },
        )
        return DifficultyUpdate(game.id, s.level, calibrated = done, changes) to lines
    }

    /** Обычные правила: окно сессий на этом уровне плюс только что сыгранная, если она не тряская. */
    private fun adaptationResult(
        s: GameSession<P, S, I>,
        input: AdaptationInput,
        score: Double,
        shakySession: Boolean,
    ): Pair<DifficultyUpdate, List<String>> {
        val level = s.startLevel
        val earlier = if (input.state?.level == level) input.window else emptyList()
        val window = if (shakySession) earlier else listOf(score) + earlier
        val change = Adaptation.decide(level, game.levels.size, window, adaptive)
        val update = DifficultyUpdate(game.id, change?.toLevel ?: level, calibrated = true, listOfNotNull(change))
        return update to listOfNotNull(change?.let { levelLine(it.fromLevel, it.toLevel) })
    }

    private fun levelLine(from: Int, to: Int): String? = when {
        to > from -> "Уровень повышен до $to."
        to < from -> "Уровень понижен до $to."
        else -> null
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
                calibration = calibration,
                roundSummary = if (phase == PlayPhase.READY) lastRoundSummary else null,
                levelNote = if (phase == PlayPhase.READY) levelNote else null,
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
