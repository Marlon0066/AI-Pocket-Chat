package com.situ.aichat.ui.character

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.worldbook.WorldBookRepository
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.member.WorldMembershipService
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [CharacterWorldViewModel] T2：响应式订阅（同屏摘/挂世界书实时同步）+ join / joinClearingWorldBooks / leave / move。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CharacterWorldViewModelTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val uuid = "c1"
    private val seed = 1L

    private class Harness(
        val vm: CharacterWorldViewModel,
        val service: WorldMembershipService,
        val characterFlow: MutableStateFlow<CharacterEntity?>,
        val boundBooksFlow: MutableStateFlow<List<String>>,
    )

    private fun buildHarness(
        joined: Boolean = false,
        homeCityId: String = WorldIds.HOME_CITY_ID,
        native: Boolean = false,
        wbBound: Boolean = false,
        worldExists: Boolean = true,
    ): Harness {
        val characterFlow = MutableStateFlow<CharacterEntity?>(
            CharacterEntity(uuid = uuid, name = "苏晚", creationDate = 0L, joinedWorld = joined, worldHomeCityId = homeCityId),
        )
        val nativesFlow = MutableStateFlow(
            if (native) listOf(WorldNativeStateEntity(nativeId = "native:x", recruitedCharacterUuid = uuid)) else emptyList(),
        )
        val worldFlow = MutableStateFlow(
            if (worldExists) WorldStateEntity(seed = seed, createdAt = 0L) else null,
        )
        val boundBooksFlow = MutableStateFlow(
            if (wbBound) listOf("book1") else emptyList(),
        )

        val characterDao = mockk<CharacterDao> {
            every { observeByUuid(uuid) } returns characterFlow
        }
        val worldNativeDao = mockk<WorldNativeDao> {
            every { observeAll() } returns nativesFlow
        }
        val worldDao = mockk<WorldDao> {
            every { observeState() } returns worldFlow
        }
        val worldBookRepo = mockk<WorldBookRepository> {
            every { observeBoundBookUuidsForCharacter(uuid) } returns boundBooksFlow
        }
        val service = mockk<WorldMembershipService>(relaxed = true)
        val vm = CharacterWorldViewModel(
            characterDao, worldNativeDao, worldDao, worldBookRepo, service,
            SavedStateHandle(mapOf("characterUuid" to uuid)),
        )
        idle()
        return Harness(vm, service, characterFlow, boundBooksFlow)
    }

    @Test
    fun `四态派生_普通关`() {
        val h = buildHarness(joined = false)
        val s = h.vm.state.value
        assertTrue(s.loaded)
        assertFalse(s.joined); assertFalse(s.nativeOrigin); assertFalse(s.worldbookBound)
    }

    @Test
    fun `四态派生_已加入_住址城名与regions解析`() {
        val h = buildHarness(joined = true)
        val s = h.vm.state.value
        assertTrue(s.joined); assertFalse(s.nativeOrigin); assertFalse(s.worldbookBound)
        assertEquals(WorldIds.HOME_CITY_ID, s.homeCityId)
        assertEquals("云野镇", s.homeCityName)
        assertTrue("同城默认", s.sameCityAsUser)
        assertEquals("十大区全列", 10, s.regions.size)
        assertTrue("住址城所在区有城", s.citiesOfRegion.isNotEmpty())
    }

    @Test
    fun `同屏响应式同步_摘除世界书或离世立即更新状态`() {
        val h = buildHarness(joined = false, wbBound = true)
        assertTrue(h.vm.state.value.worldbookBound)

        // 模拟同屏下方将世界书设为「不使用」
        h.boundBooksFlow.value = emptyList()
        idle()
        assertFalse("无需退出重进，worldbookBound 应实时变回 false", h.vm.state.value.worldbookBound)

        // 模拟同屏下方换用世界书导致离世
        h.characterFlow.value = h.characterFlow.value?.copy(joinedWorld = true)
        idle()
        assertTrue(h.vm.state.value.joined)
        h.characterFlow.value = h.characterFlow.value?.copy(joinedWorld = false)
        h.boundBooksFlow.value = listOf("book2")
        idle()
        assertFalse(h.vm.state.value.joined)
        assertTrue(h.vm.state.value.worldbookBound)
    }

    @Test
    fun `四态派生_原住民锁`() {
        val h = buildHarness(joined = true, native = true)
        val s = h.vm.state.value
        assertTrue(s.nativeOrigin); assertTrue(s.joined)
    }

    @Test
    fun `join与joinClearingWorldBooks分别委托service`() {
        val h = buildHarness(joined = false, wbBound = true)
        h.vm.joinClearingWorldBooks()
        idle()
        coVerify(exactly = 1) { h.service.joinClearingWorldBooks(uuid, any()) }

        h.vm.join()
        idle()
        coVerify(exactly = 1) { h.service.join(uuid, any()) }
    }

    @Test
    fun `leave与move委托service`() {
        val h = buildHarness(joined = true)
        h.vm.leave()
        h.vm.move("city_taoqiu")
        idle()
        coVerify(exactly = 1) { h.service.leave(uuid, any()) }
        coVerify(exactly = 1) { h.service.move(uuid, "city_taoqiu", any()) }
    }

    @Test
    fun `selectRegion切区_只重算城列表不落库`() {
        val h = buildHarness(joined = true)
        val other = h.vm.state.value.regions.first { it.id != h.vm.state.value.selectedRegionId }
        h.vm.selectRegion(other.id)
        assertEquals(other.id, h.vm.state.value.selectedRegionId)
        coVerify(exactly = 0) { h.service.join(any(), any()) }
        coVerify(exactly = 0) { h.service.move(any(), any(), any()) }
    }
}
