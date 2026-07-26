package com.zhongguoxiangqi.ui.replay

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.data.AppDatabase
import com.zhongguoxiangqi.data.GameRecord
import com.zhongguoxiangqi.databinding.ActivityReplayListBinding
import com.zhongguoxiangqi.game.Color
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对局列表(复盘主页).
 *
 * 显示已保存的对局记录.
 * 点击进入回放界面.
 */
class ReplayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReplayListBinding
    private lateinit var adapter: GameRecordAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReplayListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        adapter = GameRecordAdapter { record ->
            val intent = Intent(this, ReplayViewerActivity::class.java).apply {
                putExtra(ReplayViewerActivity.EXTRA_GAME_ID, record.id)
            }
            startActivity(intent)
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        loadRecords()
    }

    override fun onResume() {
        super.onResume()
        loadRecords()
    }

    private fun loadRecords() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@ReplayActivity).gameRecordDao()
            val records = dao.getRecent(100)
            adapter.submit(records)
            binding.empty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}

/**
 * 对局列表适配器.
 */
class GameRecordAdapter(
    private val onClick: (GameRecord) -> Unit
) : RecyclerView.Adapter<GameRecordAdapter.VH>() {

    private val items = mutableListOf<GameRecord>()
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    fun submit(records: List<GameRecord>) {
        items.clear()
        items.addAll(records)
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val result: TextView = view.findViewById(R.id.result)
        val side: TextView = view.findViewById(R.id.side)
        val difficulty: TextView = view.findViewById(R.id.difficulty)
        val date: TextView = view.findViewById(R.id.date)
        val moves: TextView = view.findViewById(R.id.moves)
        val rating: TextView = view.findViewById(R.id.rating)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_game_record, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.result.text = when (r.result) {
            1 -> "胜"
            -1 -> "负"
            else -> "和"
        }
        holder.result.setTextColor(
            holder.itemView.context.getColor(
                when (r.result) {
                    1 -> R.color.status_red
                    -1 -> R.color.status_black
                    else -> R.color.text_secondary
                }
            )
        )
        holder.side.text = if (r.playerSide == Color.RED) "执红" else "执黑"
        holder.difficulty.text = "Lv.${r.difficulty}"
        holder.date.text = dateFormat.format(Date(r.startTime))
        holder.moves.text = "${r.moves.size}手  ${r.endReason}"
        val delta = if (r.ratingDelta >= 0) "+${r.ratingDelta}" else "${r.ratingDelta}"
        holder.rating.text = "Elo ${r.playerRating} ($delta)"
        holder.itemView.setOnClickListener { onClick(r) }
    }
}