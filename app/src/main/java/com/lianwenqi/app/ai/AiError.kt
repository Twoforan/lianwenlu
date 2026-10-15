package com.lianwenqi.app.ai

/**
 * AI 请求错误的分类。
 *
 * 原先所有失败都被压成一句字符串（如「读取模型失败：xxx」），上层无法区分性质。
 * 现在按「HTTP 状态码 / 异常类型」归到不同子类：上层可直接展示 [userMessage]，
 * 也可以按具体子类做差异化处理（例如 Unauthorized 引导去设置页重新填 Key）。
 */
sealed class AiError(val userMessage: String) {
    /** 401 / 403：API Key 无效或已过期 */
    class Unauthorized : AiError("API Key 无效或已过期")

    /** IOException 等网络层失败 */
    class Network(val cause: Throwable) : AiError("网络连接失败")

    /** SocketTimeoutException：连接 / 读取超时 */
    class Timeout : AiError("请求超时")

    /** 非 2xx 的 HTTP 响应（401 / 403 已单独归为 Unauthorized） */
    class Server(val code: Int, val body: String?) : AiError("服务器返回错误：$code")

    /** kotlinx.serialization 反序列化失败 */
    class Parse(val cause: Throwable) : AiError("响应格式异常")

    /** 本地配置不合法：apiKey / baseUrl 缺失，或 apiKey 含非法字符（如中文、控制字符） */
    class InvalidConfig(message: String) : AiError(message)

    /** 其他未归类错误 */
    class Unknown(val cause: Throwable) : AiError("未知错误：${cause.message}")

    companion object {
        /** 按异常类型映射错误：超时 / 解析（JSON）/ 网络 / 配置非法 / 未知。AiClient 与上层兜底共用同一口径 */
        fun fromThrowable(e: Throwable): AiError = when (e) {
            is java.net.SocketTimeoutException -> Timeout()
            is kotlinx.serialization.SerializationException -> Parse(e)
            is java.io.IOException -> Network(e)
            // OkHttp 对非法 header（中文 apiKey）/ 非法 url 会抛 IllegalArgumentException
            is IllegalArgumentException -> InvalidConfig(e.message ?: "请求配置不合法")
            else -> Unknown(e)
        }
    }
}

/**
 * 携带 [AiError] 的异常。
 * 用于「返回 Kotlin [Result] 的接口」传递错误类型（如 AiClient.listModels / fetchRemoteModels），
 * 上层 catch 后可通过 `error.userMessage` 展示，或按 `error` 子类分支。
 */
class AiException(val error: AiError) : Exception(error.userMessage)
