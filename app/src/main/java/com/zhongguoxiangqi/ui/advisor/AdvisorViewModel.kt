package com.zhongguoxiangqi.ui.advisor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhongguoxiangqi.ai.AdvisorAgent
import com.zhongguoxiangqi.ai.AdvisorConfig
import com.zhongguoxiangqi.ai.AdvisorEvent
import com.zhongguoxiangqi.ai.AdvisorHistoryTurn
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

    private var context: String = ""
    private var history: MutableList<AdvisorHistoryTurn> = mutableListOf()
    private var currentJob: Job? = null

    fun attach(context: String) {
        this.context = context
    }

    fun send(text: String) {
        if (text.isBlank() || _busy.value) return
        val ag = agent ?: run {
            _messages.update {
                it + MessageAdapter.Message.Error(
                    if (_configured.value) "AI 暂时不可用, 请稍后再试"
                    else "AI 功能未配置, 请联系开发者"
                )
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
                                    acc.toString(), streaming = true
                                )
                            }
                        }
                        is AdvisorEvent.Error -> {
                            // 出错时把正在打字的占位消息换成错误消息.
                            _messages.value = _messages.value.toMutableList().also {
                                it[assistantIndex] = MessageAdapter.Message.Error(event.message)
                            }
                            _busy.value = false
                        }
                        AdvisorEvent.Done -> {
                            _messages.value = _messages.value.toMutableList().also {
                                it[assistantIndex] = MessageAdapter.Message.Assistant(
                                    acc.toString(), streaming = false
                                )
                            }
                            history += AdvisorHistoryTurn(text, acc.toString())
                            _busy.value = false
                        }
                    }
                }
        }
    }

    fun clear() {
        if (_busy.value) currentJob?.cancel()
        history.clear()
        _messages.value = emptyList()
        _busy.value = false
    }

    fun snapshotContext() = context

    override fun onCleared() {
        super.onCleared()
        session?.close()
    }
}
