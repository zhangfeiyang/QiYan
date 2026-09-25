package com.zhongguoxiangqi.engine

/** Shared, Android-free difficulty contract. Persisted values before version 2 used 15 levels. */
object DifficultySettings {
    const val MAX_LEVEL = 8
    const val DEFAULT_LEVEL = 1
    const val STORAGE_VERSION = 2

    fun normalize(level: Int): Int = level.coerceIn(1, MAX_LEVEL)

    fun migrateLegacyLevel(oldLevel: Int): Int = (oldLevel.coerceIn(1, 15) + 2) / 3

    fun isTraining(level: Int): Boolean = normalize(level) <= 2
}
