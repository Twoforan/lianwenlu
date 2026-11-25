package com.lianwenqi.app.data

import androidx.room.TypeConverter
import com.lianwenqi.app.data.entity.ApiType
import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.PromptType

class Converters {
    @TypeConverter
    fun apiToStr(v: ApiType): String = v.name

    @TypeConverter
    fun strToApi(v: String): ApiType =
        runCatching { ApiType.valueOf(v) }.getOrDefault(ApiType.CHAT_COMPLETIONS)

    @TypeConverter
    fun roleToStr(v: ChatRole): String = v.name

    @TypeConverter
    fun strToRole(v: String): ChatRole =
        runCatching { ChatRole.valueOf(v) }.getOrDefault(ChatRole.USER)

    @TypeConverter
    fun promptTypeToStr(v: PromptType): String = v.name

    /** 无法识别的类型（含旧库缺省值）一律按快捷提示词处理，避免升级后数据不可见 */
    @TypeConverter
    fun strToPromptType(v: String): PromptType =
        runCatching { PromptType.valueOf(v) }.getOrDefault(PromptType.QUICK)
}