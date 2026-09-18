package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 世界书条目（酒馆 World Info entry）——一张「触发关键词 + 设定内容 + 行为开关」的设定卡片。
 * 字段语义、枚举值、默认值**全部按酒馆世界书格式对齐**：契约 `FABLE5_WORLDBOOK_PROPOSAL.md` §2.1/§2.2
 * （selectiveLogic 0–3 / position 0–7 / 各默认值与酒馆新建条目的默认值一致），勿凭记忆改。
 *
 * 列取舍（契约 §4.1）：**激活引擎要读的行为字段全部立列**；不实现功能的「保留档」字段
 * （outletName / automationId / triggers / characterFilter* / match* / addMemo / extensions / 未知字段）
 * 整包进 [extraJson]，导入什么样导出还什么样（round-trip 冻字节由 WB2 解析器测试锁定）。
 *
 * 命名映射（ST → 本实体，仅这三处改名，其余同名）：`key`→[keysJson]、`keysecondary`→[secondaryKeysJson]、
 * `order`→[insertionOrder]、`group`→[groupName]、`disable`→[enabled]（极性翻转）——均为 SQL 关键字/风格原因，
 * WB2 解析器双向映射并有测试锁定。
 */
@Entity(
    tableName = "world_book_entries",
    foreignKeys = [
        ForeignKey(
            entity = WorldBookEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["bookUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookUuid")],
)
data class WorldBookEntryEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    /** 所属书（删书级联清条目）。 */
    val bookUuid: String = "",

    /** ST 原 uid（导出还原用；导入外来书时保留原值，自建条目按序分配）。 */
    val uid: Int = 0,
    /** UI 排序位（ST displayIndex）。 */
    val displayIndex: Int = 0,

    // ── 触发 ──
    /** 主关键词 JSON 数组（命中任一即候选；`/正则/` 形式见契约 D3）。 */
    val keysJson: String = "[]",
    /** 次要关键词 JSON 数组（配合 [selectiveLogic]）。 */
    val secondaryKeysJson: String = "[]",
    /** 是否启用次键逻辑（ST 默认 true；次键为空时无效果）。 */
    val selective: Boolean = true,
    /** 次键逻辑：0=AND ANY / 1=NOT ALL / 2=NOT ANY / 3=AND ALL（ST `world_info_logic`）。 */
    val selectiveLogic: Int = 0,
    /** 蓝灯常驻：跳过关键词直接激活。 */
    val constant: Boolean = false,
    /** 链接条目：语义相似触发（WB5 接向量引擎）。 */
    val vectorized: Boolean = false,

    // ── 内容 ──
    /** 条目标题（备忘，不给 AI 看；ST comment）。 */
    val comment: String = "",
    /** 注入提示词的设定文字（支持 {{user}}/{{char}}/{{now}} 宏）。 */
    val content: String = "",

    /** 条目开关（ST `disable` 的极性翻转）。 */
    val enabled: Boolean = true,

    // ── 插入 ──
    /** 插入顺序（ST order·默认 100）：数值越大越靠近上下文末尾、预算裁剪优先级越高。 */
    val insertionOrder: Int = 100,
    /** 插入位置 0–7（ST `world_info_position`，锚点映射见契约 §2.2/§4.3）。 */
    val position: Int = 0,
    /** position=4（@深度）时：插到对话历史倒数第 N 条处（ST 默认 4）。 */
    val depth: Int = 4,
    /** @深度插入的消息角色：0=system / 1=user / 2=assistant。 */
    val role: Int = 0,
    /** 无视预算强制插入。 */
    val ignoreBudget: Boolean = false,

    // ── 概率 ──
    /** 触发概率 %（sticky 保持期不重掷）。 */
    val probability: Int = 100,
    /** 概率开关（ST 恒真的历史遗留字段，导入兼容保留）。 */
    val useProbability: Boolean = true,

    // ── 条目级匹配覆盖（null = 用全局设置） ──
    val scanDepth: Int? = null,
    val caseSensitive: Boolean? = null,
    val matchWholeWords: Boolean? = null,

    // ── 递归 ──
    /** 本条目不能被其他条目递归触发（只认真实聊天文本）。 */
    val excludeRecursion: Boolean = false,
    /** 本条目内容不去触发别的条目（不进递归扫描源）。 */
    val preventRecursion: Boolean = false,
    /** 仅递归轮可激活；>1 表示第 N 层递归才解锁（0 = 不限）。 */
    val delayUntilRecursion: Int = 0,

    // ── 互斥分组 ──
    /** 分组名（ST group，逗号可分多组；"" = 不分组）。同组同轮只激活一条。 */
    val groupName: String = "",
    /** 分组内优先胜出（不抽签）。 */
    val groupOverride: Boolean = false,
    /** 分组抽签权重（ST 默认 100）。 */
    val groupWeight: Int = 100,
    /** 按关键词命中数评分选胜者（null = 用全局设置）。 */
    val useGroupScoring: Boolean? = null,

    // ── 时效三件套（null = 关） ──
    /** 触发后保持激活 N 条消息。 */
    val sticky: Int? = null,
    /** 触发后冷却 N 条消息不可再触发。 */
    val cooldown: Int? = null,
    /** 对话至少 N 条消息后才可激活。 */
    val delay: Int? = null,

    /** 保留档字段 + extensions + 未知字段整包 JSON（round-trip；"" = 无）。 */
    val extraJson: String = "",

    // ── 向量条目缓存（WB5·仿 MessageEntity.embedding / VectorMemoryService 签名自愈） ──
    /** 条目嵌入（float32 小端字节；null = 未嵌入或非向量条目）。 */
    val embedding: ByteArray? = null,
    /** 生成嵌入时的模型签名（签名漂移时按 14.5a 模式清空重嵌）。 */
    val embeddingSignature: String? = null,
)
