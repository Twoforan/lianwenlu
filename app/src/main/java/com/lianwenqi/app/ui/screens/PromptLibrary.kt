package com.lianwenqi.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianwenqi.app.R
import com.lianwenqi.app.data.entity.Prompt
import com.lianwenqi.app.data.entity.PromptType

private class PromptLibraryColors(dark: Boolean) {
    val card = if (dark) Color(0xFF201F28) else Color.White
    val text = if (dark) Color(0xFFF2EFF7) else Color.Black
    val muted = if (dark) Color(0xFFAAA5B8) else Color(0xFF70798C)
    val border = if (dark) Color(0xFF373340) else Color(0xFFF2F4F7)
    val inputBorder = if (dark) Color(0xFF403B4D) else Color(0xFFE6E9F0)
    val accent = if (dark) Color(0xFFB0A7FF) else Color(0xFF6658E8)
    val accentSoft = if (dark) Color(0xFF302A4D) else Color(0xFFF0EFFF)
    val selectedBorder = if (dark) Color(0xFF514577) else Color(0xFFE6E3FF)
    val copy = if (dark) Color(0xFF97C3B7) else Color(0xFF6D9B91)
    val delete = if (dark) Color(0xFFFF929A) else Color(0xFFE55961)
}

/** 提示词库：分类与新增、全文搜索，以及可展开的提示词卡片。 */
@Composable
internal fun PromptsTab(
    prompts: List<Prompt>,
    onAdd: (PromptType) -> Unit,
    onEdit: (Prompt) -> Unit,
    onDelete: (Prompt) -> Unit
) {
    var selectedType by rememberSaveable { mutableStateOf(PromptType.QUICK) }
    var query by rememberSaveable { mutableStateOf("") }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = remember(dark) { PromptLibraryColors(dark) }
    val search = query.trim()
    val shown = remember(prompts, selectedType, search) {
        prompts.filter { prompt ->
            prompt.type == selectedType &&
                (search.isEmpty() || prompt.title.contains(search, ignoreCase = true) ||
                    prompt.content.contains(search, ignoreCase = true))
        }
    }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PromptLibraryControls(
                selectedType = selectedType,
                colors = colors,
                onSelect = { focusManager.clearFocus(); selectedType = it },
                onAdd = { focusManager.clearFocus(); onAdd(selectedType) }
            )
            PromptSearchField(query, { query = it }, colors)
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    selectedType.label,
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.text
                )
                Text("共${shown.size}条", fontSize = 12.sp, lineHeight = 18.sp, color = colors.muted)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (shown.isEmpty()) {
                item {
                    PromptLibraryEmptyState(selectedType, search.isNotEmpty(), colors)
                }
            }
            items(shown, key = { it.id }) { prompt ->
                PromptLibraryCard(
                    prompt = prompt,
                    colors = colors,
                    onEdit = { focusManager.clearFocus(); onEdit(prompt) },
                    onCopy = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(prompt.title, prompt.content))
                        Toast.makeText(context, "提示词已复制", Toast.LENGTH_SHORT).show()
                    },
                    onDelete = { focusManager.clearFocus(); onDelete(prompt) }
                )
            }
        }
    }
}

@Composable
private fun PromptLibraryControls(
    selectedType: PromptType,
    colors: PromptLibraryColors,
    onSelect: (PromptType) -> Unit,
    onAdd: () -> Unit
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 300.dp || fontScale > 1.2f
        val tabs: @Composable () -> Unit = {
            Row(
                Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(PromptType.QUICK, PromptType.MODEL).forEach { type ->
                    val selected = type == selectedType
                    val shape = RoundedCornerShape(10.dp)
                    Box(
                        Modifier
                            .heightIn(min = 32.dp)
                            .clip(shape)
                            .background(if (selected) colors.accentSoft else Color.Transparent)
                            .then(if (selected) Modifier.border(1.dp, colors.selectedBorder, shape) else Modifier)
                            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(type) })
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            type.label,
                            color = if (selected) colors.accent else colors.muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
        val addButton: @Composable () -> Unit = {
            Row(
                Modifier
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF6658E8))
                    .clickable(role = Role.Button, onClick = onAdd)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(12.dp), tint = Color.White)
                Spacer(Modifier.width(3.dp))
                Text("添加提示词", color = Color.White, fontSize = 10.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tabs()
                Box(Modifier.align(Alignment.End)) { addButton() }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                tabs()
                Spacer(Modifier.weight(1f))
                addButton()
            }
        }
    }
}

