package com.zhongguoxiangqi.ui.menu

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
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
        binding.cardLive.setOnClickListener {
            startActivity(Intent(this, com.zhongguoxiangqi.ui.live.LiveAdviceActivity::class.java))
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
        // 中英文切换. 按钮文字始终显示"切过去会变成的语言", 不是当前语言 ——
        // 所以这里不用改按钮文字, strings.xml 里中英两份 menu_language 已经
        // 各自写死了对方的名字。
        binding.btnLanguage.setOnClickListener {
            val current = AppCompatDelegate.getApplicationLocales()
            val currentTag = if (current.isEmpty) "en" else current[0]?.language
            val next = if (currentTag == "zh") "en" else "zh"
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
        }
    }

    override fun onResume() {
        super.onResume()
        updateProfile()
    }

    private fun updateProfile() {
        binding.nickname.text = userProfile.nickname
        val rank = userProfile.rank()
        binding.ratingValue.text = getString(rank.nameRes)
        binding.ratingValue.setTextColor(ContextCompat.getColor(this, rank.colorRes))
        binding.gamesValue.text = getString(R.string.stat_total_games_format, userProfile.totalGames)
        binding.winsValue.text = getString(R.string.stat_wins_format, userProfile.wins)
        binding.lossesValue.text = getString(R.string.stat_losses_format, userProfile.losses)
        binding.drawsValue.text = getString(R.string.stat_draws_format, userProfile.draws)
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
/**
 * 难度档位.
 *
 * 原来有 10 档、最高标 Elo 3300 —— 那是 Pikafish 的棋力。Pikafish 因 GPL-3
 * 已从包中移除, 现在对手只有自研引擎, 再挂那些数字就是虚假宣传。
 *
 * 上限本来是 1700, 那是 2026-07-28 换掉 Pikafish 那批数据定的, 当时评估网络
 * 还是 all/all2 那一代。之后 NNUE 评估一路换到 net328m, 同样对 Pikafish 深度3
 * 配对测出的分数从那时约 -100 涨到 +1 —— 大约 100 Elo 的实测进步, 而这还没算
 * 上同期的搜索优化(SEE/LMR/AVX2 增量累加器)带来的额外增益。2026-08-01 把
 * 6 档整体上调 100, 级差不变。**这仍然是估计值**: 绝对 Elo 要有已知等级分的
 * 对手才能标定, 我们没有。所以界面上不再显示具体分数(见 item_difficulty),
 * 只保留"对手大概什么水平"的描述; elo 字段只用于玩家自己积分的换算。
 */
enum class DifficultyLevel(
    val id: Int,
    val nameRes: Int,
    val descRes: Int,
    val elo: Int,
    val color: Int
) {
    EASY(1, R.string.difficulty_easy, R.string.difficulty_desc_easy, 700, R.color.rank_bronze),
    BEGINNER(2, R.string.difficulty_beginner, R.string.difficulty_desc_beginner, 1000, R.color.rank_bronze),
    INTERMEDIATE(3, R.string.difficulty_intermediate, R.string.difficulty_desc_intermediate, 1300, R.color.rank_silver),
    ADVANCED(4, R.string.difficulty_advanced, R.string.difficulty_desc_advanced, 1550, R.color.rank_silver),
    EXPERT(5, R.string.difficulty_expert, R.string.difficulty_desc_expert, 1700, R.color.rank_gold),
    MASTER(6, R.string.difficulty_master, R.string.difficulty_desc_master, 1800, R.color.rank_gold);
}