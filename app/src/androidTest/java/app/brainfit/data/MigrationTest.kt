package app.brainfit.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.brainfit.data.db.BrainFitDatabase
import app.brainfit.data.db.Migrations
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Миграции сохраняют данные, а итоговая схема совпадает с той, что ожидает Room.
 * Схемы берутся из app/schemas (создаются при сборке).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BrainFitDatabase::class.java,
    )

    @Test
    fun migrate1To2KeepsSessionsAndTrials() {
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO exercise_sessions (id, gameId, workoutId, mode, startedAt, endedAt, activeMs, level, score, " +
                    "status, isCalibration, scoringVersion, metricsJson) " +
                    "VALUES (7, 'SCHULTE', NULL, 'FREE', 1000, 61000, 55000, 2, 0.8, 'COMPLETED', 1, 1, '{}')",
            )
            execSQL(
                "INSERT INTO trials (id, sessionId, `index`, paramsJson, answerJson, correct, rtMs, errorType, isShaky) " +
                    "VALUES (1, 7, 0, '{}', '{}', 1, 420, NULL, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, *Migrations.ALL)

        db.query("SELECT score, isShaky FROM exercise_sessions WHERE id = 7").use { c ->
            c.moveToFirst()
            assertEquals(0.8, c.getDouble(0), 1e-9)
            // Старые сессии тряскими не считаются.
            assertEquals(0, c.getInt(1))
        }
        db.query("SELECT rtMs, isShaky, round FROM trials WHERE sessionId = 7").use { c ->
            c.moveToFirst()
            assertEquals(420L, c.getLong(0))
            assertEquals(1, c.getInt(1))
            assertEquals(0, c.getInt(2))
        }
        db.close()
    }

    @Test
    fun migrate2To3GivesTrialsTheLevelOfTheirSession() {
        helper.createDatabase(dbName, 2).apply {
            execSQL(
                "INSERT INTO exercise_sessions (id, gameId, workoutId, mode, startedAt, endedAt, activeMs, level, score, " +
                    "status, isCalibration, scoringVersion, metricsJson, isShaky) " +
                    "VALUES (3, 'CORSI', NULL, 'FREE', 1000, 61000, 55000, 4, 0.5, 'COMPLETED', 0, 1, '{}', 0)",
            )
            execSQL(
                "INSERT INTO exercise_sessions (id, gameId, workoutId, mode, startedAt, endedAt, activeMs, level, score, " +
                    "status, isCalibration, scoringVersion, metricsJson, isShaky) " +
                    "VALUES (4, 'NBACK', NULL, 'FREE', 2000, NULL, 0, 6, NULL, 'IN_PROGRESS', 1, 1, '{}', 0)",
            )
            for ((id, session) in listOf(1 to 3, 2 to 3, 3 to 4)) {
                execSQL(
                    "INSERT INTO trials (id, sessionId, `index`, paramsJson, answerJson, correct, rtMs, errorType, isShaky, round) " +
                        "VALUES ($id, $session, $id, '{}', '{}', 1, 300, NULL, 0, 0)",
                )
            }
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 3, true, *Migrations.ALL)

        db.query("SELECT id, level FROM trials ORDER BY id").use { c ->
            val levels = buildList { while (c.moveToNext()) add(c.getInt(0) to c.getInt(1)) }
            assertEquals(listOf(1 to 4, 2 to 4, 3 to 6), levels)
        }
        db.close()
    }

    @Test
    fun migrateAllTheWayFrom1() {
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(dbName, 3, true, *Migrations.ALL).close()
    }
}
