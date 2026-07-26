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
    private val onClick: (DifficultyLevel) -> Unit
) : RecyclerView.Adapter<DifficultyAdapter.VH>() {

    private var selectedPosition = 4  // 默认选中级

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val badge: View = view.findViewById(R.id.difficulty_badge)
        val name: TextView = view.findViewById(R.id.difficulty_name)
        val desc: TextView = view.findViewById(R.id.difficulty_desc)
        val elo: TextView = view.findViewById(R.id.difficulty_elo)
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
        holder.desc.text = ctx.getString(level.descRes)
        holder.elo.text = "Elo ${level.elo}"
        holder.badge.setBackgroundColor(ContextCompat.getColor(ctx, level.color))
        holder.itemView.isSelected = (position == selectedPosition)
        holder.itemView.setOnClickListener {
            val oldPos = selectedPosition
            selectedPosition = holder.bindingAdapterPosition
            notifyItemChanged(oldPos)
            notifyItemChanged(selectedPosition)
            onClick(level)
        }
    }
}