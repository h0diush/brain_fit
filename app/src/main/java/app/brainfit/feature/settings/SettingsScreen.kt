package app.brainfit.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.brainfit.BuildConfig
import app.brainfit.core.designsystem.BrainCard
import app.brainfit.core.designsystem.BrainTheme
import app.brainfit.core.designsystem.Dimens
import app.brainfit.core.designsystem.MutedText
import app.brainfit.core.designsystem.SectionTitle
import app.brainfit.domain.model.ThemeMode
import app.brainfit.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** themeMode == null — настройки ещё читаются. */
data class SettingsUiState(
    val themeMode: ThemeMode? = null,
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<SettingsUiState> = combine(
        // Ошибку чтения показывает MainActivity; здесь без неё просто нечего отметить в списке.
        settings.settings.map<_, ThemeMode?> { it.themeMode }.catch { emit(null) },
        error,
    ) { mode, err -> SettingsUiState(themeMode = mode, error = err) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch {
            try {
                settings.setThemeMode(mode)
                error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = "Не удалось сохранить настройку: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
}

private val themeLabels = listOf(
    ThemeMode.SYSTEM to "Как в системе",
    ThemeMode.DARK to "Тёмная",
    ThemeMode.LIGHT to "Светлая",
)

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.gap),
    ) {
        BrainCard {
            SectionTitle("Тема")
            Column(Modifier.selectableGroup()) {
                themeLabels.forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimens.minTouch)
                            .selectable(
                                selected = state.themeMode == mode,
                                role = Role.RadioButton,
                                onClick = { vm.setTheme(mode) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = state.themeMode == mode, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            state.error?.let {
                Text(it, color = BrainTheme.colors.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
        BrainCard {
            SectionTitle("О приложении")
            MutedText("BrainFit ${BuildConfig.VERSION_NAME}")
            MutedText(
                "Короткие игры на память, внимание и скорость. Работает без интернета, " +
                    "без регистрации, рекламы и аналитики. Все данные хранятся только на этом телефоне.",
            )
            MutedText(
                "Приложение не медицинское. Оно показывает, как меняется твой результат в каждой игре, " +
                    "и не обещает роста IQ, успехов в работе или учёбе и профилактики заболеваний.",
            )
        }
    }
}
