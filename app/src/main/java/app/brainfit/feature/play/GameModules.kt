package app.brainfit.feature.play

import app.brainfit.feature.play.game.CorsiContent
import app.brainfit.feature.play.game.DoubleDecisionContent
import app.brainfit.feature.play.game.MathContent
import app.brainfit.feature.play.game.NBackContent
import app.brainfit.feature.play.game.SchulteContent
import app.brainfit.feature.play.game.StroopContent
import app.brainfit.games.corsi.CorsiGame
import app.brainfit.games.doubledecision.DoubleDecisionGame
import app.brainfit.games.mathsprint.MathSprintGame
import app.brainfit.games.nback.NBackGame
import app.brainfit.games.schulte.SchulteGame
import app.brainfit.games.stroop.StroopGame

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

val StroopModule = GameModule(
    game = StroopGame(),
    howTo = listOf(
        "Появится название цвета, написанное каким-то цветом.",
        "Жми кнопку того цвета, которым написаны буквы, а не того, что написано словом.",
        "На ответ есть лимит времени: не успел — промах.",
        "Сессия — ${StroopGame.ROUNDS} раунда по ${StroopGame.STIMULI} слов. Считается доля верных ответов.",
    ),
    content = { state, onInput -> StroopContent(state, onInput) },
)

val NBackModule = GameModule(
    game = NBackGame(),
    howTo = listOf(
        "В сетке по одной загораются клетки.",
        "Жми «Совпадает», если клетка та же, что N шагов назад. Сколько шагов — написано сверху.",
        "Не совпадает — ничего не нажимай.",
        "Первые N клеток раунда просто запоминай.",
        "Пауза сбивает ритм, поэтому после неё раунд начинается заново.",
        "Сессия — ${NBackGame.ROUNDS} раунда. Считаются и попадания, и лишние нажатия.",
    ),
    content = { state, onInput -> NBackContent(state, onInput) },
)

val DoubleDecisionModule = GameModule(
    game = DoubleDecisionGame(),
    howTo = listOf(
        "Смотри в центр. На миг появятся фигура в центре и кружок ближе к краю, потом их закроет шум.",
        "Сначала выбери фигуру из центра: вытянута поперёк или вдоль.",
        "Потом нажми, где был кружок. На старших уровнях вокруг будут треугольники.",
        "Время показа подстраивается: два верных подряд — показ короче, ошибка — длиннее.",
        "Сессия — ${DoubleDecisionGame.ROUNDS} раунда по ${DoubleDecisionGame.TRIALS} показов. Считается самое короткое время показа, при котором ты справляешься.",
    ),
    content = { state, onInput -> DoubleDecisionContent(state, onInput) },
)
