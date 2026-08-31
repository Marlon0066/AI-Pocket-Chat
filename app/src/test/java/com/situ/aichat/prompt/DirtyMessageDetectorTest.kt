package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.offline.OfflineMarkerStartPayload
import com.situ.aichat.promise.PromiseInjectionRenderer
import com.situ.aichat.prompt.memory.InSceneRecapCoordinator
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.link.WorldRelationshipDigest
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H3#0 测试网 · DirtyMessageDetector（脏消息=LLM 复读结构化格式·9 Reason 正例 + 「prefer
 * false-negative」防误杀负例 + 与格式产出方的单源一致性锁——CLAUDE.md 特别提醒的提示词↔检测器
 * 强耦合：改任一侧格式必须让这里红）。
 */
class DirtyMessageDetectorTest {

    private fun detect(s: String) = DirtyMessageDetector.detect(s, MessageKind.PLAIN_TEXT)

    // MARK: - 9 Reason 各正例

    @Test
    fun legacyNaturalLanguage_detected() {
        assertEquals(
            DirtyMessageDetector.Reason.LEGACY_NATURAL_LANGUAGE,
            detect("我刚刚发出了一张线下见面邀请，等你回复"),
        )
    }

    @Test
    fun markdownToolSchema_detected() {
        assertEquals(
            DirtyMessageDetector.Reason.MARKDOWN_TOOL_SCHEMA,
            detect("调用 suggest_offline_meeting 工具，参数 `activity` 填咖啡"),
        )
    }

    @Test
    fun rawInviteJson_detected() {
        assertEquals(
            DirtyMessageDetector.Reason.RAW_JSON,
            detect("""{"type": "offline_invite", "activity": "咖啡"}"""),
        )
    }

    @Test
    fun systemRecordLabel_detected() {
        assertEquals(
            DirtyMessageDetector.Reason.SYSTEM_RECORD_LABEL,
            detect("[系统记录] 线下见面邀约卡片已发送"),
        )
    }

    @Test
    fun offlineInviteStandInRepeat_detected() {
        // 留痕改造 2026-08-31（图纸 §7 T1-4）：AI 复读邀约留痕行 → 新 marker「线下见面邀约」命中。
        // 措辞与 [com.situ.aichat.data.model.OfflineInviteData.llmRepresentation] 单源同步（此处重新逐字打出）。
        assertEquals(
            DirtyMessageDetector.Reason.SYSTEM_RECORD_LABEL,
            detect("[系统记录：你向小满发出了线下见面邀约 | 地点=咖啡馆 | 活动=喝咖啡 | 状态=对方婉拒了，这次没见成]"),
        )
    }

    @Test
    fun offlineMarkerEndStandInRepeat_detected() {
        // 离场留痕行复读 → 吃既有 marker「线下见面结束」。
        assertEquals(
            DirtyMessageDetector.Reason.SYSTEM_RECORD_LABEL,
            detect("[系统记录：线下见面结束（约40分钟），你们回到了线上聊天]"),
        )
    }

    @Test
    fun markerTextRepeat_detected() {
        assertEquals(
            DirtyMessageDetector.Reason.MARKER_TEXT_REPEAT,
            detect("【线下见面开始 | 地点：公园 | 活动：散步 | 时间：下午】"),
        )
    }

    @Test
    fun xmlMetadataRepeat_detected() {
        assertEquals(
            DirtyMessageDetector.Reason.XML_METADATA_REPEAT,
            detect("<current_state>idle</current_state>"),
        )
    }

    @Test
    fun memoryFormatRepeat_needsBothSectionHeaders() {
        assertEquals(
            DirtyMessageDetector.Reason.MEMORY_FORMAT_REPEAT,
            detect("【长期事实】\n- 喜欢猫\n【近期经历】\n- [2026-06-10] 去了公园"),
        )
    }

    @Test
    fun meetingMemoryFormatRepeat_detected() {
        assertEquals(
            DirtyMessageDetector.Reason.MEETING_MEMORY_FORMAT_REPEAT,
            detect("【见面 · 2026-06-10 · 咖啡馆】聊了很久"),
        )
    }

