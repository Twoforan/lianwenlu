package com.lianwenqi.app.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.lianwenqi.app.R
import kotlin.math.roundToInt

/** 任务信息悬浮入口：每个会话默认收起，卡片不占用对话布局。 */
@Composable
internal fun AgentTaskPanel(
    sessionId: Long,
    title: String,
    onRename: () -> Unit,
    onOpenReferences: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember(sessionId) { mutableStateOf(false) }
    var bubbleOffset by remember(sessionId) { mutableStateOf<Offset?>(null) }
    BackHandler(enabled = expanded) { expanded = false }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val bubbleSize = 40.dp
        val bubblePx = with(density) { bubbleSize.toPx() }
        val marginPx = with(density) { 12.dp.toPx() }
        val defaultTopPx = with(density) { 101.dp.toPx() }
        val gapPx = with(density) { 8.dp.roundToPx() }
        val maxX = (constraints.maxWidth - bubblePx).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight - bubblePx).coerceAtLeast(0f)
        fun bounded(position: Offset) = Offset(
            position.x.coerceIn(0f, maxX), position.y.coerceIn(0f, maxY)
        )
        val position = bounded(
            bubbleOffset ?: Offset((maxX - marginPx).coerceAtLeast(0f), defaultTopPx)
        )
        val popupPosition = remember(gapPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize
                ): IntOffset {
                    val maxLeft = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                    val maxTop = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                    val above = anchorBounds.top - gapPx - popupContentSize.height
                    val below = anchorBounds.bottom + gapPx
                    val top = when {
                        above >= 0 -> above
                        below <= maxTop -> below
                        else -> above.coerceIn(0, maxTop)
                    }
                    return IntOffset(
                        (anchorBounds.right - popupContentSize.width).coerceIn(0, maxLeft),
                        top.coerceIn(0, maxTop)
                    )
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                .size(bubbleSize)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(AgentAccent)
                .pointerInput(sessionId, constraints) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        bubbleOffset = bounded((bubbleOffset ?: position) + drag)
                    }
                }
                .clickable(role = Role.Button) { expanded = !expanded },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_agent_task_paw),
                contentDescription = if (expanded) "收起任务标题" else "展开任务标题",
                modifier = Modifier.size(26.dp, 24.dp),
                tint = Color.White
            )
            if (expanded) {
                // 独立弹层不受聊天区剩余高度限制，键盘打开时资料入口仍可点击。
                Popup(popupPositionProvider = popupPosition) {
                    AgentTaskCard(title, onRename, onOpenReferences)
                }
            }
        }
    }
}

@Composable
private fun AgentTaskCard(
    title: String,
    onRename: () -> Unit,
    onOpenReferences: () -> Unit
) {
    val rowHeight = (40 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    Surface(
        modifier = Modifier.width(130.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE3E6EE)),
        shadowElevation = 4.dp
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(rowHeight)
                    .semantics { contentDescription = "修改任务标题" }
                    .clickable(role = Role.Button, onClick = onRename)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    title.ifBlank { "任务标题" },
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = Color(0xFF22232A),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    painterResource(R.drawable.ic_agent_task_edit), null,
                    Modifier.size(14.dp), tint = Color(0xFF22232A)
                )
            }
            HorizontalDivider(
                Modifier.padding(horizontal = 10.dp), color = Color(0xFFE3E6EE)
            )
            Box(
                Modifier.fillMaxWidth().height(rowHeight)
                    .clickable(role = Role.Button, onClick = onOpenReferences),
                contentAlignment = Alignment.Center
            ) {
                Text("已选资料", fontSize = 12.sp, color = AgentAccent)
            }
        }
    }
}
