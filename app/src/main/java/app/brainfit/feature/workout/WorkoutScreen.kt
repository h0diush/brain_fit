package app.brainfit.feature.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.brainfit.core.designsystem.BrainCard
import app.brainfit.core.designsystem.BrainTheme
import app.brainfit.core.designsystem.Dimens
import app.brainfit.core.designsystem.MutedText
import app.brainfit.core.designsystem.PrimaryButton
import app.brainfit.core.designsystem.SecondaryButton
import app.brainfit.domain.model.GameCatalog
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.SessionMode
import app.brainfit.domain.model.Workout
import app.brainfit.domain.model.WorkoutItem
import app.brainfit.domain.model.WorkoutMode
import app.brainfit.domain.model.WorkoutStatus
import app.brainfit.domain.model.sessionMode
import app.brainfit.domain.repository.WorkoutRepository
import app.brainfit.games.engine.Fmt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkoutUiState(
    /** false — тренировка ещё читается из базы. */
    val loaded: Boolean = false,
    /** null после загрузки — такой тренировки нет. */
    val workout: Workout? = null,
    val error: String? = null,
    val busy: Boolean = false,
)

private data class LocalState(val error: String? = null, val busy: Boolean = false)

@HiltViewModel
class WorkoutViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val workouts: WorkoutRepository,
) : ViewModel() {

    val workoutId: Long = checkNotNull(savedState.get<Long>(ARG_WORKOUT)) { "Нет id тренировки" }

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<WorkoutUiState> = combine(workouts.observe(workoutId), local) { workout, l ->
        WorkoutUiState(loaded = true, workout = workout, error = l.error, busy = l.busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutUiState())

    fun skip(order: Int) {
        if (local.value.busy) return
        local.value = LocalState(busy = true)
        viewModelScope.launch {
            try {
                workouts.skip(workoutId, order)
                local.update { it.copy(busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.value = LocalState(error = "Не удалось пропустить игру: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        const val ARG_WORKOUT = "workoutId"
    }
}

fun WorkoutMode.title(): String = when (this) {
    WorkoutMode.QUICK -> "Быстрая тренировка"
    WorkoutMode.STANDARD -> "Стандартная тренировка"
}

/** Экран тренировки: список игр, следующая игра, пропуск, итог. Сами игры идут на экране игры. */
@Composable
fun WorkoutScreen(
    onPlay: (game: GameId, workoutId: Long, mode: SessionMode) -> Unit,
    onClose: () -> Unit,
    vm: WorkoutViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.loaded) return
    val workout = state.workout
    Column(Modifier.fillMaxSize().padding(Dimens.screenPadding)) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.gap),
        ) {
            if (workout == null) {
                Text("Тренировка не найдена", style = MaterialTheme.typography.headlineMedium)
                return@Column
            }
            Text(workout.mode.title(), style = MaterialTheme.typography.headlineMedium)
            MutedText(statusLine(workout))
            BrainCard {
                workout.items.forEach { item -> ItemRow(item, isNext = item == workout.next && workout.status == WorkoutStatus.IN_PROGRESS) }
            }
            state.error?.let { Text(it, color = BrainTheme.colors.error, style = MaterialTheme.typography.bodyLarge) }
        }
        Column(
            modifier = Modifier.padding(top = Dimens.gap),
            verticalArrangement = Arrangement.spacedBy(Dimens.gap),
        ) {
            val next = workout?.next
            when {
                workout != null && workout.status == WorkoutStatus.IN_PROGRESS && next != null -> {
                    val title = GameCatalog.get(next.gameId).title
                    PrimaryButton("Играть: $title", onClick = { onPlay(next.gameId, workout.id, workout.mode.sessionMode()) })
                    SecondaryButton("Пропустить", onClick = { vm.skip(next.order) })
                    SecondaryButton("На главную", onClick = onClose)
                }
                workout?.status == WorkoutStatus.COMPLETED -> PrimaryButton("Готово", onClick = onClose)
                else -> PrimaryButton("На главную", onClick = onClose)
            }
        }
    }
}

private fun statusLine(workout: Workout): String = when (workout.status) {
    WorkoutStatus.IN_PROGRESS -> "Сыграно ${workout.doneCount} из ${workout.items.size}"
    WorkoutStatus.COMPLETED -> "Тренировка закончена · активное время ${Fmt.duration(workout.activeMs)}"
    WorkoutStatus.ABANDONED -> "Тренировка закрыта: её заменила новая или закончились сутки. Сыгранные игры остались в истории."
}

@Composable
private fun ItemRow(item: WorkoutItem, isNext: Boolean) {
    val status = when {
        item.sessionId != null -> "Сыграно" + (item.score?.let { " · ${Fmt.percent(it)}" } ?: "")
        item.skipped -> "Пропущено"
        isNext -> "Сейчас"
        else -> "Дальше"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${item.order + 1}. ${GameCatalog.get(item.gameId).title}",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            status,
            style = MaterialTheme.typography.labelLarge,
            color = if (isNext) BrainTheme.colors.accent else BrainTheme.colors.textSecondary,
        )
    }
}
