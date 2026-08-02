package com.zhongguoxiangqi.ui.advisor

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
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

    /** submit 的刷新方案. 放在类体而不是 companion —— 嵌在 companion 里
     *  外部要写 MessageAdapter.Companion.Update, 平白多一层. */
    internal sealed class Update {
        /** 内容没变, 一次都不用刷 */
        object None : Update()
        /** 只有 [index] 这一条的文字变了, 走 payload 轻量路径 */
        data class Item(val index: Int) : Update()
        /** 结构变了, 只能全量刷 */
        object All : Update()
    }

    private val items = mutableListOf<Message>()

    /**
     * 提交新的消息列表.
     *
     * 流式回答时 ViewModel **每收到一个 token 就发一次新列表**, 这里原来无脑
     * notifyDataSetChanged —— 一条几百字的回答就是几百次全列表重绑, 每次都要
     * 把所有气泡重新 measure/layout, 还会打断 TextView 的文字选择状态.
     *
     * 绝大多数情况下变化只有一处: 最后那条 Assistant 消息的文字变长了. 认出
     * 这个模式就只刷那一条, 并且带 payload 走"只改文字"的轻量路径.
     */
    fun submit(newItems: List<Message>) {
        // 判断必须在改 items 之前做完 —— 改完再比就是拿新列表和它自己比.
        val plan = diff(items, newItems)
        items.clear()
        items.addAll(newItems)
        when (plan) {
            is Update.None -> Unit
            is Update.Item -> notifyItemChanged(plan.index, PAYLOAD_TEXT)
            is Update.All -> notifyDataSetChanged()
        }
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
        when (val m = items[position]) {
            is Message.User -> holder.text.text = m.text
            is Message.Assistant -> holder.text.text = renderAssistant(m, holder.text.context.getString(R.string.advisor_thinking))
            is Message.Error -> holder.text.text = m.text
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_TEXT)) {
            val m = items[position]
            if (m is Message.Assistant) holder.text.text = renderAssistant(m, holder.text.context.getString(R.string.advisor_thinking))
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

        /**
         * 空的流式占位气泡会显示成一个莫名其妙的小圆块 —— 请求发出到第一个
         * token 之间有好几秒, 那几秒里用户看到的就是它. 补一句状态文案.
         */

        /**
         * 算出该怎么刷.
         *
         * 流式回答时每个 token 都会调一次, 所以这里不做任何分配 —— 用下标遍历
         * 而不是 dropLast/filter 之类会建临时列表的写法.
         */
        internal fun diff(old: List<Message>, new: List<Message>): Update {
            if (old.size != new.size) return Update.All
            var idx = -1
            var count = 0
            for (i in old.indices) {
                if (old[i] != new[i]) {
                    if (count == 0) idx = i
                    count++
                    if (count > 1) return Update.All
                }
            }
            if (count == 0) return Update.None
            // 只有"仍然是 Assistant 换了文字"才能局部刷.
            // 出错时同一下标会从 Assistant 变成 Error, viewType 跟着变, 那时
            // 用 payload 会把新数据绑到旧类型的 ViewHolder 上.
            return if (old[idx] is Message.Assistant && new[idx] is Message.Assistant)
                Update.Item(idx) else Update.All
        }

        private val BOLD = Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)

        /** 一段加粗区间, 下标针对**剥掉星号之后**的正文 */
        internal data class BoldRange(val start: Int, val end: Int)

        internal data class Parsed(val text: String, val bold: List<BoldRange>)

        /**
         * 纯函数: 剥掉 `**` 标记, 同时记下每段加粗在结果文本里的位置.
         *
         * 和上 span 分开是为了能测 —— SpannableStringBuilder 是 android.jar
         * 的桩类, 在普通单元测试里一碰就抛 "not mocked", 把字符串逻辑关在
         * 它里面就等于永远测不了.
         */
        /**
         * 行首的 `* ` / `- ` 换成 `• `.
         *
         * 必须在剥加粗之前做, 否则 `**加粗**` 开头的行会被误认成列表项;
         * 也因此这里要求星号后面跟空格 —— `* 形势判断` 是列表, `**均势**`
         * 不是.
         */
        internal fun bulletize(raw: String): String =
            raw.lineSequence().joinToString("\n") { line ->
                val trimmed = line.trimStart()
                val indent = line.length - trimmed.length
                if (trimmed.startsWith("* ") || trimmed.startsWith("- ")) {
                    line.substring(0, indent) + "• " + trimmed.substring(2).trimStart()
                } else {
                    line
                }
            }

        internal fun parseBold(raw: String): Parsed {
            if (!raw.contains("**")) return Parsed(raw, emptyList())
            val sb = StringBuilder()
            val ranges = ArrayList<BoldRange>()
            var cursor = 0
            for (match in BOLD.findAll(raw)) {
                sb.append(raw, cursor, match.range.first)
                val start = sb.length
                sb.append(match.groupValues[1])
                ranges += BoldRange(start, sb.length)
                cursor = match.range.last + 1
            }
            sb.append(raw, cursor, raw.length)
            return Parsed(sb.toString(), ranges)
        }

        internal fun renderAssistant(m: Message.Assistant, thinkingText: String = "正在分析…"): CharSequence =
            if (m.streaming && m.text.isEmpty()) thinkingText else markdownBold(m.text)

        /**
         * 只处理 `**加粗**`.
         *
         * 模型答的是 Markdown, 而 TextView 原样显示, 于是"局面为**均势**"里的
         * 星号直接糊在脸上. 这里不引整套 Markdown 渲染库: 棋理分析实际只会用
         * 到加粗和分行, 为这点需求拖一个依赖不划算.
         */
        internal fun markdownBold(raw: String): CharSequence {
            val (text, ranges) = parseBold(bulletize(raw))
            if (ranges.isEmpty()) return text
            val out = SpannableStringBuilder(text)
            for (r in ranges) {
                out.setSpan(
                    StyleSpan(Typeface.BOLD),
                    r.start, r.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            return out
        }
    }
}
