package app.brainfit.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brainfit.data.db.BrainFitDatabase
import app.brainfit.data.repository.DifficultyRepositoryImpl
import app.brainfit.data.repository.SessionRepositoryImpl
import app.brainfit.data.repository.WorkoutRepositoryImpl
import app.brainfit.domain.model.DifficultyUpdate
import app.brainfit.domain.model.GameDifficulty
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.LevelChange
import app.brainfit.domain.model.NewSession
import app.brainfit.domain.model.SessionFinish
import app.brainfit.domain.model.SessionMode
import app.brainfit.domain.model.SessionStatus
import app.brainfit.domain.model.TrialRecord
import app.brainfit.domain.model.WorkoutMode
import app.brainfit.domain.model.WorkoutStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Запись сессии на настоящей SQLite: черновик, раунды, итог с уровнем и тренировкой, выход, продолжение. */
@RunWith(AndroidJUnit4::class)
class SessionRepositoryTest {

    private lateinit var db: BrainFitDatabase
    private lateinit var repo: SessionRepositoryImpl
    private lateinit var difficulty: DifficultyRepositoryImpl
    private lateinit var workouts: WorkoutRepositoryImpl

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrainFitDatabase::class.java).build()
        repo = SessionRepositoryImpl(db, db.sessionDao(), db.trialDao(), db.difficultyDao(), db.workoutDao())
        difficulty = DifficultyRepositoryImpl(db, db.difficultyDao(), db.sessionDao())
        workouts = WorkoutRepositoryImpl(db, db.workoutDao(), db.sessionDao())
    }

    @After
    fun close() = db.close()

    private fun newSession(
        game: GameId = GameId.CORSI,
        startedAt: Long = 1_000,
        level: Int = 3,
        isCalibration: Boolean = false,
        workoutId: Long? = null,
    ) = NewSession(
        gameId = game,
        mode = if (workoutId == null) SessionMode.FREE else SessionMode.QUICK,
        level = level,
        isCalibration = isCalibration,
        scoringVersion = 1,
        startedAt = startedAt,
        workoutId = workoutId,
    )

    private fun trial(round: Int, index: Int, shaky: Boolean = false, level: Int = 3) = TrialRecord(
        round = round,
        level = level,
        index = index,
        paramsJson = """{"n":$index}""",
        answerJson = "{}",
        correct = true,
        rtMs = 300L + index,
        errorType = null,
        isShaky = shaky,
    )

    private fun finish(
        id: Long,
        endedAt: Long,
        score: Double = 0.9,
        isShaky: Boolean = false,
        game: GameId = GameId.CORSI,
        level: Int = 3,
        calibrated: Boolean = true,
        changes: List<LevelChange> = emptyList(),
        activeMs: Long = 9_000,
        metricsJson: String = "{}",
    ) = SessionFinish(id, endedAt, activeMs, score, metricsJson, isShaky, DifficultyUpdate(game, level, calibrated, changes))

    @Test
    fun draftIsInvisibleUntilFinished() = runTest {
        val id = repo.startSession(newSession())
        repo.saveRound(id, listOf(trial(0, 0), trial(0, 1)), activeMs = 4_000)
        assertEquals(0, repo.observeCompletedCount().first())
        assertTrue(repo.observeCompletedBetween(0, 10_000).first().isEmpty())

        repo.saveRound(id, listOf(trial(1, 2, shaky = true, level = 4)), activeMs = 9_000)
        repo.finishSession(finish(id, endedAt = 20_000, metricsJson = """{"span":5.0}"""))

        assertEquals(1, repo.observeCompletedCount().first())
        val row = db.sessionDao().byId(id)!!
        assertEquals(SessionStatus.COMPLETED, row.status)
        assertEquals(20_000L, row.endedAt)
        assertEquals(9_000L, row.activeMs)
        assertEquals(0.9, row.score!!, 1e-9)
        assertEquals("""{"span":5.0}""", row.metricsJson)
        assertEquals(listOf(0, 0, 1), db.trialDao().forSession(id).map { it.round })
        assertEquals(listOf(3, 3, 4), db.trialDao().forSession(id).map { it.level })
        assertEquals(listOf(false, false, true), db.trialDao().forSession(id).map { it.isShaky })
        assertEquals(listOf(trial(0, 0), trial(0, 1), trial(1, 2, shaky = true, level = 4)), repo.lastCompletedTrials(GameId.CORSI))
    }

    @Test
    fun draftCanBeLoadedWithItsRounds() = runTest {
        val id = repo.startSession(newSession(GameId.NBACK, isCalibration = true))
        repo.saveRound(id, listOf(trial(0, 0), trial(0, 1, shaky = true)), activeMs = 4_000)

        val draft = repo.loadDraft(id)
        assertNotNull(draft)
        assertEquals(GameId.NBACK, draft!!.gameId)
        assertEquals(3, draft.level)
        assertTrue(draft.isCalibration)
        assertEquals(4_000L, draft.activeMs)
        assertEquals(listOf(trial(0, 0), trial(0, 1, shaky = true)), draft.trials)

        assertNull(repo.loadDraft(id + 100))
        repo.abandonSession(id, endedAt = 5_000, activeMs = 4_000)
        assertNull(repo.loadDraft(id))
        assertEquals(SessionStatus.ABANDONED, db.sessionDao().byId(id)!!.status)
        // Сыгранные раунды брошенной сессии остаются в истории, но «прошлой сессией» не считаются.
        assertEquals(2, db.trialDao().forSession(id).size)
        assertTrue(repo.lastCompletedTrials(GameId.NBACK).isEmpty())
    }

    @Test
    fun newSessionClosesStaleDrafts() = runTest {
        val stale = repo.startSession(newSession(startedAt = 1_000))
        val fresh = repo.startSession(newSession(startedAt = 9_000))
        val staleRow = db.sessionDao().byId(stale)!!
        assertEquals(SessionStatus.ABANDONED, staleRow.status)
        assertEquals(9_000L, staleRow.endedAt)
        assertEquals(SessionStatus.IN_PROGRESS, db.sessionDao().byId(fresh)!!.status)
    }

    @Test
    fun finishedSessionCannotBeWrittenAgain() = runTest {
        val id = repo.startSession(newSession())
        repo.finishSession(finish(id, endedAt = 2_000, score = 1.0, isShaky = true))
        assertTrue(db.sessionDao().byId(id)!!.isShaky)

        for (write in listOf<suspend () -> Unit>(
            { repo.saveRound(id, listOf(trial(0, 0)), activeMs = 600) },
            { repo.finishSession(finish(id, endedAt = 3_000, score = 0.1, level = 5)) },
            { repo.abandonSession(id, 3_000, 600) },
        )) {
            try {
                write()
                fail("Запись в завершённую сессию должна падать")
            } catch (expected: IllegalStateException) {
                // так и надо
            }
        }
        // Неудачные записи откатились целиком, вместе с уровнем.
        assertTrue(db.trialDao().forSession(id).isEmpty())
        assertEquals(1.0, db.sessionDao().byId(id)!!.score!!, 1e-9)
        assertEquals(3, db.difficultyDao().state(GameId.CORSI)!!.level)
    }

    @Test
    fun finishWritesLevelAndChangesInOneGo() = runTest {
        val id = repo.startSession(newSession(level = 2, isCalibration = true))
        val changes = listOf(LevelChange(2, 3, "calibration"), LevelChange(3, 4, "calibration"))
        repo.finishSession(finish(id, endedAt = 5_000, level = 4, calibrated = false, changes = changes))

        assertEquals(GameDifficulty(GameId.CORSI, 4, calibrated = false), difficulty.observeStates().first()[GameId.CORSI])
        val stored = db.difficultyDao().changesFor(GameId.CORSI)
        assertEquals(listOf(2 to 3, 3 to 4), stored.map { it.fromLevel to it.toLevel })
        assertEquals(listOf(5_000L, 5_000L), stored.map { it.at })
    }

    @Test
    fun adaptationWindowStartsAfterLastLevelChange() = runTest {
        suspend fun play(start: Long, score: Double, level: Int = 3, calibration: Boolean = false, shaky: Boolean = false, changes: List<LevelChange> = emptyList(), newLevel: Int = level) {
            val id = repo.startSession(newSession(startedAt = start, level = level, isCalibration = calibration))
            repo.finishSession(finish(id, endedAt = start + 100, score = score, isShaky = shaky, level = newLevel, calibrated = !calibration, changes = changes))
        }
        assertNull(difficulty.adaptationInput(GameId.CORSI, 3).state)

        play(1_000, 0.9, level = 2, calibration = true, changes = listOf(LevelChange(2, 3, "calibration")), newLevel = 3)
        play(2_000, 0.5, calibration = true)
        play(3_000, 0.8)
        play(4_000, 0.95, shaky = true)
        play(5_000, 0.7)
        val input = difficulty.adaptationInput(GameId.CORSI, 3)
        assertEquals(GameDifficulty(GameId.CORSI, 3, calibrated = true), input.state)
        // Калибровочные: две, обе без тряски. Окно: без калибровки и тряски, новые первыми.
        assertEquals(2, input.calibrationSessions)
        assertEquals(listOf(0.7, 0.8), input.window)

        // Смена уровня: окно на новом уровне начинается с нуля.
        play(6_000, 0.9, changes = listOf(LevelChange(3, 4, "window_up")), newLevel = 4)
        assertTrue(difficulty.adaptationInput(GameId.CORSI, 3).window.isEmpty())
        play(7_000, 0.6, level = 4)
        assertEquals(listOf(0.6), difficulty.adaptationInput(GameId.CORSI, 3).window)
    }

    @Test
    fun workoutGameIsLinkedAndLastGameCompletesWorkout() = runTest {
        val workoutId = workouts.create(date = 20_000, mode = WorkoutMode.QUICK, games = listOf(GameId.SCHULTE, GameId.CORSI))
        val first = repo.startSession(newSession(GameId.SCHULTE, workoutId = workoutId))
        repo.finishSession(finish(first, endedAt = 2_000, game = GameId.SCHULTE, activeMs = 70_000))

        var workout = workouts.observe(workoutId).first()!!
        assertEquals(WorkoutStatus.IN_PROGRESS, workout.status)
        assertEquals(first, workout.items[0].sessionId)
        assertEquals(GameId.CORSI, workout.next!!.gameId)

        val second = repo.startSession(newSession(GameId.CORSI, startedAt = 3_000, workoutId = workoutId))
        repo.finishSession(finish(second, endedAt = 4_000, activeMs = 80_000))
        workout = workouts.observe(workoutId).first()!!
        assertEquals(WorkoutStatus.COMPLETED, workout.status)
        assertNull(workout.next)
        assertEquals(150_000L, workout.activeMs)
        assertEquals(listOf(0.9, 0.9), workout.items.map { it.score })
    }

    @Test
    fun abandonedWorkoutSessionLeavesItemPending() = runTest {
        val workoutId = workouts.create(date = 20_000, mode = WorkoutMode.QUICK, games = listOf(GameId.SCHULTE, GameId.CORSI))
        val id = repo.startSession(newSession(GameId.SCHULTE, workoutId = workoutId))
        repo.abandonSession(id, 2_000, 500)
        val workout = workouts.observe(workoutId).first()!!
        assertEquals(GameId.SCHULTE, workout.next!!.gameId)
        assertFalse(workout.items[0].done)
    }
}
