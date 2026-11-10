package app.brainfit.domain

import app.brainfit.domain.model.GameCatalog
import app.brainfit.domain.model.GameGroup
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.GameId.CORSI
import app.brainfit.domain.model.GameId.DOUBLE_DECISION
import app.brainfit.domain.model.GameId.MATH
import app.brainfit.domain.model.GameId.NBACK
import app.brainfit.domain.model.GameId.SCHULTE
import app.brainfit.domain.model.GameId.STROOP
import app.brainfit.domain.model.PlanningInput
import app.brainfit.domain.model.WorkoutMode
import app.brainfit.domain.workout.WorkoutPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WorkoutPlannerTest {

    private fun input(
        lastPlayed: Map<GameId, Long> = emptyMap(),
        today: Set<GameId> = emptySet(),
        previous: Set<GameId> = emptySet(),
    ) = PlanningInput(lastPlayed, today, previous)

    @Test
    fun nothingPlayedYet_catalogOrder() {
        assertEquals(listOf(SCHULTE, STROOP), WorkoutPlanner.plan(WorkoutMode.QUICK, input()))
        // Стандартная: по игре из каждой группы, четвёртая — следующая по очереди.
        assertEquals(listOf(SCHULTE, STROOP, NBACK, MATH), WorkoutPlanner.plan(WorkoutMode.STANDARD, input()))
    }

    @Test
    fun longestNotPlayedComesFirst() {
        val last = mapOf(SCHULTE to 600L, STROOP to 500L, NBACK to 400L, CORSI to 300L, MATH to 200L, DOUBLE_DECISION to 100L)
        assertEquals(listOf(MATH, DOUBLE_DECISION).sorted(), WorkoutPlanner.plan(WorkoutMode.QUICK, input(last)).sorted())
        assertEquals(listOf(DOUBLE_DECISION, MATH), WorkoutPlanner.plan(WorkoutMode.QUICK, input(last)))
        // Ни разу не сыгранная игра — раньше всех.
        assertEquals(listOf(STROOP, DOUBLE_DECISION), WorkoutPlanner.plan(WorkoutMode.QUICK, input(last - STROOP)))
    }

    @Test
    fun previousWorkoutIsNotRepeatedWhenThereIsAChoice() {
        val plan = WorkoutPlanner.plan(WorkoutMode.QUICK, input(previous = setOf(SCHULTE, STROOP)))
        assertEquals(listOf(NBACK, CORSI), plan)
    }

    @Test
    fun gamesPlayedTodayInWorkoutsGoLast() {
        val plan = WorkoutPlanner.plan(
            WorkoutMode.QUICK,
            input(today = setOf(SCHULTE, STROOP, NBACK, CORSI, MATH), previous = setOf(DOUBLE_DECISION)),
        )
        // Сегодняшние — в конец даже раньше, чем игра из прошлой тренировки.
        assertEquals(DOUBLE_DECISION, plan.first())
    }

    @Test
    fun standardHasEveryGroupEvenWhenQueueSaysOtherwise() {
        // Обе игры на внимание и обе на скорость — в начале очереди, память — в конце.
        val last = mapOf(NBACK to 900L, CORSI to 800L)
        val plan = WorkoutPlanner.plan(WorkoutMode.STANDARD, input(last))
        assertEquals(4, plan.size)
        assertEquals(GameGroup.entries.toSet(), plan.map { GameCatalog.get(it).group }.toSet())
        assertTrue(CORSI in plan)
    }

    @Test
    fun randomInputs_rulesHold() {
        val rng = Random(11)
        repeat(10_000) {
            val ids = GameId.entries
            val input = input(
                lastPlayed = ids.filter { rng.nextBoolean() }.associateWith { rng.nextLong(0, 1_000) },
                today = ids.filter { rng.nextInt(4) == 0 }.toSet(),
                previous = ids.shuffled(rng).take(rng.nextInt(0, 5)).toSet(),
            )
            for (mode in WorkoutMode.entries) {
                val plan = WorkoutPlanner.plan(mode, input)
                assertEquals(WorkoutPlanner.size(mode), plan.size)
                assertEquals(plan.size, plan.distinct().size)
                // Детерминированность.
                assertEquals(plan, WorkoutPlanner.plan(mode, input))
                if (mode == WorkoutMode.STANDARD) {
                    assertEquals(GameGroup.entries.toSet(), plan.map { GameCatalog.get(it).group }.toSet())
                } else {
                    // Повтор прошлой тренировки — только если замен не хватает.
                    val fresh = ids.count { it !in input.previousWorkout && it !in input.playedTodayInWorkouts }
                    if (fresh >= plan.size) assertTrue(plan.none { it in input.previousWorkout || it in input.playedTodayInWorkouts })
                }
            }
        }
    }
}
