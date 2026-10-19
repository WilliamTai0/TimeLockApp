package com.example.timelock.domain.manager

import com.example.timelock.data.local.dao.RestrictedAppDao
import com.example.timelock.data.local.dao.UsageSessionDao
import com.example.timelock.data.local.entity.RestrictedAppEntity
import com.example.timelock.data.local.entity.UsageSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NewFeaturesTest {

    private lateinit var fakeUsageDao: FakeUsageSessionDao
    private lateinit var fakeRestrictedDao: FakeRestrictedAppDao
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        fakeUsageDao = FakeUsageSessionDao()
        fakeRestrictedDao = FakeRestrictedAppDao()
        sessionManager = SessionManager(fakeRestrictedDao, fakeUsageDao)
    }

    // --- 測試 1：每日加鐘券（Overtime Pass）---

    @Test
    fun test_overtime_token_can_only_be_used_once_per_day() = runBlocking {
        // 初始狀態：今天還沒用過，應該可以加鐘
        assertTrue("今天首次使用應該允許加鐘", sessionManager.canUseOvertimeTokenToday())

        val sessionId = fakeUsageDao.insertSession(
            UsageSessionEntity(
                sessionId = 1L,
                packageName = "com.test.game",
                expectedDurationMs = 15 * 60_000L,
                isActive = true
            )
        )

        // 第一次使用加鐘券（加 30 分鐘）
        val firstUseSuccess = sessionManager.applyOvertimePass(sessionId, additionalMinutes = 30)
        assertTrue("第一次使用加鐘券應該成功", firstUseSuccess)

        // 檢查預期時長是否增加了 30 分鐘 (15m + 30m = 45m)
        val updatedSession = fakeUsageDao.sessions[sessionId]!!
        assertEquals(45 * 60_000L, updatedSession.expectedDurationMs)
        assertTrue(updatedSession.usedOvertimeToken)

        // 檢查今天是否還能再用
        assertFalse("加鐘券使用後，今天不應再次允許使用", sessionManager.canUseOvertimeTokenToday())

        // 第二次嘗試使用（防刷）
        val secondUseSuccess = sessionManager.applyOvertimePass(sessionId, additionalMinutes = 30)
        assertFalse("同一天第二次使用加鐘券應該被拒絕", secondUseSuccess)

        // 時長應該維持在 45m，沒有被二度延長
        assertEquals(45 * 60_000L, fakeUsageDao.sessions[sessionId]!!.expectedDurationMs)
    }

    // --- 測試 2：10 分鐘冷靜延遲解除（Delayed Unrestriction）---

    @Test
    fun test_delayed_unrestriction_remains_restricted_during_countdown() = runBlocking {
        val now = System.currentTimeMillis()
        val packageName = "com.google.android.youtube"

        // 1. 新增 YouTube 為受限
        fakeRestrictedDao.insertApp(
            RestrictedAppEntity(
                packageName = packageName,
                appName = "YouTube",
                isRestricted = true,
                pendingUnlockUntil = null
            )
        )
        assertTrue("剛加入時應該受限", fakeRestrictedDao.isAppRestricted(packageName, now))

        // 2. 用戶點擊解除，設定 10 分鐘延遲 (now + 10 分鐘)
        val tenMinutesLater = now + 10 * 60 * 1000L
        fakeRestrictedDao.setPendingUnlock(packageName, tenMinutesLater)

        // 3. 在 10 分鐘冷卻期間 (例如過咗 5 分鐘)
        val fiveMinutesPassed = now + 5 * 60 * 1000L
        assertTrue("在 10 分鐘冷靜期內，App 依然必須處於受限狀態（防止作弊繞過）", 
            fakeRestrictedDao.isAppRestricted(packageName, fiveMinutesPassed))

        // 4. 超過 10 分鐘後 (例如第 10 分 01 秒)
        val elevenMinutesPassed = now + 10 * 60 * 1000L + 1000L
        assertFalse("10 分鐘屆滿後，App 應該不再受限", 
            fakeRestrictedDao.isAppRestricted(packageName, elevenMinutesPassed))
    }

    @Test
    fun test_delayed_unrestriction_can_be_cancelled() = runBlocking {
        val now = System.currentTimeMillis()
        val packageName = "com.google.android.youtube"

        fakeRestrictedDao.insertApp(
            RestrictedAppEntity(
                packageName = packageName,
                appName = "YouTube",
                isRestricted = true,
                pendingUnlockUntil = now + 10 * 60 * 1000L
            )
        )

        // 點擊撤銷解除 (重設 pendingUnlockUntil = null)
        fakeRestrictedDao.setPendingUnlock(packageName, null)

        val app = fakeRestrictedDao.getRestrictedApp(packageName)!!
        assertNull("撤銷後 pendingUnlockUntil 應該恢復為 null", app.pendingUnlockUntil)
        assertTrue("撤銷後 App 依然完全受限", fakeRestrictedDao.isAppRestricted(packageName, now + 15 * 60 * 1000L))
    }

    // --- 測試 3：動態冷卻時間（Dynamic Cooldown with 50% Commitment Floor）---

    @Test
    fun test_dynamic_cooldown_calculation() {
        // 1. <= 5 分鐘場景：CD 2 分鐘 (120_000L)
        assertEquals(120_000L, sessionManager.calculateCooldownMs(actualUsageMs = 5 * 60_000L, expectedDurationMs = 5 * 60_000L))
        assertEquals(120_000L, sessionManager.calculateCooldownMs(actualUsageMs = 1 * 60_000L, expectedDurationMs = 5 * 60_000L))

        // 2. 10 分鐘場景：10 * 0.25 = 2.5m -> 保底 3 分鐘 (180_000L)
        assertEquals(180_000L, sessionManager.calculateCooldownMs(actualUsageMs = 10 * 60_000L, expectedDurationMs = 10 * 60_000L))

        // 3. 15 分鐘場景：15 * 0.25 = 3.75m (225_000L)
        assertEquals(225_000L, sessionManager.calculateCooldownMs(actualUsageMs = 15 * 60_000L, expectedDurationMs = 15 * 60_000L))

        // 4. 30 分鐘場景：30 * 0.25 = 7.5m (450_000L)
        assertEquals(450_000L, sessionManager.calculateCooldownMs(actualUsageMs = 30 * 60_000L, expectedDurationMs = 30 * 60_000L))

        // 5. 45 分鐘場景：45 * 0.25 = 11.25m (675_000L)
        assertEquals(675_000L, sessionManager.calculateCooldownMs(actualUsageMs = 45 * 60_000L, expectedDurationMs = 45 * 60_000L))

        // 6. 60 分鐘場景：60 * 0.25 = 15m (上限 900_000L)
        assertEquals(900_000L, sessionManager.calculateCooldownMs(actualUsageMs = 60 * 60_000L, expectedDurationMs = 60 * 60_000L))

        // 7. 防無腦揀大：預設 60 分鐘但只玩 2 分鐘，承諾底線為 30 分鐘 -> CD 7.5 分鐘 (450_000L)
        assertEquals(450_000L, sessionManager.calculateCooldownMs(actualUsageMs = 2 * 60_000L, expectedDurationMs = 60 * 60_000L))

        // 8. 提早收手：預設 30 分鐘，在 10 分鐘提早結束，底線為 15 分鐘 -> CD 3.75 分鐘 (225_000L)
        assertEquals(225_000L, sessionManager.calculateCooldownMs(actualUsageMs = 10 * 60_000L, expectedDurationMs = 30 * 60_000L))
    }

    @Test
    fun test_dynamic_cooldown_evaluation_state() = runBlocking {
        val now = 10000000L
        val packageName = "com.test.game"

        // 模擬剛結束一個 30 分鐘會話 (預期 30m, 實際 30m -> CD 7.5 分鐘 = 450,000ms)
        fakeUsageDao.insertSession(
            UsageSessionEntity(
                sessionId = 101L,
                packageName = packageName,
                expectedDurationMs = 30 * 60_000L,
                actualDurationMs = 30 * 60_000L,
                startTime = now - 30 * 60_000L,
                endTime = now,
                isActive = false
            )
        )

        // 過了 5 分鐘 (300,000ms)：依然在冷卻期內 (剩餘 150,000ms = 2.5 分鐘)
        val stateAt5m = sessionManager.evaluateSessionState(packageName, now + 300_000L)
        assertTrue("過了 5 分鐘依然應該在 7.5 分鐘冷卻期內", stateAt5m is SessionEvaluationState.InGlobalCooldown)
        val cooldownState = stateAt5m as SessionEvaluationState.InGlobalCooldown
        assertEquals(150_000L, cooldownState.remainingCooldownMs)

        // 過了 8 分鐘 (480,000ms)：超過 7.5 分鐘，冷卻結束，應該要求重新設定
        val stateAt8m = sessionManager.evaluateSessionState(packageName, now + 480_000L)
        assertTrue("超過 7.5 分鐘後冷卻應該解除", stateAt8m is SessionEvaluationState.RequireSetup)

        // 模擬另一個剛結束的 5 分鐘會話 (預期 5m, 實際 5m -> CD 2 分鐘 = 120,000ms)
        fakeUsageDao.insertSession(
            UsageSessionEntity(
                sessionId = 102L,
                packageName = packageName,
                expectedDurationMs = 5 * 60_000L,
                actualDurationMs = 5 * 60_000L,
                startTime = now + 480_000L,
                endTime = now + 480_000L + 5 * 60_000L,
                isActive = false
            )
        )

        val endOf5mSession = now + 480_000L + 5 * 60_000L

        // 過了 1 分鐘：依然在 2 分鐘冷卻期內
        val stateShort1m = sessionManager.evaluateSessionState(packageName, endOf5mSession + 60_000L)
        assertTrue("5 分鐘會話結束 1 分鐘後應該在 2 分鐘冷卻期內", stateShort1m is SessionEvaluationState.InGlobalCooldown)

        // 過了 2.5 分鐘：超過 2 分鐘，冷卻結束
        val stateShortPassed = sessionManager.evaluateSessionState(packageName, endOf5mSession + 150_000L)
        assertTrue("超過 2 分鐘後冷卻應該解除", stateShortPassed is SessionEvaluationState.RequireSetup)
    }

    @Test
    fun test_grace_period_expiry_with_long_session_still_enforces_remaining_cooldown() = runBlocking {
        val t0 = 10000000L
        val packageName = "com.test.game"

        // 1. 用戶開啟 60 分鐘會話，已使用 59 分鐘
        fakeUsageDao.insertSession(
            UsageSessionEntity(
                sessionId = 201L,
                packageName = packageName,
                expectedDurationMs = 60 * 60_000L,
                accumulatedUsageMs = 59 * 60_000L,
                startTime = t0,
                lastHeartbeatTime = t0 + 59 * 60_000L, // 在第 59 分鐘跳出
                isActive = true
            )
        )

        val leaveTime = t0 + 59 * 60_000L

        // 場景 A：跳出後只停了 1 分鐘（未過 3 分鐘寬限期）
        val stateWithinGrace = sessionManager.evaluateSessionState(packageName, leaveTime + 60_000L)
        assertTrue("未超過 3 分鐘寬限期，應該無縫接續舊會話", stateWithinGrace is SessionEvaluationState.ResumeSession)
        val resumeState = stateWithinGrace as SessionEvaluationState.ResumeSession
        assertEquals("剩餘時間應該依然是最後 1 分鐘", 60_000L, resumeState.remainingMs)

        // 場景 B：跳出後停了 3.1 分鐘（超過 3 分鐘寬限期，舊會話被強制結算）
        val stateAfterGrace = sessionManager.evaluateSessionState(packageName, leaveTime + 186_000L)
        assertTrue("超過 3 分鐘寬限期結算後，因為使用 59 分鐘對應 15 分鐘 CD，此時只過了 3.1 分鐘，絕不能重新設定，必須處於全域冷卻！", 
            stateAfterGrace is SessionEvaluationState.InGlobalCooldown)
        
        val cooldownState = stateAfterGrace as SessionEvaluationState.InGlobalCooldown
        // 實際使用 59 分鐘 -> 59 * 0.25 = 14.75 分鐘 (885,000ms)，已流逝 3.1 分鐘 (186,000ms)，剩餘 11.65 分鐘 (699,000ms)
        assertEquals(699_000L, cooldownState.remainingCooldownMs)
    }
}

