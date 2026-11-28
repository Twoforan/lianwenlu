package com.lianwenqi.app.ui.components

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianwenqi.app.R
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.BookGroup
import com.lianwenqi.app.data.style.StyleLibraryState
import kotlinx.coroutines.launch

/** 「新建 / 编辑书籍」表单提交的结果 */
data class BookFormResult(
    val title: String,
    val intro: String,
    val coverPath: String?,
    val coverColor: Int,
    val volumePrefix: String,
    val chapterPrefix: String,
    /** 所属分组 id，null 表示不分组 */
    val groupId: Long?,
    /** 绑定的文风档案 id；0 表示不使用文风 */
    val styleId: Long = 0,
    /** 封面装饰符号；null 表示按书籍自动轮换 */
    val coverGlyph: String? = null,
    /** 封面底部英文；空串表示不显示 */
    val coverEnglish: String = "LIANWENLU",
    /** 封面书名显示方式：0 完整书名，1 前 4 个字 */
    val coverTitleMode: Int = 0
)

// ---- 设计稿配色 ----
private val Ink = Color(0xFF24283A)
private val LabelGray = Color(0xFF70798C)
private val HintGray = Color(0xFF8B93A5)
private val LineColor = Color(0xFFEBEEF4)
private val FieldBorder = Color(0xFFE6E9F0)
private val CardBg = Color(0xFFF8F8FC)
private val CardBorder = Color(0xFFEEEFF5)
private val Brand = Color(0xFF6658E8)
private val BrandLight = Color(0xFF8376F1)
private val BrandSoft = Color(0xFFF3F1FF)
private val BrandSoftBorder = Color(0xFFDEDBFF)
private val GlyphBorder = Color(0xFFE8EAF1)

/** 可选封面图标 */
private val CoverGlyphOptions = listOf("✦", "❖", "✵", "♛", "❀", "◇", "☯", "⌘", "☾")

/**
 * 新建 / 编辑书籍的完整表单弹窗（书名、简介、封面、分组、标题前缀）。
 * [initial] 非空时为编辑模式，各字段预填该书现有值。
 *
 * 视觉按「添加书籍」设计稿还原：381 宽画布等比缩放，顶部栏 + 可滚动表单 + 底部操作栏。
 */
