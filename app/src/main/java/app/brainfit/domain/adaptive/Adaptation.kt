package app.brainfit.domain.adaptive

import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.LevelChange
import kotlin.math.max
import kotlin.math.min

/** Как игра повышает уровень по окну сессий. */
enum class LevelRule {
    /** Средний score окна ≥ [AdaptiveConfig.upAverage] и ни одной сессии ниже [AdaptiveConfig.upFloor]. */
    WINDOW,

    /**
     * «Двойное решение»: порог экспозиции не выше цели уровня [AdaptiveConfig.streak] сессии подряд.
     * Score там = min(1, цель / порог), так что это значит score = 1.
     */
    BELOW_TARGET_STREAK,
}

/**
 * Правила адаптации одной игры (ТЗ, раздел 6). Пороги стартовые: подгоняются после тестов на себе,
 * отдельно для каждой игры — для этого конфиг и заведён на игру.
 */
data class AdaptiveConfig(
    val upAverage: Double = 0.85,
    val upFloor: Double = 0.75,
    val downAverage: Double = 0.65,
    /** Сколько сессий в окне. */
    val window: Int = 3,
    val calibrationSessions: Int = 2,
    /** Через сколько раундов калибровка двигает уровень. В Корси раунд — одна последовательность, поэтому 4. */
    val calibrationBlock: Int = 1,
    val rule: LevelRule = LevelRule.WINDOW,
    /** Для [LevelRule.BELOW_TARGET_STREAK]: сколько сессий подряд. */
    val streak: Int = 2,
) {
    init {
        require(downAverage < upAverage && upFloor <= upAverage) { "Пороги адаптации перепутаны" }
        require(window > 0 && calibrationSessions > 0 && calibrationBlock > 0 && streak > 0)
    }
}

object AdaptiveConfigs {
    fun of(game: GameId): AdaptiveConfig = when (game) {
        GameId.CORSI -> AdaptiveConfig(calibrationBlock = 4)
        GameId.DOUBLE_DECISION -> AdaptiveConfig(rule = LevelRule.BELOW_TARGET_STREAK)
        else -> AdaptiveConfig()
    }
}

/** Решения об уровне. Уровень меняется не больше чем на 1 шаг за раз и не выходит из таблицы игры. */
object Adaptation {
    const val REASON_CALIBRATION = "calibration"
    const val REASON_WINDOW_UP = "window_up"
    const val REASON_WINDOW_DOWN = "window_down"
    const val REASON_BELOW_TARGET = "below_target"

    /** Шаг калибровки после блока раундов: ≥ upAverage — вверх, < downAverage — вниз, иначе на месте. */
    fun calibrationStep(level: Int, blockScore: Double, maxLevel: Int, config: AdaptiveConfig): Int = when {
        blockScore >= config.upAverage -> min(maxLevel, level + 1)
        blockScore < config.downAverage -> max(1, level - 1)
        else -> level
    }

    /**
     * Обычные правила. [window] — score сессий без тряски на текущем уровне после последней смены,
     * новые первыми, включая только что сыгранную. Пока сессий меньше, чем нужно правилу, уровень не меняется.
     */
    fun decide(level: Int, maxLevel: Int, window: List<Double>, config: AdaptiveConfig): LevelChange? {
        val last = window.take(config.window)
        val full = last.size == config.window
        val up = when (config.rule) {
            LevelRule.WINDOW -> full && last.average() >= config.upAverage && last.min() >= config.upFloor
            LevelRule.BELOW_TARGET_STREAK -> window.size >= config.streak && window.take(config.streak).all { it >= 1.0 - EPS }
        }
        val down = full && last.average() < config.downAverage
        return when {
            up && level < maxLevel -> LevelChange(
                level,
                level + 1,
                if (config.rule == LevelRule.WINDOW) REASON_WINDOW_UP else REASON_BELOW_TARGET,
            )
            down && level > 1 -> LevelChange(level, level - 1, REASON_WINDOW_DOWN)
            else -> null
        }
    }

    private const val EPS = 1e-9
}
