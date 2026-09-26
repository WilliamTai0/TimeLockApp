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

    @Query("SELECT EXISTS(SELECT 1 FROM restricted_apps WHERE packageName = :packageName AND isRestricted = 1)")
    suspend fun isAppRestricted(packageName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: RestrictedAppEntity)

    @Delete
    suspend fun deleteApp(app: RestrictedAppEntity)

    @Query("DELETE FROM restricted_apps WHERE packageName = :packageName")
    suspend fun deleteByPackageName(packageName: String)
}