// --- Fake DAOs for fast, zero-dependency testing ---

class FakeUsageSessionDao : UsageSessionDao {
    val sessions = mutableMapOf<Long, UsageSessionEntity>()
    private var idCounter = 1L

    override suspend fun getActiveSession(packageName: String): UsageSessionEntity? {
        return sessions.values.firstOrNull { it.packageName == packageName && it.isActive }
    }

    override suspend fun getActiveGlobalSession(): UsageSessionEntity? {
        return sessions.values.firstOrNull { it.isActive }
    }

    override suspend fun getLastSession(packageName: String): UsageSessionEntity? {
        return sessions.values.filter { it.packageName == packageName }.maxByOrNull { it.endTime ?: 0L }
    }

    override suspend fun getLatestCompletedSession(): UsageSessionEntity? {
        return sessions.values.filter { !it.isActive && it.endTime != null }.maxByOrNull { it.endTime!! }
    }

    override suspend fun getLatestSessionAcrossAll(): UsageSessionEntity? {
        return sessions.values.maxByOrNull { it.startTime }
    }

    override suspend fun insertSession(session: UsageSessionEntity): Long {
        val id = if (session.sessionId == 0L) idCounter++ else session.sessionId
        val saved = session.copy(sessionId = id)
        sessions[id] = saved
        return id
    }

