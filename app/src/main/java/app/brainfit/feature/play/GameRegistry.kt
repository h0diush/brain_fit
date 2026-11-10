package app.brainfit.feature.play

import androidx.compose.runtime.Composable
import app.brainfit.domain.model.GameId
import app.brainfit.games.engine.Game
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Игра вместе со своим экраном. Логика ([game]) — чистый Kotlin в `games/`, а [content] рисует
 * состояние раунда и отдаёт ввод игрока. Паузу, раунды, шапку и результат рисует хост (PlayScreen).
 *
 * @param howTo короткая инструкция: по пункту на строку. Показывается перед первым раундом.
 */
class GameModule<P, S : Any, I : Any>(
    val game: Game<P, S, I>,
    val howTo: List<String>,
    val content: @Composable (state: S, onInput: (I) -> Unit) -> Unit,
)

/**
 * Пробная попытка перед сессией: та же игра на простом задании, ничего не пишется в базу.
 * Время — от начала попытки, как в раунде.
 */
class Demo<S : Any, I : Any>(
    val initial: (Random) -> S,
    val reduce: (state: S, input: I, nowNanos: Long) -> S,
    val tick: (state: S, nowNanos: Long) -> S,
    val isFinished: (S) -> Boolean,
    /** Итог попытки, как между раундами; null — не показывать. */
    val summary: (S) -> String? = { null },
) {
    companion object {
        fun <P, S : Any, I : Any> of(game: Game<P, S, I>) = Demo<S, I>(
            initial = { rng -> game.initialState(game.generateDemo(rng), 1) },
            reduce = game::reduce,
            tick = game::tick,
            isFinished = game::isFinished,
            summary = { game.roundSummary(game.trials(it)) },
        )
    }
}

/** Игры, в которые уже можно играть: по строке в [ALL] на каждую. */
@Singleton
class GameRegistry(modules: List<GameModule<*, *, *>>) {

    @Inject
    constructor() : this(ALL)

    private val byId: Map<GameId, GameModule<*, *, *>> = modules.associateBy { it.game.id }

    val playable: Set<GameId> get() = byId.keys

    fun get(id: GameId): GameModule<*, *, *>? = byId[id]

    companion object {
        val ALL: List<GameModule<*, *, *>> = listOf(
            SchulteModule,
            StroopModule,
            NBackModule,
            CorsiModule,
            MathModule,
            DoubleDecisionModule,
        )
    }
}
