package com.situ.aichat.data.model

/**
 * 消息正文里的**哨兵字面量**——写入端与识别端必须引同一个常量。
 *
 * ⚠️ 强耦合（REDLINES 已登记）：[IMAGE_PLACEHOLDER] 由发图路径写进 `MessageEntity.content`，
 * 又被 `MemoryService.renderImageSemantics` 判等以决定「替换掉占位」还是「正文 + 附注」。
 * 两端任何一侧改字面量而另一侧没跟，图片消息就会把 `[图片]` 三个字原样喂进提示词/长期记忆。
 * 图片消息**不新增 [MessageKind]**（照 iOS 口径 = PLAIN_TEXT + 侧车字段 `imageRelativePath`），
 * 所以 `messageLlmSafeText` 的穷举 when 拦不住它——这个常量就是替代的护栏。
 */
object MessageContentSentinels {
    /** 纯图片消息（无配文）的正文占位。 */
    const val IMAGE_PLACEHOLDER = "[图片]"
}
