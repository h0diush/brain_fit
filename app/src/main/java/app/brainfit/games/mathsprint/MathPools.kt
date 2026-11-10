package app.brainfit.games.mathsprint

import kotlin.math.max
import kotlin.math.min

/**
 * Все примеры каждого уровня. Ответ всегда целый и положительный: деление только нацело,
 * отрицательных ответов нет ни на одном уровне (на клавиатуре нет минуса). Тексты внутри уровня
 * уникальны: по ним проверяется «без повтора в пределах сессии».
 */
internal object MathPools {

    private val pools: List<Lazy<List<MathProblem>>> = (1..5).map { level -> lazy { build(level) } }

    fun pool(level: Int): List<MathProblem> = pools[level - 1].value

    private fun build(level: Int): List<MathProblem> {
        val out = ArrayList<MathProblem>()
        fun add(text: String, answer: Int) {
            out += MathProblem(text, answer)
        }
        when (level) {
            1 -> {
                for (a in 2..18) for (b in 2..(20 - a)) add("$a + $b", a + b)
                for (a in 4..20) for (b in 2..(a - 2)) add("$a − $b", a - b)
            }

            2 -> {
                for (a in 10..94) for (b in 6..(100 - a)) add("$a + $b", a + b)
                for (a in 20..100) for (b in 6..(a - 6)) add("$a − $b", a - b)
            }

            3 -> {
                for (a in 2..10) for (b in 2..10) add("$a × $b", a * b)
            }

            4 -> {
                for (a in 11..99) for (b in 2..9) add("$a × $b", a * b)
                // Делимое двузначное, делитель однозначный, частное целое и не меньше 2.
                for (d in 2..9) {
                    for (q in max(2, (10 + d - 1) / d)..(99 / d)) add("${d * q} ÷ $d", q)
                }
            }

            5 -> {
                for (a in 5..60) for (b in 2..9) for (c in 2..9) add("$a + $b × $c", a + b * c)
                for (a in 2..9) for (b in 2..9) for (c in 5..60) add("$a × $b + $c", a * b + c)
                for (a in 3..9) for (b in 3..9) for (c in 2..min(40, a * b - 2)) add("$a × $b − $c", a * b - c)
                for (a in 20..80) for (b in 2..9) for (c in 2..9) {
                    val result = a - b * c
                    if (result >= 2) add("$a − $b × $c", result)
                }
            }

            else -> error("Нет уровня $level в математическом спринте")
        }
        return out
    }
}
