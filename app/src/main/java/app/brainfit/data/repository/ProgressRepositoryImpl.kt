package app.brainfit.data.repository

import app.brainfit.core.util.Json
import app.brainfit.data.db.DifficultyChangeEntity
import app.brainfit.data.db.DifficultyDao
import app.brainfit.data.db.ExerciseSessionEntity
import app.brainfit.data.db.SessionDao
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.LevelChangeRecord
import app.brainfit.domain.model.ProgressSession
import app.brainfit.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Метрики хранятся плоским JSON-объектом «ключ — число». Битый JSON — это ошибка данных, а не «нет
 * метрик»: бросаем исключение с id сессии, экран покажет его.
 */
internal fun ExerciseSessionEntity.toProgress(): ProgressSession {
    val metrics = try {
        Json.parseObject(metricsJson)
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException("Сессия $id: не читаются метрики (${e.message})", e)
    }
    return ProgressSession(
        id = id,
        gameId = gameId,
        mode = mode,
        startedAt = startedAt,
        level = level,
        isCalibration = isCalibration,
        isShaky = isShaky,
        score = score,
        metrics = metrics.mapNotNull { (key, value) -> (value as? Number)?.let { key to it.toDouble() } }.toMap(),
    )
}

internal fun DifficultyChangeEntity.toRecord() = LevelChangeRecord(gameId, fromLevel, toLevel, reason, at)

@Singleton
class ProgressRepositoryImpl @Inject constructor(
    private val sessions: SessionDao,
    private val difficulty: DifficultyDao,
) : ProgressRepository {

    override fun observeCompleted(): Flow<List<ProgressSession>> =
        sessions.observeAllCompleted().map { rows -> rows.map { it.toProgress() } }

    override fun observeCompleted(gameId: GameId): Flow<List<ProgressSession>> =
        sessions.observeCompletedFor(gameId).map { rows -> rows.map { it.toProgress() } }

    override suspend fun completed(gameId: GameId): List<ProgressSession> =
        sessions.completedFor(gameId).map { it.toProgress() }

    override fun observeLevelChanges(gameId: GameId): Flow<List<LevelChangeRecord>> =
        difficulty.observeChangesFor(gameId).map { rows -> rows.map { it.toRecord() } }
}
