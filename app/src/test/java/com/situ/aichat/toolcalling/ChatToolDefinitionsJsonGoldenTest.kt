package com.situ.aichat.toolcalling

import com.situ.aichat.data.remote.llm.ToolDefinitionDto
import com.situ.aichat.ui.chat.buildChatToolDefinitions
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 0-2 黄金快照：当前工具集（calendar / offline suggest+end / future meeting / **promise record+resolve**）
 * 的请求侧 `tools` 数组 JSON 字节快照。给 ④（嵌套 schema）看门——`ParameterPropertyDto` 升递归后，**扁平工具的序列化必须字节不变**
 * （靠 explicitNulls=false 自动省略新空字段）。任一字节漂移即红。
 *
 * 线材 Json 与 [com.situ.aichat.di.NetworkModule.provideJson] 同配置（explicitNulls=false / encodeDefaults=false）。
 */
class ChatToolDefinitionsJsonGoldenTest {

    private val wireJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    @Test fun full_tool_set_request_json_is_byte_identical_to_golden() {
        val actual = wireJson.encodeToString(
            ListSerializer(ToolDefinitionDto.serializer()),
            buildChatToolDefinitions(includeCalendarTool = true, canInitiateOffline = true),
        )
        assertEquals(GoldenResources.read("chat_tool_definitions_with_promise.json"), actual)
    }

    /**
     * T1-8（图纸 2026-09-06 约定工具调用化·E26）：线下见面中约定两工具撤下 → 集合退回**加约定之前**的老 golden，
     * 逐字节相等 = 三个既有工具的 schema 一个字节都没被新工具挤动。
     */
    @Test fun offlineMeeting_dropsPromiseTools_andLegacyThreeAreByteIdentical() {
        val actual = wireJson.encodeToString(
            ListSerializer(ToolDefinitionDto.serializer()),
            buildChatToolDefinitions(includeCalendarTool = true, canInitiateOffline = true, offlineMeeting = true),
        )
        assertEquals(GoldenResources.read("chat_tool_definitions.json"), actual)
    }
}
