package com.zhongguoxiangqi.ui.advisor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.zhongguoxiangqi.R

/**
 * 三种 viewType: 用户 / 助手 / 错误.
 *
 * 助手消息在流式打字时**同一行**反复 update 文本内容, 用 [notifyItemChanged]
 * 加 [payload] 比刷新整个 item 性能好, 滚动状态不会跳.
 */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {

    sealed class Message {
        data class User(val text: String) : Message()
        /** [streaming] = true 时此消息尚未完成, 还会 update */
        data class Assistant(val text: String, val streaming: Boolean) : Message()
        data class Error(val text: String) : Message()
    }

    private val items = mutableListOf<Message>()

    fun submit(newItems: List<Message>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun append(item: Message) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    fun replaceText(index: Int, text: String, streaming: Boolean) {
        if (index !in items.indices) return
        val old = items[index]
        if (old is Message.Assistant) {
            items[index] = Message.Assistant(text, streaming)
            notifyItemChanged(index, PAYLOAD_TEXT)
        }
    }

    fun markComplete(index: Int) {
        if (index !in items.indices) return
        val old = items[index]
        if (old is Message.Assistant) {
            items[index] = Message.Assistant(old.text, streaming = false)
            notifyItemChanged(index, PAYLOAD_TEXT)
        }
    }

    fun snapshot(): List<Message> = items.toList()

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Message.User -> TYPE_USER
        is Message.Assistant -> TYPE_ASSISTANT
        is Message.Error -> TYPE_ERROR
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val res = when (viewType) {
            TYPE_USER -> R.layout.item_message_user
            TYPE_ASSISTANT -> R.layout.item_message_assistant
            TYPE_ERROR -> R.layout.item_message_error
            else -> R.layout.item_message_error
        }
        val view = LayoutInflater.from(parent.context).inflate(res, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val text = when (val m = items[position]) {
            is Message.User -> m.text
            is Message.Assistant -> m.text
            is Message.Error -> m.text
        }
        holder.text.text = text
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_TEXT)) {
            val m = items[position]
            if (m is Message.Assistant) holder.text.text = m.text
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.text)
    }

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
        private const val TYPE_ERROR = 2
        private const val PAYLOAD_TEXT = "text"
    }
}
