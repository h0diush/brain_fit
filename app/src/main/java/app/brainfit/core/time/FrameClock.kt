package app.brainfit.core.time

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Перевод длительностей в кадры экрана. Короткие показы (экспозиция в «Двойном решении») задаются
 * целым числом кадров: показать стимул на 40 мс при 60 Гц нельзя, можно на 2 кадра = 33,3 мс или на 3 = 50 мс.
 */
object FrameClock {
    const val NANOS_PER_MS = 1_000_000L
    private const val NANOS_PER_SECOND = 1_000_000_000.0

    fun frameNanos(refreshHz: Float): Long {
        require(refreshHz > 0f) { "Частота экрана должна быть положительной: $refreshHz" }
        return (NANOS_PER_SECOND / refreshHz).toLong()
    }

    /** Ближайшее целое число кадров для длительности, но не меньше одного. */
    fun framesFor(durationMs: Double, refreshHz: Float): Int {
        require(refreshHz > 0f) { "Частота экрана должна быть положительной: $refreshHz" }
        return max(1, (durationMs * refreshHz / 1000.0).roundToInt())
    }

    fun durationMs(frames: Int, refreshHz: Float): Double {
        require(refreshHz > 0f) { "Частота экрана должна быть положительной: $refreshHz" }
        return frames * 1000.0 / refreshHz
    }

    fun nanosToMs(nanos: Long): Long = nanos / NANOS_PER_MS
}