@Composable
fun BookFormDialog(
    dialogTitle: String,
    initial: Book?,
    groups: List<BookGroup>,
    /** 非 null 时展示「文风绑定」选择项；新建与编辑书籍均传入 */
    styleLibrary: StyleLibraryState? = null,
    onRetryStyleProfiles: () -> Unit = {},
    onPickCover: (Uri, (String?) -> Unit) -> Unit,
    /** 现场新建分组，返回新分组 id（创建后自动选中） */
    onCreateGroup: suspend (String) -> Long,
    onConfirm: (BookFormResult) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var intro by remember { mutableStateOf(initial?.intro ?: "") }
    var coverPath by remember { mutableStateOf(initial?.coverPath) }
    var color by remember { mutableStateOf(initial?.coverColor ?: CoverColors[3]) }
    var volumePrefix by remember { mutableStateOf(initial?.volumePrefix ?: "第%n卷") }
    var chapterPrefix by remember { mutableStateOf(initial?.chapterPrefix ?: "第%n章") }
    var groupId by remember { mutableStateOf(initial?.groupId) }
    var styleId by remember { mutableStateOf(initial?.styleId ?: 0L) }
    var coverGlyph by remember {
        mutableStateOf(initial?.coverGlyph ?: if (initial == null) "☾" else null)
    }
    var coverEnglish by remember { mutableStateOf(initial?.coverEnglish ?: "LIANWENLU") }
    var coverTitleMode by remember { mutableStateOf(initial?.coverTitleMode ?: 0) }
    var designExpanded by remember { mutableStateOf(false) }
    var showStylePicker by remember { mutableStateOf(false) }
    var showGroupMenu by remember { mutableStateOf(false) }
    // 现场新建分组：输入框非 null 时展示
    var newGroupName by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onPickCover(it) { path -> coverPath = path } } }

    val selectedStyle = styleLibrary?.rows?.firstOrNull { it.profile.id == styleId }
    val groupName = groups.firstOrNull { it.id == groupId }?.name ?: "不分组"
    val styleName = when {
        styleId == 0L -> "不绑定"
        selectedStyle != null -> selectedStyle.profile.name
        else -> "已失效，请重新选择"
    }

    // 与添加供应商一致，直接叠在当前页面上，保留背景并响应输入法避让。
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .imePadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .padding(horizontal = 14.dp, vertical = 16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { },
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            border = BorderStroke(1.dp, LineColor),
            shadowElevation = 16.dp
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val s = (maxWidth.value / 381f).coerceIn(0.9f, 1.5f)
                Column(Modifier.fillMaxSize()) {
                    // ---- 顶部栏 ----
                    Box(Modifier.fillMaxWidth().height((79 * s).dp)) {
                        Image(
                            painter = painterResource(R.drawable.ic_addbook),
                            contentDescription = null,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(x = (15 * s).dp, y = (19 * s).dp)
                                .size((48 * s).dp)
                        )
                        Column(
                            Modifier
                                .align(Alignment.TopStart)
                                .offset(x = (73 * s).dp, y = (17 * s).dp)
                        ) {
                            Text(
                                dialogTitle,
                                fontSize = (21 * s).sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                style = TextStyle(fontFeatureSettings = "locl")
                            )
                            Spacer(Modifier.height((4 * s).dp))
                            Text(
                                if (initial == null) "创作一本新的作品" else "修改这本作品的信息",
                                fontSize = (10 * s).sp,
                                color = HintGray
                            )
                        }
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(LineColor)
                        )
                    }

                    // ---- 可滚动表单 ----
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = (17 * s).dp)
                    ) {
                        Spacer(Modifier.height((12 * s).dp))
                        SectionHeader("书籍封面", s)
                        Spacer(Modifier.height((12 * s).dp))
                        CoverCard(
                            s = s,
                            preview = Book(
                                title = title.ifBlank { "未命名" },
                                coverPath = coverPath,
                                coverColor = color,
                                coverGlyph = coverGlyph,
                                coverEnglish = coverEnglish,
                                coverTitleMode = coverTitleMode
                            ),
                            onPick = { picker.launch("image/*") },
                            onReset = { coverPath = null }
                        )
                        Spacer(Modifier.height((12 * s).dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(CardBorder))
                        CollapseRow(
                            s = s,
                            expanded = designExpanded,
                            onToggle = { designExpanded = !designExpanded }
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(CardBorder))
                        if (designExpanded) {
                            Spacer(Modifier.height((15 * s).dp))
                            FieldLabel("封面配色", s)
                            Spacer(Modifier.height((10 * s).dp))
                            Row(horizontalArrangement = Arrangement.spacedBy((7 * s).dp)) {
                                CoverColors.forEach { c ->
                                    CoverSwatch(c, c == color, s) { color = c }
                                }
                            }

                            Spacer(Modifier.height((14 * s).dp))
                            FieldLabel("封面图标", s)
                            Spacer(Modifier.height((10 * s).dp))
                            GlyphGrid(s = s, selected = coverGlyph, onSelect = { coverGlyph = it })

                            Spacer(Modifier.height((15 * s).dp))
                            FieldLabel("封面英文", s)
                            Spacer(Modifier.height((6 * s).dp))
                            FormInput(
                                value = coverEnglish,
                                onValueChange = { coverEnglish = it },
                                placeholder = "Lianwenlu",
                                singleLine = true,
                                s = s
                            )

                            Spacer(Modifier.height((14 * s).dp))
                            FieldLabel("封面显示文字", s)
                            Spacer(Modifier.height((8 * s).dp))
                            Row(horizontalArrangement = Arrangement.spacedBy((12 * s).dp)) {
                                SegmentButton(
                                    text = "完整书名",
                                    selected = coverTitleMode == 0,
                                    modifier = Modifier.weight(1f),
                                    s = s
                                ) { coverTitleMode = 0 }
                                SegmentButton(
                                    text = "书名前几个字",
                                    selected = coverTitleMode == 1,
                                    modifier = Modifier.weight(1f),
                                    s = s
                                ) { coverTitleMode = 1 }
                            }
                        }

                        Spacer(Modifier.height((20 * s).dp))
                        SectionHeader("基本信息", s)
                        Spacer(Modifier.height((8 * s).dp))
                        FieldLabel("书名", s)
                        Spacer(Modifier.height((6 * s).dp))
                        FormInput(
                            value = title,
                            onValueChange = { title = it },
                            placeholder = "请输入书籍名",
                            singleLine = true,
                            s = s
                        )

                        Spacer(Modifier.height((12 * s).dp))
                        FieldLabel("简介", s)
                        Spacer(Modifier.height((6 * s).dp))
                        FormInput(
                            value = intro,
                            onValueChange = { intro = it },
                            placeholder = "请输入本书简介",
                            singleLine = false,
                            heightDp = 76f,
                            s = s
                        )

                        Spacer(Modifier.height((16 * s).dp))
                        Row(horizontalArrangement = Arrangement.spacedBy((13 * s).dp)) {
                            Column(Modifier.weight(1f)) {
                                FieldLabel("所属分组", s)
                                Spacer(Modifier.height((6 * s).dp))
                                Box {
                                    SelectBox(groupName, s) { showGroupMenu = true }
                                    DropdownMenu(
                                        expanded = showGroupMenu,
                                        onDismissRequest = { showGroupMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("不分组") },
                                            onClick = {
                                                groupId = null
                                                showGroupMenu = false
                                            }
                                        )
                                        groups.forEach { g ->
                                            DropdownMenuItem(
                                                text = { Text(g.name) },
                                                onClick = {
                                                    groupId = g.id
                                                    showGroupMenu = false
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("＋ 新建分组") },
                                            onClick = {
                                                showGroupMenu = false
                                                newGroupName = ""
                                            }
                                        )
                                    }
                                }
                            }
                            if (styleLibrary != null) {
                                Column(Modifier.weight(1f)) {
                                    FieldLabel("文风绑定", s)
                                    Spacer(Modifier.height((6 * s).dp))
                                    SelectBox(styleName, s) { showStylePicker = true }
                                }
                            }
                        }

                        Spacer(Modifier.height((20 * s).dp))
                        SectionHeader("标题前缀", s)
                        Spacer(Modifier.height((8 * s).dp))
                        FieldLabel("分卷", s)
                        Spacer(Modifier.height((6 * s).dp))
                        FormInput(
                            value = volumePrefix,
                            onValueChange = { volumePrefix = it },
                            placeholder = "第%n卷",
                            singleLine = true,
                            s = s
                        )
                        Spacer(Modifier.height((12 * s).dp))
                        FieldLabel("章节", s)
                        Spacer(Modifier.height((6 * s).dp))
                        FormInput(
                            value = chapterPrefix,
                            onValueChange = { chapterPrefix = it },
                            placeholder = "第%n章",
                            singleLine = true,
                            s = s
                        )
                        Spacer(Modifier.height((10 * s).dp))
                        Text(
                            "新建分卷/章节时自动顺延编号；%n阿拉伯数字，%N中文数字",
                            fontSize = (11 * s).sp,
                            color = LabelGray,
                            lineHeight = (16 * s).sp
                        )
                        Spacer(Modifier.height((24 * s).dp))
                    }

                    // ---- 底部操作栏 ----
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = (10 * s).dp,
                                end = (10 * s).dp,
                                top = (12 * s).dp,
                                bottom = (12 * s).dp
                            ),
                        horizontalArrangement = Arrangement.spacedBy((11 * s).dp)
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height((43 * s).dp)
                                .clip(RoundedCornerShape((13 * s).dp))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFE5E8EF), RoundedCornerShape((13 * s).dp))
                                .clickable { onDismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "取消",
                                fontSize = (12 * s).sp,
                                color = Color(0xFF70788A)
                            )
                        }
                        val confirmShape = RoundedCornerShape((13 * s).dp)
                        Row(
                            Modifier
                                .weight(1f)
                                .height((44 * s).dp)
                                .shadow(
                                    elevation = (7 * s).dp,
                                    shape = confirmShape,
                                    ambientColor = Brand.copy(alpha = 0.5f),
                                    spotColor = Brand.copy(alpha = 0.5f)
                                )
                                .clip(confirmShape)
                                .background(Brush.linearGradient(listOf(Brand, BrandLight)))
                                .clickable(enabled = title.isNotBlank()) {
                                    if (title.isNotBlank()) {
                                        onConfirm(
                                            BookFormResult(
                                                title = title.trim(),
                                                intro = intro.trim(),
                                                coverPath = coverPath,
                                                coverColor = color,
                                                volumePrefix = volumePrefix.ifBlank { "第%n卷" },
                                                chapterPrefix = chapterPrefix.ifBlank { "第%n章" },
                                                groupId = groupId,
                                                styleId = styleId,
                                                coverGlyph = coverGlyph,
                                                coverEnglish = coverEnglish,
                                                coverTitleMode = coverTitleMode
                                            )
                                        )
                                    }
                                },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (initial == null) "添加书籍" else "保存",
                                fontSize = (12 * s).sp,
                                color = Color.White
                            )
                            Spacer(Modifier.width((10 * s).dp))
                            Text(
                                "→",
                                fontSize = (14 * s).sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }

    // 现场新建分组：输入名称后确认，自动选中新分组
    newGroupName?.let { pending ->
        AlertDialog(
            onDismissRequest = { newGroupName = null },
            title = { Text("新建分组") },
            text = {
                OutlinedTextField(
                    value = pending,
                    onValueChange = { newGroupName = it },
                    label = { Text("分组名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = pending.trim()
                        if (name.isNotEmpty()) {
                            scope.launch {
                                groupId = onCreateGroup(name)
                                newGroupName = null
                            }
                        }
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { newGroupName = null }) { Text("取消") }
            }
        )
    }

    // 文风档案选择：取消时不改变原有选择
    val library = styleLibrary
    if (showStylePicker && library != null) {
        StyleProfilePickerDialog(
            state = library,
            selectedId = styleId,
            onRetry = onRetryStyleProfiles,
            onConfirm = {
                styleId = it
                showStylePicker = false
            },
            onDismiss = { showStylePicker = false }
        )
    }
}

// ---------------- 设计稿小组件 ----------------

/** 小节标题：左侧紫色竖条 + 12sp 深色标题 */
@Composable
private fun SectionHeader(text: String, s: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = (3 * s).dp, height = (12 * s).dp)
                .clip(RoundedCornerShape((2 * s).dp))
                .background(Brand)
        )
        Spacer(Modifier.width((7 * s).dp))
        Text(
            text,
            fontSize = (12 * s).sp,
            fontWeight = FontWeight.Medium,
            color = Ink
        )
    }
}

