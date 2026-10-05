package com.zhongguoxiangqi.ui.menu

import com.zhongguoxiangqi.ui.common.Insets

import android.content.Intent
import android.os.Bundle
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import com.zhongguoxiangqi.data.ProgressStore
import com.zhongguoxiangqi.ui.common.DifficultyPreferences
import com.zhongguoxiangqi.ui.puzzle.PuzzleActivity
import com.zhongguoxiangqi.ui.settings.AppSettings
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.databinding.ActivityMainMenuBinding
import com.zhongguoxiangqi.engine.DifficultySettings
import com.zhongguoxiangqi.engine.PlayStyle
import com.zhongguoxiangqi.game.CampaignStages
import com.zhongguoxiangqi.game.CampaignStars
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.HistoryCampaign
import com.zhongguoxiangqi.game.PuzzleCatalog
import com.zhongguoxiangqi.game.TacticsAcademy
import com.zhongguoxiangqi.rating.RankLadder
import com.zhongguoxiangqi.rating.UserProfile
import com.zhongguoxiangqi.ui.game.GameActivity
import com.zhongguoxiangqi.ui.game.BgmPlayer
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
    private val bgm by lazy { BgmPlayer(this) }

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

        if (resources.configuration.fontScale > 1.2f || resources.configuration.screenWidthDp < 360) {
            binding.heroArt.visibility = android.view.View.GONE
            // 两张特色卡并排会挤, 改竖排: 间距从左挪到上, 最小高度去掉.
            // 卡片是横排的 0dp+weight=1 —— 只翻 orientation 的话 weight 变成
            // 抢高度而宽度仍是 0, 两张卡直接消失、下面的卡片被顶出屏幕,
            // 必须同时改成 match_parent 并清掉 weight.
            binding.featureRow.orientation = LinearLayout.VERTICAL
            listOf(binding.cardLive, binding.btnRecognize).forEach { card ->
                (card.layoutParams as LinearLayout.LayoutParams).apply {
                    width = LinearLayout.LayoutParams.MATCH_PARENT
                    weight = 0f
                }
            }
            (binding.btnRecognize.layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = 0
                topMargin = (12 * resources.displayMetrics.density).toInt()
            }
            binding.featureLiveContent.minimumHeight = 0
            binding.featureRecognizeContent.minimumHeight = 0
        }
        // Keep the first launch at the main action; a focusable lower card must not scroll it away.
        binding.homeScroll.isFocusableInTouchMode = true
        binding.homeScroll.requestFocus()
        if (savedInstanceState == null) binding.homeScroll.post { binding.homeScroll.scrollTo(0, 0) }
        userProfile = UserProfile(this)
        updateProfile()
        binding.cardDaily.setOnClickListener {
            startActivity(Intent(this, PuzzleActivity::class.java).apply {
                putExtra(PuzzleActivity.EXTRA_DAILY, true)
            })
        }
        if (savedInstanceState == null && AppSettings(this).animationEnabled && ValueAnimator.areAnimatorsEnabled()) {
            listOf(binding.heroPanel, binding.cardOpponent, binding.featureRow, binding.cardDaily).forEachIndexed { index, view ->
                view.alpha = 0f
                view.translationY = 14f * resources.displayMetrics.density
                view.animate().alpha(1f).translationY(0f).setStartDelay(index * 70L)
                    .setDuration(420).setInterpolator(DecelerateInterpolator(2f)).start()
            }
            // 入场落定后, 两张特色卡各扫过一道一次性光带引人注意 —— 只扫一遍,
            // 不做无限循环 (首页不养耗电动画).
            binding.root.postDelayed({ playFeatureSheen() }, 420 + 3 * 70 + 350L)
        }

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
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, com.zhongguoxiangqi.ui.campaign.HistoryActivity::class.java))
        }
        binding.btnCatalog.setOnClickListener {
            startActivity(Intent(this, com.zhongguoxiangqi.ui.puzzle.PuzzleCatalogActivity::class.java))
        }
        binding.btnPuzzle.setOnClickListener {
            startActivity(Intent(this, com.zhongguoxiangqi.ui.puzzle.PuzzleListActivity::class.java))
        }
        binding.btnRush.setOnClickListener {
            com.zhongguoxiangqi.ui.puzzle.PuzzleRushActivity.launch(this)
        }
        binding.btnTactics.setOnClickListener {
            com.zhongguoxiangqi.ui.puzzle.TacticsAcademyActivity.launch(this)
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
        binding.profileCard.setOnClickListener {
            startActivity(Intent(this, RankHallActivity::class.java))
        }
        // 多语言选择器. 列表项用语言的自名(English / 简体中文 / …)而不是按
        // 当前界面语言翻译 —— 用户要在看不懂当前语言的情况下也能认出自己要的语言。
        // "跟随系统"清空 per-app locale, 之后 AppCompat 直接用系统 locale 匹配资源。
        binding.btnLanguage.setOnClickListener { showLanguagePicker() }
    }

    override fun onResume() {
        super.onResume()
        updateProfile()
        // 从 NewGameActivity 换完对手回来要立刻反映到卡上, 所以放 onResume
        // 而不是 onCreate
        updateOpponentCard()
        updatePractice()
        bgm.resume()
    }

    override fun onPause() {
        bgm.pause()
        super.onPause()
    }

    override fun onDestroy() {
        bgm.release()
        super.onDestroy()
    }

    /**
     * 特色卡一次性扫光: 光带从卡左缘平移到右缘后隐藏.
     *
     * 光带本身两端透明 (bg_feature_sheen), 扫过时的淡入淡出由渐变承担,
     * 不需要再叠 alpha 动画. 仅在入场动画完成后调用一次.
     */
    private fun playFeatureSheen() {
        val pairs = listOf(
            binding.cardLive to binding.sheenLive,
            binding.btnRecognize to binding.sheenRecognize
        )
        pairs.forEachIndexed { index, (card, sheen) ->
            if (card.width == 0 || isFinishing || isDestroyed) return@forEachIndexed
            sheen.translationX = -sheen.layoutParams.width.toFloat()
            sheen.visibility = android.view.View.VISIBLE
            sheen.animate()
                .translationX(card.width.toFloat())
                .setStartDelay(index * 120L)
                .setDuration(750)
                .setInterpolator(DecelerateInterpolator(1.2f))
                .withEndAction { sheen.visibility = android.view.View.GONE }
                .start()
        }
    }

    /** 首页那张"当前对手"卡. 数据源就是"直接开局"会用的那一组设定, 两者必须一致 */
    private fun updateOpponentCard() {
        val level = DifficultyLevel.entries.firstOrNull { it.id == lastDifficulty() }
            ?: DifficultyLevel.entries.first()
        OpponentAvatar.bind(binding.opponentAvatar, level)
        binding.opponentName.text =
            getString(level.nameRes)
        // 见 DifficultyLevel.segmentOrNull; 风格拼在同一条副标题里, 开局前
        // 就能看清"这一局跟谁、什么棋路"。
        binding.opponentSegment.text = if (level.id <= 2) getString(level.segmentRes)
            else getString(PlayStyle.fromId(lastStyle()).nameRes)
        binding.btnPlay.setText(if (level.id == 1) R.string.home_start_practice else R.string.home_start_again)
        binding.heroSubtitle.text = if (level.id <= 2) getString(R.string.home_intro) else getString(level.descRes)
        binding.cardOpponent.contentDescription = "${getString(level.nameRes)}, ${getString(R.string.home_change_opponent)}"
    }

    private fun updatePractice() {
        val store = ProgressStore(this)
        val daily = store.dailyPractice()
        binding.dailyAction.setText(if (daily.completedToday) R.string.home_daily_done else R.string.home_daily_ready)
        binding.dailyStreak.text = if (daily.completedDays == 0) getString(R.string.home_streak_start)
            else getString(R.string.home_streak, daily.currentStreak, daily.completedDays)
        binding.dailyProgress.progress = daily.currentStreak.coerceIn(0, 7)
        binding.dailyProgress.contentDescription = binding.dailyStreak.text
        val cleared = store.campaignPassedLevels().count { it in 1..DifficultySettings.MAX_LEVEL }
        val passed = store.campaignPassedLevels()
        val nextLevel = (1..DifficultySettings.MAX_LEVEL).firstOrNull { it <= store.campaignUnlocked && it !in passed }
            ?: DifficultySettings.MAX_LEVEL
        binding.campaignBrief.text = getString(CampaignStages.of(nextLevel).briefRes)
        binding.campaignProgress.text = getString(
            R.string.home_campaign_stars,
            store.campaignStarTotal(),
            DifficultySettings.MAX_LEVEL * CampaignStars.MAX,
            cleared,
        )
        val battles = HistoryCampaign.battles
        val historyStars = battles.sumOf { store.chronicleStars(it.id) }
        binding.historyHomeSub.text = getString(R.string.home_history_sub, historyStars, battles.size * CampaignStars.MAX)
        val catalog = PuzzleCatalog.all(this)
        val catalogSolved = catalog.count { it.id in store.puzzleSolvedIds() }
        binding.catalogHomeSub.text = getString(R.string.home_catalog_sub, catalogSolved, catalog.size)
        binding.rushHomeSub.text = getString(R.string.home_rush_sub, store.puzzleRushHighScore)
        val learned = TacticsAcademy.topics.count { it.puzzleId in store.puzzleSolvedIds() }
        binding.tacticsHomeSub.text = getString(R.string.home_tactics_sub, learned, TacticsAcademy.topics.size)
    }

    private fun updateProfile() {
        binding.nickname.text = userProfile.nickname
        val progressLine = if (userProfile.isInPlacement) {
            // 定级中还没有印: 不挂一个没下过计分局就给的"业余三段", 进度条走定级盘数
            val played = userProfile.totalGames
            binding.ratingValue.setText(R.string.rank_placement_name)
            binding.ratingValue.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.rankSeal.text = "?"
            binding.rankMeter.progress = played * 1000 / RankLadder.PLACEMENT_GAMES
            getString(
                R.string.rank_placement_progress,
                played, RankLadder.PLACEMENT_GAMES, RankLadder.PLACEMENT_GAMES - played,
            )
        } else {
            val rank = userProfile.rank()
            binding.ratingValue.text = getString(rank.nameRes)
            binding.ratingValue.setTextColor(ContextCompat.getColor(this, rank.colorRes))
            binding.rankSeal.text = rank.id.toString()
            val ladder = RankLadder.progress(userProfile.displayedRating)
            binding.rankMeter.progress = (ladder.fraction * 1000).toInt()
            if (ladder.next == null) {
                getString(R.string.rank_progress_top)
            } else {
                getString(R.string.rank_progress_next, getString(ladder.next.nameRes), ladder.pointsToNext)
            }
        }
        binding.rankProgress.text = progressLine + "\n" + getString(R.string.home_open_rank_hall)
        binding.profileCard.contentDescription = getString(R.string.home_open_rank_hall)
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
        binding.winrateBar.visibility = if (userProfile.totalGames == 0) android.view.View.GONE else android.view.View.VISIBLE
        binding.winrateBar.requestLayout()
    }

    /**
     * 上次用的难度/执子.
     *
     * 由 GameActivity 在改动时写回, 这样"直接开局"开出来的是你上次在下的
     * 那盘的设定, 而不是每次都从默认值重来.
     */
    private fun lastDifficulty(): Int = DifficultyPreferences.read(this)

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

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_about)
            .setMessage(R.string.settings_about_text)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    /** 语言选择器: 下标 0 是"跟随系统", 其余与 [LANGUAGE_TAGS] 一一对应. */
    private fun showLanguagePicker() {
        val names = arrayOf(
            getString(R.string.language_system_default),
            "English", "简体中文", "繁體中文", "Français", "Deutsch", "Español",
            "Português (Brasil)", "Русский", "日本語", "한국어", "Italiano",
            "ไทย", "Türkçe", "Bahasa Indonesia", "Tiếng Việt", "العربية",
        )
        val locales = AppCompatDelegate.getApplicationLocales()
        val currentTag = if (locales.isEmpty) "sys" else locales[0]?.let { loc ->
            // 只比 language+region: 系统设置里选"繁體中文"会得到 zh-Hant-TW,
            // 去掉 script 才能对上选择器里的 zh-TW.
            val region = loc.country?.takeIf { it.isNotEmpty() }?.let { "-$it" } ?: ""
            loc.language + region
        } ?: "sys"
        val checked = LANGUAGE_TAGS.withIndex()
            .firstOrNull { it.value.equals(currentTag, ignoreCase = true) }?.index ?: 0
        AlertDialog.Builder(this)
            .setTitle(R.string.language_dialog_title)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                } else {
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(LANGUAGE_TAGS[which])
                    )
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    companion object {
        private val LANGUAGE_TAGS = arrayOf(
            "sys", "en", "zh", "zh-TW", "fr", "de", "es", "pt-BR", "ru",
            "ja", "ko", "it", "th", "tr", "id", "vi", "ar",
        )
    }
}

