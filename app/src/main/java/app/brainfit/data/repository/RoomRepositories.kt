package app.brainfit.data.repository

import androidx.room.withTransaction
import app.brainfit.data.db.BrainFitDatabase
import app.brainfit.data.db.DifficultyChangeEntity
import app.brainfit.data.db.DifficultyDao
import app.brainfit.data.db.DifficultyStateEntity
import app.brainfit.data.db.ExerciseSessionEntity
import app.brainfit.data.db.SessionDao
import app.brainfit.data.db.TrialDao
import app.brainfit.data.db.TrialEntity
import app.brainfit.data.db.WorkoutDao
import app.brainfit.data.db.WorkoutEntity
import app.brainfit.data.db.WorkoutItemEntity
import app.brainfit.data.db.WorkoutItemRow
import app.brainfit.domain.model.AdaptationInput
import app.brainfit.domain.model.DifficultyUpdate
import app.brainfit.domain.model.GameDifficulty
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.NewSession
import app.brainfit.domain.model.PlanningInput
import app.brainfit.domain.model.SessionDraft
import app.brainfit.domain.model.SessionFinish
import app.brainfit.domain.model.SessionStatus
import app.brainfit.domain.model.SessionSummary
import app.brainfit.domain.model.TrialRecord
import app.brainfit.domain.model.Workout
import app.brainfit.domain.model.WorkoutItem
import app.brainfit.domain.model.WorkoutMode
import app.brainfit.domain.model.WorkoutStatus
import app.brainfit.domain.repository.DifficultyRepository
import app.brainfit.domain.repository.SessionRepository
import app.brainfit.domain.repository.WorkoutRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal fun ExerciseSessionEntity.toSummary() = SessionSummary(
    id = id,
    gameId = gameId,
    mode = mode,
    startedAt = startedAt,
    level = level,
    score = score,
)

internal fun DifficultyStateEntity.toDomain() = GameDifficulty(
    gameId = gameId,
    level = level,
    calibrated = calibrated,
)

internal fun TrialRecord.toEntity(sessionId: Long) = TrialEntity(
    sessionId = sessionId,
    index = index,
    paramsJson = paramsJson,
    answerJson = answerJson,
    correct = correct,
    rtMs = rtMs,
    errorType = errorType,
    isShaky = isShaky,
    round = round,
    level = level,
)

internal fun TrialEntity.toRecord() = TrialRecord(
    round = round,
    level = level,
    index = index,
    paramsJson = paramsJson,
    answerJson = answerJson,
    correct = correct,
    rtMs = rtMs,
    errorType = errorType,
    isShaky = isShaky,
)

internal fun WorkoutItemRow.toDomain() = WorkoutItem(
    order = order,
    gameId = gameId,
    sessionId = sessionId,
    skipped = skipped,
    score = score,
    activeMs = activeMs,
)

@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val db: BrainFitDatabase,
    private val sessions: SessionDao,
    private val trials: TrialDao,
    private val difficulty: DifficultyDao,
    private val workouts: WorkoutDao,
) : SessionRepository {

    override fun observeCompletedBetween(fromMillis: Long, toMillis: Long): Flow<List<SessionSummary>> =
        sessions.observeCompletedBetween(fromMillis, toMillis).map { list -> list.map { it.toSummary() } }

    override fun observeCompletedCount(): Flow<Int> = sessions.observeCompletedCount()

    override suspend fun lastCompletedTrials(gameId: GameId): List<TrialRecord> = db.withTransaction {
        val last = sessions.lastCompleted(gameId) ?: return@withTransaction emptyList()
        trials.forSession(last.id).map { it.toRecord() }
    }

    override suspend fun startSession(session: NewSession): Long = db.withTransaction {
        // Черновики, к которым уже не вернутся (приложение смахнули), иначе висели бы вечно.
        sessions.abandonDrafts(session.startedAt)
        sessions.insert(
            ExerciseSessionEntity(
                gameId = session.gameId,
                workoutId = session.workoutId,
                mode = session.mode,
                startedAt = session.startedAt,
                endedAt = null,
                activeMs = 0,
                level = session.level,
                score = null,
                status = SessionStatus.IN_PROGRESS,
                isCalibration = session.isCalibration,
                scoringVersion = session.scoringVersion,
                metricsJson = "{}",
            ),
        )
    }

    override suspend fun saveRound(sessionId: Long, trials: List<TrialRecord>, activeMs: Long) = db.withTransaction {
        requireDraft(sessions.updateActive(sessionId, activeMs), sessionId)
        this.trials.insertAll(trials.map { it.toEntity(sessionId) })
    }

    override suspend fun finishSession(finish: SessionFinish): Unit = db.withTransaction {
        val session = sessions.byId(finish.sessionId)
        requireDraft(
            sessions.finish(finish.sessionId, finish.endedAt, finish.activeMs, finish.score, finish.metricsJson, finish.isShaky),
            finish.sessionId,
        )
        check(session!!.gameId == finish.difficulty.gameId) { "Уровень другой игры в итоге сессии ${finish.sessionId}" }
        writeDifficulty(finish.difficulty, finish.endedAt)
        session.workoutId?.let { workoutId ->
            // Пункт может быть уже закрыт (игру пропустили, пока сессия шла) — тогда сессия просто свободная.
            workouts.linkSession(workoutId, session.gameId, session.id)
            workouts.completeIfDone(workoutId)
        }
        Unit
    }

    private suspend fun writeDifficulty(update: DifficultyUpdate, at: Long) {
        val current = difficulty.state(update.gameId)
        if (current == null || current.level != update.level || current.calibrated != update.calibrated) {
            difficulty.upsert(DifficultyStateEntity(update.gameId, update.level, update.calibrated, updatedAt = at))
        }
        if (update.changes.isNotEmpty()) {
            difficulty.insertChanges(
                update.changes.map { DifficultyChangeEntity(gameId = update.gameId, fromLevel = it.fromLevel, toLevel = it.toLevel, reason = it.reason, at = at) },
            )
        }
    }

    override suspend fun abandonSession(sessionId: Long, endedAt: Long, activeMs: Long) = db.withTransaction {
        requireDraft(sessions.abandon(sessionId, endedAt, activeMs), sessionId)
    }

    override suspend fun loadDraft(sessionId: Long): SessionDraft? = db.withTransaction {
        val session = sessions.byId(sessionId)?.takeIf { it.status == SessionStatus.IN_PROGRESS }
            ?: return@withTransaction null
        SessionDraft(
            id = session.id,
            gameId = session.gameId,
            level = session.level,
            activeMs = session.activeMs,
            trials = trials.forSession(sessionId).map { it.toRecord() },
            isCalibration = session.isCalibration,
        )
    }

    private fun requireDraft(updatedRows: Int, sessionId: Long) {
        check(updatedRows == 1) { "Сессия $sessionId не найдена среди незавершённых" }
    }
}

