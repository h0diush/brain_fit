package app.brainfit.feature.games

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.brainfit.core.designsystem.BrainCard
import app.brainfit.core.designsystem.BrainTheme
import app.brainfit.core.designsystem.Dimens
import app.brainfit.core.designsystem.MutedText
import app.brainfit.domain.model.GameCatalog
import app.brainfit.domain.model.GameDifficulty
import app.brainfit.domain.model.GameId
import app.brainfit.domain.repository.DifficultyRepository
import app.brainfit.feature.play.GameRegistry
import app.brainfit.games.engine.START_LEVEL
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import app.brainfit.domain.progress.Progress
import app.brainfit.domain.progress.ProgressMetrics
import app.brainfit.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** levels == null — данные ещё читаются из базы. */
data class GamesUiState(
    val levels: Map<GameId, GameDifficulty>? = null,
    /** Игры, которые уже можно запустить. */
    val playable: Set<GameId> = emptySet(),
    /** Рекорд главной метрики на текущем уровне; игры без рекорда здесь нет. */
    val records: Map<GameId, String> = emptyMap(),
    val error: String? = null,
)

@HiltViewModel
class GamesViewModel @Inject constructor(
    difficulty: DifficultyRepository,
    progress: ProgressRepository,
    registry: GameRegistry,
) : ViewModel() {
    private val playable = registry.playable

    val state: StateFlow<GamesUiState> = combine(difficulty.observeStates(), progress.observeCompleted()) { levels, sessions ->
        val byGame = sessions.groupBy { it.gameId }
        val records = levels.values.mapNotNull { state ->
            val metric = ProgressMetrics.main(state.gameId)
            Progress.record(byGame[state.gameId].orEmpty(), metric, state.level)
                ?.let { state.gameId to "Рекорд уровня ${state.level}: ${metric.title.lowercase()} ${metric.format(it.value)}" }
        }.toMap()
        GamesUiState(levels = levels, playable = playable, records = records)
    }
        // Ошибку чтения не глотаем: показываем над карточками.
        .catch { e -> emit(GamesUiState(levels = null, playable = playable, error = "Не удалось прочитать историю: ${e.message ?: e.javaClass.simpleName}")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GamesUiState(playable = playable))
}

@Composable
fun GamesScreen(onPlay: (GameId) -> Unit, vm: GamesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.gap),
    ) {
        state.error?.let { error ->
            item { Text(error, color = BrainTheme.colors.error, style = MaterialTheme.typography.bodyLarge) }
        }
        items(GameCatalog.all, key = { it.id }) { game ->
            val ready = game.id in state.playable
            // Нажимаются только готовые игры; остальные честно подписаны.
            val cardModifier = if (ready) {
                Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(role = Role.Button, onClickLabel = "Играть") { onPlay(game.id) }
            } else {
                Modifier
            }
            BrainCard(cardModifier) {
                Text(game.title, style = MaterialTheme.typography.titleMedium)
                MutedText(game.trains)
                val levels = state.levels
                val level = levels?.get(game.id)
                val status = when {
                    !ready -> "Игра ещё не готова"
                    levels == null -> null
                    level != null && level.calibrated -> "Уровень ${level.level}"
                    level != null -> "Уровень ${level.level} · калибровка"
                    // Запись об уровне появляется после первой завершённой сессии.
                    else -> "Ещё не играли · стартовый уровень $START_LEVEL"
                }
                if (status != null) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (ready && level != null) BrainTheme.colors.accent else BrainTheme.colors.textSecondary,
                    )
                }
                state.records[game.id]?.let { MutedText(it) }
            }
        }
    }
}
