package com.zhongguoxiangqi.ui.menu

import com.zhongguoxiangqi.ui.common.Insets

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
import com.zhongguoxiangqi.engine.PlayStyle
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
        // Toolbar 里已经放了"印章 + 字标"那组自定义视图, 系统标题必须关掉 ——
        // 否则 ActionBar 会把 activity label 再画一遍, 首页顶上会出现两个
        // "QiYan" 一左一右夹着印章.
        supportActionBar?.setDisplayShowTitleEnabled(false)
        Insets.apply(binding.root, top = binding.toolbar, bottom = binding.root, extraBottomDp = 8f)

        userProfile = UserProfile(this)
        updateProfile()

        // 直接开局. 难度和执子都能在对局界面里随时改, 再用两个前置对话框
        // 挡在中间只是让"下盘棋"这件事多两次点击.
        binding.btnPlay.setOnClickListener {
            startGame(lastDifficulty(), lastSide())
        }
        binding.cardOpponent.setOnClickListener {
            startActivity(Intent(this, NewGameActivity::class.java))
        }
        binding.cardLive.setOnClickListener {
            startActivity(Intent(this, com.zhongguoxiangqi.ui.live.LiveAdviceActivity::class.java))
        }
        binding.btnCampaign.setOnClickListener {
            startActivity(Intent(this, com.zhongguoxiangqi.ui.campaign.CampaignActivity::class.java))
        }
        binding.btnPuzzle.setOnClickListener {
            startActivity(Intent(this, com.zhongguoxiangqi.ui.puzzle.PuzzleListActivity::class.java))
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
        // 从 NewGameActivity 换完对手回来要立刻反映到卡上, 所以放 onResume
        // 而不是 onCreate
        updateOpponentCard()
    }

    /** 首页那张"当前对手"卡. 数据源就是"直接开局"会用的那一组设定, 两者必须一致 */
    private fun updateOpponentCard() {
        val level = DifficultyLevel.entries.firstOrNull { it.id == lastDifficulty() }
            ?: DifficultyLevel.entries.first()
        OpponentAvatar.bind(binding.opponentAvatar, level)
        binding.opponentName.text =
            getString(R.string.new_game_level_format, level.id, getString(level.nameRes))
        // 见 DifficultyLevel.segmentOrNull; 风格拼在同一条副标题里, 开局前
        // 就能看清"这一局跟谁、什么棋路"。
        val segment = level.segmentOrNull(this)
        val styleName = getString(PlayStyle.fromId(lastStyle()).nameRes)
        binding.opponentSegment.text = listOf(segment, styleName)
            .filterNotNull()
            .joinToString(separator = " · ")
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

    private fun lastStyle(): Int =
        getSharedPreferences(GameActivity.PREFS_GAME, MODE_PRIVATE)
            .getInt(GameActivity.KEY_LAST_STYLE, PlayStyle.BALANCED.id)

    private fun startGame(difficultyId: Int, side: Int) {
        val intent = Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_DIFFICULTY, difficultyId)
            putExtra(GameActivity.EXTRA_PLAYER_SIDE, side)
            putExtra(GameActivity.EXTRA_STYLE, lastStyle())
        }
        startActivity(intent)
    }

    private companion object {
        /** 中级, 对新手不至于一边倒, 对老手也不至于无聊 */
        const val DEFAULT_DIFFICULTY = 4
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
 * 15 档, 每档都有显式的"棋手段位"标签 —— `segmentRes` 给出"这档对手大概是什么水平"
 * (新手 / 街道棋手 / 业余五段 / 棋协大师...), 描述里的"括号注"移到这一行, 不用用户读
 * 完整段话才看得到。档位只影响引擎思考时间(搜索深度), 棋力上限由自研引擎决定, 具体
 * Elo 是估计值, 只用于玩家积分的换算。
 */
enum class DifficultyLevel(
    val id: Int,
    val nameRes: Int,
    val segmentRes: Int,
    val descRes: Int,
    val elo: Int,
    val color: Int
) {
    EASY(1, R.string.difficulty_easy, R.string.difficulty_segment_easy, R.string.difficulty_desc_easy, 700, R.color.rank_bronze),
    BEGINNER(2, R.string.difficulty_beginner, R.string.difficulty_segment_beginner, R.string.difficulty_desc_beginner, 900, R.color.rank_bronze),
    LOWER_INTERMEDIATE(3, R.string.difficulty_lower_intermediate, R.string.difficulty_segment_lower_intermediate, R.string.difficulty_desc_lower_intermediate, 1100, R.color.rank_silver),
    INTERMEDIATE(4, R.string.difficulty_intermediate, R.string.difficulty_segment_intermediate, R.string.difficulty_desc_intermediate, 1300, R.color.rank_silver),
    UPPER_INTERMEDIATE(5, R.string.difficulty_upper_intermediate, R.string.difficulty_segment_upper_intermediate, R.string.difficulty_desc_upper_intermediate, 1450, R.color.rank_gold),
    ADVANCED(6, R.string.difficulty_advanced, R.string.difficulty_segment_advanced, R.string.difficulty_desc_advanced, 1600, R.color.rank_gold),
    AMATEUR(7, R.string.difficulty_amateur, R.string.difficulty_segment_amateur, R.string.difficulty_desc_amateur, 1750, R.color.rank_gold),
    DISTRICT(8, R.string.difficulty_district, R.string.difficulty_segment_district, R.string.difficulty_desc_district, 1850, R.color.rank_platinum),
    CITY(9, R.string.difficulty_city, R.string.difficulty_segment_city, R.string.difficulty_desc_city, 1950, R.color.rank_diamond),
    PROVINCIAL(10, R.string.difficulty_provincial, R.string.difficulty_segment_provincial, R.string.difficulty_desc_provincial, 2050, R.color.rank_master),
    LOCAL_MASTER(11, R.string.difficulty_local_master, R.string.difficulty_segment_local_master, R.string.difficulty_desc_local_master, 2150, R.color.rank_master),
    NATIONAL_MASTER(12, R.string.difficulty_national_master, R.string.difficulty_segment_national_master, R.string.difficulty_desc_national_master, 2250, R.color.rank_grandmaster),
    GRANDMASTER(13, R.string.difficulty_grandmaster, R.string.difficulty_segment_grandmaster, R.string.difficulty_desc_grandmaster, 2320, R.color.rank_grandmaster),
    INTERNATIONAL_MASTER(14, R.string.difficulty_international_master, R.string.difficulty_segment_international_master, R.string.difficulty_desc_international_master, 2400, R.color.rank_king),
    LEGENDARY(15, R.string.difficulty_legendary, R.string.difficulty_segment_legendary, R.string.difficulty_desc_legendary, 2500, R.color.rank_king);

    /**
     * 要显示的段位标签; 和档位名重了就返回 null(调用方把那个 chip 藏起来).
     *
     * 两条阶梯到了顶上会合流: 棋协大师 / 国家大师 / 国际大师 既是档位名, 也正是
     * 真人的头衔 —— 再并排挂一个一模一样的 chip, 那一行就成了"国家大师 国家大师".
     * 段位标签存在的意义是回答"这相当于什么水平的人", 当档位名**本身**就是答案时,
     * 正确的做法是不说第二遍, 而不是硬凑一个近义词。
     */
    fun segmentOrNull(context: android.content.Context): String? {
        val segment = context.getString(segmentRes)
        return segment.takeIf { it != context.getString(nameRes) }
    }
}
