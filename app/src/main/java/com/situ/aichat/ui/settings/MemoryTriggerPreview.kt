package com.situ.aichat.ui.settings

/**
 * 记忆设置页「攒够多少轮再总结」行的**活例子**状态（图纸 2026-09-05 §3.4·提案 D-2）。
 *
 * 纯函数：三个设置值 → 一个状态；**绝不读字符串资源**（文案映射留在屏层，见 `MemorySettingsSections`），
 * 好处是这一层能脱开 Android 框架直接做 T1，屏层只剩「态 → resource id」的直连。
 *
 * 安全区判据（提案 D-3）：`interval > window` 时那几轮对话会暂时掉出 AI 视野，等总结跑完才补回
 * ——这是**提示不是禁止**，用户的值绝不被静默改写。`interval == window` 仍属安全（含等号）。
 */
internal sealed interface MemoryTriggerPreview {

    /** 自动总结已关（interval ≤ 0）。 */
    data object Off : MemoryTriggerPreview

    /** 攒够轮数超出了短期窗口——琥珀提示档，[window] 即建议上限。 */
    data class OverWindow(val window: Int) : MemoryTriggerPreview

    /** 常态：[firstRound] = 第一次总结落在第几轮（窗口 + 攒够轮数）。 */
    data class Normal(val firstRound: Int, val interval: Int, val cooldownMinutes: Int) : MemoryTriggerPreview

    companion object {
        fun from(window: Int, interval: Int, cooldownMinutes: Int): MemoryTriggerPreview = when {
            interval <= 0 -> Off
            interval > window -> OverWindow(window)
            else -> Normal(firstRound = window + interval, interval = interval, cooldownMinutes = cooldownMinutes)
        }
    }
}
