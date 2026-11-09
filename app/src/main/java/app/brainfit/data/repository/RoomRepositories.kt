package app.brainfit.data.repository

import androidx.room.withTransaction
import app.brainfit.data.db.BrainFitDatabase
import app.brainfit.data.db.DifficultyDao
import app.brainfit.data.db.DifficultyStateEntity
import app.brainfit.data.db.ExerciseSessionEntity
import app.brainfit.data.db.SessionDao
import app.brainfit.data.db.TrialDao
import app.brainfit.data.db.TrialEntity
import app.brainfit.domain.model.GameDifficulty
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.NewSession
import app.brainfit.domain.model.SessionDraft
import app.brainfit.domain.model.SessionStatus
import app.brainfit.domain.model.SessionSummary
import app.brainfit.domain.model.TrialRecord
import app.brainfit.domain.repository.DifficultyRepository
import app.brainfit.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal fun ExerciseSessionEntity.toSummary() = SessionSummary(
    id = id,
    gameId = gameId,
    mode = mode,
    startedAt = startedAt,
    level = level,
    score = score,
)

internal fun DifficultyStateEntity.toDomain() = GameDifficulty(
    gameId = gameId,
    level = level,
    calibrated = calibrated,
)

internal fun TrialRecord.toEntity(sessionId: Long) = TrialEntity(
    sessionId = sessionId,
    index = index,
    paramsJson = paramsJson,
    answerJson = answerJson,
    correct = correct,
    rtMs = rtMs,
    errorType = errorType,
    isShaky = isShaky,
    round = round,
)

internal fun TrialEntity.toRecord() = TrialRecord(
    round = round,
    index = index,
    paramsJson = paramsJson,
    answerJson = answerJson,
    correct = correct,
    rtMs = rtMs,
    errorType = errorType,
    isShaky = isShaky,
)

@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val db: BrainFitDatabase,
    private val sessions: SessionDao,
    private val trials: TrialDao,
) : SessionRepository {

    override fun observeCompletedBetween(fromMillis: Long, toMillis: Long): Flow<List<SessionSummary>> =
        sessions.observeCompletedBetween(fromMillis, toMillis).map { list -> list.map { it.toSummary() } }

    override fun observeCompletedCount(): Flow<Int> = sessions.observeCompletedCount()

    override suspend fun startSession(session: NewSession): Long = db.withTransaction {
        // Черновики, к которым уже не вернутся (приложение смахнули), иначе висели бы вечно.
        sessions.abandonDrafts(session.startedAt)
        sessions.insert(
            ExerciseSessionEntity(
                gameId = session.gameId,
                workoutId = session.workoutId,
                mode = session.mode,
                startedAt = session.startedAt,
                endedAt = null,
                activeMs = 0,
                level = session.level,
                score = null,
                status = SessionStatus.IN_PROGRESS,
                isCalibration = session.isCalibration,
                scoringVersion = session.scoringVersion,
                metricsJson = "{}",
            ),
        )
    }

    override suspend fun saveRound(sessionId: Long, trials: List<TrialRecord>, activeMs: Long) = db.withTransaction {
        requireDraft(sessions.updateActive(sessionId, activeMs), sessionId)
        this.trials.insertAll(trials.map { it.toEntity(sessionId) })
    }

    override suspend fun finishSession(
        sessionId: Long,
        endedAt: Long,
        activeMs: Long,
        score: Double,
        metricsJson: String,
        isShaky: Boolean,
    ) = db.withTransaction {
        requireDraft(sessions.finish(sessionId, endedAt, activeMs, score, metricsJson, isShaky), sessionId)
    }

    override suspend fun abandonSession(sessionId: Long, endedAt: Long, activeMs: Long) = db.withTransaction {
        requireDraft(sessions.abandon(sessionId, endedAt, activeMs), sessionId)
    }

    override suspend fun loadDraft(sessionId: Long): SessionDraft? = db.withTransaction {
        val session = sessions.byId(sessionId)?.takeIf { it.status == SessionStatus.IN_PROGRESS }
            ?: return@withTransaction null
        SessionDraft(
            id = session.id,
            gameId = session.gameId,
            level = session.level,
            activeMs = session.activeMs,
            trials = trials.forSession(sessionId).map { it.toRecord() },
        )
    }

    private fun requireDraft(updatedRows: Int, sessionId: Long) {
        check(updatedRows == 1) { "Сессия $sessionId не найдена среди незавершённых" }
    }
}

@Singleton
class DifficultyRepositoryImpl @Inject constructor(
    private val difficulty: DifficultyDao,
) : DifficultyRepository {

    override fun observeStates(): Flow<Map<GameId, GameDifficulty>> =
        difficulty.observeStates().map { list -> list.associate { it.gameId to it.toDomain() } }
}
