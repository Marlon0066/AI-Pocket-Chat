package com.situ.aichat.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OfflineContentParser` tests (P10.2a-2), reverse-derived from iOS `OfflineContentParser`:
 * line-by-line state machine over the 10 block types, single-line tags, multi-line open/close,
 * bare-text → narration fallback, markdown-prefix stripping, sticker stripping, offline-JSON skip,
 * user-block parsing, and tag cleanup.
 */
class OfflineContentParserTest {

    @Test fun scene_header_splits_location_and_time() {
        assertEquals(
            listOf(OfflineContentBlock.SceneHeader("公园", "下午 16:30")),
            OfflineContentParser.parse("[场景：公园 · 下午 16:30]"),
        )
    }

    @Test fun scene_header_without_time() {
        assertEquals(
            listOf(OfflineContentBlock.SceneHeader("公园", "")),
            OfflineContentParser.parse("[场景：公园]"),
        )
    }

    @Test fun scene_header_empty_location_omits_empty_subsequence() {
        // 畸形「空地点·时间」→ 去空子串后时间升为地点（1:1 iOS split omittingEmptySubsequences，复核 LOW#8）。
        assertEquals(
            listOf(OfflineContentBlock.SceneHeader("黄昏", "")),
            OfflineContentParser.parse("[场景：·黄昏]"),
        )
    }

    @Test fun transition_and_inline_timeskip() {
        assertEquals(listOf(OfflineContentBlock.SceneTransition), OfflineContentParser.parse("[过渡]"))
        assertEquals(listOf(OfflineContentBlock.TimeSkip("半小时后")), OfflineContentParser.parse("[时间：半小时后]"))
    }

    @Test fun inline_open_close_same_line() {
        assertEquals(
            listOf(OfflineContentBlock.CharacterDialogue("你好呀")),
            OfflineContentParser.parse("[对话]你好呀[/对话]"),
        )
    }

    @Test fun multiline_block_joins_with_newline() {
        assertEquals(
            listOf(OfflineContentBlock.Environment("月光很亮\n风有点凉")),
            OfflineContentParser.parse("[环境]\n月光很亮\n风有点凉\n[/环境]"),
        )
    }

    @Test fun bare_text_is_narration() {
        assertEquals(
            listOf(OfflineContentBlock.Narration("他笑了笑，没说话")),
            OfflineContentParser.parse("他笑了笑，没说话"),
        )
    }

    @Test fun multiple_blocks_in_order() {
        val blocks = OfflineContentParser.parse(
            "[场景：咖啡馆 · 傍晚]\n[环境]灯光很暖[/环境]\n[动作]她坐下[/动作]\n[对话]来啦[/对话]\n[内心]有点紧张[/内心]",
        )
        assertEquals(5, blocks.size)
        assertTrue(blocks[0] is OfflineContentBlock.SceneHeader)
        assertEquals(OfflineContentBlock.Environment("灯光很暖"), blocks[1])
        assertEquals(OfflineContentBlock.Action("她坐下"), blocks[2])
        assertEquals(OfflineContentBlock.CharacterDialogue("来啦"), blocks[3])
        assertEquals(OfflineContentBlock.InnerMonologue("有点紧张"), blocks[4])
    }

    @Test fun markdown_prefixes_are_stripped_before_tag_detection() {
        assertEquals(listOf(OfflineContentBlock.CharacterDialogue("嗨")), OfflineContentParser.parse("- [对话]嗨[/对话]"))
        assertEquals(listOf(OfflineContentBlock.Environment("静")), OfflineContentParser.parse("> [环境]静[/环境]"))
        assertEquals(listOf(OfflineContentBlock.Action("走")), OfflineContentParser.parse("1. [动作]走[/动作]"))
    }

    @Test fun sticker_tags_stripped_and_offline_json_skipped() {
        // [sticker:x] removed before parsing; a rebroadcast offline JSON line is skipped.
        val blocks = OfflineContentParser.parse("[叙述]开场[/叙述]\n{\"type\":\"offline_end\",\"finalMood\":\"warm\"}")
        assertEquals(listOf(OfflineContentBlock.Narration("开场")), blocks)
    }

    @Test fun empty_after_cleaning_returns_empty() {
        assertEquals(emptyList<OfflineContentBlock>(), OfflineContentParser.parse("   \n  \n"))
    }

    @Test fun parse_user_blocks_four_tags_in_fixed_order() {
        val blocks = OfflineContentParser.parseUserBlocks(
            "[环境]咖啡馆[/环境][动作]坐下[/动作][对话]点了拿铁[/对话][内心]有点紧张[/内心]",
        )
        assertEquals(
            listOf(
                OfflineContentBlock.Environment("咖啡馆"),
                OfflineContentBlock.Action("坐下"),
                OfflineContentBlock.UserAction("点了拿铁"), // 用户"对话" → UserAction
                OfflineContentBlock.InnerMonologue("有点紧张"),
            ),
            blocks,
        )
    }

    @Test fun parse_user_blocks_skips_missing_and_empty() {
        assertEquals(listOf(OfflineContentBlock.Action("只填动作")), OfflineContentParser.parseUserBlocks("[动作]只填动作[/动作]"))
        assertEquals(emptyList<OfflineContentBlock>(), OfflineContentParser.parseUserBlocks("[环境]  [/环境]")) // empty content
        assertEquals(emptyList<OfflineContentBlock>(), OfflineContentParser.parseUserBlocks("普通一句话")) // non-immersive
    }

    // ── 卷一 E2：用户侧贴纸标签绝不在剧场里露字面量（AI 侧 parse 早有同款前置清洗）──

    @Test fun parse_user_blocks_replaces_sticker_tags_with_display_text() {
        val blocks = OfflineContentParser.parseUserBlocks("[对话]你看这个[sticker:abc123][/对话]")
        assertEquals(listOf(OfflineContentBlock.UserAction("你看这个[表情包]")), blocks)
    }

    @Test fun parse_user_blocks_sticker_only_message_still_renders() {
        val blocks = OfflineContentParser.parseUserBlocks("[动作][sticker:abc123][/动作]")
        assertEquals(listOf(OfflineContentBlock.Action("[表情包]")), blocks)
        // 反向证据：结果里绝不含 sticker 字面量。
        assertEquals(false, blocks.any { it.toString().contains("[sticker:") })
    }

    @Test fun parse_user_blocks_without_sticker_unchanged() {
        assertEquals(
            listOf(OfflineContentBlock.UserAction("就这样吧")),
            OfflineContentParser.parseUserBlocks("[对话]就这样吧[/对话]"),
        )
    }

    @Test fun strip_all_tags_removes_markup_and_collapses_blank_runs() {
        assertEquals("他说你好然后笑了", OfflineContentParser.stripAllTags("他说[对话]你好[/对话]然后[动作]笑了[/动作]"))
        assertEquals("a\n\nb", OfflineContentParser.stripAllTags("a\n\n\nb"))
        assertEquals("到了", OfflineContentParser.stripAllTags("[场景：公园 · 下午]到了"))
    }

    // MARK: - D1① 同行多标签（旧行为=闭合标签后剩余内容静默丢弃）

    @Test fun same_line_multiple_closed_tags_all_preserved() {
        val blocks = OfflineContentParser.parse("[对话]好呀[/对话][动作]她笑了[/动作]")
        assertEquals(
            listOf(OfflineContentBlock.CharacterDialogue("好呀"), OfflineContentBlock.Action("她笑了")),
            blocks,
        )
    }

    @Test fun same_line_three_tags_and_trailing_bare_text() {
        val blocks = OfflineContentParser.parse("[环境]很静[/环境][对话]走吧[/对话]她转身。")
        assertEquals(
            listOf(
                OfflineContentBlock.Environment("很静"),
                OfflineContentBlock.CharacterDialogue("走吧"),
                OfflineContentBlock.Narration("她转身。"),
            ),
            blocks,
        )
    }

    @Test fun inline_scene_tag_with_trailing_content_on_same_line() {
        val blocks = OfflineContentParser.parse("[场景：公园 · 深夜][环境]虫鸣。[/环境]")
        assertEquals(
            listOf(
                OfflineContentBlock.SceneHeader("公园", "深夜"),
                OfflineContentBlock.Environment("虫鸣。"),
            ),
            blocks,
        )
    }

    @Test fun inline_time_tag_with_trailing_content_on_same_line() {
        val blocks = OfflineContentParser.parse("[时间：半小时后][叙述]你们到了。[/叙述]")
        assertEquals(
            listOf(OfflineContentBlock.TimeSkip("半小时后"), OfflineContentBlock.Narration("你们到了。")),
            blocks,
        )
    }

    @Test fun transition_tag_with_trailing_content_on_same_line() {
        val blocks = OfflineContentParser.parse("[过渡][对话]到了。[/对话]")
        assertEquals(
            listOf(OfflineContentBlock.SceneTransition, OfflineContentBlock.CharacterDialogue("到了。")),
            blocks,
        )
    }

    @Test fun close_tag_picked_by_position_not_enum_order() {
        // 行内先闭 [/动作] 再闭 [/对话]——按位置最靠前的收口，两块都保留。
        val blocks = OfflineContentParser.parse("[动作]点头[/动作][内心]想了想[/内心]")
        assertEquals(
            listOf(OfflineContentBlock.Action("点头"), OfflineContentBlock.InnerMonologue("想了想")),
            blocks,
        )
    }

    // MARK: - D1② 全角【】写法归一化（未知标签不动）

    @Test fun full_width_known_tags_normalized() {
        val blocks = OfflineContentParser.parse("【环境】风很轻。【/环境】\n【对话】来了？【/对话】")
        assertEquals(
            listOf(OfflineContentBlock.Environment("风很轻。"), OfflineContentBlock.CharacterDialogue("来了？")),
            blocks,
        )
    }

    @Test fun full_width_scene_and_time_inline_tags_normalized() {
        val blocks = OfflineContentParser.parse("【场景：便利店 · 晚上】\n【时间：十分钟后】")
        assertEquals(
            listOf(OfflineContentBlock.SceneHeader("便利店", "晚上"), OfflineContentBlock.TimeSkip("十分钟后")),
            blocks,
        )
    }

    @Test fun unknown_bracket_tags_stay_literal_text() {
        // 自创标签不猜测语义：整行按普通文本归入当前块/叙述兜底。
        val blocks = OfflineContentParser.parse("【微笑】她看着你")
        assertEquals(listOf(OfflineContentBlock.Narration("【微笑】她看着你")), blocks)
    }

    @Test fun multiline_wellformed_input_unchanged_by_hardening() {
        // 回归锚：规范多行输入的解析结果与加固前逐块一致。
        val blocks = OfflineContentParser.parse(
            "[场景：河边 · 黄昏]\n[环境]水面泛金。[/环境]\n[对话]走走？[/对话]\n[动作]她伸了个懒腰。[/动作]",
        )
        assertEquals(
            listOf(
                OfflineContentBlock.SceneHeader("河边", "黄昏"),
                OfflineContentBlock.Environment("水面泛金。"),
                OfflineContentBlock.CharacterDialogue("走走？"),
                OfflineContentBlock.Action("她伸了个懒腰。"),
            ),
            blocks,
        )
    }
}
