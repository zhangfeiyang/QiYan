package com.zhongguoxiangqi.ui.puzzle

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.data.ProgressStore
import com.zhongguoxiangqi.databinding.ActivityPuzzleCatalogBinding
import com.zhongguoxiangqi.databinding.ItemPuzzleBinding
import com.zhongguoxiangqi.game.Puzzle
import com.zhongguoxiangqi.game.PuzzleCatalog
import com.zhongguoxiangqi.game.TacticNames
import com.zhongguoxiangqi.ui.common.Insets

/**
 * 千题库: 按杀法筛选, 点进去是红先一步杀.
 */
class PuzzleCatalogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPuzzleCatalogBinding
    private lateinit var store: ProgressStore
    private var all: List<Puzzle> = emptyList()
    private var shown: List<Puzzle> = emptyList()
    private var tacticFilter: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPuzzleCatalogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        Insets.apply(binding.root, top = binding.toolbar, bottom = binding.list, extraBottomDp = 8f)
        store = ProgressStore(this)
        all = PuzzleCatalog.all(this)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = Adapter()
        buildFilters()
        applyFilter(null)
    }

    override fun onResume() {
        super.onResume()
        val solved = store.puzzleSolvedIds()
        val done = all.count { it.id in solved }
        binding.catalogProgress.text = getString(R.string.catalog_progress, done, all.size)
        binding.list.adapter?.notifyDataSetChanged()
    }

    private fun buildFilters() {
        val counts = all.groupingBy { it.tactic ?: "绝杀" }.eachCount()
        addChip(getString(R.string.catalog_filter_all), null)
        counts.entries.sortedByDescending { it.value }.forEach { (tactic, count) ->
            addChip(getString(R.string.catalog_filter_count, TacticNames.label(this, tactic), count), tactic)
        }
    }

    private fun addChip(label: String, tactic: String?) {
        val chip = TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            setPadding(28, 16, 28, 16)
            setBackgroundResource(R.drawable.bg_pill)
            setOnClickListener { applyFilter(tactic) }
        }
        val gap = (8 * resources.displayMetrics.density).toInt()
        binding.filterRow.addView(chip, ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = gap })
    }

    private fun applyFilter(tactic: String?) {
        tacticFilter = tactic
        shown = if (tactic == null) all else all.filter { it.tactic == tactic }
        binding.list.adapter?.notifyDataSetChanged()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val row: ItemPuzzleBinding) : RecyclerView.ViewHolder(row.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = ItemPuzzleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(row)
        }

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val puzzle = shown[position]
            val solved = puzzle.id in store.puzzleSolvedIds()
            holder.row.puzzleNo.text = (puzzle.id - PuzzleCatalog.FIRST_ID + 1).toString()
            holder.row.puzzleName.text = TacticNames.label(this@PuzzleCatalogActivity, puzzle.tactic)
            holder.row.puzzleGoal.setText(R.string.catalog_mate_hint)
            holder.row.puzzleStatus.setText(if (solved) R.string.catalog_solved else R.string.catalog_open)
            holder.row.root.setOnClickListener {
                startActivity(Intent(this@PuzzleCatalogActivity, PuzzleActivity::class.java).apply {
                    putExtra(PuzzleActivity.EXTRA_PUZZLE_ID, puzzle.id)
                })
            }
        }
    }
}
