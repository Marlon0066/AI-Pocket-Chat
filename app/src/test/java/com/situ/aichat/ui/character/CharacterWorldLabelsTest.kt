package com.situ.aichat.ui.character

import com.situ.aichat.R
import com.situ.aichat.data.local.dao.WorldBookSummary
import com.situ.aichat.data.local.entity.WorldBookEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1-4：世界段 / 世界观卡文案优先级（原住民 > 已加入 > 已挂世界书可一键切换 > 未加入；
 * 历史脏数据 `joined=true && worldbookBound=true` 优先显已加入态以便正常关开关离世）。
 */
class CharacterWorldLabelsTest {

    @Test
    fun `加入世界副标四态`() {
        assertEquals(R.string.char_world_join_sub_native, worldJoinSubtitleRes(worldbookBound = true, nativeOrigin = true, joined = true))
        assertEquals(R.string.char_world_join_sub_on, worldJoinSubtitleRes(worldbookBound = true, nativeOrigin = false, joined = true))
        assertEquals(R.string.char_world_join_sub_wb, worldJoinSubtitleRes(worldbookBound = true, nativeOrigin = false, joined = false))
        assertEquals(R.string.char_world_join_sub_off, worldJoinSubtitleRes(worldbookBound = false, nativeOrigin = false, joined = false))
    }

    @Test
    fun `世界脚注四态`() {
        assertEquals(R.string.char_world_foot_native, worldFooterRes(worldbookBound = true, nativeOrigin = true, joined = true))
        assertEquals(R.string.char_world_foot_on, worldFooterRes(worldbookBound = true, nativeOrigin = false, joined = true))
        assertEquals(R.string.char_world_foot_wb, worldFooterRes(worldbookBound = true, nativeOrigin = false, joined = false))
        assertEquals(R.string.char_world_foot_off, worldFooterRes(worldbookBound = false, nativeOrigin = false, joined = false))
    }

    @Test
    fun `住址尾巴三态`() {
        assertEquals(R.string.char_world_addr_native, worldAddressTailRes(nativeOrigin = true, sameCityAsUser = false))
        assertEquals(R.string.char_world_addr_same_city, worldAddressTailRes(nativeOrigin = false, sameCityAsUser = true))
        assertEquals(R.string.char_world_addr_remote, worldAddressTailRes(nativeOrigin = false, sameCityAsUser = false))
    }

    private fun book(name: String, entries: Int) =
        WorldBookSummary(book = WorldBookEntity(uuid = name, name = name), entryCount = entries, boundCount = 1)

    @Test
    fun `世界观卡五态优先级`() {
        val three = listOf(book("青云录", 4), book("长安", 2), book("江湖", 1))
        assertEquals(WorldBookCardState.NativeLocked, worldBookCardState(nativeOrigin = true, joinedWorld = true, bound = three))
        assertEquals(WorldBookCardState.WorldLocked, worldBookCardState(nativeOrigin = false, joinedWorld = true, bound = three))
        assertEquals(WorldBookCardState.None, worldBookCardState(nativeOrigin = false, joinedWorld = false, bound = emptyList()))
        assertEquals(WorldBookCardState.Single("青云录", 4), worldBookCardState(nativeOrigin = false, joinedWorld = false, bound = three.take(1)))
        assertEquals(WorldBookCardState.Multi("青云录", 2), worldBookCardState(nativeOrigin = false, joinedWorld = false, bound = three))
    }
}