@Singleton
class DifficultyRepositoryImpl @Inject constructor(
    private val db: BrainFitDatabase,
    private val difficulty: DifficultyDao,
    private val sessions: SessionDao,
) : DifficultyRepository {

    override fun observeStates(): Flow<Map<GameId, GameDifficulty>> =
        difficulty.observeStates().map { list -> list.associate { it.gameId to it.toDomain() } }

    override suspend fun adaptationInput(gameId: GameId, windowLimit: Int): AdaptationInput = db.withTransaction {
        val state = difficulty.state(gameId)?.toDomain()
        val window = if (state == null) {
            emptyList()
        } else {
            // Окно начинается после последней смены уровня. До первой смены — со всех сессий уровня.
            sessions.windowScores(gameId, state.level, difficulty.lastChangeAt(gameId) ?: Long.MIN_VALUE, windowLimit)
        }
        AdaptationInput(state, sessions.calibrationCount(gameId), window)
    }
}

@Singleton
class WorkoutRepositoryImpl @Inject constructor(
    private val db: BrainFitDatabase,
    private val workouts: WorkoutDao,
    private val sessions: SessionDao,
) : WorkoutRepository {

    override fun observe(workoutId: Long): Flow<Workout?> =
        combine(workouts.observe(workoutId), workouts.observeItems(workoutId)) { workout, items ->
            workout?.toDomain(items)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeActive(date: Long): Flow<Workout?> =
        workouts.observeActive(date).flatMapLatest { active ->
            if (active == null) flowOf(null) else observe(active.id)
        }

    override suspend fun planningInput(dayStartMillis: Long, dayEndMillis: Long): PlanningInput = db.withTransaction {
        val previous = workouts.latest()?.let { latest ->
            workouts.itemsFor(latest.id).filter { it.sessionId != null }.map { it.gameId }.toSet()
        }.orEmpty()
        PlanningInput(
            lastPlayedAt = sessions.lastPlayed().associate { it.gameId to it.lastAt },
            playedTodayInWorkouts = sessions.gamesInWorkoutsBetween(dayStartMillis, dayEndMillis).toSet(),
            previousWorkout = previous,
        )
    }

    override suspend fun create(date: Long, mode: WorkoutMode, games: List<GameId>): Long = db.withTransaction {
        require(games.isNotEmpty() && games.distinct().size == games.size) { "В тренировке нет игр или они повторяются: $games" }
        workouts.abandonInProgress()
        val id = workouts.insert(WorkoutEntity(date = date, mode = mode, status = WorkoutStatus.IN_PROGRESS))
        workouts.insertItems(games.mapIndexed { order, game -> WorkoutItemEntity(id, order, game, sessionId = null, skipped = false) })
        id
    }

    override suspend fun skip(workoutId: Long, order: Int) = db.withTransaction {
        check(workouts.skip(workoutId, order) == 1) { "Игра $order тренировки $workoutId уже сыграна или пропущена" }
        workouts.completeIfDone(workoutId)
        Unit
    }

    override suspend fun abandonStale(today: Long) {
        workouts.abandonStale(today)
    }

    private fun WorkoutEntity.toDomain(items: List<WorkoutItemRow>) = Workout(
        id = id,
        date = date,
        mode = mode,
        status = status,
        items = items.map { it.toDomain() },
    )
}
