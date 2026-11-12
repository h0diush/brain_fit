package app.brainfit.domain.progress

import app.brainfit.domain.model.LevelChangeRecord
import app.brainfit.domain.model.ProgressSession

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
}
