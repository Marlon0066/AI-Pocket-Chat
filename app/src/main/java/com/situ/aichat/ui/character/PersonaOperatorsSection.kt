package com.situ.aichat.ui.character

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.model.PersonaOperator
import com.situ.aichat.data.model.PersonaVocab
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.designsystem.AppTheme

// 活人感内核·卷一《人设编译器》「她的固定反应」（图纸 §4.4 · D-4b）：
// 每条 = 条件 → 动作，可开关、可删除，**不提供新增入口**（它是可执行规则，不是分类标签·Y-N4）。
// 卷一只存不求值：条件求值依赖卷三的场与卷四的意图队列（§0.0 / §9.5）。

/**
 * 「她的固定反应」整区。空列表时**整区不渲染**（不显示空标题）。
 *
 * 条件 / 动作恒按封闭词表 key 取当前语言标签；词表外的 key（理论上进不来，编译端已整条丢弃）
 * 同样跳过不渲染——宁可少显示一行，也不在屏上摆一个认不出的 key。
 */
@Composable
internal fun PersonaOperatorsSection(operators: List<PersonaOperator>, onChange: (List<PersonaOperator>) -> Unit) {
    if (operators.isEmpty()) return
    val colors = AppTheme.colors
    Text(
        text = stringResource(R.string.persona_operators_title),
        style = MaterialTheme.typography.titleSmall,
        color = colors.text.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
    operators.forEach { op ->
        val conditionRes = PersonaVocab.CONDITIONS[op.condition]
        val actionRes = PersonaVocab.ACTIONS[op.action]
        if (conditionRes == null || actionRes == null) return@forEach
        Row(
            Modifier.fillMaxWidth().sizeIn(minHeight = ROW_MIN_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(conditionRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text.primary,
                )
                Text(
                    stringResource(actionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text.secondary,
                )
            }
            Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                AppSwitch(
                    checked = op.enabled,
                    onCheckedChange = { on ->
                        onChange(operators.map { if (it.id == op.id) it.copy(enabled = on) else it })
                    },
                )
                // 删除走行尾按钮，**不做左滑删除**：编辑屏整体是纵向滚动表单，左滑与既有滚动/返回手势有冲突面。
                IconButton(
                    onClick = { onChange(operators.filterNot { it.id == op.id }) },
                    modifier = Modifier.size(ROW_MIN_HEIGHT),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.persona_operators_delete),
                        tint = colors.text.tertiary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
    Text(
        text = stringResource(R.string.persona_operators_footer),
        style = MaterialTheme.typography.bodySmall,
        color = colors.text.tertiary,
        modifier = Modifier.padding(top = 8.dp),
    )
}
