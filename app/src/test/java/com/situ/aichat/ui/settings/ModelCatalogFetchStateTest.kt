package com.situ.aichat.ui.settings

import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.modelcatalog.ModelCatalogException
import com.situ.aichat.data.remote.llm.modelcatalog.ModelCatalogService
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T2：模型列表拉取状态机 [ModelCatalogUiState] 的行为。
 *
 * 断言从修缮规格独立反推（不照搬实现）——每条对应一个旧实现的确切缺陷：
 * - 连点两次 → 只认最后一次的结果（旧：两个并发请求，先回来的关掉转圈、后回来的失败还抹掉列表）；
 * - 拉取失败 → **保留上一次成功的列表**（旧：`_availableModels.value = emptyList()`）；
 * - 服务端 200 但零模型 → [ModelCatalogUiState.Empty]，与「从没拉过」区分（旧：两者外观完全一样）；
 * - 拉取一律实时（无任何写死清单，用户 2026-08-28 拍板）——零模型只会是 Empty，不会变出内置条目；
 * - 改地址/Key 后 clearModels → 回 Idle（下次展开才会自动重拉）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelCatalogFetchStateTest {

    private val repo = mockk<ApiConfigRepository>(relaxed = true)
    private val catalog = mockk<ModelCatalogService>()
    private val router = mockk<ApiFunctionRouter>(relaxed = true)
    private val dispatcher = StandardTestDispatcher()

    private fun vm(): ApiConfigViewModel {
        every { repo.observeAll() } returns emptyFlow()
        every { repo.observeActive() } returns emptyFlow()
        every { router.assignments } returns emptyFlow()
        return ApiConfigViewModel(repo, catalog, router)
    }

    private fun fetch(vm: ApiConfigViewModel) =
        vm.fetchModels(ApiProviderType.OPENAI_COMPATIBLE, "https://h.com/v1", "sk-test-key-123456")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `初始态是 Idle`() = runTest(dispatcher) {
        assertEquals(ModelCatalogUiState.Idle, vm().modelCatalogState.value)
    }

    @Test
    fun `拉取成功进入 Loaded 且无告警`() = runTest(dispatcher) {
        coEvery { catalog.fetchModels(any()) } returns listOf(APIModelOption("gpt-4o"))
        val vm = vm()
        fetch(vm)
        advanceUntilIdle()
        val state = vm.modelCatalogState.value
        assertTrue(state is ModelCatalogUiState.Loaded)
        assertEquals(listOf("gpt-4o"), state.models.map { it.id })
        assertNull(state.error)
    }

    @Test
    fun `零模型进入 Empty 而非 Idle`() = runTest(dispatcher) {
        coEvery { catalog.fetchModels(any()) } returns emptyList()
        val vm = vm()
        fetch(vm)
        advanceUntilIdle()
        assertEquals(ModelCatalogUiState.Empty, vm.modelCatalogState.value)
    }

    @Test
    fun `失败保留上一次成功的列表`() = runTest(dispatcher) {
        val vm = vm()
        coEvery { catalog.fetchModels(any()) } returns listOf(APIModelOption("gpt-4o"))
        fetch(vm)
        advanceUntilIdle()

        coEvery { catalog.fetchModels(any()) } throws ModelCatalogException.HttpStatus(401, "unauthorized")
        fetch(vm)
        advanceUntilIdle()

        val state = vm.modelCatalogState.value
        assertTrue(state is ModelCatalogUiState.Failed)
        assertEquals(listOf("gpt-4o"), state.models.map { it.id }) // 列表没被抹掉
        assertTrue(state.error!!.contains("401"))
    }

    @Test
    fun `失败信息里的 apiKey 被脱敏`() = runTest(dispatcher) {
        coEvery { catalog.fetchModels(any()) } throws RuntimeException("bad key sk-test-key-123456")
        val vm = vm()
        fetch(vm)
        advanceUntilIdle()
        val msg = vm.modelCatalogState.value.error.orEmpty()
        assertTrue(msg.isNotEmpty())
        assertTrue(!msg.contains("sk-test-key-123456"))
    }

    @Test
    fun `连点两次只认最后一次结果`() = runTest(dispatcher) {
        val vm = vm()
        // ⚠️ 按调用序分流，不能靠「先设慢 stub 再改快 stub」——StandardTestDispatcher 下 fetch() 只是把协程
        // 排队，第一个 Job 真正开跑时 stub 早被换成第二个了，两次都会拿到同一个返回值：那样写的话把 VM 换回
        // 旧的裸 launch 实现（无 fetchJob、不 cancel）测试照样绿 = 证明不了防重入。
        var call = 0
        coEvery { catalog.fetchModels(any()) } coAnswers {
            if (call++ == 0) {
                delay(1_000)
                listOf(APIModelOption("slow-A"))
            } else {
                listOf(APIModelOption("fast-B"))
            }
        }
        fetch(vm)
        runCurrent() // ← 关键：让第一个任务真的开跑并挂在 delay 上。少了这句，第二次 fetch 取消的是一个
                     //   还没启动的 Job，两次都会走 stub 的第一分支，测的根本不是防重入（第一版就栽在这）。
        fetch(vm)
        advanceUntilIdle()

        assertEquals(2, call) // 两次都真的进过 stub（否则下面的断言无意义）
        assertEquals(listOf("fast-B"), vm.modelCatalogState.value.models.map { it.id })
    }

    @Test
    fun `旧任务被取消后不得改写新状态`() = runTest(dispatcher) {
        // MC1 的另一半：取消不该被 catch(Exception) 误判成拉取失败，迟到的取消也不该盖掉 clearModels 写的 Idle。
        val vm = vm()
        coEvery { catalog.fetchModels(any()) } coAnswers {
            delay(1_000)
            listOf(APIModelOption("slow-A"))
        }
        fetch(vm)
        runCurrent()
        assertTrue(vm.modelCatalogState.value.isLoading)

        vm.clearModels()
        advanceUntilIdle() // 让被取消的协程走完收尾

        assertEquals("取消必须落回 Idle，绝不能变成 Failed", ModelCatalogUiState.Idle, vm.modelCatalogState.value)
    }

    @Test
    fun `拉取期间是 Loading 态`() = runTest(dispatcher) {
        coEvery { catalog.fetchModels(any()) } coAnswers {
            delay(1_000)
            listOf(APIModelOption("gpt-4o"))
        }
        val vm = vm()
        fetch(vm)
        // 只推进到协程启动、未到完成：此刻应在 Loading
        runCurrent()
        assertTrue(vm.modelCatalogState.value.isLoading)
        advanceUntilIdle()
        assertTrue(!vm.modelCatalogState.value.isLoading)
    }

    @Test
    fun `clearModels 回到 Idle 以便下次展开重拉`() = runTest(dispatcher) {
        coEvery { catalog.fetchModels(any()) } returns listOf(APIModelOption("gpt-4o"))
        val vm = vm()
        fetch(vm)
        advanceUntilIdle()
        assertTrue(vm.modelCatalogState.value.models.isNotEmpty())

        vm.clearModels()
        assertEquals(ModelCatalogUiState.Idle, vm.modelCatalogState.value)
    }

    @Test
    fun `失败后不再是 Idle 因此不会每次展开都自动重拉`() = runTest(dispatcher) {
        coEvery { catalog.fetchModels(any()) } throws ModelCatalogException.InvalidUrl
        val vm = vm()
        fetch(vm)
        advanceUntilIdle()
        assertTrue(vm.modelCatalogState.value !is ModelCatalogUiState.Idle)
    }
}
