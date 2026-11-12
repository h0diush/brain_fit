package app.brainfit.domain.progress

import app.brainfit.domain.model.LevelChangeRecord
import app.brainfit.domain.model.ProgressSession
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Периоды прогресса (ТЗ, раздел 7). */
enum class ProgressPeriod(val days: Int) { WEEK(7), MONTH(30), QUARTER(90) }

/** Меньше стольких точек за период — «Недостаточно данных», графика нет. */
const val MIN_SESSIONS_FOR_CHART = 3

/** Лучшее значение метрики на одном уровне. */
data class Record(val level: Int, val value: Double, val sessionId: Long, val at: Long)

/** Точка графика — одна сессия. */
data class ChartPoint(
    val sessionId: Long,
    val at: Long,
    val level: Int,
    val value: Double,
    val calibration: Boolean,
)

/** Что за период известно об одной метрике. */
data class PeriodSummary(
    /** Все завершённые сессии игры за период. */
    val sessions: Int,
    /** Из них с тряской. */
    val shaky: Int,
    val points: List<ChartPoint>,
) {
    val enoughData: Boolean get() = points.size >= MIN_SESSIONS_FOR_CHART
}

/**
 * Сравнение с прошлым периодом той же длины: медианы главной метрики на одном уровне. Медиана, а не
 * среднее, чтобы одна неудачная сессия не перевешивала.
 */
data class Trend(val level: Int, val current: Double, val previous: Double, val currentCount: Int, val previousCount: Int)

/** Серия дней подряд хотя бы с одной сессией. */
data class Streak(val current: Int, val best: Int, val playedToday: Boolean)

/**
 * Шаг истории уровней — одна сессия. На калибровке за сессию уровень может сдвинуться несколько раз,
 * поэтому шаг хранит весь путь: 2 → 3 → 2. Ничего не скрываем.
 */
data class LevelStep(val at: Long, val levels: List<Int>, val calibration: Boolean) {
    val fromLevel: Int get() = levels.first()
    val toLevel: Int get() = levels.last()
}

/** Расчёты прогресса. Только чистые функции: всё выводится из сырых сессий и смен уровня. */
object Progress {

    /**
     * Рекорды метрики по уровням, по возрастанию уровня. Уровни сравнивать между собой нельзя (3×3 и 6×6
     * у Шульте — разные задачи), поэтому рекорд у каждого уровня свой. Калибровочные сессии не идут:
     * уровень в них меняется на ходу. Тряские не идут в рекорды скорости. При равенстве рекорд остаётся
     * за более ранней сессией.
     */
    fun records(sessions: List<ProgressSession>, metric: MetricSpec): List<Record> =
        sessions.asSequence()
            .filter(metric::counts)
            .sortedWith(compareBy({ it.startedAt }, { it.id }))
            .groupBy { it.level }
            .map { (level, list) ->
                val best = list.reduce { best, s -> if (metric.isBetter(metric.valueOf(s)!!, metric.valueOf(best)!!)) s else best }
                Record(level, metric.valueOf(best)!!, best.id, best.startedAt)
            }
            .sortedBy { it.level }

    fun record(sessions: List<ProgressSession>, metric: MetricSpec, level: Int): Record? =
        records(sessions, metric).firstOrNull { it.level == level }

    /** Сводка за период: сессии с началом не раньше [fromMillis]. */
    fun period(sessions: List<ProgressSession>, metric: MetricSpec, fromMillis: Long): PeriodSummary {
        val inPeriod = sessions.filter { it.startedAt >= fromMillis }.sortedWith(compareBy({ it.startedAt }, { it.id }))
        return PeriodSummary(
            sessions = inPeriod.size,
            shaky = inPeriod.count { it.isShaky },
            points = inPeriod.filter(metric::charted).map {
                ChartPoint(it.id, it.startedAt, it.level, metric.valueOf(it)!!, it.isCalibration)
            },
        )
    }

    /**
     * Серия по местным датам (дни с эпохи). Сегодня ещё не играли, но вчера играли — серия не прервана:
     * день не кончился. Наказаний нет: пропуск просто начинает серию заново.
     */
    fun streak(days: Collection<Long>, today: Long): Streak {
        val set = days.toSortedSet()
        var best = 0
        var run = 0
        var previous: Long? = null
        for (day in set) {
            run = if (previous != null && day == previous + 1) run + 1 else 1
            best = maxOf(best, run)
            previous = day
        }
        val playedToday = today in set
        var current = 0
        var day = if (playedToday) today else today - 1
        while (day in set) {
            current++
            day--
        }
        return Streak(current, best, playedToday)
    }

    /**
     * История уровней по шагам: смены с одним временем записаны одной сессией и сливаются в один шаг.
     * Шаг калибровочный, если все его смены — калибровка.
     */
    fun levelSteps(changes: List<LevelChangeRecord>, calibrationReason: String): List<LevelStep> =
        changes.sortedBy { it.at }
            .fold(mutableListOf<MutableList<LevelChangeRecord>>()) { groups, change ->
                val last = groups.lastOrNull()
                if (last != null && last.first().at == change.at) last += change else groups += mutableListOf(change)
                groups
            }
            .map { group ->
                LevelStep(
                    at = group.first().at,
                    levels = listOf(group.first().fromLevel) + group.map { it.toLevel },
                    calibration = group.all { it.reason == calibrationReason },
                )
            }

