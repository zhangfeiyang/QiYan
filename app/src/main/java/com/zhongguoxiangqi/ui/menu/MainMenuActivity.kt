package com.zhongguoxiangqi.ui.menu

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.databinding.ActivityMainMenuBinding
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.rating.UserProfile
import com.zhongguoxiangqi.rating.UserRank
import com.zhongguoxiangqi.ui.game.GameActivity
import com.zhongguoxiangqi.ui.replay.ReplayActivity
import com.zhongguoxiangqi.ui.settings.SettingsActivity

/**
 * 主菜单.
 *
 * 显示:
 * - 用户档案(评分、段位、战绩)
 * - 入口按钮(对局、复盘、设置、关于)
 */
class MainMenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainMenuBinding
    private lateinit var userProfile: UserProfile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        userProfile = UserProfile(this)
        updateProfile()

        // 直接开局. 难度和执子都能在对局界面里随时改, 再用两个前置对话框
        // 挡在中间只是让"下盘棋"这件事多两次点击.
        binding.btnPlay.setOnClickListener {
            startGame(lastDifficulty(), lastSide())
        }
        binding.btnReplay.setOnClickListener {
            startActivity(Intent(this, ReplayActivity::class.java))
        }
        binding.btnRecognize.setOnClickListener {
            startActivity(Intent(this, com.zhongguoxiangqi.ui.recognize.RecognizeActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnAbout.setOnClickListener {
            showAbout()
        }
    }

    override fun onResume() {
        super.onResume()
        updateProfile()
    }

    private fun updateProfile() {
        binding.nickname.text = userProfile.nickname
        binding.ratingValue.text = userProfile.displayedRating.toString()
        val rank = userProfile.rank()
        binding.rankValue.text = getString(rank.nameRes)
        binding.rankValue.setTextColor(ContextCompat.getColor(this, rank.colorRes))
        binding.gamesValue.text = "共 ${userProfile.totalGames} 局"
        binding.winsValue.text = "胜 ${userProfile.wins}"
        binding.lossesValue.text = "负 ${userProfile.losses}"
        binding.drawsValue.text = "和 ${userProfile.draws}"
        updateWinRateBar()
    }

    /**
     * 按实际战绩分配胜率条三段的宽度.
     *
     * 一局都没下时三段权重全给 0 会让 LinearLayout 把条子塌成 0 宽, 只剩
     * 一条空胶囊 —— 那正是想要的"还没有数据"的样子, 所以这里不做兜底.
     */
    private fun updateWinRateBar() {
        fun weigh(view: android.view.View, value: Int) {
            view.layoutParams = (view.layoutParams as LinearLayout.LayoutParams).apply {
                weight = value.toFloat()
            }
        }
        weigh(binding.winrateWin, userProfile.wins)
        weigh(binding.winrateLoss, userProfile.losses)
        weigh(binding.winrateDraw, userProfile.draws)
        binding.winrateBar.requestLayout()
    }

    /**
     * 上次用的难度/执子.
     *
     * 由 GameActivity 在改动时写回, 这样"直接开局"开出来的是你上次在下的
     * 那盘的设定, 而不是每次都从默认值重来.
     */
    private fun lastDifficulty(): Int =
        getSharedPreferences(GameActivity.PREFS_GAME, MODE_PRIVATE)
            .getInt(GameActivity.KEY_LAST_DIFFICULTY, DEFAULT_DIFFICULTY)

    private fun lastSide(): Int =
        getSharedPreferences(GameActivity.PREFS_GAME, MODE_PRIVATE)
            .getInt(GameActivity.KEY_LAST_SIDE, Color.RED)

    private fun startGame(difficultyId: Int, side: Int) {
        val intent = Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_DIFFICULTY, difficultyId)
            putExtra(GameActivity.EXTRA_PLAYER_SIDE, side)
        }
        startActivity(intent)
    }

    private companion object {
        /** 中级, 对新手不至于一边倒, 对老手也不至于无聊 */
        const val DEFAULT_DIFFICULTY = 3
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_about)
            .setMessage(R.string.settings_about_text)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }
}

/**
 * 难度等级定义.
 */
enum class DifficultyLevel(
    val id: Int,
    val nameRes: Int,
    val descRes: Int,
    val elo: Int,
    val color: Int
) {
    EASY(1, R.string.difficulty_easy, R.string.difficulty_desc_easy, 600, R.color.rank_bronze),
    BEGINNER(2, R.string.difficulty_beginner, R.string.difficulty_desc_beginner, 900, R.color.rank_bronze),
    INTERMEDIATE(3, R.string.difficulty_intermediate, R.string.difficulty_desc_intermediate, 1200, R.color.rank_silver),
    ADVANCED(4, R.string.difficulty_advanced, R.string.difficulty_desc_advanced, 1500, R.color.rank_silver),
    EXPERT(5, R.string.difficulty_expert, R.string.difficulty_desc_expert, 1700, R.color.rank_gold),
    MASTER(6, R.string.difficulty_master, R.string.difficulty_desc_master, 1900, R.color.rank_gold),
    CHAMPION(7, R.string.difficulty_champion, R.string.difficulty_desc_champion, 2200, R.color.rank_platinum),
    ELITE(8, R.string.difficulty_elite, R.string.difficulty_desc_elite, 2500, R.color.rank_diamond),
    LEGEND(9, R.string.difficulty_legend, R.string.difficulty_desc_legend, 2900, R.color.rank_master),
    IMMORTAL(10, R.string.difficulty_immortal, R.string.difficulty_desc_immortal, 3300, R.color.rank_grandmaster);
}