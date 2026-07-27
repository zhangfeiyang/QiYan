package com.zhongguoxiangqi.ui.settings

import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.databinding.ActivitySettingsBinding

/**
 * 应用设置.
 *
 * 包括:
 * - 引擎参数(线程数、哈希大小)
 * - 界面选项(音效、振动、坐标、翻转等)
 * - 主题(浅色/深色)
 * - 关于
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        val threads = prefs.getInt(KEY_THREADS, 2)
        val hashMb = prefs.getInt(KEY_HASH, 64)
        val soundEnabled = prefs.getBoolean(KEY_SOUND, true)
        val vibrateEnabled = prefs.getBoolean(KEY_VIBRATE, true)
        val showCoords = prefs.getBoolean(KEY_SHOW_COORDS, true)
        val showWinrate = prefs.getBoolean(KEY_SHOW_WINRATE, true)
        val animation = prefs.getBoolean(KEY_ANIMATION, true)
        val flipped = prefs.getBoolean(KEY_FLIPPED, false)
        // 默认值必须和 AppSettings.themeMode 保持一致 (1=深色), 否则设置页
        // 勾着"跟随系统", App 实际跑的却是深色.
        val theme = prefs.getInt(KEY_THEME, 1)

        binding.seekThreads.progress = threads
        binding.tvThreadsValue.text = threads.toString()
        binding.seekHash.progress = hashMb
        binding.tvHashValue.text = "${hashMb} MB"
        binding.swSound.isChecked = soundEnabled
        binding.swVibrate.isChecked = vibrateEnabled
        binding.swBgm.isChecked = prefs.getBoolean(KEY_BGM, true)
        binding.swShowCoords.isChecked = showCoords
        binding.swShowWinrate.isChecked = showWinrate
        binding.swAnimation.isChecked = animation
        binding.swFlipped.isChecked = flipped
        binding.themeGroup.check(when (theme) {
            1 -> R.id.theme_dark
            2 -> R.id.theme_light
            else -> R.id.theme_system
        })
    }

    private fun setupListeners() {
        binding.seekThreads.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(1)
                binding.tvThreadsValue.text = value.toString()
                prefs.edit().putInt(KEY_THREADS, value).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) { }
            override fun onStopTrackingTouch(s: SeekBar?) { }
        })

        binding.seekHash.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(16)
                binding.tvHashValue.text = "${value} MB"
                prefs.edit().putInt(KEY_HASH, value).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) { }
            override fun onStopTrackingTouch(s: SeekBar?) { }
        })

        binding.swSound.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SOUND, checked).apply()
        }
        binding.swVibrate.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_VIBRATE, checked).apply()
        }
        binding.swBgm.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_BGM, checked).apply()
        }
        binding.swShowCoords.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SHOW_COORDS, checked).apply()
        }
        binding.swShowWinrate.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SHOW_WINRATE, checked).apply()
        }
        binding.swAnimation.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_ANIMATION, checked).apply()
        }
        binding.swFlipped.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_FLIPPED, checked).apply()
        }

        binding.themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.theme_dark -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    1
                }
                R.id.theme_light -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    2
                }
                else -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    0
                }
            }
            prefs.edit().putInt(KEY_THEME, mode).apply()
        }

        binding.btnAbout.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_about)
                .setMessage(R.string.settings_about_text)
                .setPositiveButton(R.string.dialog_ok, null)
                .show()
        }

        binding.btnClearData.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("清除数据")
                .setMessage("将清除所有对局记录和评分数据,确定?")
                .setPositiveButton(R.string.dialog_yes) { _, _ ->
                    clearAllData()
                }
                .setNegativeButton(R.string.dialog_no, null)
                .show()
        }
    }

    private fun clearAllData() {
        // 清除 SharedPreferences
        getSharedPreferences("user_profile", MODE_PRIVATE).edit().clear().apply()
        prefs.edit().clear().apply()
        // 清除数据库
        deleteDatabase("xiangqi.db")
        recreate()
    }

    companion object {
        const val KEY_THREADS = "threads"
        const val KEY_HASH = "hash_mb"
        const val KEY_SOUND = "sound"
        const val KEY_VIBRATE = "vibrate"
        const val KEY_SHOW_COORDS = "show_coords"
        const val KEY_SHOW_WINRATE = "show_winrate"
        const val KEY_ANIMATION = "animation"
        const val KEY_FLIPPED = "flipped"
        const val KEY_THEME = "theme"
        const val KEY_BGM = "bgm"
    }
}