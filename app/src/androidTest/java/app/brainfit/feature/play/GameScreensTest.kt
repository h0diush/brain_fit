package app.brainfit.feature.play

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brainfit.core.designsystem.BrainFitTheme
import androidx.compose.ui.test.assertHeightIsAtLeast
import app.brainfit.feature.play.game.CorsiContent
import app.brainfit.feature.play.game.DoubleDecisionContent
import app.brainfit.feature.play.game.MathContent
import app.brainfit.feature.play.game.NBackContent
import app.brainfit.feature.play.game.SchulteContent
import app.brainfit.feature.play.game.StroopContent
import app.brainfit.games.doubledecision.DdParams
import app.brainfit.games.doubledecision.DdPhase
import app.brainfit.games.doubledecision.DdShape
import app.brainfit.games.doubledecision.DdTrialSpec
import app.brainfit.games.doubledecision.DoubleDecisionGame
import app.brainfit.games.nback.NBackGame
import app.brainfit.games.stroop.StroopColor
import app.brainfit.games.stroop.StroopGame
import app.brainfit.games.stroop.StroopParams
import app.brainfit.games.stroop.StroopStimulus
import app.brainfit.games.corsi.CorsiGame
import app.brainfit.games.corsi.CorsiParams
import app.brainfit.games.corsi.CorsiPhase
import app.brainfit.games.mathsprint.MathParams
import app.brainfit.games.mathsprint.MathProblem
import app.brainfit.games.mathsprint.MathSprintGame
import app.brainfit.games.schulte.SchulteGame
import app.brainfit.games.schulte.SchulteParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Экраны раундов: нажатия доходят до логики игры, клетки не меньше 48 dp. Время в тесте неважно — 0. */
@RunWith(AndroidJUnit4::class)
class GameScreensTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun schulteTapsReachTheGame() {
        val game = SchulteGame()
        var state by mutableStateOf(game.initialState(SchulteParams(3, false, listOf(5, 1, 9, 2, 8, 3, 7, 4, 6)), 1))
        rule.setContent {
            BrainFitTheme { SchulteContent(state) { state = game.reduce(state, it, 0) } }
        }
        rule.onNodeWithText("Найди: 1").assertIsDisplayed()
        rule.onNode(hasText("1") and hasClickAction()).performClick()
        rule.onNodeWithText("Найди: 2").assertIsDisplayed()
        // Ошибка: найдено по-прежнему одно число.
        rule.onNode(hasText("9") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(1, state.found)
        assertEquals(1, state.wrongTaps)
    }

