package com.zhongguoxiangqi.ui.advisor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.zhongguoxiangqi.R
import androidx.lifecycle.viewModelScope
import com.zhongguoxiangqi.ai.AdvisorAgent
import com.zhongguoxiangqi.ai.AdvisorConfig
import com.zhongguoxiangqi.ai.AdvisorEvent
import com.zhongguoxiangqi.ai.AdvisorHistoryTurn
import com.zhongguoxiangqi.ai.AdvisorPromptBuilder
import com.zhongguoxiangqi.ai.AdvisorSession
import com.zhongguoxiangqi.ai.BoardContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * AdvisorActivity 的 ViewModel.
 *
 * 启动时 [attach] 把 context 拼好, 此后:
 * - [send] 把用户输入压到消息列表, 起 [AdvisorAgent.run] 流式拉回
 * - 每一段 Delta 都 update 列表里**那条** Assistant 消息, 而不是塞新消息
 * - 完成后把当前 assistant 消息固化为历史轮
 *
 * 退出 Activity 时 [onCleared] 关掉 SDK client.
 */
class AdvisorViewModel(app: Application) : AndroidViewModel(app) {

    private val config: AdvisorConfig = runCatching { AdvisorConfig.fromBuildConfig() }
        .getOrElse { AdvisorConfig(baseUrl = "", proxyToken = "", model = "") }

    private val session: AdvisorSession? = runCatching { AdvisorSession(config) }.getOrNull()
    private val agent: AdvisorAgent? = session?.let {
        AdvisorAgent(clientProvider = { it.open() }, model = it.model)
    }

    private val _messages = MutableStateFlow<List<MessageAdapter.Message>>(emptyList())
    val messages: StateFlow<List<MessageAdapter.Message>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _configured = MutableStateFlow(config.baseUrl.isNotBlank() && config.proxyToken.isNotBlank())
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    /** AI 最近一次讲到的具体着法 (UCI 起点/终点), 供界面在棋盘上演示 */
    private val _suggestedMove = MutableStateFlow<Pair<String, String>?>(null)
    val suggestedMove: StateFlow<Pair<String, String>?> = _suggestedMove.asStateFlow()

    private var context: String = ""
    private var history: MutableList<AdvisorHistoryTurn> = mutableListOf()
    private var currentJob: Job? = null

    fun attach(context: String) {
        this.context = context
    }

    fun send(text: String) {
        if (text.isBlank() || _busy.value) return
        // agent 非空不代表能用: session 现在是懒建的, 漏配代理地址要到
        // open() 才发现. 所以未配置在这里就拦掉, 不必白跑一轮网络.
        if (!_configured.value) {
            _messages.update {
                it + MessageAdapter.Message.User(text) +
                    MessageAdapter.Message.Error(getApplication<Application>().getString(R.string.advisor_no_config))
            }
            return
        }
        val ag = agent ?: run {
            _messages.update {
                it + MessageAdapter.Message.Error(getApplication<Application>().getString(R.string.advisor_unavailable))
            }
            return
        }
        _busy.value = true
        _messages.update { it + MessageAdapter.Message.User(text) }
        val assistantIndex = _messages.value.size
        _messages.update { it + MessageAdapter.Message.Assistant("", streaming = true) }

        currentJob = viewModelScope.launch {
            var acc = StringBuilder()
            ag.run(userText = text, history = history.toList(), contextPreamble = context)
                .collect { event ->
                    when (event) {
                        AdvisorEvent.Thinking -> Unit
                        is AdvisorEvent.Delta -> {
                            acc.append(event.text)
                            _messages.value = _messages.value.toMutableList().also {
                                it[assistantIndex] = MessageAdapter.Message.Assistant(
                                    displayTextOf(acc.toString()), streaming = true
                                )
                            }
                        }
                        is AdvisorEvent.Error -> {
                            _messages.value = _messages.value.toMutableList().also {
                                it[assistantIndex] = MessageAdapter.Message.Error(event.message)
                            }
                            _busy.value = false
                        }
                        AdvisorEvent.AccountKeyRejected -> {
                            _messages.value = _messages.value.toMutableList().also {
                                it[assistantIndex] = MessageAdapter.Message.Error(
                                    getApplication<Application>().getString(R.string.advisor_key_rejected)
                                )
                            }
                            _busy.value = false
                        }
                        AdvisorEvent.Done -> {
                            val answer = AdvisorPromptBuilder.parseAnswer(acc.toString())
                            _messages.value = _messages.value.toMutableList().also {
                                it[assistantIndex] = MessageAdapter.Message.Assistant(
                                    answer.text, streaming = false
                                )
                            }
                            // 历史里存剥过标记的正文: 带着标记回灌会让模型
                            // 以为那是对话的一部分, 下一轮开始学着乱标.
                            history += AdvisorHistoryTurn(text, answer.text)
                            _suggestedMove.value =
                                if (answer.fromUci != null && answer.toUci != null) {
                                    answer.fromUci to answer.toUci
                                } else null
                            _busy.value = false
                        }
                    }
                }
        }
    }

    /**
     * 流式过程中要显示的正文.
     *
     * 标记是逐 token 吐出来的, 中途会出现 "[MOVE:h2e" 这种半截字符串 —— 直接
     * 显示会让用户看到一串乱码闪一下. 所以完整标记直接剥掉, 末尾那个还没写完
     * 的也一并截掉, 等它写完再说.
     */
    private fun displayTextOf(raw: String): String {
        val stripped = AdvisorPromptBuilder.parseAnswer(raw).text
        val open = stripped.lastIndexOf('[')
        return if (open >= 0 && !stripped.substring(open).contains(']')) {
            stripped.substring(0, open).trimEnd()
        } else {
            stripped
        }
    }

    fun clear() {
        if (_busy.value) currentJob?.cancel()
        history.clear()
        _messages.value = emptyList()
        _suggestedMove.value = null
        _busy.value = false
    }

    fun snapshotContext() = context

    override fun onCleared() {
        super.onCleared()
        session?.close()
    }
}
