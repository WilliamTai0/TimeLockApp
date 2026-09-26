package com.example.timelock.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.timelock.data.local.entity.UsageSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageSessionDao {
    @Query("SELECT * FROM usage_sessions WHERE packageName = :packageName AND isActive = 1 LIMIT 1")
    suspend fun getActiveSession(packageName: String): UsageSessionEntity?

    @Query("SELECT * FROM usage_sessions WHERE isActive = 1 ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveGlobalSession(): UsageSessionEntity?

    @Query("SELECT * FROM usage_sessions WHERE packageName = :packageName ORDER BY endTime DESC LIMIT 1")
    suspend fun getLastSession(packageName: String): UsageSessionEntity?

    @Query("SELECT * FROM usage_sessions WHERE isActive = 0 AND endTime IS NOT NULL ORDER BY endTime DESC LIMIT 1")
    suspend fun getLatestCompletedSession(): UsageSessionEntity?

    @Query("SELECT * FROM usage_sessions ORDER BY startTime DESC LIMIT 1")
    suspend fun getLatestSessionAcrossAll(): UsageSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: UsageSessionEntity): Long

    @Update
    suspend fun updateSession(session: UsageSessionEntity)

    @Query("""
        UPDATE usage_sessions 
        SET accumulatedUsageMs = :accumulatedUsageMs, lastHeartbeatTime = :lastHeartbeatTime 
        WHERE sessionId = :sessionId
    """)
    suspend fun updateHeartbeat(sessionId: Long, accumulatedUsageMs: Long, lastHeartbeatTime: Long)

    @Query("""
        UPDATE usage_sessions 
        SET isActive = 0, actualDurationMs = :actualDurationMs, endTime = :endTime, isTimeout = :isTimeout 
        WHERE sessionId = :sessionId
    """)
    suspend fun finishSession(sessionId: Long, actualDurationMs: Long, endTime: Long, isTimeout: Boolean)

    @Query("SELECT * FROM usage_sessions ORDER BY startTime DESC")
    fun getAllSessionsFlow(): Flow<List<UsageSessionEntity>>

    @Query("SELECT AVG(expectedDurationMs) FROM usage_sessions WHERE isActive = 0")
    fun getAverageExpectedDurationFlow(): Flow<Double?>

    @Query("SELECT AVG(actualDurationMs) FROM usage_sessions WHERE isActive = 0")
    fun getAverageActualDurationFlow(): Flow<Double?>

    @Query("SELECT COUNT(*) FROM usage_sessions WHERE isActive = 0")
    fun getTotalCompletedSessionsCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM usage_sessions WHERE isActive = 0 AND isTimeout = 1")
    fun getTimeoutSessionsCountFlow(): Flow<Int>

    @Query("UPDATE usage_sessions SET isActive = 0 WHERE isActive = 1")
    suspend fun closeAllActiveSessions()
}
