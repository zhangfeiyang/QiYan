package com.zhongguoxiangqi.ui.campaign

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.data.ProgressStore
import com.zhongguoxiangqi.databinding.ActivityCampaignBinding
import com.zhongguoxiangqi.databinding.ItemCampaignLevelBinding
import com.zhongguoxiangqi.engine.EngineManager
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.ui.common.Insets
import com.zhongguoxiangqi.ui.game.GameActivity

/**
 * 对弈闯关: 15 关对弈, 第 n 关对手强度 = 引擎难度 Lv.n。
 * 赢下当前关卡解锁下一关; 闯关胜负不影响战绩与积分。
 */
class CampaignActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCampaignBinding
    private lateinit var store: ProgressStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCampaignBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        Insets.apply(binding.root, top = binding.toolbar, bottom = binding.recycler, extraBottomDp = 8f)

        store = ProgressStore(this)
        binding.recycler.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        binding.recycler.adapter = Adapter()
    }

    override fun onResume() {
        super.onResume()
        binding.recycler.adapter?.notifyDataSetChanged()
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

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemCampaignLevelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = TOTAL_LEVELS

        override fun onBindViewHolder(holder: VH, position: Int) {
            val level = position + 1
            val unlocked = level <= store.campaignUnlocked
            val passed = level in store.campaignPassedLevels()
            val ctx = holder.binding.root.context

            holder.binding.levelNo.text = ctx.getString(R.string.campaign_level_format, level)
            holder.binding.levelDifficulty.text =
                ctx.getString(R.string.campaign_level_difficulty_format, level)
            holder.binding.levelStatus.text = when {
                passed -> ctx.getString(R.string.campaign_passed)
                unlocked -> ctx.getString(R.string.campaign_unlocked)
                else -> ctx.getString(R.string.campaign_locked)
            }
            val colorRes = when {
                passed -> R.color.affirm
                unlocked -> R.color.action
                else -> R.color.text_tertiary
            }
            holder.binding.levelCard.alpha = if (unlocked) 1f else 0.55f
            holder.binding.levelStatus.setTextColor(ContextCompat.getColor(ctx, colorRes))
            holder.binding.levelCard.isEnabled = unlocked
            holder.binding.levelCard.setOnClickListener {
                if (unlocked) startLevel(level)
            }
        }
    }

    private class VH(val binding: ItemCampaignLevelBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val TOTAL_LEVELS = EngineManager.MAX_LEVEL
        const val GRID_COLUMNS = 3
    }
}