/** 表单字段标签 */
@Composable
private fun FieldLabel(text: String, s: Float) {
    Text(text, fontSize = (11 * s).sp, color = LabelGray)
}

/** 封面卡片：预览、说明、选择封面、恢复默认 */
@Composable
private fun CoverCard(
    s: Float,
    preview: Book,
    onPick: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height((165 * s).dp)
            .clip(RoundedCornerShape((15 * s).dp))
            .background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape((15 * s).dp))
            .padding(horizontal = (14 * s).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BookCover(
            book = preview,
            modifier = Modifier
                .width((105 * s).dp)
                .height((136 * s).dp),
            cornerRadius = (10 * s).dp,
            titleSize = (11 * s).toInt().coerceAtLeast(6)
        )
        Spacer(Modifier.width((12 * s).dp))
        Column(Modifier.height((136 * s).dp)) {
            Text(
                "为您的作品选择封面",
                fontSize = (12 * s).sp,
                color = Color.Black
            )
            Spacer(Modifier.height((8 * s).dp))
            Text(
                "上传一张图片作为封面，或者使用下方设计工具，打造专属书籍封面",
                fontSize = (11 * s).sp,
                color = LabelGray,
                lineHeight = (16 * s).sp
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width((82 * s).dp)
                        .height((33 * s).dp)
                        .clip(RoundedCornerShape((10 * s).dp))
                        .background(BrandSoft)
                        .border(1.dp, BrandSoftBorder, RoundedCornerShape((10 * s).dp))
                        .clickable { onPick() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("+ 选择封面", fontSize = (11 * s).sp, color = Brand)
                }
                Spacer(Modifier.width((18 * s).dp))
                Text(
                    "恢复默认",
                    fontSize = (10 * s).sp,
                    color = LabelGray,
                    modifier = Modifier.clickable { onReset() }
                )
            }
        }
    }
}

