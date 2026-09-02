package com.situ.aichat.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.prompt.PromptModule
import com.situ.aichat.prompt.PromptModuleService
import com.situ.aichat.prompt.SystemModuleType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T2-3（「我们的日子」卷二图纸 §7.2 · E51）：真 [SettingsRepository] + 临时文件 DataStore——
 * ① 老序备份（无 ourDays 模块）经 [SettingsRepository.applyBackupSettings] 链尾补迁后，ourDays 在见面记忆正后；
 * ② [SettingsRepository.migratePromptModuleOurDaysOnce] 冷启一次性归位 + flag 守卫（第二次不再动用户挪过的顺序）。
 * 断言从图纸 §3.2 独立反推。
 */
class SettingsRepositoryOurDaysChainTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "s.preferences_pb") })
        repo = SettingsRepository(store)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** 卷二之前的持久化 JSON：22 个模块（无 ourDays）·sortOrder = 0..21。 */
    private fun legacyJson(): String = PromptModuleService.encodeModules(
        PromptModuleService.defaultModules()
            .filter { it.systemModuleType != SystemModuleType.OUR_DAYS }
            .mapIndexed { i, m -> m.copy(sortOrder = i) },
    )

    private fun List<PromptModule>.assertOurDaysRightAfterMeetingMemory() {
        val sorted = sortedBy { it.sortOrder }
        val mm = sorted.indexOfFirst { it.systemModuleType == SystemModuleType.OFFLINE_MEETING_MEMORY }
        assertEquals(SystemModuleType.OUR_DAYS, sorted[mm + 1].systemModuleType)
        val orders = map { it.sortOrder }
        assertEquals("无并列 sortOrder", orders.size, orders.toSet().size)
    }

    @Test
    fun 老序备份恢复后_ourDays在见面记忆正后_E51() = runBlocking {
        val legacyChar = PromptModuleService.setCharacterModules("cA", PromptModuleService.loadGlobalModules(legacyJson()).filter { it.systemModuleType != SystemModuleType.OUR_DAYS }, "")
        repo.applyBackupSettings(AppSettings(promptModulesJSON = legacyJson(), characterPromptModulesJSON = legacyChar))
        val restored = repo.appSettings.first()

        val global = PromptModuleService.loadGlobalModules(restored.promptModulesJSON)
        assertEquals(23, global.size)
        global.assertOurDaysRightAfterMeetingMemory()
        assertEquals(7, global.first { it.systemModuleType == SystemModuleType.OUR_DAYS }.sortOrder)

        val cA = PromptModuleService.loadCharacterModules("cA", restored.characterPromptModulesJSON)!!
        cA.assertOurDaysRightAfterMeetingMemory()
    }

    @Test
    fun 冷启一次性归位_第二次不动用户挪过的顺序() = runBlocking {
        repo.setPromptModulesJSON(legacyJson())
        repo.migratePromptModuleOurDaysOnce()
        val migrated = PromptModuleService.loadGlobalModules(repo.appSettings.first().promptModulesJSON)
        migrated.assertOurDaysRightAfterMeetingMemory()

        // 用户随后把「我们的日子」挪到最前并保存；再跑 once（flag 已置）→ 原样。
        val moved = migrated.map { if (it.systemModuleType == SystemModuleType.OUR_DAYS) it.copy(sortOrder = -1) else it }
        repo.setPromptModulesJSON(PromptModuleService.encodeModules(moved))
        repo.migratePromptModuleOurDaysOnce()
        val after = PromptModuleService.loadGlobalModules(repo.appSettings.first().promptModulesJSON)
        assertEquals(-1, after.first { it.systemModuleType == SystemModuleType.OUR_DAYS }.sortOrder)
        assertEquals(true, store.data.first()[SettingsRepository.KEY_OUR_DAYS_MODULE_ORDER_MIGRATED])
    }

    @Test
    fun 全局JSON为空的老用户_once只置flag_默认序本就在位() = runBlocking {
        repo.migratePromptModuleOurDaysOnce()
        val prefs = store.data.first()
        assertEquals(true, prefs[SettingsRepository.KEY_OUR_DAYS_MODULE_ORDER_MIGRATED])
        assertEquals(null, prefs[SettingsRepository.KEY_PROMPT_MODULES])
        PromptModuleService.loadGlobalModules("").assertOurDaysRightAfterMeetingMemory()
    }

    @Test
    fun 备份链不置once_flag() = runBlocking {
        repo.applyBackupSettings(AppSettings(promptModulesJSON = legacyJson()))
        assertEquals(null, store.data.first()[SettingsRepository.KEY_OUR_DAYS_MODULE_ORDER_MIGRATED])
        // 之后冷启 once 仍幂等：已归位 → 不动。
        val before = repo.appSettings.first().promptModulesJSON
        repo.migratePromptModuleOurDaysOnce()
        assertEquals(before, repo.appSettings.first().promptModulesJSON)
    }
}
