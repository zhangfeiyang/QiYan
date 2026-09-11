package com.zhongguoxiangqi.ui.puzzle

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.data.ProgressStore
import com.zhongguoxiangqi.databinding.ActivityPuzzleListBinding
import com.zhongguoxiangqi.databinding.ItemPuzzleBinding

/**
 * 残局闯关列表: 一至三关一步杀, 四五关两步杀, 六关三步杀, 七关四步杀。
 */
class PuzzleListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPuzzleListBinding
    private lateinit var store: ProgressStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPuzzleListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        Insets_apply()

        store = ProgressStore(this)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = Adapter()
    }

    private fun Insets_apply() {
        com.zhongguoxiangqi.ui.common.Insets.apply(
            binding.root, top = binding.toolbar, bottom = binding.recycler, extraBottomDp = 8f
        )
    }

    override fun onResume() {
        super.onResume()
        binding.recycler.adapter?.notifyDataSetChanged()
    }

    private fun open(id: Int) {
        startActivity(Intent(this, PuzzleActivity::class.java).putExtra(PuzzleActivity.EXTRA_PUZZLE_ID, id))
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemPuzzleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = com.zhongguoxiangqi.game.Puzzles.all.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val puzzle = com.zhongguoxiangqi.game.Puzzles.all[position]
            val ctx = holder.binding.root.context
            val solved = puzzle.id in store.puzzleSolvedIds()
            val attempts = store.puzzleAttempts(puzzle.id)

            holder.binding.puzzleNo.text =
                ctx.getString(R.string.campaign_level_format, puzzle.id)
            holder.binding.puzzleName.text = ctx.getString(puzzle.nameRes)
            holder.binding.puzzleGoal.text =
                ctx.getString(R.string.puzzle_red_to_move, puzzle.maxMoves)
            holder.binding.puzzleStatus.text = when {
                solved -> ctx.getString(R.string.puzzle_solved)
                attempts > 0 -> ctx.getString(R.string.puzzle_attempts_format, attempts)
                else -> ctx.getString(R.string.puzzle_unsolved)
            }
            holder.binding.puzzleStatus.setTextColor(
                ContextCompat.getColor(
                    ctx, if (solved) R.color.affirm else R.color.text_tertiary
                )
            )
            holder.binding.root.setOnClickListener { open(puzzle.id) }
        }
    }

    private class VH(val binding: ItemPuzzleBinding) : RecyclerView.ViewHolder(binding.root)
}
