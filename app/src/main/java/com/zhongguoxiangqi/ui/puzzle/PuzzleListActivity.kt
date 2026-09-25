package com.zhongguoxiangqi.ui.puzzle

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.data.ProgressStore
import com.zhongguoxiangqi.databinding.ActivityPuzzleListBinding
import com.zhongguoxiangqi.databinding.ItemPuzzleCardBinding
import com.zhongguoxiangqi.databinding.PagePuzzleChapterBinding
import com.zhongguoxiangqi.game.Puzzle
import com.zhongguoxiangqi.game.Puzzles

/**
 * 残局研习列表: 左右横向翻卷 (ViewPager2)，每卷 8 关网格卡片展示，完全杜绝上下滑动翻页。
 * 卷一: 初试锋芒 (1~8 关, 1步必杀)
 * 卷二: 妙手连珠 (9~16 关, 2步精妙)
 * 卷三: 神机妙算 (17~24 关, 3~4步深算)
 */
class PuzzleListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPuzzleListBinding
    private lateinit var store: ProgressStore

    private val chapterTitles = intArrayOf(
        R.string.puzzle_chapter_1,
        R.string.puzzle_chapter_2,
        R.string.puzzle_chapter_3,
        R.string.puzzle_chapter_4,
        R.string.puzzle_chapter_5,
        R.string.puzzle_chapter_6
    )

    private val chapterSubtitles = intArrayOf(
        R.string.puzzle_chapter_sub_1,
        R.string.puzzle_chapter_sub_2,
        R.string.puzzle_chapter_sub_3,
        R.string.puzzle_chapter_sub_4,
        R.string.puzzle_chapter_sub_5,
        R.string.puzzle_chapter_sub_6
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPuzzleListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        com.zhongguoxiangqi.ui.common.Insets.apply(
            binding.root, top = binding.toolbar, bottom = binding.bottomBar, extraBottomDp = 8f
        )

        store = ProgressStore(this)

        val adapter = ChapterPagerAdapter()
        binding.viewPager.adapter = adapter
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateChapterHeader(position)
            }
        })

        binding.btnPrevChapter.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current > 0) binding.viewPager.setCurrentItem(current - 1, true)
        }

        binding.btnNextChapter.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current < Puzzles.CHAPTER_COUNT - 1) binding.viewPager.setCurrentItem(current + 1, true)
        }

        binding.cardPuzzleRush.setOnClickListener {
            PuzzleRushActivity.launch(this)
        }
        binding.cardTacticsAcademy.setOnClickListener {
            TacticsAcademyActivity.launch(this)
        }

        updateProgress()
        updateChapterHeader(0)
    }

    override fun onResume() {
        super.onResume()
        updateProgress()
        binding.viewPager.adapter?.notifyDataSetChanged()
    }

    private fun updateProgress() {
        val solvedIds = store.puzzleSolvedIds()
        val total = Puzzles.all.size
        val solvedCount = Puzzles.all.count { it.id in solvedIds }
        val percent = if (total > 0) (solvedCount * 100) / total else 0

        binding.overallProgressText.text = getString(R.string.puzzle_collection_progress, solvedCount, total)
        binding.overallPercentText.text = "$percent%"
        binding.overallProgressBar.max = total
        binding.overallProgressBar.progress = solvedCount

        binding.rushBannerSub.text = getString(R.string.puzzle_rush_banner, store.puzzleRushHighScore)

        val masteredCount = com.zhongguoxiangqi.game.TacticsAcademy.topics.count { it.puzzleId in solvedIds }
        binding.tacticsBannerSub.text = getString(
            R.string.tactics_banner_progress,
            masteredCount,
            com.zhongguoxiangqi.game.TacticsAcademy.topics.size,
        )
    }

    private fun updateChapterHeader(pageIndex: Int) {
        val chapter = pageIndex + 1
        binding.chapterTitle.text = getString(chapterTitles.getOrElse(pageIndex) { R.string.puzzle_chapter_1 })
        binding.chapterSubtitle.text = getString(chapterSubtitles.getOrElse(pageIndex) { R.string.puzzle_chapter_sub_1 })
        binding.pageIndicator.text = getString(R.string.puzzle_page, chapter, Puzzles.CHAPTER_COUNT)

        binding.btnPrevChapter.isEnabled = pageIndex > 0
        binding.btnNextChapter.isEnabled = pageIndex < Puzzles.CHAPTER_COUNT - 1
    }

    private fun openPuzzle(id: Int) {
        startActivity(
            Intent(this, PuzzleActivity::class.java).putExtra(PuzzleActivity.EXTRA_PUZZLE_ID, id)
        )
    }

    private inner class ChapterPagerAdapter : RecyclerView.Adapter<ChapterPagerAdapter.ChapterVH>() {

        inner class ChapterVH(val b: PagePuzzleChapterBinding) : RecyclerView.ViewHolder(b.root) {
            val slots = arrayOf<FrameLayout>(
                b.slot0, b.slot1, b.slot2, b.slot3,
                b.slot4, b.slot5, b.slot6, b.slot7
            )
            val cardBindings: List<ItemPuzzleCardBinding> = slots.map { slot ->
                ItemPuzzleCardBinding.inflate(LayoutInflater.from(b.root.context), slot, true)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChapterVH {
            val b = PagePuzzleChapterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ChapterVH(b)
        }

        override fun getItemCount(): Int = Puzzles.CHAPTER_COUNT

        override fun onBindViewHolder(holder: ChapterVH, position: Int) {
            val chapter = position + 1
            val puzzles = Puzzles.byChapter(chapter)
            val solvedIds = store.puzzleSolvedIds()

            for (i in holder.slots.indices) {
                val slot = holder.slots[i]
                if (i < puzzles.size) {
                    val puzzle = puzzles[i]
                    bindCard(holder.cardBindings[i], puzzle, solvedIds)
                    slot.visibility = View.VISIBLE
                } else {
                    slot.visibility = View.INVISIBLE
                }
            }
        }

        private fun bindCard(
            card: ItemPuzzleCardBinding,
            puzzle: Puzzle,
            solvedIds: Set<Int>
        ) {
            val ctx = card.root.context
            val solved = puzzle.id in solvedIds
            val attempts = store.puzzleAttempts(puzzle.id)

            card.puzzleNo.text = ctx.getString(R.string.campaign_level_format, puzzle.id)
            card.puzzleName.text = PuzzleTitles.theme(ctx.getString(puzzle.nameRes))
            card.puzzleGoal.text = ctx.getString(R.string.puzzle_red_to_move, puzzle.maxMoves)

            if (solved) {
                card.cardRoot.setBackgroundResource(R.drawable.bg_puzzle_card_solved)
                card.puzzleSeal.visibility = View.VISIBLE
                card.puzzleStatus.visibility = View.GONE
            } else {
                card.cardRoot.setBackgroundResource(R.drawable.bg_puzzle_card)
                card.puzzleSeal.visibility = View.GONE
                card.puzzleStatus.visibility = View.VISIBLE
                card.puzzleStatus.text = if (attempts > 0) {
                    ctx.getString(R.string.puzzle_attempts_format, attempts)
                } else {
                    ctx.getString(R.string.puzzle_unsolved)
                }
                card.puzzleStatus.setTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary))
            }

            card.cardRoot.setOnClickListener { openPuzzle(puzzle.id) }
        }
    }
}
