package com.zhongguoxiangqi.ai

/**
 * AI 分析功能的代理配置.
 *
 * 镜像 AstroSimulator 的 [com.astro.simulator.ai.CloudAdvisorConfig]:
 * 真正用 LLM 的是本地代理, App 端只持有一个可以独立轮换的 proxy token.
 * 验证规则一致: 必须 HTTPS, 只允许在 127/10/172.16-31/192.168 私有网段
 * 上用 cleartext, 否则必须 HTTPS.
 */
data class AdvisorConfig(
    val baseUrl: String,
    val proxyToken: String,
    val model: String,
) {
    val normalizedBaseUrl: String
        get() = baseUrl.trim().trimEnd('/')

    fun validate() {
        // "未配置" 优先于 "必须 HTTPS" 报告, 否则用户看到 "必须 HTTPS"
        // 会去查 TLS 问题, 实际上根本没设置地址.
        require(normalizedBaseUrl.isNotBlank()) {
            "未配置云端代理地址: 请在 local.properties 写入\n" +
                "  qiyanAdvisorProxyBaseUrl=https://<your-proxy-host>/anthropic\n"
        }
        val hostPart = normalizedBaseUrl.removePrefix("https://").removePrefix("http://")
        require(hostPart.isNotBlank()) { "云端代理地址缺少主机名: $normalizedBaseUrl" }
        require(
            normalizedBaseUrl.startsWith("https://") ||
                (normalizedBaseUrl.startsWith("http://") && isPrivateHost(hostPart))
        ) {
            "云端代理必须使用 HTTPS; 仅本机或局域网地址 (127.0.0.1, 10.x, " +
                "172.16-31.x, 192.168.x) 可用 http:// 调试"
        }
        require(proxyToken.isNotBlank()) {
            "未配置云端代理令牌: 请在 local.properties 写入\n" +
                "  qiyanAdvisorProxyToken=<PROXY_ACCESS_TOKEN>\n"
        }
        require(model.isNotBlank()) { "未配置云端模型" }
    }

    companion object {
        /**
         * True for loopback and RFC 1918 private ranges.
         *
         * Cleartext is tolerated only for these because they can't leave the
         * local network — 跑代理的机器和手机在同一个 Wi-Fi 下, 这就是合法的
         * 调试路径. `10.0.2.2` (模拟器对宿主的回环) 落在 10.x 段.
         */
        internal fun isPrivateHost(hostPart: String): Boolean {
            val host = hostPart.substringBefore('/').substringBefore(':')
            if (host == "localhost" || host == "127.0.0.1" || host.startsWith("127.")) return true
            if (host.startsWith("10.")) return true
            if (host.startsWith("192.168.")) return true
            if (host.startsWith("172.")) {
                val second = host.split('.').getOrNull(1)?.toIntOrNull() ?: return false
                return second in 16..31
            }
            return false
        }

        fun fromBuildConfig(): AdvisorConfig = AdvisorConfig(
            baseUrl = com.zhongguoxiangqi.BuildConfig.ADVISOR_PROXY_BASE_URL,
            proxyToken = com.zhongguoxiangqi.BuildConfig.ADVISOR_PROXY_TOKEN,
            model = com.zhongguoxiangqi.BuildConfig.ADVISOR_MODEL,
        )
    }
}
