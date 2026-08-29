package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.MessageEntity
import android.content.Context
import android.util.Log
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.offline.OfflineContentParser
import com.situ.aichat.prompt.DirtyMessageDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * 1:1 port of iOS `VectorMemoryService` + `VectorMemorySearchActor`. Semantic embedding & retrieval over
 * chat history. The embedding backend is [TextEmbedder] (ONNX bge-small-zh-v1.5) instead of NLEmbedding;
 * all retrieval logic (topK 5, threshold 0.65, window-cutoff exclusion, dirty/offline filtering, snippet
 * format) matches the iOS source.
 *
 * Stored vectors are Float (ONNX output is float32) serialized LE into `MessageEntity.embedding`.
 */
@Singleton
class VectorMemoryService @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val embedder: TextEmbedder,
    /** 见面档案第二路候选（记忆改造四期·部件⑥·图纸 §3.2）：档案行向量与消息向量在同一 TOP_K 池竞争。 */
    private val archiveIndex: MeetingArchiveVectorService,
) {

    private data class Candidate(
        val content: String,
        val timestamp: Long,
        val roleRaw: String,
        val similarity: Double,
        /** 命中的是线下见面消息（=[MessageEntity.isOfflineMode]）→ 片段打「线下见面」来源标（§3.8）。 */
        val isOffline: Boolean,
    )

    /** 单池合并的评分片段（消息路 + 档案路统一按相似度取 TOP_K·记忆改造四期·图纸 §3.2）。 */
    private data class Scored(val similarity: Double, val snippet: String)

    // MARK: - 嵌入生成

    /** 为文本生成嵌入（trim 后长度 < [MIN_CONTENT_LENGTH] 或嵌入器不可用 → null）。 */
    suspend fun generateEmbedding(text: String): FloatArray? {
        val cleaned = text.trim()
        if (cleaned.length < MIN_CONTENT_LENGTH) return null
        return withContext(Dispatchers.Default) { embedder.embed(cleaned) }
    }

    /** 为消息生成嵌入并写回（已有嵌入 / system / 空内容 / 结构化卡 / 太短 → 跳过）。 */
    suspend fun embedMessageIfNeeded(message: MessageEntity) {
        // 图片消息 + 摘要还没回来 → **推迟**，交给摘要链在写完 mediaMemorySummary 后调
        // [embedImageMessageAfterSummary]（ChatImageSender）。
        // 不推迟的话，此刻 renderMemoryContent 恒产出无信息量的「发送了一张图片」，而 `embedding != null`
        // 会永久挡住后续回填 → 每条图片消息的向量都是同一句话，既检索不到「那张海边的照片」，
        // 这批同构向量还会互相高相似、挤占召回名额。
        // ⚠️ [backfillMissingEmbeddings] **有意不加这条规则**：那是自愈兜底路径（进程被杀导致摘要链没跑完时），
        // 宁可嵌一个弱向量，也不能让这条消息永远没有向量。两处口径不同是设计，别来「统一」。
        if (message.imageRelativePath != null && message.mediaMemorySummary.isBlank()) return
        embedIfEligible(message)
    }

    /**
     * 摘要链跑完后由 `ChatImageSender` 调：**跳过上面那条「有图且无摘要」的推迟规则**。
     *
     * 语义差别：推迟闸判的是「摘要还没回来，再等等」；到了这个调用点，摘要链**已经跑完**，
     * 此时的空摘要 = 真的没有描述可用（契约 §B5 三条兜底路径：「图片理解」路由无视觉能力 /
     * 调用失败 / 返回空），该嵌什么就嵌什么，不该再等。
     *
     * ⚠️ **别把这个入口当成「兜底路径也能进索引」的保证**（R4 🔵-1 更正 R3 🟡-7 的措辞）：
     * 兜底摘要为空时 `renderMemoryContent` 只产出「发送了一张图片」= **7 字**，撞上本类
     * [MIN_CONTENT_LENGTH]（8·一条**与图片无关**的老规矩）→ [generateEmbedding] 返 null →
     * 这一路**照样一个字都不写**，仍要等冷启动 [backfillMissingEmbeddings]（那里会写 [SENTINEL]，
     * 等于这张照片永久搜不到）。这条现状与其成因由 `ChatImageSenderTest` 最后一例钉住。
     *
     * 那这个入口的价值在哪：**去掉对「7 < 8 这个巧合」的隐性依赖**。改用 [embedMessageIfNeeded]
     * 的话，一旦有人把兜底文案改成 8 个字、或为图片放宽下限，推迟闸就会静默把这条消息永久挡在门外。
     * 是否该为图片放宽那道下限（弱向量 vs 一堆同构向量互相挤占召回名额）= 产品取舍，
     * 2026-08-29 用户拍板**不放宽**，改为在设置「功能 API 分配 › 图片理解」给辅助文字提示
     * （见 IMAGE_MULTIMODAL §4 决策日志 7）。
     */
    suspend fun embedImageMessageAfterSummary(message: MessageEntity) = embedIfEligible(message)

    /** 两个入口共用的嵌入本体（差别只在要不要先过「推迟」那道闸）。 */
    private suspend fun embedIfEligible(message: MessageEntity) {
        if (message.embedding != null) return
        if (message.roleRaw == ROLE_SYSTEM || message.content.isEmpty()) return
        // 结构化卡（礼物 / 红包 / 通话 / 红包结算 / 线下卡）content 是 JSON / 标记文本：嵌原文会把红包 amount、礼物 cost、
        // 通话逐字稿沉淀进可语义检索的长期向量库（日后召回注入 prompt → AI 偷看到本该脱敏的数值）。整条不嵌入——卡片无
        // 语义检索价值；脱敏文本表示是 messageLlmSafeText 管的「喂 LLM 文本」旁路，向量库走更严的「整条跳过」（见其 KDoc）。
        if (MessageKind.fromRaw(message.messageKindRaw).isStructuredCard) return
        val renderable = MemoryService.renderMemoryContent(
            message.content, message.mediaMemorySummary, message.imageRelativePath != null,
        )
        val vector = generateEmbedding(renderable) ?: return
        // 列级写（非整行 upsert）：messages 表无 D1 锁，整行写会覆盖并发的投递/恢复状态列（见 MessageDao.updateEmbedding）。
        messageDao.updateEmbedding(message.messageUUID, serializeEmbedding(vector))
        Log.i(TAG, "已嵌入并存储: dim=${vector.size} role=${message.roleRaw}")
    }

    // MARK: - 嵌入回填（自愈兜底，12.3；1:1 iOS VectorMemoryEmbeddingActor.backfillMissingEmbeddings）

    /**
     * 后台分批回填仍缺 embedding 的历史消息。触发：冷启动一次性 + 导入备份后
     * （[com.situ.aichat.work.EmbeddingBackfillWorker]）。
     *
     * 为何需要：导入【旧版备份】（无 embedding 字段）或嵌入器曾不可用时，历史消息 embedding 为 NULL；而每轮聊天
     * 只嵌【当轮】消息（懒路径），永不回访历史 → 这些历史永远语义检索不到（导入备份后历史记忆「失忆」的功能缺口）。
     * 新版备份已随包带 embedding（[com.situ.aichat.data.backup] Base64 往返）→ 常态无需回填。
     *
     * - 先 [MessageDao.hasMissingEmbedding] 秒探测：无缺失直接返回，绝不加载 24MB 模型。
     * - 嵌入器不可用（缺模型 / 16KB 设备）→ 返回 0、不写 sentinel，留待将来设备/版本恢复后再补。
     * - 列级 [MessageDao.updateEmbedding] 写；不可嵌入（过短 / 无实义 token）→ 空 [SENTINEL]：NOT NULL 故下次
     *   不再探测（对齐 iOS sentinel 空 Data），检索时 deserialize 得 null 被跳过。
     * - 每批后 [BACKFILL_YIELD_MS] 让片给前台发消息当轮嵌入；批查询自推进（已写行离开缺失集），无需游标。
     * @return 本次处理的消息条数。
     */
    suspend fun backfillMissingEmbeddings(): Int {
        if (!messageDao.hasMissingEmbedding()) return 0
        if (!embedder.isAvailable) {
            Log.w(TAG, "嵌入回填跳过: 嵌入器不可用")
            return 0
        }
        var total = 0
        while (true) {
            val batch = messageDao.messagesMissingEmbedding(BACKFILL_BATCH)
            if (batch.isEmpty()) break
            for (m in batch) {
                // 结构化卡整条不嵌入（同 embedMessageIfNeeded·堵原文 JSON 含金额 / 逐字稿进向量库）。DAO
                // messagesMissingEmbedding 谓词已把卡排除在批外，正常永不命中此 continue；保留为「与活路径同口径」的防御位
                // ——注：此 continue 仅在 DAO 谓词同步排卡时才安全（否则被跳过的卡每轮重取→空转），两者必须成对存在。
                if (MessageKind.fromRaw(m.messageKindRaw).isStructuredCard) continue
                val renderable = MemoryService.renderMemoryContent(
                    m.content, m.mediaMemorySummary, m.imageRelativePath != null,
                )
                val vector = generateEmbedding(renderable)
                messageDao.updateEmbedding(m.messageUUID, vector?.let { serializeEmbedding(it) } ?: SENTINEL)
                total++
            }
            delay(BACKFILL_YIELD_MS)
        }
        Log.i(TAG, "嵌入回填完成: 处理=$total 条")
        return total
    }

    // MARK: - 启动自愈：模型签名变更检测 → 全量清空待重嵌（14.5a；1:1 iOS detectAndHandleModelChangeIfNeeded）

    /**
     * 冷启动/导入后由 [com.situ.aichat.work.EmbeddingBackfillWorker] 在回填**之前**调用：检测嵌入模型是否换过，
     * 换了就清空全部旧向量，让随后的 [backfillMissingEmbeddings] 用新模型重嵌。
     *
     * 与 iOS 的平台差异（faithful 适配，同一自愈行为）：
     * - **签名=构建常量 [MODEL_SIGNATURE]**：安卓嵌入器是固定打包的单一 ONNX（[TextEmbedder]），无 iOS
     *   `NLContextualEmbedding.revision` 那种随系统升级的运行时漂移，也无降级链。签名只在「未来换打包模型」时变，
     *   故无需为算签名而加载 24MB 模型。
     * - **常态零开销**：签名一致（绝大多数启动）直接返回，**不触碰 [embedder]、不加载模型**。
     * - **不可用则不清空（安卓独有安全位）**：安卓无 iOS 的 NLEmbedding 兜底嵌入器；若模型此刻加载不了还盲目清空，
     *   向量会永久孤儿化（清了又补不回）。故仅在「签名变 且 嵌入器可用」时才清，否则推迟、连签名也不更新，下次启动重试。
     * - 跳过 iOS 的阈值 45→65 一次性迁移：安卓默认阈值一向是 65，无 legacy NLEmbedding 用户群可迁。
     * @return 清空的行数（未变更/首装/推迟均为 0）。
     */
    suspend fun detectModelChangeAndClearIfNeeded(context: Context): Int {
        val saved = EmbeddingModelSignatureStore.saved(context)
        val current = MODEL_SIGNATURE
        // 仅在签名确实不同且本机已有过签名（非首装）时才探测嵌入器可用性——避免常态启动白加载 24MB 模型。
        val needsAvailabilityProbe = saved != current && saved.isNotEmpty()
        val action = signatureAction(
            saved = saved,
            current = current,
            embedderAvailable = if (needsAvailabilityProbe) embedder.isAvailable else false,
        )
        return when (action) {
            SignatureAction.NO_CHANGE -> 0
            SignatureAction.RECORD_FIRST_INSTALL -> {
                EmbeddingModelSignatureStore.set(context, current)
                0
            }
            SignatureAction.DEFER_UNAVAILABLE -> {
                Log.w(TAG, "嵌入模型签名变更但嵌入器不可用，暂不清空（待设备/版本恢复后下次启动重试）")
                0
            }
            SignatureAction.CLEAR_AND_REEMBED -> {
                val cleared = messageDao.clearAllEmbeddings()
                val clearedArchive = archiveIndex.clearAll() // 四期：消息 + 档案双清（图纸 §3.2·计数并入日志）
                EmbeddingModelSignatureStore.set(context, current)
                Log.i(TAG, "嵌入模型签名变更 $saved → $current，已清空 $cleared 条消息 + $clearedArchive 条档案旧向量，待回填重嵌")
                cleared
            }
        }
    }

    enum class SignatureAction { NO_CHANGE, RECORD_FIRST_INSTALL, DEFER_UNAVAILABLE, CLEAR_AND_REEMBED }

    // MARK: - 序列化（FloatArray ↔ ByteArray，float32 小端）
    // 记忆改造四期·部件⑥（图纸 §3.2）：函数体已搬进 companion（供 MeetingArchiveVectorService 第二路候选复用），
    // 实例方法保留原签名、单行委托——WorldMemoryEmbedder 与全部既有调用/测试零改。

    fun serializeEmbedding(v: FloatArray): ByteArray = Companion.serializeEmbedding(v)

    fun deserializeEmbedding(data: ByteArray): FloatArray? = Companion.deserializeEmbedding(data)

    // MARK: - 余弦相似度

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Double = Companion.cosineSimilarity(a, b)

    // MARK: - 语义检索

    /**
     * 根据当前查询文本（最新一条用户消息），从该角色的历史消息中检索相关片段。
     *
     * 说话人标注用真名（2026-07-12 拍板）：`[时间] <用户名>/<角色名>：内容`——抽象身份词「用户/角色」会被模型
     * 误当术语甚至第三人，具名对话理解更自然（与通知「最近聊过」片段、见面摘要禁「用户」同一口径）。
     * [userName]/[characterName] 由调用方解析后传入（昵称空 → `pb_user_fallback`，与全库注入面一致）；
     * 两参数**有意不设默认值**——新调用点必须显式给名，防止悄悄退回抽象标注。
     * @return 片段列表 `[时间] <说话人>：内容`，按相似度降序，最多 [TOP_K] 条。
     */
    suspend fun searchRelevantMemories(
        query: String,
        characterUuid: String,
        currentConversationUuid: String,
        userName: String,
        characterName: String,
        shortTermLength: Int,
        thresholdPercent: Int,
    ): List<String> {
        val queryEmbedding = generateEmbedding(query) ?: run {
            Log.w(TAG, "检索跳过: 嵌入器不可用或查询过短")
            return emptyList()
        }

        // 反序列化 + 余弦相似度是 CPU 密集循环（分页扫描全部已嵌入消息 × 512 维），
        // 对齐 iOS `VectorMemorySearchActor`：整段搬到后台线程，避免阻塞发消息时的主线程（generateEmbedding
        // 已在 Dispatchers.Default）。Room suspend DAO 调用在 withContext(Default) 内仍走 Room 自己的执行器，安全。
        return withContext(Dispatchers.Default) {
            val startNanos = System.nanoTime()
            val effectiveThreshold = if (thresholdPercent > 0) thresholdPercent / 100.0 else DEFAULT_SIMILARITY_THRESHOLD

            // 第二路候选（见面档案·记忆改造四期·部件⑥·图纸 §3.2）：档案向量 + top-N 场原文消息排除集（下方扫描剔除）。
            val archive = archiveIndex.retrieval(queryEmbedding, characterUuid, effectiveThreshold)

            val conversationUuids = conversationDao.getByCharacter(characterUuid).map { it.uuid }
                .ifEmpty { listOf(currentConversationUuid) }
            val windowCutoff = shortTermWindowCutoffMillis(currentConversationUuid, shortTermLength)

            val candidates = ArrayList<Candidate>()
            for (convUuid in conversationUuids) {
                val isCurrent = convUuid == currentConversationUuid
                // 批1修复：分页扫描该会话【全部】已嵌入消息（旧实现只看最新 200 条 → 第 201 条起的老消息永久不可召回）。
                // 每页 [CANDIDATE_PAGE_SIZE] 条，页间只保留过阈值候选并修剪到 [CANDIDATE_KEEP_LIMIT]，内存恒定。
                var offset = 0
                while (true) {
                    val messages = messageDao.getEmbeddedPage(convUuid, CANDIDATE_PAGE_SIZE, offset)
                    if (messages.isEmpty()) break
                    for (m in messages) {
                        if (m.content.isEmpty() || m.roleRaw == ROLE_SYSTEM) continue
                        val kind = MessageKind.fromRaw(m.messageKindRaw)
                        // 结构化卡整条排除（isStructuredCard ⊋ isOfflineEventCard：额外覆盖 礼物/红包/通话/红包结算卡，
                        // 其原文含 amount/cost/逐字稿）。写侧现已不再嵌卡，但旧版本可能嵌过 → 读时兜底覆盖历史已嵌入的卡。
                        if (kind.isStructuredCard) continue
                        // 系统耳语（如「取消见面」提示）= 只喂当轮模型的后台旁白，绝不沉淀为可检索的长期记忆
                        // （与显示面「用户永不可见」同一不变量；其 roleRaw='user'+非脏，否则会漏过本循环被当记忆召回）。
                        if (kind == MessageKind.SYSTEM_HINT) continue
                        if (DirtyMessageDetector.isDirty(m.content, kind)) continue
                        // top-N 场原文消息排除（记忆改造四期·部件⑥·「一事一形态」：该场完整档案卡已在场·图纸 §3.2）。
                        if (m.offlineSessionId != null && m.offlineSessionId in archive.excludedSessionIds) continue
                        if (isCurrent) {
                            if (windowCutoff == null) continue
                            if (m.timestamp >= windowCutoff) continue
                        }
                        val data = m.embedding ?: continue
                        val emb = deserializeEmbedding(data) ?: continue
                        if (emb.size != queryEmbedding.size) continue
                        val sim = cosineSimilarity(queryEmbedding, emb)
                        if (sim < effectiveThreshold) continue
                        // §3.8：见面消息剥线下标签（[环境]/[对话]… → 纯文本）再入候选；剥后为空 → 跳过（避免注入空片段·E10）。
                        val rendered = MemoryService.renderMemoryContent(m.content, m.mediaMemorySummary, m.imageRelativePath != null)
                        val content = offlineCandidateContent(rendered, m.isOfflineMode) ?: continue
                        candidates.add(
                            Candidate(
                                content = content,
                                timestamp = m.timestamp,
                                roleRaw = m.roleRaw,
                                similarity = sim,
                                isOffline = m.isOfflineMode,
                            ),
                        )
                    }
                    if (candidates.size > CANDIDATE_KEEP_LIMIT) {
                        candidates.sortByDescending { it.similarity }
                        while (candidates.size > CANDIDATE_KEEP_LIMIT) candidates.removeAt(candidates.size - 1)
                    }
                    offset += messages.size
                    if (messages.size < CANDIDATE_PAGE_SIZE) break
                }
            }

            // 单池合并（记忆改造四期·图纸 §3.2·锁定）：消息路 TOP_K + 档案路全部候选，统一按相似度再取 TOP_K。
            val scored = candidates.sortedByDescending { it.similarity }.take(TOP_K)
                .map { Scored(it.similarity, formatRetrievalSnippet(MemoryService.formatTimestamp(it.timestamp), it.roleRaw, it.content, it.isOffline, userName, characterName)) } +
                archive.candidates.map { Scored(it.similarity, formatArchiveSnippet(MemoryService.formatTimestamp(it.startedAtMillis), it.content)) }
            val result = scored.sortedByDescending { it.similarity }.take(TOP_K).map { it.snippet }
            Log.i(
                TAG,
                "检索完成: 过阈值(${"%.2f".format(effectiveThreshold)})候选=${candidates.size} 档案候选=${archive.candidates.size} " +
                    "注入prompt=${result.size} 耗时=${(System.nanoTime() - startNanos) / 1_000_000}ms(后台)",
            )
            result
        }
    }

    // MARK: - 短期窗口截断时间

    /** 第 [shortTermLength] 近的用户消息时间戳；不足 N 条 → null（全部在窗口内）。 */
    private suspend fun shortTermWindowCutoffMillis(conversationUuid: String, shortTermLength: Int): Long? {
        val timestamps = messageDao.recentUserTimestamps(conversationUuid, shortTermLength)
        if (timestamps.size < shortTermLength) return null
        return timestamps.last() // DESC 取回，最后一个 = 第 N 近（最旧）
    }

    // 内容渲染（renderMemoryContent）与时间格式（formatTimestamp）已下沉到 MemoryService，
    // 作为摘要 formatMessages + 向量嵌入共用的唯一入口（spec M05 §4#8）。

    companion object {
        private const val TAG = "VectorMemory"

        // MARK: - 序列化 / 余弦（四期搬入 companion·体逐字·实例方法委托至此）

        fun serializeEmbedding(v: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (x in v) buf.putFloat(x)
            return buf.array()
        }

        fun deserializeEmbedding(data: ByteArray): FloatArray? {
            if (data.isEmpty() || data.size % 4 != 0) return null
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(data.size / 4) { buf.float }
        }

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size || a.isEmpty()) return 0.0
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                val x = a[i].toDouble()
                val y = b[i].toDouble()
                dot += x * y
                na += x * x
                nb += y * y
            }
            val denom = sqrt(na) * sqrt(nb)
            return if (denom > 0) dot / denom else 0.0
        }

        /**
         * 当前打包嵌入模型的签名（14.5a 启动自愈唯一真源；对齐 iOS `modelSignature()` 的 `模型-dim维度` 形式）。
         * 安卓模型是固定资产（[TextEmbedder] = bge-small-zh-v1.5 int8，CLS 池化，dim 512）→ 这是构建期常量，
         * **将来更换/升级嵌入模型资产时必须改这里**，启动自愈即会清空旧向量并用新模型重嵌全部历史。
         */
        const val MODEL_SIGNATURE = "onnx-bge-small-zh-v1.5-int8-dim512"

        /**
         * 纯函数：由「上次签名 / 当前签名 / 嵌入器是否可用」裁定启动自愈动作（单测反推 iOS 分支）。
         * - 签名一致 → 无操作（优先级最高，覆盖嵌入器状态）。
         * - 首装（saved 为空）→ 仅记录当前签名，不清空（对齐 iOS：savedSig 空且无 legacy dimension key=全新安装不迁移；
         *   安卓本就无 legacyDimensionKey，故 saved 空恒为首装）。
         * - 签名变更但嵌入器不可用 → 推迟（安卓独有安全位，见 [detectModelChangeAndClearIfNeeded]）。
         * - 签名变更且嵌入器可用 → 清空全部并重嵌。
         */
        internal fun signatureAction(saved: String, current: String, embedderAvailable: Boolean): SignatureAction =
            when {
                saved == current -> SignatureAction.NO_CHANGE
                saved.isEmpty() -> SignatureAction.RECORD_FIRST_INSTALL
                !embedderAvailable -> SignatureAction.DEFER_UNAVAILABLE
                else -> SignatureAction.CLEAR_AND_REEMBED
            }

        /**
         * §3.8 检索候选内容：见面消息剥线下标签（[OfflineContentParser.stripAllTags]）后返回；剥后 trim 为空 → null
         * （调用方跳过该候选·E10）。非见面消息原样返回。纯函数·T2-5。
         */
        internal fun offlineCandidateContent(rendered: String, isOffline: Boolean): String? =
            if (!isOffline) rendered else OfflineContentParser.stripAllTags(rendered).takeIf { it.trim().isNotEmpty() }

        /**
         * §3.8 检索片段格式：见面来源打「$ts · 线下见面」前缀标（让 AI 知道回忆来自线下见面），否则原样 `$ts`；
         * 说话人用真名 [userName]/[characterName]（2026-07-12 拍板，取代写死的「用户/角色」——调用方负责兜底解析，
         * 见 [searchRelevantMemories] KDoc）。纯函数·T2-5。
         */
        internal fun formatRetrievalSnippet(
            ts: String,
            roleRaw: String,
            content: String,
            isOffline: Boolean,
            userName: String,
            characterName: String,
        ): String {
            val speaker = if (roleRaw == ROLE_USER) userName else characterName
            val tsLabel = if (isOffline) "$ts · 线下见面" else ts
            return "[$tsLabel] $speaker：$content"
        }

        /**
         * 见面档案第二路候选片段格式（记忆改造四期·部件⑥·图纸 §3.2·锁定·纯函数·T2-1）：打「· 见面档案」来源标
         * （让 AI 知道回忆来自那次线下见面档案，非逐条消息）。`[… · 见面档案]` 是 prompt 输入格式非模型输出禁令面
         * （与既有 `[… · 线下见面]` 同性质·§6）。
         */
        internal fun formatArchiveSnippet(ts: String, content: String): String = "[$ts · 见面档案] $content"

        const val TOP_K = 5
        const val DEFAULT_SIMILARITY_THRESHOLD = 0.65
        const val MIN_CONTENT_LENGTH = 8

        /** 检索分页大小（批1修复：全量扫描替代旧「最新 200 条」天花板；一页 embedding blob ≈ 1MB，内存有界）。 */
        private const val CANDIDATE_PAGE_SIZE = 500

        /** 页间候选修剪上限（只需 TOP_K=5，留余量防跨页排序抖动）。 */
        private const val CANDIDATE_KEEP_LIMIT = 50
        private const val BACKFILL_BATCH = 100 // 对齐 iOS backfillMissingEmbeddings batchSize=100
        private const val BACKFILL_YIELD_MS = 50L // 批间让片给前台发消息当轮嵌入（对齐 iOS Task.sleep 50ms）
        private val SENTINEL = ByteArray(0) // 不可嵌入消息的占位：非 NULL→不再探测；解码空→检索跳过
        private const val ROLE_USER = "user"
        private const val ROLE_SYSTEM = "system"
    }
}
