package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.affectField
import com.situ.aichat.data.model.intentQueue
import com.situ.aichat.data.model.personaOperators
import com.situ.aichat.data.model.relationshipPressure
import com.situ.aichat.prompt.InnerStateRenderer
import com.situ.aichat.prompt.InnerStateScripts
import com.situ.aichat.prompt.growth.fieldForRead
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/**
 * 顶栏副标的「此刻」一句（图纸 2026-09-05 卷二B §4.9 · 契约 §5.1 · A-6）：把活人感内核已经算好的
 * 【此刻】内心行**取来读第一句**，放在副标回退链的首位（此刻 → 日程 → 心情行）。
 *
 * **UI 侧纯派生、VM 零改、数据零新增**（契约 §3.1 #7）：调用的是提示词侧同一个 [InnerStateRenderer.render]，
 * 逐参同源——场走读值 [fieldForRead]、双压 / 算子 / 意图全取角色实体的解码访问器。唯一差异是时区取系统时区
 * （提示词侧取日程时区），它只影响台词变体轮换，副标不介意（A-6）。
 *
 * **算法与提示词零碰**：本件只**读** `render` 的产出，连 [InnerStateScripts.PREFIX] 也只用于剥前缀。
 */
internal fun liuliInnerStateFirstSentence(
    character: CharacterEntity,
    userName: String,
    nowMs: Long,
    zone: ZoneId,
    growthEnabled: Boolean,
): String? {
    // 成长系统关 ⇒ 场系统整个不在（同 PromptBuilderSchedule.innerLine 的第一道闸）。
    if (!growthEnabled) return null
    val rendered = InnerStateRenderer.render(
        field = fieldForRead(character.affectField, nowMs, zone),
        pressure = character.relationshipPressure,
        operators = character.personaOperators,
        userName = userName,
        hour = Instant.ofEpochMilli(nowMs).atZone(zone).hour,
        now = nowMs,
        intents = character.intentQueue.intents,
        zone = zone,
    )
    return liuliFirstInnerSentence(rendered)
}

/**
 * 从整段内心行里取第一句（纯函数·T1-2）：剥 [InnerStateScripts.PREFIX]，取到第一个「。」为止（含句号）；
 * 整段没有句号就整段都要（`render` 已保证单句 ≤74 字）。空段 / 只有前缀 ⇒ null（副标回退到下一档）。
 */
internal fun liuliFirstInnerSentence(rendered: String): String? {
    if (rendered.isEmpty()) return null
    val body = rendered.removePrefix(InnerStateScripts.PREFIX)
    val end = body.indexOf(SENTENCE_END)
    val first = if (end >= 0) body.substring(0, end + 1) else body
    return first.takeIf { it.isNotBlank() }
}

/**
 * 副标用的「此刻」一句（[LiuliChatScreen] 收一次下传）：角色 / 昵称 / 成长开关任一变即重算，另有
 * 60 秒自转的心跳——与 VM 的 `scheduleTicker` 同周期、但**不进 VM**（纯派生无状态·A-6）。
 */
@Composable
internal fun rememberLiuliInnerStateLine(
    character: CharacterEntity?,
    userNickname: String?,
    growthEnabled: Boolean,
): String? {
    // 无昵称回退「用户」——与提示词侧 `resolvedUserName` 同一个兜底资源。
    val fallback = stringResource(R.string.pb_user_fallback)
    val userName = userNickname?.trim().orEmpty().ifEmpty { fallback }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(INNER_LINE_TICK_MS)
            tick++
        }
    }
    return remember(character, userName, growthEnabled, tick) {
        character?.let {
            liuliInnerStateFirstSentence(it, userName, System.currentTimeMillis(), ZoneId.systemDefault(), growthEnabled)
        }
    }
}

/** 句号（内心行的句子一律以它收尾·`InnerStateScripts` 口径）。 */
private const val SENTENCE_END = '。'

/** 副标心跳周期（与 `ChatViewModel.scheduleTicker` 同 60 秒）。 */
private const val INNER_LINE_TICK_MS = 60_000L
