package com.lianwenqi.app.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianwenqi.app.R
import com.lianwenqi.app.agent.data.entity.AgentWritePermissionPolicy

internal val AgentPageBackground = Color(0xFFF5F6FA)
internal val AgentAccent = Color(0xFF6658E8)
internal val AgentMuted = Color(0xFF70798C)

/** 资料入口按可用宽度排列；窄屏或大字号时分行，保持四个入口同时可见。 */
@Composable
internal fun AgentPageHeader(
    bookTitle: String?,
    chapterCount: Int,
    outlineCount: Int,
    hasBook: Boolean,
    onSettings: () -> Unit,
    onNewSession: () -> Unit,
    onHistory: () -> Unit,
    onExitAgent: () -> Unit,
    onBook: () -> Unit,
    onChapters: () -> Unit,
    onOutline: () -> Unit,
    onTaskHistory: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .background(AgentPageBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(Brush.verticalGradient(listOf(Color(0xFFEEEAFF), Color(0xFFF6F4FF))))
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "自动化", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF17171B), modifier = Modifier.weight(1f), maxLines = 1
            )
            AgentHeaderAction(R.drawable.ic_agent_settings, "Agent 设置", onClick = onSettings)
            AgentHeaderAction(R.drawable.ic_chat_new, "新建 Agent 会话", 12.dp, onClick = onNewSession)
            AgentHeaderAction(R.drawable.ic_chat_history, "历史会话", 17.dp, onClick = onHistory)
            AgentHeaderAction(
                R.drawable.ic_chat_agent, "返回 AI 聊天", 18.dp,
                selected = true, onClick = onExitAgent
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fontScale = LocalDensity.current.fontScale
            val compact = maxWidth < 360.dp || fontScale > 1.2f
            val separateHistory = maxWidth < (240 * fontScale).dp
            if (compact) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AgentContextPill(
                        label = bookTitle ?: "选择书籍", modifier = Modifier.fillMaxWidth(),
                        iconRes = R.drawable.ic_chat_book, onClick = onBook
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentContextPill(
                            "章节", Modifier.weight(1f), count = chapterCount,
                            enabled = hasBook, onClick = onChapters
                        )
                        AgentContextPill(
                            "大纲", Modifier.weight(1f), count = outlineCount,
                            enabled = hasBook, onClick = onOutline
                        )
                        if (!separateHistory) {
                            AgentContextPill(
                                "历史任务", Modifier.weight(1f), enabled = hasBook, onClick = onTaskHistory
                            )
                        }
                    }
                    if (separateHistory) {
                        AgentContextPill(
                            "历史任务", Modifier.fillMaxWidth(), enabled = hasBook, onClick = onTaskHistory
                        )
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AgentContextPill(
                        label = bookTitle ?: "选择书籍", modifier = Modifier.weight(1.35f),
                        iconRes = R.drawable.ic_chat_book, compact = true, onClick = onBook
                    )
                    AgentContextPill(
                        "章节", Modifier.weight(1f), count = chapterCount,
                        enabled = hasBook, compact = true, onClick = onChapters
                    )
                    AgentContextPill(
                        "大纲", Modifier.weight(1f), count = outlineCount,
                        enabled = hasBook, compact = true, onClick = onOutline
                    )
                    AgentContextPill(
                        "历史任务", Modifier.weight(1.2f), enabled = hasBook,
                        compact = true, onClick = onTaskHistory
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentHeaderAction(
    iconRes: Int,
    description: String,
    iconSize: Dp = 18.dp,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(36.dp).clip(shape)
            .background(if (selected) AgentAccent else Color.White)
            .border(1.dp, if (selected) AgentAccent else Color(0xFFE3E6EE), shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(iconRes), description, Modifier.size(iconSize),
            tint = if (selected) Color.White else Color.Black
        )
    }
}

@Composable
private fun AgentContextPill(
    label: String,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    count: Int? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val foreground = if (enabled) Color(0xFF22232A) else Color(0xFF9AA1B0)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Surface(
        modifier.height((34 * fontScale).dp).clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE3E6EE))
    ) {
        Row(
            Modifier.fillMaxSize().padding(
                start = if (iconRes != null) {
                    if (compact) 12.dp else 16.dp
                } else if (compact) {
                    7.dp
                } else {
                    11.dp
                },
                end = if (compact) 7.dp else 11.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
        ) {
            iconRes?.let {
                Icon(painterResource(it), null, Modifier.size(11.dp), tint = AgentMuted)
            }
            Text(
                label, fontSize = 11.sp, lineHeight = 14.sp,
                fontWeight = FontWeight.Medium, color = foreground,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (iconRes != null) Modifier.weight(1f) else Modifier
            )
            count?.let {
                Box(
                    Modifier.height((16 * fontScale).dp)
                        .widthIn(min = (16 * fontScale).dp)
                        .background(Color(0xFFF0EFFF), CircleShape)
                        .padding(horizontal = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        it.toString(), fontSize = 10.sp, lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (enabled) AgentAccent else foreground, maxLines = 1
                    )
                }
            }
        }
    }
}

/** 对应设计素材 s / m / l；保留中权限勾号的原始紫色。 */
@Composable
internal fun AgentPermissionIcon(
    policy: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp
) {
    val (resource, label) = when (policy) {
        AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL -> R.drawable.ic_agent_permission_high to "高权限"
        AgentWritePermissionPolicy.BATCH_CONFIRM -> R.drawable.ic_agent_permission_medium to "中权限"
        else -> R.drawable.ic_agent_permission_low to "低权限"
    }
    Icon(painterResource(resource), label, modifier.size(iconSize), tint = Color.Unspecified)
}