/** Five understandable opponents; IDs follow DifficultySettings. Elo is for internal rating only. */
enum class DifficultyLevel(
    val id: Int,
    val nameRes: Int,
    val segmentRes: Int,
    val descRes: Int,
    val elo: Int,
    val color: Int
) {
    EASY(1, R.string.difficulty_gentle, R.string.difficulty_gentle_tag, R.string.difficulty_gentle_desc, 350, R.color.rank_bronze),
    BEGINNER(2, R.string.difficulty_starter, R.string.difficulty_starter_tag, R.string.difficulty_starter_desc, 800, R.color.rank_silver),
    INTERMEDIATE(3, R.string.difficulty_club, R.string.difficulty_club_tag, R.string.difficulty_club_desc, 1450, R.color.rank_gold),
    ADVANCED(4, R.string.difficulty_expert, R.string.difficulty_expert_tag, R.string.difficulty_expert_desc, 2000, R.color.rank_diamond),
    MASTER(5, R.string.difficulty_master_new, R.string.difficulty_master_new_tag, R.string.difficulty_master_new_desc, 2500, R.color.rank_grandmaster),
    GRANDMASTER(6, R.string.difficulty_6, R.string.difficulty_6_tag, R.string.difficulty_6_desc, 2600, R.color.rank_grandmaster),
    LEGEND(7, R.string.difficulty_7, R.string.difficulty_7_tag, R.string.difficulty_7_desc, 2700, R.color.rank_king),
    PINNACLE(8, R.string.difficulty_8, R.string.difficulty_8_tag, R.string.difficulty_8_desc, 2800, R.color.rank_king);

    fun segmentOrNull(context: android.content.Context): String? =
        context.getString(segmentRes).takeIf { it != context.getString(nameRes) }
}
