package com.lianwenqi.app.ui.screens

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lianwenqi.app.ai.RemoteModelInfo
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.Provider
import com.lianwenqi.app.data.entity.ProviderKey
import com.lianwenqi.app.ui.theme.LianWenQiTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 只渲染无 ViewModel 的设置组件；数据和操作均来自内存，不访问用户数据库或真实 API。 */
@RunWith(AndroidJUnit4::class)
class ProviderSettingsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val provider = Provider(
        id = 501L,
        name = "演示供应商",
        baseUrl = "https://provider-settings.invalid/v1",
        createdAt = 0L
    )
    private val mainKey = ProviderKey(
        id = 511L,
        providerId = provider.id,
        name = "主密钥",
        apiKey = "sk-local-fixture-main-only",
        createdAt = 0L,
        updatedAt = 0L
    )
    private val backupKey = mainKey.copy(
        id = 512L,
        name = "备用密钥",
        apiKey = "sk-local-fixture-backup-only"
    )
    private val mainModel = AiModel(
        id = 521L,
        providerId = provider.id,
        providerKeyId = mainKey.id,
        name = "fixture-writer",
        alias = "写作模型",
        temperature = 0.8f
    )
    private val alternateModel = mainModel.copy(
        id = 522L,
        name = "fixture-reviewer",
        alias = "校对模型",
        temperature = 0.4f
    )
    private val backupModel = mainModel.copy(
        id = 523L,
        providerKeyId = backupKey.id,
        name = "fixture-backup",
        alias = "备用会话模型"
    )

    @Test
    fun headerTabsExposeSelectionAndDispatchTheirOwnActions() {
        var selectedTab by mutableStateOf(2)
        val selections = mutableListOf<Int>()
        var personalizationClicks = 0
        var updateClicks = 0
        composeRule.setContent {
            LianWenQiTheme(darkTheme = false) {
                SettingsPageHeader(
                    selectedTabIndex = selectedTab,
                    nightMode = false,
                    onSelectTab = { selections += it; selectedTab = it },
                    onOpenPersonalization = { personalizationClicks++ },
                    onOpenUpdate = { updateClicks++ }
                )
            }
        }
        val labels = listOf("模型与供应商", "提示词库", "通用")
        labels.forEachIndexed { index, label ->
            val tab = composeRule.onNodeWithText(label).assertIsDisplayed()
            if (index == 2) tab.assertIsSelected() else tab.assertIsNotSelected()
        }
        labels.forEachIndexed { selectedIndex, label ->
            composeRule.onNodeWithText(label).performClick()
            labels.forEachIndexed { index, otherLabel ->
                val tab = composeRule.onNodeWithText(otherLabel)
                if (index == selectedIndex) tab.assertIsSelected() else tab.assertIsNotSelected()
            }
        }
        composeRule.onNodeWithContentDescription("个性化设置").performClick()
        composeRule.onNodeWithContentDescription("检查更新").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(0, 1, 2), selections)
            assertEquals(2, selectedTab)
            assertEquals(1, personalizationClicks)
            assertEquals(1, updateClicks)
        }
    }

    @Test
    fun providerExpansionUsesHoistedStateAndEditingDoesNotCollapseIt() {
        val fixture = populatedFixture(expanded = false)
        showFixture(fixture)
        composeRule.onNodeWithText(mainKey.name).assertDoesNotExist()
        composeRule.onNodeWithText(provider.name).performClick()
        composeRule.onNodeWithText(mainKey.name).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("编辑供应商").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(provider.id), fixture.editedProviders)
            assertEquals(listOf(provider.id to true), fixture.expansionCalls)
            assertEquals(setOf(provider.id), fixture.expandedProviderIds)
            fixture.visible = false
        }
        composeRule.onNodeWithText(mainKey.name).assertDoesNotExist()
        // 模拟离开后重新挂载组件，展开状态仍由调用方传回，而非读取真实偏好设置。
        composeRule.runOnIdle { fixture.visible = true }
        composeRule.onNodeWithText(mainKey.name).assertIsDisplayed()
        composeRule.onNodeWithText(provider.name).performClick()
        composeRule.onNodeWithText(mainKey.name).assertDoesNotExist()
        composeRule.onNodeWithText(provider.name).performClick()
        composeRule.onNodeWithText(mainKey.name).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(
                listOf(provider.id to true, provider.id to false, provider.id to true),
                fixture.expansionCalls
            )
            assertEquals(setOf(provider.id), fixture.expandedProviderIds)
        }
    }

    @Test
    fun keyToggleAndModelUseAndEditDispatchWithoutBubblingToOtherActions() {
        val fixture = populatedFixture()
        showFixture(fixture)
        val toggle = composeRule.onNodeWithContentDescription("启用密钥 主密钥")
        toggle.performScrollTo().assertIsOn().performClick()
        toggle.assertIsOff().performClick()
        toggle.assertIsOn()
        composeRule.onNodeWithContentDescription("编辑密钥").performClick()
        composeRule.onNode(
            hasText("使用") and hasAnyAncestor(hasText(alternateModel.displayName))
        ).performScrollTo().performClick()
        composeRule.onNode(
            hasText(alternateModel.displayName) and hasText("使用中")
        ).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(alternateModel.id, fixture.activeModelId)
            assertEquals(listOf(alternateModel.id), fixture.selectedModels)
            assertTrue("使用按钮不能同时触发模型编辑", fixture.editedModels.isEmpty())
        }
        composeRule.onNodeWithText(alternateModel.displayName).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(mainKey.id to false, mainKey.id to true), fixture.keyToggleCalls)
            assertEquals(listOf(mainKey.id), fixture.editedKeys)
            assertEquals(listOf(alternateModel.id), fixture.editedModels)
            assertEquals(listOf(alternateModel.id), fixture.selectedModels)
            assertTrue("密钥和模型操作不能切换供应商展开状态", fixture.expansionCalls.isEmpty())
        }
    }

    @Test
    fun modelKeyAndProviderDeleteOnlyAfterConfirmationAndCanBeCancelled() {
        val fixture = populatedFixture()
        showFixture(fixture)
        val targets = listOf(
            DeleteCase("删除模型", "模型", mainModel.displayName, "model:${mainModel.id}"),
            DeleteCase("删除密钥", "API 密钥", mainKey.name, "key:${mainKey.id}"),
            DeleteCase("删除供应商", "供应商", provider.name, "provider:${provider.id}")
        )
        var expectedDeletes = emptyList<String>()
        for (target in targets) {
            // 模型列表里有两行；限制到主模型所在的那一行以免选中另一个删除入口。
            val deleteButton = if (target.type == "模型") {
                composeRule.onNode(
                    hasContentDescription(target.description) and
                        hasAnyAncestor(hasText(mainModel.displayName))
                )
            } else {
                composeRule.onNodeWithContentDescription(target.description)
            }
            deleteButton.performScrollTo().performClick()
            composeRule.onNodeWithText("删除${target.type}", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithText("确定删除 ${target.name} 吗？", useUnmergedTree = true)
                .assertIsDisplayed()
            composeRule.runOnIdle { assertEquals(expectedDeletes, fixture.deleteCalls) }
            composeRule.onNodeWithText("取消").performClick()
            composeRule.onNodeWithText("删除${target.type}", useUnmergedTree = true).assertDoesNotExist()
            composeRule.runOnIdle { assertEquals(expectedDeletes, fixture.deleteCalls) }
            deleteButton.performClick()
            composeRule.runOnIdle { assertEquals(expectedDeletes, fixture.deleteCalls) }
            composeRule.onNodeWithText("删除").performClick()
            expectedDeletes = expectedDeletes + target.record
            composeRule.onNodeWithText("删除${target.type}", useUnmergedTree = true).assertDoesNotExist()
            composeRule.runOnIdle { assertEquals(expectedDeletes, fixture.deleteCalls) }
        }
    }

    @Test
    fun readAndChatTestsUseTheirOwnKeyEvenWhenAnotherKeyOwnsTheActiveModel() {
        val fixture = populatedFixture(includeBackup = true)
        fixture.activeModelId = backupModel.id
        showFixture(fixture)
        composeRule.onAllNodesWithText("测试读取").assertCountEquals(2)
        composeRule.onAllNodesWithText("测试会话").assertCountEquals(2)
        listOf(mainKey, backupKey).forEachIndexed { index, key ->
            composeRule.onAllNodesWithText("测试读取")[index].performScrollTo().performClick()
            composeRule.onAllNodesWithText("测试会话")[index].performScrollTo().performClick()
            composeRule.onNodeWithText("本地会话成功：${key.name}")
                .performScrollTo().assertIsDisplayed()
        }
        composeRule.runOnIdle {
            assertEquals(
                listOf(provider.id to mainKey.id, provider.id to backupKey.id),
                fixture.readCalls
            )
            assertEquals(
                listOf(
                    Triple(provider.id, mainKey.id, mainModel.id),
                    Triple(provider.id, backupKey.id, backupModel.id)
                ),
                fixture.chatCalls
            )
            assertEquals(backupModel.id, fixture.activeModelId)
            assertTrue(fixture.editedModels.isEmpty())
            assertTrue(fixture.deleteCalls.isEmpty())
        }
    }

    @Test
    fun emptyStateCanAddProviderAndSaveLightDarkAndNarrowReviewScreenshots() {
        val fixture = Fixture(emptyList(), emptyList(), emptyList(), 0L, emptySet())
        var variant by mutableStateOf(ReviewVariant())
        showFixture(fixture) { variant }
        composeRule.onNodeWithText("还没有模型供应商").assertIsDisplayed()
        composeRule.onNodeWithText("添加供应商").performClick()
        composeRule.runOnIdle {
            assertEquals(1, fixture.addProviderCalls)
            // 只模拟上层收到添加结果后的数据变化，不打开真实供应商表单或写入数据库。
            fixture.providers = listOf(provider)
            fixture.providerKeys = listOf(mainKey)
            fixture.models = listOf(mainModel, alternateModel)
            fixture.activeModelId = mainModel.id
            fixture.expandedProviderIds = setOf(provider.id)
        }
        composeRule.onNodeWithText("还没有模型供应商").assertDoesNotExist()
        composeRule.onNodeWithText(mainKey.name).assertIsDisplayed()
        saveScreenshot("light-expanded")
        composeRule.runOnIdle { variant = ReviewVariant(dark = true) }
        saveScreenshot("dark-expanded")
        composeRule.runOnIdle { variant = ReviewVariant(width = 320.dp, fontScale = 1.3f) }
        composeRule.onNodeWithTag(FIXTURE_TAG).assertWidthIsEqualTo(320.dp)
        saveScreenshot("narrow-320dp-font130")
        composeRule.onNodeWithText("添加供应商").assertIsDisplayed()
        composeRule.onNodeWithText("测试会话").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("测试读取").assertIsDisplayed()
    }

    private fun populatedFixture(expanded: Boolean = true, includeBackup: Boolean = false): Fixture =
        Fixture(
            providers = listOf(provider),
            providerKeys = if (includeBackup) listOf(mainKey, backupKey) else listOf(mainKey),
            models = listOf(mainModel, alternateModel) + if (includeBackup) listOf(backupModel) else emptyList(),
            activeModelId = mainModel.id,
            expandedProviderIds = if (expanded) setOf(provider.id) else emptySet()
        )

    private fun showFixture(fixture: Fixture, variant: () -> ReviewVariant = { ReviewVariant() }) {
        composeRule.setContent { FixturePage(fixture, variant()) }
    }

    @Composable
    private fun FixturePage(fixture: Fixture, variant: ReviewVariant) {
        val deviceDensity = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(deviceDensity.density, variant.fontScale)) {
            LianWenQiTheme(darkTheme = variant.dark) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.width(variant.width).fillMaxHeight()
                            .background(settingsPageBackground(variant.dark)).testTag(FIXTURE_TAG)
                    ) {
                        SettingsPageHeader(
                            selectedTabIndex = 0,
                            nightMode = variant.dark,
                            onSelectTab = {},
                            onOpenPersonalization = {},
                            onOpenUpdate = {}
                        )
                        if (fixture.visible) {
                            Box(Modifier.weight(1f)) {
                                ProvidersTab(
                                    providers = fixture.providers,
                                    providerKeys = fixture.providerKeys,
                                    models = fixture.models,
                                    activeModelId = fixture.activeModelId,
                                    expandedProviderIds = fixture.expandedProviderIds,
                                    onToggleProvider = { id, expanded ->
                                        fixture.expansionCalls += id to expanded
                                        fixture.expandedProviderIds = if (expanded) {
                                            fixture.expandedProviderIds + id
                                        } else {
                                            fixture.expandedProviderIds - id
                                        }
                                    },
                                    onAddProvider = { fixture.addProviderCalls++ },
                                    onEditProvider = { fixture.editedProviders += it.id },
                                    onDeleteProvider = { fixture.deleteCalls += "provider:${it.id}" },
                                    onAddKey = {},
                                    onEditKey = { fixture.editedKeys += it.id },
                                    onDeleteKey = { fixture.deleteCalls += "key:${it.id}" },
                                    onToggleKey = { key, enabled ->
                                        fixture.keyToggleCalls += key.id to enabled
                                        fixture.providerKeys = fixture.providerKeys.map {
                                            if (it.id == key.id) it.copy(enabled = enabled) else it
                                        }
                                    },
                                    onAddModel = { _, _ -> },
                                    onEditModel = { fixture.editedModels += it.id },
                                    onDeleteModel = { fixture.deleteCalls += "model:${it.id}" },
                                    onSetActive = {
                                        fixture.selectedModels += it.id
                                        fixture.activeModelId = it.id
                                    },
                                    onFetchModels = { owner, key, complete ->
                                        fixture.readCalls += owner.id to key.id
                                        complete(listOf(RemoteModelInfo("local-fixture-model")), null)
                                    },
                                    onTestChat = { owner, key, model, complete ->
                                        fixture.chatCalls += Triple(owner.id, key.id, model?.id)
                                        complete(true, "本地会话成功：${key.name}")
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun saveScreenshot(name: String) {
        composeRule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = requireNotNull(context.getExternalFilesDir(null))
        check(directory.isDirectory || directory.mkdirs()) { "无法创建截图目录：$directory" }
        val output = File(directory, "provider-settings-$name.png")
        val bitmap = composeRule.onNodeWithTag(FIXTURE_TAG).captureToImage().asAndroidBitmap()
        output.outputStream().use { stream ->
            assertTrue("PNG 保存失败：$output", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        assertTrue("截图为空：$output", output.length() > 0L)
        Log.i("ProviderSettingsUiTest", "Saved ${output.absolutePath}")
    }

    private data class ReviewVariant(
        val dark: Boolean = false,
        val width: Dp = 400.dp,
        val fontScale: Float = 1f
    )

    private data class DeleteCase(val description: String, val type: String, val name: String, val record: String)

    private class Fixture(
        providers: List<Provider>,
        providerKeys: List<ProviderKey>,
        models: List<AiModel>,
        activeModelId: Long,
        expandedProviderIds: Set<Long>
    ) {
        var providers by mutableStateOf(providers)
        var providerKeys by mutableStateOf(providerKeys)
        var models by mutableStateOf(models)
        var activeModelId by mutableStateOf(activeModelId)
        var expandedProviderIds by mutableStateOf(expandedProviderIds)
        var visible by mutableStateOf(true)
        var addProviderCalls = 0
        val expansionCalls = mutableListOf<Pair<Long, Boolean>>()
        val editedProviders = mutableListOf<Long>()
        val keyToggleCalls = mutableListOf<Pair<Long, Boolean>>()
        val editedKeys = mutableListOf<Long>()
        val selectedModels = mutableListOf<Long>()
        val editedModels = mutableListOf<Long>()
        val deleteCalls = mutableListOf<String>()
        val readCalls = mutableListOf<Pair<Long, Long>>()
        val chatCalls = mutableListOf<Triple<Long, Long, Long?>>()
    }

    private companion object {
        const val FIXTURE_TAG = "provider-settings-fixture"
    }
}
