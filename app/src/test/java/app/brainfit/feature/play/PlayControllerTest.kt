package app.brainfit.feature.play

import app.brainfit.domain.adaptive.Adaptation
import app.brainfit.domain.model.AppSettings
import app.brainfit.domain.model.GameDifficulty
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.LevelChange
import app.brainfit.domain.model.SessionMode
import app.brainfit.domain.model.SessionStatus
import app.brainfit.games.engine.ResumePolicy
import app.brainfit.testing.FakeClock
import app.brainfit.testing.FakeSessionRepository
import app.brainfit.testing.FakeSettingsRepository
import app.brainfit.testing.FakeShakeMonitor
import app.brainfit.testing.TapGame
import app.brainfit.testing.TapInput
import app.brainfit.testing.TapParams
import app.brainfit.testing.TapState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayControllerTest {

    /** Шульте (игра TapGame по умолчанию) уже откалибрована на уровне 2: калибровку проверяют отдельные тесты. */
    private var repo = FakeSessionRepository(mapOf(GameId.SCHULTE to 2))
    private val clock = FakeClock()
    private val shake = FakeShakeMonitor()
    private var settings = FakeSettingsRepository()

    /** То, что лежало бы в SavedStateHandle. */
    private var savedDraftId: Long? = null

    private fun controller(
        game: TapGame = TapGame(rounds = 2, target = 2),
        seed: Long = 1,
        mode: SessionMode = SessionMode.FREE,
        workoutId: Long? = null,
    ): PlayController<TapParams, TapState, TapInput> = PlayController(
        game = game,
        mode = mode,
        // Unconfined: запущенные корутины выполняются сразу, тест идёт как обычный код.
        scope = TestScope(UnconfinedTestDispatcher()),
        sessions = repo,
        difficulty = repo,
        settings = settings,
        shake = shake,
        clock = clock,
        wallClock = clock,
        seed = seed,
        workoutId = workoutId,
        draftId = savedDraftId,
        onDraftId = { savedDraftId = it },
    )

    /** Целая сессия из одинаковых раундов: каждый раунд — эти нажатия. */
    private fun playSession(game: TapGame, vararg inputs: TapInput): PlayUiState<TapState> {
        val c = controller(game)
        repeat(game.roundsPerSession(1)) { c.playRound(*inputs) }
        return c.state.value
    }

    private fun PlayController<TapParams, TapState, TapInput>.playRound(vararg inputs: TapInput) {
        startRound()
        inputs.forEach {
            clock.advanceMs(100)
            input(it)
        }
    }

    @Test
    fun openingTheScreenWritesNothing() {
        val c = controller()
        assertEquals(PlayPhase.READY, c.state.value.phase)
        assertEquals(0, c.state.value.round)
        assertEquals(2, c.state.value.totalRounds)
        assertTrue(repo.rows.isEmpty())
        assertNull(savedDraftId)
        c.exit()
        assertTrue(c.state.value.closed)
        assertTrue(repo.rows.isEmpty())
    }

    @Test
    fun levelComesFromDifficultyOrStartLevel() {
        repo = FakeSessionRepository()
        assertEquals(2, controller().state.value.level)
        repo = FakeSessionRepository(mapOf(GameId.SCHULTE to 3))
        assertEquals(3, controller().state.value.level)
        // Уровень из базы вне таблицы игры зажимается в её границы.
        repo = FakeSessionRepository(mapOf(GameId.SCHULTE to 9))
        assertEquals(3, controller().state.value.level)
    }

    @Test
    fun firstSessionsCalibrateLevelAfterEveryRound() {
        repo = FakeSessionRepository()
        val game = TapGame(rounds = 3, target = 2)
        val c = controller(game)
        assertTrue(c.state.value.calibration)
        assertEquals(2, c.state.value.level)

        c.playRound(TapInput.HIT, TapInput.HIT)
        assertEquals(3, c.state.value.level)
        assertEquals("Калибровка: уровень повышен до 3.", c.state.value.levelNote)
        c.playRound(TapInput.MISS, TapInput.MISS)
        assertEquals(2, c.state.value.level)
        assertEquals("Калибровка: уровень понижен до 2.", c.state.value.levelNote)
        c.playRound(TapInput.HIT, TapInput.MISS)

        val ui = c.state.value
        val row = repo.rows.single()
        assertTrue(row.session.isCalibration)
        // В сессии записан стартовый уровень, у проб — уровень их раунда.
        assertEquals(2, row.session.level)
        assertEquals(listOf(2, 2, 3, 3, 2, 2), row.trials.map { it.level })
        // 0,5 < 0,65: после последнего раунда уровень опустился до 1.
        assertEquals(GameDifficulty(GameId.SCHULTE, 1, calibrated = false), repo.difficulty[GameId.SCHULTE])
        assertEquals(
            listOf(2 to 3, 3 to 2, 2 to 1).map { (from, to) -> LevelChange(from, to, Adaptation.REASON_CALIBRATION) },
            repo.changes.map { it.first },
        )
        // Каждый раунд оценён на своём уровне: (1 + 0 + 0,5) / 3.
        assertEquals(0.5, ui.result!!.score, 1e-9)
        assertEquals(listOf("Уровень понижен до 1.", "Калибровка: сыграно 1 из 2."), ui.levelLines)

        // Вторая калибровочная сессия заканчивает калибровку.
        val second = playSession(game, TapInput.HIT, TapInput.HIT)
        assertEquals(listOf("Уровень повышен до 3.", "Калибровка закончена. Дальше уровень меняется по итогам нескольких сессий подряд."), second.levelLines)
        assertEquals(GameDifficulty(GameId.SCHULTE, 3, calibrated = true), repo.difficulty[GameId.SCHULTE])

        // Дальше — обычная сессия.
        val third = controller(game)
        assertFalse(third.state.value.calibration)
        assertEquals(3, third.state.value.level)
    }

    @Test
    fun shakyRoundDoesNotMoveCalibrationAndShakySessionDoesNotCount() {
        repo = FakeSessionRepository()
        val c = controller(TapGame(rounds = 1, target = 1))
        shake.rms = 3f
        c.playRound(TapInput.HIT)
        assertEquals(2, repo.difficulty.getValue(GameId.SCHULTE).level)
        assertTrue(repo.changes.isEmpty())
        assertEquals(
            listOf("Уровень 2: калибровка его не изменила.", "Из-за тряски сессия не засчитана в калибровку."),
            c.state.value.levelLines,
        )
        assertEquals(0, kotlinx.coroutines.runBlocking { repo.adaptationInput(GameId.SCHULTE, 3) }.calibrationSessions)
    }

    @Test
    fun calibrationLevelSurvivesProcessDeath() {
        repo = FakeSessionRepository()
        val first = controller(TapGame(rounds = 3, target = 1))
        first.playRound(TapInput.HIT)
        first.startRound()

        val second = controller(TapGame(rounds = 3, target = 1), seed = 5)
        val ui = second.state.value
        assertTrue(ui.restored)
        assertTrue(ui.calibration)
        assertEquals(3, ui.level)
        second.playRound(TapInput.HIT)
        assertEquals(listOf(2, 3), repo.rows.single().trials.map { it.level })
    }

    @Test
    fun threeGoodSessionsRaiseLevelAndResetWindow() {
        val game = TapGame(rounds = 1, target = 1)
        assertTrue(playSession(game, TapInput.HIT).levelLines.isEmpty())
        assertTrue(playSession(game, TapInput.HIT).levelLines.isEmpty())
        assertEquals(listOf("Уровень повышен до 3."), playSession(game, TapInput.HIT).levelLines)
        assertEquals(GameDifficulty(GameId.SCHULTE, 3, calibrated = true), repo.difficulty[GameId.SCHULTE])
        assertEquals(listOf(LevelChange(2, 3, Adaptation.REASON_WINDOW_UP)), repo.changes.map { it.first })
        // На новом уровне окно пустое: одна сессия ничего не решает.
        assertTrue(playSession(game, TapInput.MISS).levelLines.isEmpty())
        assertEquals(3, repo.difficulty.getValue(GameId.SCHULTE).level)
        assertFalse(repo.rows.any { it.session.isCalibration })
    }

    @Test
    fun weakWindowLowersLevel() {
        val game = TapGame(rounds = 1, target = 1)
        repeat(2) { playSession(game, TapInput.MISS) }
        assertEquals(listOf("Уровень понижен до 1."), playSession(game, TapInput.MISS).levelLines)
        assertEquals(1, repo.difficulty.getValue(GameId.SCHULTE).level)
    }

    @Test
    fun shakySessionIsLeftOutOfTheWindow() {
        val game = TapGame(rounds = 1, target = 1)
        repeat(2) { playSession(game, TapInput.HIT) }
        shake.rms = 3f
        assertTrue(playSession(game, TapInput.HIT).levelLines.isEmpty())
        shake.rms = 0.1f
        assertEquals(listOf("Уровень повышен до 3."), playSession(game, TapInput.HIT).levelLines)
    }

    @Test
    fun sessionFromWorkoutKeepsWorkoutAndMode() {
        controller(mode = SessionMode.QUICK, workoutId = 5).startRound()
        val row = repo.rows.single()
        assertEquals(5L, row.session.workoutId)
        assertEquals(SessionMode.QUICK, row.session.mode)
    }

    @Test
    fun fullSessionIsSavedRoundByRound() {
        val c = controller()
        c.startRound()
        assertEquals(PlayPhase.RUNNING, c.state.value.phase)
        assertNotNull(c.state.value.gameState)
        val row = repo.rows.single()
        assertEquals(SessionStatus.IN_PROGRESS, row.status)
        assertEquals(1L, savedDraftId)
        assertEquals(SessionMode.FREE, row.session.mode)
        assertEquals(2, row.session.level)

        clock.advanceMs(100)
        c.input(TapInput.HIT)
        assertTrue(row.trials.isEmpty())
        clock.advanceMs(100)
        c.input(TapInput.MISS)

        // Раунд завершён: пробы уже в базе, сессия ещё черновик.
        assertEquals(PlayPhase.READY, c.state.value.phase)
        assertEquals(1, c.state.value.round)
        assertNull(c.state.value.gameState)
        assertEquals(listOf(0 to 0, 0 to 1), row.trials.map { it.round to it.index })
        assertEquals(200L, row.activeMs)
        assertEquals(SessionStatus.IN_PROGRESS, row.status)

        clock.advanceMs(5_000)
        c.playRound(TapInput.HIT, TapInput.HIT)

        val ui = c.state.value
        assertEquals(PlayPhase.FINISHED, ui.phase)
        assertEquals(0.75, ui.result!!.score, 1e-9)
        assertEquals(SessionStatus.COMPLETED, row.status)
        assertEquals(0.75, row.score!!, 1e-9)
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 2, 1 to 3), row.trials.map { it.round to it.index })
        assertEquals(listOf(100L, 100L, 100L, 100L), row.trials.map { it.rtMs })
        assertEquals(400L, row.activeMs)
        assertEquals("""{"accuracy":0.75,"trials":4.0}""", row.metricsJson)
        assertNotNull(row.endedAt)
        assertFalse(row.isShaky)
        assertNull(savedDraftId)

        c.exit()
        assertTrue(c.state.value.closed)
        assertEquals(SessionStatus.COMPLETED, row.status)
    }

    @Test
    fun inputIsIgnoredOutsideRunningRound() {
        val c = controller()
        c.input(TapInput.HIT)
        c.frame(1)
        c.pause()
        c.resume()
        c.restartRound()
        assertEquals(PlayPhase.READY, c.state.value.phase)
        assertTrue(repo.rows.isEmpty())
        assertTrue(shake.calls.isEmpty())
    }

    @Test
    fun pauseStopsTimeAndSensor() {
        val c = controller(TapGame(rounds = 1, target = 1))
        c.startRound()
        clock.advanceMs(200)
        c.pause()
        assertEquals(PlayPhase.PAUSED, c.state.value.phase)
        clock.advanceMs(60_000)
        c.input(TapInput.HIT)
        assertTrue(repo.rows.single().trials.isEmpty())
        c.resume()
        assertEquals(PlayPhase.RUNNING, c.state.value.phase)
        clock.advanceMs(100)
        c.input(TapInput.HIT)
        assertEquals(300L, repo.rows.single().trials.single().rtMs)
        assertEquals(300L, repo.rows.single().activeMs)
        assertEquals(listOf("start", "pause", "resume", "stop"), shake.calls)
    }

    @Test
    fun restartFromPauseGivesNewTaskAndSavesOnlyTheReplay() {
        val c = controller(TapGame(rounds = 1, target = 2))
        c.startRound()
        val firstToken = c.state.value.gameState!!.params.token
        clock.advanceMs(100)
        c.input(TapInput.MISS)
        c.pause()
        c.restartRound()
        assertEquals(PlayPhase.RUNNING, c.state.value.phase)
        assertNotEquals(firstToken, c.state.value.gameState!!.params.token)
        clock.advanceMs(100)
        c.input(TapInput.HIT)
        clock.advanceMs(100)
        c.input(TapInput.HIT)
        val row = repo.rows.single()
        assertEquals(listOf(true, true), row.trials.map { it.correct })
        assertEquals(1.0, row.score!!, 1e-9)
        // Брошенная попытка не входит в активное время.
        assertEquals(200L, row.activeMs)
    }

    @Test
    fun gameWithRestartPolicyRestartsRoundAfterPause() {
        val c = controller(TapGame(rounds = 1, target = 2, resumePolicy = ResumePolicy.RESTART_ROUND))
        assertTrue(c.state.value.restartOnResume)
        c.startRound()
        val firstToken = c.state.value.gameState!!.params.token
        clock.advanceMs(100)
        c.input(TapInput.MISS)
        c.pause()
        c.resume()
        assertEquals(PlayPhase.RUNNING, c.state.value.phase)
        assertNotEquals(firstToken, c.state.value.gameState!!.params.token)
        assertTrue(c.state.value.gameState!!.trials.isEmpty())
    }

    @Test
    fun framesDriveTimeAndCanEndTheRound() {
        val c = controller(TapGame(rounds = 1, target = 5, limitNanos = 2 * TapGame.NANOS_PER_SECOND))
        c.startRound()
        c.frame(1_500_000_000)
        assertEquals(0L, c.state.value.gameState!!.secondsLeft)
        assertEquals(PlayPhase.RUNNING, c.state.value.phase)
        c.frame(2_000_000_000)
        assertEquals(PlayPhase.FINISHED, c.state.value.phase)
        assertEquals("timeout", repo.rows.single().trials.single().errorType)
        assertEquals(0.0, repo.rows.single().score!!, 1e-9)
    }

    @Test
    fun shakyRoundsAreMarkedAndMajorityMarksSession() {
        val c = controller(TapGame(rounds = 3, target = 1))
        shake.rms = 3f
        c.playRound(TapInput.HIT)
        shake.rms = 0.2f
        c.playRound(TapInput.HIT)
        shake.rms = 3f
        c.playRound(TapInput.HIT)
        val row = repo.rows.single()
        assertEquals(listOf(true, false, true), row.trials.map { it.isShaky })
        assertTrue(row.isShaky)
        assertTrue(c.state.value.shakySession)
    }

    @Test
    fun halfShakyRoundsDoNotMarkSession() {
        val c = controller(TapGame(rounds = 2, target = 1))
        shake.rms = 3f
        c.playRound(TapInput.HIT)
        shake.rms = null
        c.playRound(TapInput.HIT)
        assertEquals(listOf(true, false), repo.rows.single().trials.map { it.isShaky })
        assertFalse(repo.rows.single().isShaky)
    }

    @Test
    fun disabledDetectorNeverMarksAndDoesNotStartSensor() {
        settings = FakeSettingsRepository(AppSettings(shakeDetectorEnabled = false))
        shake.rms = 9f
        val c = controller(TapGame(rounds = 1, target = 1))
        c.startRound()
        c.pause()
        c.resume()
        c.input(TapInput.HIT)
        assertFalse(repo.rows.single().trials.single().isShaky)
        assertFalse(repo.rows.single().isShaky)
        assertFalse("start" in shake.calls)
        assertFalse("resume" in shake.calls)
    }

    @Test
    fun exitInTheMiddleAbandonsSessionButKeepsFinishedRounds() {
        val c = controller()
        c.playRound(TapInput.HIT, TapInput.HIT)
        c.startRound()
        clock.advanceMs(100)
        c.input(TapInput.HIT)
        c.pause()
        c.exit()
        val row = repo.rows.single()
        assertTrue(c.state.value.closed)
        assertEquals(SessionStatus.ABANDONED, row.status)
        assertEquals(2, row.trials.size)
        assertNull(row.score)
        assertNotNull(row.endedAt)
        assertNull(savedDraftId)
    }

    @Test
    fun afterProcessDeathSessionContinuesFromLastFinishedRound() {
        val first = controller(TapGame(rounds = 3, target = 2))
        shake.rms = 3f
        first.playRound(TapInput.HIT, TapInput.MISS)
        shake.rms = 0.1f
        // Второй раунд начат и не доигран — тут процесс убивают.
        first.startRound()
        clock.advanceMs(100)
        first.input(TapInput.HIT)
        assertEquals(1L, savedDraftId)

        val game = TapGame(rounds = 3, target = 2)
        val second = controller(game, seed = 99)
        val ui = second.state.value
        assertEquals(PlayPhase.READY, ui.phase)
        assertTrue(ui.restored)
        assertEquals(1, ui.round)
        assertEquals(3, ui.totalRounds)
        assertEquals(1, repo.rows.size)

        second.playRound(TapInput.HIT, TapInput.HIT)
        assertFalse(second.state.value.restored)
        second.playRound(TapInput.HIT, TapInput.HIT)

        val row = repo.rows.single()
        assertEquals(SessionStatus.COMPLETED, row.status)
        // Недоигранная проба второго раунда пропала, сохранённый первый раунд остался.
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 2, 1 to 3, 2 to 4, 2 to 5), row.trials.map { it.round to it.index })
        assertEquals(5.0 / 6, row.score!!, 1e-9)
        assertEquals(listOf(2, 4), game.historySizes)
        // Тряским был один раунд из трёх, и это помнится после восстановления.
        assertFalse(row.isShaky)
        assertEquals(600L, row.activeMs)
    }

    @Test
    fun roundSummaryIsShownBetweenRoundsAndResultLinesAtTheEnd() {
        val c = controller(TapGame(rounds = 2, target = 2))
        assertNull(c.state.value.roundSummary)
        c.playRound(TapInput.HIT, TapInput.MISS)
        assertEquals("попаданий 1 из 2", c.state.value.roundSummary)
        c.startRound()
        // Во время раунда итога прошлого не видно.
        assertNull(c.state.value.roundSummary)
        clock.advanceMs(100)
        c.input(TapInput.HIT)
        clock.advanceMs(100)
        c.input(TapInput.HIT)
        val ui = c.state.value
        assertEquals(PlayPhase.FINISHED, ui.phase)
        assertNull(ui.roundSummary)
        assertEquals(listOf("Проб: 4"), ui.resultLines)
    }

    @Test
    fun restoredSessionShowsSummaryOfLastSavedRound() {
        val first = controller(TapGame(rounds = 3, target = 2))
        first.playRound(TapInput.MISS, TapInput.MISS)
        val second = controller(TapGame(rounds = 3, target = 2), seed = 7)
        assertTrue(second.state.value.restored)
        assertEquals("попаданий 0 из 2", second.state.value.roundSummary)
    }

    @Test
    fun deathBetweenLastRoundAndResultIsFinishedOnRestore() {
        val first = controller(TapGame(rounds = 1, target = 1))
        repo.failOnce += "finish"
        first.playRound(TapInput.HIT)
        assertNotNull(first.state.value.error)
        assertEquals(SessionStatus.IN_PROGRESS, repo.rows.single().status)

        val second = controller(TapGame(rounds = 1, target = 1))
        assertEquals(PlayPhase.FINISHED, second.state.value.phase)
        assertEquals(1.0, second.state.value.result!!.score, 1e-9)
        assertEquals(SessionStatus.COMPLETED, repo.rows.single().status)
        assertNull(savedDraftId)
    }

    @Test
    fun draftOfAnotherGameOrFinishedSessionIsNotRestored() {
        controller(TapGame(rounds = 2, target = 1)).playRound(TapInput.HIT)
        val other = controller(TapGame(rounds = 2, target = 1, id = GameId.MATH))
        assertFalse(other.state.value.restored)
        assertEquals(0, other.state.value.round)
        assertNull(savedDraftId)

        // Новая сессия закрывает зависший черновик.
        other.startRound()
        assertEquals(listOf(SessionStatus.ABANDONED, SessionStatus.IN_PROGRESS), repo.rows.map { it.status })
    }

    @Test
    fun failedRoundSaveIsShownAndRetried() {
        val c = controller(TapGame(rounds = 2, target = 1))
        repo.failOnce += "saveRound"
        c.playRound(TapInput.HIT)
        val failed = c.state.value
        assertEquals("Не удалось сохранить раунд: диск недоступен", failed.error)
        assertTrue(repo.rows.single().trials.isEmpty())

        // Пока ошибка на экране, игра дальше не идёт.
        c.startRound()
        c.exit()
        assertFalse(c.state.value.closed)

        c.retry()
        assertNull(c.state.value.error)
        assertEquals(PlayPhase.READY, c.state.value.phase)
        assertEquals(1, repo.rows.single().trials.size)
    }

    @Test
    fun failedResultSaveIsRetriedWithoutSavingRoundTwice() {
        val c = controller(TapGame(rounds = 1, target = 1))
        repo.failOnce += "finish"
        c.playRound(TapInput.HIT)
        assertEquals("Не удалось сохранить результат: диск недоступен", c.state.value.error)
        assertEquals(1, repo.rows.single().trials.size)

        c.retry()
        assertNull(c.state.value.error)
        assertEquals(PlayPhase.FINISHED, c.state.value.phase)
        assertEquals(1, repo.rows.single().trials.size)
        assertEquals(SessionStatus.COMPLETED, repo.rows.single().status)
    }

    @Test
    fun failedSessionStartIsShownAndRetried() {
        val c = controller()
        repo.failOnce += "start"
        c.startRound()
        assertEquals("Не удалось начать сессию: диск недоступен", c.state.value.error)
        assertTrue(repo.rows.isEmpty())
        c.retry()
        assertEquals(PlayPhase.RUNNING, c.state.value.phase)
        assertEquals(1, repo.rows.size)
    }

    @Test
    fun failedExitCanBeRetriedOrForced() {
        val c = controller()
        c.startRound()
        repo.failOnce += "abandon"
        c.pause()
        c.exit()
        assertEquals("Не удалось сохранить выход: диск недоступен", c.state.value.error)
        assertFalse(c.state.value.closed)
        c.forceClose()
        assertTrue(c.state.value.closed)
        assertEquals(SessionStatus.IN_PROGRESS, repo.rows.single().status)
    }

    @Test
    fun failedDraftLoadIsShownAndRetried() {
        controller().playRound(TapInput.HIT, TapInput.HIT)
        repo.failOnce += "loadDraft"
        val c = controller()
        assertEquals("Не удалось открыть игру: диск недоступен", c.state.value.error)
        assertEquals(PlayPhase.LOADING, c.state.value.phase)
        c.retry()
        assertNull(c.state.value.error)
        assertTrue(c.state.value.restored)
        assertEquals(1, c.state.value.round)
    }
}
