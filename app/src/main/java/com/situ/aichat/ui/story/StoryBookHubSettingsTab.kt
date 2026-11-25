@file:OptIn(ExperimentalMaterial3Api::class)

package com.situ.aichat.ui.story

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.story.StoryEditableField
import com.situ.aichat.story.StoryGlobalCraftValues
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppTheme

/** 设定 Tab 的全部回调（一处登记，免得入口函数长成 20 个形参）。 */
internal class StoryHubSettingsCallbacks(
    val onOpenField: (StoryEditableField) -> Unit,
    /** 「全局写作偏好 →」：跳 App 设置的故事创作子屏（卷四把全局项迁走后，书页只留这一个指路牌）。 */
    val onOpenGlobalSettings: () -> Unit,
    val onUpdateDraft: (((StorySettingsDraft) -> StorySettingsDraft)) -> Unit,
    val onSaveRole: (StoryCharacterRoleEntity) -> Unit,
    val onDeleteRole: (String) -> Unit,
    /** null = 没有故事创作 API 配置 → 角色弹层不给「AI 起草」钮。 */
    val onDraftPersona: (suspend (role: StoryCharacterRoleEntity, name: String, description: String) -> String?)?,
    val onChapterChoicesChange: (Boolean) -> Unit,
    val onSceneSnapshotChange: (Boolean) -> Unit,
    val onWorldInfoChange: (Boolean) -> Unit,
    val onReminderChange: (Boolean) -> Unit,
    val onSaveTemplate: (String) -> Unit,
    val onArchive: () -> Unit,
    val onDelete: () -> Unit,
    val onContinue: () -> Unit,
    val onRestart: () -> Unit,
)

/**
 * 书页「设定」Tab（故事二期卷二·mockup 屏 2·提案 §8）：**全部本书级**四组。
 *
 * 写法 / 角色 / 生成开关 / 连载与管理 —— 七个文本设定每行右侧一枚三态值标（[HubValueLabel]），点开进统一编辑页。
 * 卷二的过渡组「全局（暂驻）」已随卷四迁家整组删除：温度 / 段序 / 全局忌口现住 App 设置「故事 → 故事创作」，
 * 书页只在「连载与管理」组留一行「全局写作偏好 →」指过去（图纸 §4.3）。
 */
internal fun LazyListScope.storyHubSettingsItems(
    story: StoryEntity,
    draft: StorySettingsDraft,
    roles: List<StoryCharacterRoleEntity>,
    globals: StoryGlobalCraftValues,
    hasWorldBooks: Boolean,
    reminderEnabled: Boolean,
    templateCount: Int,
    callbacks: StoryHubSettingsCallbacks,
) {
    item(key = "settings_craft") { CraftGroup(story, globals, callbacks.onOpenField) }
    item(key = "settings_roles") {
        StoryRolesSection(story.id, roles, callbacks.onSaveRole, callbacks.onDeleteRole, callbacks.onDraftPersona)
    }
    item(key = "settings_toggles") { ToggleGroup(story, hasWorldBooks, callbacks) }
    item(key = "settings_serial") {
        SerialManageGroup(story, draft, reminderEnabled, templateCount, callbacks)
    }
}

/** 写法组：七个文本设定，每行一枚三态值标，点开一律进统一编辑页（全案唯一编辑长相）。 */
@Composable
private fun CraftGroup(
    story: StoryEntity,
    globals: StoryGlobalCraftValues,
    onOpenField: (StoryEditableField) -> Unit,
) {
    val prompts = rememberStoryPrompts(story)
    SettingsGroup(stringResource(R.string.story_hub_group_craft)) {
        val craftFields = storyHubCraftFields()
        craftFields.forEachIndexed { index, field ->
            if (index > 0) RowDivider()
            CraftRow(
                label = stringResource(field.titleRes),
                isNew = storyHubCraftFieldIsNew(field),
                trailing = { HubValueLabel(field.valueLabel(story, globals, prompts)) },
                onClick = { onOpenField(field) },
            )
        }
    }
}

