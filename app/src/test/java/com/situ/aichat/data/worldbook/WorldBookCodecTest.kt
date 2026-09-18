package com.situ.aichat.data.worldbook

import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书编解码 T1（WB2·契约 §2.1/§3）：字段映射与默认值按酒馆格式独立断言；
 * round-trip 承诺 = 已知列逐项一致 + 未知字段原样保留 + 二次导出达到不动点（导出补默认值白名单行为）。
 */
class WorldBookCodecTest {

    // ── 酒馆独立格式 fixture：条目1全字段 / 条目2空对象 / 条目7宽容形态 ──
    private val stFixture = """
        {
          "name": "青云录",
          "description": "修仙世界观",
          "scan_depth": 3,
          "token_budget": 1024,
          "recursive_scanning": true,
          "customRoot": "根级自定义",
          "entries": {
            "1": {
              "uid": 1, "key": ["青云宗", "青云"], "keysecondary": ["宗主"],
              "comment": "门派设定", "content": "青云宗是北域第一修真门派。",
              "constant": false, "vectorized": false, "selective": true, "selectiveLogic": 3,
              "addMemo": true, "order": 250, "position": 4, "depth": 2, "role": 2,
              "disable": true, "ignoreBudget": true, "excludeRecursion": true, "preventRecursion": true,
              "delayUntilRecursion": 2, "probability": 75, "useProbability": true,
              "group": "门派", "groupOverride": true, "groupWeight": 60, "useGroupScoring": true,
              "scanDepth": 5, "caseSensitive": true, "matchWholeWords": false,
              "sticky": 3, "cooldown": 8, "delay": 10,
              "outletName": "", "automationId": "auto-1", "triggers": ["normal"],
              "characterFilter": {"isExclude": false, "names": ["小翠"], "tags": []},
              "extensions": {},
              "myCustomField": "自定义值",
              "displayIndex": 0
            },
            "2": {},
            "7": { "delayUntilRecursion": true, "probability": 100.0 }
          }
        }
    """.trimIndent()

    private fun parseSt() = WorldBookCodec.parse(stFixture, fallbackName = "兜底名")

    private fun String.toObj(): JsonObject = Json.parseToJsonElement(this).jsonObject

    private fun WorldBookEntryEntity.columnsOnly() =
        copy(uuid = "", bookUuid = "", extraJson = "")

    private fun WorldBookEntryEntity.normalized() = copy(uuid = "", bookUuid = "")

    private fun WorldBookEntity.normalized() = copy(uuid = "", createdAt = 0L, updatedAt = 0L)

    // ── 独立格式：解析 ──

    @Test
    fun ST全字段_逐列解析正确() {
        val parsed = parseSt()
        assertEquals(WorldBookCodec.WorldBookFormat.ST_STANDALONE, parsed.format)
        assertEquals(0, parsed.skippedEntryCount)
        val e = parsed.entries.single { it.uid == 1 }
        assertEquals(listOf("青云宗", "青云"), decodeStringList(e.keysJson))
        assertEquals(listOf("宗主"), decodeStringList(e.secondaryKeysJson))
        assertEquals("门派设定", e.comment)
        assertEquals(3, e.selectiveLogic)
        assertEquals(250, e.insertionOrder)
        assertEquals(4, e.position)
        assertEquals(2, e.depth)
        assertEquals(2, e.role)
        assertFalse("disable:true 必须翻转为 enabled=false", e.enabled)
        assertTrue(e.ignoreBudget)
        assertTrue(e.excludeRecursion)
        assertTrue(e.preventRecursion)
        assertEquals(2, e.delayUntilRecursion)
        assertEquals(75, e.probability)
        assertEquals("门派", e.groupName)
        assertTrue(e.groupOverride)
        assertEquals(60, e.groupWeight)
        assertEquals(true, e.useGroupScoring)
        assertEquals(5, e.scanDepth)
        assertEquals(true, e.caseSensitive)
        assertEquals(false, e.matchWholeWords)
        assertEquals(3, e.sticky)
        assertEquals(8, e.cooldown)
        assertEquals(10, e.delay)
    }