    /**
     * Сравнение только что сыгранной сессии с прошлыми на том же уровне — строки для экрана результата.
     * [previous] — завершённые сессии игры до этой. Калибровку и тряскую сессию скорости не сравниваем:
     * про них уже сказано в строках об уровне и о тряске.
     */
    fun comparison(
        metric: MetricSpec,
        value: Double?,
        level: Int,
        isCalibration: Boolean,
        isShaky: Boolean,
        previous: List<ProgressSession>,
    ): List<String> {
        if (value == null || !value.isFinite() || isCalibration || (metric.speed && isShaky)) return emptyList()
        if (metric.positive && value <= 0.0) return emptyList()
        val earlier = previous
            .filter { it.level == level && metric.counts(it) }
            .sortedWith(compareBy({ it.startedAt }, { it.id }))
        if (earlier.isEmpty()) return listOf("Первая сессия на уровне $level — сравнивать пока не с чем.")

        val now = metric.format(value)
        val last = metric.valueOf(earlier.last())!!
        val verdict = when {
            metric.format(last) == now -> "так же"
            metric.isBetter(value, last) -> "сейчас лучше"
            else -> "сейчас хуже"
        }
        val best = records(earlier, metric).single()
        val recordLine = if (metric.isBetter(value, best.value) && metric.format(best.value) != now) {
            "Новый рекорд уровня $level!"
        } else {
            "Рекорд уровня $level — ${metric.format(best.value)}."
        }
        return listOf("${metric.title} в прошлый раз — ${metric.format(last)}, $verdict.", recordLine)
    }

    /**
     * Сколько сессий в каждый день с [fromDay] по [toDay] включительно (дни с эпохи). [days] — дни сессий,
     * по одному на сессию; дни вне промежутка не считаются.
     */
    fun dailyCounts(days: List<Long>, fromDay: Long, toDay: Long): List<Int> {
        if (toDay < fromDay) return emptyList()
        val counts = IntArray((toDay - fromDay + 1).toInt())
        days.forEach { day -> if (day in fromDay..toDay) counts[(day - fromDay).toInt()]++ }
        return counts.toList()
    }

    /**
     * Тренд метрики: текущий период [[currentFrom], ∞) против прошлого [[previousFrom], [currentFrom]).
     * Уровень — тот, на котором последняя подходящая сессия текущего периода: уровни между собой не сравнить.
     * Сессии те же, что для рекордов ([MetricSpec.counts]). В каждом периоде нужно хотя бы
     * [MIN_SESSIONS_FOR_CHART] сессий на этом уровне, иначе null.
     */
    fun trend(sessions: List<ProgressSession>, metric: MetricSpec, currentFrom: Long, previousFrom: Long): Trend? {
        val usable = sessions.filter(metric::counts).sortedWith(compareBy({ it.startedAt }, { it.id }))
        val level = usable.lastOrNull { it.startedAt >= currentFrom }?.level ?: return null
        val current = usable.filter { it.level == level && it.startedAt >= currentFrom }.map { metric.valueOf(it)!! }
        val previous = usable.filter { it.level == level && it.startedAt in previousFrom until currentFrom }.map { metric.valueOf(it)!! }
        if (current.size < MIN_SESSIONS_FOR_CHART || previous.size < MIN_SESSIONS_FOR_CHART) return null
        return Trend(level, median(current), median(previous), current.size, previous.size)
    }

    /** Результаты (score) последних [limit] сессий по порядку — для мини-графика на карточке игры. */
    fun recentScores(sessions: List<ProgressSession>, limit: Int = 10): List<Double> =
        sessions.sortedWith(compareBy({ it.startedAt }, { it.id })).mapNotNull { it.score }.takeLast(limit)

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /**
     * Тренд словами: «лучше на 12 %» и направление (true — лучше, false — хуже, null — так же).
     * У долей разница в процентных пунктах: «точность лучше на 5 п. п.», а не «на 6 %».
     */
    fun trendText(metric: MetricSpec, trend: Trend): Pair<String, Boolean?> {
        if (metric.format(trend.current) == metric.format(trend.previous)) return "так же" to null
        val better = metric.isBetter(trend.current, trend.previous)
        val word = if (better) "лучше" else "хуже"
        val size = when {
            // Разница видимых процентов: 87 % и 88 % — «на 1 п. п.», а не «на 0».
            metric.share -> "${abs((trend.current * 100).roundToInt() - (trend.previous * 100).roundToInt())} п. п."
            trend.previous == 0.0 -> metric.format(abs(trend.current - trend.previous))
            else -> "${max(1, (abs(trend.current - trend.previous) / abs(trend.previous) * 100).roundToInt())} %"
        }
        return "$word на $size" to better
    }
}
