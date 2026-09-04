package com.situ.aichat.ui.chat

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.CustomAccessibilityAction
import com.situ.aichat.data.local.entity.MessageEntity
import kotlinx.coroutines.launch

/**
 * 行级读屏动作面（M2 Y2 收编·自 [MessageRow] 抽出）：读屏用户不必先开视觉菜单即可触发消息动作，
 * 条目与显示条件与沉浸菜单**逐字同源**（同一个 [immersiveMenuActions]）。
 *
 * **陈旧快照修（2026-09-04·独立复核 R1 附注揪出的既有缺陷）**：动作 lambda 原先直接闭包捕获传入的
 * [message] 实例，而 `remember` 的 key 不含 `content`（有意为之——流式打字每帧都在更新同一条消息，
 * 把 content 纳入 key 会让整个动作面逐帧重建）。两者相加的后果：AI 边打字边被读屏用户触发「复制」/
 * 「引用」时，拿到的是**这条消息首帧的半截文本**。改经 [rememberUpdatedState] 读最新实例——列表每次
 * 重组把新 entity 写进同一个 State，lambda 读的恒是当刻值，动作面本身仍不重建。
 *
 * 视觉菜单那条路无此问题（它的 message 取自长按当刻的 `ChatImmersiveMenuState.target`）。
 */
@Composable
internal fun rememberMessageRowA11yActions(
    message: MessageEntity,
    isUser: Boolean,
    canRegenerate: Boolean,
    actions: MessageRowActions,
    /**
     * 本行是否并入行级动作面（语音/混合贴纸行有意不并·见 [MessageRow]）。**做成参数而非在调用点包 if**：
     * 复核 R2 🔵-4——它依赖 `content`（贴纸标签），流式中途翻 false 会让整个组被丢弃、连带取消
     * [rememberCoroutineScope]，此刻在飞的剪贴板写入会被静默吞掉。恒调用 = scope 生命周期与行等长。
     */
    eligible: Boolean,
): List<CustomAccessibilityAction> {
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()
    // 恒指向最新 entity（内容随流式刷新），故意不进下面的 remember key。
    val current by rememberUpdatedState(message)
    if (!eligible) return emptyList()
    // 复核 R1 🟡-1：`canQuote` 读 `content`，而下面的 remember **有意不含 content**（防流式逐帧重建）——
    // 算在 remember 体内会被冻在首帧。占位气泡（ChatMessageGrouping 合成·content=""·kind 默认 plain_text）
    // 与「STT 还没回来的语音」都会随后**沿用同一 uuid 原地变身**成真消息，判据要跟着正文走才不会停在旧答案。
    // 故提到外面每帧现算、并进 key（布尔极少翻转，不会引发原先担心的逐帧重建；每帧代价=几次串比较，
    // 右滑那条路本就在付）。
    val canQuote = messageCanBeQuoted(message)
    return remember(message.messageUUID, canRegenerate, actions, canQuote) {
        immersiveMenuActions(
            isUser,
            message.imageRelativePath != null,
            canRegenerate,
            canQuote = canQuote,
        ).map { action ->
            CustomAccessibilityAction(immersiveMenuActionLabel(action)) {
                when (action) {
                    ImmersiveMenuAction.COPY -> copyScope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", messageCopyText(current))))
                    }
                    ImmersiveMenuAction.SAVE_IMAGE -> actions.onSaveImage(current)
                    ImmersiveMenuAction.QUOTE -> actions.onQuote(current)
                    ImmersiveMenuAction.REGENERATE -> actions.onRegenerate()
                    ImmersiveMenuAction.DELETE -> actions.onDelete(current)
                }
                true
            }
        }
    }
}
