package com.lianwenqi.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lianwenqi.app.data.entity.Book
import java.io.File

/** 封面配色（与添加书籍设计稿一致的 7 组低饱和渐变底色） */
val CoverColors = listOf(
    0xFF3E446D.toInt(), 0xFFB17D6B.toInt(), 0xFFD9D3C6.toInt(), 0xFF8699B6.toInt(),
    0xFFC5A461.toInt(), 0xFFA68FBC.toInt(), 0xFF5A8178.toInt()
)

/** 封面装饰符号：按书籍 id 轮换，避免每本书封面完全一样 */
private val CoverGlyphs = listOf("✦", "❀", "❧", "☾", "✿", "✧")

/**
 * 书籍封面：渐变底色 + 斜向反光 + 左侧书脊阴影 + 内侧装饰边框。
 * 有封面图片时图片铺满，三层光影同样叠加在图片上；无图片时显示书名。
 */
@Composable
fun BookCover(
    book: Book,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    titleSize: Int = 13
) {
    val shape = RoundedCornerShape(
        topStart = cornerRadius * 0.7f,
        topEnd = cornerRadius,
        bottomEnd = cornerRadius,
        bottomStart = cornerRadius * 0.7f
    )
    val base = Color(book.coverColor)
    val topColor = lerp(base, Color.White, 0.28f)
    val bottomColor = lerp(base, Color.Black, 0.42f)
    val ink = Color(0xFFF7EEDC)
    val glyph = book.coverGlyph?.takeIf { it.isNotBlank() }
        ?: CoverGlyphs[((book.id + book.title.length) % CoverGlyphs.size).toInt()]
    val coverTitle = if (book.coverTitleMode == 1) book.title.take(4) else book.title

    Box(
        modifier = modifier
            .shadow(elevation = 6.dp, shape = shape, clip = false)
            .clip(shape)
            .background(Brush.linearGradient(listOf(topColor, bottomColor)))
    ) {
        if (!book.coverPath.isNullOrBlank()) {
            AsyncImage(
                model = File(book.coverPath),
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = glyph,
                    color = ink.copy(alpha = 0.85f),
                    fontSize = (titleSize * 1.7f).sp,
                    lineHeight = (titleSize * 1.9f).sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = coverTitle,
                    color = ink,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = titleSize.sp,
                        lineHeight = (titleSize * 1.45f).sp,
                        letterSpacing = (titleSize * 0.15f).sp,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.22f),
                            offset = Offset(0f, 1f),
                            blurRadius = 3f
                        )
                    )
                )
                if (book.coverEnglish.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = book.coverEnglish,
                        color = ink.copy(alpha = 0.7f),
                        fontSize = (titleSize * 0.42f).coerceAtLeast(4.5f).sp,
                        letterSpacing = (titleSize * 0.2f).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 三层光影：整体光照 → 斜向反光带 → 左侧书脊阴影
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.28f),
                    0.18f to Color.White.copy(alpha = 0.10f),
                    0.42f to Color.Transparent,
                    0.72f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.12f),
                    start = Offset(0f, 0f),
                    end = Offset(size.width * 0.45f, size.height)
                )
            )
            rotate(28f, pivot = center) {
                val bandWidth = size.width * 0.44f
                val left = size.width * 0.28f
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.12f to Color.White.copy(alpha = 0.02f),
                        0.28f to Color.White.copy(alpha = 0.06f),
                        0.42f to Color.White.copy(alpha = 0.13f),
                        0.52f to Color.White.copy(alpha = 0.18f),
                        0.64f to Color.White.copy(alpha = 0.14f),
                        0.80f to Color.White.copy(alpha = 0.07f),
                        0.92f to Color.White.copy(alpha = 0.02f),
                        1f to Color.Transparent,
                        startX = left,
                        endX = left + bandWidth
                    ),
                    topLeft = Offset(left, -size.height),
                    size = Size(bandWidth, size.height * 3f)
                )
            }
            val spineWidth = (size.width * 0.08f).coerceAtLeast(3f)
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.20f),
                    0.45f to Color.White.copy(alpha = 0.10f),
                    1f to Color.Transparent,
                    startX = 0f,
                    endX = spineWidth
                ),
                size = Size(spineWidth, size.height)
            )
        }

        // 内侧装饰边框
        Box(
            Modifier
                .fillMaxSize()
                .padding(5.dp)
                .border(1.dp, Color.White.copy(alpha = 0.35f))
        )
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}