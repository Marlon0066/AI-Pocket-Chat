package com.situ.aichat.data.backup

import com.situ.aichat.data.local.entity.ConversationEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 老备份兼容看门（**聊天归档功能删除** · 图纸 `docs/handoff/2026-09-06-删除聊天归档功能.md` §0.2 A6 / §5 E2）。
 *
 * 归档功能删除时把 `isArchived` 从 [ConversationExport] 里摘掉了。用户手上**已有的**备份包里仍然带着这个键，
 * 导入侧靠 `Json { ignoreUnknownKeys = true }` 兜底——**那是一个配置开关，谁手滑关掉，所有老备份就当场解析失败**。
 * 本测试把这条兜底钉死：老包（含已删字段）必须能解码，且其余字段一个不落地还原。
 *
 * R1 复核补（2026-09-06）：原卷只有配置佐证（读代码确认 `ignoreUnknownKeys = true`），无机器断言。
 *
 * ⚠️ **本测试的边界**：`BackupImporter.json` 是 private，本测试只能**复刻**同款配置（全库既有做法，
 * 见 [ConversationRecapBackupTest]），故第一例证明的是「这套配置下老包能解」，**挡不住有人去改生产侧的
 * `ignoreUnknownKeys`**。第二例走的是真 [toExport]，那一条是真的生产侧看门。
 */
class ConversationArchiveLegacyBackupTest {

    /** 与 BackupImporter / BackupExporter 同款配置。 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = true
    }

    /** 老包里带着已删的 `isArchived` 键 → 解码不崩，其余字段照常还原。 */
    @Test
    fun legacyBackupWithRemovedArchiveKey_decodesAndKeepsOtherFields() {
        val legacyJson = """
            {
              "uuid": "conv-old",
              "title": "小满",
              "creationDate": 1700000000000,
              "isPinned": true,
              "isArchived": true,
              "lastMessagePreview": "在吗",
              "lastMessageRole": "user",
              "cachedUnreadCount": 3
            }
        """.trimIndent()

        val restored = json.decodeFromString(ConversationExport.serializer(), legacyJson)
            .toEntity(characterUuid = "cNEW")

        assertEquals("conv-old", restored.uuid)
        assertEquals("小满", restored.title)
        assertEquals("cNEW", restored.characterUuid)
        assertEquals(1_700_000_000_000L, restored.creationDate)
        assertEquals("置顶等既有字段必须照常还原", true, restored.isPinned)
        assertEquals("在吗", restored.lastMessagePreview)
        assertEquals(3, restored.cachedUnreadCount)
        // 归档功能已删：老包里的 true 不再有任何去处，落实体后是实体默认值 false（残留列·无人读写）。
        assertFalse("已删字段不该被还原成 true", restored.isArchived)
    }

    /** 新导出的包里**不再有** `isArchived` 键（字段已从 ConversationExport 摘除）。 */
    @Test
    fun newExport_noLongerEmitsArchiveKey() {
        val export = ConversationEntity(
            uuid = "conv1",
            title = "小满",
            characterUuid = "c1",
            creationDate = 1_700_000_000_000L,
            isArchived = true, // 残留列即便为 true，也不该出现在导出里
        ).toExport(messages = emptyList())

        val text = json.encodeToString(ConversationExport.serializer(), export)
        assertFalse("导出不该再写 isArchived 键", text.contains("isArchived"))
    }
}
