package com.lianwenqi.app.ui.screens

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lianwenqi.app.util.FormatSettings
import com.lianwenqi.app.util.IMPORTED_FONT_PREFIX
import com.lianwenqi.app.util.IMPORTED_IMAGE_PREFIX
import com.lianwenqi.app.util.ReaderBackgroundPresets
import com.lianwenqi.app.util.ReaderBackgroundType
import com.lianwenqi.app.util.ReaderPageStyle
import com.lianwenqi.app.util.ReaderSettings
import com.lianwenqi.app.util.StyleSettings
import com.lianwenqi.app.util.importedName
import com.lianwenqi.app.util.readerBackgroundModel

/** 底部三个主入口（目录 / 设置 / 样式），再次点击当前入口即收起 */
const val READER_PANEL_CATALOG = "catalog"
const val READER_PANEL_SETTINGS = "settings"
const val READER_PANEL_STYLE = "style"

private val PanelMaxHeight = 360.dp

/** 阅读区背景：纯色或图片（图片上覆盖极淡白纱保证正文可读，不遮挡文字） */
@Composable
fun ReaderBackgroundLayer(style: StyleSettings, reader: ReaderSettings) {
    Box(Modifier.fillMaxSize()) {
        val model = if (reader.backgroundType == ReaderBackgroundType.IMAGE) {
            readerBackgroundModel(reader.backgroundImage, reader.importedImages)
        } else {
            null
        }
        if (model != null) {
            // 仿真翻页会把这一层连同正文一起渲染进软件 Bitmap，
            // 背景图若被 Coil 解码成硬件 Bitmap 绘制到软件 Canvas 会直接抛
            // IllegalArgumentException（Software rendering doesn't support hardware bitmaps），
            // 所以阅读区背景一律禁用硬件 Bitmap。
            val context = LocalContext.current
            val request = remember(context, model) {
                ImageRequest.Builder(context).data(model).allowHardware(false).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.22f))
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(style.backgroundColor))
            )
        }
    }
}

