package com.zhongguoxiangqi.ui.campaign

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.data.ProgressStore
import com.zhongguoxiangqi.databinding.ActivityHistoryBinding
import com.zhongguoxiangqi.game.HistoryCampaign
import com.zhongguoxiangqi.game.PuzzleCatalog
import com.zhongguoxiangqi.ui.common.Insets
import com.zhongguoxiangqi.ui.puzzle.PuzzleActivity

/**
 * 历史战役: 五个篇章, 每战一道已验证的一步杀.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var store: ProgressStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        Insets.apply(binding.root, top = binding.toolbar, bottom = binding.scroll, extraBottomDp = 8f)
        store = ProgressStore(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val catalog = PuzzleCatalog.all(this)
        val list = binding.chapterList
        list.removeAllViews()
        val d = resources.displayMetrics.density
        for (chapter in HistoryCampaign.chapters) {
            list.addView(TextView(this).apply {
                text = getString(chapter.titleRes)
                setTextColor(getColor(R.color.text_primary))
                textSize = 20f
                setPadding((20 * d).toInt(), (22 * d).toInt(), (20 * d).toInt(), (6 * d).toInt())
            })
            for (battle in chapter.battles) {
                val puzzle = HistoryCampaign.resolve(battle, catalog)
                val stars = store.chronicleStars(battle.id)
                val starText = buildString {
                    repeat(stars.coerceIn(0, 3)) { append('★') }
                    repeat(3 - stars.coerceIn(0, 3)) { append('☆') }
                }
                val block = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
                    isClickable = puzzle != null
                    isFocusable = puzzle != null
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener {
                        val id = puzzle?.id ?: return@setOnClickListener
                        startActivity(Intent(this@HistoryActivity, PuzzleActivity::class.java).apply {
                            putExtra(PuzzleActivity.EXTRA_PUZZLE_ID, id)
                            putExtra(PuzzleActivity.EXTRA_BATTLE, battle.id)
                        })
                    }
                }
                block.addView(TextView(this).apply {
                    text = getString(battle.nameRes) + "  " + starText
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 16f
                })
                block.addView(TextView(this).apply {
                    text = getString(battle.briefRes)
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 13f
                    setPadding(0, (4 * d).toInt(), 0, 0)
                })
                list.addView(block)
            }
        }
    }
}
