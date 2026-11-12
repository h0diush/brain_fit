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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import app.brainfit.core.designsystem.ActivityGrid
import app.brainfit.core.designsystem.HairlineDivider
import app.brainfit.core.designsystem.LevelBadge
import app.brainfit.core.designsystem.Meter
import app.brainfit.core.designsystem.StatRow
import app.brainfit.core.designsystem.StatTile
import app.brainfit.core.designsystem.appear
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import javax.inject.Inject

data class HomeUiState(
    /** null — данные ещё читаются из базы. */
    val today: List<SessionSummary>? = null,
    /** Незавершённая тренировка сегодняшнего дня. */
    val active: Workout? = null,
    /** null — ещё читается. */
    val streak: Streak? = null,
    /** Сессий по дням текущей недели с понедельника; null — день ещё не наступил. */
    val week: List<Int?> = emptyList(),
    val todayActiveMs: Long = 0,
    val todayDate: LocalDate? = null,
    val error: String? = null,
    val busy: Boolean = false,
)

private data class HomeStats(val streak: Streak, val week: List<Int?>, val todayActiveMs: Long)

private data class Today(val day: LocalDate, val sessions: List<SessionSummary>, val active: Workout?, val stats: HomeStats)

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
            val stats = progress.observeCompleted().map { all ->
                val allDays = all.map { days.dayOf(it.startedAt).toEpochDay() }
                val monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val counts = Progress.dailyCounts(allDays, monday.toEpochDay(), day.toEpochDay())
                HomeStats(
                    streak = Progress.streak(allDays, day.toEpochDay()),
                    week = (0 until 7).map { counts.getOrNull(it) },
                    todayActiveMs = all.filter { it.startedAt in from until to }.sumOf { it.activeMs },
                )
            }
            combine(sessions.observeCompletedBetween(from, to), workouts.observeActive(day.toEpochDay()), stats) { today, active, st ->
                Today(day, today, active, st)
            }
        }
        // Ошибку чтения не глотаем: показываем на главной.
        .catch { e -> local.value = LocalState(error = "Не удалось прочитать историю: ${e.message ?: e.javaClass.simpleName}") }

    val state: StateFlow<HomeUiState> = combine(today, local) { t, l ->
        HomeUiState(
            today = t.sessions,
            active = t.active,
            streak = t.stats.streak,
            week = t.stats.week,
            todayActiveMs = t.stats.todayActiveMs,
            todayDate = t.day,
            error = l.error,
            busy = l.busy,
        )
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

private val dateTitle = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru"))
private val weekdayLabels = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

@Composable
fun HomeScreen(onOpenWorkout: (Long) -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val start = { mode: WorkoutMode -> vm.start(mode, onOpenWorkout) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(Dimens.gap),
    ) {
        TodayCard(state, Modifier.appear(0))

        SectionTitle("Тренировка", Modifier.padding(top = 8.dp).appear(1))
        val active = state.active
        if (active != null) {
            BrainCard(Modifier.appear(1)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(active.mode.title(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        "${active.doneCount} из ${active.items.size}",
                        style = MaterialTheme.typography.titleMedium,
                        color = BrainTheme.colors.accent,
                    )
                }
                Meter(active.doneCount.toFloat() / active.items.size)
                MutedText("Не закончена. Продолжить можно до конца суток.")
                PrimaryButton("Продолжить", onClick = { onOpenWorkout(active.id) })
            }
        }
        Row(Modifier.fillMaxWidth().appear(2), horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
            ModeTile("Быстрая", "~3 мин", "2 игры", Modifier.weight(1f)) { start(WorkoutMode.QUICK) }
            ModeTile("Стандартная", "~8 мин", "4 игры", Modifier.weight(1f)) { start(WorkoutMode.STANDARD) }
        }
        MutedText(
            if (active != null) "Новая тренировка заменит незаконченную." else "Игры подбираются сами: сначала те, в которые дольше всего не играли.",
            Modifier.appear(2),
        )
        state.error?.let { Text(it, color = BrainTheme.colors.error, style = MaterialTheme.typography.bodyLarge) }

        SectionTitle("Сыграно сегодня", Modifier.padding(top = 8.dp).appear(2))
        val today = state.today
        when {
            today == null -> Unit
            today.isEmpty() -> MutedText("Сегодня ещё ничего не сыграно.", Modifier.appear(2))
            else -> BrainCard(Modifier.appear(2)) {
                today.forEachIndexed { i, session ->
                    if (i > 0) HairlineDivider()
                    SessionRow(session)
                }
            }
        }
    }
}

/** Сводка дня: сессии, активное время, серия и неделя по дням. */
@Composable
private fun TodayCard(state: HomeUiState, modifier: Modifier = Modifier) {
    val streak = state.streak
    BrainCard(modifier) {
        SectionTitle(state.todayDate?.format(dateTitle) ?: "Сегодня")
        StatRow {
            StatTile("Сессий", "${state.today?.size ?: 0}", Modifier.weight(1f), caption = "сегодня")
            StatTile("Активно", activeText(state.todayActiveMs), Modifier.weight(1f), caption = "без пауз")
            StatTile(
                "Серия",
                streak?.let { Fmt.days(it.current) } ?: "—",
                Modifier.weight(1f),
                caption = streak?.takeIf { it.best > 0 }?.let { "лучшая ${Fmt.days(it.best)}" },
                accent = (streak?.current ?: 0) > 0,
            )
        }
        if (state.week.isNotEmpty()) {
            HairlineDivider(Modifier.padding(vertical = 4.dp))
            val played = state.week.count { (it ?: 0) > 0 }
            ActivityGrid(
                rows = listOf(state.week),
                columnLabels = weekdayLabels,
                description = "Эта неделя: занимались ${Fmt.days(played)}.",
                cellAspect = 2.4f,
            )
        }
        streak?.let(::streakLine)?.let { MutedText(it) }
    }
}

/** Серия без упрёков: пропуск просто начинает её заново (ТЗ, раздел 7). */
private fun streakLine(streak: Streak): String? = when {
    streak.best == 0 -> "Серия начнётся с первой сыгранной сессии."
    streak.current > 0 && !streak.playedToday -> "Сегодня ещё не играли — серия продолжится с первой сессией."
    // Сегодня уже играли: всё сказано в плитке «Серия».
    streak.current > 0 -> null
    else -> "Сейчас серии нет — она начнётся с сегодняшней сессии."
}

private fun activeText(ms: Long): String = when {
    ms <= 0 -> "0 мин"
    ms < 60_000 -> "${(ms + 500) / 1000} с"
    else -> "${(ms + 30_000) / 60_000} мин"
}

@Composable
private fun ModeTile(title: String, duration: String, games: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    BrainCard(modifier, onClick = onClick, onClickLabel = "Начать тренировку") {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(duration, style = MaterialTheme.typography.headlineSmall, color = BrainTheme.colors.accent)
        MutedText(games)
    }
}

@Composable
private fun SessionRow(session: SessionSummary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(GameCatalog.get(session.gameId).title, style = MaterialTheme.typography.bodyLarge)
            MutedText(modeText(session.mode))
        }
        LevelBadge(session.level, Modifier.padding(end = 12.dp))
        Text(session.score?.let { Fmt.percent(it) } ?: "—", style = MaterialTheme.typography.titleMedium)
    }
}

private fun modeText(mode: SessionMode) = when (mode) {
    SessionMode.QUICK -> "быстрая тренировка"
    SessionMode.STANDARD -> "стандартная тренировка"
    SessionMode.FREE -> "свободная игра"
}