    @Test
    fun schulte6x6CellsAreAtLeast48dpOnNarrowScreen() {
        val game = SchulteGame()
        val state = game.initialState(game.generate(6, kotlin.random.Random(1), emptyList()), 6)
        rule.setContent {
            // 360 dp экран минус поля по 16 dp.
            BrainFitTheme { Box(Modifier.width(328.dp)) { SchulteContent(state) {} } }
        }
        rule.onNode(hasText("36") and hasClickAction()).assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun mathKeypadTypesErasesAndSubmits() {
        val game = MathSprintGame()
        val params = MathParams(listOf(MathProblem("7 + 5", 12), MathProblem("9 − 4", 5)), durationNanos = 60_000_000_000L)
        var state by mutableStateOf(game.initialState(params, 1))
        rule.setContent {
            BrainFitTheme { MathContent(state) { state = game.reduce(state, it, 0) } }
        }
        fun key(text: String) = rule.onNode(hasText(text) and hasClickAction()).performClick()
        rule.onNodeWithText("7 + 5 = ?").assertIsDisplayed()
        rule.onNodeWithText("Осталось 60 с").assertIsDisplayed()
        key("1")
        key("3")
        rule.onNodeWithContentDescription("Стереть").performClick()
        key("2")
        rule.waitForIdle()
        assertEquals("12", state.input)
        key("OK")
        rule.onNodeWithText("9 − 4 = ?").assertIsDisplayed()
        assertTrue(state.trials.single().correct)
    }

    @Test
    fun stroopButtonsAnswerByInkColorAndThreeColorLevelHasNoGreen() {
        val game = StroopGame()
        val params = StroopParams(
            colors = StroopColor.entries.take(3),
            stimuli = listOf(StroopStimulus(StroopColor.RED, StroopColor.BLUE), StroopStimulus(StroopColor.YELLOW, StroopColor.YELLOW)),
            limitMs = 3_000,
        )
        var state by mutableStateOf(game.tick(game.initialState(params, 1), 600 * 1_000_000L))
        rule.setContent {
            BrainFitTheme { StroopContent(state) { state = game.reduce(state, it, 700 * 1_000_000L) } }
        }
        rule.onNodeWithText("КРАСНЫЙ").assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(hasText("Зелёный")).fetchSemanticsNodes().size)
        rule.onNode(hasText("Синий") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        rule.waitForIdle()
        assertTrue(state.trials.single().correct)
    }

    @Test
    fun nBackButtonRegistersPress() {
        val game = NBackGame()
        val params = game.build(n = 1, intervalMs = 2_500, scored = 4, targetCount = 1, lureCount = 0, rng = kotlin.random.Random(1))
        // Второй стимул (номер 1) горит: 1000 + 3000 мс.
        var state by mutableStateOf(game.tick(game.initialState(params, 1), 4_100 * 1_000_000L))
        rule.setContent {
            BrainFitTheme { NBackContent(state) { state = game.reduce(state, it, 4_200 * 1_000_000L) } }
        }
        rule.onNodeWithText("Та же клетка, что 1 шаг назад?").assertIsDisplayed()
        rule.onNode(hasText("Совпадает") and hasClickAction()).performClick()
        rule.waitForIdle()
        // Стимул 1 начался на 4000 мс, нажатие — на 4200.
        assertEquals(mapOf(1 to 200L), state.presses)
    }

    @Test
    fun doubleDecisionAnswersCenterThenPeripheryWithBigTargets() {
        val game = DoubleDecisionGame()
        val params = DdParams(
            trials = listOf(DdTrialSpec(DdShape.WIDE, target = 2, distractors = emptyList(), maskSeed = 3)),
            aspect = 1.6,
            startExposureMs = 500.0,
            startStreak = 0,
        )
        var state by mutableStateOf(game.initialState(params, 1).copy(phase = DdPhase.CENTER, exposureFrames = 30))
        rule.setContent {
            BrainFitTheme {
                Box(Modifier.width(328.dp)) { DoubleDecisionContent(state) { state = game.reduce(state, it, 1_000_000_000L) } }
            }
        }
        rule.onNodeWithText("Какая фигура была в центре?").assertIsDisplayed()
        rule.onNodeWithContentDescription("Вытянута поперёк").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Где был кружок?").assertIsDisplayed()
        rule.onNodeWithContentDescription("Позиция 3").assertWidthIsAtLeast(48.dp).performClick()
        rule.waitForIdle()
        assertTrue(state.trials.single().correct)
    }

    @Test
    fun corsiIgnoresTapsWhileShowingAndCountsThemInInput() {
        val game = CorsiGame()
        val params = CorsiParams(gridSize = 3, cells = listOf(0, 4, 8), reverse = false, highlightMs = 700)
        var state by mutableStateOf(game.initialState(params, 1))
        rule.setContent {
            BrainFitTheme { CorsiContent(state) { state = game.reduce(state, it, 0) } }
        }
        rule.onNodeWithText("Запоминай").assertIsDisplayed()
        rule.onNodeWithContentDescription("Клетка 1, 1").performClick()
        rule.waitForIdle()
        assertTrue(state.taps.isEmpty())

        rule.runOnUiThread { state = game.tick(state, game.showEndMs(params) * 1_000_000L) }
        rule.onNodeWithText("Повтори по порядку").assertIsDisplayed()
        assertEquals(CorsiPhase.INPUT, state.phase)
        rule.onNodeWithContentDescription("Клетка 1, 1").performClick()
        rule.onNodeWithText("Нажато 1 из 3").assertIsDisplayed()
        rule.onNodeWithContentDescription("Клетка 2, 2").performClick()
        rule.onNodeWithContentDescription("Клетка 3, 3").performClick()
        rule.waitForIdle()
        assertTrue(game.isFinished(state))
        assertTrue(game.trials(state).single().correct)
    }
}
