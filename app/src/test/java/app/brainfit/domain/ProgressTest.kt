package app.brainfit.domain

import app.brainfit.domain.adaptive.Adaptation
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.LevelChangeRecord
import app.brainfit.domain.model.ProgressSession
import app.brainfit.domain.model.SessionMode
import app.brainfit.domain.progress.LevelStep
import app.brainfit.domain.progress.Progress
import app.brainfit.domain.progress.ProgressMetrics
import app.brainfit.domain.progress.Record
import app.brainfit.domain.progress.Streak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTest {

    private val msPerCell = ProgressMetrics.main(GameId.SCHULTE)
    private val schulteAccuracy = ProgressMetrics.of(GameId.SCHULTE)[1]

    private var nextId = 1L

    private fun session(
        at: Long,
        level: Int = 2,
        ms: Double? = 800.0,
        accuracy: Double = 0.9,
        calibration: Boolean = false,
        shaky: Boolean = false,
        game: GameId = GameId.SCHULTE,
    ) = ProgressSession(
        id = nextId++,
        gameId = game,
        mode = SessionMode.FREE,
        startedAt = at,
        level = level,
        isCalibration = calibration,
        isShaky = shaky,
        score = 0.8,
        metrics = buildMap {
            if (ms != null) put("msPerCell", ms)
            put("accuracy", accuracy)
        },
    )

    @Test
    fun recordsAreKeptPerLevelWithoutCalibrationAndShakySpeed() {
        val sessions = listOf(
            session(at = 1, level = 2, ms = 900.0),
            session(at = 2, level = 2, ms = 700.0),
            // Быстрее, но калибровка: уровень в ней менялся на ходу.
            session(at = 3, level = 2, ms = 500.0, calibration = true),
            // Быстрее, но тряска: в рекорды скорости не идёт.
            session(at = 4, level = 2, ms = 400.0, shaky = true, accuracy = 1.0),
            session(at = 5, level = 3, ms = 1_200.0),
            // 0 — ни одной найденной клетки: это не результат.
            session(at = 6, level = 3, ms = 0.0),
        )
        assertEquals(
            listOf(Record(level = 2, value = 700.0, sessionId = 2, at = 2), Record(level = 3, value = 1_200.0, sessionId = 5, at = 5)),
            Progress.records(sessions, msPerCell),
        )
        // Точность — не скорость: тряская сессия в её рекорды идёт.
        assertEquals(1.0, Progress.record(sessions, schulteAccuracy, level = 2)!!.value, 1e-9)
        assertNull(Progress.record(sessions, msPerCell, level = 4))
    }

    @Test
    fun recordTieStaysWithEarlierSession() {
        val sessions = listOf(session(at = 10, ms = 600.0), session(at = 20, ms = 600.0))
        assertEquals(10L, Progress.records(sessions, msPerCell).single().at)
    }

    @Test
    fun periodCountsSessionsAndChartsOnlyUsableOnes() {
        val sessions = listOf(
            session(at = 50),
            session(at = 100, calibration = true),
            session(at = 200, shaky = true),
            session(at = 300, ms = null),
            session(at = 400),
        )
        val summary = Progress.period(sessions, msPerCell, fromMillis = 100)
        assertEquals(4, summary.sessions)
        assertEquals(1, summary.shaky)
        // Калибровка на графике есть (отмечена), тряская и без значения — нет.
        assertEquals(listOf(100L, 400L), summary.points.map { it.at })
        assertEquals(listOf(true, false), summary.points.map { it.calibration })
        assertFalse(summary.enoughData)

        val accuracy = Progress.period(sessions, schulteAccuracy, fromMillis = 100)
        assertEquals(4, accuracy.points.size)
        assertTrue(accuracy.enoughData)
    }

    @Test
    fun streakCountsConsecutiveDaysWithoutPunishingToday() {
        assertEquals(Streak(current = 0, best = 0, playedToday = false), Progress.streak(emptyList(), today = 100))
        // Повторы в один день не удлиняют серию.
        assertEquals(Streak(3, 3, true), Progress.streak(listOf(98, 99, 100, 100), today = 100))
        // Сегодня ещё не играли — вчерашняя серия жива.
        assertEquals(Streak(2, 2, false), Progress.streak(listOf(98, 99), today = 100))
        // Пропущен целый день — серия начинается заново, лучшая помнится.
        assertEquals(Streak(0, 4, false), Progress.streak(listOf(90, 91, 92, 93, 97), today = 100))
        assertEquals(Streak(1, 4, true), Progress.streak(listOf(90, 91, 92, 93, 97, 100), today = 100))
    }

    @Test
    fun levelStepsMergeOneSessionAndKeepTheWholePath() {
        fun change(from: Int, to: Int, at: Long, reason: String) = LevelChangeRecord(GameId.CORSI, from, to, reason, at)
        val steps = Progress.levelSteps(
            listOf(
                change(2, 3, 10, Adaptation.REASON_CALIBRATION),
                change(3, 2, 10, Adaptation.REASON_CALIBRATION),
                change(2, 1, 20, Adaptation.REASON_CALIBRATION),
                change(1, 2, 30, "window_up"),
            ),
            Adaptation.REASON_CALIBRATION,
        )
        assertEquals(
            listOf(
                LevelStep(10, listOf(2, 3, 2), calibration = true),
                LevelStep(20, listOf(2, 1), calibration = true),
                LevelStep(30, listOf(1, 2), calibration = false),
            ),
            steps,
        )
    }

    @Test
    fun comparisonWithPreviousSessionsOnTheSameLevel() {
        val previous = listOf(
            session(at = 1, level = 2, ms = 700.0),
            session(at = 2, level = 2, ms = 900.0),
            // Другой уровень и калибровка — не сравниваем.
            session(at = 3, level = 3, ms = 300.0),
            session(at = 4, level = 2, ms = 100.0, calibration = true),
        )
        fun compare(value: Double?, level: Int = 2, calibration: Boolean = false, shaky: Boolean = false) =
            Progress.comparison(msPerCell, value, level, calibration, shaky, previous)

        assertEquals(listOf("Время на ячейку в прошлый раз — 0,90 с, сейчас лучше.", "Новый рекорд уровня 2!"), compare(650.0))
        assertEquals(listOf("Время на ячейку в прошлый раз — 0,90 с, сейчас лучше.", "Рекорд уровня 2 — 0,70 с."), compare(800.0))
        assertEquals(listOf("Время на ячейку в прошлый раз — 0,90 с, сейчас хуже.", "Рекорд уровня 2 — 0,70 с."), compare(950.0))
        // Разница меньше видимой точности — «так же», а не «лучше».
        assertEquals("Время на ячейку в прошлый раз — 0,90 с, так же.", compare(899.0).first())
        assertEquals(listOf("Первая сессия на уровне 4 — сравнивать пока не с чем."), compare(800.0, level = 4))
        assertTrue(compare(800.0, calibration = true).isEmpty())
        assertTrue(compare(800.0, shaky = true).isEmpty())
        assertTrue(compare(null).isEmpty())
        assertTrue(compare(0.0).isEmpty())
    }

    @Test
    fun everyGameHasMetricsAndFormatsAreRussian() {
        GameId.entries.forEach { assertTrue(ProgressMetrics.of(it).isNotEmpty()) }
        assertEquals("87 %", ProgressMetrics.main(GameId.STROOP).format(0.87))
        assertEquals("−35 мс", ProgressMetrics.of(GameId.STROOP)[1].format(-35.2))
        assertEquals("+120 мс", ProgressMetrics.of(GameId.STROOP)[1].format(120.0))
        assertEquals("5", ProgressMetrics.main(GameId.CORSI).format(5.0))
        assertEquals("23,5", ProgressMetrics.main(GameId.MATH).format(23.46))
        assertEquals("117 мс", ProgressMetrics.main(GameId.DOUBLE_DECISION).format(116.7))
        assertEquals("1,25", ProgressMetrics.of(GameId.NBACK)[1].format(1.249))
    }

    @Test
    fun dailyCountsCoverEveryDayOfThePeriod() {
        assertEquals(listOf(2, 0, 1), Progress.dailyCounts(listOf(9, 10, 10, 12, 13), fromDay = 10, toDay = 12))
        assertTrue(Progress.dailyCounts(listOf(1), fromDay = 5, toDay = 4).isEmpty())
    }

    @Test
    fun trendComparesMediansOnTheLatestLevel() {
        val sessions = listOf(
            // Прошлый период, уровень 2: медиана 900.
            session(at = 10, ms = 1_000.0), session(at = 11, ms = 900.0), session(at = 12, ms = 800.0),
            // Текущий период, уровень 2: медиана 720; калибровка и тряская — мимо.
            session(at = 100, ms = 700.0), session(at = 101, ms = 720.0), session(at = 102, ms = 760.0),
            session(at = 103, ms = 100.0, calibration = true), session(at = 104, ms = 100.0, shaky = true),
        )
        val trend = Progress.trend(sessions, msPerCell, currentFrom = 100, previousFrom = 0)!!
        assertEquals(2, trend.level)
        assertEquals(720.0, trend.current, 1e-9)
        assertEquals(900.0, trend.previous, 1e-9)
        assertEquals("лучше на 20 %" to true, Progress.trendText(msPerCell, trend))

        // Последняя сессия — на уровне 3, а там прошлого периода нет.
        assertNull(Progress.trend(sessions + session(at = 105, level = 3), msPerCell, currentFrom = 100, previousFrom = 0))
        // Мало сессий в прошлом периоде.
        assertNull(Progress.trend(sessions.drop(1), msPerCell, currentFrom = 100, previousFrom = 0))
    }

    @Test
    fun trendTextUsesPointsForSharesAndSaysSameWhenInvisible() {
        val share = schulteAccuracy
        assertEquals("хуже на 5 п. п." to false, Progress.trendText(share, app.brainfit.domain.progress.Trend(2, 0.85, 0.90, 3, 3)))
        assertEquals("лучше на 1 п. п." to true, Progress.trendText(share, app.brainfit.domain.progress.Trend(2, 0.876, 0.874, 3, 3)))
        assertEquals("так же" to null, Progress.trendText(msPerCell, app.brainfit.domain.progress.Trend(2, 801.0, 799.0, 3, 3)))
    }

    @Test
    fun recentScoresAreTheLastOnesInOrder() {
        val sessions = (1..12).map { session(at = it.toLong()).copy(score = it / 100.0) }.shuffled(kotlin.random.Random(1))
        assertEquals((3..12).map { it / 100.0 }, Progress.recentScores(sessions))
    }
}
