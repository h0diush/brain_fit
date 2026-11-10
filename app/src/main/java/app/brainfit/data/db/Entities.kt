package app.brainfit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.SessionMode
import app.brainfit.domain.model.SessionStatus
import app.brainfit.domain.model.WorkoutMode
import app.brainfit.domain.model.WorkoutStatus

/*
 * Схема из раздела 7 ТЗ. Источник истины — сессии и пробы; рекорды, графики и серии
 * вычисляются из них. Даты (startedAt, endedAt, updatedAt, at) — время по часам устройства
 * в мс с эпохи: это календарь, а не время реакции. Время реакции (rtMs) меряется монотонными часами.
 */

@Entity(
    tableName = "exercise_sessions",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("gameId", "startedAt"), Index("startedAt"), Index("workoutId")],
)
data class ExerciseSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: GameId,
    /** null — свободная игра вне тренировки. */
    val workoutId: Long?,
    val mode: SessionMode,
    val startedAt: Long,
    val endedAt: Long?,
    /** Активное время без пауз. */
    val activeMs: Long,
    /** Уровень, с которого сессия началась. Уровень каждого раунда — в trials.level. */
    val level: Int,
    /** 0..1; у брошенной сессии оценки нет. */
    val score: Double?,
    val status: SessionStatus,
    val isCalibration: Boolean,
    /** Версия формулы score: при её смене старые сессии можно пересчитать из проб. */
    val scoringVersion: Int,
    val metricsJson: String,
    /** Больше половины раундов шли при сильной тряске: сессия не влияет на уровень и рекорды скорости. С версии 2. */
    @ColumnInfo(defaultValue = "0") val isShaky: Boolean = false,
)

@Entity(
    tableName = "trials",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "index"], unique = true)],
)
data class TrialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val index: Int,
    val paramsJson: String,
    val answerJson: String,
    val correct: Boolean,
    /** null — ответа не было (пропуск, истёк лимит). */
    val rtMs: Long?,
    val errorType: String?,
    val isShaky: Boolean,
    /** Номер раунда в сессии с нуля: по нему сессия продолжается после смерти процесса. С версии 2. */
    @ColumnInfo(defaultValue = "0") val round: Int = 0,
    /** Уровень раунда: на калибровке он меняется между раундами. С версии 3. */
    @ColumnInfo(defaultValue = "0") val level: Int,
)

@Entity(tableName = "difficulty_states")
data class DifficultyStateEntity(
    @PrimaryKey val gameId: GameId,
    val level: Int,
    val calibrated: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "difficulty_changes", indices = [Index("gameId", "at")])
data class DifficultyChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: GameId,
    val fromLevel: Int,
    val toLevel: Int,
    val reason: String,
    val at: Long,
)

@Entity(tableName = "workouts", indices = [Index("date")])
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Местная дата тренировки как число дней с эпохи (LocalDate.toEpochDay). */
    val date: Long,
    val mode: WorkoutMode,
    val status: WorkoutStatus,
)

@Entity(
    tableName = "workout_items",
    primaryKeys = ["workoutId", "order"],
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExerciseSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("sessionId")],
)
data class WorkoutItemEntity(
    val workoutId: Long,
    val order: Int,
    val gameId: GameId,
    /** null — игра ещё не сыграна или пропущена. */
    val sessionId: Long?,
    val skipped: Boolean,
)
