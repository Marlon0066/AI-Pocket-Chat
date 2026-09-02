package com.situ.aichat.prompt.growth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 搬迁保真钉（活人感内核卷零 chunk1）：[RelationshipBands] 是「只搬不改」的定义点汇总，
 * 任何一个数值/关键词/顺序被顺手改动都必须在此变红。
 *
 * **断言值在此重新打字为字面量**（PITFALLS §1e：绝不 `assertEquals(A.x, A.x)` 式自证），
 * 来源 = 卷零图纸 §3.1 与搬迁前的四处源码现值。
 */
class RelationshipBandsPinTest {

    // MARK: - ① 行为剧本六档边界

    @Test fun scriptBands_pinnedValues() {
        assertEquals(20, RelationshipBands.SCRIPT_LOW)
        assertEquals(40, RelationshipBands.SCRIPT_MID_LOW)
        assertEquals(40, RelationshipBands.SCRIPT_SILENT_MIN)
        assertEquals(60, RelationshipBands.SCRIPT_SILENT_MAX)
        assertEquals(80, RelationshipBands.SCRIPT_HIGH)
    }

    // MARK: - ② 原型二维渲染水位档

    @Test fun waterBands_pinnedValues() {
        assertEquals(0.30f, RelationshipBands.WATER_L1_MAX, 0f)
        assertEquals(0.65f, RelationshipBands.WATER_SILENT_MAX, 0f)
        assertEquals(0.90f, RelationshipBands.WATER_L3_MAX, 0f)
    }

    // MARK: - ③ 关系跃迁探测边界（非均匀·末位 100 破 96+ 饱和死锁，勿删）

    @Test fun crossingBoundaries_pinnedContentAndOrder() {
        assertArrayEquals(intArrayOf(10, 20, 30, 50, 70, 85, 95, 100), RelationshipBands.CROSSING_BOUNDARIES)
        assertEquals(100, RelationshipBands.CROSSING_BOUNDARIES.last())
    }

    // MARK: - ④ 日程关系档位

    @Test fun scheduleTier_pinnedDimensionsAndBounds() {
        assertEquals(
            listOf("familiarity", "trust", "closeness", "attachment"),
            RelationshipBands.TIER_DIMENSION_KEYS,
        )
        assertEquals(25, RelationshipBands.TIER_FAMILIAR_MAX)
        assertEquals(50, RelationshipBands.TIER_CLOSE_MAX)
        assertEquals(75, RelationshipBands.TIER_DEEP_MAX)
    }

    // MARK: - ⑤ 名分平衡点关键词表（顺序即优先级）

    @Test fun equilibriumKeywords_pinnedContentAndOrder() {
        assertEquals(
            listOf("恋人", "热恋", "老夫老妻", "灵魂伴侣", "伴侣", "爱人", "lover", "partner", "soulmate"),
            RelationshipBands.EQUILIBRIUM_INTIMATE,
        )
        assertEquals(
            listOf("好朋友", "死党", "闺蜜", "知己", "暧昧", "损友", "best friend", "close friend"),
            RelationshipBands.EQUILIBRIUM_CLOSE,
        )
        assertEquals(listOf("朋友", "普通朋友", "网友", "friend"), RelationshipBands.EQUILIBRIUM_FRIEND)
        assertEquals(listOf("陌生人", "点头之交", "stranger"), RelationshipBands.EQUILIBRIUM_DISTANT)
    }

    @Test fun equilibriumValues_pinned() {
        assertEquals(70, RelationshipBands.EQUILIBRIUM_INTIMATE_VALUE)
        assertEquals(55, RelationshipBands.EQUILIBRIUM_CLOSE_VALUE)
        assertEquals(40, RelationshipBands.EQUILIBRIUM_FRIEND_VALUE)
        assertEquals(20, RelationshipBands.EQUILIBRIUM_DISTANT_VALUE)
        assertEquals(35, RelationshipBands.EQUILIBRIUM_DEFAULT_VALUE)
    }

    /**
     * 匹配顺序即优先级的行为钉：「好朋友」同时含子串「朋友」，必须先命中 close(55) 而非 friend(40)；
     * 「老夫老妻」不含任何 friend 词 → intimate(70)。顺序一旦被调乱，本例即红。
     */
    @Test fun equilibriumPoint_orderIsPriority() {
        assertEquals(55, equilibriumPoint("好朋友"))
        assertEquals(40, equilibriumPoint("朋友"))
        assertEquals(70, equilibriumPoint("老夫老妻"))
        assertEquals(20, equilibriumPoint("陌生人"))
        assertEquals(35, equilibriumPoint(null))
        assertEquals(35, equilibriumPoint("同事"))
    }
}