    override suspend fun updateSession(session: UsageSessionEntity) {
        sessions[session.sessionId] = session
    }

    override suspend fun updateHeartbeat(sessionId: Long, accumulatedUsageMs: Long, lastHeartbeatTime: Long) {
        sessions[sessionId]?.let {
            sessions[sessionId] = it.copy(accumulatedUsageMs = accumulatedUsageMs, lastHeartbeatTime = lastHeartbeatTime)
        }
    }

    override suspend fun extendSessionDuration(sessionId: Long, additionalMs: Long) {
        sessions[sessionId]?.let {
            sessions[sessionId] = it.copy(
                expectedDurationMs = it.expectedDurationMs + additionalMs,
                usedOvertimeToken = true
            )
        }
    }

    override suspend fun getDailyOvertimeTokenUsageCount(startOfDayTimestamp: Long): Int {
        return sessions.values.count { it.usedOvertimeToken && it.startTime >= startOfDayTimestamp }
    }

    override suspend fun finishSession(sessionId: Long, actualDurationMs: Long, endTime: Long, isTimeout: Boolean) {
        sessions[sessionId]?.let {
            sessions[sessionId] = it.copy(isActive = false, actualDurationMs = actualDurationMs, endTime = endTime, isTimeout = isTimeout)
        }
    }

