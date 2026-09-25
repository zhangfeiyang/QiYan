package com.zhongguoxiangqi.ui.common

import android.content.Context
import com.zhongguoxiangqi.engine.DifficultySettings

/** One-time migration shared by every entry point into the five difficulty levels. */
object DifficultyPreferences {
    private const val PREFS = "game_setup"
    private const val KEY_LEVEL = "last_difficulty"
    private const val KEY_VERSION = "difficulty_schema_version"

    @Synchronized
    fun read(context: Context): Int {
        migrate(context)
        return DifficultySettings.normalize(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_LEVEL, DifficultySettings.DEFAULT_LEVEL)
        )
    }

    @Synchronized
    fun save(context: Context, level: Int) {
        migrate(context)
        val normalized = DifficultySettings.normalize(level)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_LEVEL, normalized)
            .putInt(KEY_VERSION, DifficultySettings.STORAGE_VERSION)
            .apply()
        context.getSharedPreferences("user_profile", Context.MODE_PRIVATE).edit()
            .putInt("default_difficulty", normalized).apply()
    }

    private fun migrate(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= DifficultySettings.STORAGE_VERSION) return
        val profile = context.getSharedPreferences("user_profile", Context.MODE_PRIVATE)
        val level = when {
            prefs.contains(KEY_LEVEL) -> DifficultySettings.migrateLegacyLevel(prefs.getInt(KEY_LEVEL, 1))
            profile.contains("default_difficulty") ->
                DifficultySettings.migrateLegacyLevel(profile.getInt("default_difficulty", 1))
            else -> DifficultySettings.DEFAULT_LEVEL
        }
        prefs.edit().putInt(KEY_LEVEL, level)
            .putInt(KEY_VERSION, DifficultySettings.STORAGE_VERSION).apply()
        profile.edit().putInt("default_difficulty", level).apply()
    }
}
