package com.lianwenqi.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.ui.appViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 个性化设置：当前只提供「书架背景图」。
 *
 * 图片复制、替换与删除都在 [PersonalizationViewModel] 中执行，Composable 只负责展示与触发。
 */
class PersonalizationViewModel(private val container: AppContainer) : ViewModel() {

    private val prefs = container.prefs
    private val store = container.shelfBackgroundStore

    /** 当前可用的书架背景图；文件缺失时视为未设置，自动回退默认背景 */
    val shelfBackgroundPath: StateFlow<String?> = prefs.shelfBackgroundImage
        .map { path -> path?.takeIf { store.isUsable(it) } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /**
     * 导入或更换背景图：先复制成功并写入配置，再删除旧文件；
     * 写入配置失败时删除刚复制的新文件，保证原背景不受影响。
     */
    fun importImage(uri: Uri, onResult: (String?) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val oldPath = prefs.shelfBackgroundImage.first()
            store.copyFromUri(uri)
                .onSuccess { newPath ->
                    runCatching { prefs.setShelfBackgroundImage(newPath) }
                        .onSuccess {
                            if (!oldPath.isNullOrBlank() && oldPath != newPath) {
                                store.deleteFile(oldPath)
                            }
                            onResult(null)
                        }
                        .onFailure { error ->
                            store.deleteFile(newPath)
                            onResult("保存背景设置失败：${error.message ?: "未知错误"}")
                        }
                }
                .onFailure { error ->
                    onResult("图片导入失败：${error.message ?: "未知错误"}")
                }
            _busy.value = false
        }
    }

    /** 恢复默认背景：清除配置并删除已不再使用的图片文件 */
    fun restoreDefault(onResult: (String?) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val oldPath = prefs.shelfBackgroundImage.first()
            runCatching { prefs.setShelfBackgroundImage(null) }
                .onSuccess {
                    store.deleteFile(oldPath)
                    onResult(null)
                }
                .onFailure { error ->
                    onResult("恢复默认失败：${error.message ?: "未知错误"}")
                }
            _busy.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalizationSettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { PersonalizationViewModel(it) }
    val context = LocalContext.current
    val backgroundPath by vm.shelfBackgroundPath.collectAsState()
    val busy by vm.busy.collectAsState()
    var showRestoreConfirm by remember { mutableStateOf(false) }

    // 系统图片选择器：取消选择时 uri 为 null，直接忽略
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            vm.importImage(uri) { error ->
                Toast.makeText(context, error ?: "已设置书架背景图", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun pickImage() {
        imagePicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个性化设置") },
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
            Text("书架背景", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "导入手机本地图片作为书架界面的背景；图片会复制到应用内保存，不会因相册原图被删除而失效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            ShelfBackgroundPreview(path = backgroundPath)
            Spacer(Modifier.height(8.dp))
            Text(
                if (backgroundPath == null) "当前使用：默认背景" else "当前使用：自定义背景",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (backgroundPath == null) {
                Button(
                    onClick = { pickImage() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("导入图片") }
            } else {
                Row(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { pickImage() },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("更换图片") }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { showRestoreConfirm = true },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("恢复默认") }
                }
            }

            if (busy) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    "正在处理图片…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("恢复默认背景") },
            text = { Text("将移除书架背景图并恢复默认背景，已导入的图片文件会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirm = false
                        vm.restoreDefault { error ->
                            Toast.makeText(
                                context,
                                error ?: "已恢复默认背景",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) { Text("恢复默认") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 背景图预览：有图时 CenterCrop 展示；无图或加载失败时显示默认背景示意 */
@Composable
private fun ShelfBackgroundPreview(path: String?) {
    val shape = RoundedCornerShape(12.dp)
    var loadFailed by remember(path) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center
    ) {
        if (path != null && !loadFailed) {
            AsyncImage(
                model = File(path),
                contentDescription = "当前书架背景图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { loadFailed = true }
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "默认背景",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
