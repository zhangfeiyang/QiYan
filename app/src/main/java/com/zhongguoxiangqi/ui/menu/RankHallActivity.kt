package com.zhongguoxiangqi.ui.menu

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.databinding.ActivityRankHallBinding
import com.zhongguoxiangqi.rating.RankLadder
import com.zhongguoxiangqi.rating.UserProfile
import com.zhongguoxiangqi.rating.UserRank
import com.zhongguoxiangqi.ui.common.Insets

/**
 * 段位印鉴: 十枚印从初学排到棋王, 当前印标金边.
 * 只有排位对局推动分数; 练习和闯关不改这面印.
 */
class RankHallActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityRankHallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        Insets.apply(binding.root, top = binding.toolbar, bottom = binding.scroll, extraBottomDp = 12f)

        val profile = UserProfile(this)
        val progress = RankLadder.progress(profile.displayedRating)
        binding.rankSummary.text = when {
            profile.isInPlacement -> getString(
                R.string.rank_hall_placement, profile.totalGames, RankLadder.PLACEMENT_GAMES,
            )
            progress.next == null -> getString(R.string.rank_progress_top)
            else -> getString(R.string.rank_progress_next, getString(progress.next.nameRes), progress.pointsToNext)
        }
        binding.hall.setLadder(
            // 定级中一枚印都还没拿到: 全部显示为"未达"
            currentId = if (profile.isInPlacement) 0 else progress.current.id,
            names = UserRank.RANKS.map { getString(it.nameRes) },
            ranges = UserRank.RANKS.map { rank ->
                if (rank.id == UserRank.RANKS.last().id) getString(R.string.rank_range_top, rank.minRating)
                else getString(R.string.rank_range, rank.minRating, rank.maxRating)
            },
            colors = UserRank.RANKS.map { ContextCompat.getColor(this, it.colorRes) }.toIntArray(),
        )
    }
}
