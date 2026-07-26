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
            // 整段答完了. Done 携带 finalText 是为了让 ViewModel 把
            // 当前 Assistant 消息固化到 history.
            trySend(AdvisorEvent.Done)
        } catch (e: UnauthorizedException) {
            trySend(AdvisorEvent.Error("代理令牌被拒, 请联系开发者"))
        } catch (e: PermissionDeniedException) {
            trySend(AdvisorEvent.Error("代理拒绝访问, 可能是额度用尽"))
        } catch (e: Throwable) {
            // 真正的网络错误. 不要让 stacktrace 露到用户面前.
            trySend(AdvisorEvent.Error(e.message ?: "分析暂时不可用, 稍后再试"))
        }
    }.flowOn(streamDispatcher)

    companion object {
        private const val MAX_HISTORY_TURNS = 4
        private const val MAX_OUTPUT_TOKENS = 1024L
    }
}