    @Test
    fun ST最小条目_补齐酒馆默认值全表() {
        val e = parseSt().entries.single { it.uid == 2 }
        // 默认值全表 = ST newWorldInfoEntryDefinition（契约 §2.1），从规格独立反推
        assertTrue(e.enabled)
        assertTrue(e.selective)
        assertEquals(0, e.selectiveLogic)
        assertFalse(e.constant)
        assertFalse(e.vectorized)
        assertEquals(100, e.insertionOrder)
        assertEquals(0, e.position)
        assertEquals(4, e.depth)
        assertEquals(0, e.role)
        assertEquals(100, e.probability)
        assertTrue(e.useProbability)
        assertEquals("", e.groupName)
        assertEquals(100, e.groupWeight)
        assertFalse(e.groupOverride)
        assertNull(e.useGroupScoring)
        assertNull(e.scanDepth)
        assertNull(e.caseSensitive)
        assertNull(e.matchWholeWords)
        assertNull(e.sticky)
        assertNull(e.cooldown)
        assertNull(e.delay)
        assertFalse(e.excludeRecursion)
        assertFalse(e.preventRecursion)
        assertEquals(0, e.delayUntilRecursion)
        assertFalse(e.ignoreBudget)
        assertEquals("", e.extraJson)
        assertEquals(listOf<String>(), decodeStringList(e.keysJson))
    }

    @Test
    fun 宽容解析_老导出布尔delay与小数概率() {
        val e = parseSt().entries.single { it.uid == 7 }
        assertEquals("布尔 true 须按老格式折成层级 1", 1, e.delayUntilRecursion)
        assertEquals("100.0 小数须宽容取整", 100, e.probability)
    }

    @Test
    fun 未知与保留字段进extraJson_已映射列不混入() {
        val extras = parseSt().entries.single { it.uid == 1 }.extraJson.toObj()
        assertEquals("自定义值", extras["myCustomField"]?.jsonPrimitive?.content)
        assertTrue(extras.containsKey("characterFilter"))
        assertTrue(extras.containsKey("triggers"))
        assertTrue(extras.containsKey("automationId"))
        assertTrue(extras.containsKey("addMemo"))
        assertTrue(extras.containsKey("outletName"))
        assertTrue(extras.containsKey("extensions"))
        assertFalse("已映射列不得混入 extras", extras.containsKey("order"))
        assertFalse(extras.containsKey("disable"))
        assertFalse(extras.containsKey("group"))
    }

    @Test
    fun 书级字段与根级未知字段() {
        val book = parseSt().book
        assertEquals("青云录", book.name)
        assertEquals("修仙世界观", book.description)
        assertEquals(3, book.scanDepth)
        assertEquals(1024, book.tokenBudget)
        assertEquals(true, book.recursiveScanning)
        assertEquals("根级自定义", book.extraJson.toObj()["customRoot"]?.jsonPrimitive?.content)
    }

    @Test
    fun 书名缺失_用文件名兜底() {
        val parsed = WorldBookCodec.parse("""{"entries":{}}""", fallbackName = "我的书")
        assertEquals("我的书", parsed.book.name)
    }

    // ── round-trip ──

    @Test
    fun roundTrip_已知列逐项一致() {
        val a = parseSt()
        val b = WorldBookCodec.parse(WorldBookCodec.exportToStJson(a.book, a.entries), "兜底名")
        assertEquals(a.book.normalized(), b.book.normalized())
        assertEquals(
            a.entries.map { it.columnsOnly() }.sortedBy { it.uid },
            b.entries.map { it.columnsOnly() }.sortedBy { it.uid },
        )
        // 未知字段穿越 round-trip 存活
        val extrasB = b.entries.single { it.uid == 1 }.extraJson.toObj()
        assertEquals("自定义值", extrasB["myCustomField"]?.jsonPrimitive?.content)
        assertTrue(extrasB.containsKey("characterFilter"))
    }

