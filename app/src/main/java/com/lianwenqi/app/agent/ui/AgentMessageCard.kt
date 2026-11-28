package com.lianwenqi.app.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** Agent 对话原型：整宽圆角卡片，正文 / 模型统计 / 内缩分隔线 / 原有操作栏。 */
@Composable
internal fun AgentMessageCard(
    ai: Boolean,
    modifier: Modifier = Modifier,
    tools: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val shape = RoundedCornerShape(15.dp)
    val background = when {
        dark && ai -> MaterialTheme.colorScheme.primaryContainer
        dark -> MaterialTheme.colorScheme.surface
        ai -> Color(0xFFF6F4FF)
        else -> Color.White
    }
    val borderColor = when {
        dark -> MaterialTheme.colorScheme.outlineVariant
        ai -> Color(0xFFEAECF2)
        else -> Color(0xFFE3E6EE)
    }
    val dividerColor = if (dark) MaterialTheme.colorScheme.outlineVariant else Color(0xFFE3E6EE)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            // 不使用整卡 shadow / clip 图层：超长回答仍能绘制并点击末尾的操作栏。
            // 叠加低透明度圆角底色，模拟原型向下的轻阴影。
            .drawBehind {
                val radius = CornerRadius(15.dp.toPx())
                drawRoundRect(
                    color = Color.Black.copy(alpha = if (dark) 0.08f else 0.035f),
                    topLeft = Offset(0f, 4.dp.toPx()),
                    size = size,
                    cornerRadius = radius
                )
                drawRoundRect(
                    color = Color.Black.copy(alpha = if (dark) 0.10f else 0.045f),
                    topLeft = Offset(0f, 2.dp.toPx()),
                    size = size,
                    cornerRadius = radius
                )
            }
            .background(background, shape)
            .border(1.dp, borderColor, shape)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp),
            content = content
        )
        if (tools != null) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 14.dp),
                thickness = 1.dp,
                color = dividerColor
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .widthIn(min = maxWidth)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = tools
                )
            }
        }
    }
}
