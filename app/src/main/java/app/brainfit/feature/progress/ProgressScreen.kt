package app.brainfit.feature.progress

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import app.brainfit.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** completedSessions == null — данные ещё читаются из базы. */
data class ProgressUiState(val completedSessions: Int? = null)

@HiltViewModel
class ProgressViewModel @Inject constructor(sessions: SessionRepository) : ViewModel() {
    val state: StateFlow<ProgressUiState> = sessions.observeCompletedCount()
        .map { ProgressUiState(completedSessions = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressUiState())
}

@Composable
fun ProgressScreen(vm: ProgressViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(Dimens.screenPadding)) {
        val count = state.completedSessions ?: return@Column
        // Графики, рекорды и история уровней — этап 6. Пока честно показываем, сколько есть данных.
        BrainCard {
            SectionTitle("Недостаточно данных")
            MutedText("Завершённых сессий: $count. Графики появятся, когда наберётся хотя бы три сессии в одной игре.")
        }
    }
}