    @Test
    fun roundTrip_二次导出达到不动点() {
        val a = parseSt()
        val b = WorldBookCodec.parse(WorldBookCodec.exportToStJson(a.book, a.entries), "兜底名")
        val c = WorldBookCodec.parse(WorldBookCodec.exportToStJson(b.book, b.entries), "兜底名")
        assertEquals(b.book.normalized(), c.book.normalized())
        assertEquals(
            b.entries.map { it.normalized() }.sortedBy { it.uid },
            c.entries.map { it.normalized() }.sortedBy { it.uid },
        )
    }

    @Test
    fun 导出JSON_未知字段原样存在且disable极性正确() {
        val a = parseSt()
        val root = WorldBookCodec.exportToStJson(a.book, a.entries).toObj()
        assertEquals("根级自定义", root["customRoot"]?.jsonPrimitive?.content)
        val e1 = root["entries"]!!.jsonObject["1"]!!.jsonObject
        assertEquals("自定义值", e1["myCustomField"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(true), e1["disable"])
        assertEquals(
            "小翠",
            e1["characterFilter"]!!.jsonObject["names"]!!.jsonArray[0].jsonPrimitive.content,
        )
        // 最小条目导出须补全默认字段（白名单行为）
        val e2 = root["entries"]!!.jsonObject["2"]!!.jsonObject
        assertEquals(JsonPrimitive(100), e2["order"])
        assertEquals(JsonPrimitive(false), e2["disable"])
        assertTrue(e2["key"] is JsonArray)
    }

    // ── 容错与报错 ──

    @Test
    fun 坏条目跳过并计数_好条目保留() {
        val parsed = WorldBookCodec.parse(
            """{"entries":{"0":{"key":["a"]},"1":"垃圾字符串"}}""",
            "书",
        )
        assertEquals(1, parsed.entries.size)
        assertEquals(1, parsed.skippedEntryCount)
    }

    @Test
    fun uid撞键_顺延去重不丢条目() {
        val parsed = WorldBookCodec.parse(
            """{"entries":{"0":{"uid":5,"content":"甲"},"1":{"uid":5,"content":"乙"}}}""",
            "书",
        )
        assertEquals(setOf(5, 6), parsed.entries.map { it.uid }.toSet())
        assertEquals(2, parsed.entries.size)
    }

    @Test
    fun 非JSON_人话报错() {
        val e = assertThrows(WorldBookParseException::class.java) {
            WorldBookCodec.parse("这不是JSON", "书")
        }
        assertTrue(e.message!!.contains("JSON"))
    }

    @Test
    fun 结构不认识_人话报错() {
        val e = assertThrows(WorldBookParseException::class.java) {
            WorldBookCodec.parse("""{"foo":1}""", "书")
        }
        assertTrue(e.message!!.contains("没认出"))
    }

    @Test
    fun 角色卡无内嵌世界书_人话报错() {
        val e = assertThrows(WorldBookParseException::class.java) {
            WorldBookCodec.parse("""{"spec":"chara_card_v2","data":{"name":"小翠"}}""", "书")
        }
        assertTrue(e.message!!.contains("没有内嵌世界书"))
    }

    // ── 角色卡 V2 内嵌格式 ──

    private val cardFixture = """
        {
          "spec": "chara_card_v2",
          "spec_version": "2.0",
          "data": {
            "name": "小翠",
            "character_book": {
              "scan_depth": 2,
              "extensions": {"book_ext": 1},
              "entries": [
                {
                  "id": 10, "keys": ["灵田"], "secondary_keys": ["浇水"],
                  "comment": "灵田", "content": "灵田在后山。",
                  "constant": true, "selective": true, "enabled": false,
                  "insertion_order": 5, "position": "before_char",
                  "priority": 4, "name": "灵田条目", "case_sensitive": true,
                  "extensions": {
                    "position": 4, "depth": 6, "probability": 50, "selectiveLogic": 3,
                    "group": "g1", "sticky": 2, "exclude_recursion": true,
                    "automation_id": "a1", "outlet_name": "o1", "triggers": ["normal"],
                    "match_scenario": true, "custom_ext": "x"
                  }
                },
                {}
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun 角色卡_extensions覆盖字符串position与全字段映射() {
        val parsed = WorldBookCodec.parse(cardFixture, "兜底名")
        assertEquals(WorldBookCodec.WorldBookFormat.CHARACTER_CARD, parsed.format)
        val e = parsed.entries.single { it.uid == 10 }
        assertEquals(listOf("灵田"), decodeStringList(e.keysJson))
        assertEquals(listOf("浇水"), decodeStringList(e.secondaryKeysJson))
        assertFalse("enabled:false 直取", e.enabled)
        assertTrue(e.constant)
        assertEquals(5, e.insertionOrder)
        assertEquals("extensions.position 数字必须压过 'before_char' 字符串", 4, e.position)
        assertEquals(6, e.depth)
        assertEquals(50, e.probability)
        assertEquals(3, e.selectiveLogic)
        assertEquals("g1", e.groupName)
        assertEquals(2, e.sticky)
        assertTrue(e.excludeRecursion)
        assertNull("条目级 case_sensitive 按酒馆导入规则忽略（只认 extensions 里的）", e.caseSensitive)
    }

    @Test
    fun 角色卡_保留档与卡独有字段进extraJson() {
        val extras = WorldBookCodec.parse(cardFixture, "兜底名")
            .entries.single { it.uid == 10 }.extraJson.toObj()
        assertEquals("o1", extras["outletName"]?.jsonPrimitive?.content)
        assertEquals("a1", extras["automationId"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(true), extras["matchScenario"])
        assertTrue(extras.containsKey("triggers"))
        // extensions 整包保留（与酒馆导入一致），含未消费的 custom_ext
        assertEquals("x", extras["extensions"]!!.jsonObject["custom_ext"]?.jsonPrimitive?.content)
        // 卡独有字段不丢
        assertEquals(JsonPrimitive(4), extras["priority"])
        assertEquals("灵田条目", extras["name"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(true), extras["case_sensitive"])
        assertFalse("已映射进列的键不得在 extras 顶层重复", extras.containsKey("position"))
    }

    @Test
    fun 角色卡_最小条目用卡格式默认值() {
        val e = WorldBookCodec.parse(cardFixture, "兜底名").entries.single { it.uid != 10 }
        assertEquals("id 缺失用数组下标", 1, e.uid)
        assertEquals("position 缺失 = after(1)，与酒馆导入一致（≠独立格式默认 0）", 1, e.position)
        assertFalse("卡格式 selective 默认 false（≠独立格式默认 true）", e.selective)
        assertTrue(e.enabled)
        assertEquals(100, e.probability)
        assertEquals(4, e.depth)
        assertEquals(100, e.insertionOrder)
    }

    @Test
    fun 角色卡_书级字段与书名兜底用角色名() {
        val book = WorldBookCodec.parse(cardFixture, "兜底名").book
        assertEquals("小翠的世界书", book.name)
        assertEquals(2, book.scanDepth)
        assertEquals(1, book.extraJson.toObj()["extensions"]!!.jsonObject["book_ext"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun 裸character_book_entries数组自动识别() {
        val parsed = WorldBookCodec.parse(
            """{"name":"裸书","entries":[{"keys":["a"],"content":"b"}]}""",
            "兜底名",
        )
        assertEquals(WorldBookCodec.WorldBookFormat.CHARACTER_BOOK, parsed.format)
        assertEquals("裸书", parsed.book.name)
        assertEquals(listOf("a"), decodeStringList(parsed.entries.single().keysJson))
    }

    @Test
    fun 角色卡导入后roundTrip_不动点() {
        val a = WorldBookCodec.parse(cardFixture, "兜底名")
        val b = WorldBookCodec.parse(WorldBookCodec.exportToStJson(a.book, a.entries), "兜底名")
        val c = WorldBookCodec.parse(WorldBookCodec.exportToStJson(b.book, b.entries), "兜底名")
        assertEquals(
            b.entries.map { it.normalized() }.sortedBy { it.uid },
            c.entries.map { it.normalized() }.sortedBy { it.uid },
        )
        // 卡→ST 后列字段稳定
        assertEquals(
            a.entries.map { it.columnsOnly() }.sortedBy { it.uid },
            b.entries.map { it.columnsOnly() }.sortedBy { it.uid },
        )
    }
}
