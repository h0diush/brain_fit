package app.brainfit.games.engine

import app.brainfit.games.engine.GameSession.Phase
import app.brainfit.testing.TapGame
import app.brainfit.testing.TapGame.Companion.NANOS_PER_SECOND
import app.brainfit.testing.TapInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSessionTest {

    private val ms = 1_000_000L

    private fun session(game: TapGame = TapGame(), seed: Long = 42, completed: List<CompletedRound> = emptyList()) =
        GameSession(game, level = 2, seed = seed, completed = completed)

    @Test
    fun playsAllRoundsToTheEnd() {
        val s = session(TapGame(rounds = 2, target = 2))
        assertEquals(Phase.READY, s.phase)
        assertEquals(2, s.totalRounds)

        s.startRound(0)
        assertEquals(Phase.RUNNING, s.phase)
        assertNull(s.input(TapInput.HIT, 100 * ms))
        val first = s.input(TapInput.MISS, 250 * ms)
        assertNotNull(first)
        assertEquals(listOf(true, false), first!!.trials.map { it.correct })
        assertEquals(listOf(100L, 150L), first.trials.map { it.rtMs })
        assertEquals(250 * ms, first.activeNanos)
        assertEquals(Phase.READY, s.phase)
        assertEquals(1, s.roundIndex)
        assertNull(s.state)

        s.startRound(1_000 * ms)
        s.input(TapInput.HIT, 1_100 * ms)
        assertNotNull(s.input(TapInput.HIT, 1_200 * ms))
        assertEquals(Phase.FINISHED, s.phase)
        assertEquals(0.75, s.score().score, 1e-9)
        assertEquals(4, s.history().size)
    }

    @Test
    fun timeStartsFromZeroInEveryRound() {
        val s = session(TapGame(rounds = 2, target = 1))
        s.startRound(5_000 * ms)
        val first = s.input(TapInput.HIT, 5_300 * ms)!!
        assertEquals(300L, first.trials.single().rtMs)
        s.startRound(90_000 * ms)
        val second = s.input(TapInput.HIT, 90_050 * ms)!!
        assertEquals(50L, second.trials.single().rtMs)
    }

    @Test
    fun pauseDoesNotCountAsGameTime() {
        val s = session(TapGame(rounds = 1, target = 1))
        s.startRound(0)
        s.pause(200 * ms)
        assertEquals(Phase.PAUSED, s.phase)
        // На паузе ввод и кадры игнорируются.
        assertNull(s.input(TapInput.HIT, 300 * ms))
        assertNull(s.tick(400 * ms))
        s.resume(60_000 * ms)
        val round = s.input(TapInput.HIT, 60_100 * ms)!!
        assertEquals(300L, round.trials.single().rtMs)
        assertEquals(300 * ms, round.activeNanos)
    }

    @Test
    fun roundEndsByTimeOnTick() {
        val s = session(TapGame(rounds = 1, target = 5, limitNanos = 2 * NANOS_PER_SECOND))
        s.startRound(0)
        s.input(TapInput.HIT, 500 * ms)
        assertNull(s.tick(1_999 * ms))
        val round = s.tick(2_000 * ms)!!
        assertEquals(Phase.FINISHED, s.phase)
        assertEquals("timeout", round.trials.last().errorType)
        assertNull(round.trials.last().rtMs)
    }

    @Test
    fun pausedTimeDoesNotEatTheTimeLimit() {
        val s = session(TapGame(rounds = 1, target = 5, limitNanos = 2 * NANOS_PER_SECOND))
        s.startRound(0)
        s.pause(1_000 * ms)
        s.resume(500_000 * ms)
        assertNull(s.tick(500_900 * ms))
        assertNotNull(s.tick(501_000 * ms))
    }

    @Test
    fun tickWithoutChangesKeepsTheSameState() {
        val s = session(TapGame(rounds = 1, target = 5))
        s.startRound(0)
        s.tick(10 * ms)
        val before = s.state
        s.tick(20 * ms)
        assertSame(before, s.state)
        s.tick(1_500 * ms)
        assertEquals(58L, s.state!!.secondsLeft)
    }

    /** Кадр несёт время своего начала, а нажатие — реальное «сейчас»: отметки приходят не по порядку. */
    @Test
    fun timeNeverGoesBackWhenFrameIsOlderThanInput() {
        val s = session(TapGame(rounds = 1, target = 2))
        s.startRound(0)
        s.input(TapInput.HIT, 100 * ms)
        s.tick(90 * ms)
        val round = s.input(TapInput.HIT, 95 * ms)!!
        assertEquals(listOf(100L, 0L), round.trials.map { it.rtMs })
        assertEquals(100 * ms, round.activeNanos)
    }

    @Test
    fun sameSeedGivesSameTasks() {
        fun tokens(seed: Long): List<Int> {
            val s = session(TapGame(rounds = 3, target = 1), seed)
            return List(3) {
                s.startRound(0)
                val token = s.state!!.params.token
                s.input(TapInput.HIT, 1)
                token
            }
        }
        assertEquals(tokens(7), tokens(7))
        assertNotEquals(tokens(7), tokens(8))
        assertEquals(3, tokens(7).toSet().size)
    }

    @Test
    fun restartGivesNewTaskAndDropsTrials() {
        val s = session(TapGame(rounds = 1, target = 2))
        s.startRound(0)
        val firstToken = s.state!!.params.token
        s.input(TapInput.MISS, 100 * ms)
        s.restartRound()
        assertEquals(Phase.READY, s.phase)
        assertEquals(0, s.roundIndex)

        s.startRound(1_000 * ms)
        assertNotEquals(firstToken, s.state!!.params.token)
        s.input(TapInput.HIT, 1_100 * ms)
        val round = s.input(TapInput.HIT, 1_200 * ms)!!
        assertEquals(2, round.trials.size)
        assertTrue(round.trials.all { it.correct })
        assertEquals(1.0, s.score().score, 1e-9)
    }

    @Test
    fun restartWorksFromPause() {
        val s = session(TapGame(rounds = 1, target = 1))
        s.startRound(0)
        s.pause(10 * ms)
        s.restartRound()
        assertEquals(Phase.READY, s.phase)
        s.startRound(20 * ms)
        assertEquals(30L, s.input(TapInput.HIT, 50 * ms)!!.trials.single().rtMs)
    }

    @Test
    fun historyOfEarlierRoundsIsPassedToGenerator() {
        val game = TapGame(rounds = 3, target = 2)
        val s = session(game)
        repeat(3) {
            s.startRound(0)
            s.input(TapInput.HIT, 1)
            s.input(TapInput.HIT, 2)
        }
        assertEquals(listOf(0, 2, 4), game.historySizes)
    }

    @Test
    fun continuesFromCompletedRounds() {
        val done = CompletedRound(listOf(TrialResult("{}", "{}", correct = false, rtMs = 10)), activeNanos = 0)
        val game = TapGame(rounds = 2, target = 1)
        val s = session(game, completed = listOf(done))
        assertEquals(Phase.READY, s.phase)
        assertEquals(1, s.roundIndex)
        s.startRound(0)
        assertEquals(listOf(1), game.historySizes)
        s.input(TapInput.HIT, 5)
        assertEquals(Phase.FINISHED, s.phase)
        assertEquals(0.5, s.score().score, 1e-9)
    }

    @Test
    fun sessionWithAllRoundsRestoredIsFinished() {
        val done = CompletedRound(listOf(TrialResult("{}", "{}", correct = true, rtMs = 10)), activeNanos = 0)
        val s = session(TapGame(rounds = 2, target = 1), completed = listOf(done, done))
        assertEquals(Phase.FINISHED, s.phase)
        assertEquals(1.0, s.score().score, 1e-9)
    }

    @Test(expected = IllegalStateException::class)
    fun cannotStartRoundTwice() {
        val s = session()
        s.startRound(0)
        s.startRound(1)
    }

    @Test(expected = IllegalStateException::class)
    fun scoreNeedsFinishedSession() {
        session().score()
    }

    @Test(expected = IllegalArgumentException::class)
    fun levelOutsideTableIsRejected() {
        GameSession(TapGame(), level = 4, seed = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun scoreOutsideRangeIsRejected() {
        SessionScore(1.01)
    }
}