@Composable
private fun PromptSearchField(query: String, onQueryChange: (String) -> Unit, colors: PromptLibraryColors) {
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 39.dp)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.inputBorder, shape)
            .semantics { contentDescription = "搜索提示词标题或内容" },
        textStyle = TextStyle(fontSize = 11.sp, lineHeight = 17.sp, color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        decorationBox = { innerTextField ->
            Row(
                Modifier.padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (query.isEmpty()) {
                        Text("搜索提示词标题或内容", fontSize = 11.sp, lineHeight = 17.sp, color = colors.muted)
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button, onClick = { onQueryChange("") }),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Close, "清除搜索", Modifier.size(16.dp), tint = colors.muted)
                    }
                } else {
                    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_prompt_search), null, Modifier.size(16.dp), tint = colors.accent)
                    }
                }
            }
        }
    )
}

@Composable
private fun PromptLibraryCard(
    prompt: Prompt,
    colors: PromptLibraryColors,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by rememberSaveable(prompt.id, prompt.content) { mutableStateOf(false) }
    var contentOverflows by rememberSaveable(prompt.id, prompt.content) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = 3.dp
    ) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(29.dp).clip(RoundedCornerShape(10.dp)).background(colors.accentSoft),
                    contentAlignment = Alignment.Center
                ) {
                    PromptTypeIcon(prompt.type, colors.accent)
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    prompt.title,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                prompt.content,
                modifier = Modifier.fillMaxWidth(),
                color = colors.muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout ->
                    if (!expanded && contentOverflows != layout.hasVisualOverflow) {
                        contentOverflows = layout.hasVisualOverflow
                    }
                }
            )
            if (contentOverflows || expanded) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button, onClick = { expanded = !expanded })
                        .padding(top = 7.dp, bottom = 12.dp)
                ) {
                    Text(
                        if (expanded) "收起内容" else "展开内容",
                        fontSize = 10.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.accent
                    )
                }
            }
            HorizontalDivider(color = colors.border)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                PromptCardAction("编辑", R.drawable.ic_prompt_edit, colors.accent, onEdit)
                PromptCardAction("复制", R.drawable.ic_prompt_copy, colors.copy, onCopy)
                PromptCardAction("删除", R.drawable.ic_prompt_delete, colors.delete, onDelete)
            }
        }
    }
}

@Composable
private fun PromptCardAction(label: String, @DrawableRes icon: Int, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(painterResource(icon), null, Modifier.size(11.dp), tint = color)
        Text(label, fontSize = 10.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun PromptTypeIcon(type: PromptType, tint: Color) {
    Icon(
        painterResource(if (type == PromptType.MODEL) R.drawable.ic_prompt_model else R.drawable.ic_prompt_quick),
        contentDescription = null,
        modifier = if (type == PromptType.MODEL) Modifier.size(width = 21.6.dp, height = 18.7.dp)
            else Modifier.size(width = 10.dp, height = 15.dp),
        tint = tint
    )
}

@Composable
private fun PromptLibraryEmptyState(type: PromptType, searching: Boolean, colors: PromptLibraryColors) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(colors.accentSoft),
            contentAlignment = Alignment.Center
        ) {
            if (searching) {
                Icon(painterResource(R.drawable.ic_prompt_search), null, Modifier.size(22.dp), tint = colors.accent)
            } else {
                PromptTypeIcon(type, colors.accent)
            }
        }
        Text(
            if (searching) "没有找到匹配的提示词" else "还没有${type.label}",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = colors.text,
            textAlign = TextAlign.Center
        )
        Text(
            if (searching) "试试其他标题或内容关键词" else "点击上方「添加提示词」，保存常用的提示词内容",
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = colors.muted,
            textAlign = TextAlign.Center
        )
    }
}
