package com.situ.aichat.ui.settings

import android.os.Looper
import com.situ.aichat.data.local.SettingsPreferences
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.data.model.AppearanceMode
import com.situ.aichat.data.model.GlassTier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * T2：外观设置 VM 的选脸 / 玻璃档两路（图纸 2026-09-04-琉璃第二张脸-卷一 §7 T2-1）。
 *
 * 钉两件：① 两路 StateFlow 从 DataStore 回显（值取反向组合防对称写错）；② 两个 setter 真的写到
 * [SettingsPreferences] 的对应 suspend 口（`coVerify`）——写错口 = 用户点了没反应，且屏上还会显示成功。
 * WhileSubscribed 需有订阅者才拉上游，故用 Robolectric 主循环驱动（同 [SettingsOverviewViewModelTest] 惯例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppearanceSettingsViewModelTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun buildVm(
        skin: AppSkin,
        tier: GlassTier,
        prefs: SettingsPreferences = mockk(relaxed = true),
    ): Pair<AppearanceSettingsViewModel, SettingsPreferences> {
        every { prefs.appSkin } returns flowOf(skin)
        every { prefs.glassTier } returns flowOf(tier)
        every { prefs.appearanceMode } returns flowOf(AppearanceMode.SYSTEM)
        every { prefs.useDynamicColor } returns flowOf(false)
        every { prefs.bottomNavOpacity } returns flowOf(0.88f)
        coEvery { prefs.setAppSkin(any()) } returns Unit
        coEvery { prefs.setGlassTier(any()) } returns Unit
        return AppearanceSettingsViewModel(prefs) to prefs
    }

    /** 订阅两路（WhileSubscribed 才开闸）并驱动主循环，读完 value 再退订。 */
    private fun <T> withSubscriptions(vm: AppearanceSettingsViewModel, block: () -> T): T {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val flows: List<StateFlow<*>> = listOf(vm.skin, vm.glassTier)
        flows.forEach { flow -> scope.launch { flow.collect {} } }
        idle()
        return try {
            block()
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun `选脸与玻璃档回显·琉璃 + 着色`() {
        val (vm, _) = buildVm(AppSkin.LIULI, GlassTier.TINTED)
        withSubscriptions(vm) {
            assertEquals(AppSkin.LIULI, vm.skin.value)
            assertEquals(GlassTier.TINTED, vm.glassTier.value)
        }
    }

    @Test
    fun `选脸与玻璃档回显·暖陶 + 清透（反向值防对称写错）`() {
        val (vm, _) = buildVm(AppSkin.CLAY, GlassTier.CLEAR)
        withSubscriptions(vm) {
            assertEquals(AppSkin.CLAY, vm.skin.value)
            assertEquals(GlassTier.CLEAR, vm.glassTier.value)
        }
    }

    @Test
    fun `setSkin 写 DataStore 的 setAppSkin`() {
        val (vm, prefs) = buildVm(AppSkin.CLAY, GlassTier.CLEAR)
        vm.setSkin(AppSkin.LIULI)
        idle()
        coVerify(exactly = 1) { prefs.setAppSkin(AppSkin.LIULI) }
    }

    @Test
    fun `setGlassTier 写 DataStore 的 setGlassTier`() {
        val (vm, prefs) = buildVm(AppSkin.LIULI, GlassTier.CLEAR)
        vm.setGlassTier(GlassTier.TINTED)
        idle()
        coVerify(exactly = 1) { prefs.setGlassTier(GlassTier.TINTED) }
    }
}
