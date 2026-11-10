package app.brainfit.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import app.brainfit.domain.model.GameId
import kotlinx.coroutines.flow.Flow

/** Когда игру последний раз играли (начало последней завершённой сессии). */
data class GameLastPlayed(val gameId: GameId, val lastAt: Long)

/** Пункт тренировки вместе с итогом его сессии. */
data class WorkoutItemRow(
    val workoutId: Long,
    val order: Int,
    val gameId: GameId,
    val sessionId: Long?,
    val skipped: Boolean,
    val score: Double?,
    val activeMs: Long?,
)

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

    @Query(
        "SELECT * FROM exercise_sessions WHERE gameId = :gameId AND status = 'COMPLETED' " +
            "ORDER BY startedAt DESC, id DESC LIMIT 1",
    )
    suspend fun lastCompleted(gameId: GameId): ExerciseSessionEntity?

    /** Окно адаптации: обычные сессии без тряски на уровне [level], закончившиеся после [afterMillis]. */
    @Query(
        "SELECT score FROM exercise_sessions WHERE gameId = :gameId AND status = 'COMPLETED' " +
            "AND isCalibration = 0 AND isShaky = 0 AND level = :level AND endedAt > :afterMillis " +
            "AND score IS NOT NULL ORDER BY endedAt DESC, id DESC LIMIT :limit",
    )
    suspend fun windowScores(gameId: GameId, level: Int, afterMillis: Long, limit: Int): List<Double>

    @Query(
        "SELECT COUNT(*) FROM exercise_sessions WHERE gameId = :gameId AND status = 'COMPLETED' " +
            "AND isCalibration = 1 AND isShaky = 0",
    )
    suspend fun calibrationCount(gameId: GameId): Int

    @Query(
        "SELECT gameId, MAX(startedAt) AS lastAt FROM exercise_sessions WHERE status = 'COMPLETED' GROUP BY gameId",
    )
    suspend fun lastPlayed(): List<GameLastPlayed>

    @Query(
        "SELECT DISTINCT gameId FROM exercise_sessions WHERE status = 'COMPLETED' AND workoutId IS NOT NULL " +
            "AND startedAt >= :fromMillis AND startedAt < :toMillis",
    )
    suspend fun gamesInWorkoutsBetween(fromMillis: Long, toMillis: Long): List<GameId>

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

    @Query("SELECT * FROM difficulty_states WHERE gameId = :gameId")
    suspend fun state(gameId: GameId): DifficultyStateEntity?

    @Upsert
    suspend fun upsert(state: DifficultyStateEntity)

    @Insert
    suspend fun insertChange(change: DifficultyChangeEntity): Long

    @Insert
    suspend fun insertChanges(changes: List<DifficultyChangeEntity>)

    @Query("SELECT * FROM difficulty_changes WHERE gameId = :gameId ORDER BY at, id")
    suspend fun changesFor(gameId: GameId): List<DifficultyChangeEntity>

    @Query("SELECT MAX(at) FROM difficulty_changes WHERE gameId = :gameId")
    suspend fun lastChangeAt(gameId: GameId): Long?
}

@Dao
interface WorkoutDao {
    @Insert
    suspend fun insert(workout: WorkoutEntity): Long

    @Insert
    suspend fun insertItems(items: List<WorkoutItemEntity>)

    @Query("SELECT * FROM workout_items WHERE workoutId = :workoutId ORDER BY `order`")
    suspend fun itemsFor(workoutId: Long): List<WorkoutItemEntity>

    @Query("SELECT * FROM workouts WHERE id = :id")
    fun observe(id: Long): Flow<WorkoutEntity?>

    @Query("SELECT * FROM workouts WHERE date = :date AND status = 'IN_PROGRESS' ORDER BY id DESC LIMIT 1")
    fun observeActive(date: Long): Flow<WorkoutEntity?>

    @Query(
        "SELECT wi.workoutId, wi.`order`, wi.gameId, wi.sessionId, wi.skipped, s.score AS score, s.activeMs AS activeMs " +
            "FROM workout_items wi LEFT JOIN exercise_sessions s ON s.id = wi.sessionId " +
            "WHERE wi.workoutId = :workoutId ORDER BY wi.`order`",
    )
    fun observeItems(workoutId: Long): Flow<List<WorkoutItemRow>>

    @Query("SELECT * FROM workouts ORDER BY id DESC LIMIT 1")
    suspend fun latest(): WorkoutEntity?

    @Query("UPDATE workouts SET status = 'ABANDONED' WHERE status = 'IN_PROGRESS'")
    suspend fun abandonInProgress(): Int

    @Query("UPDATE workouts SET status = 'ABANDONED' WHERE status = 'IN_PROGRESS' AND date < :today")
    suspend fun abandonStale(today: Long): Int

    /** Привязать сессию к несыгранному пункту этой игры. */
    @Query(
        "UPDATE workout_items SET sessionId = :sessionId WHERE workoutId = :workoutId AND gameId = :gameId " +
            "AND sessionId IS NULL AND skipped = 0",
    )
    suspend fun linkSession(workoutId: Long, gameId: GameId, sessionId: Long): Int

    @Query(
        "UPDATE workout_items SET skipped = 1 WHERE workoutId = :workoutId AND `order` = :order " +
            "AND sessionId IS NULL AND skipped = 0",
    )
    suspend fun skip(workoutId: Long, order: Int): Int

    /** Тренировка завершена, когда каждая игра сыграна или пропущена. */
    @Query(
        "UPDATE workouts SET status = 'COMPLETED' WHERE id = :workoutId AND status = 'IN_PROGRESS' " +
            "AND NOT EXISTS (SELECT 1 FROM workout_items WHERE workoutId = :workoutId AND sessionId IS NULL AND skipped = 0)",
    )
    suspend fun completeIfDone(workoutId: Long): Int
}
