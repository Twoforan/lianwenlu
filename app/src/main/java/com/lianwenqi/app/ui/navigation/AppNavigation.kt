package com.lianwenqi.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lianwenqi.app.appContainer
import com.lianwenqi.app.ui.screens.AiChatScreen
import com.lianwenqi.app.ui.screens.AiToolsScreen
import com.lianwenqi.app.ui.screens.BookDetailScreen
import com.lianwenqi.app.ui.screens.BookSearchScreen
import com.lianwenqi.app.ui.screens.BookshelfScreen
import com.lianwenqi.app.ui.screens.ChapterEditorScreen
import com.lianwenqi.app.ui.screens.CheckUpdateScreen
import com.lianwenqi.app.ui.screens.ManualGuideScreen
import com.lianwenqi.app.ui.screens.MemoryProgressScreen
import com.lianwenqi.app.ui.screens.MemorySettingsScreen
import com.lianwenqi.app.ui.screens.PrefixManageScreen
import com.lianwenqi.app.ui.screens.ReaderScreen
import com.lianwenqi.app.ui.screens.SettingsScreen
import com.lianwenqi.app.ui.screens.StyleNewProfileScreen
import com.lianwenqi.app.ui.screens.StyleProfileDetailScreen
import com.lianwenqi.app.ui.screens.StyleProfileStyleScreen
import com.lianwenqi.app.ui.screens.StyleSampleManageScreen
import com.lianwenqi.app.ui.screens.StyleSampleStyleScreen
import com.lianwenqi.app.ui.screens.StyleSettingsScreen
import com.lianwenqi.app.ui.screens.StyleTagLibraryScreen
import com.lianwenqi.app.ui.screens.VolumeCompressionSettingsScreen

object Routes {
    const val SHELL = "shell"
    const val BOOK = "book/{bookId}"
    const val EDITOR = "editor/{bookId}/{chapterId}"
    const val READER = "reader/{bookId}/{chapterId}"
    const val BOOK_SEARCH = "booksearch/{bookId}"
    const val AI_TOOLS = "aitools"
    const val PREFIX_MANAGE = "prefix"
    const val MEMORY = "memory"
    const val MEMORY_PROGRESS = "memoryprogress"
    const val STYLE = "style"
    /** 新建文风档案：样本导入与文风分析独立页 */
    const val STYLE_NEW = "stylenew"
    const val STYLE_DETAIL = "style/{profileId}"
    /** 全局用户标签库 */
    const val STYLE_TAGS = "styletags"
    /** 文风档案专属标签库 */
    const val STYLE_PROFILE_TAGS = "styletags/{profileId}"
    /** 文风档案下的样本管理 */
    const val STYLE_SAMPLES = "stylesamples/{profileId}"
    /** 进入样本管理并直接打开导入弹窗 */
    const val STYLE_SAMPLES_IMPORT = "stylesamples/{profileId}/import"
    /** 文风档案样式详情 */
    const val STYLE_PROFILE_STYLE = "stylearchive/{profileId}"
    /** 文风样本样式详情 */
    const val STYLE_SAMPLE_STYLE = "stylesample/{sampleId}"
    const val UPDATE = "update"
    const val MANUAL = "manual"
    /** 分卷压缩全局默认设置 */
    const val COMPRESSION_SETTINGS = "compression"

    fun book(id: Long) = "book/$id"
    fun editor(bookId: Long, chapterId: Long) = "editor/$bookId/$chapterId"
    fun reader(bookId: Long, chapterId: Long) = "reader/$bookId/$chapterId"
    fun bookSearch(bookId: Long) = "booksearch/$bookId"
    fun styleProfile(profileId: Long) = "style/$profileId"
    fun styleProfileTags(profileId: Long) = "styletags/$profileId"
    fun styleSamples(profileId: Long) = "stylesamples/$profileId"
    fun styleSamplesImport(profileId: Long) = "stylesamples/$profileId/import"
    fun styleProfileStyle(profileId: Long) = "stylearchive/$profileId"
    fun styleSampleStyle(sampleId: Long) = "stylesample/$sampleId"
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("bookshelf", "书架", Icons.AutoMirrored.Filled.MenuBook),
    TabItem("ai", "AI 聊天", Icons.AutoMirrored.Filled.Chat),
    TabItem("settings", "设置", Icons.Filled.Settings)
)