    @Test
    fun scheduleListRepeat_detected() {
        assertEquals(
            DirtyMessageDetector.Reason.SCHEDULE_LIST_REPEAT,
            detect("【你今天完整的日程】\n09:00-10:00 晨跑"),
        )
    }

    // MARK: - 承诺账本块复读（记忆改造一期·部件①·T2-11·§3.3-D 双侧同步）

    @Test
    fun promiseLedgerRepeat_detected() {
        assertEquals(
            DirtyMessageDetector.Reason.PROMISE_LEDGER_REPEAT,
            detect("【我们的约定】\n- 2026-07-10（今天·聊天中）定下：一起去看画展"),
        )
    }

    @Test
    fun promiseWordWithoutBlockHeader_notDirty() {
        // 只是聊天里提到「约定」二字、无块头 → 不误杀（prefer false-negative）。
        assertNull(detect("我们不是约定好周末一起去看展吗"))
    }

    // MARK: - 场内前情提要块复读（记忆改造二期·部件⑤·§3.2-D·T1-7）

    @Test
    fun inSceneRecapRepeat_detected() {
        assertEquals(
            DirtyMessageDetector.Reason.IN_SCENE_RECAP_REPEAT,
            detect("【前情提要】（本场更早部分的浓缩，下面的正文只保留了最近的对话）\n两人聊起周末的展览"),
        )
    }

    @Test
    fun inSceneRecapWordWithoutBlockHeader_notDirty() {
        // 聊天里提到「前情提要」四字、无块头 → 不误杀（prefer false-negative）。
        assertNull(detect("你先给我讲讲前情提要吧"))
    }

    @Test
    fun meetingTimelineAnnotationLine_notMisflaggedAsRecap() {
        // 见面时间线注记整行（以「【时间 · 」开头·含「这中间你们线下见了一面」）不含【前情提要】→ 不误伤。
        assertNull(detect("【时间 · 7月3日 周五 19:20 · 这中间你们线下见了一面：江边，散步】"))
        // 普通回复亦不误伤。
        assertNull(detect("好呀，那我们那天见面聊的展览就这么定了"))
    }

    @Test
    fun singleSource_inSceneRecapHeader_isDetected() {
        // InSceneRecapCoordinator.RECAP_HEADER（PromptBuilder 2.15 注入端）↔ 检测器 matchesInSceneRecapRepeat 同源（§3.2-D）：
        // 用常量拼出注入块 → 检测器必须判脏，否则两处漂移即红。
        val block = "${InSceneRecapCoordinator.RECAP_HEADER}（本场更早部分的浓缩，下面的正文只保留了最近的对话）\n浓缩正文"
        assertEquals(DirtyMessageDetector.Reason.IN_SCENE_RECAP_REPEAT, detect(block))
    }

    // MARK: - W5 世界上下文提炼行复读（E12·§6 双侧同步）

    @Test
    fun worldContextRepeat_detected() {
        assertEquals(
            DirtyMessageDetector.Reason.WORLD_CONTEXT_REPEAT,
            detect("【与阿哲｜相识·朋友】你们关系不错，眼下和 TA 有点别扭"),
        )
        // 嵌在长回复中亦命中（containsMatchIn）。
        assertEquals(
            DirtyMessageDetector.Reason.WORLD_CONTEXT_REPEAT,
            detect("我最近啊，【与小雅｜密友】你们交情很深，唉说来话长"),
        )
    }

    @Test
    fun worldContextRepeat_ordinaryBracketNotDirty() {
        // 非「【与…｜…】」结构的普通方括号不误杀（prefer false-negative）。
        assertNull(detect("【重要】今天很开心"))
        assertNull(detect("我和阿哲｜的关系不错"))
    }

    // MARK: - prefer false-negative：单一信号不判脏

    @Test
    fun toolNameWithoutFieldMarker_notDirty() {
        assertNull(detect("suggest_offline_meeting 这个词我只是提一下"))
    }

    @Test
    fun inviteJsonSignatureWithoutBracePrefix_notDirty() {
        assertNull(detect("""它会输出 "type": "offline_invite" 这样的字段"""))
    }

    @Test
    fun systemRecordPrefixWithoutMarker_notDirty() {
        assertNull(detect("[系统记录] 今天天气晴"))
    }

