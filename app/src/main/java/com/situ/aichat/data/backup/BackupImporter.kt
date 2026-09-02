package com.situ.aichat.data.backup

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
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
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.local.entity.UserWalletEntity
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.PromptModuleService
import com.situ.aichat.util.AvatarStore
import com.situ.aichat.util.WallpaperStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全量备份导入/恢复器（写侧；从 [BackupService] 抽出·文件瘦身刀5）。事务化全成或全回滚的导入：旧明文 .json
 * 覆盖式（[import]）+ 新 zip 全量恢复 + 冲突预览/三策略（覆盖 / 创建副本 / 跳过·[previewArchive]/[importArchive]）。
 *
 * 💰 钱还原 1:1 镜像 iOS（13.6d 已 SHIP）：用户钱包原地改三字段 max(0) 钳位、角色钱包据快照新建不钳位、
 * 礼物/红包纯历史按 uuid 覆盖不动余额、流水台账原 uuid REPLACE 搬回（幂等防重发薪/重扣租）。本刀只搬不改:
 * 余额恢复语义、事务边界、清理/回滚路径一字未动。公开入口由 BackupService 薄委托转发（公开 API 不变）。
 */
@Singleton
class BackupImporter @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val appDatabase: AppDatabase,
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
    // 卷 A：媒体逐条读→重存→弃（含 zip 键路由与跳过键收集，从本类原样搬出）。
    private val mediaRestorer: BackupMediaRestorer,
    // 成长原型校准（图纸 §3.3 入口③）：导入成功后无条件全量重算（老包无 archetypeId → null → 此处补算）。
    private val archetypeCalibrator: com.situ.aichat.prompt.growth.RelationshipArchetypeCalibrator,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = true
    }

    // ════════════════════════════════ IMPORT (旧 .json 4 段；13.6b 全量重写) ════════════════════════════════

    suspend fun import(jsonStr: String): ImportResult = withContext(Dispatchers.IO) {
        val pkg = runCatching { json.decodeFromString(BackupPackage.serializer(), jsonStr) }
            .getOrElse { return@withContext ImportResult.Error("解析失败：文件可能不是有效的备份") }
        if (pkg.manifest.version > 1) {
            return@withContext ImportResult.Error("不支持的备份版本 ${pkg.manifest.version}，请更新 App")
        }

        // 数据保命（P12.4）：覆盖式导入「先删旧角色再插新」，过去无事务——一旦中途失败/被杀，旧角色连同其
        // 全部会话/消息/里程碑就永久丢了。改为：① DB 改动全部包进 Room 事务（全成或全回滚，对齐 iOS 单次
        // context.save 的原子语义）；② 新头像先落盘、失败时清理孤儿；旧头像/提示词模块（DataStore，事务外）
        // 留到提交成功后再删/写，保证回滚时原有数据与文件原样不动；③ 任何异常转 ImportResult.Error 不外抛。
        // 按 uuid 去重（手改/损坏的备份可能含重复 uuid；保留最后一条 = 覆盖语义）。否则同 uuid 第二次会把刚插入
        // 的新头像当「旧头像」误删、并多落一份孤儿头像文件。app 自身导出受主键约束不会重复，此为防御。
        val characters = pkg.characters.associateBy { it.character.uuid }.values.toList()

        val newAvatarByUuid = HashMap<String, String>()
        characters.forEach { cd ->
            cd.character.avatarData
                ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
                ?.let { AvatarStore.saveBytes(appContext, it) }
                ?.let { newAvatarByUuid[cd.character.uuid] = it }
        }

        val oldAvatarsToDelete = ArrayList<String>()
        val oldWallpapersToDelete = ArrayList<String>() // chunk1b：被覆盖角色的旧壁纸文件（旧 .json 无新壁纸，仅清旧防孤儿）
        val outcome = runCatching {
            appDatabase.withTransaction {
                var importedChars = 0
                var importedMsgs = 0
                for (cd in characters) {
                    val uuid = cd.character.uuid
                    // 覆盖式：删旧角色（FK 级联清会话/消息/里程碑）；旧头像文件留到事务提交后再删。
                    characterDao.getByUuid(uuid)?.let { old ->
                        old.avatarPath?.let { oldAvatarsToDelete.add(it) }
                        old.chatWallpaperPath?.let { oldWallpapersToDelete.add(it) }
                        characterDao.deleteByUuid(uuid)
                    }
                    characterDao.upsert(cd.character.toEntity(newAvatarByUuid[uuid], null))
                    importedChars++
                    cd.conversations?.forEach { conv ->
                        conversationDao.upsert(conv.toEntity(uuid))
                        conv.messages?.forEach { m ->
                            messageDao.upsert(m.toEntity(conv.uuid))
                            importedMsgs++
                        }
                    }
                    cd.milestones?.forEach { milestoneDao.upsert(it.toEntity(uuid)) }
                }
                importedChars to importedMsgs
            }
        }

        outcome.fold(
            onSuccess = { (chars, msgs) ->
                // 事务已提交：删旧头像/旧壁纸 + 写提示词模块覆盖（均为事务外副作用，提交后再做才安全）。
                oldAvatarsToDelete.forEach { AvatarStore.delete(it) }
                oldWallpapersToDelete.forEach { WallpaperStore.delete(it) }
                applyPromptModuleOverrides(characters)
                // 成长原型校准（图纸 §3.3 入口③）：导入已成功，校准失败不改判导入结果（下次词表升级仍自愈）。
                runCatching { archetypeCalibrator.recalibrateAll("backup-import") }
                // 旧 .json 无冲突预览 → 全部按「覆盖式新导入」计入 imported。
                ImportResult.Success(imported = chars, overwritten = 0, duplicated = 0, skipped = 0, messages = msgs)
            },
            onFailure = { e ->
                // 事务已回滚：原有角色/会话/消息/里程碑/旧头像全部原样保留；仅清理刚落盘的新头像孤儿文件。
                newAvatarByUuid.values.forEach { AvatarStore.delete(it) }
                ImportResult.Error("导入失败：${e.message ?: "未知错误"}（已回滚，原有数据未受影响）")
            },
        )
    }

    /** 应用每个角色的提示词模块覆盖（DataStore，非 Room 事务；仅在导入事务提交成功后调用）。 */
    private suspend fun applyPromptModuleOverrides(characters: List<CharacterBackupData>) {
        characters.forEach { cd ->
            cd.promptModules?.let { mods ->
                val newJson = PromptModuleService.setCharacterModules(
                    cd.character.uuid,
                    mods,
                    settingsRepo.getAppSettings().characterPromptModulesJSON,
                )
                settingsRepo.setCharacterPromptModulesJSON(newJson)
            }
        }
    }

    // ════════════════════════════════ IMPORT ARCHIVE (13.6b：zip 全量恢复 + 冲突预览/三策略) ════════════════════════════════

    /**
     * 预览段（**不写库**）：遍一只解 manifest（连字符串都不物化）→ 逐角色构建预览行（按 **uuid** 比对本地的冲突
     * 标记）；遍二只把**头像那几条**媒体读进来做缩略图，其余条目连字节都不读（预览段它们全是白载 —— 大包内存
     * 就炸在这）。1:1 iOS `previewBackup`/`checkConflicts`：冲突 = 本地已存在同 uuid 角色（非按名字）。
     * 非 zip / 损坏 / 版本过高 → 返回 null（调用方走旧 `.json` 直接导入，无冲突预览）。
     */
    @OptIn(ExperimentalSerializationApi::class) // decodeFromStream（卷 A·J2：manifest 条目流直喂解析器）
    suspend fun previewArchive(source: BackupByteSource): BackupPreview? = withContext(Dispatchers.IO) {
        val pkg = try {
            BackupArchive.consumeManifest(source::open) { json.decodeFromStream(BackupPackage.serializer(), it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // 损坏/非备份：预览段一律安静退回 null，由 importArchive 给出精确错误文案
        } ?: return@withContext null
        if (pkg.manifest.version > SUPPORTED_VERSION) return@withContext null
        val summaryByUuid = pkg.manifest.characterSummaries.associateBy { it.uuid }
        val characters = pkg.characters.associateBy { it.character.uuid }.values // 去重，与导入一致
        val mediaBytes = mediaRestorer.readMediaBytes(source, characters.mapNotNull { it.character.avatarArchiveKey }.toSet())
        val rows = characters
            .map { cd ->
                val uuid = cd.character.uuid
                val existing = characterDao.getByUuid(uuid)
                val msgCount = summaryByUuid[uuid]?.messageCount
                    ?: (cd.conversations?.sumOf { it.messages?.size ?: 0 } ?: 0)
                CharacterPreviewRow(
                    uuid = uuid,
                    name = cd.character.name,
                    messageCount = msgCount,
                    avatarBytes = cd.character.avatarArchiveKey?.let { mediaBytes[it] },
                    hasConflict = existing != null,
                    existingName = existing?.name,
                )
            }
        BackupPreview(
            characters = rows,
            mediaCount = pkg.manifest.mediaCount,
            hasGlobalData = pkg.moments != null || pkg.diaryEntries != null || pkg.monthlyReviews != null ||
                pkg.stories != null ||
                pkg.gifts != null || pkg.redPackets != null || pkg.stickers != null ||
                pkg.redeemCodeUsages != null || pkg.currencyTransactions != null ||
                pkg.futureAppointments != null || pkg.worldBooks != null || pkg.world != null ||
                pkg.promises != null || pkg.userStoryTemplates != null || pkg.ourDays != null ||
                pkg.userWallet != null || pkg.userProfile != null || pkg.appSettings != null,
        )
    }

    /**
     * 从可重开的字节源**两遍流式**恢复全量备份（事务化全成或全回滚）：遍一只解 manifest、遍二逐条媒体读→重存→弃，
     * 峰值内存 ≈ 单个媒体文件（旧路径整包 + 全部媒体一次性入内存，大备份必 OOM 且被误报成「解析失败」）。
     * 非 zip → 回退旧 `.json` 文本解析（[import]，带 32MB 安全帽）。
     *
     * **逐角色策略 [strategies]**（uuid→策略；不在 map = 无冲突新导入）：覆盖=删旧 uuid 重插 / 跳过=不碰 / 创建副本=
     * 整条私有子树主键重映射为新 uuid（[remapCharacterSubtree]，原角色原封不动）。**顶层全局段**（朋友圈/日记/故事/
     * 💰礼物/💰红包/贴纸/💰用户钱包/用户资料/设置）按 13.6a 已签字设计**整体恢复一次，不随逐角色策略走**（创建副本不
     * 另得重映射的全局段 = 已登记 LOW；与 iOS 顶层 userWallet 无条件恢复一致）。
     *
     * 💰 钱还原 1:1 镜像 iOS（13.6d 独立复核 SHIP）：① 用户钱包=取现有原地改三字段、`max(0)` 钳位、保留本地身份；
     * ② 角色钱包=据快照新建（`?? 0` **不钳位**）；③ 礼物/红包=纯历史按 uuid 覆盖插入、**绝不动钱包余额**；④ 流水台账=
     * 原 uuid/relatedEntityId 整表 REPLACE 搬回（R2 修：发薪/房租等靠 relatedEntityId 幂等台账判「是否已发」，不还原则
     * 换机/重装后重发工资/重扣房租；**不动余额**，仅恢复幂等记录）。
     * **创建副本**只把角色钱包快照换个主键复制一份（复用同一 [CharacterWalletExport.toEntity] 快照语义，**零新增钱算路径**）；
     * 流水作为顶层全局段不随创建副本重映射（=礼物/红包同档·已登记 LOW）。
     */
    @OptIn(ExperimentalSerializationApi::class) // decodeFromStream（卷 A·J2：manifest 条目流直喂解析器）
    suspend fun importArchive(
        source: BackupByteSource,
        strategies: Map<String, ImportStrategy> = emptyMap(),
        onProgress: ((BackupProgress) -> Unit)? = null,
    ): ImportResult = withContext(Dispatchers.IO) {
        // 遍一：只把 manifest 那一条条目的有界流交给解析器（整包字节从不物化）。
        val pkg = try {
            BackupArchive.consumeManifest(source::open) { json.decodeFromStream(BackupPackage.serializer(), it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 打得开 zip、找得到 manifest，但 JSON 解不出 = 备份损坏；绝不误当成「不是 zip」滑进旧 .json 回退路。
            return@withContext ImportResult.Error("解析失败：备份文件损坏")
        }
        if (pkg == null) return@withContext importLegacyJson(source)
        if (pkg.manifest.version > SUPPORTED_VERSION) {
            return@withContext ImportResult.Error("不支持的备份版本 ${pkg.manifest.version}，请更新 App")
        }

        // 按 uuid 去重（防损坏备份重复 uuid；保留最后一条 = 覆盖语义），与预览一致。
        val characters = pkg.characters.associateBy { it.character.uuid }.values.toList()

        // 1) 预存媒体（事务外，文件非事务）：zip 内字节 → 各 Store 重存 → archiveKey → 新绝对路径。回滚时删这些新文件。
        //    跳过策略的角色其私有媒体（头像/消息图音）不重存，避免产生孤儿文件；全局段媒体始终重存。
        val skipKeys = HashSet<String>()
        for (cd in characters) {
            if (strategies[cd.character.uuid] == ImportStrategy.SKIP) mediaRestorer.collectCharacterMediaKeys(cd, skipKeys)
        }
        val newPathByKey = HashMap<String, String>()
        val newMediaFiles = ArrayList<String>()
        // E1#2：媒体预存 + 事务全程包一层 try——用户在 RESTORE_MEDIA 段系统返回会取消 viewModelScope（进而取消本
        // 协程），取消若落在「媒体已重存、事务未提交」窗口，旧逻辑（仅事务 onFailure 清理）漏清已落盘新媒体 → 永久孤儿。
        // 这里 catch 取消/异常统一清 newMediaFiles，并重抛 CancellationException（结构化并发，绝不吞）。
        try {
        // 遍二：逐条读→重存→弃。条目推进失败（zip 截断/中央目录损坏）→ 中止整个导入：此刻仍在事务前，
        // 清掉已落盘的新媒体后如实报错，库零写入。
        val mediaFailed = try {
            mediaRestorer.restoreMedia(source, skipKeys, pkg.manifest.mediaCount, newPathByKey, newMediaFiles, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            newMediaFiles.forEach { runCatching { File(it).delete() } }
            return@withContext ImportResult.Error("导入失败：${e.message ?: "未知错误"}（已回滚，原有数据未受影响）")
        }
        if (mediaFailed > 0) Log.w("BackupImport", "$mediaFailed 个媒体文件未能恢复，其余数据照常导入")

        val oldAvatars = ArrayList<String>()
        val oldWallpapers = ArrayList<String>() // chunk1b：被覆盖角色的旧壁纸文件（事务提交后删·防孤儿·仿 oldAvatars）
        val outcome = runCatching {
            appDatabase.withTransaction {
                var imported = 0
                var overwritten = 0
                var duplicated = 0
                var skipped = 0
                var importedMsgs = 0
                var processed = 0
                // ── 角色私有段（逐角色策略） ──
                // 进度回调在事务内调用——约定只做 StateFlow 赋值、绝不抛（抛出会回滚整个导入）。
                for (cd in characters) {
                    when (strategies[cd.character.uuid]) {
                        ImportStrategy.SKIP -> skipped++
                        ImportStrategy.DUPLICATE -> {
                            val sub = remapCharacterSubtree(
                                cd,
                                newCharUuid = UUID.randomUUID().toString(),
                                newPathByKey = newPathByKey,
                                nameSuffix = DUPLICATE_NAME_SUFFIX,
                                nextUuid = { UUID.randomUUID().toString() },
                            )
                            insertRemappedSubtree(sub)
                            importedMsgs += sub.messages.size
                            duplicated++
                        }
                        // OVERWRITE 或 null(无冲突新导入)：覆盖式插入（删旧 uuid + 原 uuid 重插）。
                        else -> {
                            // 计数按「是否实际覆盖了本地同 uuid 角色」归类，而非只看策略——覆盖预览后才出现同 uuid 的
                            // 陈旧预览/跨设备竞态（strategy=null 但本地已存在 → insertCharacterOriginal 会删旧重插=覆盖，
                            // 数据安全），使结果区「覆盖 N / 新导入 N」与实际落库一致。
                            val replacedLocal = characterDao.getByUuid(cd.character.uuid) != null
                            importedMsgs += insertCharacterOriginal(cd, newPathByKey, oldAvatars, oldWallpapers)
                            if (strategies[cd.character.uuid] == ImportStrategy.OVERWRITE || replacedLocal) overwritten++ else imported++
                        }
                    }
                    processed++
                    onProgress?.invoke(BackupProgress(BackupProgress.Stage.WRITE_DB, processed, characters.size))
                }
                // ── 顶层全局段（整体恢复一次，不随逐角色策略走 = 13.6a 已签字设计） ──
                restoreUserWallet(pkg.userWallet)
                restoreUserProfile(pkg.userProfile, newPathByKey)
                restoreMoments(pkg.moments, newPathByKey)
                restoreDiary(pkg.diaryEntries, newPathByKey)
                restoreMonthlyReviews(pkg.monthlyReviews)
                restoreStories(pkg.stories)
                pkg.gifts?.forEach { giftDao.insertOrReplace(it.toEntity(newPathByKey)) }
                pkg.redPackets?.forEach { redPacketDao.insertOrReplace(it.toEntity()) }
                pkg.stickers?.forEach { customStickerDao.insert(it.toEntity(newPathByKey)) }
                pkg.redeemCodeUsages?.let { list -> redeemCodeUsageDao.upsertAll(list.map { it.toEntity() }) }
                // 💰流水台账（R2）：随余额恢复幂等台账，防恢复后回前台重发工资/重扣房租。原 uuid REPLACE，
                // 不动钱包余额（余额已由 restoreUserWallet/角色钱包段恢复）。顶层全局段，不随逐角色策略重映射
                // （与礼物/红包记录同档·创建副本不另得重映射=已签字 LOW）。
                pkg.currencyTransactions?.let { list -> currencyDao.restoreTransactions(list.map { it.toEntity() }) }
                // 未来约定见面（全局段，整体恢复一次，不随逐角色策略走）。无钱路，按 uuid REPLACE 幂等。
                pkg.futureAppointments?.forEach { meetingAppointmentDao.insertOrReplace(it.toEntity()) }
                // WB6b 世界书 + W1 世界系统（全局段整体恢复一次·幂等）：绑定/参与者仅接到库中真实存在的角色
                // （副本不继承·同全局段档）。世界段幽灵 uuid 行跳过 / 招募指针置 null（见 WorldBackup.restoreWorld）。
                val existingUuids = characterDao.getAll().map { it.uuid }.toSet()
                restoreWorldBooks(worldBookDao, pkg.worldBooks, existingUuids)
                restoreWorld(worldDao, worldSocialDao, worldNativeDao, worldMemoryDao, worldUserResidentDao, pkg.world, existingUuids)
                // 梦剧场 B 部：见面回忆表（顶层全局段·characterUuid 幽灵行跳过·uuid REPLACE 幂等·图纸 §3.2）。
                restoreOfflineMeetingMemories(offlineMeetingMemoryDao, pkg.offlineMeetingMemories, existingUuids)
                // 记忆改造一期·部件①：承诺账本（顶层全局段·characterUuid 幽灵行跳过·uuid REPLACE 幂等·图纸 §3.1）。
                restorePromises(promiseDao, pkg.promises, existingUuids)
                // 图纸四：故事「我的模板」（顶层全局段·无幽灵过滤·uuid REPLACE 幂等·§3.2）。
                restoreUserStoryTemplates(userStoryTemplateDao, pkg.userStoryTemplates)
                // 「我们的日子」卷一：our_days（顶层全局段·characterUuid 幽灵行跳过·uuid REPLACE 幂等·embedding 落 null·图纸 §3.5）。
                restoreOurDays(ourDayDao, pkg.ourDays, existingUuids)
                ImportCounts(imported, overwritten, duplicated, skipped, importedMsgs)
            }
        }

        outcome.fold(
            onSuccess = { c ->
                oldAvatars.forEach { AvatarStore.delete(it) }
                oldWallpapers.forEach { WallpaperStore.delete(it) }
                // 全局设置恢复（DataStore，非 Room 事务；提交成功后做）。新 zip 带 appSettings → 整体覆盖式恢复
                // （含三块提示词模块 JSON，故无需再 merge 每角色覆盖）；无 appSettings 的备份回退旧的逐角色 merge。
                val settings = pkg.appSettings
                if (settings != null) settingsRepo.applyBackupSettings(settings) else applyPromptModuleOverrides(characters)
                // 成长原型校准（图纸 §3.3 入口③）：settings 恢复后全量重算；失败不改判导入结果。
                runCatching { archetypeCalibrator.recalibrateAll("backup-import") }
                ImportResult.Success(c.imported, c.overwritten, c.duplicated, c.skipped, c.messages, mediaFailed)
            },
            onFailure = { e ->
                if (e is CancellationException) throw e // 取消交外层 catch 统一清理+重抛（结构化并发，绝不吞）
                // 回滚：DB 原样保留；仅清理刚落盘的新媒体孤儿文件。
                newMediaFiles.forEach { runCatching { File(it).delete() } }
                ImportResult.Error("导入失败：${e.message ?: "未知错误"}（已回滚，原有数据未受影响）")
            },
        )
        } catch (e: CancellationException) {
            // 媒体重存段 / 事务中途被取消（用户退屏）：清掉已落盘新媒体孤儿，再重抛。
            newMediaFiles.forEach { runCatching { File(it).delete() } }
            throw e
        }
    }

    /** 覆盖式插入一个角色（原 uuid）：删旧角色（FK 级联清私有子树）+ 通知模板单独清 → 原 uuid 重插。返回写入消息数。 */
    private suspend fun insertCharacterOriginal(
        cd: CharacterBackupData,
        newPathByKey: Map<String, String>,
        oldAvatars: MutableList<String>,
        oldWallpapers: MutableList<String>,
    ): Int {
        val uuid = cd.character.uuid
        characterDao.getByUuid(uuid)?.let { old ->
            old.avatarPath?.let { oldAvatars.add(it) }
            old.chatWallpaperPath?.let { oldWallpapers.add(it) }
            characterDao.deleteByUuid(uuid)
        }
        notificationTemplateDao.deleteForCharacter(uuid)
        characterDao.upsert(
            cd.character.toEntity(
                cd.character.avatarArchiveKey?.let { newPathByKey[it] },
                cd.character.chatWallpaperArchiveKey?.let { newPathByKey[it] },
            ),
        )
        var msgs = 0
        cd.conversations?.forEach { conv ->
            conversationDao.upsert(conv.toEntity(uuid))
            conv.messages?.forEach { m ->
                messageDao.upsert(m.toEntity(conv.uuid, newPathByKey))
                msgs++
            }
        }
        cd.milestones?.forEach { milestoneDao.upsert(it.toEntity(uuid)) }
        cd.pet?.let { petDao.upsert(it.toEntity(uuid)) }
        cd.wallet?.let { currencyDao.insertCharacterWallet(it.toEntity(uuid)) }
        cd.schedules?.forEach { s ->
            scheduleDao.insertSchedule(s.toEntity(uuid))
            s.events?.let { evs -> scheduleDao.insertEvents(evs.map { it.toEntity(s.uuid) }) }
        }
        cd.notificationTemplates?.let { tpls -> notificationTemplateDao.insertAll(tpls.map { it.toEntity(uuid) }) }
        return msgs
    }

    /** 插入一条「创建副本」重映射后的私有子树（全新 uuid；不删旧角色）。 */
    private suspend fun insertRemappedSubtree(sub: RemappedSubtree) {
        characterDao.upsert(sub.character)
        sub.conversations.forEach { conversationDao.upsert(it) }
        sub.messages.forEach { messageDao.upsert(it) }
        sub.milestones.forEach { milestoneDao.upsert(it) }
        sub.pet?.let { petDao.upsert(it) }
        sub.wallet?.let { currencyDao.insertCharacterWallet(it) }
        sub.schedules.forEach { scheduleDao.insertSchedule(it) }
        if (sub.scheduleEvents.isNotEmpty()) scheduleDao.insertEvents(sub.scheduleEvents)
        if (sub.notificationTemplates.isNotEmpty()) notificationTemplateDao.insertAll(sub.notificationTemplates)
    }

    /**
     * 非 zip → 回退旧明文 `.json`（13.6 之前的备份格式，无媒体、实际只有几 MB）。
     *
     * **绝不再对任意大文件整读**：最多读到 [LEGACY_JSON_MAX_BYTES] 就收手判「不是备份文件」——用户误选了一个
     * 几百 MB 的随便什么文件时，旧代码会把它整个读进内存（本卷要根治的正是这种整读）。流打不开同判。
     */
    private suspend fun importLegacyJson(source: BackupByteSource): ImportResult {
        val text = source.readTextCapped(LEGACY_JSON_MAX_BYTES) ?: return ImportResult.Error("不是有效的备份文件")
        return import(text)
    }

    /** 💰 用户钱包：取现有原地改余额三字段（max(0) 钳位、保留本地 uuid/createdAt = 不让其他模块引用失效）；无则新建。 */
    private suspend fun restoreUserWallet(w: UserWalletExport?) {
        w ?: return
        val existing = currencyDao.getUserWallet()
        if (existing != null) {
            currencyDao.updateUserWallet(
                existing.copy(
                    coinBalance = w.coinBalance.coerceAtLeast(0),
                    totalEarned = w.totalEarned.coerceAtLeast(0),
                    totalSpent = w.totalSpent.coerceAtLeast(0),
                ),
            )
        } else {
            currencyDao.insertUserWallet(
                UserWalletEntity(
                    uuid = w.uuid,
                    createdAt = w.createdAt,
                    coinBalance = w.coinBalance.coerceAtLeast(0),
                    totalEarned = w.totalEarned.coerceAtLeast(0),
                    totalSpent = w.totalSpent.coerceAtLeast(0),
                ),
            )
        }
    }

    private suspend fun restoreUserProfile(p: UserProfileExport?, newPathByKey: Map<String, String>) {
        p ?: return
        userProfileDao.upsert(
            UserProfileEntity(
                id = 1,
                nickname = p.nickname,
                bio = p.bio,
                avatarPath = p.avatarArchiveKey?.let { newPathByKey[it] },
                cityName = p.cityName,
                cityLatitude = p.cityLatitude,
                cityLongitude = p.cityLongitude,
                birthday = p.birthday,
                companionPreference = p.companionPreference,
            ),
        )
    }

    private suspend fun restoreMoments(m: MomentsExport?, newPathByKey: Map<String, String>) {
        m ?: return
        // 帖先插（REPLACE 按 uuid 覆盖 → 旧帖的评论/赞经 FK CASCADE 自清，再插备份的 → 再导入幂等不重复）。
        val postUuids = HashSet<String>()
        m.posts?.forEach { p ->
            val paths = resolveImagePaths(p.imageArchiveKeys, newPathByKey, "moment:${p.uuid}")
            momentDao.insertPost(p.toEntity(encodePaths(paths)))
            postUuids.add(p.uuid)
        }
        // 评论：跳过悬挂 postUuid；按 parentCommentUuid 拓扑插（父先于子；悬挂父 → 置顶级避免自引用 FK 失败）。
        val comments = m.comments.orEmpty().filter { it.postUuid == null || postUuids.contains(it.postUuid) }
        val remaining = comments.toMutableList()
        val inserted = HashSet<String>()
        var progress = true
        while (remaining.isNotEmpty() && progress) {
            progress = false
            val iter = remaining.iterator()
            while (iter.hasNext()) {
                val c = iter.next()
                if (c.parentCommentUuid == null || inserted.contains(c.parentCommentUuid)) {
                    momentDao.insertComment(c.toEntity())
                    inserted.add(c.uuid)
                    iter.remove()
                    progress = true
                }
            }
        }
        remaining.forEach { momentDao.insertComment(it.toEntity().copy(parentCommentUuid = null)) }
        // 13.6d 复核：赞无业务键（autoGenerate id）→ 幂等只靠帖 REPLACE 的 FK CASCADE 清旧赞。只保留命中已导入帖的赞
        // （丢弃 postUuid==null/悬挂 = 脏数据，赞必挂帖；对齐 iOS postIndex 越界即跳）→ 杜绝再导入累积虚增。
        m.likes?.filter { it.postUuid != null && postUuids.contains(it.postUuid) }
            ?.forEach { momentDao.insertLike(it.toEntity()) }
    }

    private suspend fun restoreDiary(entries: List<DiaryEntryExport>?, newPathByKey: Map<String, String>) {
        entries ?: return
        entries.forEach { e ->
            val paths = resolveImagePaths(e.imageArchiveKeys, newPathByKey, "diary:${e.uuid}")
            diaryDao.upsertEntry(e.toEntity(encodePaths(paths))) // REPLACE → 旧评论/点赞经 FK CASCADE 自清
            e.comments?.forEach { diaryDao.insertComment(it.toEntity(e.uuid)) }
            e.reactions?.forEach { diaryDao.insertReaction(it.toEntity(e.uuid)) }
        }
    }

    /** 月度回顾恢复（R6-3②）：monthStartMillis 唯一 + insert IGNORE = 每月一篇幂等（重导已有月保留现有）。 */
    private suspend fun restoreMonthlyReviews(reviews: List<MonthlyReviewExport>?) {
        reviews ?: return
        reviews.forEach { diaryDao.insertMonthlyReview(it.toEntity()) }
    }

    /**
     * 多图 archiveKey 列表 → 重存后的新绝对路径列表。13.6d 复核：若某张图的字节解码失败（损坏/手改备份），
     * 该图会从结果里消失、张数缩水——正常自产备份（导出的都是已归一化的有效 JPEG）不会触发；此处对缺失记一条
     * Logcat warn 便于真机批排查，不让张数静默缩水到无从察觉。
     */
    private fun resolveImagePaths(keys: List<String>?, newPathByKey: Map<String, String>, tag: String): List<String> {
        keys ?: return emptyList()
        val paths = keys.mapNotNull { newPathByKey[it] }
        if (paths.size < keys.size) {
            Log.w("BackupImport", "$tag: ${keys.size - paths.size}/${keys.size} 张图未能恢复（字节缺失/解码失败），张数缩水")
        }
        return paths
    }

    private suspend fun restoreStories(stories: List<StoryExport>?) {
        stories ?: return
        stories.forEach { s ->
            storyDao.insertStory(s.toEntity()) // REPLACE → 旧章节/角色经 FK CASCADE 自清
            s.chapters?.forEach { storyDao.insertChapter(it.toEntity(s.id)) }
            s.characterRoles?.let { roles -> storyDao.insertRoles(roles.map { it.toEntity(s.id) }) }
        }
    }

    /** 绝对路径列表 → imagePathsJson（空 → "" = 实体默认）。 */
    private fun encodePaths(paths: List<String>): String =
        if (paths.isEmpty()) "" else json.encodeToString<List<String>>(paths)

    private companion object {
        /** 当前可导入的最高 manifest 版本（1=旧明文 .json / 2=zip 全量）。 */
        const val SUPPORTED_VERSION = 2

        /** 旧明文 `.json` 回退路的安全帽：超过就判「不是备份文件」，绝不把任意大文件整读进内存（卷 A·J4）。 */
        const val LEGACY_JSON_MAX_BYTES = 32L * 1024 * 1024

        /** 「创建副本」角色名后缀（用户拍板 2026-06-08，安卓超越 iOS：iOS 不加后缀 → 两个同名角色易混）。 */
        const val DUPLICATE_NAME_SUFFIX = "（副本）"
    }
}

/** [importArchive] 事务内累计的逐策略计数。 */
private data class ImportCounts(
    val imported: Int,
    val overwritten: Int,
    val duplicated: Int,
    val skipped: Int,
    val messages: Int,
)
