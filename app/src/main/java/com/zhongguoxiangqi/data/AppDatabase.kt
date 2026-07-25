package com.zhongguoxiangqi.data

import android.content.Context
import androidx.room.Room

/**
 * 应用数据库单例.
 */
object AppDatabase {
    @Volatile private var INSTANCE: GameDatabase? = null

    fun get(context: Context): GameDatabase {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                GameDatabase::class.java,
                "xiangqi.db"
            )
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
        }
    }
}