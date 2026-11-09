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
import app.brainfit.core.designsystem.Dimens
import app.brainfit.core.designsystem.MutedText
import app.brainfit.core.designsystem.SectionTitle
import app.brainfit.domain.model.GameCatalog
import app.brainfit.domain.model.SessionSummary
import app.brainfit.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** today == null — данные ещё читаются из базы. */
data class HomeUiState(val today: List<SessionSummary>? = null)

@HiltViewModel
class HomeViewModel @Inject constructor(sessions: SessionRepository) : ViewModel() {

    val state: StateFlow<HomeUiState> = run {
        // Границы «сегодня» считаются при открытии экрана; смена суток на открытом экране — этап 5.
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone)
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        sessions.observeCompletedBetween(from, to)
            .map { HomeUiState(today = it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
    }
}

@Composable
fun HomeScreen(vm: HomeViewModel = hiltViewModel()) {
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
            MutedText("Быстрая и стандартная тренировки появятся здесь, когда будут готовы игры.")
        }
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
                }
            }
        }
    }
}
