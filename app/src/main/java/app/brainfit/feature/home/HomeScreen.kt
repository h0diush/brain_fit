package app.brainfit.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.brainfit.core.designsystem.PrimaryButton
import app.brainfit.core.designsystem.SecondaryButton
import app.brainfit.core.designsystem.SectionTitle
import app.brainfit.core.time.WallClock
import app.brainfit.domain.model.GameCatalog
import app.brainfit.domain.model.SessionMode
import app.brainfit.domain.model.SessionSummary
import app.brainfit.domain.model.Workout
import app.brainfit.domain.model.WorkoutMode
import app.brainfit.domain.repository.SessionRepository
import app.brainfit.domain.repository.WorkoutRepository
import app.brainfit.domain.workout.WorkoutPlanner
import app.brainfit.feature.workout.title
import app.brainfit.games.engine.Fmt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.brainfit.core.time.LocalDays
import app.brainfit.domain.progress.Progress
import app.brainfit.domain.progress.Streak
import app.brainfit.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class HomeUiState(
    /** null — данные ещё читаются из базы. */
    val today: List<SessionSummary>? = null,
    /** Незавершённая тренировка сегодняшнего дня. */
    val active: Workout? = null,
    /** null — ещё читается. */
    val streak: Streak? = null,
    val error: String? = null,
    val busy: Boolean = false,
)

private data class Today(val sessions: List<SessionSummary>, val active: Workout?, val streak: Streak)

private data class LocalState(val error: String? = null, val busy: Boolean = false)

@HiltViewModel
class HomeViewModel @Inject constructor(
    sessions: SessionRepository,
    progress: ProgressRepository,
    private val workouts: WorkoutRepository,
    wallClock: WallClock,
) : ViewModel() {

    private val days = LocalDays(wallClock)
    private val local = MutableStateFlow(LocalState())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val today: Flow<Today> = days.observe()
        // Вчерашняя незавершённая тренировка больше не продолжается.
        .onEach { day -> guarded("Не удалось закрыть вчерашнюю тренировку") { workouts.abandonStale(day.toEpochDay()) } }
        .flatMapLatest { day ->
            val (from, to) = days.bounds(day)
            val streak = progress.observeCompleted().map { all ->
                Progress.streak(all.map { days.dayOf(it.startedAt).toEpochDay() }, day.toEpochDay())
            }
            combine(sessions.observeCompletedBetween(from, to), workouts.observeActive(day.toEpochDay()), streak, ::Today)
        }
        // Ошибку чтения не глотаем: показываем на главной.
        .catch { e -> local.value = LocalState(error = "Не удалось прочитать историю: ${e.message ?: e.javaClass.simpleName}") }

    val state: StateFlow<HomeUiState> = combine(today, local) { t, l ->
        HomeUiState(today = t.sessions, active = t.active, streak = t.streak, error = l.error, busy = l.busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Новая тренировка: подбор игр и запись. Незаконченная тренировка заменяется новой. */
    fun start(mode: WorkoutMode, onCreated: (Long) -> Unit) {
        if (local.value.busy) return
        local.value = LocalState(busy = true)
        viewModelScope.launch {
            guarded("Не удалось начать тренировку") {
                val day = days.today()
                val (from, to) = days.bounds(day)
                val games = WorkoutPlanner.plan(mode, workouts.planningInput(from, to))
                val id = workouts.create(day.toEpochDay(), mode, games)
                local.value = LocalState()
                onCreated(id)
            }
        }
    }

    /** Ошибку не глотаем: показываем на главной. */
    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            local.value = LocalState(error = "$what: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

@Composable
fun HomeScreen(onOpenWorkout: (Long) -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.gap),
    ) {
        BrainCard {
            SectionTitle("Тренировки")
            val active = state.active
            if (active != null) {
                MutedText("${active.mode.title()}: сыграно ${active.doneCount} из ${active.items.size}")
                PrimaryButton("Продолжить", onClick = { onOpenWorkout(active.id) })
            } else {
                MutedText("Игры подбираются сами: сначала те, в которые дольше всего не играли.")
            }
            val start = { mode: WorkoutMode -> vm.start(mode, onOpenWorkout) }
            if (active == null) {
                PrimaryButton("Быстрая · ~3 мин · 2 игры", onClick = { start(WorkoutMode.QUICK) })
            } else {
                SecondaryButton("Быстрая · ~3 мин · 2 игры", onClick = { start(WorkoutMode.QUICK) })
            }
            SecondaryButton("Стандартная · ~8 мин · 4 игры", onClick = { start(WorkoutMode.STANDARD) })
            if (active != null) MutedText("Новая тренировка заменит незаконченную.")
            state.error?.let { Text(it, color = BrainTheme.colors.error, style = MaterialTheme.typography.bodyLarge) }
        }
        state.streak?.let { StreakCard(it) }
        BrainCard {
            SectionTitle("Сегодня")
            val today = state.today
            when {
                today == null -> Unit
                today.isEmpty() -> MutedText("Сегодня ещё ничего не сыграно.")
                else -> today.forEach { session ->
                    Text(
                        text = GameCatalog.get(session.gameId).title,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    MutedText(sessionLine(session))
                }
            }
        }
    }
}

private fun sessionLine(session: SessionSummary): String {
    val mode = when (session.mode) {
        SessionMode.QUICK -> "быстрая тренировка"
        SessionMode.STANDARD -> "стандартная тренировка"
        SessionMode.FREE -> "свободная игра"
    }
    return listOfNotNull("уровень ${session.level}", session.score?.let { Fmt.percent(it) }, mode).joinToString(" · ")
}

/** Серия дней (ТЗ, раздел 7): без наказаний — пропуск просто начинает её заново. */
@Composable
fun StreakCard(streak: Streak) {
    BrainCard {
        SectionTitle("Серия")
        when {
            streak.best == 0 -> MutedText("Серия начнётся с первой сыгранной сессии.")
            streak.current > 0 -> {
                Text("${Fmt.days(streak.current)} подряд", style = MaterialTheme.typography.titleLarge, color = BrainTheme.colors.accent)
                MutedText(
                    listOfNotNull(
                        "Лучшая серия — ${Fmt.days(streak.best)}.",
                        if (streak.playedToday) null else "Сегодня ещё не играли.",
                    ).joinToString(" "),
                )
            }
            else -> MutedText("Сейчас серии нет — она начнётся с сегодняшней сессии. Лучшая — ${Fmt.days(streak.best)}.")
        }
    }
}
