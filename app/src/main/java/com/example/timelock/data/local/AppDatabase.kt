package com.example.timelock.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.timelock.data.local.dao.RestrictedAppDao
import com.example.timelock.data.local.dao.UsageSessionDao
import com.example.timelock.data.local.entity.RestrictedAppEntity
import com.example.timelock.data.local.entity.UsageSessionEntity

@Database(
    entities = [RestrictedAppEntity::class, UsageSessionEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun restrictedAppDao(): RestrictedAppDao
    abstract fun usageSessionDao(): UsageSessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "timelock_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
