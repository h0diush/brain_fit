package app.brainfit.feature.play

import androidx.compose.runtime.Composable
import app.brainfit.domain.model.GameId
import app.brainfit.games.engine.Game
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Игра вместе со своим экраном. Логика ([game]) — чистый Kotlin в `games/`, а [content] рисует
 * состояние раунда и отдаёт ввод игрока. Паузу, раунды, шапку и результат рисует хост (PlayScreen).
 */
class GameModule<P, S : Any, I : Any>(
    val game: Game<P, S, I>,
    val content: @Composable (state: S, onInput: (I) -> Unit) -> Unit,
)

/**
 * Игры, в которые уже можно играть. Сейчас список пуст: движок готов, сами игры добавляются
 * на этапах 3–4 — по строке в [ALL] на каждую.
 */
@Singleton
class GameRegistry(modules: List<GameModule<*, *, *>>) {

    @Inject
    constructor() : this(ALL)

    private val byId: Map<GameId, GameModule<*, *, *>> = modules.associateBy { it.game.id }

    val playable: Set<GameId> get() = byId.keys

    fun get(id: GameId): GameModule<*, *, *>? = byId[id]

    private companion object {
        val ALL: List<GameModule<*, *, *>> = emptyList()
    }
}
