package app.brainfit.feature.play

import app.brainfit.feature.play.game.CorsiContent
import app.brainfit.feature.play.game.MathContent
import app.brainfit.feature.play.game.SchulteContent
import app.brainfit.games.corsi.CorsiGame
import app.brainfit.games.mathsprint.MathSprintGame
import app.brainfit.games.schulte.SchulteGame

val SchulteModule = GameModule(
    game = SchulteGame(),
    howTo = listOf(
        "В клетках перемешаны числа. Нажимай их по порядку: 1, 2, 3…",
        "На некоторых уровнях — в обратном порядке, от большего к меньшему.",
        "Сверху написано, какое число искать сейчас. Ошибка не страшна, но снижает точность.",
        "Сессия — ${SchulteGame.ROUNDS} таблицы. Считается время на клетку и точность.",
    ),
    content = { state, onInput -> SchulteContent(state, onInput) },
)

val MathModule = GameModule(
    game = MathSprintGame(),
    howTo = listOf(
        "Решай примеры и вводи ответ на клавиатуре, потом жми OK.",
        "Раунд длится ${MathSprintGame.ROUND_SECONDS} секунд, раундов — ${MathSprintGame.ROUNDS}.",
        "Считаются верные ответы в минуту и точность. Отрицательных ответов не бывает.",
    ),
    content = { state, onInput -> MathContent(state, onInput) },
)

val CorsiModule = GameModule(
    game = CorsiGame(),
    howTo = listOf(
        "Клетки загораются по одной. Запомни порядок.",
        "Когда показ закончится, нажми те же клетки в том же порядке.",
        "На старших уровнях — в обратном порядке, с последней клетки.",
        "Два верных подряд — последовательность длиннее, две ошибки — короче.",
        "Сессия — ${CorsiGame.ROUNDS} последовательностей. Считается самая длинная верная.",
    ),
    content = { state, onInput -> CorsiContent(state, onInput) },
)
