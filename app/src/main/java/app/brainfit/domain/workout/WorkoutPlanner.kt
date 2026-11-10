package app.brainfit.domain.workout

import app.brainfit.domain.model.GameCatalog
import app.brainfit.domain.model.GameGroup
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.GameInfo
import app.brainfit.domain.model.PlanningInput
import app.brainfit.domain.model.WorkoutMode

/**
 * Подбор игр в тренировку (ТЗ, раздел 5). Детерминированный: одни и те же данные — один и тот же набор.
 *
 * Очередь игр строится по ключу, от сильного к слабому:
 * 1. уже сыграна сегодня в тренировке — в конец (правило 4);
 * 2. была в прошлой тренировке — после остальных (правило 2: не повторять, если есть замена);
 * 3. дольше не играли — раньше, ни разу не играли — самые первые (правило 1);
 * 4. при равенстве — порядок каталога.
 *
 * Стандартная тренировка берёт лучшую игру каждой группы, а четвёртой — лучшую из оставшихся (правило 3).
 * Порядок игр в тренировке — порядок очереди.
 */
object WorkoutPlanner {

    fun size(mode: WorkoutMode): Int = when (mode) {
        WorkoutMode.QUICK -> 2
        WorkoutMode.STANDARD -> 4
    }

    fun plan(mode: WorkoutMode, input: PlanningInput, catalog: List<GameInfo> = GameCatalog.all): List<GameId> {
        // sortedWith устойчивая: при равных ключах остаётся порядок каталога.
        val queue = catalog.sortedWith(
            compareBy<GameInfo>(
                { it.id in input.playedTodayInWorkouts },
                { it.id in input.previousWorkout },
                { input.lastPlayedAt[it.id] ?: Long.MIN_VALUE },
            ),
        )
        val chosen: Set<GameInfo> = when (mode) {
            WorkoutMode.QUICK -> queue.take(size(mode)).toSet()
            WorkoutMode.STANDARD -> {
                val perGroup = GameGroup.entries.mapNotNull { group -> queue.firstOrNull { it.group == group } }
                (perGroup + (queue - perGroup.toSet()).take(size(mode) - perGroup.size)).toSet()
            }
        }
        return queue.filter { it in chosen }.map { it.id }
    }
}
