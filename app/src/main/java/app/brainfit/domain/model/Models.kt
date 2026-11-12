package app.brainfit.domain.model

/*
 * Перечисления хранятся в базе текстом (имя константы): переименовывать константы нельзя,
 * только добавлять новые.
 */

enum class GameId { SCHULTE, STROOP, NBACK, CORSI, MATH, DOUBLE_DECISION }

/** Группы для подбора игр в стандартную тренировку (ТЗ, раздел 5). */
enum class GameGroup { MEMORY, ATTENTION, SPEED }

enum class SessionMode { QUICK, STANDARD, FREE }

/** IN_PROGRESS — черновик: сессия идёт или процесс убили посреди неё. */
enum class SessionStatus { IN_PROGRESS, COMPLETED, ABANDONED }

enum class WorkoutMode { QUICK, STANDARD }

enum class WorkoutStatus { IN_PROGRESS, COMPLETED, ABANDONED }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Настройки приложения. Значения по умолчанию — из раздела 3 ТЗ. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val soundEnabled: Boolean = false,
    val vibrationEnabled: Boolean = true,
    val shakeDetectorEnabled: Boolean = true,
)

/** Завершённая сессия одной игры — то, что нужно спискам и сводкам. */
data class SessionSummary(
    val id: Long,
    val gameId: GameId,
    val mode: SessionMode,
    val startedAt: Long,
    val level: Int,
    val score: Double?,
)

/** Данные для создания черновика сессии. */
data class NewSession(
    val gameId: GameId,
    val mode: SessionMode,
    val level: Int,
    val isCalibration: Boolean,
    val scoringVersion: Int,
    val startedAt: Long,
    val workoutId: Long? = null,
)

/** Проба в том виде, в каком она лежит в базе. */
data class TrialRecord(
    /** Номер раунда в сессии с нуля. */
    val round: Int,
    /** Уровень раунда: на калибровке он меняется от раунда к раунду. */
    val level: Int,
    /** Сквозной номер пробы в сессии с нуля. */
    val index: Int,
    val paramsJson: String,
    val answerJson: String,
    val correct: Boolean,
    val rtMs: Long?,
    val errorType: String?,
    /** Раунд этой пробы шёл при сильной тряске. */
    val isShaky: Boolean,
)

/** Незавершённая сессия с уже сохранёнными раундами — чтобы продолжить после смерти процесса. */
data class SessionDraft(
    val id: Long,
    val gameId: GameId,
    /** Уровень, с которого сессия началась. */
    val level: Int,
    val activeMs: Long,
    val trials: List<TrialRecord>,
    val isCalibration: Boolean,
)

/** Текущий уровень сложности игры. Записи нет, пока в игру ни разу не играли. */
data class GameDifficulty(
    val gameId: GameId,
    val level: Int,
    val calibrated: Boolean,
)

/** Смена уровня. reason — код причины (см. Adaptation), хранится в базе текстом. */
data class LevelChange(val fromLevel: Int, val toLevel: Int, val reason: String)

/** Что записать об уровне игры вместе с итогом сессии. */
data class DifficultyUpdate(
    val gameId: GameId,
    val level: Int,
    val calibrated: Boolean,
    /** По порядку; на калибровке их может быть несколько за сессию. */
    val changes: List<LevelChange>,
)

/** Всё, что нужно, чтобы решить, менять ли уровень после сессии. */
data class AdaptationInput(
    val state: GameDifficulty?,
    /** Завершённые калибровочные сессии без тряски. */
    val calibrationSessions: Int,
    /**
     * Score обычных сессий без тряски на текущем уровне после последней смены уровня, новые первыми.
     * Это и есть окно: после смены уровня оно начинается заново.
     */
    val window: List<Double>,
)

/** Итог сессии: пишется одной транзакцией вместе с уровнем и пунктом тренировки. */
data class SessionFinish(
    val sessionId: Long,
    val endedAt: Long,
    val activeMs: Long,
    val score: Double,
    val metricsJson: String,
    val isShaky: Boolean,
    val difficulty: DifficultyUpdate,
)

fun WorkoutMode.sessionMode(): SessionMode = when (this) {
    WorkoutMode.QUICK -> SessionMode.QUICK
    WorkoutMode.STANDARD -> SessionMode.STANDARD
}

data class WorkoutItem(
    val order: Int,
    val gameId: GameId,
    /** Сессия, которой игра сыграна; null — ещё не сыграна или пропущена. */
    val sessionId: Long?,
    val skipped: Boolean,
    val score: Double?,
    val activeMs: Long?,
) {
    val done: Boolean get() = sessionId != null || skipped
}

data class Workout(
    val id: Long,
    /** Местная дата, число дней с эпохи. */
    val date: Long,
    val mode: WorkoutMode,
    val status: WorkoutStatus,
    val items: List<WorkoutItem>,
) {
    /** Следующая игра; null — все сыграны или пропущены. */
    val next: WorkoutItem? get() = items.firstOrNull { !it.done }
    val doneCount: Int get() = items.count { it.done }

    /** Активное время тренировки — сумма активного времени её сессий, без пауз. */
    val activeMs: Long get() = items.sumOf { it.activeMs ?: 0L }
}

/** Данные для подбора игр в новую тренировку (ТЗ, раздел 5). */
data class PlanningInput(
    /** Когда начиналась последняя завершённая сессия игры; игр без сессий здесь нет. */
    val lastPlayedAt: Map<GameId, Long>,
    /** Игры, сыгранные сегодня в тренировках. */
    val playedTodayInWorkouts: Set<GameId>,
    /** Игры, сыгранные в последней тренировке. */
    val previousWorkout: Set<GameId>,
)

/** Завершённая сессия со всем, что нужно прогрессу: метрики уже разобраны из metricsJson. */
data class ProgressSession(
    val id: Long,
    val gameId: GameId,
    val mode: SessionMode,
    val startedAt: Long,
    /** Уровень, с которого сессия началась; у обычной сессии он не меняется. */
    val level: Int,
    val isCalibration: Boolean,
    val isShaky: Boolean,
    val score: Double?,
    val metrics: Map<String, Double>,
    /** Активное время без пауз. */
    val activeMs: Long = 0,
)

/** Смена уровня из истории. */
data class LevelChangeRecord(
    val gameId: GameId,
    val fromLevel: Int,
    val toLevel: Int,
    val reason: String,
    val at: Long,
)
