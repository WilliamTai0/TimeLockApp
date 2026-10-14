package com.example.timelock.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.timelock.data.local.entity.RestrictedAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RestrictedAppDao {
    @Query("SELECT * FROM restricted_apps ORDER BY addedAt DESC")
    fun getAllRestrictedAppsFlow(): Flow<List<RestrictedAppEntity>>

    @Query("SELECT * FROM restricted_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getRestrictedApp(packageName: String): RestrictedAppEntity?

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM restricted_apps 
            WHERE packageName = :packageName 
              AND isRestricted = 1 
              AND (pendingUnlockUntil IS NULL OR pendingUnlockUntil > :now)
        )
    """)
    suspend fun isAppRestricted(packageName: String, now: Long = System.currentTimeMillis()): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: RestrictedAppEntity)

    @Delete
    suspend fun deleteApp(app: RestrictedAppEntity)

    @Query("DELETE FROM restricted_apps WHERE packageName = :packageName")
    suspend fun deleteByPackageName(packageName: String)

    @Query("UPDATE restricted_apps SET pendingUnlockUntil = :unlockUntil WHERE packageName = :packageName")
    suspend fun setPendingUnlock(packageName: String, unlockUntil: Long?)

    @Query("DELETE FROM restricted_apps WHERE pendingUnlockUntil IS NOT NULL AND pendingUnlockUntil <= :now")
    suspend fun cleanExpiredPendingUnlocks(now: Long = System.currentTimeMillis())
}
