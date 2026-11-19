package com.lianwenqi.app.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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

    /**
     * 非 2xx 的 HTTP 响应（401 / 403 已单独归为 Unauthorized）。
     *
     * 404 最常见的原因是「请求地址不存在」：baseUrl 少了 /v1，或接口类型（chat completions /
     * responses / anthropic messages）与中转站实际支持的不一致。这里把服务端返回的具体
     * message 一并带出来，避免用户只看到一句「服务器返回错误：404」无法定位。
     */
    class Server(val code: Int, val body: String?) : AiError(
        buildString {
            if (code == 404) {
                append("服务器返回 404（请求地址不存在）")
            } else {
                append("服务器返回错误：$code")
            }
            errorDetailOf(body)?.let { append("：").append(it) }
            if (code == 404) {
                append("。请检查：baseUrl 是否需要以 /v1 结尾、接口类型是否与中转站一致")
            }
        }
    )

    /** kotlinx.serialization 反序列化失败 */
    class Parse(val cause: Throwable) : AiError("响应格式异常")

    /** 本地配置不合法：apiKey / baseUrl 缺失，或 apiKey 含非法字符（如中文、控制字符） */
    class InvalidConfig(message: String) : AiError(message)

    /** 本地向量化工具相关：模型未下载 / 加载失败 / 本地推理失败 */
    class LocalEngine(message: String) : AiError(message)

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
 * 从服务端错误响应里提取一句可读的 message。
 * 兼容 {"error":{"message":"..."}} / {"message":"..."}；纯文本直传；
 * HTML 错误页（如网关 404 页）不展示，避免把整段标签塞进提示。
 */
private fun errorDetailOf(body: String?): String? {
    val text = body?.trim().orEmpty()
    if (text.isEmpty()) return null
    val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    val message = ((obj?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
        ?: (obj?.get("message") as? JsonPrimitive)?.contentOrNull
    val detail = message ?: text.takeUnless { it.startsWith("<") }
    return detail
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(160)
        ?.takeIf { it.isNotBlank() }
}

/**
 * 携带 [AiError] 的异常。
 * 用于「返回 Kotlin [Result] 的接口」传递错误类型（如 AiClient.listModels / fetchRemoteModels），
 * 上层 catch 后可通过 `error.userMessage` 展示，或按 `error` 子类分支。
 */
class AiException(val error: AiError) : Exception(error.userMessage)
