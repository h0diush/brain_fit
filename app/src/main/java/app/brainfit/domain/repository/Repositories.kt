package app.brainfit.domain.repository

import app.brainfit.domain.model.AdaptationInput
import app.brainfit.domain.model.AppSettings
import app.brainfit.domain.model.GameDifficulty
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.LevelChangeRecord
import app.brainfit.domain.model.NewSession
import app.brainfit.domain.model.PlanningInput
import app.brainfit.domain.model.ProgressSession
import app.brainfit.domain.model.SessionDraft
import app.brainfit.domain.model.SessionFinish
import app.brainfit.domain.model.SessionSummary
import app.brainfit.domain.model.ThemeMode
import app.brainfit.domain.model.TrialRecord
import app.brainfit.domain.model.Workout
import app.brainfit.domain.model.WorkoutMode
import kotlinx.coroutines.flow.Flow

/** Запись бросает исключение при ошибке хранилища: вызывающий обязан показать её пользователю. */
interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setSoundEnabled(enabled: Boolean)
    suspend fun setVibrationEnabled(enabled: Boolean)
    suspend fun setShakeDetectorEnabled(enabled: Boolean)
}

interface SessionRepository {
    /** Завершённые сессии с началом в интервале [fromMillis, toMillis), по возрастанию времени. */
    fun observeCompletedBetween(fromMillis: Long, toMillis: Long): Flow<List<SessionSummary>>
    fun observeCompletedCount(): Flow<Int>

    /** Пробы последней завершённой сессии игры; пусто, если таких сессий нет. */
    suspend fun lastCompletedTrials(gameId: GameId): List<TrialRecord>

    /*
     * Запись сессии. Каждый вызов — одна транзакция; при ошибке бросает исключение,
     * и вызывающий обязан показать её пользователю.
     */

    /** Создаёт черновик и возвращает его id. Старые брошенные черновики помечаются ABANDONED. */
    suspend fun startSession(session: NewSession): Long

    /** Сохраняет пробы завершённого раунда и активное время сессии на этот момент. */
    suspend fun saveRound(sessionId: Long, trials: List<TrialRecord>, activeMs: Long)

    /**
     * Итог сессии вместе с уровнем игры и пунктом тренировки, если сессия из тренировки.
     * Когда это последняя несыгранная игра тренировки, тренировка становится завершённой.
     */
    suspend fun finishSession(finish: SessionFinish)

    suspend fun abandonSession(sessionId: Long, endedAt: Long, activeMs: Long)

    /** Черновик с сохранёнными раундами; null, если сессии нет или она уже не черновик. */
    suspend fun loadDraft(sessionId: Long): SessionDraft?
}

interface DifficultyRepository {
    /** Только игры, в которые уже играли. */
    fun observeStates(): Flow<Map<GameId, GameDifficulty>>

    /** Состояние уровня игры и окно сессий для правил адаптации — одним чтением. */
    suspend fun adaptationInput(gameId: GameId, windowLimit: Int): AdaptationInput
}

interface WorkoutRepository {
    fun observe(workoutId: Long): Flow<Workout?>

    /** Незавершённая тренировка за этот день (число дней с эпохи); null — такой нет. */
    fun observeActive(date: Long): Flow<Workout?>

    /** Данные для подбора игр: что когда играли, что сегодня было в тренировках, что было в прошлой. */
    suspend fun planningInput(dayStartMillis: Long, dayEndMillis: Long): PlanningInput

    /** Новая тренировка. Незавершённые тренировки помечаются брошенными: новая заменяет старую. */
    suspend fun create(date: Long, mode: WorkoutMode, games: List<GameId>): Long

    /** Пропустить игру. Пропуск — не ошибка: в оценки он не идёт. */
    suspend fun skip(workoutId: Long, order: Int)

    /** Незавершённые тренировки прошлых дней — брошенные: «Продолжить» живёт до конца суток. */
    suspend fun abandonStale(today: Long)
}

/** Данные для прогресса. Всё считается из сырых сессий и смен уровня; ничего не хранится отдельно. */
interface ProgressRepository {
    /** Все завершённые сессии всех игр по возрастанию времени начала. */
    fun observeCompleted(): Flow<List<ProgressSession>>

    /** Завершённые сессии игры по возрастанию времени начала. */
    fun observeCompleted(gameId: GameId): Flow<List<ProgressSession>>

    /** То же одним чтением — для сравнения на экране результата. */
    suspend fun completed(gameId: GameId): List<ProgressSession>

    /** Смены уровня игры по порядку. */
    fun observeLevelChanges(gameId: GameId): Flow<List<LevelChangeRecord>>
}
