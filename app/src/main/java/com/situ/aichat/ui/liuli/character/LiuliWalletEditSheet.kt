package com.situ.aichat.ui.liuli.character

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell

/** 发薪日合法区间（1–28·避月末边界·1:1 暖陶）与月薪输入位数上限。 */
private val PAYDAY_RANGE = 1..28
private const val SALARY_MAX_DIGITS = 5

/**
 * 角色月薪 + 发薪日编辑面板（琉璃·换壳自暖陶 `CharacterWalletEditSheet`·图纸 §2.1）。
 *
 * **💰钱路零碰**：初值规则、位数过滤、发薪日钳位、保存去重（`saved` 一次性闩）、
 * `onSave(salaryText, salaryDay)` 的调用与参数**逐字同暖陶**；只换壳与控件皮。
 *
 * 发薪日在暖陶是步进器；琉璃这一卷按图纸 §2.1 落成第二枚 [LiuliField]（数字键盘 + 钳位），
 * `LiuliStepper` 由 A-14 推到卷五（§11 D-15）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiuliWalletEditSheet(
    initialSalary: Int,
    salaryInferred: Boolean,
    initialSalaryDay: Int,
    onDismiss: () -> Unit,
    onSave: (salaryText: String, salaryDay: Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 涉钱表单：编辑中值升 rememberSaveable（转屏 / 进程死亡不丢）；saved 是瞬态成功位保持 remember。
    var salaryText by rememberSaveable {
        mutableStateOf(if (salaryInferred && initialSalary >= 0) initialSalary.toString() else "")
    }
    var paydayText by rememberSaveable {
        mutableStateOf(initialSalaryDay.coerceIn(PAYDAY_RANGE.first, PAYDAY_RANGE.last).toString())
    }
    var saved by remember { mutableStateOf(false) }
    val salaryDay = paydayText.toIntOrNull()?.coerceIn(PAYDAY_RANGE.first, PAYDAY_RANGE.last)
        ?: initialSalaryDay.coerceIn(PAYDAY_RANGE.first, PAYDAY_RANGE.last)

    LiuliSheetShell(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        title = stringResource(R.string.wallet_edit_title),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LiuliField(
                value = salaryText,
                onValueChange = { input -> salaryText = input.filter { it.isDigit() }.take(SALARY_MAX_DIGITS) },
                label = stringResource(R.string.wallet_edit_salary),
                suffix = stringResource(R.string.wallet_coins_unit),
                supportingText = stringResource(R.string.wallet_edit_salary_footer),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            LiuliField(
                value = paydayText,
                onValueChange = { input -> paydayText = input.filter { it.isDigit() }.take(2) },
                label = stringResource(R.string.wallet_edit_payday),
                supportingText = stringResource(R.string.wallet_edit_payday_footer),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                LiuliButton(onClick = onDismiss, style = LiuliButtonStyle.Text) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(Modifier.width(8.dp))
                LiuliButton(
                    onClick = {
                        if (!saved) {
                            saved = true
                            onSave(salaryText, salaryDay)
                            onDismiss()
                        }
                    },
                    style = LiuliButtonStyle.Prominent,
                    enabled = !saved,
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}
