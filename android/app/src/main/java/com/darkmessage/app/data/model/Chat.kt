package com.darkmessage.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class Chat(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val colorHue: Float,
    val sortOrder: Int = 0,
    val lastActivityAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
