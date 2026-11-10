package app.brainfit.games.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Статистика для оценщиков игр. Чистый Kotlin. */
object Stats {

    /** Медиана; для пустого списка — null. */
    fun median(values: List<Long>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** Среднее геометрическое положительных чисел; для пустого списка — null. */
    fun geometricMean(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        require(values.all { it > 0 }) { "Среднее геометрическое только для положительных: $values" }
        return exp(values.sumOf { ln(it) } / values.size)
    }

    /**
     * Обратная функция стандартного нормального распределения (z-оценка) для p в (0, 1).
     * Алгоритм Акклама, относительная ошибка меньше 1,15e-9 — для d′ с запасом.
     */
    fun normalQuantile(p: Double): Double {
        require(p > 0.0 && p < 1.0) { "p должно быть в (0, 1): $p" }
        val a = doubleArrayOf(-3.969683028665376e1, 2.209460984245205e2, -2.759285104469687e2, 1.383577518672690e2, -3.066479806614716e1, 2.506628277459239)
        val b = doubleArrayOf(-5.447609879822406e1, 1.615858368580409e2, -1.556989798598866e2, 6.680131188771972e1, -1.328068155288572e1)
        val c = doubleArrayOf(-7.784894002430293e-3, -3.223964580411365e-1, -2.400758277161838, -2.549732539343734, 4.374664141464968, 2.938163982698783)
        val d = doubleArrayOf(7.784695709041462e-3, 3.224671290700398e-1, 2.445134137142996, 3.754408661907416)
        val low = 0.02425
        return when {
            p < low -> {
                val q = sqrt(-2 * ln(p))
                (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)
            }
            p > 1 - low -> -normalQuantile(1 - p)
            else -> {
                val q = p - 0.5
                val r = q * q
                (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                    (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1)
            }
        }
    }

    /**
     * d′ с поправкой log-linear (Hautus, 1995): к попаданиям и ложным тревогам прибавляется 0,5,
     * к числу целей и не-целей — 1. Так d′ конечен и при 100 % попаданий или 0 ложных тревог.
     */
    fun dPrime(hits: Int, targets: Int, falseAlarms: Int, nonTargets: Int): Double {
        val hitRate = (hits + 0.5) / (targets + 1)
        val faRate = (falseAlarms + 0.5) / (nonTargets + 1)
        return normalQuantile(hitRate) - normalQuantile(faRate)
    }
}
