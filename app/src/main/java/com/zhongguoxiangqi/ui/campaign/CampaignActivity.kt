package com.zhongguoxiangqi.ui.campaign

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.data.ProgressStore
import com.zhongguoxiangqi.databinding.ActivityCampaignBinding
import com.zhongguoxiangqi.engine.EngineManager
import com.zhongguoxiangqi.game.CampaignStages
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.ui.common.Insets
import com.zhongguoxiangqi.ui.game.GameActivity

/**
 * 对弈闯关: 5 关对弈, 第 n 关对手强度 = 引擎难度 Lv.n。
 * 赢下当前关卡解锁下一关; 闯关胜负不影响战绩与积分。
 *
 * 关卡铺在一条蜿蜒的山路上(见 [CampaignPathView]): 打开页面自动滚到
 * 当前关 —— 老玩家每次进来第一眼就该看到自己的战旗立在哪。
 */
class CampaignActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCampaignBinding
    private lateinit var store: ProgressStore

    private var scrolledToCurrent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCampaignBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        Insets.apply(binding.root, top = binding.toolbar, bottom = binding.scroll, extraBottomDp = 8f)

        store = ProgressStore(this)
        binding.pathView.onLevelClickListener = { level ->
            if (level <= store.campaignUnlocked) startLevel(level)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.pathView.setData(
            totalLevels = TOTAL_LEVELS,
            unlocked = store.campaignUnlocked,
            passed = store.campaignPassedLevels(),
            stars = (1..TOTAL_LEVELS).associateWith { store.campaignStars(it) },
            names = (1..TOTAL_LEVELS).map { getString(CampaignStages.of(it).nameRes) },
        )
        maybeScrollToCurrent()
    }

    /**
     * 首次进入把当前关滚到屏幕中上部: 山路是自下而上的, 不滚的话玩家
     * 落在第 1 关的旗子上, 还以为进度丢了。
     */
    private fun maybeScrollToCurrent() {
        if (scrolledToCurrent) return
        binding.scroll.post {
            val current = store.campaignUnlocked
            val y = binding.pathView.yOffsetOf(current) -
                binding.scroll.height * SCROLL_ANCHOR_RATIO
            binding.scroll.smoothScrollTo(0, y.toInt().coerceAtLeast(0))
            scrolledToCurrent = true
        }
    }

    private fun startLevel(level: Int) {
        startActivity(
            Intent(this, GameActivity::class.java).apply {
                putExtra(GameActivity.EXTRA_MODE, GameActivity.MODE_CAMPAIGN)
                putExtra(GameActivity.EXTRA_CAMPAIGN_LEVEL, level)
                putExtra(GameActivity.EXTRA_DIFFICULTY, level.coerceIn(1, EngineManager.MAX_LEVEL))
                putExtra(GameActivity.EXTRA_PLAYER_SIDE, Color.RED)
            }
        )
    }

    private companion object {
        const val TOTAL_LEVELS = EngineManager.MAX_LEVEL

        /** 当前关停在屏幕高度的 45% 处(中上部), 前路比来路多留一点 */
        const val SCROLL_ANCHOR_RATIO = 0.45f
    }
}
