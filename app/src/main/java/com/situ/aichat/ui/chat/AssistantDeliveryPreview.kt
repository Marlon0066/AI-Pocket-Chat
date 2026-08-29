package com.situ.aichat.ui.chat

import com.situ.aichat.sticker.StickerTagParser

/**
 * 助手投递收尾时写入会话列表「最后一条」预览的纯决策（无副作用·便于单测，对偶于读侧 [chatListPreviewText]）。
 *
 * - **线下见面回合（[isOffline]=true）：返回 null = 不写预览**。见面期 AI 叙事正文带 `[叙述]/[对话]/[动作]/[场景:…]/[时间:…]`
 *   等沉浸标签（落库时 `preserveOfflineTags` 故意保留供沉浸渲染解析），绝不可外显进日常聊天列表（方案 A·见
 *   [com.situ.aichat.offline.OfflineChatVisibility]：见面期间产生的一切消息都不进日常聊天）。调用方据此改为仅
 *   `touchLastMessageDate` 保鲜排序、不动预览文案，列表保持入场标记「正在见面中…」直到见面收尾覆写。
 * - **普通在线消息**：表情包标签 → `[表情包]`（1:1 iOS `replaceStickerTagsForDisplay`），并截断 50 字。与原
 *   [finalizeDelivery][ChatViewModel] 行为逐字一致（非线下路径零改动）。
 */
internal fun assistantDeliveryPreview(content: String, isOffline: Boolean): String? =
    if (isOffline) null else StickerTagParser.replaceStickerTagsForDisplay(content).take(50)
