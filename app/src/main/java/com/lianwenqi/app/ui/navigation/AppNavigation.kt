package com.lianwenqi.app.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lianwenqi.app.ui.screens.AiChatScreen
import com.lianwenqi.app.ui.screens.AiToolsScreen
import com.lianwenqi.app.ui.screens.BookDetailScreen
import com.lianwenqi.app.ui.screens.BookSearchScreen
import com.lianwenqi.app.ui.screens.BookshelfScreen
import com.lianwenqi.app.ui.screens.ChapterEditorScreen
import com.lianwenqi.app.ui.screens.CheckUpdateScreen
import com.lianwenqi.app.ui.screens.MemoryProgressScreen
import com.lianwenqi.app.ui.screens.MemorySettingsScreen
import com.lianwenqi.app.ui.screens.PrefixManageScreen
import com.lianwenqi.app.ui.screens.ReaderScreen
import com.lianwenqi.app.ui.screens.SettingsScreen

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
    const val UPDATE = "update"

    fun book(id: Long) = "book/$id"
    fun editor(bookId: Long, chapterId: Long) = "editor/$bookId/$chapterId"
    fun reader(bookId: Long, chapterId: Long) = "reader/$bookId/$chapterId"
    fun bookSearch(bookId: Long) = "booksearch/$bookId"
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

        composable(Routes.UPDATE) {
            CheckUpdateScreen(onBack = { rootNav.popBackStack() })
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

    // 输入法弹出时隐藏底部导航栏，避免它占位导致输入框与键盘之间出现大块空白
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        // 各 Tab 页自带 TopAppBar 处理状态栏 Insets，外层不再重复贡献顶部内边距
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!imeVisible) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    tabNav.navigate(tab.route) {
                                        popUpTo(tabNav.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
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
                    onOpenUpdate = { rootNav.navigate(Routes.UPDATE) }
                )
            }
        }
    }
}