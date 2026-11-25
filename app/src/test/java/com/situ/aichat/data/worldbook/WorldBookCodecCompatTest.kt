package com.situ.aichat.data.worldbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1：世界书跨平台格式兼容单测（UTF-8 BOM、ST/V2 混血键名、逗号分隔单串关键词、正则量词逗号保护）。
 */
class WorldBookCodecCompatTest {

    @Test
    fun `UTF8_BOM前缀JSON正常识别解析`() {
        val json = "\uFEFF" + """
            {
              "name": "带BOM设定集",
              "entries": {
                "0": {
                  "key": ["灵石, 仙晶", "/\\d{1,3}阶/i"],
                  "content": "修真货币体系"
                }
              }
            }
        """.trimIndent()

        val parsed = WorldBookCodec.parse(json, "兜底名")
        assertEquals("带BOM设定集", parsed.book.name)
        assertEquals(1, parsed.entries.size)
        val keys = decodeStringList(parsed.entries.single().keysJson)
        assertEquals(listOf("灵石", "仙晶", "/\\d{1,3}阶/i"), keys)
    }

    @Test
    fun `ST格式兼容V2风格keys与enabled与insertion_order及单字符串key`() {
        val json = """
            {
              "entries": {
                "0": {
                  "keys": "青云宗，太上长老",
                  "secondary_keys": ["禁地, 剑冢"],
                  "enabled": false,
                  "insertion_order": 42,
                  "position": "before_char",
                  "name": "宗门秘闻",
                  "content": "后山禁地封印着古剑。"
                }
              }
            }
        """.trimIndent()

        val parsed = WorldBookCodec.parse(json, "青云录")
        val entry = parsed.entries.single()
        assertEquals(listOf("青云宗", "太上长老"), decodeStringList(entry.keysJson))
        assertEquals(listOf("禁地", "剑冢"), decodeStringList(entry.secondaryKeysJson))
        assertFalse("enabled=false 应解析为停用", entry.enabled)
        assertEquals(42, entry.insertionOrder)
        assertEquals(0, entry.position)
        assertEquals("宗门秘闻", entry.comment)
    }

    @Test
    fun `V2角色卡character_book兼容ST风格key与disable与order与数字position`() {
        val json = """
            {
              "data": {
                "character_book": {
                  "name": "混血角色卡书",
                  "entries": [
                    {
                      "key": "猎人协会, 芯核",
                      "keysecondary": "深空",
                      "disable": true,
                      "order": 77,
                      "position": 4,
                      "name": "协会档案",
                      "content": "深空猎人设定"
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val parsed = WorldBookCodec.parse(json, "兜底")
        val entry = parsed.entries.single()
        assertEquals(listOf("猎人协会", "芯核"), decodeStringList(entry.keysJson))
        assertEquals(listOf("深空"), decodeStringList(entry.secondaryKeysJson))
        assertFalse("disable=true 应解析为停用", entry.enabled)
        assertEquals(77, entry.insertionOrder)
        assertEquals(4, entry.position)
        assertEquals("协会档案", entry.comment)
        assertTrue("原 name 仍保真在 extraJson 供回写", entry.extraJson.contains("协会档案"))
    }
}
