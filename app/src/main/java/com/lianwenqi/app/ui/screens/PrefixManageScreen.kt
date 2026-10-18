package com.lianwenqi.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.ui.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PrefixManageViewModel(private val container: AppContainer) : ViewModel() {
    private val prefs = container.prefs

    /** 上下文前缀化（默认开启） */
    val contextEnabled: StateFlow<Boolean> =
        prefs.prefixContextEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 大纲前缀化（默认开启） */
    val outlineEnabled: StateFlow<Boolean> =
        prefs.prefixOutlineEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 自定义前缀（纯文本） */
    val customText: StateFlow<String> =
        prefs.prefixCustomText.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 适配性筛选（默认关闭） */
    val adaptiveFilter: StateFlow<Boolean> =
        prefs.prefixAdaptiveFilterEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 首轮全选大纲与章节（默认关闭） */
    val outlineFirstRound: StateFlow<Boolean> =
        prefs.aiOutlineFirstRound.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setContextEnabled(v: Boolean) = viewModelScope.launch { prefs.setPrefixContextEnabled(v) }
    fun setOutlineEnabled(v: Boolean) = viewModelScope.launch { prefs.setPrefixOutlineEnabled(v) }
    fun setCustomText(v: String) = viewModelScope.launch { prefs.setPrefixCustomText(v) }
    fun setAdaptiveFilter(v: Boolean) = viewModelScope.launch { prefs.setPrefixAdaptiveFilterEnabled(v) }
    fun setOutlineFirstRound(v: Boolean) = viewModelScope.launch { prefs.setAiOutlineFirstRound(v) }
}

/**
 * 前缀词管理：上下文前缀化、大纲前缀化与自定义前缀。
 * 说明：前缀按「会话」生效 —— 只增不减，新建对话是全新的空前缀，删除会话即删除其前缀。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrefixManageScreen(onBack: () -> Unit) {
    val vm = appViewModel { PrefixManageViewModel(it) }
    val contextEnabled by vm.contextEnabled.collectAsState()
    val outlineEnabled by vm.outlineEnabled.collectAsState()
    val adaptiveFilter by vm.adaptiveFilter.collectAsState()
    val outlineFirstRound by vm.outlineFirstRound.collectAsState()
    val savedText by vm.customText.collectAsState()
    // 输入框用本地状态，避免每次写入 DataStore 后回传造成光标跳动
    var editing by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("前缀词管理") },
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("上下文前缀化")
                    Text(
                        "开启后，勾选的书籍 / 章节在首次发送时写进会话前缀并只增不减：" +
                            "取消勾选不会移除，新勾选的追加在末尾；只有重新勾选时才对比内容并更新，" +
                            "持续勾选的内容不会被刷新",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = contextEnabled, onCheckedChange = { vm.setContextEnabled(it) })
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("大纲前缀化")
                    Text(
                        "开启后，勾选的大纲在首轮写进会话前缀并只增不减；" +
                            "关闭则大纲只作为普通提示词随本轮勾选实时发送",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = outlineEnabled, onCheckedChange = { vm.setOutlineEnabled(it) })
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("适配性筛选")
                    Text(
                        "开启后，首轮全选大纲改为只选「常驻化」分类下的全部内容；" +
                            "发送提示词时，自动用本章梗概匹配「非常驻化」分类下各条目的显示标签，" +
                            "命中的条目把其下完整字段（表）作为「新增大纲资料」在检索片段之后、提示词之前" +
                            "提交给 AI（已命中的不重复提交）。关闭则维持现状：首轮全选全部大纲",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = adaptiveFilter, onCheckedChange = { vm.setAdaptiveFilter(it) })
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("首轮全选大纲与章节")
                    Text(
                        "新建「跟书」会话时默认全选大纲与章节（梗概默认全部纳入、正文默认不纳入，" +
                            "可在「选择上下文」里按卷 / 章节逐项调整）。两项内容由本页在首轮写入会话前缀并保持稳定，" +
                            "只有重新勾选才改写前缀。「本章梗概」只写进本轮提示词、不进前缀，避免打断缓存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = outlineFirstRound, onCheckedChange = { vm.setOutlineFirstRound(it) })
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("自定义前缀")
            Spacer(Modifier.height(4.dp))
            Text(
                "纯文本。新建对话建立前缀时取一次并固定在该会话中，" +
                    "之后修改此处不会影响已有对话，只对之后新建的对话生效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = editing ?: savedText,
                onValueChange = {
                    editing = it
                    vm.setCustomText(it)
                },
                placeholder = { Text("例如：你是一位资深网文作者，请按以下风格续写……") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                "前缀只属于当前对话：点「＋」新建对话即为全新的空前缀，删除会话时其前缀随之删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