    @Test
    fun naturalSentenceMentioningInviteWithoutSystemRecordPrefix_notDirty() {
        // 防误杀（新 marker「线下见面邀约」只在 [系统记录 前缀下生效）：角色自然说起邀约不是脏消息。
        assertNull(detect("我刚才在想要不要给你发个线下见面邀约，又怕你在忙"))
    }

    @Test
    fun memorySingleHeader_notDirty() {
        assertNull(detect("【长期事实】只有一个区域标题不算"))
    }

    @Test
    fun plainChat_notDirty() {
        assertNull(detect("今晚一起去吃饭吗？"))
        assertNull(detect(""))
    }

    @Test
    fun nonPlainTextKind_neverDirty() {
        assertFalse(DirtyMessageDetector.isDirty("【你今天完整的日程】", MessageKind.SYSTEM_HINT))
        assertFalse(DirtyMessageDetector.isDirty("【长期事实】【近期经历】", MessageKind.GIFT_CARD))
    }

    // MARK: - 检测顺序（最特定 → 最宽松·KDoc 锁定）

    @Test
    fun detectionOrder_xmlBeatsMemoryFormat() {
        val both = "<current_state>x</current_state>\n【长期事实】\n【近期经历】"
        assertEquals(DirtyMessageDetector.Reason.XML_METADATA_REPEAT, detect(both))
    }

    // MARK: - 单源一致性锁（格式产出方 ↔ 检测器·任一侧漂移此处即红）

    @Test
    fun singleSource_offlineMarkerPayloadOutput_isDetected() {
        val real = OfflineMarkerStartPayload(
            location = "楼下咖啡馆",
            activity = "喝咖啡",
            timeString = "下午三点",
            tensionSeed = "她今天有点心事",
        ).makeContent()
        assertEquals(DirtyMessageDetector.Reason.MARKER_TEXT_REPEAT, detect(real))
    }

    @Test
    fun singleSource_memoryExtractionPrompt_containsBothHeadersDetectorRequires() {
        // MemoryService 的提取提示词要求 LLM 按这两个区域标题输出；检测器靠同样两个标题识别复读。
        val prompt = MemoryService.DEFAULT_EXTRACTION_PROMPT
        assertTrue(prompt.contains("【长期事实】"))
        assertTrue(prompt.contains("【近期经历】"))
    }

    @Test
    fun singleSource_promiseInjectionRendererTitle_isDetected() {
        // PromiseInjectionRenderer 的【我们的约定】块头 ↔ 检测器 matchesPromiseLedgerRepeat 同源（§3.3-D）：
        // 任一侧漂移此处即红。用真渲染器产出块，断言被判脏。
        val block = PromiseInjectionRenderer.render(
            listOf(
                PromiseEntity(
                    uuid = "p1", characterUuid = "c1", content = "一起去看画展",
                    createdAtMillis = 1_700_000_000_000L, updatedAtMillis = 1_700_000_000_000L,
                ),
            ),
            now = 1_700_000_000_000L,
            zone = ZoneOffset.UTC,
        )
        assertEquals(DirtyMessageDetector.Reason.PROMISE_LEDGER_REPEAT, detect(block))
    }

    @Test
    fun singleSource_worldDigestLineOutput_isDetected() {
        // WorldRelationshipDigest 的提炼行头 ↔ 检测器正则同源（§6 双侧同步）：任一侧漂移此处即红。
        val line = WorldRelationshipDigest.build(
            self = CharacterEntity(uuid = "me", name = "我", creationDate = 0L),
            edges = listOf(
                WorldRelationshipEntity(
                    fromId = "me", toId = "azhe",
                    typesJson = StringListJson.encode(listOf("相识", "朋友")), closeness = 50, colorRaw = "投缘",
                ),
            ),
            recentEventByPair = emptyMap(),
            namesById = mapOf("azhe" to "阿哲"),
            queryText = "",
            nowMs = 0L,
            zone = ZoneOffset.UTC,
        ).single()
        assertEquals(DirtyMessageDetector.Reason.WORLD_CONTEXT_REPEAT, detect(line))
    }
}
