package com.situ.aichat.ui.backup

import android.os.Looper
import com.situ.aichat.data.backup.BackupByteSource
import com.situ.aichat.data.backup.BackupPreview
import com.situ.aichat.data.backup.BackupService
import com.situ.aichat.data.backup.CharacterPreviewRow
import com.situ.aichat.data.backup.ImportResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * T2-4（性能专项卷 A 图纸 §7）：[BackupViewModel] 换成「可重开的字节源」之后的跨屏行为。
 *
 * 断言从图纸 §3.1/§5 E11-E12 独立反推：
 * - 预览段只驻留**源**（不再是整包字节）：确认导入时交给 service 的是同一个源，且它还能再开出一条流；
 * - 源打不开（文件被移走/授权失效）→ [BackupViewModel.readFailed] 置位、忙态复位、**不崩、不进导入**（E11）；
 * - 关掉预览后确认导入不再生效（E12：pendingSource 已清）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupViewModelSourceTest {

    private lateinit var backupService: BackupService
    private lateinit var settingsRepo: com.situ.aichat.data.repository.SettingsRepository
    private lateinit var vm: BackupViewModel

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val preview = BackupPreview(
        characters = listOf(CharacterPreviewRow(uuid = "u1", name = "阿甲", messageCount = 3)),
        mediaCount = 1,
        hasGlobalData = false,
    )

    @Before
    fun setUp() {
        backupService = mockk()
        settingsRepo = mockk(relaxed = true)
        vm = BackupViewModel(
            backupService = backupService,
            backgroundScheduler = mockk(relaxed = true),
            settingsRepo = settingsRepo,
            reliabilityPromptController = mockk(relaxed = true),
            llmForeground = mockk(relaxed = true),
        )
    }

    @Test
    fun `确认导入交回同一个源且它还能再开一条流（预览段不驻留整包字节）`() = runBlocking {
        var opens = 0
        val source = BackupByteSource {
            opens++
            byteArrayOf(1, 2, 3).inputStream()
        }
        coEvery { backupService.canOpen(any()) } returns true
        coEvery { backupService.previewArchive(any()) } returns preview
        val captured = slot<BackupByteSource>()
        coEvery { backupService.importArchive(capture(captured), any(), any()) } returns
            ImportResult.Success(1, 0, 0, 0, 3)

        vm.startImport(source)
        idle()

        assertEquals("预览段绝不整读（真正的开流由 service 层按需做）", 0, opens)
        assertNotNull("已进预览段", vm.preview.value)

        vm.confirmImport()
        idle()

        assertSame("确认导入用的必须是同一个源", source, captured.captured)
        assertNotNull("源必须还能再开一条新流（两遍流式全靠它）", captured.captured.open())
        assertEquals("刚才那一下就是重开的流", 1, opens)
        assertTrue(vm.importResult.value is ImportResult.Success)
        assertFalse(vm.busy.value)
    }

    @Test
    fun `源打不开时置 readFailed_不崩也不进导入（E11）`() = runBlocking {
        val dead = BackupByteSource { null }
        coEvery { backupService.canOpen(any()) } returns false

        vm.startImport(dead)
        idle()

        assertTrue("打不开要如实告诉用户", vm.readFailed.value)
        assertFalse(vm.busy.value)
        coVerify(exactly = 0) { backupService.previewArchive(any()) }
        coVerify(exactly = 0) { backupService.importArchive(any(), any(), any()) }
    }

    @Test
    fun `关掉预览后确认导入不再生效（E12）`() = runBlocking {
        coEvery { backupService.canOpen(any()) } returns true
        coEvery { backupService.previewArchive(any()) } returns preview
        coEvery { backupService.importArchive(any(), any(), any()) } returns ImportResult.Success(1, 0, 0, 0, 3)

        vm.startImport { byteArrayOf(9).inputStream() }
        idle()
        vm.dismissPreview()
        vm.confirmImport()
        idle()

        coVerify(exactly = 0) { backupService.importArchive(any(), any(), any()) }
        assertEquals(null, vm.preview.value)
        assertEquals(null, vm.importResult.value)
    }

    @Test
    fun B7_切换包含媒体持久化写入设置仓库() = runBlocking {
        vm.setIncludeMedia(false)
        idle()
        coVerify(exactly = 1) { settingsRepo.setBackupIncludeMedia(false) }
    }
}
