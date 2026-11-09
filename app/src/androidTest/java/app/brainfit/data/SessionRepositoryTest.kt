package app.brainfit.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brainfit.data.db.BrainFitDatabase
import app.brainfit.data.repository.SessionRepositoryImpl
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.NewSession
import app.brainfit.domain.model.SessionMode
import app.brainfit.domain.model.SessionStatus
import app.brainfit.domain.model.TrialRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Запись сессии на настоящей SQLite: черновик, раунды, итог, выход, продолжение. */
@RunWith(AndroidJUnit4::class)
class SessionRepositoryTest {

    private lateinit var db: BrainFitDatabase
    private lateinit var repo: SessionRepositoryImpl

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrainFitDatabase::class.java).build()
        repo = SessionRepositoryImpl(db, db.sessionDao(), db.trialDao())
    }

    @After
    fun close() = db.close()

    private fun newSession(game: GameId = GameId.CORSI, startedAt: Long = 1_000) = NewSession(
        gameId = game,
        mode = SessionMode.FREE,
        level = 3,
        isCalibration = false,
        scoringVersion = 1,
        startedAt = startedAt,
    )

    private fun trial(round: Int, index: Int, shaky: Boolean = false) = TrialRecord(
        round = round,
        index = index,
        paramsJson = """{"n":$index}""",
        answerJson = "{}",
        correct = true,
        rtMs = 300L + index,
        errorType = null,
        isShaky = shaky,
    )

    @Test
    fun draftIsInvisibleUntilFinished() = runTest {
        val id = repo.startSession(newSession())
        repo.saveRound(id, listOf(trial(0, 0), trial(0, 1)), activeMs = 4_000)
        assertEquals(0, repo.observeCompletedCount().first())
        assertTrue(repo.observeCompletedBetween(0, 10_000).first().isEmpty())

        repo.saveRound(id, listOf(trial(1, 2, shaky = true)), activeMs = 9_000)
        repo.finishSession(id, endedAt = 20_000, activeMs = 9_000, score = 0.9, metricsJson = """{"span":5.0}""", isShaky = false)

        assertEquals(1, repo.observeCompletedCount().first())
        val row = db.sessionDao().byId(id)!!
        assertEquals(SessionStatus.COMPLETED, row.status)
        assertEquals(20_000L, row.endedAt)
        assertEquals(9_000L, row.activeMs)
        assertEquals(0.9, row.score!!, 1e-9)
        assertEquals("""{"span":5.0}""", row.metricsJson)
        assertEquals(listOf(0, 0, 1), db.trialDao().forSession(id).map { it.round })
        assertEquals(listOf(false, false, true), db.trialDao().forSession(id).map { it.isShaky })
    }

    @Test
    fun draftCanBeLoadedWithItsRounds() = runTest {
        val id = repo.startSession(newSession(GameId.NBACK))
        repo.saveRound(id, listOf(trial(0, 0), trial(0, 1, shaky = true)), activeMs = 4_000)

        val draft = repo.loadDraft(id)
        assertNotNull(draft)
        assertEquals(GameId.NBACK, draft!!.gameId)
        assertEquals(3, draft.level)
        assertEquals(4_000L, draft.activeMs)
        assertEquals(listOf(trial(0, 0), trial(0, 1, shaky = true)), draft.trials)

        assertNull(repo.loadDraft(id + 100))
        repo.abandonSession(id, endedAt = 5_000, activeMs = 4_000)
        assertNull(repo.loadDraft(id))
        assertEquals(SessionStatus.ABANDONED, db.sessionDao().byId(id)!!.status)
        // Сыгранные раунды брошенной сессии остаются в истории.
        assertEquals(2, db.trialDao().forSession(id).size)
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
        repo.finishSession(id, endedAt = 2_000, activeMs = 500, score = 1.0, metricsJson = "{}", isShaky = true)
        assertTrue(db.sessionDao().byId(id)!!.isShaky)

        for (write in listOf<suspend () -> Unit>(
            { repo.saveRound(id, listOf(trial(0, 0)), activeMs = 600) },
            { repo.finishSession(id, 3_000, 600, 0.1, "{}", false) },
            { repo.abandonSession(id, 3_000, 600) },
        )) {
            try {
                write()
                fail("Запись в завершённую сессию должна падать")
            } catch (expected: IllegalStateException) {
                // так и надо
            }
        }
        // Неудачная запись раунда откатилась целиком.
        assertTrue(db.trialDao().forSession(id).isEmpty())
        assertEquals(1.0, db.sessionDao().byId(id)!!.score!!, 1e-9)
    }
}
