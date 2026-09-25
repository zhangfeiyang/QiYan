package com.zhongguoxiangqi.ui.puzzle

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.data.ProgressStore
import com.zhongguoxiangqi.databinding.ActivityTacticsAcademyBinding
import com.zhongguoxiangqi.databinding.ItemTacticAcademyBinding
import com.zhongguoxiangqi.game.TacticNames
import com.zhongguoxiangqi.game.TacticTopic
import com.zhongguoxiangqi.game.TacticsAcademy
import com.zhongguoxiangqi.ui.common.Insets

/**
 * 经典杀法宝典界面.
 *
 * 呈现象棋十大经典绝杀 (马后炮、铁门栓、重炮、双车错、卧槽马、挂角马、白脸将、天地炮、大刀剜心、闷宫)
 * 支持原声试听、破局实战与精通印章点亮.
 */
class TacticsAcademyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTacticsAcademyBinding
    private lateinit var store: ProgressStore
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTacticsAcademyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        Insets.apply(binding.root, top = binding.toolbar, bottom = binding.recyclerView, extraBottomDp = 8f)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        store = ProgressStore(this)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = TacticsAdapter(TacticsAcademy.topics)
    }

    override fun onResume() {
        super.onResume()
        binding.recyclerView.adapter?.notifyDataSetChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun playVoice(voiceRes: Int) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(this, voiceRes)?.apply {
            setOnCompletionListener {
                it.release()
                if (mediaPlayer == it) mediaPlayer = null
            }
            start()
        }
    }

    private inner class TacticsAdapter(
        private val list: List<TacticTopic>
    ) : RecyclerView.Adapter<TacticsAdapter.ViewHolder>() {

        inner class ViewHolder(val b: ItemTacticAcademyBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemTacticAcademyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            val isMastered = store.puzzleSolvedIds().contains(item.puzzleId)

            holder.b.tacticName.text = "${item.icon} ${TacticNames.label(this@TacticsAcademyActivity, item.name)}"
            holder.b.tacticRhyme.text = getString(item.rhymeRes)
            holder.b.tacticDesc.text = getString(item.descriptionRes)

            if (isMastered) {
                holder.b.tacticSeal.text = getString(R.string.tactic_mastered)
                holder.b.tacticSeal.setTextColor(getColor(R.color.action))
            } else {
                holder.b.tacticSeal.text = getString(R.string.tactic_learning)
                holder.b.tacticSeal.setTextColor(getColor(R.color.text_secondary))
            }

            holder.b.btnVoice.setOnClickListener {
                playVoice(item.voiceRes)
            }

            holder.b.btnChallenge.setOnClickListener {
                val intent = Intent(this@TacticsAcademyActivity, PuzzleActivity::class.java).apply {
                    putExtra(PuzzleActivity.EXTRA_PUZZLE_ID, item.puzzleId)
                }
                startActivity(intent)
            }
        }

        override fun getItemCount(): Int = list.size
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, TacticsAcademyActivity::class.java))
        }
    }
}
