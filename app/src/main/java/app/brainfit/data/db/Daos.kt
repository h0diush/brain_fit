package app.brainfit.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import app.brainfit.domain.model.GameId
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: ExerciseSessionEntity): Long

    @Query(
        "SELECT * FROM exercise_sessions WHERE status = 'COMPLETED' " +
            "AND startedAt >= :fromMillis AND startedAt < :toMillis ORDER BY startedAt",
    )
    fun observeCompletedBetween(fromMillis: Long, toMillis: Long): Flow<List<ExerciseSessionEntity>>

    @Query("SELECT COUNT(*) FROM exercise_sessions WHERE status = 'COMPLETED'")
    fun observeCompletedCount(): Flow<Int>

    @Query("SELECT * FROM exercise_sessions WHERE id = :id")
    suspend fun byId(id: Long): ExerciseSessionEntity?

    /*
     * Обновления ниже трогают только черновик (status = 'IN_PROGRESS') и возвращают число
     * изменённых строк: 0 значит, что черновика с таким id уже нет.
     */

    @Query("UPDATE exercise_sessions SET status = 'ABANDONED', endedAt = :now WHERE status = 'IN_PROGRESS'")
    suspend fun abandonDrafts(now: Long): Int

    @Query("UPDATE exercise_sessions SET activeMs = :activeMs WHERE id = :id AND status = 'IN_PROGRESS'")
    suspend fun updateActive(id: Long, activeMs: Long): Int

    @Query(
        "UPDATE exercise_sessions SET status = 'COMPLETED', endedAt = :endedAt, activeMs = :activeMs, " +
            "score = :score, metricsJson = :metricsJson, isShaky = :isShaky " +
            "WHERE id = :id AND status = 'IN_PROGRESS'",
    )
    suspend fun finish(id: Long, endedAt: Long, activeMs: Long, score: Double, metricsJson: String, isShaky: Boolean): Int

    @Query(
        "UPDATE exercise_sessions SET status = 'ABANDONED', endedAt = :endedAt, activeMs = :activeMs " +
            "WHERE id = :id AND status = 'IN_PROGRESS'",
    )
    suspend fun abandon(id: Long, endedAt: Long, activeMs: Long): Int
}

@Dao
interface TrialDao {
    @Insert
    suspend fun insertAll(trials: List<TrialEntity>)

    @Query("SELECT * FROM trials WHERE sessionId = :sessionId ORDER BY `index`")
    suspend fun forSession(sessionId: Long): List<TrialEntity>
}

@Dao
interface DifficultyDao {
    @Query("SELECT * FROM difficulty_states")
    fun observeStates(): Flow<List<DifficultyStateEntity>>

    @Upsert
    suspend fun upsert(state: DifficultyStateEntity)

    @Insert
    suspend fun insertChange(change: DifficultyChangeEntity): Long

    @Query("SELECT * FROM difficulty_changes WHERE gameId = :gameId ORDER BY at")
    suspend fun changesFor(gameId: GameId): List<DifficultyChangeEntity>
}

@Dao
interface WorkoutDao {
    @Insert
    suspend fun insert(workout: WorkoutEntity): Long

    @Insert
    suspend fun insertItems(items: List<WorkoutItemEntity>)

    @Query("SELECT * FROM workout_items WHERE workoutId = :workoutId ORDER BY `order`")
    suspend fun itemsFor(workoutId: Long): List<WorkoutItemEntity>
}
