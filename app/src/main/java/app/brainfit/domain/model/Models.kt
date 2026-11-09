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
    val level: Int,
    val activeMs: Long,
    val trials: List<TrialRecord>,
)

/** Текущий уровень сложности игры. Записи нет, пока в игру ни разу не играли. */
data class GameDifficulty(
    val gameId: GameId,
    val level: Int,
    val calibrated: Boolean,
)
