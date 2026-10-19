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

    suspend fun evaluateSessionState(packageName: String, currentTime: Long = System.currentTimeMillis()): SessionEvaluationState {
        val now = currentTime
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
        // 檢查是否有已結束的會話在動態冷卻期內 (方案一：依使用時長動態冷卻，保底 50% 承諾)
        val latestCompleted = usageSessionDao.getLatestCompletedSession()
        if (latestCompleted?.endTime != null) {
            val cooldownDurationMs = calculateCooldownMs(
                actualUsageMs = latestCompleted.actualDurationMs,
                expectedDurationMs = latestCompleted.expectedDurationMs
            )
            val elapsed = now - latestCompleted.endTime
            if (elapsed < cooldownDurationMs) {
                val remaining = cooldownDurationMs - elapsed
                val allowQuickReply = !latestCompleted.isQuickReply
                return SessionEvaluationState.InGlobalCooldown(remaining, allowQuickReply)
            }
        }

        return SessionEvaluationState.RequireSetup
    }

    /**
     * 動態計算冷卻時間 (方案一：Weighted Floor)
     * 基準 = max(實際使用時長, 預設時長 * 50%)
     * - base <= 5 分鐘：CD 2 分鐘
     * - 其餘：base * 0.25 (保底 3 分鐘，上限 15 分鐘)
     */
    fun calculateCooldownMs(actualUsageMs: Long, expectedDurationMs: Long): Long {
        val baseMs = maxOf(actualUsageMs, (expectedDurationMs * 0.5).toLong())
        val baseMinutes = baseMs / 60_000.0

        val cdMinutes = when {
            baseMinutes <= 5.0 -> 2.0
            else -> (baseMinutes * 0.25).coerceIn(3.0, 15.0)
        }

        return (cdMinutes * 60_000L).toLong()
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

    suspend fun canUseOvertimeTokenToday(): Boolean {
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        val count = usageSessionDao.getDailyOvertimeTokenUsageCount(startOfDay)
        return count < 1 // 每日限量 1 次
    }

    suspend fun applyOvertimePass(sessionId: Long, additionalMinutes: Int = 30): Boolean {
        if (!canUseOvertimeTokenToday()) return false
        val additionalMs = additionalMinutes * 60_000L
        usageSessionDao.extendSessionDuration(sessionId, additionalMs)
        return true
    }

    companion object {
        const val GRACE_PERIOD_MS = 180_000L // 3 分鐘寬限期
        const val COOLDOWN_PERIOD_MS = 180_000L // 3 分鐘冷卻期
    }
}
