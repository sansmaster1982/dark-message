package com.darkmessage.app.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.darkmessage.app.data.model.Chat

@Database(
    entities = [Chat::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
