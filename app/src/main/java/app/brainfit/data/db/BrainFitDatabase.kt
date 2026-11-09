package app.brainfit.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ExerciseSessionEntity::class,
        TrialEntity::class,
        DifficultyStateEntity::class,
        DifficultyChangeEntity::class,
        WorkoutEntity::class,
        WorkoutItemEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class BrainFitDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun trialDao(): TrialDao
    abstract fun difficultyDao(): DifficultyDao
    abstract fun workoutDao(): WorkoutDao

    companion object {
        const val NAME = "brainfit.db"
    }
}

/**
 * Новая схема = version + 1, миграция здесь, запись в ALL и тест в MigrationTest.
 * fallbackToDestructiveMigration не используется: данные пользователя не стираются.
 *
 * Версии: 1 → 2 — exercise_sessions.isShaky и trials.round (этап 2, игровой движок).
 */
object Migrations {
    const val SQL_1_2_SESSION_SHAKY = "ALTER TABLE exercise_sessions ADD COLUMN isShaky INTEGER NOT NULL DEFAULT 0"
    const val SQL_1_2_TRIAL_ROUND = "ALTER TABLE trials ADD COLUMN round INTEGER NOT NULL DEFAULT 0"

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(SQL_1_2_SESSION_SHAKY)
            db.execSQL(SQL_1_2_TRIAL_ROUND)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
