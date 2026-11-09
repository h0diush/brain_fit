package app.brainfit.testing

import app.brainfit.domain.model.NewSession
import app.brainfit.domain.model.SessionDraft
import app.brainfit.domain.model.SessionStatus
import app.brainfit.domain.model.SessionSummary
import app.brainfit.domain.model.TrialRecord
import app.brainfit.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.IOException

/** База в памяти с теми же правилами, что у настоящей: писать можно только в черновик. */
class FakeSessionRepository : SessionRepository {

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

    override suspend fun finishSession(sessionId: Long, endedAt: Long, activeMs: Long, score: Double, metricsJson: String, isShaky: Boolean) {
        maybeFail("finish")
        val row = draft(sessionId)
        row.status = SessionStatus.COMPLETED
        row.endedAt = endedAt
        row.activeMs = activeMs
        row.score = score
        row.metricsJson = metricsJson
        row.isShaky = isShaky
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
        return SessionDraft(row.id, row.session.gameId, row.session.level, row.activeMs, row.trials.toList())
    }
}
