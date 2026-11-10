package app.brainfit.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brainfit.data.db.BrainFitDatabase
import app.brainfit.data.repository.SessionRepositoryImpl
import app.brainfit.data.repository.WorkoutRepositoryImpl
import app.brainfit.domain.model.DifficultyUpdate
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.NewSession
import app.brainfit.domain.model.SessionFinish
import app.brainfit.domain.model.SessionMode
import app.brainfit.domain.model.WorkoutMode
import app.brainfit.domain.model.WorkoutStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Тренировки на настоящей SQLite: создание, замена, пропуск, конец суток, данные для подбора игр. */
@RunWith(AndroidJUnit4::class)
class WorkoutRepositoryTest {

    private lateinit var db: BrainFitDatabase
    private lateinit var workouts: WorkoutRepositoryImpl
    private lateinit var sessions: SessionRepositoryImpl

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrainFitDatabase::class.java).build()
        workouts = WorkoutRepositoryImpl(db, db.workoutDao(), db.sessionDao())
        sessions = SessionRepositoryImpl(db, db.sessionDao(), db.trialDao(), db.difficultyDao(), db.workoutDao())
    }

    @After
    fun close() = db.close()

    private suspend fun play(game: GameId, startedAt: Long, workoutId: Long? = null) {
        val id = sessions.startSession(
            NewSession(game, if (workoutId == null) SessionMode.FREE else SessionMode.STANDARD, 2, false, 1, startedAt, workoutId),
        )
        sessions.finishSession(SessionFinish(id, startedAt + 60_000, 50_000, 0.8, "{}", false, DifficultyUpdate(game, 2, true, emptyList())))
    }

    @Test
    fun newWorkoutReplacesUnfinishedOne() = runTest {
        val old = workouts.create(date = 100, mode = WorkoutMode.QUICK, games = listOf(GameId.SCHULTE, GameId.STROOP))
        assertEquals(old, workouts.observeActive(100).first()!!.id)
        val new = workouts.create(date = 100, mode = WorkoutMode.STANDARD, games = listOf(GameId.NBACK, GameId.SCHULTE, GameId.MATH, GameId.CORSI))

        assertEquals(WorkoutStatus.ABANDONED, workouts.observe(old).first()!!.status)
        val active = workouts.observeActive(100).first()!!
        assertEquals(new, active.id)
        assertEquals(WorkoutMode.STANDARD, active.mode)
        assertEquals(listOf(GameId.NBACK, GameId.SCHULTE, GameId.MATH, GameId.CORSI), active.items.map { it.gameId })
        assertEquals(listOf(0, 1, 2, 3), active.items.map { it.order })
        // На другой день этой тренировки нет.
        assertNull(workouts.observeActive(101).first())
    }

    @Test
    fun skippingEveryGameCompletesWorkout() = runTest {
        val id = workouts.create(date = 100, mode = WorkoutMode.QUICK, games = listOf(GameId.SCHULTE, GameId.STROOP))
        workouts.skip(id, 0)
        var workout = workouts.observe(id).first()!!
        assertTrue(workout.items[0].skipped)
        assertEquals(GameId.STROOP, workout.next!!.gameId)
        try {
            workouts.skip(id, 0)
            fail("Пропущенную игру второй раз не пропустить")
        } catch (expected: IllegalStateException) {
            // так и надо
        }
        workouts.skip(id, 1)
        workout = workouts.observe(id).first()!!
        assertEquals(WorkoutStatus.COMPLETED, workout.status)
        assertNull(workouts.observeActive(100).first())
    }

    @Test
    fun staleWorkoutsAreAbandonedNextDay() = runTest {
        val yesterday = workouts.create(date = 99, mode = WorkoutMode.QUICK, games = listOf(GameId.SCHULTE, GameId.STROOP))
        workouts.abandonStale(today = 99)
        assertEquals(WorkoutStatus.IN_PROGRESS, workouts.observe(yesterday).first()!!.status)
        workouts.abandonStale(today = 100)
        assertEquals(WorkoutStatus.ABANDONED, workouts.observe(yesterday).first()!!.status)
    }

    @Test
    fun planningInputComesFromRealHistory() = runTest {
        assertEquals(emptyMap<GameId, Long>(), workouts.planningInput(0, 1_000_000).lastPlayedAt)

        play(GameId.SCHULTE, startedAt = 1_000)
        play(GameId.SCHULTE, startedAt = 5_000)
        play(GameId.MATH, startedAt = 3_000)
        val workout = workouts.create(date = 0, mode = WorkoutMode.QUICK, games = listOf(GameId.CORSI, GameId.STROOP))
        play(GameId.CORSI, startedAt = 200_000, workoutId = workout)
        // Брошенная сессия игру «сыгранной» не делает.
        sessions.abandonSession(sessions.startSession(NewSession(GameId.NBACK, SessionMode.FREE, 2, false, 1, 300_000)), 301_000, 0)

        val input = workouts.planningInput(dayStartMillis = 100_000, dayEndMillis = 400_000)
        assertEquals(mapOf(GameId.SCHULTE to 5_000L, GameId.MATH to 3_000L, GameId.CORSI to 200_000L), input.lastPlayedAt)
        assertEquals(setOf(GameId.CORSI), input.playedTodayInWorkouts)
        // В прошлой тренировке сыграна только Корси: Струп не сыгран.
        assertEquals(setOf(GameId.CORSI), input.previousWorkout)
        // Другой день: сегодняшних нет.
        assertTrue(workouts.planningInput(400_000, 500_000).playedTodayInWorkouts.isEmpty())
    }
}
