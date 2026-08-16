package com.zhongguoxiangqi.ui.replay

import com.zhongguoxiangqi.ui.common.Insets

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
import com.zhongguoxiangqi.rating.UserRank
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
        Insets.apply(binding.root, top = binding.toolbar, bottom = binding.list, extraBottomDp = 0f)
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
        val ctx = holder.itemView.context
        holder.result.text = when (r.result) {
            1 -> ctx.getString(R.string.result_win_short)
            -1 -> ctx.getString(R.string.result_loss_short)
            else -> ctx.getString(R.string.result_draw_short)
        }
        // 胜负用语义色, 不是执子色. 原来"胜"取 status_red、"负"取
        // status_black —— 那两个是红方/黑方的标识色, 跟输赢无关, 结果
        // 就是赢和输染成了同一族的颜色, 扫一眼列表分不出战绩.
        // 这里与主菜单胜率条的图例保持同一套: 胜=正向色, 负=危险色.
        // 注意胜必须用 affirm 而不是 action —— 品牌色本身就是朱红,
        // 用它标"胜"会和标"负"的 danger 变成同一族红, 又回到旧毛病.
        holder.result.setTextColor(
            holder.itemView.context.getColor(
                when (r.result) {
                    1 -> R.color.affirm
                    -1 -> R.color.danger
                    else -> R.color.text_secondary
                }
            )
        )
        holder.side.text = ctx.getString(if (r.playerSide == Color.RED) R.string.side_red_short else R.string.side_black_short)
        holder.difficulty.text = "Lv.${r.difficulty}"
        holder.date.text = dateFormat.format(Date(r.startTime))
        holder.moves.text = ctx.getString(R.string.replay_moves_summary, r.moves.size, r.endReason)
        val delta = if (r.ratingDelta >= 0) "+${r.ratingDelta}" else "${r.ratingDelta}"
        val rank = ctx.getString(UserRank.fromRating(r.playerRating).nameRes)
        holder.rating.text = ctx.getString(R.string.replay_rank_delta, rank, delta)
        holder.itemView.setOnClickListener { onClick(r) }
    }
}