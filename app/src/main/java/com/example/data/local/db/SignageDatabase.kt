package com.example.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedVideoEntity::class,
        CachedPlaylistEntity::class,
        DeviceLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SignageDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun deviceLogDao(): DeviceLogDao

    companion object {
        @Volatile
        private var INSTANCE: SignageDatabase? = null

        fun getInstance(context: Context): SignageDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SignageDatabase::class.java,
                    "signage_player.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
