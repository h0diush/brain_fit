package app.brainfit.games.engine

import app.brainfit.domain.model.GameId
import kotlin.random.Random

/** Строка таблицы уровней игры. Уровни нумеруются с 1: уровень N — это `levels[N - 1]`. */
interface LevelConfig {
    val level: Int
}

/** Что делать с раундом после паузы. */
enum class ResumePolicy {
    /** Вернуться ровно в то же место. */
    CONTINUE,

    /** Начать раунд заново: в играх на память с жёстким темпом (N-back) пауза ломает сам раунд. */
    RESTART_ROUND,
}

/** Одна проба: задание, ответ и результат. Сохраняется в базу как есть — это источник истины. */
data class TrialResult(
    val paramsJson: String,
    val answerJson: String,
    val correct: Boolean,
    /** Время реакции по монотонным часам; null — ответа не было. */
    val rtMs: Long?,
    val errorType: String? = null,
)

/** Оценка сессии: score от 0 до 1 для адаптации сложности и метрики игры для прогресса. */
data class SessionScore(
    val score: Double,
    val metrics: Map<String, Double> = emptyMap(),
) {
    init {
        require(score in 0.0..1.0) { "score должен быть от 0 до 1: $score" }
    }
}

/**
 * Контракт игры. Игра — чистые функции без Android и без своих часов: движок сам ведёт время,
 * паузы, раунды и запись в базу.
 *
 * Время. Во все функции приходит `nowNanos` — активное время раунда в наносекундах: 0 в момент
 * старта раунда, на паузе стоит, назад не идёт. Игре не нужно знать о паузах.
 *
 * Раунд. Сессия состоит из [roundsPerSession] раундов. Раунд — то, что нельзя разорвать: если его
 * прервали (приложение убито, нажали «Заново»), он начинается сначала с новым заданием.
 * Завершённые раунды сохраняются сразу.
 *
 * @param P задание раунда, @param S состояние раунда (неизменяемое), @param I ввод игрока.
 */
interface Game<P, S : Any, I : Any> {
    val id: GameId

    /** Версия формулы [score]: менять при каждом изменении формулы. */
    val scoringVersion: Int

    val levels: List<LevelConfig>

    val resumePolicy: ResumePolicy get() = ResumePolicy.CONTINUE

    fun roundsPerSession(level: Int): Int

    /**
     * Задание раунда. Обязано зависеть только от аргументов: один seed — одно и то же задание.
     * [history] — пробы уже сыгранных раундов этой сессии, для подстройки внутри сессии
     * (длина последовательности в Корси, экспозиция в «Двойном решении»).
     */
    fun generate(level: Int, rng: Random, history: List<TrialResult>): P

    fun initialState(params: P, level: Int): S

    /** Ответ игрока. */
    fun reduce(state: S, input: I, nowNanos: Long): S

    /** Ход времени: вызывается на каждом кадре. Если ничего не изменилось — вернуть тот же объект. */
    fun tick(state: S, nowNanos: Long): S = state

    fun isFinished(state: S): Boolean

    /** Пробы раунда по порядку. Движок читает их, когда раунд завершён. */
    fun trials(state: S): List<TrialResult>

    /** Оценка сессии по всем её пробам. */
    fun score(trials: List<TrialResult>, level: Int): SessionScore
}

/** Уровень, с которого игра начинается до калибровки (ТЗ, раздел 6). */
const val START_LEVEL = 2
