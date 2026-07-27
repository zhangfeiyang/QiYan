package com.zhongguoxiangqi.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import java.time.Duration

/**
 * 持有 [AnthropicClient] 单例. 整个 App 一个, 跟 ViewModel 生命周期对齐.
 *
 * 上游模型 key 永远在代理那侧 —— 这里只用可以独立轮换的 proxy token
 * 注入 SDK header, 见 [CloudAdvisorSession] 在 AstroSimulator 的实现.
 */
class AdvisorSession(private val config: AdvisorConfig) {

    val endpoint: String = config.normalizedBaseUrl
    val model: String = config.model

    @Volatile
    private var client: AnthropicClient? = null

    @Volatile
    private var closed = false

    /**
     * 懒建 client, 并且**先 validate**.
     *
     * 之前是在构造函数里直接 build: baseUrl 为空时 SDK 不会当场报错, 要等
     * 到真正发请求, OkHttp 才抛 "Expected URL scheme 'http' or 'https'" ——
     * 用户在对话框里看到的就是这句英文, 完全看不出是漏配了代理地址.
     * validate() 先跑, 未配置就直接是那句中文提示.
     */
    @Synchronized
    fun open(): AnthropicClient {
        check(!closed) { "云端会话已关闭" }
        client?.let { return it }

        config.validate()
        return AnthropicOkHttpClient.builder()
            .baseUrl(config.normalizedBaseUrl)
            .authToken(config.proxyToken)
            .maxRetries(MAX_RETRIES)
            .timeout(Duration.ofSeconds(STREAM_TIMEOUT_SECONDS))
            .build()
            .also { client = it }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        runCatching { client?.close() }
        client = null
    }

    companion object {
        private const val MAX_RETRIES = 2
        private const val STREAM_TIMEOUT_SECONDS = 90L
    }
}
