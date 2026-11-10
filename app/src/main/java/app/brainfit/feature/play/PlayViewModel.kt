package app.brainfit.feature.play

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.brainfit.core.sensors.ShakeMonitor
import app.brainfit.core.time.MonotonicClock
import app.brainfit.core.time.WallClock
import app.brainfit.domain.model.GameCatalog
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.SessionMode
import app.brainfit.domain.repository.DifficultyRepository
import app.brainfit.domain.repository.SessionRepository
import app.brainfit.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.random.Random

/** Контроллер и экран одной игры с общими типами состояния и ввода. */
class PlayBinding<S : Any, I : Any>(
    val title: String,
    val controller: PlayController<*, S, I>,
    val content: @Composable (state: S, onInput: (I) -> Unit) -> Unit,
    val howTo: List<String>,
    val demo: Demo<S, I>,
) {
    @Composable
    fun Screen(onClose: () -> Unit) = PlayScreen(controller, title, content, onClose, howTo, demo)
}

@HiltViewModel
class PlayViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    registry: GameRegistry,
    private val sessions: SessionRepository,
    private val difficulty: DifficultyRepository,
    private val settings: SettingsRepository,
    private val shake: ShakeMonitor,
    private val clock: MonotonicClock,
    private val wallClock: WallClock,
) : ViewModel() {

    /** null — такой игры нет или она ещё не готова. */
    val binding: PlayBinding<*, *>? = GameId.entries
        .firstOrNull { it.name == savedState.get<String>(ARG_GAME) }
        ?.let(registry::get)
        ?.let { bind(it) }

    private fun <P, S : Any, I : Any> bind(module: GameModule<P, S, I>): PlayBinding<S, I> = PlayBinding(
        title = GameCatalog.get(module.game.id).title,
        controller = PlayController(
            game = module.game,
            // Тренировки (быстрая, стандартная) — этап 5; пока игра запускается только свободно.
            mode = SessionMode.FREE,
            scope = viewModelScope,
            sessions = sessions,
            difficulty = difficulty,
            settings = settings,
            shake = shake,
            clock = clock,
            wallClock = wallClock,
            // Новый seed при каждом создании: после смерти процесса раунд получит новое задание.
            seed = Random.nextLong(),
            draftId = savedState.get<Long>(KEY_DRAFT),
            onDraftId = { savedState[KEY_DRAFT] = it },
        ),
        content = module.content,
        howTo = module.howTo,
        demo = Demo.of(module.game),
    )

    override fun onCleared() {
        shake.stop()
    }

    companion object {
        const val ARG_GAME = "gameId"
        private const val KEY_DRAFT = "draftSessionId"
    }
}
