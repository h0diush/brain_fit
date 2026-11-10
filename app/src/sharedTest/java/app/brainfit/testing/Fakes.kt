package app.brainfit.testing

import app.brainfit.core.sensors.ShakeMonitor
import app.brainfit.core.time.MonotonicClock
import app.brainfit.core.time.WallClock
import app.brainfit.domain.model.AdaptationInput
import app.brainfit.domain.model.AppSettings
import app.brainfit.domain.model.GameDifficulty
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.ThemeMode
import app.brainfit.domain.repository.DifficultyRepository
import app.brainfit.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Часы, которые двигает тест. */
class FakeClock(var nanos: Long = 0L, var millis: Long = 1_000_000L) : MonotonicClock, WallClock {
    override fun nowNanos() = nanos
    override fun nowMillis() = millis

    fun advanceMs(ms: Long) {
        nanos += ms * 1_000_000
        millis += ms
    }
}

class FakeShakeMonitor(var rms: Float? = 0.1f) : ShakeMonitor {
    val calls = mutableListOf<String>()

    override fun start() { calls += "start" }
    override fun pause() { calls += "pause" }
    override fun resume() { calls += "resume" }
    override fun stop(): Float? {
        calls += "stop"
        return rms
    }
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state

    override suspend fun setThemeMode(mode: ThemeMode) = state.update { it.copy(themeMode = mode) }
    override suspend fun setSoundEnabled(enabled: Boolean) = state.update { it.copy(soundEnabled = enabled) }
    override suspend fun setVibrationEnabled(enabled: Boolean) = state.update { it.copy(vibrationEnabled = enabled) }
    override suspend fun setShakeDetectorEnabled(enabled: Boolean) = state.update { it.copy(shakeDetectorEnabled = enabled) }
}

/** Уровни без истории: окно адаптации всегда пустое. Записанные уровни сюда не возвращаются. */
class FakeDifficultyRepository(levels: Map<GameId, Int> = emptyMap(), calibrated: Boolean = true) : DifficultyRepository {
    private val state = MutableStateFlow(levels.mapValues { (id, level) -> GameDifficulty(id, level, calibrated) })
    override fun observeStates(): Flow<Map<GameId, GameDifficulty>> = state

    override suspend fun adaptationInput(gameId: GameId, windowLimit: Int) =
        AdaptationInput(state.value[gameId], calibrationSessions = 0, window = emptyList())
}
