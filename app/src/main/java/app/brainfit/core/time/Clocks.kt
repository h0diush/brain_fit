package app.brainfit.core.time

import kotlin.math.max

/**
 * Монотонные часы в наносекундах. На Android это System.nanoTime: та же шкала, что у времени кадров
 * (withFrameNanos), поэтому нажатия и кадры можно сравнивать между собой. Для времени реакции —
 * только они, никогда не часы устройства.
 */
fun interface MonotonicClock {
    fun nowNanos(): Long
}

/** Обычные часы устройства: только для дат в истории (когда сыграна сессия). */
fun interface WallClock {
    fun nowMillis(): Long
}

/** Секундомер с паузой: считает только активное время. Время назад не идёт, даже если отметки пришли не по порядку. */
class Stopwatch {
    private var startedAt = 0L
    private var accumulated = 0L
    private var running = false

    val isRunning: Boolean get() = running

    fun restart(nowNanos: Long) {
        accumulated = 0L
        startedAt = nowNanos
        running = true
    }

    fun pause(nowNanos: Long) {
        if (!running) return
        accumulated += max(0L, nowNanos - startedAt)
        running = false
    }

    fun resume(nowNanos: Long) {
        if (running) return
        startedAt = nowNanos
        running = true
    }

    fun elapsed(nowNanos: Long): Long =
        accumulated + if (running) max(0L, nowNanos - startedAt) else 0L
}