    override fun getAllSessionsFlow(): Flow<List<UsageSessionEntity>> = flowOf(sessions.values.toList())
    override fun getAverageExpectedDurationFlow(): Flow<Double?> = flowOf(0.0)
    override fun getAverageActualDurationFlow(): Flow<Double?> = flowOf(0.0)
    override fun getTotalCompletedSessionsCountFlow(): Flow<Int> = flowOf(0)
    override fun getTimeoutSessionsCountFlow(): Flow<Int> = flowOf(0)
    override suspend fun closeAllActiveSessions() {}
}

class FakeRestrictedAppDao : RestrictedAppDao {
    val apps = mutableMapOf<String, RestrictedAppEntity>()

    override fun getAllRestrictedAppsFlow(): Flow<List<RestrictedAppEntity>> = flowOf(apps.values.toList())

    override suspend fun getRestrictedApp(packageName: String): RestrictedAppEntity? = apps[packageName]

    override suspend fun isAppRestricted(packageName: String, now: Long): Boolean {
        val app = apps[packageName] ?: return false
        if (!app.isRestricted) return false
        val until = app.pendingUnlockUntil
        return until == null || until > now
    }

    override suspend fun insertApp(app: RestrictedAppEntity) {
        apps[app.packageName] = app
    }

    override suspend fun deleteApp(app: RestrictedAppEntity) {
        apps.remove(app.packageName)
    }

    override suspend fun deleteByPackageName(packageName: String) {
        apps.remove(packageName)
    }

    override suspend fun setPendingUnlock(packageName: String, unlockUntil: Long?) {
        apps[packageName]?.let {
            apps[packageName] = it.copy(pendingUnlockUntil = unlockUntil)
        }
    }

    override suspend fun cleanExpiredPendingUnlocks(now: Long) {
        val expired = apps.values.filter { it.pendingUnlockUntil != null && it.pendingUnlockUntil!! <= now }
        expired.forEach { apps.remove(it.packageName) }
    }
}
