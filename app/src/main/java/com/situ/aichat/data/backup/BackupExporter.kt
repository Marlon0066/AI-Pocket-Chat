package com.situ.aichat.data.backup

import android.content.Context
import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.local.dao.CustomStickerDao
import com.situ.aichat.data.local.dao.DiaryDao
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.dao.MomentDao
import com.situ.aichat.data.local.dao.NotificationTemplateDao
import com.situ.aichat.data.local.dao.PetDao
import com.situ.aichat.data.local.dao.MeetingAppointmentDao
import com.situ.aichat.data.local.dao.RedPacketDao
import com.situ.aichat.data.local.dao.RedeemCodeUsageDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.StoryDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.PromptModuleService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全量备份导出器（只读侧；从 [BackupService] 抽出·文件瘦身刀4）。流式收集结构化数据 + 媒体「key→绝对路径」表，
 * 再交 [BackupArchive.writeTo] 逐文件流式写 zip（杜绝大媒体 OOM）。导出纯读、绝不改任何余额/库数据。
 * 公开入口 [exportTo]/[exportAtomic] 由 BackupService 薄委托转发（公开 API 不变）。
 */
@Singleton
class BackupExporter @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val characterDao: CharacterDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val milestoneDao: MilestoneDao,
    private val petDao: PetDao,
    private val currencyDao: CurrencyDao,
    private val giftDao: GiftDao,
    private val redPacketDao: RedPacketDao,
    private val meetingAppointmentDao: MeetingAppointmentDao,
    private val redeemCodeUsageDao: RedeemCodeUsageDao,
    private val momentDao: MomentDao,
    private val diaryDao: DiaryDao,
    private val scheduleDao: ScheduleDao,
    private val storyDao: StoryDao,
    private val notificationTemplateDao: NotificationTemplateDao,
    private val customStickerDao: CustomStickerDao,
    private val userProfileDao: UserProfileDao,
    private val worldBookDao: com.situ.aichat.data.local.dao.WorldBookDao,
    private val worldDao: com.situ.aichat.data.local.dao.WorldDao,
    private val worldSocialDao: com.situ.aichat.data.local.dao.WorldSocialDao,
    private val worldNativeDao: com.situ.aichat.data.local.dao.WorldNativeDao,
    private val worldMemoryDao: com.situ.aichat.data.local.dao.WorldMemoryDao,
    private val worldUserResidentDao: com.situ.aichat.data.local.dao.WorldUserResidentDao,
    private val offlineMeetingMemoryDao: com.situ.aichat.data.local.dao.OfflineMeetingMemoryDao,
    private val promiseDao: com.situ.aichat.data.local.dao.PromiseDao,
    private val userStoryTemplateDao: com.situ.aichat.data.local.dao.UserStoryTemplateDao,
    private val ourDayDao: com.situ.aichat.data.local.dao.OurDayDao,
    private val settingsRepo: SettingsRepository,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = true
    }

    /**
     * 流式导出全量备份到 [out]（**不在内存里建整包**：先收集结构化数据 + 媒体「key→绝对路径」表，再逐个媒体文件从
     * 磁盘流式写入 zip，峰值内存≈manifest 文本 + 单个文件拷贝缓冲，杜绝大媒体备份 OOM——13.6c）。[includeMedia]=false →
     * 文本备份（不带媒体字节，archiveKey 留空），但 DIY 礼物图例外（无条件带，对齐 iOS）。
     *
     * **[out] 的所有权归调用方**：用 `out.use { exportTo(it) }` 包裹（手动导出 / 自动备份 worker 都这样调），本函数不 close。
     *
     * [onProgress]（P1-7 确定性进度·只读观测点·约定不抛）：COLLECT = 逐角色 + [GLOBAL_SEGMENT_COUNT] 个全局段；
     * WRITE_MEDIA 由 [BackupArchive.writeTo] 透传。段收集顺序 / 序列化 / 钱字段零变。
     */
    @OptIn(ExperimentalSerializationApi::class) // encodeToStream（卷 A·J7：manifest 直写 zip 流）
    suspend fun exportTo(
        out: OutputStream,
        includeMedia: Boolean = true,
        onProgress: ((BackupProgress) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val media = LinkedHashMap<String, String>() // key → 媒体绝对路径（真正读盘推迟到流式写入时）
        val settings = settingsRepo.getAppSettings()

        val characters = characterDao.getAll()
        val collectTotal = characters.size + GLOBAL_SEGMENT_COUNT
        var collected = 0
        fun step() {
            collected++
            onProgress?.invoke(BackupProgress(BackupProgress.Stage.COLLECT, collected, collectTotal))
        }

        val charData = characters.map { c ->
            collectCharacter(c, includeMedia, settings.characterPromptModulesJSON, media).also { step() }
        }

        val moments = collectMoments(includeMedia, media).also { step() }
        val diaryEntries = collectDiary(includeMedia, media).also { step() }
        val monthlyReviews = diaryDao.getAllMonthlyReviews().map { it.toExport() }.ifEmpty { null }.also { step() }
        val stories = collectStories().also { step() }
        val gifts = collectGifts(media).also { step() }
        val redPackets = redPacketDao.getAllRecords().map { it.toExport() }.ifEmpty { null }.also { step() }
        val stickers = collectStickers(includeMedia, media).also { step() }
        val redeemCodeUsages = redeemCodeUsageDao.getAll().map { it.toExport() }.ifEmpty { null }.also { step() }
        val currencyTransactions = currencyDao.getAllTransactions().map { it.toExport() }.ifEmpty { null }.also { step() }
        val userWallet = currencyDao.getUserWallet()?.toExport().also { step() }
        val userProfile = collectUserProfile(includeMedia, media).also { step() }
        val futureAppointments = meetingAppointmentDao.getAllAppointments().map { it.toExport() }.ifEmpty { null }.also { step() }
        val worldBooks = collectWorldBooks(worldBookDao).also { step() }
        val world = collectWorld(worldDao, worldSocialDao, worldNativeDao, worldMemoryDao, worldUserResidentDao).also { step() }
        // 梦剧场 B 部：见面回忆表全局段（图纸 §3.2·顶层·恢复靠 characterUuid 幽灵过滤）。
        val offlineMeetingMemories = collectOfflineMeetingMemories(offlineMeetingMemoryDao).also { step() }
        // 记忆改造一期·部件①：承诺账本全局段（图纸 §3.1·顶层·恢复靠 characterUuid 幽灵过滤）。
        val promises = collectPromises(promiseDao).also { step() }
        // 图纸四：故事「我的模板」全局段（顶层·无幽灵过滤·整段搬回）。
        val userStoryTemplates = collectUserStoryTemplates(userStoryTemplateDao).also { step() }
        // 「我们的日子」卷一：our_days 全局段（图纸 §3.5·顶层·剥 embedding·恢复靠 characterUuid 幽灵过滤）。
        val ourDays = collectOurDays(ourDayDao).also { step() }

        val pkg = BackupPackage(
            manifest = BackupManifest(
                version = 2,
                appVersion = appVersionName(),
                exportDate = System.currentTimeMillis(),
                includesMedia = includeMedia,
                mediaCount = media.size,
                characterSummaries = charData.map { d ->
                    CharacterSummary(
                        name = d.character.name,
                        uuid = d.character.uuid,
                        messageCount = d.conversations?.sumOf { it.messages?.size ?: 0 } ?: 0,
                    )
                },
            ),
            characters = charData,
            userWallet = userWallet,
            userProfile = userProfile,
            appSettings = settings,
            moments = moments,
            diaryEntries = diaryEntries,
            monthlyReviews = monthlyReviews,
            stories = stories,
            gifts = gifts,
            redPackets = redPackets,
            stickers = stickers,
            redeemCodeUsages = redeemCodeUsages,
            currencyTransactions = currencyTransactions,
            futureAppointments = futureAppointments,
            worldBooks = worldBooks,
            world = world,
            offlineMeetingMemories = offlineMeetingMemories,
            promises = promises,
            userStoryTemplates = userStoryTemplates,
            ourDays = ourDays,
        )
        // 卷 A·J7：manifest 直接编码进 zip 条目流——不再先攒完整 String 再复制一份 UTF-8 字节
        //（含 embedding 的大库那两份复制能到几十 MB）。序列化配置/字段/输出内容零变（同 Json 实例、
        // encodeToStream 与 encodeToString 同配置输出逐字节一致·BackupRoundTripTest 锁）。
        BackupArchive.writeTo(
            out,
            manifestWriter = { json.encodeToStream(BackupPackage.serializer(), pkg, it) },
            mediaPaths = media,
        ) { done, total ->
            onProgress?.invoke(BackupProgress(BackupProgress.Stage.WRITE_MEDIA, done, total))
        }
    }

    /**
     * 原子导出（P1-7）：先把整包写进 **cache 临时文件**，成功后整文件流拷贝到 SAF 目标；任一步失败或被取消则
     * **删除目标文档**——SAF URI 无法 temp+rename（§10 修正#2），故仿 [com.situ.aichat.work.AutoBackupFolder.writeBackup]
     * 的失败删除 + 前置 cache 临时段（行为对标 iOS CharacterBackupExportService.swift:147-149 的
     * temporaryDirectory + `.atomic` 写）。CreateDocument 在用户**选名当刻**即建 0 字节文档 → 失败删除必须覆盖
     * 「SAF 流从未打开」分支，否则留 0 字节残留。取消（用户退出备份页）同样先删目标再重抛。
     *
     * 进程死于「临时→SAF 拷贝」窗口仍可能残留半截文件（SAF 无两阶段提交）——窗口已从全程缩到纯拷贝段，
     * FGS 保活再压低概率，接受并入真机批验证项。
     *
     * @param openOut 打开 SAF 目标输出流（调用方持 Uri）；返回 null 视为失败。
     * @param deleteTarget 删除 SAF 目标文档（[android.provider.DocumentsContract.deleteDocument]）；
     *   在 [NonCancellable] 下调用，取消路径也保证执行。
     * @return true=成功；false=失败（目标已清理，临时文件已删）。
     */
    suspend fun exportAtomic(
        includeMedia: Boolean,
        onProgress: ((BackupProgress) -> Unit)? = null,
        openOut: suspend () -> OutputStream?,
        deleteTarget: () -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        // 复核修（LOW）：先扫上次进程死留下的孤儿临时文件（含媒体导出可达数百 MB，无人清会挤占 cache、
        // 放大下面 createTempFile 的 ENOSPC 面）。busy 守卫保证同刻单导出 → 此刻存在的同前缀文件必为孤儿。
        appContext.cacheDir.listFiles { f -> f.name.startsWith(TMP_PREFIX) && f.name.endsWith(TMP_SUFFIX) }
            ?.forEach { it.delete() }
        var tmp: File? = null
        try {
            // 复核修（MED）：createTempFile 必须在 try 内——cache 满/不可写抛 IOException 时走下方 catch
            // （删 0 字节目标 + 返回 false=优雅失败），而不是穿透 VM 无 handler 崩进程。
            val t = File.createTempFile(TMP_PREFIX, TMP_SUFFIX, appContext.cacheDir)
            tmp = t
            t.outputStream().use { exportTo(it, includeMedia, onProgress) }
            val out = openOut() ?: error("无法打开导出目标输出流")
            val totalKb = (t.length() / 1024).toInt()
            out.use { o ->
                t.inputStream().use { ins ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        ensureActive() // 退页取消能在拷贝中途及时停（无挂起点的纯阻塞循环不自检）
                        val n = ins.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        copied += n
                        onProgress?.invoke(BackupProgress(BackupProgress.Stage.COPY, (copied / 1024).toInt(), totalKb))
                    }
                }
            }
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { deleteTarget() } }
            throw e // 批1 惯例：CancellationException 必须重抛，绝不吞
        } catch (e: Exception) {
            Log.w(TAG_EXPORT, "导出失败，已清理目标文件", e)
            runCatching { deleteTarget() }
            false
        } finally {
            tmp?.delete()
        }
    }

    private suspend fun collectCharacter(
        c: CharacterEntity,
        includeMedia: Boolean,
        characterModulesJson: String,
        media: MutableMap<String, String>,
    ): CharacterBackupData {
        val conversations = conversationDao.getByCharacter(c.uuid).map { conv ->
            conv.toExport(messageDao.getAllForConversation(conv.uuid).map { it.toExport(includeMedia, media) })
        }
        val milestones = milestoneDao.getForCharacter(c.uuid).map { it.toExport() }
        val moduleOverride = PromptModuleService.loadCharacterModules(c.uuid, characterModulesJson)
        val avatarKey = if (includeMedia) {
            addMedia(media, "${BackupArchive.MEDIA_PREFIX}avatars/${c.uuid}.jpg", c.avatarPath)
        } else {
            null
        }
        val wallpaperKey = if (includeMedia) {
            addMedia(media, "${BackupArchive.MEDIA_PREFIX}wallpapers/${c.uuid}.jpg", c.chatWallpaperPath)
        } else {
            null
        }
        val pet = petDao.getForCharacter(c.uuid)?.toExport()
        val wallet = currencyDao.getCharacterWallet(c.uuid)?.toExport()
        val schedules = scheduleDao.schedulesForCharacter(c.uuid).map { s ->
            s.toExport(scheduleDao.eventsForSchedule(s.uuid).map { it.toExport() })
        }
        val notifTemplates = notificationTemplateDao.allForCharacter(c.uuid).map { it.toExport() }
        return CharacterBackupData(
            character = c.toExport(avatarKey, wallpaperKey),
            conversations = conversations.ifEmpty { null },
            milestones = milestones.ifEmpty { null },
            promptModules = moduleOverride,
            pet = pet,
            wallet = wallet,
            schedules = schedules.ifEmpty { null },
            notificationTemplates = notifTemplates.ifEmpty { null },
        )
    }

    private suspend fun collectMoments(includeMedia: Boolean, media: MutableMap<String, String>): MomentsExport? {
        val posts = momentDao.getAllPosts()
        if (posts.isEmpty()) return null
        val postExports = posts.map { p ->
            val keys = if (includeMedia) {
                decodePaths(p.imagePathsJson).mapIndexedNotNull { i, path ->
                    addMedia(media, "${BackupArchive.MEDIA_PREFIX}moment/${p.uuid}_$i.jpg", path)
                }.ifEmpty { null }
            } else {
                null
            }
            p.toExport(keys)
        }
        val comments = momentDao.getAllComments().map { it.toExport() }
        val likes = momentDao.getAllLikes().map { it.toExport() }
        return MomentsExport(
            posts = postExports,
            comments = comments.ifEmpty { null },
            likes = likes.ifEmpty { null },
        )
    }

    private suspend fun collectDiary(includeMedia: Boolean, media: MutableMap<String, String>): List<DiaryEntryExport>? {
        val entries = diaryDao.getAllEntries()
        if (entries.isEmpty()) return null
        val commentsByEntry = diaryDao.getAllComments().groupBy { it.entryUuid }
        val reactionsByEntry = diaryDao.getAllReactions().groupBy { it.entryUuid }
        return entries.map { e ->
            val keys = if (includeMedia) {
                decodePaths(e.imagePathsJson).mapIndexedNotNull { i, path ->
                    addMedia(media, "${BackupArchive.MEDIA_PREFIX}diary/${e.uuid}_$i.jpg", path)
                }.ifEmpty { null }
            } else {
                null
            }
            e.toExport(
                keys,
                commentsByEntry[e.uuid]?.map { it.toExport() },
                reactionsByEntry[e.uuid]?.map { it.toExport() },
            )
        }
    }

    private suspend fun collectStories(): List<StoryExport>? {
        val stories = storyDao.getAllStories()
        if (stories.isEmpty()) return null
        val chaptersByStory = storyDao.getAllChapters().groupBy { it.storyId }
        val rolesByStory = storyDao.getAllRoles().groupBy { it.storyId }
        return stories.map { s ->
            s.toExport(
                chaptersByStory[s.id]?.map { it.toExport() },
                rolesByStory[s.id]?.map { it.toExport() },
            )
        }
    }

    private suspend fun collectGifts(media: MutableMap<String, String>): List<GiftRecordExport>? {
        val gifts = giftDao.getAllRecords()
        if (gifts.isEmpty()) return null
        return gifts.map { g ->
            // DIY 礼物图无条件带（对齐 iOS：diyImageData 不受 includeMedia 门控）。
            val key = addMedia(media, "${BackupArchive.MEDIA_PREFIX}gift/${g.uuid}.jpg", g.diyImagePath)
            g.toExport(key)
        }
    }

    private suspend fun collectStickers(includeMedia: Boolean, media: MutableMap<String, String>): List<CustomStickerExport>? {
        val stickers = customStickerDao.getAllOrderByCreatedAtAsc()
        if (stickers.isEmpty()) return null
        return stickers.map { st ->
            val ext = if (st.isAnimated) "gif" else "png"
            val key = if (includeMedia) {
                addMedia(media, "${BackupArchive.MEDIA_PREFIX}stickers/${st.stickerUuid}.$ext", st.imagePath)
            } else {
                null
            }
            st.toExport(key)
        }
    }

    private suspend fun collectUserProfile(includeMedia: Boolean, media: MutableMap<String, String>): UserProfileExport? {
        val p = userProfileDao.get() ?: return null
        val key = if (includeMedia) {
            addMedia(media, "${BackupArchive.MEDIA_PREFIX}avatars/user_profile.jpg", p.avatarPath)
        } else {
            null
        }
        return UserProfileExport(
            nickname = p.nickname,
            bio = p.bio,
            avatarArchiveKey = key,
            cityName = p.cityName,
            cityLatitude = p.cityLatitude,
            cityLongitude = p.cityLongitude,
            birthday = p.birthday,
            companionPreference = p.companionPreference,
        )
    }

    /** 登记媒体绝对路径到 [media]（key=zip 相对键；字节推迟到流式写入时才读盘）；路径空 / 文件不存在 → 不登记，返回 null。 */
    private fun addMedia(media: MutableMap<String, String>, key: String, absolutePath: String?): String? {
        if (absolutePath.isNullOrEmpty()) return null
        if (!File(absolutePath).exists()) return null
        media[key] = absolutePath
        return key
    }

    /** 解码 imagePathsJson（绝对路径 JSON 数组）；空串/损坏 → 空列表。 */
    private fun decodePaths(jsonStr: String): List<String> {
        if (jsonStr.isEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(jsonStr) }.getOrDefault(emptyList())
    }

    private fun appVersionName(): String =
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }
            .getOrNull() ?: ""

    private companion object {
        /** 导出 COLLECT 进度的全局段数（朋友圈/日记/月度回顾/故事/礼物/红包/贴纸/兑换码/流水台账/用户钱包/用户资料/约定见面/世界书/世界/见面回忆/承诺账本/我的模板/我们的日子）。 */
        const val GLOBAL_SEGMENT_COUNT = 18

        const val TAG_EXPORT = "BackupExport"

        /** [exportAtomic] 的 cache 临时文件名前后缀（开跑先扫同前缀孤儿）。 */
        const val TMP_PREFIX = "backup_export_"
        const val TMP_SUFFIX = ".tmp"
    }
}
