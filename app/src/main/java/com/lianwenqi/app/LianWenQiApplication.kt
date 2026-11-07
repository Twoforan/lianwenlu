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
import com.lianwenqi.app.data.BackupRepository
import com.lianwenqi.app.data.BookRepository
import com.lianwenqi.app.data.ChatRepository
import com.lianwenqi.app.data.ConfigRepository
import com.lianwenqi.app.data.EmbeddingConfig
import com.lianwenqi.app.data.MemoryRepository
import com.lianwenqi.app.data.ManualSeeder
import com.lianwenqi.app.data.OutlineRepository
import com.lianwenqi.app.data.Prefs
import com.lianwenqi.app.data.StyleRepository
import com.lianwenqi.app.data.StyleTagRepository
import com.lianwenqi.app.data.VolumeSummaryRepository
import com.lianwenqi.app.data.style.StyleAnalysisService
import com.lianwenqi.app.data.style.StyleSampleRetriever
import com.lianwenqi.app.data.style.StyleTaggingService
import com.lianwenqi.app.data.style.StyleConstraintLoader
import com.lianwenqi.app.data.style.StyleProfileSaver
import com.lianwenqi.app.data.style.StyleProfileUpdater
import com.lianwenqi.app.data.volume.OutlineDiffService
import com.lianwenqi.app.data.volume.VolumeCompressionCoordinator
import com.lianwenqi.app.data.volume.VolumeCompressionService
import com.lianwenqi.app.data.volume.VolumeOutlineUpdateCoordinator
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

    /** 应用级协程域：压缩任务、启动恢复与后台向量化都在此执行，不随页面销毁中断 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val backupRepository: BackupRepository = BackupRepository(database, app, prefs)
    val chatRepository: ChatRepository = ChatRepository(database)
    val outlineRepository: OutlineRepository = OutlineRepository(database)
    val configRepository: ConfigRepository = ConfigRepository(database, aiClient)
    /** 整卷梗概版本仓库（F1）；压缩引擎只写入候选，不自动确认生效 */
    val volumeSummaryRepository: VolumeSummaryRepository = VolumeSummaryRepository(database)
    /** 统一整卷压缩引擎：F2 场景使用章节梗概来源 */
    val volumeCompressionService: VolumeCompressionService = VolumeCompressionService.forSynopsis(
        db = database,
        config = configRepository,
        ai = aiClient,
        summaries = volumeSummaryRepository
    )
    /** F3：章节正文来源，复用同一压缩引擎与候选写入链路 */
    val volumeContentCompressionService: VolumeCompressionService =
        VolumeCompressionService.forContent(
            db = database,
            config = configRepository,
            ai = aiClient,
            summaries = volumeSummaryRepository
        )
    /** F5：压缩任务协调器（排队、去重、逐批落库、恢复、取消与 usage 汇总） */
    val volumeCompressionCoordinator: VolumeCompressionCoordinator = VolumeCompressionCoordinator(
        db = database,
        summaries = volumeSummaryRepository,
        synopsisService = volumeCompressionService,
        contentService = volumeContentCompressionService,
        scope = appScope
    )
    /** 删书 / 删卷前先安全取消压缩任务，再由仓库事务清理任务记录 */
    val bookRepository: BookRepository = BookRepository(
        db = database,
        beforeDeleteBook = { bookId -> volumeCompressionCoordinator.cancelAndDeleteForBook(bookId) },
        beforeDeleteVolume = { bookId, volumeId ->
            volumeCompressionCoordinator.cancelAndDeleteForVolume(bookId, volumeId)
        }
    )
    /** F6：压缩确认后的大纲差异分析，复用模型网关与压缩任务 usage 统计 */
    val outlineDiffService: OutlineDiffService = OutlineDiffService.create(configRepository, aiClient)
    val volumeOutlineUpdateCoordinator: VolumeOutlineUpdateCoordinator =
        VolumeOutlineUpdateCoordinator(
            db = database,
            prefs = prefs,
            outlines = outlineRepository,
            summaries = volumeSummaryRepository,
            diffService = outlineDiffService,
            compression = volumeCompressionCoordinator,
            scope = appScope
        )
    val styleRepository: StyleRepository = StyleRepository(database)
    /** 文风分析：复用现有模型配置与 [AiClient]，不另建 API 客户端 */
    val styleAnalysisService: StyleAnalysisService = StyleAnalysisService(configRepository, aiClient)
    val styleProfileSaver: StyleProfileSaver = StyleProfileSaver(styleRepository)
    /** 文风档案编辑：复用同一 Repository，保存前查重与结构校验 */
    val styleProfileUpdater: StyleProfileUpdater = StyleProfileUpdater(styleRepository)

    /** 文风标签 / 样本：全局标签库、档案专属标签库、片段关联与章节梗概标签 */
    val styleTagRepository: StyleTagRepository = StyleTagRepository(database)

    /** 样本片段标签分析：复用现有模型配置与 AiClient，模型只输出结构化建议 */
    val styleTaggingService: StyleTaggingService =
        StyleTaggingService(configRepository, aiClient, styleTagRepository)

    /**
     * 生成请求的文风加载入口：每次按书籍 id 实时读取 styleId 与档案，
     * 换绑 / 解绑 / 编辑 / 删除后下一次生成立即使用最新数据。
     */
    val styleConstraintLoader: StyleConstraintLoader = StyleConstraintLoader(
        styleIdOf = { bookId -> bookRepository.getBook(bookId)?.styleId },
        profileOf = { id -> styleRepository.getProfile(id) }
    )
    val updateChecker: UpdateChecker = UpdateChecker(app)

    /** 向量化专用的 Embedding 客户端（只在向量化 / 检索时调用，不参与对话请求） */
    val embeddingClient: EmbeddingClient = EmbeddingClient()

    /** 本地向量化模型的文件管理：路径 / 就绪校验 / 下载（含断点续传） */
    val localModelStore: LocalModelStore = LocalModelStore(app)

    /** 本地推理引擎的懒加载持有者：模型 200MB+，首次真正推理时才开始加载 */
    val localEngineHolder: LocalEngineHolder = LocalEngineHolder(localModelStore)

    /**
     * 向量化能力：按「向量化工具」设置分发到厂商 Embedding API 或本地 GGUF 工具。
     * 上层（MemoryRepository）只依赖这个接口，因此切到本地工具后
     * 「原先提交给向量 API 的内容」自动改由本地模型计算并返回。
     */
    val memoryEmbeddingGateway: EmbeddingGateway = MemoryEmbeddingGateway(
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

    /** 正文生成前的文风样本抽样：只按标签权重随机抽，硬性按 styleProfileId 过滤 */
    val styleSampleRetriever: StyleSampleRetriever = StyleSampleRetriever(styleTagRepository)
}

class LianWenQiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 先把上次进程中断的压缩任务标记为可恢复，再恢复排队中的任务
        container.appScope.launch {
            container.volumeCompressionCoordinator.recoverInterrupted()
            container.volumeOutlineUpdateCoordinator.recoverInterrupted()
        }
        // 首次启动时把内置《使用说明书》导入书架，之后可选中它向 AI 提问本 App 的用法
        container.appScope.launch {
            ManualSeeder.ensureSeeded(this@LianWenQiApplication, container.prefs)
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as LianWenQiApplication).container