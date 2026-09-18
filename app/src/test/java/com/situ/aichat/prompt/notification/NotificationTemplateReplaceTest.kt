package com.situ.aichat.prompt.notification

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.NotificationTemplateDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.NotificationTemplateEntity
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 2026-09-18 五处小修 #3（T2·Robolectric + 真 in-memory Room）：通知文案池「生成成功后再原子替换」。
 *
 * 修前 generateAndSave 一进门就删整池，之后才调模型（最多 3 次 + 2s 间隔）——进程在这段时间被杀，
 * 该角色留下空池直到下次补生成。这里用「模型调用时抛出不会被 catch(Exception) 吞掉的 Error」模拟
 * 进程在调用途中死亡：生成器的后续代码一行都不会再执行，库里的状态就是被杀那一刻的状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationTemplateReplaceTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: NotificationTemplateDao

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "k",
        baseUrl = "https://example.test",
        modelName = "m",
    )

    private val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)

    /** 模拟进程在模型调用途中被杀（Error 不被生成器的 catch(Exception) 接住）。 */
    private class SimulatedProcessDeath : Error()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.notificationTemplateDao()
    }

    @After
    fun tearDown() = db.close()

    private fun seedOldPools() = runBlocking {
        dao.insertAll(
            listOf(
                NotificationTemplateEntity(characterId = "c1", category = "morning", content = "旧·早安"),
                NotificationTemplateEntity(characterId = "c1", category = "pet_hungry", content = "旧·饿了", isUsed = true),
                NotificationTemplateEntity(characterId = "c1", category = "random", content = "旧·随便聊"),
                NotificationTemplateEntity(characterId = "c2", category = "morning", content = "别人的早安"),
            ),
        )
    }

    private fun generator(llmAnswer: suspend () -> String): NotificationTemplateGenerator {
        val contextLog = mockk<ContextLogService>()
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers { llmAnswer() }
        val userProfileDao = mockk<UserProfileDao>()
        coEvery { userProfileDao.get() } returns null
        return NotificationTemplateGenerator(
            context = RuntimeEnvironment.getApplication(),
            llmClient = mockk(relaxed = true),
            contextLog = contextLog,
            templateDao = dao,
            userProfileDao = userProfileDao,
        )
    }

    private fun contents(characterId: String) = runBlocking { dao.allForCharacter(characterId).map { it.content }.sorted() }

    @Test fun processKilledDuringModelCall_oldPoolSurvives() {
        seedOldPools()
        var poolSeenByModelCall: List<String>? = null
        val gen = generator {
            poolSeenByModelCall = contents("c1")
            throw SimulatedProcessDeath()
        }

        val outcome = runCatching { runBlocking { gen.generateAndSave(character, relationship = null, config = config) } }

        assertTrue("前提：确实在模型调用处「被杀」", outcome.exceptionOrNull() is SimulatedProcessDeath)
        assertEquals("调模型时旧池必须还在", listOf("旧·早安", "旧·随便聊", "旧·饿了"), poolSeenByModelCall)
        assertEquals("被杀后旧池原样保留", listOf("旧·早安", "旧·随便聊", "旧·饿了"), contents("c1"))
        assertEquals(listOf("别人的早安"), contents("c2"))
    }

    @Test fun success_replacesWholePool_onlyForThatCharacter() {
        seedOldPools()
        val fullJson =
            """{"streak_remind":["a1","a2","a3","a4","a5"],"streak_urgent":["b1","b2","b3"],""" +
                """"streak_broken":["c1","c2","c3"],"morning":["d1","d2","d3"],"evening":["e1","e2","e3"],""" +
                """"random":["f1","f2","f3"],"pet_hungry":["g1","g2","g3"],"pet_sick":["h1","h2"],""" +
                """"pet_milestone":["i1","i2"]}"""
        val gen = generator { fullJson }

        runBlocking { gen.generateAndSave(character, relationship = null, config = config) }

        val pool = runBlocking { dao.allForCharacter("c1") }
        assertEquals("27 条新文案、旧的一条不剩", 27, pool.size)
        assertTrue(pool.none { it.content.startsWith("旧·") })
        assertTrue("新池全部未用", pool.none { it.isUsed })
        assertEquals(listOf("别人的早安"), contents("c2"))
    }

    @Test fun noConfig_defaultTemplatesReplaceOldPool() {
        seedOldPools()
        val gen = generator { error("无 config 不该调模型") }

        runBlocking { gen.generateAndSave(character, relationship = null, config = null) }

        val pool = runBlocking { dao.allForCharacter("c1") }
        assertTrue(pool.isNotEmpty())
        assertTrue("旧文案被默认文案整池替换", pool.none { it.content.startsWith("旧·") })
        assertTrue(runBlocking { gen.isUsingDefaultTemplates("c1") })
        assertEquals(listOf("别人的早安"), contents("c2"))
    }
}