@Composable
fun RootNavHost() {
    val rootNav = rememberNavController()
    NavHost(navController = rootNav, startDestination = Routes.SHELL) {
        composable(Routes.SHELL) { MainShell(rootNav) }

        composable(Routes.AI_TOOLS) {
            AiToolsScreen(onBack = { rootNav.popBackStack() })
        }

        composable(Routes.PREFIX_MANAGE) {
            PrefixManageScreen(onBack = { rootNav.popBackStack() })
        }

        composable(Routes.MEMORY) {
            MemorySettingsScreen(
                onBack = { rootNav.popBackStack() },
                onOpenProgress = { rootNav.navigate(Routes.MEMORY_PROGRESS) }
            )
        }

        composable(Routes.MEMORY_PROGRESS) {
            MemoryProgressScreen(onBack = { rootNav.popBackStack() })
        }

        composable(Routes.STYLE) {
            StyleSettingsScreen(
                onBack = { rootNav.popBackStack() },
                onCreateProfile = { rootNav.navigate(Routes.STYLE_NEW) },
                onOpenProfile = { profileId -> rootNav.navigate(Routes.styleProfile(profileId)) },
                onOpenProfileStyle = { profileId ->
                    rootNav.navigate(Routes.styleProfileStyle(profileId))
                },
                onOpenSampleStyle = { sampleId ->
                    rootNav.navigate(Routes.styleSampleStyle(sampleId))
                },
                onOpenGlobalTags = { rootNav.navigate(Routes.STYLE_TAGS) },
                onOpenSamples = { profileId -> rootNav.navigate(Routes.styleSamples(profileId)) }
            )
        }

        composable(Routes.STYLE_NEW) {
            StyleNewProfileScreen(
                onBack = { rootNav.popBackStack() },
                onDone = { rootNav.popBackStack() }
            )
        }

        composable(
            Routes.STYLE_PROFILE_STYLE,
            arguments = listOf(navArgument("profileId") { type = NavType.LongType })
        ) { entry ->
            StyleProfileStyleScreen(
                profileId = entry.arguments?.getLong("profileId") ?: 0L,
                onBack = { rootNav.popBackStack() },
                onOpenTags = { profileId -> rootNav.navigate(Routes.styleProfileTags(profileId)) }
            )
        }

        composable(
            Routes.STYLE_SAMPLE_STYLE,
            arguments = listOf(navArgument("sampleId") { type = NavType.LongType })
        ) { entry ->
            StyleSampleStyleScreen(
                sampleId = entry.arguments?.getLong("sampleId") ?: 0L,
                onBack = { rootNav.popBackStack() },
                onOpenSamples = { profileId -> rootNav.navigate(Routes.styleSamples(profileId)) }
            )
        }

        composable(
            Routes.STYLE_DETAIL,
            arguments = listOf(navArgument("profileId") { type = NavType.LongType })
        ) { entry ->
            StyleProfileDetailScreen(
                profileId = entry.arguments?.getLong("profileId") ?: 0L,
                onBack = { rootNav.popBackStack() },
                onOpenTags = { profileId -> rootNav.navigate(Routes.styleProfileTags(profileId)) },
                onOpenSamples = { profileId -> rootNav.navigate(Routes.styleSamples(profileId)) }
            )
        }

        composable(Routes.STYLE_TAGS) {
            StyleTagLibraryScreen(
                scope = 0L,
                title = "全局标签库",
                subtitle = "全局用户标签库：只有你能新增、修改和删除；AI 只会读取和使用这里的标签。",
                onBack = { rootNav.popBackStack() }
            )
        }

        composable(
            Routes.STYLE_PROFILE_TAGS,
            arguments = listOf(navArgument("profileId") { type = NavType.LongType })
        ) { entry ->
            val profileId = entry.arguments?.getLong("profileId") ?: 0L
            StyleTagLibraryScreen(
                scope = profileId,
                title = "档案标签库",
                subtitle = "当前文风档案专属标签库：与其它档案隔离；AI 分析样本时只能新增标签，不会修改或删除已有标签。",
                onBack = { rootNav.popBackStack() }
            )
        }

        composable(
            Routes.STYLE_SAMPLES,
            arguments = listOf(navArgument("profileId") { type = NavType.LongType })
        ) { entry ->
            StyleSampleManageScreen(
                profileId = entry.arguments?.getLong("profileId") ?: 0L,
                onBack = { rootNav.popBackStack() }
            )
        }

        composable(
            Routes.STYLE_SAMPLES_IMPORT,
            arguments = listOf(navArgument("profileId") { type = NavType.LongType })
        ) { entry ->
            StyleSampleManageScreen(
                profileId = entry.arguments?.getLong("profileId") ?: 0L,
                onBack = { rootNav.popBackStack() },
                autoOpenImport = true
            )
        }

        composable(Routes.UPDATE) {
            CheckUpdateScreen(onBack = { rootNav.popBackStack() })
        }

        composable(Routes.MANUAL) {
            ManualGuideScreen(onBack = { rootNav.popBackStack() })
        }

        composable(Routes.COMPRESSION_SETTINGS) {
            VolumeCompressionSettingsScreen(onBack = { rootNav.popBackStack() })
        }

        composable(
            Routes.BOOK,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) { entry ->
            val bookId = entry.arguments?.getLong("bookId") ?: 0L
            BookDetailScreen(
                bookId = bookId,
                onBack = { rootNav.popBackStack() },
                onOpenChapter = { chapterId -> rootNav.navigate(Routes.editor(bookId, chapterId)) }
            )
        }

        composable(
            Routes.EDITOR,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("chapterId") { type = NavType.LongType }
            )
        ) { entry ->
            ChapterEditorScreen(
                bookId = entry.arguments?.getLong("bookId") ?: 0L,
                chapterId = entry.arguments?.getLong("chapterId") ?: 0L,
                onBack = { rootNav.popBackStack() },
                onPreview = { bId, cId -> rootNav.navigate(Routes.reader(bId, cId)) },
                onOpenBookSearch = { bId -> rootNav.navigate(Routes.bookSearch(bId)) }
            )
        }

        composable(
            Routes.BOOK_SEARCH,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) { entry ->
            val bookId = entry.arguments?.getLong("bookId") ?: 0L
            BookSearchScreen(
                bookId = bookId,
                onBack = { rootNav.popBackStack() },
                onOpenChapter = { chapterId -> rootNav.navigate(Routes.editor(bookId, chapterId)) }
            )
        }

        composable(
            Routes.READER,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("chapterId") { type = NavType.LongType }
            )
        ) { entry ->
            ReaderScreen(
                bookId = entry.arguments?.getLong("bookId") ?: 0L,
                chapterId = entry.arguments?.getLong("chapterId") ?: 0L,
                onBack = { rootNav.popBackStack() }
            )
        }
    }
}

