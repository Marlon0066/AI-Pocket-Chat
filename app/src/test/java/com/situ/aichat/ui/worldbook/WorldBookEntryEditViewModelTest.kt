package com.situ.aichat.ui.worldbook

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.worldbook.WorldBookRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 条目编辑器 VM T2（WB7b·真 Room + 真仓库·Robolectric 主循环驱动 viewModelScope）：
 * 向导预选 / 关键词切分去重 / 触发方式互斥 / 脏标记 / 保存与删除真落库（保存走仓库 = 热更新语义随行）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldBookEntryEditViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: WorldBookRepository
    private lateinit var bookUuid: String

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = WorldBookRepository(db.worldBookDao())
        bookUuid = runBlocking { repo.createBook("青云录") }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Room suspend 查询走自身后台执行器，续体稍后才回主循环——轮询 idle 直到条件满足。 */
    private fun await(message: String, condition: () -> Boolean) {
        repeat(200) {
            idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    private fun newVm(vararg args: Pair<String, Any?>): WorldBookEntryEditViewModel {
        val handle = SavedStateHandle(mapOf("bookUuid" to bookUuid, *args))
        return WorldBookEntryEditViewModel(repo, handle).also { vm ->
            await("草稿加载") { vm.draft != null }
        }
    }

    @Test
    fun 向导类别_基调预选常驻_其余关键词() {
        val basis = newVm("guide" to "BASIS")
        assertTrue("基调向导须预选常驻", basis.draft!!.constant)

        val place = newVm("guide" to "PLACE")
        val entry = place.draft!!
        assertFalse(entry.constant)
        assertFalse(entry.vectorized)
    }

    @Test
    fun 关键词_切分去重与删除() {
        val vm = newVm()
        vm.addKeys("青云门, 掌门、青云门，宗门")
        val entry = vm.draft!!
        assertEquals(listOf("青云门", "掌门", "宗门"), vm.keys(entry))

        vm.removeKey("掌门")
        assertEquals(listOf("青云门", "宗门"), vm.keys(vm.draft!!))

        vm.addKeys("苏掌门", secondary = true)
        assertEquals(listOf("苏掌门"), vm.secondaryKeys(vm.draft!!))
    }

    @Test
    fun 触发方式_三态互斥() {
        val vm = newVm()
        vm.setTriggerMode(WorldBookTriggerMode.VECTOR)
        vm.draft!!.let {
            assertTrue(it.vectorized)
            assertFalse(it.constant)
        }
        vm.setTriggerMode(WorldBookTriggerMode.CONSTANT)
        vm.draft!!.let {
            assertTrue(it.constant)
            assertFalse(it.vectorized)
        }
        vm.setTriggerMode(WorldBookTriggerMode.KEYWORD)
        vm.draft!!.let {
            assertFalse(it.constant)
            assertFalse(it.vectorized)
        }
    }

    @Test
    fun 脏标记_保存后复位_真落库() = runBlocking {
        val vm = newVm()
        assertFalse("初始不脏", vm.isDirty)

        vm.update { it.copy(comment = "青云门", content = "东域第一大派。") }
        assertTrue("改动后为脏", vm.isDirty)

        var saved = false
        vm.save { saved = true }
        await("保存完成") { saved }
        assertFalse("保存后不脏", vm.isDirty)

        val entries = db.worldBookDao().entriesForBook(bookUuid)
        assertEquals(1, entries.size)
        assertEquals("青云门", entries[0].comment)
    }

    @Test
    fun 编辑已有条目_加载与删除() = runBlocking {
        val draft = repo.newEntryDraft(bookUuid).copy(comment = "旧条目", content = "旧内容")
        repo.saveEntry(draft)

        val vm = newVm("entryUuid" to draft.uuid)
        assertTrue(vm.isEditing)
        assertEquals("旧条目", vm.draft!!.comment)

        var deleted = false
        vm.deleteEntry { deleted = true }
        await("删除完成") { deleted }
        assertNull(repo.getEntry(draft.uuid))
    }

    @Test
    fun 未回车关键词_计入isDirty_点保存自动收词入库() = runBlocking {
        val vm = newVm()
        vm.pendingPrimaryKeyInput = "剑冢, 藏经阁"
        vm.pendingSecondaryKeyInput = "禁制"
        assertTrue("输入框有未回车词条时 isDirty 应为真", vm.isDirty)

        var saved = false
        vm.save { saved = true }
        await("保存完成") { saved }

        assertEquals("", vm.pendingPrimaryKeyInput)
        assertEquals("", vm.pendingSecondaryKeyInput)
        assertFalse(vm.isDirty)

        val stored = db.worldBookDao().entriesForBook(bookUuid).single()
        assertEquals(listOf("剑冢", "藏经阁"), vm.keys(stored))
        assertEquals(listOf("禁制"), vm.secondaryKeys(stored))
    }

    @Test
    fun 正则量词逗号_整条保留绝不拆断() {
        val vm = newVm()
        vm.addKeys("/\\d{1,3}层/i")
        assertEquals(listOf("/\\d{1,3}层/i"), vm.keys(vm.draft!!))
    }
}
