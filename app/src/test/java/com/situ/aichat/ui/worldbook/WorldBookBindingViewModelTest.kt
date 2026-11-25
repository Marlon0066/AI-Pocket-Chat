package com.situ.aichat.ui.worldbook

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.worldbook.WorldBookRepository
import com.situ.aichat.world.member.WorldMembershipService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [WorldBookBindingViewModel] T2：响应式同步 + 未确认击穿守卫 + 确认后单事务 `switchLeaveAndBind` + 原住民拒绝。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldBookBindingViewModelTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val uuid = "c1"

    private class Harness(
        val vm: WorldBookBindingViewModel,
        val repo: WorldBookRepository,
        val membership: WorldMembershipService,
        val charFlow: MutableStateFlow<CharacterEntity?>,
    )

    private fun buildHarness(
        joined: Boolean = false,
        native: Boolean = false,
    ): Harness {
        val charEntity = CharacterEntity(uuid = uuid, name = "苏晚", creationDate = 0L, joinedWorld = joined)
        val charFlow = MutableStateFlow<CharacterEntity?>(charEntity)
        val nativeEntity = if (native) WorldNativeStateEntity(nativeId = "native:x", recruitedCharacterUuid = uuid) else null
        val nativeFlow = MutableStateFlow(listOfNotNull(nativeEntity))

        val characterDao = mockk<CharacterDao> {
            every { observeByUuid(uuid) } returns charFlow
            coEvery { getByUuid(uuid) } answers { charFlow.value }
        }
        val worldNativeDao = mockk<WorldNativeDao> {
            every { observeAll() } returns nativeFlow
            coEvery { getByRecruitedUuid(uuid) } returns nativeEntity
        }
        val repository = mockk<WorldBookRepository>(relaxed = true) {
            every { observeBoundBookUuidsForCharacter(uuid) } returns flowOf(emptyList())
            every { observeBookSummaries() } returns flowOf(emptyList())
            every { observeAllBooks() } returns flowOf(emptyList())
        }
        val membership = mockk<WorldMembershipService>(relaxed = true)
        val vm = WorldBookBindingViewModel(
            repository, characterDao, worldNativeDao, membership,
            SavedStateHandle(mapOf("characterUuid" to uuid)),
        )
        idle()
        return Harness(vm, repository, membership, charFlow)
    }

    @Test
    fun `未加入_selectSole与toggle正常委托repository`() {
        val h = buildHarness(joined = false)
        h.vm.selectSole("book1")
        h.vm.toggle("book2", bound = true)
        h.vm.toggle("book3", bound = false)
        idle()
        coVerify(exactly = 1) { h.repo.setSoleBinding(uuid, "book1") }
        coVerify(exactly = 1) { h.repo.bind(uuid, "book2") }
        coVerify(exactly = 1) { h.repo.unbind(uuid, "book3") }
    }

    @Test
    fun `已加入_未确认直接绑书被挡_但摘书清理脏数据放行`() {
        val h = buildHarness(joined = true)
        h.vm.selectSole("book1")
        h.vm.toggle("book2", bound = true)
        idle()
        coVerify(exactly = 0) { h.repo.setSoleBinding(uuid, "book1") }
        coVerify(exactly = 0) { h.repo.bind(any(), any()) }

        // 摘书（null / bound=false）允许清理历史残留脏绑定
        h.vm.selectSole(null)
        h.vm.toggle("book3", bound = false)
        idle()
        coVerify(exactly = 1) { h.repo.setSoleBinding(uuid, null) }
        coVerify(exactly = 1) { h.repo.unbind(uuid, "book3") }
    }

    @Test
    fun `已加入_确认切换switchLeaveAndBind委托membership单事务`() {
        val h = buildHarness(joined = true)
        h.vm.switchLeaveAndBind("book1", sole = true)
        idle()
        coVerify(exactly = 1) { h.membership.leaveAndBindWorldBook(uuid, "book1", sole = true, nowMs = any()) }
    }

    @Test
    fun `原住民_所有写操作零调用`() {
        val h = buildHarness(joined = true, native = true)
        assertTrue(h.vm.nativeOrigin.value)
        h.vm.selectSole("book1")
        h.vm.switchLeaveAndBind("book1", sole = true)
        idle()
        coVerify(exactly = 0) { h.repo.setSoleBinding(any(), any()) }
        coVerify(exactly = 0) { h.membership.leaveAndBindWorldBook(any(), any(), any(), any()) }
    }

    @Test
    fun `同屏响应式同步_世界开关翻转后joinedWorld立即跟随`() = runBlocking {
        val h = buildHarness(joined = true)
        assertTrue(h.vm.joinedWorld.value)
        h.charFlow.value = h.charFlow.value?.copy(joinedWorld = false)
        idle()
        assertFalse(h.vm.joinedWorld.value)
    }
}