@Composable
private fun MainShell(rootNav: NavHostController) {
    val tabNav = rememberNavController()
    val backStackEntry by tabNav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val context = LocalContext.current
    val night by context.appContainer.prefs.nightMode.collectAsState(initial = false)

    // 输入法弹出时隐藏底部导航栏，避免它占位导致输入框与键盘之间出现大块空白
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        // 各 Tab 页自带 TopAppBar 处理状态栏 Insets，外层不再重复贡献顶部内边距
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!imeVisible) {
                ShelfBottomBar(currentRoute = currentRoute, night = night) { route ->
                    if (currentRoute != route) {
                        tabNav.navigate(route) {
                            popUpTo(tabNav.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = tabNav,
            startDestination = "bookshelf",
            modifier = Modifier.padding(padding)
        ) {
            composable("bookshelf") {
                BookshelfScreen(
                    onOpenBook = { rootNav.navigate(Routes.book(it)) },
                    onPreviewBook = { bId, cId -> rootNav.navigate(Routes.reader(bId, cId)) },
                    onOpenAi = {
                        tabNav.navigate("ai") {
                            popUpTo(tabNav.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable("ai") { AiChatScreen(initialBookId = null) }
            composable("settings") {
                SettingsScreen(
                    onOpenAiTools = { rootNav.navigate(Routes.AI_TOOLS) },
                    onOpenPrefix = { rootNav.navigate(Routes.PREFIX_MANAGE) },
                    onOpenMemory = { rootNav.navigate(Routes.MEMORY) },
                    onOpenStyle = { rootNav.navigate(Routes.STYLE) },
                    onOpenManual = { rootNav.navigate(Routes.MANUAL) },
                    onOpenUpdate = { rootNav.navigate(Routes.UPDATE) },
                    onOpenCompression = { rootNav.navigate(Routes.COMPRESSION_SETTINGS) }
                )
            }
        }
    }
}

/**
 * 底部导航：按参考稿实现 75dp 高、淡紫底、选中项胶囊高亮、半粗标签。
 * 夜间模式使用对应的深色底与文字色，避免浅色底刺眼。
 */
@Composable
private fun ShelfBottomBar(
    currentRoute: String?,
    night: Boolean,
    onSelect: (String) -> Unit
) {
    val background = if (night) Color(0xFF211F2A) else Color(0xFFF5EFF8)
    val selectedPill = if (night) Color(0xFF352F48) else Color(0xFFE9E1F6)
    val selectedText = if (night) Color(0xFFE9E5F2) else Color(0xFF21182B)
    val normalText = if (night) Color(0xFFB7B2C1) else Color(0xFF4C4852)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(75.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabs.forEach { tab ->
            val selected = currentRoute == tab.route
            Column(
                modifier = Modifier
                    .width(90.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onSelect(tab.route) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (selected) selectedPill else Color.Transparent)
                        .padding(
                            horizontal = if (selected) 24.dp else 12.dp,
                            vertical = 3.dp
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (selected) selectedText else normalText,
                        modifier = Modifier.size(23.dp)
                    )
                }
                Text(
                    tab.label,
                    color = if (selected) selectedText else normalText,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}