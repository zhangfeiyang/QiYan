package com.zhongguoxiangqi.ui.settings

import com.zhongguoxiangqi.ui.common.Insets

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.databinding.ActivitySettingsBinding
import com.zhongguoxiangqi.ui.board.BoardSkin
import com.zhongguoxiangqi.ui.game.BgmPlayer

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
    private val musicPreviewDelegate = lazy { BgmPlayer(this) }
    private val musicPreview by musicPreviewDelegate
    private val previewHandler = Handler(Looper.getMainLooper())
    private var previewing = false
    private val stopPreview = Runnable { stopMusicPreview() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        Insets.apply(binding.root, top = binding.toolbar, bottom = binding.scroll, extraBottomDp = 0f)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        // 这两项的默认值跟设备走(核心数/内存), 只有 AppSettings 知道怎么算 ——
        // 在这里再写一份字面量, 就会重演 KEY_THEME 那个坑: 滑块显示 2 线程,
        // 引擎实际按 4 线程在跑.
        val appSettings = AppSettings(this)
        val threads = appSettings.threads
        val hashMb = appSettings.hashMb
        val soundEnabled = prefs.getBoolean(KEY_SOUND, true)
        val vibrateEnabled = prefs.getBoolean(KEY_VIBRATE, true)
        val showCoords = prefs.getBoolean(KEY_SHOW_COORDS, true)
        val showWinrate = prefs.getBoolean(KEY_SHOW_WINRATE, true)
        val animation = prefs.getBoolean(KEY_ANIMATION, true)
        val flipped = prefs.getBoolean(KEY_FLIPPED, false)
        // 默认值必须和 AppSettings.themeMode 保持一致 (2=浅色), 否则设置页
        // 勾着"跟随系统", App 实际跑的却是深色.
        val theme = prefs.getInt(KEY_THEME, 2)

        binding.seekThreads.progress = threads
        binding.tvThreadsValue.text = threads.toString()
        binding.seekHash.progress = hashMb
        binding.tvHashValue.text = "${hashMb} MB"
        binding.swSound.isChecked = soundEnabled
        binding.swVibrate.isChecked = vibrateEnabled
        binding.swBgm.isChecked = prefs.getBoolean(KEY_BGM, true)
        binding.seekBgmVolume.progress = appSettings.bgmVolume
        binding.tvBgmVolume.text = getString(R.string.audio_volume_value, appSettings.bgmVolume)
        binding.seekBgmVolume.isEnabled = appSettings.bgmEnabled
        binding.btnMusicPreview.isEnabled = appSettings.bgmEnabled && appSettings.bgmVolume > 0
        binding.swShowCoords.isChecked = showCoords
        binding.swShowWinrate.isChecked = showWinrate
        binding.swAnimation.isChecked = animation
        binding.swCaptureFx.isChecked = prefs.getBoolean(KEY_CAPTURE_FX, true)
        binding.swFlipped.isChecked = flipped
        binding.themeGroup.check(when (theme) {
            1 -> R.id.theme_dark
            2 -> R.id.theme_light
            else -> R.id.theme_system
        })
        binding.pieceSetGroup.check(when (prefs.getInt(KEY_PIECE_SET, 0)) {
            1 -> R.id.piece_set_hanzi
            2 -> R.id.piece_set_latin
            else -> R.id.piece_set_auto
        })
        binding.notationGroup.check(when (prefs.getInt(KEY_NOTATION, 0)) {
            1 -> R.id.notation_chinese
            2 -> R.id.notation_wxf
            else -> R.id.notation_auto
        })
        binding.skinPreview.inputEnabled = false
        binding.skinGroup.check(when (BoardSkin.fromId(appSettings.boardSkin)) {
            BoardSkin.BLACK_GOLD -> R.id.skin_black_gold
            BoardSkin.PORCELAIN -> R.id.skin_porcelain
            BoardSkin.JADE -> R.id.skin_jade
            BoardSkin.WOOD -> R.id.skin_wood
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
            binding.seekBgmVolume.isEnabled = checked
            binding.btnMusicPreview.isEnabled = checked && binding.seekBgmVolume.progress > 0
            if (!checked) stopMusicPreview()
        }
        binding.seekBgmVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvBgmVolume.text = getString(R.string.audio_volume_value, progress)
                prefs.edit().putInt(KEY_BGM_VOLUME, progress).apply()
                binding.btnMusicPreview.isEnabled = binding.swBgm.isChecked && progress > 0
                if (progress == 0) stopMusicPreview()
            }
            override fun onStartTrackingTouch(s: SeekBar?) { }
            override fun onStopTrackingTouch(s: SeekBar?) { }
        })
        binding.btnMusicPreview.setOnClickListener {
            if (previewing) {
                stopMusicPreview()
            } else {
                previewing = true
                binding.btnMusicPreview.setText(R.string.audio_preview_stop)
                musicPreview.resume()
                previewHandler.postDelayed(stopPreview, 12_000L)
            }
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
        binding.swCaptureFx.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_CAPTURE_FX, checked).apply()
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

        // 棋子字符集只影响 BoardView 的绘制, 而 BoardView 是在各界面 onCreate
        // 时读一次设置. 这里存完即可, 下次进对局/复盘就生效 —— 设置页本身
        // 没有棋盘可看, 不需要即时重绘.
        binding.pieceSetGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.edit().putInt(KEY_PIECE_SET, when (checkedId) {
                R.id.piece_set_hanzi -> 1
                R.id.piece_set_latin -> 2
                else -> 0
            }).apply()
        }

        binding.notationGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.edit().putInt(KEY_NOTATION, when (checkedId) {
                R.id.notation_chinese -> 1
                R.id.notation_wxf -> 2
                else -> 0
            }).apply()
        }

        binding.skinGroup.setOnCheckedChangeListener { _, checkedId ->
            val id = when (checkedId) {
                R.id.skin_black_gold -> BoardSkin.BLACK_GOLD.id
                R.id.skin_porcelain -> BoardSkin.PORCELAIN.id
                R.id.skin_jade -> BoardSkin.JADE.id
                else -> BoardSkin.WOOD.id
            }
            prefs.edit().putInt(KEY_BOARD_SKIN, id).apply()
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
                .setTitle(R.string.dialog_clear_data_title)
                .setMessage(R.string.dialog_clear_data_message)
                .setPositiveButton(R.string.dialog_yes) { _, _ ->
                    clearAllData()
                }
                .setNegativeButton(R.string.dialog_no, null)
                .show()
        }
    }

    private fun stopMusicPreview() {
        previewHandler.removeCallbacks(stopPreview)
        if (previewing) musicPreview.pause()
        previewing = false
        binding.btnMusicPreview.setText(R.string.audio_preview)
    }

    override fun onPause() {
        stopMusicPreview()
        super.onPause()
    }

    override fun onDestroy() {
        previewHandler.removeCallbacksAndMessages(null)
        // Avoid creating an unused decoder when this screen was only opened for settings.
        if (musicPreviewDelegate.isInitialized()) musicPreview.release()
        super.onDestroy()
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
        const val KEY_CAPTURE_FX = "capture_fx"
        const val KEY_THEME = "theme"
        const val KEY_BGM = "bgm"
        const val KEY_BGM_VOLUME = "bgm_volume"
        const val KEY_PIECE_SET = "piece_set"
        const val KEY_NOTATION = "notation"
        const val KEY_BOARD_SKIN = "board_skin"
    }
}
