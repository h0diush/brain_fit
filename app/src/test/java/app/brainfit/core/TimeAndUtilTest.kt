package app.brainfit.core

import app.brainfit.core.sensors.GravityFilter
import app.brainfit.core.sensors.ShakeConfig
import app.brainfit.core.sensors.ShakeMeter
import app.brainfit.core.time.FrameClock
import app.brainfit.core.time.Stopwatch
import app.brainfit.core.util.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class StopwatchTest {

    @Test
    fun countsOnlyActiveTime() {
        val sw = Stopwatch()
        sw.restart(1_000)
        assertEquals(500, sw.elapsed(1_500))
        sw.pause(1_500)
        assertEquals(500, sw.elapsed(9_000))
        sw.resume(9_000)
        assertEquals(700, sw.elapsed(9_200))
    }

    @Test
    fun repeatedPauseAndResumeAreIgnored() {
        val sw = Stopwatch()
        sw.restart(0)
        sw.pause(100)
        sw.pause(500)
        sw.resume(1_000)
        sw.resume(2_000)
        assertEquals(300, sw.elapsed(1_200))
    }

    @Test
    fun neverGoesBackwards() {
        val sw = Stopwatch()
        sw.restart(1_000)
        assertEquals(0, sw.elapsed(900))
        sw.pause(900)
        assertEquals(0, sw.elapsed(5_000))
    }

    @Test
    fun restartDropsAccumulatedTime() {
        val sw = Stopwatch()
        sw.restart(0)
        sw.pause(400)
        sw.restart(1_000)
        assertEquals(50, sw.elapsed(1_050))
        assertTrue(sw.isRunning)
    }
}

class FrameClockTest {

    @Test
    fun framesAreRoundedToNearest() {
        assertEquals(30, FrameClock.framesFor(500.0, 60f))
        assertEquals(45, FrameClock.framesFor(500.0, 90f))
        assertEquals(60, FrameClock.framesFor(500.0, 120f))
        // 40 мс при 60 Гц — это 2,4 кадра: показываем 2.
        assertEquals(2, FrameClock.framesFor(40.0, 60f))
        assertEquals(5, FrameClock.framesFor(40.0, 120f))
    }

    @Test
    fun neverLessThanOneFrame() {
        assertEquals(1, FrameClock.framesFor(1.0, 60f))
        assertEquals(1, FrameClock.framesFor(0.0, 120f))
    }

    @Test
    fun durationOfFrames() {
        assertEquals(16.667, FrameClock.durationMs(1, 60f), 0.001)
        assertEquals(8.333, FrameClock.durationMs(1, 120f), 0.001)
        assertEquals(16_666_666L, FrameClock.frameNanos(60f))
        assertEquals(3L, FrameClock.nanosToMs(3_999_999))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroRefreshRateIsRejected() {
        FrameClock.framesFor(100.0, 0f)
    }
}

class ShakeTest {

    @Test
    fun rmsOfConstantAcceleration() {
        val meter = ShakeMeter(minSamples = 5)
        repeat(10) { meter.add(3f, 0f, 4f) }
        assertEquals(5f, meter.rms()!!, 1e-4f)
    }

    @Test
    fun rmsMixesQuietAndShakySamples() {
        val meter = ShakeMeter(minSamples = 2)
        meter.add(0f, 0f, 0f)
        meter.add(2f, 0f, 0f)
        assertEquals(sqrt(2f), meter.rms()!!, 1e-4f)
    }

    @Test
    fun tooFewSamplesGiveNoAnswer() {
        val meter = ShakeMeter(minSamples = 20)
        repeat(19) { meter.add(9f, 9f, 9f) }
        assertNull(meter.rms())
        meter.add(9f, 9f, 9f)
        assertNotNull(meter.rms())
        meter.reset()
        assertNull(meter.rms())
    }

    @Test
    fun thresholdDecidesShaky() {
        val config = ShakeConfig(thresholdRms = 1.2f)
        assertFalse(config.isShaky(null))
        assertFalse(config.isShaky(1.2f))
        assertTrue(config.isShaky(1.21f))
    }

    /** Телефон лежит неподвижно: после разогрева фильтр отдаёт почти ноль, а не 9,8. */
    @Test
    fun gravityFilterRemovesGravity() {
        val filter = GravityFilter(warmupSamples = 25)
        var last: FloatArray? = null
        repeat(25) { assertNull(filter.linear(0f, 0f, 9.81f)) }
        repeat(50) { last = filter.linear(0f, 0f, 9.81f) }
        assertTrue(abs(last!![2]) < 0.01f)
    }

    @Test
    fun gravityFilterKeepsFastShaking() {
        val filter = GravityFilter(warmupSamples = 25)
        val meter = ShakeMeter(minSamples = 10)
        repeat(200) { i ->
            val shake = if (i % 2 == 0) 3f else -3f
            filter.linear(shake, 0f, 9.81f)?.let { meter.add(it[0], it[1], it[2]) }
        }
        // Быстрая тряска ±3 м/с² почти целиком проходит через фильтр.
        assertTrue(meter.rms()!! > 2.5f)
    }
}

class JsonTest {

    @Test
    fun writesScalarsAndNesting() {
        assertEquals(
            """{"a":1,"b":true,"c":null,"d":"x","e":[1,2],"f":{"g":1.5}}""",
            Json.obj("a" to 1, "b" to true, "c" to null, "d" to "x", "e" to listOf(1, 2), "f" to mapOf("g" to 1.5)),
        )
    }

    @Test
    fun escapesStrings() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", Json.encode("a\"b\\c\nd\u0001"))
        assertEquals("\"Привет\"", Json.encode("Привет"))
    }

    @Test
    fun nonFiniteNumbersBecomeNull() {
        assertEquals("[null,null,0.5]", Json.encode(listOf(Double.NaN, Float.POSITIVE_INFINITY, 0.5)))
    }

    @Test
    fun emptyContainers() {
        assertEquals("{}", Json.encode(emptyMap<String, Any>()))
        assertEquals("[]", Json.encode(emptyList<Int>()))
        assertEquals("[1,2]", Json.encode(intArrayOf(1, 2)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownTypesAreRejected() {
        Json.encode(Any())
    }
}
