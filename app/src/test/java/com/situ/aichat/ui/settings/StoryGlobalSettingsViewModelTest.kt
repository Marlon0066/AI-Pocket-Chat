package com.situ.aichat.ui.settings

import android.os.Looper
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.repository.SettingsRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 全局创作子屏 VM 的 T2（卷四 §7 T2-2）：温度 setter 直通仓库（E8·段序 setter 随 2026-08-03
 * 「B 序固化」整链拆除，该维度用例一并删除）、思考模型提示行的条件（E11）、`appSettings` 流回显三态原值。
 *
 * 期望从图纸 §3.2 独立反推：**UI 不重复实现任何钳位/判空**——越界温度也原样交给仓库（钳位是仓库的活），
 * 三个全局文本原值原样回显（null / "" / 文本互不折叠）。
 * MockK 假仓库；viewModelScope 由 Robolectric 主循环驱动（照 [WorldSettingsViewModelTest] 与
 * `StorySettingsViewModelTest` 先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryGlobalSettingsViewModelTest {

    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val functionRouter = mockk<ApiFunctionRouter>()
    private val apiConfigs = mockk<ApiConfigRepository>()

    private val scope = CoroutineScope(Dispatchers.Main)
    private val jobs = mutableListOf<Job>()

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** 三条上游流「确已被消费」的证据（防「初始态本就满足条件」的假绿·PITFALLS §1e）。 */
    private val consumedUpstream = mutableSetOf<String>()

    private fun config(uuid: String, thinkingMode: String) = ApiConfigEntity(
        uuid = uuid,
        providerName = "p",
        apiKeyId = "k",
        baseURL = "https://example.com",
        modelName = "m",
        creationDate = 0L,
        thinkingModelModeRaw = thinkingMode,
        detectedThinkingModelType = -1,
    )

    private fun vm(
        appSettings: AppSettings = AppSettings(),
        assignments: Map<ApiFunction, String> = emptyMap(),
        configs: List<ApiConfigEntity> = emptyList(),
        active: ApiConfigEntity? = null,
    ): StoryGlobalSettingsViewModel {
        every { settingsRepository.appSettings } returns flowOf(appSettings)
        every { functionRouter.assignments } returns flow { emit(assignments); consumedUpstream += "assignments" }
        every { apiConfigs.observeAll() } returns flow { emit(configs); consumedUpstream += "all" }
        every { apiConfigs.observeActive() } returns flow { emit(active); consumedUpstream += "active" }
        return StoryGlobalSettingsViewModel(settingsRepository, functionRouter, apiConfigs)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(message: String, condition: () -> Boolean) {
        repeat(200) {
            idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    /** 订阅 `WhileSubscribed` 冷流（模拟屏幕的 collectAsStateWithLifecycle），等上游真跑过再读值。 */
    private fun StoryGlobalSettingsViewModel.thinkingFlagAfterUpstreamRan(): Boolean {
        jobs += scope.launch { storyModelIsThinking.collect {} }
        await("三条上游流被消费") { consumedUpstream.size == 3 }
        repeat(20) { idle() }
        return storyModelIsThinking.value
    }

    /** 订阅 settings 冷流后读快照（同上：无订阅者时 stateIn 停在初始值）。 */
    private fun StoryGlobalSettingsViewModel.settingsAfterUpstreamRan(): AppSettings {
        jobs += scope.launch { settings.collect {} }
        repeat(20) { idle() }
        return settings.value
    }

    // ── E8：温度 setter 直通仓库（钳位/语义都不在 UI 侧重复实现）──

    @Test fun e8_温度_原样交给仓库_UI侧不自己钳位() {
        val vm = vm()
        vm.setTemperature(1.3)
        await("温度写入") { runCatching { coVerify { settingsRepository.setStoryCreationTemperature(1.3) } }.isSuccess }
        coVerify(exactly = 1) { settingsRepository.setStoryCreationTemperature(1.3) }

        // 越界值也原样透传——钳位是 SettingsRepository 的职责，UI 重复实现就成了两处真理源
        vm.setTemperature(7.5)
        await("越界温度写入") { runCatching { coVerify { settingsRepository.setStoryCreationTemperature(7.5) } }.isSuccess }
        coVerify(exactly = 1) { settingsRepository.setStoryCreationTemperature(7.5) }
    }

    // ── appSettings 回显：三个全局文本的三态原值不许被折叠 ──

    @Test fun 回显_三个全局文本三态原样透出_温度照读() {
        val vm = vm(
            AppSettings(
                storyCreationTemperature = 1.4,
                storyBannedExpressions = "",      // 用户主动清空
                storySceneBeats = "我的节拍",      // 自定义
                storyTasteProfile = null,          // 从未设置
            ),
        )
        val s = vm.settingsAfterUpstreamRan()
        assertEquals(1.4, s.sanitizedStoryCreationTemperature, 0.0001)
        assertEquals("空串必须原样透出（≠ null），否则「已关闭」这一态在屏上就没了", "", s.storyBannedExpressions)
        assertEquals("我的节拍", s.storySceneBeats)
        assertEquals(null, s.storyTasteProfile)
    }

    // ── E11：思考模型提示行的条件（与书页暂驻组同一条谓词链）──

    @Test fun e11_故事创作分配到思考模型_提示行出() {
        val thinking = config("t", thinkingMode = "thinking")
        val normal = config("n", thinkingMode = "standard")
        val vm = vm(
            assignments = mapOf(ApiFunction.STORY_CREATION to "t"),
            configs = listOf(thinking, normal),
            active = normal,
        )
        assertTrue(vm.thinkingFlagAfterUpstreamRan())
    }

    @Test fun e11_无任何配置_兜底不提示() {
        assertFalse(vm().thinkingFlagAfterUpstreamRan())
    }

    @Test fun e11_只看故事创作的分配_不被聊天分配带偏() {
        // 反例：聊天分配的与默认配置都是思考模型——只有真读 STORY_CREATION 才会得到 false
        val thinking = config("t", thinkingMode = "thinking")
        val normal = config("n", thinkingMode = "standard")
        val vm = vm(
            assignments = mapOf(ApiFunction.CHAT to "t", ApiFunction.STORY_CREATION to "n"),
            configs = listOf(thinking, normal),
            active = thinking,
        )
        assertFalse(vm.thinkingFlagAfterUpstreamRan())
    }

    @Test fun e11_故事创作未分配_回退默认配置() {
        val thinking = config("t", thinkingMode = "thinking")
        val vm = vm(assignments = emptyMap(), configs = listOf(thinking), active = thinking)
        assertTrue(vm.thinkingFlagAfterUpstreamRan())
    }

    @Test fun b6_回到出厂默认_清空三个全局文本字段() {
        val vm = vm()
        vm.resetToDefaults()
        await("三个全局文本清空") {
            runCatching {
                coVerify(exactly = 1) { settingsRepository.setStoryBannedExpressions(null) }
                coVerify(exactly = 1) { settingsRepository.setStorySceneBeats(null) }
                coVerify(exactly = 1) { settingsRepository.setStoryTasteProfile(null) }
            }.isSuccess
        }
    }
}
