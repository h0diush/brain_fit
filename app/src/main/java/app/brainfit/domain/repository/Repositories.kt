package app.brainfit.domain.repository

import app.brainfit.domain.model.AppSettings
import app.brainfit.domain.model.GameDifficulty
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.NewSession
import app.brainfit.domain.model.SessionDraft
import app.brainfit.domain.model.SessionSummary
import app.brainfit.domain.model.TrialRecord
import app.brainfit.domain.model.ThemeMode
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

    /*
     * Запись сессии. Каждый вызов — одна транзакция; при ошибке бросает исключение,
     * и вызывающий обязан показать её пользователю.
     */

    /** Создаёт черновик и возвращает его id. Старые брошенные черновики помечаются ABANDONED. */
    suspend fun startSession(session: NewSession): Long

    /** Сохраняет пробы завершённого раунда и активное время сессии на этот момент. */
    suspend fun saveRound(sessionId: Long, trials: List<TrialRecord>, activeMs: Long)

    suspend fun finishSession(
        sessionId: Long,
        endedAt: Long,
        activeMs: Long,
        score: Double,
        metricsJson: String,
        isShaky: Boolean,
    )

    suspend fun abandonSession(sessionId: Long, endedAt: Long, activeMs: Long)

    /** Черновик с сохранёнными раундами; null, если сессии нет или она уже не черновик. */
    suspend fun loadDraft(sessionId: Long): SessionDraft?
}

interface DifficultyRepository {
    /** Только игры, в которые уже играли. */
    fun observeStates(): Flow<Map<GameId, GameDifficulty>>
}