/** 「收起封面设计」可折叠行 */
@Composable
private fun CollapseRow(s: Float, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height((32 * s).dp)
            .clickable { onToggle() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("✦", fontSize = (10 * s).sp, color = LabelGray)
        Spacer(Modifier.width((10 * s).dp))
        Text(
            if (expanded) "收起封面设计" else "展开封面设计",
            fontSize = (11 * s).sp,
            color = LabelGray
        )
    }
}

/** 封面配色圆点 */
@Composable
private fun CoverSwatch(color: Int, selected: Boolean, s: Float, onClick: () -> Unit) {
    val base = Color(color)
    Box(
        Modifier
            .size((33 * s).dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(lerp(base, Color.White, 0.24f), lerp(base, Color.Black, 0.28f))
                )
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) Brand else Color(0xFFE5E6ED),
                shape = CircleShape
            )
            .clickable { onClick() }
    )
}

/** 封面图标 5 + 4 宫格 */
@Composable
private fun GlyphGrid(s: Float, selected: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy((8 * s).dp)) {
        CoverGlyphOptions.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy((8 * s).dp)) {
                row.forEach { g ->
                    Box(
                        Modifier
                            .size((37 * s).dp)
                            .clip(RoundedCornerShape((10 * s).dp))
                            .background(Color.White)
                            .border(
                                width = 1.dp,
                                color = if (g == selected) Color(0xFFAAA2FF) else GlyphBorder,
                                shape = RoundedCornerShape((10 * s).dp)
                            )
                            .clickable { onSelect(g) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(g, fontSize = (20 * s).sp, color = Color.Black)
                    }
                }
            }
        }
    }
}

