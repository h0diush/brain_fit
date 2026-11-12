package app.brainfit.domain.progress

import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.ProgressSession
import app.brainfit.games.engine.Fmt
import kotlin.math.abs
import kotlin.math.roundToInt

/** Какое значение метрики лучше. */
enum class Better { HIGHER, LOWER }

/**
 * Метрика игры для графиков, рекордов и сравнения. Ключ — тот же, что игра пишет в metricsJson.
 *
 * @param speed метрика скорости: сессии с тряской в её рекорды, график и сравнение не идут (ТЗ, раздел 7).
 * @param share доля от 0 до 1: на графике шкала всегда от 0 до 100 %.
 * @param positive значение 0 и меньше — «нечего мерить» (например, ни одной найденной клетки), а не результат.
 */
data class MetricSpec(
    val key: String,
    val title: String,
    val better: Better,
    val speed: Boolean,
    val format: (Double) -> String,
    val share: Boolean = false,
    val positive: Boolean = false,
) {
    /** Значение метрики сессии; null — его нет или оно не годится в прогресс. */
    fun valueOf(session: ProgressSession): Double? =
        session.metrics[key]?.takeIf { it.isFinite() && (!positive || it > 0.0) }

    fun isBetter(candidate: Double, than: Double): Boolean = when (better) {
        Better.HIGHER -> candidate > than
        Better.LOWER -> candidate < than
    }

    /** Сессия годится для рекордов и сравнения: после калибровки, без тряски для скорости, со значением. */
    fun counts(session: ProgressSession): Boolean =
        !session.isCalibration && !(speed && session.isShaky) && valueOf(session) != null

    /** Сессия попадает на график: калибровочные тоже, но тряские — нет, если это скорость. */
    fun charted(session: ProgressSession): Boolean =
        !(speed && session.isShaky) && valueOf(session) != null
}

/** Метрики прогресса по играм. Первая — главная (ТЗ, раздел 4): на карточке игры и в сравнении. */
object ProgressMetrics {
    private val accuracy = MetricSpec("accuracy", "Точность", Better.HIGHER, speed = false, format = Fmt::percent, share = true)

    private val schulte = listOf(
        MetricSpec(
            "msPerCell", "Время на ячейку", Better.LOWER, speed = true, positive = true,
            format = { Fmt.decimal(it / 1000.0, 2) + " с" },
        ),
        accuracy,
    )
    private val stroop = listOf(
        accuracy,
        MetricSpec(
            "stroopEffectMs", "Струп-эффект", Better.LOWER, speed = true,
            format = { "${if (it >= 0) "+" else "−"}${abs(it).roundToInt()} мс" },
        ),
    )
    private val nback = listOf(
        accuracy,
        MetricSpec("dPrime", "d′", Better.HIGHER, speed = false, format = { Fmt.decimal(it, 2) }),
    )
    private val corsi = listOf(
        MetricSpec("span", "Длина последовательности", Better.HIGHER, speed = false, positive = true, format = { "${it.roundToInt()}" }),
    )
    private val math = listOf(
        MetricSpec("correctPerMinute", "Верных в минуту", Better.HIGHER, speed = true, format = { Fmt.decimal(it) }),
        accuracy,
    )
    private val doubleDecision = listOf(
        MetricSpec("thresholdMs", "Порог экспозиции", Better.LOWER, speed = true, positive = true, format = { "${it.roundToInt()} мс" }),
    )

    fun of(game: GameId): List<MetricSpec> = when (game) {
        GameId.SCHULTE -> schulte
        GameId.STROOP -> stroop
        GameId.NBACK -> nback
        GameId.CORSI -> corsi
        GameId.MATH -> math
        GameId.DOUBLE_DECISION -> doubleDecision
    }

    fun main(game: GameId): MetricSpec = of(game).first()
}
