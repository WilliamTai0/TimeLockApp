package com.example.timelock.domain.manager

import com.example.timelock.data.local.dao.RestrictedAppDao
import com.example.timelock.data.local.dao.UsageSessionDao
import com.example.timelock.data.local.entity.UsageSessionEntity

sealed interface SessionEvaluationState {
    data class ResumeSession(val session: UsageSessionEntity, val remainingMs: Long) : SessionEvaluationState
    data object RequireSetup : SessionEvaluationState
    data class InGlobalCooldown(val remainingCooldownMs: Long, val allowQuickReply: Boolean) : SessionEvaluationState
}

class SessionManager(
    private val restrictedAppDao: RestrictedAppDao,
    private val usageSessionDao: UsageSessionDao
) {

    suspend fun evaluateSessionState(packageName: String): SessionEvaluationState {
        val now = System.currentTimeMillis()
        val activeSession = usageSessionDao.getActiveGlobalSession()

        if (activeSession == null) {
            return checkGlobalCooldown(now)
        }

        val timeSinceLastBeat = now - activeSession.lastHeartbeatTime
        if (activeSession.lastHeartbeatTime > 0 && timeSinceLastBeat > GRACE_PERIOD_MS) {
            // 超過 3 分鐘寬限期，強制結算舊會話
            finishSession(activeSession, isTimeout = activeSession.accumulatedUsageMs >= activeSession.expectedDurationMs)
            return checkGlobalCooldown(now)
        }

        val remainingMs = (activeSession.expectedDurationMs - activeSession.accumulatedUsageMs).coerceAtLeast(0L)
        if (remainingMs <= 0L) {
            finishSession(activeSession, isTimeout = true)
            return checkGlobalCooldown(now)
        }

        return SessionEvaluationState.ResumeSession(activeSession, remainingMs)
    }

    private suspend fun checkGlobalCooldown(now: Long): SessionEvaluationState {
        // 檢查是否有已結束的會話在冷卻期內 (全域冷卻 3 分鐘)
        val latestCompleted = usageSessionDao.getLatestCompletedSession()
        if (latestCompleted?.endTime != null && (now - latestCompleted.endTime) < COOLDOWN_PERIOD_MS) {
            val remaining = COOLDOWN_PERIOD_MS - (now - latestCompleted.endTime)
            val allowQuickReply = !latestCompleted.isQuickReply
            return SessionEvaluationState.InGlobalCooldown(remaining, allowQuickReply)
        }

        return SessionEvaluationState.RequireSetup
    }

    suspend fun createNewSession(packageName: String, durationMinutes: Int): UsageSessionEntity {
        val session = UsageSessionEntity(
            packageName = packageName,
            expectedDurationMs = durationMinutes * 60_000L,
            startTime = System.currentTimeMillis(),
            lastHeartbeatTime = System.currentTimeMillis(),
            isActive = true,
            isQuickReply = false
        )
        val id = usageSessionDao.insertSession(session)
        return session.copy(sessionId = id)
    }

    suspend fun createQuickReplySession(packageName: String): UsageSessionEntity {
        val session = UsageSessionEntity(
            packageName = packageName,
            expectedDurationMs = 60_000L, // 1 分鐘快速回覆
            startTime = System.currentTimeMillis(),
            lastHeartbeatTime = System.currentTimeMillis(),
            isActive = true,
            isQuickReply = true
        )
        val id = usageSessionDao.insertSession(session)
        return session.copy(sessionId = id)
    }

    suspend fun finishSession(session: UsageSessionEntity, isTimeout: Boolean) {
        val end = if (session.lastHeartbeatTime > 0) session.lastHeartbeatTime else System.currentTimeMillis()
        usageSessionDao.finishSession(
            sessionId = session.sessionId,
            actualDurationMs = session.accumulatedUsageMs,
            endTime = end,
            isTimeout = isTimeout
        )
    }

    suspend fun finishSessionByPackage(packageName: String, flushUsageMs: Long? = null) {
        val activeSession = usageSessionDao.getActiveSession(packageName)
        if (activeSession != null) {
            val end = System.currentTimeMillis()
            val finalAccumulated = flushUsageMs ?: activeSession.accumulatedUsageMs
            usageSessionDao.finishSession(
                sessionId = activeSession.sessionId,
                actualDurationMs = finalAccumulated,
                endTime = end,
                isTimeout = finalAccumulated >= activeSession.expectedDurationMs
            )
        }
    }

    suspend fun updateHeartbeat(sessionId: Long, accumulatedMs: Long, now: Long) {
        usageSessionDao.updateHeartbeat(sessionId, accumulatedMs, now)
    }

    companion object {
        const val GRACE_PERIOD_MS = 180_000L // 3 分鐘寬限期
        const val COOLDOWN_PERIOD_MS = 180_000L // 3 分鐘冷卻期
    }
}
