package app.brainfit.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brainfit.data.db.BrainFitDatabase
import app.brainfit.data.repository.ProgressRepositoryImpl
import app.brainfit.data.repository.SessionRepositoryImpl
import app.brainfit.domain.model.DifficultyUpdate
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.LevelChange
import app.brainfit.domain.model.LevelChangeRecord
import app.brainfit.domain.model.NewSession
import app.brainfit.domain.model.SessionFinish
import app.brainfit.domain.model.SessionMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Прогресс читается из настоящих сессий: только завершённые, метрики разобраны, смены уровня по порядку. */
@RunWith(AndroidJUnit4::class)
class ProgressRepositoryTest {

    private lateinit var db: BrainFitDatabase
    private lateinit var sessions: SessionRepositoryImpl
    private lateinit var progress: ProgressRepositoryImpl

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrainFitDatabase::class.java).build()
        sessions = SessionRepositoryImpl(db, db.sessionDao(), db.trialDao(), db.difficultyDao(), db.workoutDao())
        progress = ProgressRepositoryImpl(db.sessionDao(), db.difficultyDao())
    }

    @After
    fun close() = db.close()

    private suspend fun play(
        game: GameId,
        startedAt: Long,
        metricsJson: String,
        shaky: Boolean = false,
        changes: List<LevelChange> = emptyList(),
        level: Int = 2,
    ): Long {
        val id = sessions.startSession(NewSession(game, SessionMode.FREE, level, false, 1, startedAt))
        val newLevel = changes.lastOrNull()?.toLevel ?: level
        sessions.finishSession(
            SessionFinish(id, startedAt + 100, 50, 0.8, metricsJson, shaky, DifficultyUpdate(game, newLevel, true, changes)),
        )
        return id
    }

    @Test
    fun completedSessionsComeWithParsedMetrics() = runTest {
        val late = play(GameId.SCHULTE, startedAt = 5_000, metricsJson = """{"msPerCell":812.5,"accuracy":1.0,"cells":9}""", shaky = true)
        val early = play(GameId.SCHULTE, startedAt = 1_000, metricsJson = """{"msPerCell":900.0,"accuracy":0.9,"cells":9}""")
        play(GameId.CORSI, startedAt = 3_000, metricsJson = """{"span":5.0}""")
        // Брошенная сессия в прогресс не попадает.
        sessions.abandonSession(sessions.startSession(NewSession(GameId.SCHULTE, SessionMode.FREE, 2, false, 1, 7_000)), 8_000, 10)

        val schulte = progress.observeCompleted(GameId.SCHULTE).first()
        assertEquals(listOf(early, late), schulte.map { it.id })
        assertEquals(mapOf("msPerCell" to 812.5, "accuracy" to 1.0, "cells" to 9.0), schulte[1].metrics)
        assertTrue(schulte[1].isShaky)
        assertEquals(schulte, progress.completed(GameId.SCHULTE))
        assertEquals(listOf(GameId.SCHULTE, GameId.CORSI, GameId.SCHULTE), progress.observeCompleted().first().map { it.gameId })
    }

    @Test
    fun levelChangesComeInOrder() = runTest {
        play(GameId.CORSI, 1_000, "{}", changes = listOf(LevelChange(2, 3, "calibration"), LevelChange(3, 2, "calibration")))
        play(GameId.CORSI, 2_000, "{}", changes = listOf(LevelChange(2, 3, "window_up")))
        play(GameId.SCHULTE, 3_000, "{}", changes = listOf(LevelChange(2, 1, "window_down")))
        assertEquals(
            listOf(
                LevelChangeRecord(GameId.CORSI, 2, 3, "calibration", 1_100),
                LevelChangeRecord(GameId.CORSI, 3, 2, "calibration", 1_100),
                LevelChangeRecord(GameId.CORSI, 2, 3, "window_up", 2_100),
            ),
            progress.observeLevelChanges(GameId.CORSI).first(),
        )
    }

    @Test
    fun brokenMetricsAreAnErrorNotEmptyData() = runTest {
        val id = play(GameId.MATH, 1_000, metricsJson = "{oops")
        try {
            progress.completed(GameId.MATH)
            fail("Битые метрики должны давать ошибку")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Сессия $id"))
        }
    }
}