/** 写法行：标题（可挂 NEW）+ 值标 + ›。造型与 [NavRow] 同尺寸，只把右值换成带样式的值标。 */
@Composable
private fun CraftRow(label: String, isNew: Boolean, trailing: @Composable () -> Unit, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = AppTheme.typography.body, color = c.text.primary)
        if (isNew) HubTagChip(stringResource(R.string.story_hub_tag_new), highlighted = true)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) { trailing() }
        Icon(AppActionIcons.ChevronRight, contentDescription = null, tint = c.text.tertiary)
    }
}

/** 生成开关组：三个本书级开关（世界观注入只在绑定角色挂了设定集时出现·契约 §4）。 */
@Composable
private fun ToggleGroup(story: StoryEntity, hasWorldBooks: Boolean, cb: StoryHubSettingsCallbacks) {
    val prompts = rememberStoryPrompts(story)
    SettingsGroup(stringResource(R.string.story_hub_group_toggles)) {
        SwitchRow(
            title = stringResource(R.string.story_toggle_choices),
            subtitle = stringResource(R.string.story_toggle_choices_sub),
            checked = storyChapterChoicesOn(prompts),
            onChange = cb.onChapterChoicesChange,
        )
        RowDivider()
        SwitchRow(
            title = stringResource(R.string.story_toggle_snapshot),
            subtitle = stringResource(R.string.story_toggle_snapshot_sub),
            checked = storySceneSnapshotOn(prompts),
            onChange = cb.onSceneSnapshotChange,
        )
        if (hasWorldBooks) {
            RowDivider()
            SwitchRow(
                title = stringResource(R.string.story_settings_world_title),
                subtitle = stringResource(R.string.story_settings_world_sub),
                checked = story.worldInfoEnabled,
                onChange = cb.onWorldInfoChange,
            )
        }
    }
}

/**
 * 连载与管理组：创作设定七行 + 存模板 + 追更/解锁/提醒 + 归档 + 删除；完结或暂停的书在卡外追两枚连载操作钮
 * （条件与文案同现状）。删除是破坏性动作，用 error 字色 + 确认弹窗。
 */
@Composable
private fun SerialManageGroup(
    story: StoryEntity,
    d: StorySettingsDraft,
    reminderEnabled: Boolean,
    templateCount: Int,
    cb: StoryHubSettingsCallbacks,
) {
    var showTimePicker by remember { mutableStateOf(false) }
    var showReminderChooser by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val chase = storyDraftChase(d)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsGroup(
            header = stringResource(R.string.story_hub_group_serial),
            footer = stringResource(R.string.story_settings_creative_footer),
        ) {
            StoryHubCreativeRows(d, cb.onUpdateDraft, templateCount, story.title, cb.onSaveTemplate)
            RowDivider()
            SwitchRow(
                title = stringResource(R.string.story_settings_mode_chase),
                subtitle = stringResource(R.string.story_settings_chase_sub),
                checked = chase,
            ) { on -> cb.onUpdateDraft { storyDraftWithChase(it, on) } }
            if (chase) {
                RowDivider()
                NavRow(
                    stringResource(R.string.story_settings_unlock_time),
                    storyUnlockTimeText(d),
                ) { showTimePicker = true }
                RowDivider()
                NavRow(
                    stringResource(R.string.story_settings_reminder),
                    stringResource(storyReminderLabelRes(reminderEnabled)),
                ) { showReminderChooser = true }
            }
            RowDivider()
            NavRow(stringResource(R.string.story_hub_archive_row), "", onClick = cb.onArchive)
            RowDivider()
            DangerRow(stringResource(R.string.story_hub_delete_row)) { confirmDelete = true }
            RowDivider()
            // 全局项已迁 App 设置（卷四）：组尾留一条弱化指路链（mockup 屏2 glink·非常规行），免得在书里找不到温度/段序/忌口。
            GlobalPrefsLink(cb.onOpenGlobalSettings)
        }
        storySerialOps(story)?.let { ops -> SerialOpsButtons(ops, cb.onContinue, cb.onRestart) }
    }

    if (showTimePicker) {
        UnlockTimeDialog(
            initialHour = d.unlockHour,
            initialMinute = d.unlockMinute,
            onConfirm = { h, m -> cb.onUpdateDraft { it.copy(unlockHour = h, unlockMinute = m) }; showTimePicker = false },
            onDismiss = { showTimePicker = false },
        )
    }
    if (showReminderChooser) {
        ReminderChooserDialog(
            current = reminderEnabled,
            onSelect = { cb.onReminderChange(it); showReminderChooser = false },
            onDismiss = { showReminderChooser = false },
        )
    }
    if (confirmDelete) {
        AppDialog(
            onDismissRequest = { confirmDelete = false },
            title = stringResource(R.string.story_hub_delete_title),
            body = stringResource(R.string.story_hub_delete_body),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; cb.onDelete() },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { confirmDelete = false },
        )
    }
}

