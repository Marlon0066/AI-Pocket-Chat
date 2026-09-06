package com.situ.aichat.ui.pet

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.pet.PetNeglectPhase
import com.situ.aichat.pet.neglectPhase
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 宠物状态行的**单源**映射（图纸 2026-09-06-宠物总览页复活 §3.2）。
 *
 * 三处消费者共用，任何一处都**不许**另写 when 分支：
 * ① 世界卡信息条（`ui/moments/WorldCardInfo` 的 `PetNeedKind` 由此派生）；
 * ② 动态页宠物条尾行（`ui/moments/PetHubStrip`）；
 * ③ 宠物总览页卡内状态行（[PetListScreen]）。
 *
 * 文案取 `pet_hub_*` 既有键（圈子枢纽契约 §2.2 词表·**纯文字 + 状态色圆点，无 emoji**）；
 * [PetStatusKind.CONTENT] 无对应键 —— 由调用方拼「{物种} · {成长期}」。
 */
enum class PetStatusKind { RAN_AWAY, SICK, HUNGRY, SAD, HAPPY, CONTENT }

/**
 * 宠物实体 → 状态态。复用 [PetMoodType.from] 单一心情判定（DRY·不重写阈值），
 * 再用 [neglectPhase] 把 SICK 拆出「离家出走」。
 */
fun petStatusKind(pet: CharacterPetEntity): PetStatusKind = when (PetMoodType.from(pet)) {
    PetMoodType.SICK ->
        if (pet.neglectPhase == PetNeglectPhase.RAN_AWAY) PetStatusKind.RAN_AWAY else PetStatusKind.SICK
    PetMoodType.HUNGRY -> PetStatusKind.HUNGRY
    PetMoodType.SAD -> PetStatusKind.SAD
    PetMoodType.HAPPY -> PetStatusKind.HAPPY
    PetMoodType.CONTENT -> PetStatusKind.CONTENT
}

/**
 * 「需要你」= 前四态。世界卡信息条只顶这四种（决策 41⑥ quiet 哲学：好着不打扰），
 * 宠物条尾行也据此判「都好着呢」。
 */
val PetStatusKind.needsAttention: Boolean
    get() = this == PetStatusKind.RAN_AWAY || this == PetStatusKind.SICK ||
        this == PetStatusKind.HUNGRY || this == PetStatusKind.SAD

/** 状态文案资源；[PetStatusKind.CONTENT] → null（调用方拼「{物种} · {成长期}」）。 */
@StringRes
fun petStatusRes(kind: PetStatusKind): Int? = when (kind) {
    PetStatusKind.RAN_AWAY -> R.string.pet_hub_runaway
    PetStatusKind.SICK -> R.string.pet_hub_sick
    PetStatusKind.HUNGRY -> R.string.pet_hub_hungry
    PetStatusKind.SAD -> R.string.pet_hub_sad
    PetStatusKind.HAPPY -> R.string.pet_hub_happy
    PetStatusKind.CONTENT -> null
}

/** 状态圆点色（图纸 §4.3）：病/走 → onError，饿 → onWarning，闷 → tertiary，好 → onSuccess，满足 → tertiary。 */
@Composable
fun petStatusColor(kind: PetStatusKind): Color {
    val colors = AppTheme.colors
    return when (kind) {
        PetStatusKind.RAN_AWAY, PetStatusKind.SICK -> colors.status.onError
        PetStatusKind.HUNGRY -> colors.status.onWarning
        PetStatusKind.SAD, PetStatusKind.CONTENT -> colors.text.tertiary
        PetStatusKind.HAPPY -> colors.status.onSuccess
    }
}
