package app.brainfit.feature.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.Alignment
import app.brainfit.core.designsystem.HairlineDivider
import app.brainfit.core.designsystem.LevelBadge
import app.brainfit.core.designsystem.Sparkline
import app.brainfit.core.designsystem.appear
import app.brainfit.domain.progress.MIN_SESSIONS_FOR_CHART
import app.brainfit.games.engine.Fmt
import javax.inject.Inject

/** levels == null — данные ещё читаются из базы. */
data class GamesUiState(
    val levels: Map<GameId, GameDifficulty>? = null,
    /** Игры, которые уже можно запустить. */
    val playable: Set<GameId> = emptySet(),
    /** Рекорд главной метрики на текущем уровне; игры без рекорда здесь нет. */
    val records: Map<GameId, String> = emptyMap(),
    /** Результаты (score) последних сессий для мини-графика. */
    val recent: Map<GameId, List<Double>> = emptyMap(),
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
                ?.let { state.gameId to "Рекорд уровня ${state.level} · ${metric.title.lowercase()} ${metric.format(it.value)}" }
        }.toMap()
        val recent = byGame.mapValues { (_, list) -> Progress.recentScores(list) }
        GamesUiState(levels = levels, playable = playable, records = records, recent = recent)
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
        itemsIndexed(GameCatalog.all, key = { _, game -> game.id }) { index, game ->
            val ready = game.id in state.playable
            val levels = state.levels
            val level = levels?.get(game.id)
            // Нажимаются только готовые игры; остальные честно подписаны.
            BrainCard(
                modifier = Modifier.appear(index),
                onClick = if (ready) ({ onPlay(game.id) }) else null,
                onClickLabel = "Играть",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(game.title, style = MaterialTheme.typography.titleMedium)
                        MutedText(game.trains)
                    }
                    if (level != null) LevelBadge(level.level)
                }
                val status = when {
                    !ready -> "Игра ещё не готова"
                    levels == null -> null
                    level != null && !level.calibrated -> "Уровень ${level.level} · идёт калибровка"
                    level != null -> null
                    // Запись об уровне появляется после первой завершённой сессии.
                    else -> "Ещё не играли · стартовый уровень $START_LEVEL"
                }
                status?.let { MutedText(it) }
                val recent = state.recent[game.id].orEmpty()
                if (recent.size >= MIN_SESSIONS_FOR_CHART) {
                    Sparkline(
                        values = recent,
                        description = "Результат последних ${Fmt.sessions(recent.size)}: от ${Fmt.percent(recent.min())} до " +
                            "${Fmt.percent(recent.max())}, последняя — ${Fmt.percent(recent.last())}.",
                    )
                    Row {
                        Text("Результат последних ${recent.size}", style = MaterialTheme.typography.labelMedium, color = BrainTheme.colors.textSecondary, modifier = Modifier.weight(1f))
                        Text(Fmt.percent(recent.last()), style = MaterialTheme.typography.labelLarge)
                    }
                }
                state.records[game.id]?.let {
                    HairlineDivider()
                    Text(it, style = MaterialTheme.typography.labelLarge, color = BrainTheme.colors.accent)
                }
            }
        }
    }
}
