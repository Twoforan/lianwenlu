package com.lianwenqi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianwenqi.app.R

internal fun settingsPageBackground(nightMode: Boolean): Color =
    if (nightMode) Color(0xFF17161C) else Color(0xFFF5F6FA)

/** 设置页顶部：标题与快捷入口，以及设计稿中的三段圆角切换栏。 */
@Composable
internal fun SettingsPageHeader(
    selectedTabIndex: Int,
    nightMode: Boolean,
    onSelectTab: (Int) -> Unit,
    onOpenPersonalization: () -> Unit,
    onOpenUpdate: () -> Unit
) {
    val cardColor = if (nightMode) Color(0xFF201F28) else Color.White
    val borderColor = if (nightMode) Color(0xFF34313F) else Color(0xFFE3E6EE)
    val titleColor = if (nightMode) Color(0xFFF2EFF7) else Color.Black
    val mutedColor = if (nightMode) Color(0xFF9C97A8) else Color(0xFF70798C)
    val accentColor = if (nightMode) Color(0xFF9F94FF) else Color(0xFF6658E8)
    val selectedColor = if (nightMode) Color(0xFF302A4D) else Color(0xFFF0EFFF)

    Column(
        Modifier
            .fillMaxWidth()
            .background(settingsPageBackground(nightMode))
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 18.dp, end = 20.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "设置",
                modifier = Modifier.weight(1f),
                color = titleColor,
                fontSize = 24.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold
            )
            SettingsHeaderAction(
                iconRes = R.drawable.ic_settings_personalization,
                description = "个性化设置",
                iconWidth = 16.dp,
                iconHeight = 16.dp,
                background = cardColor,
                tint = titleColor,
                borderColor = borderColor,
                onClick = onOpenPersonalization
            )
            SettingsHeaderAction(
                iconRes = R.drawable.ic_settings_update,
                description = "检查更新",
                iconWidth = 20.dp,
                iconHeight = 20.dp,
                background = Color(0xFF6658E8),
                tint = Color.White,
                onClick = onOpenUpdate
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp)
        ) {
            val shape = RoundedCornerShape(10.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clip(shape)
                    .background(cardColor)
                    .border(1.dp, borderColor, shape)
                    .selectableGroup()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("模型与供应商", "提示词库", "通用").forEachIndexed { index, label ->
                    val selected = selectedTabIndex == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .heightIn(min = 35.dp)
                            .then(if (selected) Modifier.shadow(2.dp, shape, clip = false) else Modifier)
                            .clip(shape)
                            .background(if (selected) selectedColor else Color.Transparent)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { onSelectTab(index) }
                            )
                            .padding(horizontal = 3.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (selected) accentColor else mutedColor,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeaderAction(
    iconRes: Int,
    description: String,
    iconWidth: Dp,
    iconHeight: Dp,
    background: Color,
    tint: Color,
    borderColor: Color? = null,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(background)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            modifier = Modifier.size(width = iconWidth, height = iconHeight),
            tint = tint
        )
    }
}
