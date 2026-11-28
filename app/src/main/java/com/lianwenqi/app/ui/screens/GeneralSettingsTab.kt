package com.lianwenqi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianwenqi.app.R

/**
 * 通用设置页的设计稿还原：浅灰画布、白色浮卡、紫色图标底座和紧凑的两行说明。
 * 卡片顺序与设计稿保持一致，所有入口仍复用原有页面导航与备份逻辑。
 */
@Composable
internal fun GeneralSettingsTab(
    nightMode: Boolean,
    onOpenManual: () -> Unit,
    onOpenPrefix: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenRestore: () -> Unit,
    onOpenAiTools: () -> Unit,
    onOpenCompression: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenStyle: () -> Unit
) {
    val background = settingsPageBackground(nightMode)
    val card = if (nightMode) Color(0xFF201F28) else Color.White
    val tile = if (nightMode) Color(0xFF302A4D) else Color(0xFFF0EFFF)
    val title = if (nightMode) Color(0xFFF2EFF7) else Color.Black
    val secondary = if (nightMode) Color(0xFFA6A1B3) else Color(0xFF70798C)
    val accent = if (nightMode) Color(0xFF9F94FF) else Color(0xFF6658E8)
    val border = if (nightMode) Color(0xFF34313F) else Color(0xFFF2F4F7)
    val shape = RoundedCornerShape(10.dp)
    val scroll = rememberScrollState()

    val items = listOf(
        GeneralSettingItem("使用说明", "查看内置使用说明书，并可把它交给 AI，提问本应用的用法", R.drawable.ic_general_manual, onOpenManual),
        GeneralSettingItem("前缀词管理", "上下文前缀化、大纲前缀化与自定义前缀", R.drawable.ic_general_prefix, onOpenPrefix),
        GeneralSettingItem("数据备份", "可选择备份提示词库、系统设置与全部书籍的大纲、正文、梗概和卷梗概", R.drawable.ic_general_backup, onOpenBackup),
        GeneralSettingItem("数据恢复", "导入本应用生成的备份 zip，按备份里的分区勾选要恢复的内容", R.drawable.ic_general_restore, onOpenRestore),
        GeneralSettingItem("AI工具", "写入大纲等 AI 辅助工具", R.drawable.ic_general_ai, onOpenAiTools),
        GeneralSettingItem("分卷压缩", "整卷梗概的默认来源、完成后是否更新大纲，以及大纲更新范围", R.drawable.ic_general_compression, onOpenCompression),
        GeneralSettingItem("长期记忆（书本向量化）", "书籍正文向量化后，发送时按本章梗概与提示词语义检索相关片段，作为参考资料写入本轮对话", R.drawable.ic_general_memory, onOpenMemory),
        GeneralSettingItem("文风管理", "管理文风档案、样本与标签", R.drawable.ic_general_style, onOpenStyle)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .verticalScroll(scroll)
            .padding(start = 21.dp, end = 21.dp, top = 6.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items.forEach { item ->
            GeneralSettingCard(
                item = item,
                card = card,
                tile = tile,
                title = title,
                secondary = secondary,
                accent = accent,
                border = border,
                shape = shape
            )
        }
        Spacer(Modifier.height(1.dp))
        Text(
            text = "炼文器 v0.1.0 · 基础框架\n数据保存在本机 Room 数据库，可在「数据备份」里打包迁移。",
            modifier = Modifier.fillMaxWidth(),
            color = secondary,
            fontSize = 10.sp,
            lineHeight = 14.sp
        )
    }
}

private data class GeneralSettingItem(
    val title: String,
    val description: String,
    val iconRes: Int,
    val onClick: () -> Unit
)

@Composable
private fun GeneralSettingCard(
    item: GeneralSettingItem,
    card: Color,
    tile: Color,
    title: Color,
    secondary: Color,
    accent: Color,
    border: Color,
    shape: RoundedCornerShape
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(79.dp)
            .shadow(4.dp, shape)
            .clip(shape)
            .background(card)
            .border(1.dp, border, shape)
            .clickable(role = Role.Button, onClick = item.onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .size(42.dp)
                .clip(shape)
                .background(tile),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(item.iconRes),
                contentDescription = item.title,
                tint = accent,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = item.title,
                color = title,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.description,
                color = secondary,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
