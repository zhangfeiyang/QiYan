package com.zhongguoxiangqi.ui.game

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.ai.AdvisorAgent
import com.zhongguoxiangqi.ai.AdvisorEvent
import com.zhongguoxiangqi.ai.AdvisorHistoryTurn
import com.zhongguoxiangqi.ai.AdvisorPromptBuilder
import com.zhongguoxiangqi.databinding.DialogGameAnalysisBinding
import com.zhongguoxiangqi.ui.advisor.MessageAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * AI 分析的透明对话框: 浮在对局主界面之上, 背景半透明能看到棋局。
 *
 * 打开时自动让 AI 总结当前局面, 之后可以继续追问。所有网络对话走
 * [AdvisorAgent] 流式返回, 增量刷新最后一条消息。
 */
class GameAnalysisDialog(
    context: Context,
    private val agent: AdvisorAgent?,
    private val configured: Boolean,
    private val contextPreamble: String,
    private val scope: CoroutineScope,
) : Dialog(context) {

    private lateinit var binding: DialogGameAnalysisBinding
    private val adapter = MessageAdapter()
    private val history = mutableListOf<AdvisorHistoryTurn>()
    private var busy = false
    private var currentJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogGameAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 透明背景 + 几乎不压暗, 棋局完全透出来
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.setDimAmount(0.06f)
        // 整窗贴底, 高度 60% 屏, 留 40% 给上面的扫描屏/棋局.
        // 用 attributes 一次性设宽高+gravity, 避免 setLayout / attributes
        // 互相覆盖导致 wrap_content 跳出来.
        // adjustResize 让键盘弹起时窗口收高, ConstraintLayout 自动重排
        // 建议条/输入栏, 不会被挤出可见区.
        val heightPx = (context.resources.displayMetrics.heightPixels * 0.6f).toInt()
        window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            heightPx
        )
        window?.setGravity(android.view.Gravity.BOTTOM)
        // adjustResize 对 Dialog 也生效, 但要 SOFT_INPUT_ADJUST_RESIZE flag
        // 一起开, 否则部分 ROM 不会触发.
        window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )

        binding.messages.layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
        binding.messages.adapter = adapter

        binding.btnSend.setOnClickListener { sendCurrentInput() }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput(); true
            } else false
        }
        // 建议条: 三个 TextView 都明确标 clickable/focusable, 之前用 bg_pill
        // 看起来"像按钮", 但默认 TextView 不可点, 用户点了没反应.
        // 整个点击事件还要用 runCatching 包住: 万一某个 chip 因为状态异常
        // 抛了 NullPointerException (比如 isFinishing 时碰 chip), 也不让
        // 整个 dialog 死掉.
        listOf(binding.suggestAnalyze, binding.suggestNext, binding.suggestReview).forEach { chip ->
            chip.setOnClickListener {
                runCatching { send(chip.text?.toString().orEmpty()) }
            }
        }
        // 点窗口外部不要自动关掉: 之前默认是 true, 用户点建议条/输入框时手
        // 一抖点到外面就被关掉了, 体验很糟. 还是要能用返回键关, 用
        // setCancelable(true) + setCanceledOnTouchOutside(false) 这对组合.
        setCancelable(true)
        setCanceledOnTouchOutside(false)
    }

    override fun onStart() {
        super.onStart()
        // 打开就总结当前局面
        send(context.getString(R.string.analysis_summary_prompt))
    }

    private fun sendCurrentInput() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        if (busy) {
            // 之前这里直接 return, 用户点了"发送"什么都不发生,
            // 看起来像是按钮坏了. 弹个 toast 让他知道是"上一条还没回完".
            android.widget.Toast.makeText(
                context,
                R.string.advisor_waiting,
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        binding.input.setText("")
        send(text)
    }

    private fun send(text: String) {
        if (text.isBlank()) return
        if (busy) {
            android.widget.Toast.makeText(
                context,
                R.string.advisor_waiting,
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!configured) {
            adapter.append(MessageAdapter.Message.User(text))
            adapter.append(MessageAdapter.Message.Error(context.getString(R.string.advisor_no_config)))
            return
        }
        val ag = agent ?: run {
            adapter.append(MessageAdapter.Message.Error(context.getString(R.string.advisor_unavailable)))
            return
        }
        busy = true
        binding.btnSend.isEnabled = false
        adapter.append(MessageAdapter.Message.User(text))
        val assistantIndex = adapter.itemCount
        adapter.append(MessageAdapter.Message.Assistant("", streaming = true))

        currentJob = scope.launch {
            val acc = StringBuilder()
            ag.run(userText = text, history = history.toList(), contextPreamble = contextPreamble)
                .collect { event ->
                    when (event) {
                        AdvisorEvent.Thinking -> Unit
                        is AdvisorEvent.Delta -> {
                            acc.append(event.text)
                            adapter.replaceText(assistantIndex, displayText(acc.toString()), true)
                        }
                        is AdvisorEvent.Error -> {
                            adapter.replaceText(assistantIndex, event.message, false)
                            busy = false
                            binding.btnSend.isEnabled = true
                        }
                        AdvisorEvent.AccountKeyRejected -> {
                            adapter.replaceText(
                                assistantIndex,
                                context.getString(R.string.advisor_key_rejected),
                                false
                            )
                            busy = false
                            binding.btnSend.isEnabled = true
                        }
                        AdvisorEvent.Done -> {
                            val answer = AdvisorPromptBuilder.parseAnswer(acc.toString())
                            adapter.replaceText(assistantIndex, answer.text, false)
                            history += AdvisorHistoryTurn(text, answer.text)
                            busy = false
                            binding.btnSend.isEnabled = true
                        }
                    }
                }
        }
    }

    private fun displayText(raw: String): String =
        AdvisorPromptBuilder.parseAnswer(raw).text

    override fun dismiss() {
        currentJob?.cancel()
        super.dismiss()
    }
}
