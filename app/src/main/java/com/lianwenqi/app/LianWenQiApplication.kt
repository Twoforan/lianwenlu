package com.lianwenqi.app

import android.app.Application
import android.content.Context
import com.lianwenqi.app.ai.AiClient
import com.lianwenqi.app.ai.EmbeddingClient
import com.lianwenqi.app.ai.EmbeddingGateway
import com.lianwenqi.app.ai.LocalEmbeddingGateway
import com.lianwenqi.app.ai.LocalEngineHolder
import com.lianwenqi.app.ai.LocalModelStore
import com.lianwenqi.app.ai.MemoryEmbeddingGateway
import com.lianwenqi.app.ai.RemoteEmbeddingGateway
import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.BookRepository
import com.lianwenqi.app.data.ChatRepository
import com.lianwenqi.app.data.ConfigRepository
import com.lianwenqi.app.data.EmbeddingConfig
import com.lianwenqi.app.data.MemoryRepository
import com.lianwenqi.app.data.ManualSeeder
import com.lianwenqi.app.data.OutlineRepository
import com.lianwenqi.app.data.Prefs
import com.lianwenqi.app.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 简单的依赖容器（ServiceLocator），供 ViewModel 获取 */
class AppContainer(val app: Application) {
    val context: Context get() = app.applicationContext
    val database: AppDatabase = AppDatabase.get(app)
    val aiClient: AiClient = AiClient()
    val prefs: Prefs = Prefs(app)
    val bookRepository: BookRepository = BookRepository(database)
    val chatRepository: ChatRepository = ChatRepository(database)
    val outlineRepository: OutlineRepository = OutlineRepository(database)
    val configRepository: ConfigRepository = ConfigRepository(database, aiClient)
    val updateChecker: UpdateChecker = UpdateChecker(app)

    /** 向量化专用的 Embedding 客户端（只在向量化 / 检索时调用，不参与对话请求） */
    val embeddingClient: EmbeddingClient = EmbeddingClient()

    /** 本地向量化模型的文件管理：路径 / 就绪校验 / 下载（含断点续传） */
    val localModelStore: LocalModelStore = LocalModelStore(app)

    /** 本地推理引擎的懒加载持有者：模型 200MB+，首次真正推理时才开始加载 */
    val localEngineHolder: LocalEngineHolder = LocalEngineHolder(localModelStore)

    /** 应用级协程域：A2 开启后正文保存触发的后台静默向量化在此执行 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 向量化能力：按「向量化工具」设置分发到厂商 Embedding API 或本地 GGUF 工具。
     * 上层（MemoryRepository）只依赖这个接口，因此切到本地工具后
     * 「原先提交给向量 API 的内容」自动改由本地模型计算并返回。
     */
    private val memoryEmbeddingGateway: EmbeddingGateway = MemoryEmbeddingGateway(
        remote = RemoteEmbeddingGateway(embeddingClient) {
            EmbeddingConfig(
                baseUrl = prefs.memoryEmbeddingBaseUrl.first(),
                apiKey = prefs.memoryEmbeddingApiKey.first(),
                model = prefs.memoryEmbeddingModel.first()
            )
        },
        local = LocalEmbeddingGateway { localEngineHolder.get() },
        mode = { prefs.memoryToolMode.first() }
    )

    val memoryRepository: MemoryRepository = MemoryRepository(database, memoryEmbeddingGateway)
}

class LianWenQiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 首次启动时把内置《使用说明书》导入书架，之后可选中它向 AI 提问本 App 的用法
        container.appScope.launch {
            ManualSeeder.ensureSeeded(this@LianWenQiApplication, container.prefs)
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as LianWenQiApplication).container