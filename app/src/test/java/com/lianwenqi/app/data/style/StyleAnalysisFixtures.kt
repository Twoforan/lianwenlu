package com.lianwenqi.app.data.style

/**
 * 测试用分析结果 JSON。默认值是任意构造的合法结构，只用于验证解析 / 校验链路，
 * 不代表任何真实样本的分析结论。
 */
object StyleAnalysisFixtures {

    fun validJson(
        schemaVersion: String = "1",
        overview: String = "整体偏冷峻克制，叙述围绕人物的即时反应展开，很少直接评论",
        narration: String = """["多采用第三人称限制视角，叙述者极少直接评论人物动机"]""",
        sentenceRhythm: String = """["段落以中短句为主，关键动作处连续使用短句形成停顿"]""",
        diction: String = """["动词密度高于形容词，情绪多通过动作与物件细节间接呈现"]""",
        description: String = """["环境描写集中在光线与声音，人物描写偏向局部动作而非全貌"]""",
        paragraphStructure: String = """["段落普遍较短，一段只推进一个动作或一个信息点"]""",
        dialogue: String = """["证据不足：样本对话较少，无法判断对话长度与口吻"]""",
        emotion: String = """["情绪表达克制，很少直接使用情绪标签，多由停顿与视线变化承载"]""",
        transition: String = """["场景转换依靠时间标记与动作衔接，较少使用总结性过渡句"]""",
        writingGuidelines: String = """["写人物迟疑时先写动作停顿，再用一句短对白交代态度","场景转换前用一个具体物件或声音作为落点"]""",
        avoidances: String = """["避免连续使用抽象情绪形容词收束段落"]""",
        evidence: String = """[{"dimension":"narration","confidence":"high","evidence":"全篇未见叙述者评论性插入"},{"dimension":"diction","confidence":"medium","evidence":"动作动词密集而形容词较少"},{"dimension":"sentenceRhythm","confidence":"high","evidence":"关键动作处短句连续出现"}]"""
    ): String = """
        {
          "schemaVersion": $schemaVersion,
          "overview": "$overview",
          "narration": $narration,
          "sentenceRhythm": $sentenceRhythm,
          "diction": $diction,
          "description": $description,
          "paragraphStructure": $paragraphStructure,
          "dialogue": $dialogue,
          "emotion": $emotion,
          "transition": $transition,
          "writingGuidelines": $writingGuidelines,
          "avoidances": $avoidances,
          "evidenceAndConfidence": $evidence
        }
    """.trimIndent()
}
