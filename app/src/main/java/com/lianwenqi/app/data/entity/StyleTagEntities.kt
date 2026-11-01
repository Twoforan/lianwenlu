package com.lianwenqi.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 标签来源：用户手动创建 / AI 分析后创建 */
object StyleTagSource {
    const val USER = "USER"
    const val AI = "AI"

    fun normalize(raw: String): String = if (raw == AI) AI else USER
}

/** 全局标签库用 0 表示；档案专属标签带真实 styleProfileId */
const val GLOBAL_STYLE_TAG_SCOPE = 0L

/** 标签分类：全局（styleProfileId = 0）或某个文风档案专属 */
@Entity(
    tableName = "style_tag_category",
    indices = [
        Index("styleProfileId"),
        Index(value = ["styleProfileId", "name"], unique = true)
    ]
)
data class StyleTagCategory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 0 = 全局用户标签库；>0 = 该文风档案专属标签库 */
    val styleProfileId: Long = GLOBAL_STYLE_TAG_SCOPE,
    val name: String,
    /** USER / AI */
    val source: String = StyleTagSource.USER,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 文风标签。全局标签（styleProfileId = 0）只允许用户增删改；
 * 档案标签（styleProfileId > 0）允许用户增删改，AI 只能新增。
 */
@Entity(
    tableName = "style_tag",
    indices = [
        Index("styleProfileId"),
        Index(value = ["styleProfileId", "category", "name"], unique = true)
    ]
)
data class StyleTag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val styleProfileId: Long = GLOBAL_STYLE_TAG_SCOPE,
    val category: String,
    val name: String,
    /** USER / AI */
    val source: String = StyleTagSource.USER,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** 文风样本：必须归属一个文风档案，但允许解绑（styleProfileId = null） */
@Entity(tableName = "style_sample", indices = [Index("styleProfileId")])
data class StyleSampleRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** null = 未绑定，不参与任何文风 RAG 检索 */
    val styleProfileId: Long? = null,
    val fileName: String,
    val format: String,
    val charCount: Int = 0,
    /** SUCCESS / FAILED / PENDING 等，与导入解析状态保持一致 */
    val status: String = "SUCCESS",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 样本切分后的文本片段。styleProfileId 反规范化保存（0 = 未绑定），
 * 让文风 RAG 可以用一个硬性条件直接筛出当前档案的片段。
 * 解绑只把两处 styleProfileId 置空，不删除片段文本与向量。
 */
@Entity(
    tableName = "style_sample_chunk",
    indices = [
        Index("sampleId"),
        Index("styleProfileId"),
        Index(value = ["sampleId", "chunkIndex"], unique = true)
    ]
)
data class StyleSampleChunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sampleId: Long,
    /** 0 = 未绑定；>0 = 该文风档案。RAG 硬过滤字段 */
    val styleProfileId: Long = GLOBAL_STYLE_TAG_SCOPE,
    val chunkIndex: Int,
    val text: String,
    val hash: String,
    val dim: Int = 0,
    /** 向量（FloatArray 小端 BLOB）；向量化不可用时为 null，检索降级为纯标签匹配 */
    val vector: ByteArray? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/** 文本片段与标签的多对多关系；标签本身不会被拼进片段正文 */
@Entity(
    tableName = "style_sample_tag",
    indices = [
        Index("chunkId"),
        Index("tagId"),
        Index(value = ["chunkId", "tagId"], unique = true)
    ]
)
data class StyleSampleTagLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chunkId: Long,
    val tagId: Long,
    /** 关联建立时的标签来源快照（USER / AI），展示用 */
    val source: String = StyleTagSource.USER,
    val createdAt: Long = System.currentTimeMillis()
)

/** 章节梗概标签来源 */
object SummaryTagSource {
    /** 取自用户全局标签库 */
    const val USER_GLOBAL = "USER_GLOBAL"

    /** 取自当前文风档案专属标签库 */
    const val PROFILE = "PROFILE"

    /** 本章临时 AI 标签（不写入任何标签库，可参与后续语义匹配） */
    const val CHAPTER_AI = "CHAPTER_AI"

    /** 用户手动维护 */
    const val MANUAL = "MANUAL"
}

/**
 * 章节梗概标签。与梗概正文分开保存，绝不拼接进 summary。
 * [summaryHash] 记录生成时的梗概内容哈希，用于判断标签是否过期。
 *
 * 自动标签是「梗概句子 ↔ 文风档案标签」的关联：每条记录用
 * [sentenceIndex] / [sentenceStart] / [sentenceEnd] / [sentenceText] 精确指向被命中的原句；
 * 手动标签没有句子定位（默认 -1 / 空），只展示与保留，不参与正文抽样。
 */
@Entity(tableName = "chapter_summary_tag", indices = [Index("chapterId")])
data class ChapterSummaryTag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val category: String,
    val name: String,
    /** 复用标签库时保存真实标签 id；没有对应档案标签时为 0 */
    val tagId: Long = 0,
    val source: String = SummaryTagSource.PROFILE,
    /** 该标签对应的梗概内容哈希（shortHash），梗概改动作废 */
    val summaryHash: String,
    /** 用户手动添加 / 固定的标签，重新分析时优先保留 */
    val manual: Boolean = false,
    /** 命中句子在梗概中的序号（从 0 开始）；-1 表示没有句子定位 */
    val sentenceIndex: Int = -1,
    /** 命中句子在梗概原文中的起始下标（含），-1 表示未知 */
    val sentenceStart: Int = -1,
    /** 命中句子在梗概原文中的结束下标（不含），-1 表示未知 */
    val sentenceEnd: Int = -1,
    /** 命中句子的原文，用于精确还原；为空表示没有句子定位 */
    val sentenceText: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 是否带有可还原的原句定位 */
    val hasSentence: Boolean
        get() = sentenceIndex >= 0 && sentenceStart >= 0 && sentenceEnd > sentenceStart && sentenceText.isNotBlank()
}
