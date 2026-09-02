package com.situ.aichat.prompt

import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定 [SystemModuleType.rawValue]（批 D 上下文日志·[ContextSegment.systemModuleType] 的来源）。
 *
 * 生产代码用显式 `when` 列出 rawValue（避免实验序列化 API 引入编译警告），但这些串是**冻结契约**（= 持久化
 * JSON 与 iOS 备份的线格式）。本测试用 kotlinx 序列化描述符（`@SerialName` 的单一真源）全量交叉校验：任一
 * `when` 分支与对应 `@SerialName` 漂移即红，等价「rawValue == @SerialName」全表锁。外加少量硬编码 spot 值，
 * 防描述符与 `when` 同时被改坏（独立锚点）。
 */
class SystemModuleTypeRawValueTest {

    @OptIn(ExperimentalSerializationApi::class)
    @Test fun `rawValue matches @SerialName for every entry`() {
        val descriptor = SystemModuleType.serializer().descriptor
        for (type in SystemModuleType.entries) {
            // 枚举描述符的元素名即各项 @SerialName，按声明序 = ordinal。
            val serialName = descriptor.getElementName(type.ordinal)
            assertEquals("rawValue 与 @SerialName 漂移：$type", serialName, type.rawValue)
        }
    }

    @Test fun `spot-check known rawValues`() {
        assertEquals("coreRules", SystemModuleType.CORE_RULES.rawValue)
        assertEquals("characterMemory", SystemModuleType.CHARACTER_MEMORY.rawValue)
        assertEquals("currentMoment", SystemModuleType.CURRENT_MOMENT.rawValue)
        assertEquals("characterEconomicState", SystemModuleType.CHARACTER_ECONOMIC_STATE.rawValue)
        assertEquals("busyReplyInstruction", SystemModuleType.BUSY_REPLY_INSTRUCTION.rawValue)
        assertEquals("ourDays", SystemModuleType.OUR_DAYS.rawValue) // 「我们的日子」卷二
    }

    @Test fun `every rawValue is unique and non-blank`() {
        val raws = SystemModuleType.entries.map { it.rawValue }
        assertEquals("rawValue 不应重复", raws.size, raws.toSet().size)
        raws.forEach { assertEquals("rawValue 非空", true, it.isNotBlank()) }
    }
}
