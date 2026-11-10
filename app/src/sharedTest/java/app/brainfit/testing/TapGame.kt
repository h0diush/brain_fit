package app.brainfit.testing

import app.brainfit.core.util.Json
import app.brainfit.domain.model.GameId
import app.brainfit.games.engine.Game
import app.brainfit.games.engine.LevelConfig
import app.brainfit.games.engine.ResumePolicy
import app.brainfit.games.engine.SessionScore
import app.brainfit.games.engine.TrialResult
import kotlin.random.Random

/*
 * Игра-заглушка только для тестов движка и хоста (в приложение не попадает): в раунде нужно
 * сделать target нажатий, каждое — «верное» или «неверное». Раунд обрывается по лимиту времени.
 */

data class TapParams(val target: Int, val token: Int)

data class TapState(
    val params: TapParams,
    val trials: List<TrialResult> = emptyList(),
    val lastNanos: Long = 0,
    val secondsLeft: Long,
    val timedOut: Boolean = false,
)

enum class TapInput { HIT, MISS }

data class TapLevel(override val level: Int) : LevelConfig

class TapGame(
    private val rounds: Int = 2,
    private val target: Int = 3,
    private val limitNanos: Long = 60 * NANOS_PER_SECOND,
    override val resumePolicy: ResumePolicy = ResumePolicy.CONTINUE,
    override val id: GameId = GameId.SCHULTE,
) : Game<TapParams, TapState, TapInput> {

    /** Размер history при каждом вызове generate — чтобы проверить, что движок передаёт историю. */
    val historySizes = mutableListOf<Int>()

    override val scoringVersion = 1
    override val levels: List<LevelConfig> = (1..3).map { TapLevel(it) }

    override fun roundsPerSession(level: Int) = rounds

    override fun generate(level: Int, rng: Random, history: List<TrialResult>): TapParams {
        historySizes += history.size
        return TapParams(target, rng.nextInt())
    }

    override fun initialState(params: TapParams, level: Int) =
        TapState(params, secondsLeft = limitNanos / NANOS_PER_SECOND)

    override fun reduce(state: TapState, input: TapInput, nowNanos: Long): TapState {
        val trial = TrialResult(
            paramsJson = Json.obj("n" to state.trials.size, "token" to state.params.token),
            answerJson = Json.obj("hit" to (input == TapInput.HIT)),
            correct = input == TapInput.HIT,
            rtMs = (nowNanos - state.lastNanos) / 1_000_000,
            errorType = if (input == TapInput.MISS) "miss" else null,
        )
        return state.copy(trials = state.trials + trial, lastNanos = nowNanos)
    }

    override fun tick(state: TapState, nowNanos: Long): TapState {
        if (nowNanos >= limitNanos) {
            val timeout = TrialResult(Json.obj("n" to state.trials.size), "{}", correct = false, rtMs = null, errorType = "timeout")
            return state.copy(trials = state.trials + timeout, timedOut = true, secondsLeft = 0)
        }
        val left = (limitNanos - nowNanos) / NANOS_PER_SECOND
        return if (left == state.secondsLeft) state else state.copy(secondsLeft = left)
    }

    override fun isFinished(state: TapState) = state.timedOut || state.trials.size >= state.params.target

    override fun trials(state: TapState) = state.trials

    override fun score(trials: List<TrialResult>, level: Int): SessionScore {
        val accuracy = if (trials.isEmpty()) 0.0 else trials.count { it.correct }.toDouble() / trials.size
        return SessionScore(accuracy, mapOf("accuracy" to accuracy, "trials" to trials.size.toDouble()))
    }

    override fun roundSummary(trials: List<TrialResult>) = "попаданий ${trials.count { it.correct }} из ${trials.size}"

    override fun resultLines(score: SessionScore) = listOf("Проб: ${score.metrics.getValue("trials").toInt()}")

    companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
