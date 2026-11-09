package app.brainfit.core.sensors

import kotlin.math.sqrt

/**
 * Порог тряски. Стартовое значение взято с запасом над дрожанием руки в покое и подгоняется
 * на этапе 7 по замерам в реальном автобусе.
 */
data class ShakeConfig(
    /** RMS ускорения без гравитации, м/с². Выше — раунд считается сыгранным в тряске. */
    val thresholdRms: Float = 1.2f,
    /** Меньше замеров — данных нет, раунд тряским не считаем. */
    val minSamples: Int = 20,
) {
    /** Раунд тряский? null у [rms] — датчика нет или замеров слишком мало. */
    fun isShaky(rms: Float?): Boolean = rms != null && rms > thresholdRms
}

/** Копит ускорение без гравитации за раунд и считает его RMS. Чистый Kotlin. */
class ShakeMeter(private val minSamples: Int = ShakeConfig().minSamples) {
    private var sumSquares = 0.0
    private var samples = 0

    fun reset() {
        sumSquares = 0.0
        samples = 0
    }

    fun add(x: Float, y: Float, z: Float) {
        sumSquares += x.toDouble() * x + y.toDouble() * y + z.toDouble() * z
        samples++
    }

    fun rms(): Float? = if (samples < minSamples) null else sqrt(sumSquares / samples).toFloat()
}

/**
 * Убирает гравитацию из показаний обычного акселерометра (фильтр низких частот). Нужен только
 * на телефонах без датчика линейного ускорения. Первые замеры идут на оценку гравитации и не отдаются.
 */
class GravityFilter(private val alpha: Float = 0.9f, private val warmupSamples: Int = 25) {
    private val gravity = FloatArray(3)
    private var seen = 0

    fun reset() {
        gravity.fill(0f)
        seen = 0
    }

    /** Линейное ускорение или null, пока фильтр разогревается. */
    fun linear(x: Float, y: Float, z: Float): FloatArray? {
        if (seen == 0) {
            gravity[0] = x
            gravity[1] = y
            gravity[2] = z
        } else {
            gravity[0] = alpha * gravity[0] + (1 - alpha) * x
            gravity[1] = alpha * gravity[1] + (1 - alpha) * y
            gravity[2] = alpha * gravity[2] + (1 - alpha) * z
        }
        seen++
        if (seen <= warmupSamples) return null
        return floatArrayOf(x - gravity[0], y - gravity[1], z - gravity[2])
    }
}

/** Замер тряски за раунд. Реализация на датчиках — [SensorShakeMonitor]. */
interface ShakeMonitor {
    /** Начать новый замер с нуля. */
    fun start()
    fun pause()
    fun resume()

    /** Закончить замер. RMS ускорения в м/с² или null, если датчика нет или замеров мало. */
    fun stop(): Float?
}
