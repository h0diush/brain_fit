package app.brainfit.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brainfit.data.db.BrainFitDatabase
import app.brainfit.data.db.DifficultyChangeEntity
import app.brainfit.data.db.DifficultyStateEntity
import app.brainfit.data.db.ExerciseSessionEntity
import app.brainfit.data.db.TrialEntity
import app.brainfit.data.db.WorkoutEntity
import app.brainfit.data.db.WorkoutItemEntity
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.SessionMode
import app.brainfit.domain.model.SessionStatus
import app.brainfit.domain.model.WorkoutMode
import app.brainfit.domain.model.WorkoutStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Схема версии 1 на настоящей SQLite: запись и чтение, внешние ключи, фильтр по статусу.
 * Запуск: `gradlew connectedDebugAndroidTest` (нужен телефон или эмулятор).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseTest {

    private lateinit var db: BrainFitDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrainFitDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    private fun session(
        game: GameId,
        startedAt: Long,
        status: SessionStatus = SessionStatus.COMPLETED,
        workoutId: Long? = null,
    ) = ExerciseSessionEntity(
        gameId = game,
        workoutId = workoutId,
        mode = if (workoutId == null) SessionMode.FREE else SessionMode.QUICK,
        startedAt = startedAt,
        endedAt = startedAt + 60_000,
        activeMs = 55_000,
        level = 2,
        score = if (status == SessionStatus.COMPLETED) 0.8 else null,
        status = status,
        isCalibration = true,
        scoringVersion = 1,
        metricsJson = "{}",
    )

    private fun trial(sessionId: Long, index: Int) = TrialEntity(
        sessionId = sessionId,
        index = index,
        paramsJson = "{}",
        answerJson = "{}",
        correct = index % 2 == 0,
        rtMs = if (index == 0) null else 400L + index,
        errorType = null,
        isShaky = index == 1,
        level = 2,
    )

    @Test
    fun sessionAndTrialsRoundTrip() = runTest {
        val id = db.sessionDao().insert(session(GameId.SCHULTE, 1_000))
        db.trialDao().insertAll(listOf(trial(id, 2), trial(id, 0), trial(id, 1)))

        val trials = db.trialDao().forSession(id)
        assertEquals(listOf(0, 1, 2), trials.map { it.index })
        assertNull(trials[0].rtMs)
        assertTrue(trials[1].isShaky)

        val completed = db.sessionDao().observeCompletedBetween(0, 2_000).first()
        assertEquals(GameId.SCHULTE, completed.single().gameId)
        assertEquals(0.8, completed.single().score!!, 1e-9)
    }

    @Test
    fun abandonedSessionsAreNotCounted() = runTest {
        db.sessionDao().insert(session(GameId.MATH, 1_000))
        db.sessionDao().insert(session(GameId.MATH, 2_000, SessionStatus.ABANDONED))
        assertEquals(1, db.sessionDao().observeCompletedCount().first())
        // Интервал полуоткрытый: [from, to).
        assertEquals(0, db.sessionDao().observeCompletedBetween(0, 1_000).first().size)
        assertEquals(1, db.sessionDao().observeCompletedBetween(1_000, 1_001).first().size)
    }

    @Test
    fun deletingSessionDeletesItsTrials() = runTest {
        val id = db.sessionDao().insert(session(GameId.CORSI, 1_000))
        db.trialDao().insertAll(listOf(trial(id, 0), trial(id, 1)))
        db.openHelper.writableDatabase.execSQL("DELETE FROM exercise_sessions WHERE id = $id")
        assertTrue(db.trialDao().forSession(id).isEmpty())
    }

    @Test
    fun workoutLinksSurviveSessionDeletion() = runTest {
        val workoutId = db.workoutDao().insert(WorkoutEntity(date = 20_766, mode = WorkoutMode.QUICK, status = WorkoutStatus.IN_PROGRESS))
        val sessionId = db.sessionDao().insert(session(GameId.STROOP, 1_000, workoutId = workoutId))
        db.workoutDao().insertItems(
            listOf(
                WorkoutItemEntity(workoutId, order = 1, gameId = GameId.NBACK, sessionId = null, skipped = false),
                WorkoutItemEntity(workoutId, order = 0, gameId = GameId.STROOP, sessionId = sessionId, skipped = false),
            ),
        )
        assertEquals(listOf(GameId.STROOP, GameId.NBACK), db.workoutDao().itemsFor(workoutId).map { it.gameId })

        db.openHelper.writableDatabase.execSQL("DELETE FROM exercise_sessions WHERE id = $sessionId")
        assertNull(db.workoutDao().itemsFor(workoutId).first().sessionId)
    }

    @Test
    fun difficultyStateIsUpsertedAndChangesAreKept() = runTest {
        val dao = db.difficultyDao()
        dao.upsert(DifficultyStateEntity(GameId.NBACK, level = 2, calibrated = false, updatedAt = 1))
        dao.upsert(DifficultyStateEntity(GameId.NBACK, level = 3, calibrated = true, updatedAt = 2))
        dao.insertChange(DifficultyChangeEntity(gameId = GameId.NBACK, fromLevel = 2, toLevel = 3, reason = "calibration", at = 2))

        val state = dao.observeStates().first().single()
        assertEquals(3, state.level)
        assertTrue(state.calibrated)
        assertEquals(3, dao.changesFor(GameId.NBACK).single().toLevel)
    }
}
