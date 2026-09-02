package com.situ.aichat.prompt.growth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 活人感内核·修缮卷 T1-10（图纸 §3.6）：[intLenient] 四种形状——整数 / 小数取整 / 数字串 / `{pos,neg}` 差；其它 ⇒ null。
 * 断言从规格独立反推（`roundToInt` = 半数向正无穷：2.5 → 3、-2.5 → -2；`{"pos":3,"neg":1}` ⇒ 2；`{}` 无 pos/neg ⇒ null）。
 */
class JsonLenientTest {

    private fun el(json: String): JsonElement? = Json.parseToJsonElement("""{"v":$json}""").jsonObject["v"]

    @Test
    fun integer_isReturnedAsIs() {
        assertEquals(3, el("3").intLenient())
        assertEquals(-10, el("-10").intLenient())
        assertEquals(0, el("0").intLenient())
    }

    @Test
    fun decimal_isRounded() {
        assertEquals(3, el("2.6").intLenient())
        assertEquals(2, el("2.4").intLenient())
        assertEquals(3, el("2.5").intLenient())
        assertEquals("roundToInt 半数向正无穷（与图纸 §3.2 预算取整同口径）", -2, el("-2.5").intLenient())
        assertEquals(-3, el("-2.6").intLenient())
    }

    @Test
    fun numericString_isParsed_andRounded() {
        assertEquals(3, el("\"3\"").intLenient())
        assertEquals(3, el("\" 2.6 \"").intLenient())
        assertEquals(-2, el("\"-2\"").intLenient())
    }

    @Test
    fun posNegObject_yieldsDifference_otherObjectsNull() {
        assertEquals(2, el("""{"pos":3,"neg":1}""").intLenient())
        assertEquals(3, el("""{"pos":"3"}""").intLenient())
        assertEquals(-2, el("""{"neg":2.4}""").intLenient())
        assertNull(el("""{"x":1}""").intLenient())
        assertNull(el("{}").intLenient())
    }

    @Test
    fun nullBoolArrayAndGarbage_areNull() {
        assertNull((null as JsonElement?).intLenient())
        assertNull(JsonNull.intLenient())
        assertNull(el("true").intLenient())
        assertNull(el("[1,2]").intLenient())
        assertNull(el("\"很多\"").intLenient())
        assertNull("NaN 串不许炸", el("\"NaN\"").intLenient())
        assertNull(el("\"Infinity\"").intLenient())
    }
}
