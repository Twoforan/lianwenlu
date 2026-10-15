package com.lianwenqi.app

import android.app.Application
import android.content.Context
import com.lianwenqi.app.ai.AiClient
import com.lianwenqi.app.ai.EmbeddingClient
import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.BookRepository
import com.lianwenqi.app.data.ChatRepository
import com.lianwenqi.app.data.ConfigRepository
import com.lianwenqi.app.data.EmbeddingConfig
import com.lianwenqi.app.data.MemoryRepository
import com.lianwenqi.app.data.OutlineRepository
import com.lianwenqi.app.data.Prefs
import com.lianwenqi.app.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

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

    /** 应用级协程域：A2 开启后正文保存触发的后台静默向量化在此执行 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val memoryRepository: MemoryRepository = MemoryRepository(database, embeddingClient) {
        EmbeddingConfig(
            baseUrl = prefs.memoryEmbeddingBaseUrl.first(),
            apiKey = prefs.memoryEmbeddingApiKey.first(),
            model = prefs.memoryEmbeddingModel.first()
        )
    }
}

class LianWenQiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as LianWenQiApplication).container