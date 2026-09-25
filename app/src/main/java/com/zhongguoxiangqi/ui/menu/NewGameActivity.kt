package com.zhongguoxiangqi.ui.menu

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.zhongguoxiangqi.ui.common.DifficultyPreferences
import androidx.recyclerview.widget.RecyclerView
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.databinding.ActivityNewGameBinding
import com.zhongguoxiangqi.databinding.ItemOpponentBinding
import com.zhongguoxiangqi.engine.PlayStyle
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.ui.common.Insets
import com.zhongguoxiangqi.ui.game.GameActivity

/**
 * 新对局: 挑对手 + 选执子.
 *
 * 首页的"开始对弈"仍然一键直接开上一盘的设定 —— 这一屏是想换对手时才进来的,
 * 不挡在每一次开局前面。见 [MainMenuActivity] 的对手卡。
 */
class NewGameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewGameBinding
    private lateinit var adapter: OpponentAdapter

    private var selected: DifficultyLevel = DifficultyLevel.entries.first()
    private var side: Int = Color.RED
    private var style: PlayStyle = PlayStyle.BALANCED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewGameBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        Insets.apply(binding.root, top = binding.toolbar, bottom = binding.bottomBar, extraBottomDp = 8f)

        val prefs = getSharedPreferences(GameActivity.PREFS_GAME, MODE_PRIVATE)
        val lastId = DifficultyPreferences.read(this)
        selected = DifficultyLevel.entries.firstOrNull { it.id == lastId }
            ?: DifficultyLevel.entries.first()
        // 执子偏好单独存: GameActivity 写回的 KEY_LAST_SIDE 是**实际**执的
        // 那一方(随机掷完的结果), 拿它当偏好读回来, 用户选的"随机"就永远
        // 只会显示成红或黑, 再也回不到随机那一档.
        side = prefs.getInt(KEY_SIDE_PREF, Color.RED)
        style = PlayStyle.fromId(prefs.getInt(GameActivity.KEY_LAST_STYLE, PlayStyle.BALANCED.id))

        adapter = OpponentAdapter(selected.id) { level ->
            selected = level
            bindSelected()
        }
        binding.opponentGrid.layoutManager = LinearLayoutManager(this)
        binding.opponentGrid.adapter = adapter
        bindSelected()

        // 棋路风格: 四选一, 描述跟着点亮的那档走
        binding.styleBalanced.text = getString(PlayStyle.BALANCED.nameRes)
        binding.styleAggressive.text = getString(PlayStyle.AGGRESSIVE.nameRes)
        binding.styleSolid.text = getString(PlayStyle.SOLID.nameRes)
        binding.styleSwarm.text = getString(PlayStyle.SWARM.nameRes)
        binding.styleGroup.check(
            when (style) {
                PlayStyle.AGGRESSIVE -> R.id.style_aggressive
                PlayStyle.SOLID -> R.id.style_solid
                PlayStyle.SWARM -> R.id.style_swarm
                else -> R.id.style_balanced
            }
        )
        binding.styleDesc.text = getString(style.descRes)
        binding.styleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            style = when (checkedId) {
                R.id.style_aggressive -> PlayStyle.AGGRESSIVE
                R.id.style_solid -> PlayStyle.SOLID
                R.id.style_swarm -> PlayStyle.SWARM
                else -> PlayStyle.BALANCED
            }
            binding.styleDesc.text = getString(style.descRes)
            bindSelected()
        }

        binding.sideGroup.check(
            when (side) {
                Color.BLACK -> R.id.side_black
                RANDOM_SIDE -> R.id.side_random
                else -> R.id.side_red
            }
        )
        binding.sideGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            side = when (checkedId) {
                R.id.side_black -> Color.BLACK
                R.id.side_random -> RANDOM_SIDE
                else -> Color.RED
            }
            prefs.edit().putInt(KEY_SIDE_PREF, side).apply()
        }

        fitChoices(binding.styleGroup)
        fitChoices(binding.sideGroup)
        binding.btnStart.setOnClickListener {
            // 随机在**开局这一刻**掷, 不是存下来的那个值: 存 RANDOM 再在
            // GameActivity 里解析会让"上次执子"这个记忆变成每局都变, 而
            // 玩家选"随机"的意思是每局重掷, 不是"忘掉我选过什么".
            val actual = if (side == RANDOM_SIDE) {
                if (java.util.Random().nextBoolean()) Color.RED else Color.BLACK
            } else {
                side
            }
            startActivity(
                Intent(this, GameActivity::class.java).apply {
                    putExtra(GameActivity.EXTRA_DIFFICULTY, selected.id)
                    putExtra(GameActivity.EXTRA_PLAYER_SIDE, actual)
                    putExtra(GameActivity.EXTRA_STYLE, style.id)
                }
            )
            finish()
        }
    }

    /** Let translated labels and large text determine when the choices need more room. */
    private fun fitChoices(group: com.google.android.material.button.MaterialButtonToggleGroup) {
        group.post {
            val widthPerChoice = group.width / group.childCount.coerceAtLeast(1)
            val needsColumn = (0 until group.childCount).any { index ->
                val button = group.getChildAt(index) as android.widget.TextView
                button.paint.measureText(button.text.toString()) + button.paddingLeft + button.paddingRight > widthPerChoice
            }
            if (needsColumn || resources.configuration.fontScale > 1.2f) {
                group.orientation = android.widget.LinearLayout.VERTICAL
                for (index in 0 until group.childCount) {
                    group.getChildAt(index).layoutParams = android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            }
        }
    }

    private fun bindSelected() {
        binding.styleOptions.visibility = android.view.View.VISIBLE
        binding.trainingNote.visibility = if (selected.id <= 2) android.view.View.VISIBLE else android.view.View.GONE
        adapter.select(selected.id)
    }

    private class OpponentAdapter(
        private var selectedId: Int,
        private val onPick: (DifficultyLevel) -> Unit
    ) : RecyclerView.Adapter<OpponentAdapter.VH>() {

        private val levels = DifficultyLevel.entries

        class VH(val b: ItemOpponentBinding) : RecyclerView.ViewHolder(b.root)

        fun select(id: Int) {
            if (id == selectedId) return
            val old = levels.indexOfFirst { it.id == selectedId }
            val new = levels.indexOfFirst { it.id == id }
            selectedId = id
            // 只刷新真正换了状态的两格, 而不是 notifyDataSetChanged() ——
            // 后者会把五个选项连同头像的 mutate() 一起重建一遍.
            if (old >= 0) notifyItemChanged(old)
            if (new >= 0) notifyItemChanged(new)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemOpponentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = levels.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val level = levels[position]
            OpponentAvatar.bind(holder.b.avatar, level)
            holder.b.levelNo.text = holder.itemView.context.getString(level.nameRes)
            holder.b.levelDesc.text = holder.itemView.context.getString(level.descRes)
            holder.b.selectedMark.visibility = if (level.id == selectedId) android.view.View.VISIBLE else android.view.View.INVISIBLE
            holder.b.root.contentDescription = buildString {
                append(holder.b.levelNo.text).append(". ").append(holder.b.levelDesc.text)
                if (level.id == selectedId) append(". ").append(holder.itemView.context.getString(R.string.new_game_selected))
            }
            holder.b.root.isSelected = level.id == selectedId
            holder.b.root.setOnClickListener { onPick(level) }
        }
    }

    private companion object {
        /**
         * "随机执子"的哨兵值.
         *
         * 必须与 [Color.RED]=1 / [Color.BLACK]=0 / [Color.NONE]=-1 都不同,
         * 否则存进 prefs 后读回来会被当成某一方。
         */
        const val RANDOM_SIDE = 2

        /** 用户选的执子偏好(可能是 RANDOM_SIDE), 与"上一局实际执的那方"分开存 */
        const val KEY_SIDE_PREF = "side_pref"
    }
}
