package com.situ.aichat.data.backup

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.prompt.growth.RelationshipArchetypeCalibrator
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * T2-4（图纸 §7·§3.3 入口③）：import()/importArchive() **成功路各触发一次** recalibrateAll，**失败路不触发**。
 * 真 Room（Robolectric·withTransaction 需真 db）+ 空 [BackupPackage]（无角色→事务空转）+ mockk calibrator。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupImporterCalibrationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }
    private lateinit var db: AppDatabase
    private lateinit var calibrator: RelationshipArchetypeCalibrator
    private lateinit var importer: BackupImporter

    @Before fun setup() {
        val ctx = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        calibrator = mockk(relaxed = true)
        importer = BackupImporter(
            appContext = ctx,
            appDatabase = db,
            characterDao = mockk(relaxed = true),
            conversationDao = mockk(relaxed = true),
            messageDao = mockk(relaxed = true),
            milestoneDao = mockk(relaxed = true),
            petDao = mockk(relaxed = true),
            currencyDao = mockk(relaxed = true),
            giftDao = mockk(relaxed = true),
            redPacketDao = mockk(relaxed = true),
            meetingAppointmentDao = mockk(relaxed = true),
            redeemCodeUsageDao = mockk(relaxed = true),
            momentDao = mockk(relaxed = true),
            diaryDao = mockk(relaxed = true),
            scheduleDao = mockk(relaxed = true),
            storyDao = mockk(relaxed = true),
            notificationTemplateDao = mockk(relaxed = true),
            customStickerDao = mockk(relaxed = true),
            userProfileDao = mockk(relaxed = true),
            worldBookDao = mockk(relaxed = true),
            worldDao = mockk(relaxed = true),
            worldSocialDao = mockk(relaxed = true),
            worldNativeDao = mockk(relaxed = true),
            worldMemoryDao = mockk(relaxed = true),
            worldUserResidentDao = mockk(relaxed = true),
            offlineMeetingMemoryDao = mockk(relaxed = true),
            promiseDao = mockk(relaxed = true),
            userStoryTemplateDao = mockk(relaxed = true),
            ourDayDao = mockk(relaxed = true),
            settingsRepo = mockk(relaxed = true),
            mediaRestorer = BackupMediaRestorer(ctx), // 真件（只落盘不碰库）：空包无媒体条目 → 一次都不会被调用
            archetypeCalibrator = calibrator,
        )
    }

    @After fun tearDown() = db.close()

    private fun emptyPackageJson(): String = json.encodeToString(BackupPackage.serializer(), BackupPackage())

    @Test fun `import 成功 - 触发一次 recalibrateAll`() = runBlocking {
        val result = importer.import(emptyPackageJson())
        assert(result is ImportResult.Success) { "空包导入应成功，实=$result" }
        coVerify(exactly = 1) { calibrator.recalibrateAll("backup-import") }
    }

    @Test fun `import 失败 - 不触发 recalibrateAll`() = runBlocking {
        val result = importer.import("这不是有效的备份json")
        assert(result is ImportResult.Error)
        coVerify(exactly = 0) { calibrator.recalibrateAll(any()) }
    }

    @Test fun `importArchive 成功 - 触发一次 recalibrateAll`() = runBlocking {
        val bos = ByteArrayOutputStream()
        bos.use { BackupArchive.writeTo(it, emptyPackageJson(), emptyMap()) }
        val result = importer.importArchive(BackupByteSource.fromBytes(bos.toByteArray()))
        assert(result is ImportResult.Success) { "空 zip 导入应成功，实=$result" }
        coVerify(exactly = 1) { calibrator.recalibrateAll("backup-import") }
    }

    @Test fun `importArchive 解析失败 - 不触发 recalibrateAll`() = runBlocking {
        val bos = ByteArrayOutputStream()
        bos.use { BackupArchive.writeTo(it, "坏掉的manifest不是json", emptyMap()) }
        val result = importer.importArchive(BackupByteSource.fromBytes(bos.toByteArray()))
        assert(result is ImportResult.Error)
        coVerify(exactly = 0) { calibrator.recalibrateAll(any()) }
    }
}
