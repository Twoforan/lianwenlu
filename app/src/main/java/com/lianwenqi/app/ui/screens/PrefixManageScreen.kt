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

    /** 首轮全选大纲（默认关闭） */
    val outlineFirstRound: StateFlow<Boolean> =
        prefs.aiOutlineFirstRound.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 首轮全选章节（默认开启） */
    val chapterFirstRound: StateFlow<Boolean> =
        prefs.aiChapterFirstRound.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 历史对话前缀：历史梗概 / 历史正文（默认关闭，新建会话生效） */
    val historySummaryOn: StateFlow<Boolean> =
        prefs.prefixHistorySummary.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val historyContentOn: StateFlow<Boolean> =
        prefs.prefixHistoryContent.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 历史对话前缀：每轮消息父开关与角色子开关（默认关闭，新建会话生效） */
    val historyMessageOn: StateFlow<Boolean> =
        prefs.prefixHistoryMessage.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val historyAiOn: StateFlow<Boolean> =
        prefs.prefixHistoryAi.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val historyUserOn: StateFlow<Boolean> =
        prefs.prefixHistoryUser.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setContextEnabled(v: Boolean) = viewModelScope.launch { prefs.setPrefixContextEnabled(v) }
    fun setOutlineEnabled(v: Boolean) = viewModelScope.launch { prefs.setPrefixOutlineEnabled(v) }
    fun setCustomText(v: String) = viewModelScope.launch { prefs.setPrefixCustomText(v) }
    fun setAdaptiveFilter(v: Boolean) = viewModelScope.launch { prefs.setPrefixAdaptiveFilterEnabled(v) }
    fun setOutlineFirstRound(v: Boolean) = viewModelScope.launch { prefs.setAiOutlineFirstRound(v) }
    fun setChapterFirstRound(v: Boolean) = viewModelScope.launch { prefs.setAiChapterFirstRound(v) }
    fun setHistorySummaryOn(v: Boolean) = viewModelScope.launch { prefs.setPrefixHistorySummary(v) }
    fun setHistoryContentOn(v: Boolean) = viewModelScope.launch { prefs.setPrefixHistoryContent(v) }
    fun setHistoryMessageOn(v: Boolean) = viewModelScope.launch { prefs.setPrefixHistoryMessage(v) }
    fun setHistoryAiOn(v: Boolean) = viewModelScope.launch { prefs.setPrefixHistoryAi(v) }
    fun setHistoryUserOn(v: Boolean) = viewModelScope.launch { prefs.setPrefixHistoryUser(v) }
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
    val chapterFirstRound by vm.chapterFirstRound.collectAsState()
    val savedText by vm.customText.collectAsState()
    val historySummaryOn by vm.historySummaryOn.collectAsState()
    val historyContentOn by vm.historyContentOn.collectAsState()
    val historyMessageOn by vm.historyMessageOn.collectAsState()
    val historyAiOn by vm.historyAiOn.collectAsState()
    val historyUserOn by vm.historyUserOn.collectAsState()
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
                    Text("首轮全选大纲")
                    Text(
                        "新建「跟书」会话时默认勾选全部大纲。大纲在首轮发送时写入会话前缀并保持稳定；" +
                            "首轮发送后勾选会自动取消，第二轮起默认不勾选，只有重新勾选才改写前缀。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = outlineFirstRound, onCheckedChange = { vm.setOutlineFirstRound(it) })
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("首轮全选章节")
                    Text(
                        "新建「跟书」会话时默认全选章节（梗概默认全部纳入、正文默认不纳入，" +
                            "可在「选择上下文」里按卷 / 章节逐项调整）。章节在首轮发送时写入会话前缀；" +
                            "首轮发送后勾选会自动取消，第二轮起默认不勾选，只有重新勾选才改写前缀。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = chapterFirstRound, onCheckedChange = { vm.setChapterFirstRound(it) })
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("历史对话前缀")
            Spacer(Modifier.height(4.dp))
            Text(
                "开关在建会话时冻结，配置修改后新建会话生效。" +
                    "历史梗概 / 历史正文读取本会话已绑定过的章节（排除本次请求所在章节），" +
                    "按书籍中的章节顺序排列、同章去重，空梗概 / 空正文的章节跳过，正文使用完整内容；" +
                    "两者在每次发送时按章节当前内容重算，章节更新会反映到下一次请求。" +
                    "每轮消息只在每次发送时按角色开关重新生成（不冻结为固定快照），" +
                    "消息增长或变化可能影响前缀缓存。全部关闭时请求不再携带本会话历史消息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("历史梗概")
                    Text(
                        "加入本会话已绑定章节（不含本次请求章节）的梗概；没有梗概的章节跳过",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = historySummaryOn, onCheckedChange = { vm.setHistorySummaryOn(it) })
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("历史正文")
                    Text(
                        "加入本会话已绑定章节（不含本次请求章节）的完整正文；没有正文的章节跳过",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = historyContentOn, onCheckedChange = { vm.setHistoryContentOn(it) })
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("每轮消息")
                    Text(
                        "每次发送时按本会话消息重新生成历史对话块；关闭后不注入任何消息内容",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = historyMessageOn, onCheckedChange = { vm.setHistoryMessageOn(it) })
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 24.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("AI 回复")
                    Text(
                        "加入历史中当前生效的 AI 回答（废弃的重新生成版本不加入）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = historyAiOn,
                    enabled = historyMessageOn,
                    onCheckedChange = { vm.setHistoryAiOn(it) }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 24.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("用户回复")
                    Text(
                        "加入历史中的用户消息；不含本轮正在发送的输入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = historyUserOn,
                    enabled = historyMessageOn,
                    onCheckedChange = { vm.setHistoryUserOn(it) }
                )
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
