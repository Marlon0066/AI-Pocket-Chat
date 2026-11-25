package com.situ.aichat.ui.worldbook

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppActionChip
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.appCardSurface

/**
 * 写作向导（WB7b·契约 §12.10「新建时引导写全」）：类别 → 预选触发方式 + 标题/内容的**具体示例 placeholder**。
 * 只给引导不落假数据——placeholder 不写进条目，用户不理会也不会保存出半成品。
 */
enum class WorldBookGuideCategory(
    @param:StringRes val labelRes: Int,
    @param:StringRes val titlePlaceholderRes: Int,
    @param:StringRes val contentPlaceholderRes: Int,
    /** 基调 = 常驻，其余 = 关键词（契约 §12.10）。 */
    val constant: Boolean,
) {
    BASIS(R.string.wb_guide_basis, R.string.wb_guide_basis_title_ph, R.string.wb_guide_basis_ph, constant = true),
    PLACE(R.string.wb_guide_place, R.string.wb_guide_place_title_ph, R.string.wb_guide_place_ph, constant = false),
    FACTION(R.string.wb_guide_faction, R.string.wb_guide_faction_title_ph, R.string.wb_guide_faction_ph, constant = false),
    PERSON(R.string.wb_guide_person, R.string.wb_guide_person_title_ph, R.string.wb_guide_person_ph, constant = false),
    RULES(R.string.wb_guide_rules, R.string.wb_guide_rules_title_ph, R.string.wb_guide_rules_ph, constant = false),
    ITEM(R.string.wb_guide_item, R.string.wb_guide_item_title_ph, R.string.wb_guide_item_ph, constant = false),
    ;

    companion object {
        fun fromKeyOrNull(key: String?): WorldBookGuideCategory? =
            entries.firstOrNull { it.name == key }
    }
}

/**
 * 书详情里的向导卡：空书 = 全量展开（说明 + 类别 chips）；已有条目 = 降级为一行小入口，点开再展开
 * （契约 §12.10·引导只鼓励不强制）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorldBookGuideCard(
    hasEntries: Boolean,
    onPickCategory: (WorldBookGuideCategory) -> Unit,
    modifier: Modifier = Modifier,
    cardModifier: @Composable () -> Modifier = { Modifier.appCardSurface() },
) {
    val colors = AppTheme.colors
    var expanded by rememberSaveable(hasEntries) { mutableStateOf(!hasEntries) }

    Column(modifier = modifier.fillMaxWidth().then(cardModifier())) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableScale { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(if (hasEntries) R.string.wb_guide_more else R.string.wb_guide_title),
                    style = AppTypography.titleSmall,
                    color = colors.text.primary,
                )
                if (expanded) {
                    Text(
                        stringResource(R.string.wb_guide_intro),
                        style = AppTypography.caption,
                        color = colors.text.secondary,
                    )
                }
            }
            Icon(
                if (expanded) AppActionIcons.ChevronUp else AppActionIcons.ChevronRight,
                contentDescription = null,
                tint = colors.text.secondary,
            )
        }
        if (expanded) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WorldBookGuideCategory.entries.forEach { category ->
                    AppActionChip(
                        onClick = { onPickCategory(category) },
                        label = stringResource(category.labelRes),
                    )
                }
            }
        }
    }
}
