package app.brainfit.domain.model

data class GameInfo(
    val id: GameId,
    val title: String,
    /** Что тренирует — формулировка для карточки игры. */
    val trains: String,
    val group: GameGroup,
    val mainMetric: String,
)

/** Каталог игр живёт в коде, не в базе (ТЗ, раздел 7). Порядок — как в таблице раздела 4. */
object GameCatalog {
    val all: List<GameInfo> = listOf(
        GameInfo(GameId.SCHULTE, "Таблица Шульте", "Внимание, поиск", GameGroup.ATTENTION, "Время на ячейку"),
        GameInfo(GameId.STROOP, "Струп", "Внимание, торможение", GameGroup.ATTENTION, "Точность и Струп-эффект"),
        GameInfo(GameId.NBACK, "N-back", "Рабочая память", GameGroup.MEMORY, "Уровень N и точность"),
        GameInfo(GameId.CORSI, "Корси", "Пространственная память", GameGroup.MEMORY, "Длина последовательности"),
        GameInfo(GameId.MATH, "Математический спринт", "Скорость обработки", GameGroup.SPEED, "Правильных ответов в минуту"),
        GameInfo(
            GameId.DOUBLE_DECISION,
            "Двойное решение",
            "Скорость обработки, поле внимания",
            GameGroup.SPEED,
            "Порог экспозиции, мс",
        ),
    )

    private val byId = all.associateBy { it.id }

    fun get(id: GameId): GameInfo = byId.getValue(id)
}
