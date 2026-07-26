package com.zhongguoxiangqi.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient

/**
 * 持有 [AnthropicClient] 单例. 整个 App 一个, 跟 ViewModel 生命周期对齐.
 *
 * 上游模型 key 永远在代理那侧 —— 这里只用可以独立轮换的 proxy token
 * 注入 SDK header, 见 [CloudAdvisorSession] 在 AstroSimulator 的实现.
 */
class AdvisorSession(private val config: AdvisorConfig) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .baseUrl(config.normalizedBaseUrl)
        .authToken(config.proxyToken)
        .build()

    val endpoint: String = config.normalizedBaseUrl
    val model: String = config.model

    fun open(): AnthropicClient = client

    fun close() {
        runCatching { client.close() }
    }
}
