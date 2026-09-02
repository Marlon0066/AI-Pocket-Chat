package com.situ.aichat.data.backup

import android.content.Context
import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.diagnostics.perf.FakeBackupBuilder
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * T2-1 / T2-2（性能专项卷 A 图纸 §7）：[BackupImporter] **两遍流式导入**的真行为——Robolectric + 真 in-memory Room
 * + 真 DAO（计数/策略/落库都是真的，只 mock 掉设置仓库与原型校准这两个与本卷无关的协作者）。
 *
 * 断言从图纸 §5 边界表独立反推：
 * - E1 正常包：逐策略计数与消息数（导入两轮：首轮全新 → 次轮 跳过+覆盖）；
 * - **E10 跳过策略的媒体连字节都不读**：用「重存出来的音频文件条数」量——跳过的那个角色不该多出文件；
 * - E2/B5 manifest 不在首位（用户解压重打包）照样导入；
 * - E19 RESTORE_MEDIA 进度单调、分母 = manifest 记的媒体数；
 * - E9/J6 同键条目重复出现：后者胜、前者文件当场删掉不留孤儿；
 * - E3 旧 `.json` 回退仍可导入；**E4 非 zip 大文件读满 32MB 帽即收手**（绝不整读）；
 * - E6 zip 里 manifest 损坏 → 「解析失败：备份文件损坏」（不误入 legacy 回退路）；E5 无 manifest 的 zip → 报错不崩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupImporterStreamingTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }
    private lateinit var app: Context
    private lateinit var db: AppDatabase
    private lateinit var importer: BackupImporter

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        importer = BackupImporter(
            appContext = app,
            appDatabase = db,
            characterDao = db.characterDao(),
            conversationDao = db.conversationDao(),
            messageDao = db.messageDao(),
            milestoneDao = db.milestoneDao(),
            petDao = db.petDao(),
            currencyDao = db.currencyDao(),
            giftDao = db.giftDao(),
            redPacketDao = db.redPacketDao(),
            meetingAppointmentDao = db.meetingAppointmentDao(),
            redeemCodeUsageDao = db.redeemCodeUsageDao(),
            momentDao = db.momentDao(),
            diaryDao = db.diaryDao(),
            scheduleDao = db.scheduleDao(),
            storyDao = db.storyDao(),
            notificationTemplateDao = db.notificationTemplateDao(),
            customStickerDao = db.customStickerDao(),
            userProfileDao = db.userProfileDao(),
            worldBookDao = db.worldBookDao(),
            worldDao = db.worldDao(),
            worldSocialDao = db.worldSocialDao(),
            worldNativeDao = db.worldNativeDao(),
            worldMemoryDao = db.worldMemoryDao(),
            worldUserResidentDao = db.worldUserResidentDao(),
            offlineMeetingMemoryDao = db.offlineMeetingMemoryDao(),
            promiseDao = db.promiseDao(),
            userStoryTemplateDao = db.userStoryTemplateDao(),
            ourDayDao = db.ourDayDao(),
            settingsRepo = mockk(relaxed = true),
            mediaRestorer = BackupMediaRestorer(app),
            archetypeCalibrator = mockk(relaxed = true),
        )
        File(app.filesDir, AUDIO_DIR).deleteRecursively()
    }

    @After fun tearDown() = db.close()

    // ── 造包 ──

    /** 音频键：重存走 [com.situ.aichat.util.AudioStore]，**纯字节落盘不解码** → 文件条数是可信的观测量。 */
    private fun audioKey(uuid: String) = "${BackupArchive.MEDIA_PREFIX}audio/$uuid.wav"

    private fun character(uuid: String, name: String, msgUuid: String) = CharacterBackupData(
        character = CharacterExport(uuid = uuid, name = name, creationDate = 1L),
        conversations = listOf(
            ConversationExport(
                uuid = "conv-$uuid",
                creationDate = 1L,
                messages = listOf(MessageExport(messageUUID = msgUuid, timestamp = 1L, audioArchiveKey = audioKey(uuid))),
            ),
        ),
    )

    private fun twoCharacterPackage() = BackupPackage(
        manifest = BackupManifest(version = 2, includesMedia = true, mediaCount = 2),
        characters = listOf(character(UUID_A, "阿甲", "m-a"), character(UUID_B, "阿乙", "m-b")),
    )

    private val twoCharacterMedia = linkedMapOf(
        audioKey(UUID_A) to byteArrayOf(1, 2, 3, 4),
        audioKey(UUID_B) to byteArrayOf(5, 6, 7, 8, 9),
    )

    /** 用真打包路径（[BackupArchive.writeTo]）造包：manifest 在首位，媒体从磁盘流式拷入。 */
    private fun archiveBytes(pkg: BackupPackage, media: Map<String, ByteArray>): ByteArray {
        val dir = File(app.cacheDir, "streaming_src").apply { mkdirs() }
        val paths = LinkedHashMap<String, String>()
        media.forEach { (key, bytes) ->
            paths[key] = File(dir, key.substringAfterLast('/')).apply { writeBytes(bytes) }.absolutePath
        }
        return ByteArrayOutputStream().also { bos ->
            bos.use { BackupArchive.writeTo(it, json.encodeToString(BackupPackage.serializer(), pkg), paths) }
        }.toByteArray()
    }

    /** 手工排序造包（造得出「manifest 不在首位」这种用户解压重打包的包）。 */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun audioFileCount(): Int = File(app.filesDir, AUDIO_DIR).listFiles()?.size ?: 0

    // ── E1 / E10：计数、策略、跳过不重存 ──

    @Test fun `逐策略计数与消息数如实_跳过的角色媒体一个字节都不重存（E1_E10）`() = runBlocking {
        val bytes = archiveBytes(twoCharacterPackage(), twoCharacterMedia)

        val first = importer.importArchive(BackupByteSource.fromBytes(bytes)) as ImportResult.Success

        assertEquals("两个都是新角色", 2, first.imported)
        assertEquals(0, first.overwritten)
        assertEquals(0, first.skipped)
        assertEquals(2, first.messages)
        assertEquals("一条不缺 → 结果区不该出现警示行（E14）", 0, first.mediaFailed)
        assertEquals("两条音频都该重存出来", 2, audioFileCount())

        val second = importer.importArchive(
            BackupByteSource.fromBytes(bytes),
            mapOf(UUID_A to ImportStrategy.SKIP, UUID_B to ImportStrategy.OVERWRITE),
        ) as ImportResult.Success

        assertEquals(0, second.imported)
        assertEquals(1, second.overwritten)
        assertEquals(1, second.skipped)
        assertEquals("跳过的角色不写消息", 1, second.messages)
        assertEquals("只该多出被覆盖角色那一条音频（跳过项的字节压根没读）", 3, audioFileCount())
        assertEquals("库里仍是两个角色", 2, db.characterDao().getAll().size)
    }

    @Test fun `单条媒体坏掉只记一笔_整包结构化数据照常恢复（E7）`() = runBlocking {
        // 坏媒体的真实形态之一：条目在、字节没了（截断/半截文件）。重存必然失败（空字节 → Store 返回 null）。
        val media = linkedMapOf(
            audioKey(UUID_A) to ByteArray(0),
            audioKey(UUID_B) to byteArrayOf(5, 6, 7, 8, 9),
        )
        val bytes = archiveBytes(twoCharacterPackage(), media)

        val result = importer.importArchive(BackupByteSource.fromBytes(bytes)) as ImportResult.Success

        assertEquals("坏一条不该让整包导入失败", 2, result.imported)
        assertEquals("两个角色的消息一条不少", 2, result.messages)
        assertEquals("坏掉的那条要如实报出来", 1, result.mediaFailed)
        assertEquals("好的那条照常落盘", 1, audioFileCount())
        assertEquals(2, db.characterDao().getAll().size)
    }

    @Test fun `manifest 不在首位的重打包包照样导入（E2_B5）`() = runBlocking {
        val media = twoCharacterMedia
        val bytes = zipOf(
            audioKey(UUID_A) to media.getValue(audioKey(UUID_A)),
            audioKey(UUID_B) to media.getValue(audioKey(UUID_B)),
            BackupArchive.MANIFEST_ENTRY to json.encodeToString(BackupPackage.serializer(), twoCharacterPackage()).encodeToByteArray(),
        )

        val result = importer.importArchive(BackupByteSource.fromBytes(bytes)) as ImportResult.Success

        assertEquals(2, result.imported)
        assertEquals(2, result.messages)
        assertEquals(2, audioFileCount())
    }

    @Test fun `媒体进度单调递增且分母取 manifest 记的媒体数（E19）`() = runBlocking {
        val bytes = archiveBytes(twoCharacterPackage(), twoCharacterMedia)
        val restore = ArrayList<Pair<Int, Int>>()

        importer.importArchive(BackupByteSource.fromBytes(bytes)) { p ->
            if (p.stage == BackupProgress.Stage.RESTORE_MEDIA) restore.add(p.done to p.total)
        }

        assertEquals(listOf(1 to 2, 2 to 2), restore)
    }

    @Test fun `同键条目重复出现时后者胜且前者文件不留孤儿（E9_J6）`() = runBlocking {
        // ZipOutputStream 不许写重名条目 → 造两个**等长**的名字再把字节里的 "dupB" 换成 "dupA"，
        // 得到一个真含重复键的包（ZipInputStream 只认局部文件头，逐条读得到两条同名条目）。
        val pkg = BackupPackage(
            manifest = BackupManifest(version = 2, includesMedia = true, mediaCount = 2),
            characters = listOf(
                CharacterBackupData(
                    character = CharacterExport(uuid = UUID_A, name = "阿甲", creationDate = 1L),
                    conversations = listOf(
                        ConversationExport(
                            uuid = "conv-$UUID_A",
                            creationDate = 1L,
                            messages = listOf(
                                MessageExport(
                                    messageUUID = "m-dup",
                                    timestamp = 1L,
                                    audioArchiveKey = "${BackupArchive.MEDIA_PREFIX}audio/dupA.wav",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val older = byteArrayOf(1, 1, 1, 1)
        val newer = byteArrayOf(2, 2, 2, 2, 2, 2)
        val raw = zipOf(
            BackupArchive.MANIFEST_ENTRY to json.encodeToString(BackupPackage.serializer(), pkg).encodeToByteArray(),
            "${BackupArchive.MEDIA_PREFIX}audio/dupA.wav" to older,
            "${BackupArchive.MEDIA_PREFIX}audio/dupB.wav" to newer,
        )
        val bytes = replaceAscii(raw, "dupB", "dupA")

        val result = importer.importArchive(BackupByteSource.fromBytes(bytes))

        assertTrue("导入应成功，实=$result", result is ImportResult.Success)
        val files = File(app.filesDir, AUDIO_DIR).listFiles().orEmpty()
        assertEquals("前一份必须被删掉，不留孤儿", 1, files.size)
        assertEquals("后者胜", newer.toList(), files.single().readBytes().toList())
        val stored = db.messageDao().getAllForConversation("conv-$UUID_A").single().audioRelativePath
        assertEquals("消息指向的正是留下来的那一份", files.single().absolutePath, stored)
    }

    // ── E3 / E4 / E5 / E6：回退路与损坏包 ──

    @Test fun `旧明文 json 备份仍可导入（E3）`() = runBlocking {
        val legacy = json.encodeToString(
            BackupPackage.serializer(),
            BackupPackage(characters = listOf(CharacterBackupData(character = CharacterExport(uuid = UUID_A, name = "旧甲", creationDate = 1L)))),
        )

        val result = importer.importArchive(BackupByteSource.fromBytes(legacy.encodeToByteArray()))

        assertTrue("旧 .json 应照常导入，实=$result", result is ImportResult.Success)
        assertEquals("旧甲", db.characterDao().getAll().single().name)
    }

    @Test fun `非 zip 的大文件读满 32MB 帽就收手_绝不整读（E4）`() = runBlocking {
        // 用户误选了个几百 MB 的文件：旧代码会把它整个读进内存（本卷要根治的病根）。
        // 这里给一条「要多少给多少」的无穷流，量它到底拉走了多少。
        val stream = EndlessCountingStream()

        val result = importer.importArchive({ stream })

        assertEquals(ImportResult.Error("不是有效的备份文件"), result)
        assertTrue(
            "读取量必须钉在 32MB 帽附近，实拉 ${stream.bytesRead} 字节",
            stream.bytesRead <= 32L * 1024 * 1024 + 1024 * 1024,
        )
    }

    @Test fun `zip 里 manifest 损坏报「备份文件损坏」而不是滑进回退路（E6）`() = runBlocking {
        val bytes = zipOf(BackupArchive.MANIFEST_ENTRY to "{ 这不是合法 JSON".encodeToByteArray())

        val result = importer.importArchive(BackupByteSource.fromBytes(bytes))

        assertEquals(ImportResult.Error("解析失败：备份文件损坏"), result)
    }

    @Test fun `没有 manifest 的 zip 走回退路后报错不崩（E5）`() = runBlocking {
        val bytes = zipOf("${BackupArchive.MEDIA_PREFIX}audio/a.wav" to byteArrayOf(1, 2, 3))

        val result = importer.importArchive(BackupByteSource.fromBytes(bytes))

        assertTrue("应是错误结果，实=$result", result is ImportResult.Error)
        assertEquals("库不该被写进任何东西", 0, db.characterDao().getAll().size)
    }

    /** T2-5 / E17（卷 A 图纸 §7）：体检用的「可导入档」假包必须真能进导入，且再导一次不翻倍。 */
    @Test fun `可导入档假包能真导入且重复导入幂等（T2-5_E17）`() = runBlocking {
        val builder = FakeBackupBuilder(app, json)
        val file = builder.build(1L * 1024 * 1024, importable = true)!!
        val source = BackupByteSource { file.inputStream() }

        val first = importer.importArchive(source) as ImportResult.Success

        assertEquals("三个体检角色", 3, first.imported)
        assertEquals("每角色 20 条", 60, first.messages)
        assertEquals(3, db.characterDao().getAll().size)

        val second = importer.importArchive(source) as ImportResult.Success

        assertEquals("再导一次是覆盖不是新增", 3, second.overwritten)
        assertEquals("角色数不该翻倍（uuid REPLACE 幂等）", 3, db.characterDao().getAll().size)
        assertTrue("清理靠 uuid 前缀认人", db.characterDao().getAll().all { it.uuid.startsWith("fakebkp-") })
        builder.clear()
    }

    @Test fun `canOpen 对活源为真_对打不开的源为假（E11 的判据）`() = runBlocking {
        val service = BackupService(exporter = mockk(), importer = mockk(), firstMessageDateBackfill = mockk(relaxed = true))

        assertTrue(service.canOpen(BackupByteSource.fromBytes(byteArrayOf(1, 2, 3))))
        assertFalse("文件被移走 / 授权失效 → UI 给「读取失败」", service.canOpen { null })
        assertFalse("开流直接抛也算打不开，绝不穿透崩掉", service.canOpen { error("boom") })
    }

    // ── 小工具 ──

    /** 等长 ASCII 片段替换（造重复键的包用；等长才不破坏 zip 里的各处偏移）。 */
    private fun replaceAscii(bytes: ByteArray, from: String, to: String): ByteArray {
        require(from.length == to.length)
        val needle = from.encodeToByteArray()
        val replacement = to.encodeToByteArray()
        val out = bytes.copyOf()
        var i = 0
        while (i <= out.size - needle.size) {
            if ((needle.indices).all { out[i + it] == needle[it] }) {
                replacement.indices.forEach { out[i + it] = replacement[it] }
                i += needle.size
            } else {
                i++
            }
        }
        return out
    }

    /** 永远读得出字节的流（不是 zip、也没有尽头）——专门用来量「到底读了多少就收手」。 */
    private class EndlessCountingStream : InputStream() {
        var bytesRead = 0L
            private set

        override fun read(): Int {
            bytesRead++
            return 'a'.code
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            b.fill('a'.code.toByte(), off, off + len)
            bytesRead += len
            return len
        }
    }

    private companion object {
        const val UUID_A = "uuid-aaa"
        const val UUID_B = "uuid-bbb"

        /** [com.situ.aichat.util.AudioStore] 的落盘目录名（那里是纯字节写入，故文件条数可作观测量）。 */
        const val AUDIO_DIR = "tts_audio"
    }
}
