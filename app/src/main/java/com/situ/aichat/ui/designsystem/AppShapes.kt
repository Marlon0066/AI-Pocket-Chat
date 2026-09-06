package com.situ.aichat.ui.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Fable-5 形状 token：半径阶 **8 / 16 / 28 / full**（主导 16·从现有 90+ 处 RoundedCornerShape 真实分布提炼）。
 * 全曲线无锐角。消息气泡 2026-06-19 起改用统一 16dp 圆角（见 [bubble]·取代旧 D3 非对称尾角）。见 [FABLE5_DESIGN_LANGUAGE.md] §3。
 * 主题无关（深浅不变），经 [AppTheme].shapes 取用。
 */
object AppShapes {

    /** small 8dp：引用块内角 / 连续卡相接角 / 行内高亮。 */
    val small = RoundedCornerShape(8.dp)

    /** medium 16dp（主导）：气泡 / 卡片外壳 / 浮层 / 错误卡。 */
    val medium = RoundedCornerShape(16.dp)

    /** large 28dp：输入托盘顶角 / 状态横幅胶囊。 */
    val large = RoundedCornerShape(28.dp)

    /** full：头像 / 圆钮 / 主行动钮 / pill 徽章 / starter / 输入胶囊。 */
    val full = RoundedCornerShape(percent = 50)

    /**
     * 消息气泡（文字 / 语音 / 折叠 / 打字指示器·用户与 AI 同）：四角统一 16dp。
     * 2026-06-19 用户拍板取代旧 D3「非对称 6dp 尾角 + 连续连发 8dp 水滴串」——不再用收角暗示方向，
     * 每条气泡都是同弧度的独立圆角矩形（更干净耐看）。与主导 medium 同值 16dp，但保留独立语义 token（卡片调形不波及气泡）。
     */
    val bubble = RoundedCornerShape(16.dp)

    /** 输入托盘：仅顶部两角 28dp（底贴 imePadding）。 */
    val inputTray = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 0.dp, bottomStart = 0.dp)

    /**
     * 浮层 20dp：确认弹窗纸卡 / 浮层菜单玻璃小笺（M3 清零总契约 §1 拍板①·先例 = 故事玻璃菜单 ST10-4）。
     * 与 [medium] 16dp 分立的独立语义 token（沿用 bubble≠medium 先例）——卡片调形不波及浮层。
     */
    val overlay = RoundedCornerShape(20.dp)

    /**
     * 底部弹层：仅顶部两角 28dp（与 [inputTray] 同弧度、独立 token——输入托盘调形不波及弹层）。
     * M3 清零总契约 §1。
     */
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 0.dp, bottomStart = 0.dp)
}
