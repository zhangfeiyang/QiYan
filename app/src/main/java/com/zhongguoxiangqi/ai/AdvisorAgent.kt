package com.zhongguoxiangqi.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn

/**
 * 一轮完成的对话, 下一轮作为历史带回去. 跟 [AstroAgent] 的
 * [com.astro.simulator.ai.AgentHistoryTurn] 同样语义.
 */
data class AdvisorHistoryTurn(val userText: String, val assistantText: String)

sealed class AdvisorEvent {
    object Thinking : AdvisorEvent()
    data class Delta(val text: String) : AdvisorEvent()
    data class Error(val message: String) : AdvisorEvent()
    object AccountKeyRejected : AdvisorEvent()
    object Done : AdvisorEvent()
}

/**
 * 流式对话循环. 棋眼不要 tool-calling —— 上下文全在 system prompt 里,
 * 让模型直接回答. 走 [AstroAgent] 的纯文本路径.
 */
class AdvisorAgent(
    private val clientProvider: () -> AnthropicClient,
    private val model: String,
    private val streamDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    fun run(
        userText: String,
        history: List<AdvisorHistoryTurn>,
        contextPreamble: String,
    ): Flow<AdvisorEvent> = channelFlow {
        try {
            send(AdvisorEvent.Thinking)

            val client = clientProvider()
            val conversation = ArrayList<MessageParam>()
            history.takeLast(MAX_HISTORY_TURNS).forEach { turn ->
                conversation += MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(turn.userText)
                    .build()
                conversation += MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .content(turn.assistantText)
                    .build()
            }
            conversation += MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(userText)
                .build()

            val params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .system(AdvisorPromptBuilder.systemPrompt(contextPreamble))
                .messages(conversation.toList())
                .build()

            val finalText = StringBuilder()
            client.messages().createStreaming(params).use { response ->
                response.stream().use { stream ->
                    stream.forEach { event ->
                        event.contentBlockDelta().ifPresent { blockDelta ->
                            blockDelta.delta().text().ifPresent { textDelta ->
                                val chunk = textDelta.text()
                                if (chunk.isNotEmpty()) {
                                    finalText.append(chunk)
                                    trySend(AdvisorEvent.Delta(chunk))
                                }
                            }
                        }
                    }
                }
            }
            trySend(AdvisorEvent.Done)
        } catch (t: Throwable) {
            when {
                isUnknownKey(t) -> send(AdvisorEvent.AccountKeyRejected)
                else -> send(AdvisorEvent.Error(describeFailure(t)))
            }
        }
    }.flowOn(streamDispatcher)

    private fun describeFailure(t: Throwable): String = Companion.describeFailure(t)

    companion object {
        private const val MAX_HISTORY_TURNS = 4
        private const val MAX_OUTPUT_TOKENS = 1024L

        internal fun isUnknownKey(t: Throwable): Boolean {
            var current: Throwable? = t
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                val c = current
                if (c is UnauthorizedException) {
                    val fields = runCatching {
                        @Suppress("UNCHECKED_CAST")
                        c.body().convert(Map::class.java) as? Map<String, Any?>
                    }.getOrNull()
                    return fields?.get("error") == "unknown_key"
                }
                current = c.cause.takeIf { it !== c }
                depth++
            }
            return false
        }

        internal fun describeFailure(t: Throwable): String {
            // AdvisorConfig.validate() / AdvisorSession.open() 的 require/check
            // 本身就写了成句的中文提示, 原样透出, 不要再套一层"请求失败:".
            if (t is IllegalArgumentException || t is IllegalStateException) {
                t.message?.takeIf { it.isNotBlank() }?.let { return it }
            }

            val chain = buildString {
                var current: Throwable? = t
                var depth = 0
                while (current != null && depth < MAX_CAUSE_DEPTH) {
                    append(current.javaClass.simpleName).append(' ')
                    current.message?.let { append(it).append(' ') }
                    current = current.cause.takeIf { it !== current }
                    depth++
                }
            }
            fun has(vararg needles: String) =
                needles.any { chain.contains(it, ignoreCase = true) }

            return when {
                has("401", "unauthorized", "authentication") ->
                    "云端代理拒绝了请求（凭据无效）。请检查代理令牌配置。"
                has("403", "forbidden") ->
                    "云端代理拒绝了请求（无权限）。请确认令牌是否仍然有效。"
                has("429", "rate limit") ->
                    "云端请求过于频繁，请稍后再试。"
                has("UnknownHost", "ConnectException", "NoRouteToHost", "unreachable") ->
                    "连不上云端代理，请确认设备网络和代理地址。"
                has("SocketTimeout", "timeout", "timed out") ->
                    "云端响应超时，请检查网络后重试。"
                has("SSL", "CertPath", "handshake") ->
                    "与云端建立安全连接失败，请检查证书或代理地址。"
                has("cleartext") ->
                    "系统拦截了明文 HTTP 请求，请改用 HTTPS 代理地址。"
                has("500", "502", "503", "504", "upstream unavailable") ->
                    "云端服务暂时不可用，请稍后再试。"
                else -> {
                    val detail = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                    "请求失败：$detail"
                }
            }
        }

        private const val MAX_CAUSE_DEPTH = 8
    }
}
