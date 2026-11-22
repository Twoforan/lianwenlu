package com.lianwenqi.app

import android.app.Application
import android.content.Context
import com.lianwenqi.app.agent.context.AgentContextAssembler
import com.lianwenqi.app.agent.context.AgentContextCompressor
import com.lianwenqi.app.agent.context.AgentReferenceResolver
import com.lianwenqi.app.agent.background.AgentTaskController
import com.lianwenqi.app.agent.background.AgentTaskRecoveryCoordinator
import com.lianwenqi.app.agent.background.AgentTaskScheduler
import com.lianwenqi.app.agent.background.WorkManagerAgentTaskScheduler
import com.lianwenqi.app.agent.data.AgentEventRepository
import com.lianwenqi.app.agent.data.AgentExecutionLeaseRepository
import com.lianwenqi.app.agent.data.AgentExecutionNodeRepository
import com.lianwenqi.app.agent.data.AgentModelRequestRepository
import com.lianwenqi.app.agent.data.AgentRecoveryLogRepository
import com.lianwenqi.app.agent.data.AgentReferenceRepository
import com.lianwenqi.app.agent.data.AgentReferenceSourceRepository
import com.lianwenqi.app.agent.data.AgentSessionRepository
import com.lianwenqi.app.agent.data.AgentTaskReferenceRepository
import com.lianwenqi.app.agent.data.AgentTaskRepository
import com.lianwenqi.app.agent.data.AgentToolExecutionRepository
import com.lianwenqi.app.agent.data.AgentWriteOperationRepository
import com.lianwenqi.app.agent.execution.AgentExecutionEngine
import com.lianwenqi.app.agent.execution.AgentTaskRunner
import com.lianwenqi.app.agent.planning.AgentModelClient
import com.lianwenqi.app.agent.planning.AgentPlanParser
import com.lianwenqi.app.agent.planning.AgentPlanValidator
import com.lianwenqi.app.agent.planning.AgentPlanner
import com.lianwenqi.app.agent.tool.AgentGenerationTools
import com.lianwenqi.app.agent.tool.AgentInteractionTools
import com.lianwenqi.app.agent.tool.AgentReadOnlyTools
import com.lianwenqi.app.agent.tool.AgentToolExecutor
import com.lianwenqi.app.agent.tool.AgentToolRegistry
import com.lianwenqi.app.agent.tool.AgentToolValidator
import com.lianwenqi.app.agent.write.AgentWriteConfirmationGate
import com.lianwenqi.app.agent.write.AgentWriteExecutionService
import com.lianwenqi.app.agent.write.AgentWriteRecoveryService
import com.lianwenqi.app.agent.write.AgentWriteTools
import com.lianwenqi.app.agent.write.RoomAgentWriteBusinessWriter
import com.lianwenqi.app.agent.write.RoomAgentWriteTransactionRunner
import com.lianwenqi.app.ai.AiClient
import com.lianwenqi.app.ai.ChapterGenerationService
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
import com.lianwenqi.app.data.entity.defaultOutlineKeys
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
    /** 当前进程启动时间；用于判断上一进程遗留的执行租约 */
    val processStartedAt: Long = System.currentTimeMillis()
    val aiClient: AiClient = AiClient()
    val prefs: Prefs = Prefs(app)
    /** F7：唯一的应用级任务调度器；页面与恢复扫描都只能通过它安排执行 */
    val agentTaskScheduler: AgentTaskScheduler = WorkManagerAgentTaskScheduler(app)

    /** 应用级协程域：压缩任务、启动恢复与后台向量化都在此执行，不随页面销毁中断 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val backupRepository: BackupRepository = BackupRepository(database, app, prefs)
    val chatRepository: ChatRepository = ChatRepository(database)
    /** Agent 独立会话体系：只读写 agent_* 表，不依赖普通聊天仓库 */
    val agentSessionRepository: AgentSessionRepository = AgentSessionRepository(database)
    val agentReferenceRepository: AgentReferenceRepository = AgentReferenceRepository(database)
    /** F5：会话引用的只读来源访问与任务级快照 */
    val agentReferenceSourceRepository: AgentReferenceSourceRepository =
        AgentReferenceSourceRepository(database)
    val agentTaskReferenceRepository: AgentTaskReferenceRepository =
        AgentTaskReferenceRepository(database)
    /** F2：Agent 任务与执行过程数据层，不发起模型请求、不执行工具 */
    val agentTaskRepository: AgentTaskRepository = AgentTaskRepository(database)
    val agentExecutionNodeRepository: AgentExecutionNodeRepository =
        AgentExecutionNodeRepository(database)
    val agentToolExecutionRepository: AgentToolExecutionRepository =
        AgentToolExecutionRepository(database)
    val agentEventRepository: AgentEventRepository = AgentEventRepository(database)
    /** F7：数据库级执行租约、模型请求记录与恢复日志 */
    val agentExecutionLeaseRepository: AgentExecutionLeaseRepository =
        AgentExecutionLeaseRepository(database)
    val agentModelRequestRepository: AgentModelRequestRepository =
        AgentModelRequestRepository(database)
    val agentRecoveryLogRepository: AgentRecoveryLogRepository =
        AgentRecoveryLogRepository(database)
    /** Agent 请求不展示增量内容，仅用流式保持长连接，避免长任务被网关判超时（524） */
    private val agentStreamSink: (String, String) -> Unit = { _, _ -> }
    /** F3：Agent 独立模型请求与规划；不进入普通聊天请求链路 */
    val agentModelClient: AgentModelClient = AgentModelClient(
        endpointFor = { modelId -> configRepository.endpointFor(modelId) },
        invoke = { endpoint, turns ->
            aiClient.chat(endpoint, turns, onDelta = agentStreamSink)
        },
        invokeWithTools = { endpoint, turns, tools ->
            aiClient.chat(endpoint, turns, tools, onDelta = agentStreamSink)
        }
    )
    val agentPlanParser: AgentPlanParser = AgentPlanParser()
    val agentPlanValidator: AgentPlanValidator = AgentPlanValidator()
    /** Agent 上下文自动压缩：计划与执行接近模型窗口上限时使用 */
    val agentContextCompressor: AgentContextCompressor = AgentContextCompressor(agentModelClient)
    val agentPlanner: AgentPlanner = AgentPlanner(
        modelClient = agentModelClient,
        parser = agentPlanParser,
        validator = agentPlanValidator,
        contextCompressor = agentContextCompressor
    )
    val outlineRepository: OutlineRepository = OutlineRepository(database)
    val configRepository: ConfigRepository = ConfigRepository(database, aiClient, prefs)
    /** 章节梗概 / 正文生成的共享服务：普通 AI 助手与 Agent 生成工具共用 */
    val chapterGenerationService: ChapterGenerationService = ChapterGenerationService(
        sendToModel = { modelId, request, onDelta ->
            configRepository.sendToModel(modelId, request, onDelta)
        }
    )
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

    /** F6：受控写入操作仓库、业务适配、确认等待器与执行服务 */
    val agentWriteOperationRepository: AgentWriteOperationRepository =
        AgentWriteOperationRepository(database)
    val agentWriteConfirmationGate: AgentWriteConfirmationGate = AgentWriteConfirmationGate()
    val agentWriteBusinessWriter: RoomAgentWriteBusinessWriter = RoomAgentWriteBusinessWriter(
        db = database,
        books = bookRepository,
        outlines = outlineRepository
    )
    val agentWriteExecutionService: AgentWriteExecutionService = AgentWriteExecutionService(
        operations = agentWriteOperationRepository,
        writer = agentWriteBusinessWriter,
        policyProvider = { prefs.agentWritePolicy.first() },
        transactionRunner = RoomAgentWriteTransactionRunner(database),
        outlineCategories = { bookId ->
            prefs.outlineCategories(bookId) ?: defaultOutlineKeys
        },
        outlineItemCount = { bookId, category ->
            outlineRepository.getOutline(bookId).count { it.category == category }
        },
        addOutlineCategory = { bookId, category ->
            val current = prefs.outlineCategories(bookId) ?: defaultOutlineKeys
            if (category !in current) {
                prefs.setOutlineCategories(bookId, current + category)
            }
        },
        removeOutlineCategory = { bookId, category ->
            val current = prefs.outlineCategories(bookId) ?: defaultOutlineKeys
            if (category in current) {
                prefs.setOutlineCategories(bookId, current - category)
            }
        },
        deleteOutlineCategory = { bookId, category ->
            outlineRepository.deleteOutlineByCategory(bookId, category)
            val current = prefs.outlineCategories(bookId) ?: defaultOutlineKeys
            if (category in current) {
                prefs.setOutlineCategories(bookId, current - category)
            }
        }
    )
    /** F7：写入中断恢复核对；只读业务数据，不自动确认或重放写入 */
    val agentWriteRecoveryService: AgentWriteRecoveryService = AgentWriteRecoveryService(
        operations = agentWriteOperationRepository,
        writer = agentWriteBusinessWriter,
        outlineCategories = { bookId ->
            prefs.outlineCategories(bookId) ?: defaultOutlineKeys
        }
    )

    val agentToolRegistry: AgentToolRegistry = AgentToolRegistry().apply {
        val readDao = database.agentReadDao()
        registerAll(
            AgentReadOnlyTools(
                bookOf = { bookRepository.getBook(it) },
                chapterCountOf = { readDao.validChapterCount(it) },
                chapterMetaOf = { readDao.chapterMeta(it) },
                contentSliceOf = { chapterId, offset, length ->
                    readDao.contentSlice(chapterId, offset, length)
                },
                searchHitsOf = { bookId, keyword, snippet, limit ->
                    readDao.searchChapterHits(bookId, keyword, snippet, limit)
                },
                outlineOf = { outlineRepository.getOutline(it) },
                volumesOf = { bookRepository.getVolumes(it) },
                volumeSummariesOf = { volumeSummaryRepository.activeListByBook(it) },
                chaptersOf = { bookRepository.getChapters(it) },
                memoryRetrieve = { bookId, query, topK ->
                    memoryRepository.retrieveScored(bookId, query, topK = topK)
                }
            ).create()
        )
        registerAll(
            AgentWriteTools(
                bookOf = { bookRepository.getBook(it) },
                chapterMetaOf = { chapterId -> agentWriteBusinessWriter.chapterMeta(chapterId) },
                chapterContentOf = { chapterId ->
                    agentWriteBusinessWriter.chapterContent(chapterId)
                },
                outlineOf = { outlineId -> agentWriteBusinessWriter.outline(outlineId) },
                volumeOf = { volumeId -> agentWriteBusinessWriter.volume(volumeId) },
                writeService = agentWriteExecutionService,
                outlineCategoriesOf = { bookId ->
                    prefs.outlineCategories(bookId) ?: defaultOutlineKeys
                },
                outlineItemCountOf = { bookId, category ->
                    outlineRepository.getOutline(bookId).count { it.category == category }
                }
            ).create()
        )
        registerAll(
            AgentInteractionTools(
                createClarification = { taskId, question, options ->
                    agentTaskRepository.createClarification(
                        taskId = taskId,
                        question = question,
                        options = options
                    )
                }
            ).create()
        )
        registerAll(
            AgentGenerationTools(
                bookOf = { bookRepository.getBook(it) },
                chapterOf = { chapterId -> bookRepository.getChapter(chapterId) },
                chaptersOf = { bookId -> bookRepository.getChapters(bookId) },
                outlineOf = { bookId -> outlineRepository.getOutline(bookId) },
                volumesOf = { bookId -> bookRepository.getVolumes(bookId) },
                volumeSummariesOf = { bookId -> volumeSummaryRepository.activeListByBook(bookId) },
                styleBlockOf = { bookId -> styleConstraintLoader.apply(bookId).block },
                memoryRetrieve = { bookId, query, topK ->
                    memoryRepository.retrieveScored(bookId, query, topK = topK)
                },
                modelIdOfTask = { taskId ->
                    val task = agentTaskRepository.getTask(taskId)
                    task?.modelId ?: task?.sessionId?.let { sessionId ->
                        agentSessionRepository.getSession(sessionId)?.modelId
                    }
                },
                // 正文生成可单独配置模型；未配置或模型已删除时回退到任务模型
                modelIdOfContent = { taskId ->
                    val configured = prefs.agentContentModelId.first()
                        .takeIf { it > 0 && configRepository.getModel(it) != null }
                    configured ?: run {
                        val task = agentTaskRepository.getTask(taskId)
                        task?.modelId ?: task?.sessionId?.let { sessionId ->
                            agentSessionRepository.getSession(sessionId)?.modelId
                        }
                    }
                },
                summaryWordRange = { prefs.genSummaryWordRange.first() },
                contentWordRange = { prefs.genContentWordRange.first() },
                generationService = chapterGenerationService
            ).create()
        )
    }
    val agentToolExecutor: AgentToolExecutor = AgentToolExecutor(
        registry = agentToolRegistry,
        validator = AgentToolValidator(),
        tools = agentToolExecutionRepository,
        tasks = agentTaskRepository,
        writeOperations = agentWriteOperationRepository
    )

    /**
     * F5：历史资料解析与上下文组装。
     * 解析器只依赖只读 lambda；组装器在执行引擎启动时冻结任务引用快照。
     */
    val agentReferenceResolver: AgentReferenceResolver = AgentReferenceResolver(
        chapterMetaOf = { chapterId -> agentReferenceSourceRepository.chapterMeta(chapterId) },
        chapterContentOf = { chapterId, offset, length ->
            agentReferenceSourceRepository.chapterContentSlice(chapterId, offset, length)
        },
        bookTitleOf = { bookId -> agentReferenceSourceRepository.bookTitle(bookId) },
        volumeTitleOf = { volumeId -> agentReferenceSourceRepository.volumeTitle(volumeId) },
        agentTaskOf = { taskId -> agentReferenceSourceRepository.agentTask(taskId) },
        agentSessionOf = { sessionId -> agentReferenceSourceRepository.agentSession(sessionId) },
        agentMessageOf = { messageId -> agentReferenceSourceRepository.agentMessage(messageId) },
        toolExecutionOf = { executionId ->
            agentReferenceSourceRepository.toolExecution(executionId)
        },
        finalAnswerOf = { taskId -> agentReferenceSourceRepository.finalAnswerOf(taskId) },
        planParser = agentPlanParser
    )
    val agentContextAssembler: AgentContextAssembler = AgentContextAssembler(
        resolver = agentReferenceResolver,
        freezeSnapshot = { taskId, sessionId ->
            agentTaskReferenceRepository.freezeSnapshot(taskId, sessionId)
        },
        recordInjectionResults = { taskId, results ->
            agentTaskReferenceRepository.recordInjectionResults(taskId, results)
        }
    )

    val agentExecutionEngine: AgentExecutionEngine = AgentExecutionEngine(
        tasks = agentTaskRepository,
        nodes = agentExecutionNodeRepository,
        toolExecutions = agentToolExecutionRepository,
        events = agentEventRepository,
        sessions = agentSessionRepository,
        modelClient = agentModelClient,
        registry = agentToolRegistry,
        toolExecutor = agentToolExecutor,
        planParser = agentPlanParser,
        planValidator = agentPlanValidator,
        contextAssembler = agentContextAssembler,
        writeOperations = agentWriteOperationRepository,
        writeService = agentWriteExecutionService,
        writeGate = agentWriteConfirmationGate,
        leases = agentExecutionLeaseRepository,
        modelRequests = agentModelRequestRepository,
        contextCompressor = agentContextCompressor
    )

    /** F7：后台执行生命周期编排与用户控制入口（使用 lazy 避免容器初始化顺序问题） */
    val agentTaskRunner: AgentTaskRunner by lazy {
        AgentTaskRunner(
            tasks = agentTaskRepository,
            nodes = agentExecutionNodeRepository,
            tools = agentToolExecutionRepository,
            events = agentEventRepository,
            sessions = agentSessionRepository,
            leases = agentExecutionLeaseRepository,
            modelRequests = agentModelRequestRepository,
            recoveryLogs = agentRecoveryLogRepository,
            writeOperations = agentWriteOperationRepository,
            writeService = agentWriteExecutionService,
            writeRecovery = agentWriteRecoveryService,
            engine = agentExecutionEngine,
            processStartedAt = processStartedAt
        )
    }

    val agentTaskController: AgentTaskController by lazy {
        AgentTaskController(
            tasks = agentTaskRepository,
            nodes = agentExecutionNodeRepository,
            events = agentEventRepository,
            writes = agentWriteOperationRepository,
            writeService = agentWriteExecutionService,
            leases = agentExecutionLeaseRepository,
            scheduler = agentTaskScheduler
        )
    }

    val agentTaskRecoveryCoordinator: AgentTaskRecoveryCoordinator by lazy {
        AgentTaskRecoveryCoordinator(
            tasks = agentTaskRepository,
            leases = agentExecutionLeaseRepository,
            writes = agentWriteOperationRepository,
            events = agentEventRepository,
            scheduler = agentTaskScheduler
        )
    }

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
        // F7：注册周期恢复扫描，并在启动时把租约过期的 RUNNING 任务重新入队
        container.appScope.launch {
            container.agentTaskScheduler.ensurePeriodicRecovery()
            container.agentTaskRecoveryCoordinator.reconcile(
                reason = "STARTUP",
                processStartedAt = container.processStartedAt
            )
        }
        // 首次启动时把内置《使用说明书》导入书架，之后可选中它向 AI 提问本 App 的用法
        container.appScope.launch {
            ManualSeeder.ensureSeeded(this@LianWenQiApplication, container.prefs)
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as LianWenQiApplication).container