/** 破坏性行：与 [NavRow] 同尺寸，字色走 error（= AppButton danger 同一 token 同一语义）。 */
@Composable
private fun DangerRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = AppTheme.typography.body, color = AppTheme.colors.status.onError, modifier = Modifier.weight(1f))
        Icon(AppActionIcons.ChevronRight, contentDescription = null, tint = AppTheme.colors.status.onError)
    }
}

/** 连载操作两钮（「只搬不改」自退役的 `SerialOpsGroup`：条件、文案、造型逐字照旧，只是不再自带组头）。 */
@Composable
private fun SerialOpsButtons(ops: StorySerialOps, onContinue: () -> Unit, onRestart: () -> Unit) {
    val c = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ops == StorySerialOps.COMPLETED) {
            AppButton(onClick = onContinue, style = AppButtonStyle.Primary, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.story_settings_continue)) }
            AppButton(onClick = onRestart, style = AppButtonStyle.Tonal, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.story_settings_restart)) }
            Text(stringResource(R.string.story_settings_ops_footer_completed), style = AppTheme.typography.secondary, color = c.text.secondary)
        } else {
            AppButton(onClick = onContinue, style = AppButtonStyle.Primary, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.story_settings_resume)) }
            Text(stringResource(R.string.story_settings_ops_footer_paused), style = AppTheme.typography.secondary, color = c.text.secondary)
        }
    }
}

/**
 * 「全局写作偏好 →」组尾弱化指路链（mockup 屏2 `glink`：12sp 深陶半粗小字、上接 [RowDivider] 发丝线，
 * **非**常规导航行）——全局项迁去 App 设置后，书页只留这条尾注指过去；48dp 最小触达兜 a11y 军规。
 */
@Composable
private fun GlobalPrefsLink(onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            stringResource(R.string.story_hub_global_prefs_row),
            style = AppTheme.typography.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = AppTheme.colors.accent.text,
        )
    }
}

/** 解锁时间选择对话框（「只搬不改」自退役的故事设定屏）。 */
@Composable
private fun UnlockTimeDialog(initialHour: Int, initialMinute: Int, onConfirm: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = true)
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.story_settings_unlock_time),
        confirmText = stringResource(R.string.action_confirm),
        onConfirm = { onConfirm(state.hour, state.minute) },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = { TimePicker(state = state) },
    )
}

/** 更新提醒选择对话框（「只搬不改」自退役的故事设定屏）。 */
@Composable
private fun ReminderChooserDialog(current: Boolean, onSelect: (Boolean) -> Unit, onDismiss: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.story_settings_reminder),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = {
            Column {
                ChoiceOptionRow(stringResource(R.string.story_settings_reminder_on), selected = current) { onSelect(true) }
                ChoiceOptionRow(stringResource(R.string.action_close), selected = !current) { onSelect(false) }
            }
        },
    )
}
