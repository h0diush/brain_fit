package app.brainfit.feature.play

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brainfit.core.designsystem.BrainFitTheme
import app.brainfit.core.designsystem.PrimaryButton
import app.brainfit.data.db.BrainFitDatabase
import app.brainfit.data.repository.SessionRepositoryImpl
import app.brainfit.domain.model.SessionMode
import app.brainfit.domain.model.SessionStatus
import app.brainfit.testing.FakeClock
import app.brainfit.testing.FakeDifficultyRepository
import app.brainfit.testing.FakeSettingsRepository
import app.brainfit.testing.FakeShakeMonitor
import app.brainfit.testing.TapGame
import app.brainfit.testing.TapInput
import app.brainfit.testing.TapParams
import app.brainfit.testing.TapState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Экран-хост на устройстве с игрой-заглушкой и настоящей базой: раунды, автопауза при уходе
 * в фон, продолжение после «смерти процесса» (новый контроллер с тем же черновиком).
 */
@RunWith(AndroidJUnit4::class)
class PlayHostTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: BrainFitDatabase
    private lateinit var repo: SessionRepositoryImpl
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = FakeClock()
    private var draftId: Long? = null
    private var closed = false

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BrainFitDatabase::class.java).build()
        repo = SessionRepositoryImpl(db, db.sessionDao(), db.trialDao())
        // Кадры идут без остановки, пока раунд запущен: часы теста двигаем вручную.
        rule.mainClock.autoAdvance = false
    }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    private fun controller(): PlayController<TapParams, TapState, TapInput> = PlayController(
        // Лимит раунда с запасом: виртуальные кадры теста не должны оборвать раунд по времени.
        game = TapGame(rounds = 2, target = 2, limitNanos = 3_600 * TapGame.NANOS_PER_SECOND),
        mode = SessionMode.FREE,
        scope = scope,
        sessions = repo,
        difficulty = FakeDifficultyRepository(),
        settings = FakeSettingsRepository(),
        shake = FakeShakeMonitor(),
        clock = clock,
        wallClock = clock,
        seed = 5,
        draftId = draftId,
        onDraftId = { draftId = it },
    )

    private fun show(controller: PlayController<TapParams, TapState, TapInput>, withIntro: Boolean = false) {
        val game = TapGame(rounds = 2, target = 2, limitNanos = 3_600 * TapGame.NANOS_PER_SECOND)
        rule.setContent {
            BrainFitTheme {
                PlayScreen(
                    controller = controller,
                    title = "Тестовая игра",
                    content = { state, onInput ->
                        Column {
                            Text("Нажатий: ${state.trials.size}")
                            PrimaryButton("Попал", onClick = { onInput(TapInput.HIT) })
                        }
                    },
                    onClose = { closed = true },
                    howTo = if (withIntro) listOf("Жми «Попал».") else emptyList(),
                    demo = if (withIntro) Demo.of(game) else null,
                )
            }
        }
    }

    @Test
    fun instructionAndDemoComeFirstAndDemoIsNotSaved() {
        show(controller(), withIntro = true)
        waitForText("Как играть")
        waitForText("•  Жми «Попал».")
        click("Попробовать")
        waitForText("Пробная попытка: ответы не записываются")
        // «Пропустить» возвращает к инструкции.
        click("Пропустить")
        click("Попробовать")
        click("Попал")
        click("Попал")
        waitForText("Пробная попытка закончена")
        waitForText("Итог: попаданий 2 из 2")
        assertEquals(0, countSessions())

        click("Начать игру")
        waitForText("Нажатий: 0")
        assertEquals(1, countSessions())
        click("Попал")
        click("Попал")
        waitForText("Прошлый раунд: попаданий 2 из 2")
    }

    private fun countSessions(): Int =
        db.query("SELECT COUNT(*) FROM exercise_sessions", null).use { c -> c.moveToFirst(); c.getInt(0) }

    /** Ждём запись в базу и перерисовку: двигаем кадры, пока текст не появится. */
    private fun waitForText(text: String) {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.mainClock.advanceTimeByFrame()
            runCatching { rule.onNodeWithText(text).assertIsDisplayed() }.isSuccess
        }
    }

    private fun click(text: String) {
        waitForText(text)
        rule.onNodeWithText(text).performClick()
        rule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun playsSessionWithAutoPauseAndSavesIt() {
        show(controller())
        waitForText("Раунд 1 из 2")
        click("Начать")
        waitForText("Нажатий: 0")
        click("Попал")
        waitForText("Нажатий: 1")

        // Приложение ушло в фон посреди раунда.
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForText("Пауза")
        assertTrue(rule.onAllNodesWithTextCount("Попал") == 0)
        click("Продолжить")
        waitForText("Нажатий: 1")

        // Пауза кнопкой.
        rule.onNodeWithContentDescription("Пауза").performClick()
        click("Продолжить")

        click("Попал")
        waitForText("Раунд 2 из 2")
        click("Дальше")
        click("Попал")
        waitForText("Нажатий: 1")
        click("Попал")
        waitForText("Результат: 100 %")

        val session = runBlocking { db.sessionDao().byId(draftIdOfOnlySession())!! }
        assertEquals(SessionStatus.COMPLETED, session.status)
        assertEquals(1.0, session.score!!, 1e-9)
        val trials = runBlocking { db.trialDao().forSession(session.id) }
        assertEquals(listOf(0, 0, 1, 1), trials.map { it.round })

        click("Готово")
        rule.waitUntil(5_000) { closed }
    }

    @Test
    fun continuesAfterProcessDeathFromLastFinishedRound() {
        // Первая «жизнь» процесса: сыгран один раунд, второй начат.
        val first = controller()
        rule.waitUntil(5_000) { first.state.value.phase == PlayPhase.READY }
        rule.runOnUiThread { first.startRound() }
        rule.waitUntil(5_000) { first.state.value.phase == PlayPhase.RUNNING }
        rule.runOnUiThread {
            first.input(TapInput.HIT)
            first.input(TapInput.HIT)
        }
        rule.waitUntil(5_000) { first.state.value.phase == PlayPhase.READY }
        rule.runOnUiThread { first.startRound() }
        rule.waitUntil(5_000) { first.state.value.phase == PlayPhase.RUNNING }
        rule.runOnUiThread { first.input(TapInput.HIT) }

        // Вторая: новый контроллер, от старого остался только id черновика.
        show(controller())
        waitForText("Продолжаем с того раунда, на котором остановились.")
        waitForText("Раунд 2 из 2")
        click("Дальше")
        waitForText("Нажатий: 0")
        click("Попал")
        click("Попал")
        waitForText("Результат: 100 %")

        val trials = runBlocking { db.trialDao().forSession(draftIdOfOnlySession()) }
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 2, 1 to 3), trials.map { it.round to it.index })
    }

    private fun draftIdOfOnlySession(): Long {
        val ids = mutableListOf<Long>()
        db.query("SELECT id FROM exercise_sessions", null).use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        return ids.single()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
