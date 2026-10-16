package com.lianwenqi.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 使用说明引导页：告诉用户内置说明书已经在书架上，
 * 以及配置好模型后如何把整本说明书喂给 AI，向 AI 提问本应用的用法。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualGuideScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("使用说明") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "《炼文炉 · 使用说明书》已经内置在书架里",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "首次启动时，App 会自动在书架上生成一本《炼文炉 · 使用说明书》，" +
                    "它和普通书籍一样，可以打开阅读、导出与分享。",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(20.dp))
            GuideStep(
                "第一步 · 在书架上看使用说明",
                "打开底部导航栏的「书架」，找到《炼文炉 · 使用说明书》，点开即可阅读全部功能说明；" +
                    "也可以在书籍菜单里把它导出为 txt / Word 保存到本地。"
            )
            Spacer(Modifier.height(16.dp))
            GuideStep(
                "第二步 · 先配置好模型",
                "进入设置 → 通用设置 → 供应商管理，填写 BaseURL 与 API Key；" +
                    "再到模型管理里选择或添加模型。配置好之后 AI 对话才可用。"
            )
            Spacer(Modifier.height(16.dp))
            GuideStep(
                "第三步 · 把整本说明书交给 AI",
                "打开底部导航栏的「AI 对话」，在上下文里选中书籍《炼文炉 · 使用说明书》，" +
                    "再点章节「全选」，让整本说明书进入上下文。"
            )
            Spacer(Modifier.height(16.dp))
            GuideStep(
                "第四步 · 直接向 AI 提问",
                "在输入框里提问本应用的用法，例如「怎么开启长期记忆的本地向量化」「大纲怎么批量导入」，" +
                    "AI 会依据说明书内容回答。"
            )
            Spacer(Modifier.height(16.dp))
            GuideStep(
                "第五步 · 也可导出为 txt 到网页提问",
                "在书架里把《炼文炉 · 使用说明书》导出为 txt，再把这份 txt 上传到网页版 AI" +
                    "（如网页版 ChatGPT、Claude、豆包等）里提问，同样可以了解本应用的用法，" +
                    "无需在 App 内配置模型。"
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "提示：说明书覆盖了全部功能，遇到不确定的操作都可以这样向 AI 提问；" +
                    "若觉得说明内容不是最新，可到设置 → 检查更新升级到最新版本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GuideStep(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