@Composable
fun ReaderBottomBar(
    panel: String?,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onEntry: (String) -> Unit
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onPrevChapter, enabled = hasPrev) {
                    Text("上一章", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                ReaderBottomEntry(
                    modifier = Modifier.weight(1f),
                    label = "目录",
                    selected = panel == READER_PANEL_CATALOG,
                    onClick = { onEntry(READER_PANEL_CATALOG) }
                )
                ReaderBottomEntry(
                    modifier = Modifier.weight(1f),
                    label = "设置",
                    selected = panel == READER_PANEL_SETTINGS,
                    onClick = { onEntry(READER_PANEL_SETTINGS) }
                )
                ReaderBottomEntry(
                    modifier = Modifier.weight(1f),
                    label = "样式",
                    selected = panel == READER_PANEL_STYLE,
                    onClick = { onEntry(READER_PANEL_STYLE) }
                )
                TextButton(onClick = onNextChapter, enabled = hasNext) {
                    Text("下一章", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ReaderBottomEntry(
    modifier: Modifier,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier
            .padding(horizontal = 3.dp)
            .height(38.dp)
            .background(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 设置菜单：只负责排版与阅读行为，不包含字体 / 颜色 / 背景 */
@Composable
fun ReaderSettingsPanel(
    style: StyleSettings,
    format: FormatSettings,
    reader: ReaderSettings,
    immersive: Boolean,
    onStyle: ((StyleSettings) -> StyleSettings) -> Unit,
    onFormat: ((FormatSettings) -> FormatSettings) -> Unit,
    onImmersive: (Boolean) -> Unit,
    onReader: ((ReaderSettings) -> ReaderSettings) -> Unit
) {
    ReaderPanelSurface {
        ReaderValueSlider(
            label = "行距",
            valueText = "%.1f".format(format.lineSpacing),
            value = format.lineSpacing,
            range = 1.0f..3.0f,
            onChange = { v -> onFormat { it.copy(lineSpacing = v) } }
        )
        ReaderValueSlider(
            label = "段距",
            valueText = "${format.blankLines} 行",
            value = format.blankLines.toFloat(),
            range = 0f..3f,
            steps = 2,
            onChange = { v -> onFormat { it.copy(blankLines = v.toInt()) } }
        )
        ReaderValueSlider(
            label = "首行缩进",
            valueText = if (format.indent <= 0) "关闭" else "${format.indent} 字符",
            value = format.indent.toFloat(),
            range = 0f..4f,
            steps = 3,
            onChange = { v -> onFormat { it.copy(indent = v.toInt()) } }
        )
        ReaderValueSlider(
            label = "左右边距",
            valueText = "${format.paddingHorizontal} dp",
            value = format.paddingHorizontal.toFloat(),
            range = 0f..64f,
            onChange = { v -> onFormat { it.copy(paddingHorizontal = v.toInt()) } }
        )
        ReaderValueSlider(
            label = "上下边距",
            valueText = "${format.paddingVertical} dp",
            value = format.paddingVertical.toFloat(),
            range = 0f..64f,
            onChange = { v -> onFormat { it.copy(paddingVertical = v.toInt()) } }
        )
        ReaderValueSlider(
            label = "字号",
            valueText = "${style.fontSizeSp.toInt()} sp",
            value = style.fontSizeSp,
            range = 12f..40f,
            onChange = { v -> onStyle { it.copy(fontSizeSp = v) } }
        )
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        ReaderSwitchRow(
            label = "全屏阅读（隐藏系统栏）",
            checked = immersive,
            onChange = onImmersive
        )
        ReaderSwitchRow(
            label = "音量键翻页（音量 + 上一页 / 音量 − 下一页）",
            checked = reader.volumeKeys,
            onChange = { on -> onReader { it.copy(volumeKeys = on) } }
        )
    }
}

/** 样式菜单：翻页动画、字体、字体样式、文字颜色、阅读背景 */
@Composable
fun ReaderStylePanel(
    style: StyleSettings,
    reader: ReaderSettings,
    onStyle: ((StyleSettings) -> StyleSettings) -> Unit,
    onReader: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onImportFont: () -> Unit,
    onImportImage: () -> Unit,
    onDeleteImage: (String) -> Unit
) {
    var textColorDialog by remember { mutableStateOf(false) }
    var bgColorDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    ReaderPanelSurface {
        ReaderSectionTitle("翻页动画")
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ReaderPageStyle.options.forEach { (key, label) ->
                FilterChip(
                    selected = reader.pageStyle == key,
                    onClick = { onReader { it.copy(pageStyle = key) } },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        ReaderSectionTitle("字体")
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("默认", "楷体", "衬线", "宋体").forEach { label ->
                FilterChip(
                    selected = style.fontLabel == label,
                    onClick = { onStyle { it.copy(fontLabel = label) } },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                )
            }
            reader.importedFonts.sortedBy(::importedName).forEach { spec ->
                val name = importedName(spec)
                FilterChip(
                    selected = style.fontLabel == IMPORTED_FONT_PREFIX + name,
                    onClick = { onStyle { it.copy(fontLabel = IMPORTED_FONT_PREFIX + name) } },
                    label = { Text(name, style = MaterialTheme.typography.labelMedium) }
                )
            }
            TextButton(onClick = onImportFont) { Text("导入字体") }
        }

        ReaderSectionTitle("字体样式")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = style.bold,
                onClick = {
                    val next = !style.bold
                    onStyle { it.copy(bold = next, thin = if (next) false else it.thin) }
                },
                label = { Text("加粗", style = MaterialTheme.typography.labelMedium) }
            )
            FilterChip(
                selected = style.thin,
                onClick = {
                    val next = !style.thin
                    onStyle { it.copy(thin = next, bold = if (next) false else it.bold) }
                },
                label = { Text("细体", style = MaterialTheme.typography.labelMedium) }
            )
            FilterChip(
                selected = style.italic,
                onClick = { onStyle { it.copy(italic = !it.italic) } },
                label = { Text("斜体", style = MaterialTheme.typography.labelMedium) }
            )
        }

        ReaderSectionTitle("字体颜色")
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextColors.forEach { color ->
                ColorDot(
                    color = color,
                    selected = style.textColor == color,
                    onClick = { onStyle { it.copy(textColor = color) } }
                )
            }
            TextButton(onClick = { textColorDialog = true }) { Text("自定义") }
        }

        ReaderSectionTitle("阅读背景")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = reader.backgroundType == ReaderBackgroundType.COLOR,
                onClick = { onReader { it.copy(backgroundType = ReaderBackgroundType.COLOR) } },
                label = { Text("纯色", style = MaterialTheme.typography.labelMedium) }
            )
            FilterChip(
                selected = reader.backgroundType == ReaderBackgroundType.IMAGE,
                onClick = { onReader { it.copy(backgroundType = ReaderBackgroundType.IMAGE) } },
                label = { Text("背景图片", style = MaterialTheme.typography.labelMedium) }
            )
        }
        Spacer(Modifier.height(8.dp))
        if (reader.backgroundType == ReaderBackgroundType.COLOR) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BgColors.forEach { color ->
                    ColorDot(
                        color = color,
                        selected = style.backgroundColor == color,
                        onClick = {
                            val dark = Color(color).luminance() < 0.4f
                            onStyle {
                                it.copy(
                                    backgroundColor = color,
                                    textColor = if (dark) 0xFFD8D8D8.toInt()
                                    else if (Color(it.textColor).luminance() > 0.6f) 0xFF1A1A1A.toInt()
                                    else it.textColor
                                )
                            }
                        }
                    )
                }
                TextButton(onClick = { bgColorDialog = true }) { Text("自定义") }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ReaderBackgroundPresets.forEach { preset ->
                    BackgroundThumb(
                        model = preset.resId,
                        label = preset.label,
                        selected = reader.backgroundImage == preset.key,
                        onClick = {
                            onReader {
                                it.copy(
                                    backgroundType = ReaderBackgroundType.IMAGE,
                                    backgroundImage = preset.key
                                )
                            }
                        }
                    )
                }
                reader.importedImages.sortedBy(::importedName).forEach { spec ->
                    val name = importedName(spec)
                    BackgroundThumb(
                        model = readerBackgroundModel(IMPORTED_IMAGE_PREFIX + name, reader.importedImages),
                        label = name,
                        selected = reader.backgroundImage == IMPORTED_IMAGE_PREFIX + name,
                        onClick = {
                            onReader {
                                it.copy(
                                    backgroundType = ReaderBackgroundType.IMAGE,
                                    backgroundImage = IMPORTED_IMAGE_PREFIX + name
                                )
                            }
                        },
                        onLongClick = { pendingDelete = spec }
                    )
                }
                Column(
                    Modifier
                        .size(64.dp, 88.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .clickable(onClick = onImportImage),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("＋", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                    Text("导入图片", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }

    if (textColorDialog) {
        ReaderColorPickerDialog(
            title = "自定义字体颜色",
            initial = style.textColor,
            onConfirm = { color ->
                onStyle { it.copy(textColor = color) }
                textColorDialog = false
            },
            onDismiss = { textColorDialog = false }
        )
    }
    if (bgColorDialog) {
        ReaderColorPickerDialog(
            title = "自定义阅读背景",
            initial = style.backgroundColor,
            onConfirm = { color ->
                onStyle { it.copy(backgroundColor = color) }
                onReader { it.copy(backgroundType = ReaderBackgroundType.COLOR) }
                bgColorDialog = false
            },
            onDismiss = { bgColorDialog = false }
        )
    }
    pendingDelete?.let { spec ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除背景图片") },
            text = { Text("确定要删除「${importedName(spec)}」吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteImage(spec)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ReaderPanelSurface(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = PanelMaxHeight)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            content = content
        )
    }
}

@Composable
private fun ReaderSectionTitle(text: String) {
    Spacer(Modifier.height(10.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ReaderValueSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            valueText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        valueRange = range,
        steps = steps
    )
}

@Composable
private fun ReaderSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RowScope.ColorDot(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .align(Alignment.CenterVertically)
            .size(if (selected) 30.dp else 26.dp)
            .background(Color(color), CircleShape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackgroundThumb(
    model: Any?,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            )
    ) {
        Box(
            Modifier
                .size(64.dp, 88.dp)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(2.dp)
        ) {
            AsyncImage(
                model = model,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(64.dp)
        )
    }
}

/** 字体 / 背景自定义颜色弹窗：色环选色相 + 内嵌方块选饱和度与明度 */
@Composable
fun ReaderColorPickerDialog(
    title: String,
    initial: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val hsv = remember(initial) {
        FloatArray(3).also { AndroidColor.colorToHSV(initial, it) }
    }
    var hue by remember(initial) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(initial) { mutableFloatStateOf(hsv[1].coerceIn(0f, 1f)) }
    var value by remember(initial) { mutableFloatStateOf(hsv[2].coerceIn(0f, 1f)) }
    val argb = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(236.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                fun apply(pos: Offset) {
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    val dx = pos.x - center.x
                                    val dy = pos.y - center.y
                                    val dist = kotlin.math.hypot(dx, dy)
                                    val minSide = kotlin.math.min(size.width, size.height).toFloat()
                                    val outer = minSide / 2f
                                    val inner = outer - minSide * 0.13f
                                    val half = outer - minSide * 0.30f
                                    if (dist in inner..outer) {
                                        var angle = Math.toDegrees(
                                            kotlin.math.atan2(dy.toDouble(), dx.toDouble())
                                        ).toFloat()
                                        if (angle < 0f) angle += 360f
                                        hue = angle
                                    } else if (kotlin.math.abs(dx) <= half && kotlin.math.abs(dy) <= half) {
                                        saturation = ((dx + half) / (half * 2f)).coerceIn(0f, 1f)
                                        value = (1f - (dy + half) / (half * 2f)).coerceIn(0f, 1f)
                                    }
                                }
                                apply(down.position)
                                down.consume()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.pressed } ?: break
                                    apply(change.position)
                                    change.consume()
                                }
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val center = this.center
                        val outer = size.minDimension / 2f
                        val ringStroke = size.minDimension * 0.13f
                        val r = outer - ringStroke / 2f
                        for (i in 0 until 360) {
                            drawArc(
                                color = Color(
                                    AndroidColor.HSVToColor(floatArrayOf(i.toFloat(), 0.92f, 0.96f))
                                ),
                                startAngle = i.toFloat(),
                                sweepAngle = 1.3f,
                                useCenter = false,
                                topLeft = Offset(center.x - r, center.y - r),
                                size = Size(r * 2, r * 2),
                                style = Stroke(width = ringStroke)
                            )
                        }
                        val half = outer - ringStroke * 2.3f
                        val topLeft = Offset(center.x - half, center.y - half)
                        val square = Size(half * 2, half * 2)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color.White,
                                    Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                                )
                            ),
                            topLeft = topLeft,
                            size = square
                        )
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black)
                            ),
                            topLeft = topLeft,
                            size = square
                        )
                        val sel = Offset(
                            center.x - half + saturation * half * 2,
                            center.y - half + (1f - value) * half * 2
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 8.dp.toPx(),
                            center = sel,
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(Color(argb), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("#%06X".format(0xFFFFFF and argb), style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(0xFF000000.toInt() or (argb and 0xFFFFFF)) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}