/** 封面显示文字二选一按钮 */
@Composable
private fun SegmentButton(
    text: String,
    selected: Boolean,
    modifier: Modifier,
    s: Float,
    onClick: () -> Unit
) {
    Box(
        modifier
            .height((33 * s).dp)
            .clip(RoundedCornerShape((10 * s).dp))
            .background(if (selected) BrandSoft else Color.White)
            .border(
                width = 1.dp,
                color = if (selected) BrandSoftBorder else FieldBorder,
                shape = RoundedCornerShape((10 * s).dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = (11 * s).sp,
            color = if (selected) Brand else LabelGray
        )
    }
}

/** 通用输入框（单行 / 多行） */
@Composable
private fun FormInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    s: Float,
    heightDp: Float = 39f
) {
    val shape = RoundedCornerShape((10 * s).dp)
    val inputTextStyle = TextStyle(
        fontSize = (11 * s).sp,
        lineHeight = (16 * s).sp,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None
        ),
        color = Ink,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
    val textAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
    Box(
        Modifier
            .fillMaxWidth()
            .height((heightDp * s).dp)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, FieldBorder, shape)
            .padding(horizontal = (14 * s).dp, vertical = (10 * s).dp),
        contentAlignment = textAlignment
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = inputTextStyle,
            cursorBrush = SolidColor(Brand),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                // 提示与编辑内容共用行高、字体度量和对齐容器。
                Box(Modifier.fillMaxWidth(), contentAlignment = textAlignment) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = inputTextStyle.copy(color = LabelGray),
                            maxLines = if (singleLine) 1 else 4
                        )
                    }
                    inner()
                }
            }
        )
    }
}

/** 下拉选择框（分组 / 文风） */
@Composable
private fun SelectBox(text: String, s: Float, onClick: () -> Unit) {
    val shape = RoundedCornerShape((10 * s).dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height((39 * s).dp)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, FieldBorder, shape)
            .clickable { onClick() }
            .padding(horizontal = (14 * s).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            fontSize = (11 * s).sp,
            color = LabelGray,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start
        )
        Text("▾", fontSize = (10 * s).sp, color = LabelGray)
    }
}
