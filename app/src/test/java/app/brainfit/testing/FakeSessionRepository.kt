package app.brainfit.testing

import app.brainfit.core.util.Json
import app.brainfit.domain.model.AdaptationInput
import app.brainfit.domain.model.GameDifficulty
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.LevelChangeRecord
import app.brainfit.domain.model.LevelChange
import app.brainfit.domain.model.NewSession
import app.brainfit.domain.model.ProgressSession
import app.brainfit.domain.model.SessionDraft
import app.brainfit.domain.model.SessionFinish
import app.brainfit.domain.model.SessionStatus
import app.brainfit.domain.model.SessionSummary
import app.brainfit.domain.model.TrialRecord
import app.brainfit.domain.repository.DifficultyRepository
import app.brainfit.domain.repository.ProgressRepository
import app.brainfit.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import java.io.IOException

/**
 * База в памяти с теми же правилами, что у настоящей: писать можно только в черновик, итог сессии
 * пишется вместе с уровнем игры, окно адаптации считается так же, как запросом в Room.
 *
 * @param levels уровни игр, которые уже откалиброваны; остальные игры — без записи об уровне.
 */
class FakeSessionRepository(levels: Map<GameId, Int> = emptyMap()) : SessionRepository, DifficultyRepository, ProgressRepository {

    class Row(
        val id: Long,
        val session: NewSession,
        var status: SessionStatus = SessionStatus.IN_PROGRESS,
        var activeMs: Long = 0,
        var endedAt: Long? = null,
        var score: Double? = null,
        var metricsJson: String = "{}",
        var isShaky: Boolean = false,
        val trials: MutableList<TrialRecord> = mutableListOf(),
    )

    val rows = mutableListOf<Row>()

    private val states = MutableStateFlow(levels.mapValues { (id, level) -> GameDifficulty(id, level, calibrated = true) })
    val difficulty: Map<GameId, GameDifficulty> get() = states.value

    /** Смены уровня по порядку с временем записи. */
    val changes = mutableListOf<Pair<LevelChange, Long>>()

    /** Имена операций, которые должны упасть по одному разу: "start", "saveRound", "finish", "abandon", "loadDraft". */
    val failOnce = mutableSetOf<String>()

    private fun maybeFail(op: String) {
        if (failOnce.remove(op)) throw IOException("диск недоступен")
    }

    private fun draft(id: Long): Row =
        rows.firstOrNull { it.id == id && it.status == SessionStatus.IN_PROGRESS }
            ?: error("Сессия $id не найдена среди незавершённых")

    override fun observeCompletedBetween(fromMillis: Long, toMillis: Long): Flow<List<SessionSummary>> = flowOf(emptyList())

    override fun observeCompletedCount(): Flow<Int> = flowOf(rows.count { it.status == SessionStatus.COMPLETED })

    override suspend fun lastCompletedTrials(gameId: GameId): List<TrialRecord> =
        rows.filter { it.session.gameId == gameId && it.status == SessionStatus.COMPLETED }
            .maxByOrNull { it.session.startedAt }?.trials?.toList().orEmpty()

    override suspend fun startSession(session: NewSession): Long {
        maybeFail("start")
        rows.filter { it.status == SessionStatus.IN_PROGRESS }.forEach {
            it.status = SessionStatus.ABANDONED
            it.endedAt = session.startedAt
        }
        val id = rows.size + 1L
        rows += Row(id, session)
        return id
    }

    override suspend fun saveRound(sessionId: Long, trials: List<TrialRecord>, activeMs: Long) {
        maybeFail("saveRound")
        val row = draft(sessionId)
        // Как уникальный индекс (sessionId, index) в настоящей базе.
        check(trials.none { t -> row.trials.any { it.index == t.index } }) { "Проба с таким номером уже записана" }
        row.trials += trials
        row.activeMs = activeMs
    }

    override suspend fun finishSession(finish: SessionFinish) {
        maybeFail("finish")
        val row = draft(finish.sessionId)
        check(row.session.gameId == finish.difficulty.gameId)
        row.status = SessionStatus.COMPLETED
        row.endedAt = finish.endedAt
        row.activeMs = finish.activeMs
        row.score = finish.score
        row.metricsJson = finish.metricsJson
        row.isShaky = finish.isShaky
        val update = finish.difficulty
        states.update { it + (update.gameId to GameDifficulty(update.gameId, update.level, update.calibrated)) }
        changes += update.changes.map { it to finish.endedAt }
    }

    override suspend fun abandonSession(sessionId: Long, endedAt: Long, activeMs: Long) {
        maybeFail("abandon")
        val row = draft(sessionId)
        row.status = SessionStatus.ABANDONED
        row.endedAt = endedAt
        row.activeMs = activeMs
    }

    override suspend fun loadDraft(sessionId: Long): SessionDraft? {
        maybeFail("loadDraft")
        val row = rows.firstOrNull { it.id == sessionId && it.status == SessionStatus.IN_PROGRESS } ?: return null
        return SessionDraft(row.id, row.session.gameId, row.session.level, row.activeMs, row.trials.toList(), row.session.isCalibration)
    }

    override fun observeStates(): Flow<Map<GameId, GameDifficulty>> = states

    override suspend fun adaptationInput(gameId: GameId, windowLimit: Int): AdaptationInput {
        val state = states.value[gameId]
        val completed = rows.filter { it.session.gameId == gameId && it.status == SessionStatus.COMPLETED }
        // Время смен уровня этой игры: в фейке игра одна на тест, поэтому берём все смены.
        val lastChange = changes.maxOfOrNull { it.second } ?: Long.MIN_VALUE
        val window = if (state == null) {
            emptyList()
        } else {
            completed
                .filter { !it.session.isCalibration && !it.isShaky && it.session.level == state.level && it.endedAt!! > lastChange }
                .sortedByDescending { it.endedAt }
                .take(windowLimit)
                .map { it.score!! }
        }
        return AdaptationInput(state, completed.count { it.session.isCalibration && !it.isShaky }, window)
    }

    private fun Row.toProgress() = ProgressSession(
        id = id,
        gameId = session.gameId,
        mode = session.mode,
        startedAt = session.startedAt,
        level = session.level,
        isCalibration = session.isCalibration,
        isShaky = isShaky,
        score = score,
        metrics = Json.parseObject(metricsJson).mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toDouble() } }.toMap(),
        activeMs = activeMs,
    )

    private fun completedRows() = rows.filter { it.status == SessionStatus.COMPLETED }.sortedBy { it.session.startedAt }

    override fun observeCompleted(): Flow<List<ProgressSession>> = flowOf(completedRows().map { it.toProgress() })

    override fun observeCompleted(gameId: GameId): Flow<List<ProgressSession>> =
        flowOf(completedRows().filter { it.session.gameId == gameId }.map { it.toProgress() })

    override suspend fun completed(gameId: GameId): List<ProgressSession> {
        maybeFail("completed")
        return completedRows().filter { it.session.gameId == gameId }.map { it.toProgress() }
    }

    /** Смены уровня здесь хранятся без игры ([changes]); контроллеру игры они не нужны. */
    override fun observeLevelChanges(gameId: GameId): Flow<List<LevelChangeRecord>> =
        throw UnsupportedOperationException("В подделке нет истории уровней по играм")
}
