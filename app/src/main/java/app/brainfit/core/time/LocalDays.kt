package app.brainfit.core.time

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Местные даты: серия дней, «сегодня», границы периода. Только для истории, не для времени реакции. */
class LocalDays(private val wallClock: WallClock, private val zone: ZoneId = ZoneId.systemDefault()) {

    fun today(): LocalDate = dayOf(wallClock.nowMillis())

    fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun startOf(day: LocalDate): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Начало и конец суток [day] в миллисекундах: [начало, конец). */
    fun bounds(day: LocalDate): Pair<Long, Long> = startOf(day) to startOf(day.plusDays(1))

    /**
     * Текущая дата; меняется в полночь, даже если экран открыт. Пока поток никто не слушает, он стоит,
     * а при новой подписке дата считается заново.
     */
    fun observe(): Flow<LocalDate> = flow {
        while (true) {
            val now = Instant.ofEpochMilli(wallClock.nowMillis()).atZone(zone)
            emit(now.toLocalDate())
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
            delay(Duration.between(now, midnight).toMillis() + 1_000)
        }
    }.distinctUntilChanged()
}
