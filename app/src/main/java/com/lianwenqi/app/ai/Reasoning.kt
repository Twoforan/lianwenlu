package com.lianwenqi.app.ai

/**
 * 思考模式 / 思考强度：把用户选择的统一档位翻译成各模型后端真正认识的原生参数。
 *
 * 实现思路（对齐 terva 的 NormalizeReasoning）：
 *  1. 标准化：把外部输入（存储值 / 中文 / 大小写混杂 / 空值）统一成内部档位 low/medium/high/max；
 *  2. 查表映射：按模型名找到它支持的原生参数方式与档位范围；
 *  3. 降级输出：档位超出该模型支持范围时，夹取（clamp）到最近的可用值。
 *
 * 未识别的模型一律按 [ReasoningStyle.UNSUPPORTED] 处理 —— 不发送任何思考参数，
 * 保证与旧版本行为完全一致，避免给不支持的模型发参数导致 400。
 */

/** 思考强度档位，由低到高：低 < 中 < 高 < 极（极 = 最大） */
enum class ReasoningLevel(val storage: String, val label: String) {
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),
    MAX("max", "极");

    companion object {
        /** 默认档位：最高强度 */
        val DEFAULT: ReasoningLevel = MAX

        /** 设置页展示顺序（低 → 极） */
        val ordered: List<ReasoningLevel> = listOf(LOW, MEDIUM, HIGH, MAX)

        /** 标准化：把外部输入统一成内部档位 */
        fun normalize(raw: String?): ReasoningLevel = when (raw?.trim()?.lowercase()) {
            "low", "低" -> LOW
            "medium", "中" -> MEDIUM
            "high", "高" -> HIGH
            "max", "极", "highest", "ultra" -> MAX
            else -> DEFAULT
        }

        /** OpenAI 系 reasoning_effort 的取值（只到 high，没有「极」） */
        fun effortValue(level: ReasoningLevel): String = when (level) {
            LOW -> "low"
            MEDIUM -> "medium"
            HIGH, MAX -> "high"
        }

        /**
         * DeepSeek 系 reasoning_effort 的取值：原生只有 low / high / max 三档（外加 none 表示关闭）。
         * 本应用的四档按比例落到这三档上，「中」与「高」同为 high，「极」为 max。
         */
        fun deepseekEffortValue(level: ReasoningLevel): String = when (level) {
            LOW -> "low"
            MEDIUM, HIGH -> "high"
            MAX -> "max"
        }

        /** 思考预算 token（Anthropic thinking.budget_tokens / Qwen thinking_budget） */
        fun budgetTokens(level: ReasoningLevel): Int = when (level) {
            LOW -> 1024
            MEDIUM -> 4096
            HIGH -> 8192
            MAX -> 16384
        }
    }
}

/** 模型对思考参数的原生支持方式 */
enum class ReasoningStyle {
    /** OpenAI 系：reasoning_effort，仅支持到「高」，没有「极」 */
    OPENAI_EFFORT,

    /**
     * DeepSeek 系（V4.1-Flash / V4-Pro）：chat completions 用 reasoning_effort，
     * 取值 none / low / high / max —— none 即关闭思考，一个参数同时控制开关与强度。
     */
    DEEPSEEK,

    /** Anthropic 系：thinking.type + budget_tokens，四档齐全 */
    ANTHROPIC,

    /** 通义千问系：chat_template_kwargs.enable_thinking + thinking_budget */
    QWEN,

    /** 开关型：thinking.type = enabled / disabled（智谱、豆包等） */
    TOGGLE,

    /** 未识别：不发送任何思考参数 */
    UNSUPPORTED
}

/** 查表结果：模型走哪种参数方式 + 已夹取到其支持范围的档位 */
data class ReasoningPlan(val style: ReasoningStyle, val level: ReasoningLevel)

/** 某个模型片段对应的支持情况；maxLevel 为该模型支持的最高档位，超出即夹取 */
private data class ReasoningSpec(
    val style: ReasoningStyle,
    val maxLevel: ReasoningLevel = ReasoningLevel.MAX
)

/**
 * normalize_and_map：标准化用户档位 → 按模型名查表 → 超出支持范围时夹取到最近可用值。
 */
fun reasoningPlan(model: String, rawLevel: String?): ReasoningPlan {
    val level = ReasoningLevel.normalize(rawLevel)
    val spec = REASONING_SPECS
        .firstOrNull { (tokens, _) -> tokens.any { modelMatches(model, it) } }
        ?.second
        ?: return ReasoningPlan(ReasoningStyle.UNSUPPORTED, level)
    val clamped = if (level.ordinal > spec.maxLevel.ordinal) spec.maxLevel else level
    return ReasoningPlan(spec.style, clamped)
}

/**
 * 模型名匹配：忽略大小写，token 需作为「型号片段」出现（位于开头或紧跟在分隔符之后），
 * 避免 sonoma 命中 o3 这类误判。
 */
private fun modelMatches(model: String, token: String): Boolean {
    val m = model.lowercase()
    var i = m.indexOf(token)
    while (i >= 0) {
        if (i == 0 || m[i - 1] in "-_./:@ ") return true
        i = m.indexOf(token, i + 1)
    }
    return false
}

/** 映射表：按顺序匹配，第一条命中即生效（排除项写在最前面） */
private val REASONING_SPECS: List<Pair<List<String>, ReasoningSpec>> = listOf(
    // 不支持思考参数的变体，先排除
    listOf("o1-preview", "o1-mini", "gpt-5-chat") to ReasoningSpec(ReasoningStyle.UNSUPPORTED),
    // 旧版 Claude（3.5 及更早）没有扩展思考
    listOf("claude-3-5", "claude-3.5", "claude-3-opus", "claude-3-haiku", "claude-3-sonnet") to
        ReasoningSpec(ReasoningStyle.UNSUPPORTED),
    // OpenAI 推理系（o1 / o3 / o4 / gpt-5）：reasoning_effort，只到 high
    listOf("o1", "o3", "o4", "gpt-5", "gpt5") to ReasoningSpec(ReasoningStyle.OPENAI_EFFORT, ReasoningLevel.HIGH),
    // DeepSeek V4 系（V4.1-Flash / V4-Pro / deepseek-flash）：reasoning_effort = none/low/high/max
    listOf("deepseek-v4", "deepseekv4", "deepseek-v41", "deepseekv41", "deepseek-flash", "deepseekflash") to
        ReasoningSpec(ReasoningStyle.DEEPSEEK),
    // Grok mini / Gemini 2.5+ 的 OpenAI 兼容接口同样吃 reasoning_effort，只到 high
    listOf("grok-3-mini", "grok3-mini", "gemini-2.5", "gemini-3", "gemini2.5", "gemini3") to
        ReasoningSpec(ReasoningStyle.OPENAI_EFFORT, ReasoningLevel.HIGH),
    // Anthropic Claude（3.7+）：thinking + budget_tokens，四档齐全
    listOf("claude", "anthropic") to ReasoningSpec(ReasoningStyle.ANTHROPIC),
    // 通义千问 Qwen3 / QwQ：enable_thinking + thinking_budget
    listOf("qwen3", "qwen-3", "qwq") to ReasoningSpec(ReasoningStyle.QWEN),
    // 开关型 thinking（智谱 GLM、豆包 seed）
    listOf("glm-4.5", "glm-4.6", "glm4.5", "glm4.6", "glm-z1", "glm-z", "doubao") to
        ReasoningSpec(ReasoningStyle.TOGGLE)
)
