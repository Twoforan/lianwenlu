package com.lianwenqi.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lianwenqi.app.R

/**
 * 统一的删除确认弹窗。
 *
 * @param type 删除对象类型，如「模型」「供应商」「API 秘钥」「章节」
 * @param name 删除对象名称，会以加粗形式显示在「确定删除 xxx 吗？」中
 * @param note 第二行说明，默认「删除后不可恢复。」
 */
@Composable
fun DeleteConfirmDialog(
    type: String,
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    note: String = "删除后不可恢复。",
    noteColor: Color = Color(0xFF737C90),
    confirmLabel: String = "删除",
    confirmEnabled: Boolean = true
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { },
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE3E6EE)),
                shadowElevation = 10.dp
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 19.dp, end = 19.dp, top = 24.dp, bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 删除图标
                    Box(
                        Modifier
                            .size(54.dp)
                            .background(Color(0xFFFFF1F1), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_delete_box),
                            contentDescription = null,
                            modifier = Modifier.size(width = 26.dp, height = 23.dp),
                            tint = Color.Unspecified
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    // 标题
                    Text(
                        "删除$type",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF202536),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))

                    // 描述：确定删除 xxx 吗？
                    Text(
                        buildAnnotatedString {
                            append("确定删除 ")
                            withStyle(
                                SpanStyle(
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF424A5D)
                                )
                            ) { append(name) }
                            append(" 吗？")
                        },
                        fontSize = 12.sp,
                        lineHeight = 20.sp,
                        color = Color(0xFF737C90),
                        textAlign = TextAlign.Center
                    )
                    if (note.isNotBlank()) {
                        Text(
                            note,
                            fontSize = 12.sp,
                            lineHeight = 20.sp,
                            color = noteColor,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(15.dp))

                    // 警告提示
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFF8F5), RoundedCornerShape(11.dp))
                            .border(1.dp, Color(0xFFF9E9E3), RoundedCornerShape(11.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_warning_triangle),
                            contentDescription = null,
                            modifier = Modifier.size(width = 14.dp, height = 12.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "此操作不可撤销，请谨慎操作",
                            fontSize = 11.sp,
                            color = Color(0xFFD98265)
                        )
                    }
                    Spacer(Modifier.height(24.dp))

                    // 底部按钮
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(13.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E8EF)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF70788A))
                        ) {
                            Text("取消", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFFE6535B), Color(0xFFEF7076))
                                    ),
                                    RoundedCornerShape(13.dp)
                                )
                                .clickable(enabled = confirmEnabled) { onConfirm() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                confirmLabel,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
