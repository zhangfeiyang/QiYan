package com.zhongguoxiangqi.ui.menu

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.zhongguoxiangqi.R

/**
 * 难度选择列表适配器.
 */
class DifficultyAdapter(
    private val levels: List<DifficultyLevel>,
    selectedId: Int,
    private val onClick: (DifficultyLevel) -> Unit
) : RecyclerView.Adapter<DifficultyAdapter.VH>() {

    /**
     * 高亮的是**当前这盘用的那一档**.
     *
     * 原来这里写死 4 —— 不管你在下 Lv.1 还是 Lv.15, 打开选择器都指着第 5 档,
     * 看上去就像"我上次选的没存住"。
     */
    private var selectedPosition = levels.indexOfFirst { it.id == selectedId }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val badge: View = view.findViewById(R.id.difficulty_badge)
        val name: TextView = view.findViewById(R.id.difficulty_name)
        val segment: TextView = view.findViewById(R.id.difficulty_segment)
        val desc: TextView = view.findViewById(R.id.difficulty_desc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_difficulty, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = levels.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val level = levels[position]
        val ctx = holder.itemView.context
        holder.name.text = ctx.getString(level.nameRes)
        holder.segment.text = ctx.getString(level.segmentRes)
        holder.desc.text = ctx.getString(level.descRes)
        holder.badge.setBackgroundColor(ContextCompat.getColor(ctx, level.color))
        holder.itemView.isSelected = (position == selectedPosition)
        holder.itemView.setOnClickListener {
            val oldPos = selectedPosition
            selectedPosition = holder.bindingAdapterPosition
            if (oldPos >= 0) notifyItemChanged(oldPos)
            notifyItemChanged(selectedPosition)
            onClick(level)
        }
    }
}