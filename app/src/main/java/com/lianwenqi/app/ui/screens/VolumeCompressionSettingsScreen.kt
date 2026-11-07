package com.lianwenqi.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.data.CompressionOutlineScopeMode
import com.lianwenqi.app.data.CompressionOutlineUpdateMode
import com.lianwenqi.app.data.CompressionSourceMode
import com.lianwenqi.app.data.VolumeCompressionConfig
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.ui.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class VolumeCompressionSettingsViewModel(container: AppContainer) : ViewModel() {
    private val prefs = container.prefs
    private val configRepository = container.configRepository

    val config: StateFlow<VolumeCompressionConfig> = prefs.compressionConfig.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        VolumeCompressionConfig.DEFAULT
    )

    val models: StateFlow<List<AiModel>> = configRepository.observeModels().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    fun update(block: (VolumeCompressionConfig) -> VolumeCompressionConfig) {
        viewModelScope.launch {
            prefs.setCompressionConfig(block(config.value).normalized())
        }
    }
}

/**
 * 全局默认压缩设置。
 *
 * 这里保存的是「以后每次压缩默认使用」的值；单次任务仍然在配置弹窗里确认 / 临时覆盖，
 * 不会因为一次不同的选择而改写这里的默认值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeCompressionSettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { VolumeCompressionSettingsViewModel(it) }
    val config by vm.config.collectAsState()
    val models by vm.models.collectAsState()
    var modelMenu by remember { mutableStateOf(false) }
    var maxCharsText by remember(config.maxCharsPerRequest) {
        mutableStateOf(config.maxCharsPerRequest.toString())
    }
    var outputChars by remember(config.outputChars) {
        mutableFloatStateOf(config.outputChars.toFloat())
    }
    val selectedModel = models.firstOrNull { it.id == config.compressionModelId }
        ?: models.firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分卷压缩") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SwitchRow(
                title = "勾选卷梗概时自动压缩",
                subtitle = "开启后，在上下文里勾选尚未生成的整卷梗概会按默认来源自动压缩；关闭时只提示先手动生成。",
                checked = config.autoCompressWholeVolumeSummary,
                onCheckedChange = {
                    vm.update { config -> config.copy(autoCompressWholeVolumeSummary = it) }
                }
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("默认压缩来源")
            Text(
                "章节梗概更省 Token；章节正文更完整。选择「每次询问」时，压缩前会先让你确认来源。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            CompressionSourceMode.all.forEach { mode ->
                RadioRow(
                    label = CompressionSourceMode.label(mode),
                    selected = config.source == mode,
                    onSelect = { vm.update { it.copy(source = mode) } }
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("分卷压缩模型")
            Text(
                "分卷压缩默认调用这个模型；下拉列表显示模型备注名。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Box {
                OutlinedButton(
                    onClick = { modelMenu = true },
                    enabled = models.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        selectedModel?.displayName ?: "尚未配置模型",
                        maxLines = 1
                    )
                }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.displayName) },
                            onClick = {
                                vm.update { it.copy(compressionModelId = model.id) }
                                modelMenu = false
                            }
                        )
                    }
                }
            }
            if (models.isEmpty()) {
                Text(
                    "还没有可用模型：请先到设置中添加模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (config.compressionModelId == 0L) {
                Text(
                    "未指定时，默认使用列表中的第一个模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("每次最大提交字符数")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = maxCharsText,
                onValueChange = { input ->
                    val digits = input.filter { it.isDigit() }.take(6)
                    maxCharsText = digits
                    digits.toIntOrNull()?.let { value ->
                        if (
                            value >= VolumeCompressionConfig.MIN_MAX_CHARS_PER_REQUEST &&
                            value <= VolumeCompressionConfig.MAX_MAX_CHARS_PER_REQUEST
                        ) {
                            vm.update { it.copy(maxCharsPerRequest = value) }
                        }
                    }
                },
                label = { Text("字符数") },
                placeholder = { Text("默认 12000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "单次请求最多提交的字符数（按字符估算，默认 12000 字）；超出的章节会自动分批或无损切片。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("输出字数")
            Text(
                "最终卷梗概的目标字数（500–10000 字），整合时会按该篇幅输出；" +
                    "实际输出还受模型最大输出 Token 限制。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = outputChars,
                    onValueChange = { outputChars = it },
                    valueRange = VolumeCompressionConfig.MIN_OUTPUT_CHARS.toFloat()..
                        VolumeCompressionConfig.MAX_OUTPUT_CHARS.toFloat(),
                    steps = (VolumeCompressionConfig.MAX_OUTPUT_CHARS -
                        VolumeCompressionConfig.MIN_OUTPUT_CHARS) / 100 - 1,
                    onValueChangeFinished = {
                        val value = ((outputChars / 100f).roundToInt() * 100)
                            .coerceIn(
                                VolumeCompressionConfig.MIN_OUTPUT_CHARS,
                                VolumeCompressionConfig.MAX_OUTPUT_CHARS
                            )
                        outputChars = value.toFloat()
                        vm.update { it.copy(outputChars = value) }
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${outputChars.roundToInt()} 字",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("压缩完成后更新大纲")
            Text(
                "关闭不会影响整卷梗概生成；重新开启时，下面选择的大纲范围仍然保留。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            listOf(
                CompressionOutlineUpdateMode.ON,
                CompressionOutlineUpdateMode.ASK,
                CompressionOutlineUpdateMode.OFF
            ).forEach { mode ->
                RadioRow(
                    label = CompressionOutlineUpdateMode.label(mode),
                    selected = config.outlineUpdateMode == mode,
                    onSelect = { vm.update { it.copy(outlineUpdateMode = mode) } }
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("大纲更新范围")
            Text(
                "「全部大纲」更新整本书的大纲；「仅可更新大纲」只更新在书籍大纲里长按标记为「可更新」的条目。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            CompressionOutlineScopeMode.all.forEach { mode ->
                RadioRow(
                    label = CompressionOutlineScopeMode.label(mode),
                    selected = config.outlineScopeMode == mode,
                    enabled = config.outlineUpdateEnabled,
                    onSelect = { vm.update { it.copy(outlineScopeMode = mode) } }
                )
            }
            if (!config.outlineUpdateEnabled) {
                Text(
                    "大纲更新已关闭：范围选择仍会保存，但本次不会改动大纲。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onSelect: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Text(
            label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else
                MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
