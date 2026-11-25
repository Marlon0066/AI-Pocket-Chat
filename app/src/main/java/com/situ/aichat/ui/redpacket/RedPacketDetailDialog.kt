package com.situ.aichat.ui.redpacket

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.RedPacketRecordEntity
import com.situ.aichat.data.model.RedPacketData
import com.situ.aichat.data.model.RedPacketStatus
import com.situ.aichat.data.model.WalletOwnerType
import com.situ.aichat.gift.FestivalCalendar
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppEconomyColors
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.util.DateFormatters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** 红包仪式底（漆红/退回）上的暖白字（brand-fixed·与 RedPacketCardBubble 的 OnPacketRed 同值）。 */
private val OnPacketField = Color(0xFFF5EFEA)

/**
 * 红包详情全屏浮层（1:1 iOS `RedPacketDetailView` 行为，Material 3 原生）。点击聊天红包气泡弹出，对标微信「拆红包页」。
 *
 * 状态呈现（[recordFlow] 实时观察）：① pending+用户是接收方 → 大红包 + 圆「拆」按钮 → 拆开动画 → 金额揭晓
 * ② pending+用户是发送方 → 静态大红包 + 「对方还没拆开哦」 ③ accepted → 金色 + 大金额 + 祝福 + 时间戳
 * ④ rejected/expired → 灰红包 + 已退回 + 理由 + 时间戳。
 *
 * 拆开动画（仅 pending+接收方点「拆」）：放大 + 触感 →（调 [onOpen] 走 acceptRedPacket + 取消预警闹钟）→ 金额 spring 揭晓。
 * 业务在 [onOpen]（VM）完成，本浮层只演动画 + 观察 Record。金额**拆开后才显示**。
 *
 * @param characterName/[characterAvatarPath] 会话角色（另一方）；角色发的红包头像/名用它，用户发的显示「你」。
 * @param onOpen 拆开回调（suspend；VM.openRedPacket → acceptRedPacket + cancelWarningAlarm）。
 */
@Composable
fun RedPacketDetailDialog(
    data: RedPacketData,
    recordFlow: Flow<RedPacketRecordEntity?>,
    characterName: String,
    characterAvatarPath: String?,
    onOpen: suspend () -> Unit,
    onDismiss: () -> Unit,
) {
    val record by recordFlow.collectAsState(initial = null)
    val status = record?.let { RedPacketStatus.fromRaw(it.status) } ?: RedPacketStatus.PENDING
    val senderIsUser = record?.let { WalletOwnerType.fromRaw(it.senderType) == WalletOwnerType.USER } ?: false
    val receiverIsUser = record?.let { WalletOwnerType.fromRaw(it.receiverType) == WalletOwnerType.USER } ?: false
    val amount = record?.amount ?: data.amount
    val blessing = data.blessingText.trim()

    val scope = rememberCoroutineScope()
    val appHaptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val economy = AppTheme.colors.economy
    val isDarkTheme = AppTheme.colors.isDark
    // R2：拆开态金底 → 文字/图标翻深（onKeepsake）；封口/退回红灰底 → 暖白。
    val onBackdrop = if (status == RedPacketStatus.ACCEPTED) economy.onKeepsake else OnPacketField
    val festivalName = data.festivalId?.let { FestivalCalendar.festivalById(it)?.name }
    val openedDate = record?.resolvedAt?.let { DateFormatters.monthDayHourMinute(it) }
    // redpacket-1：三幕拆红包（放大 → 翻面+金币雨 → 金额 spring），对齐 iOS RedPacketDetailView.handleOpen。
    var isAccepting by remember { mutableStateOf(false) }
    var animStage by remember { mutableIntStateOf(0) } // 0 封口/待拆,1 放大,2 翻面+金币雨,3 金额揭晓
    val packetScale = remember { Animatable(1f) }
    val openButtonScale = remember { Animatable(1f) }
    var flipDegrees by remember { mutableFloatStateOf(0f) } // 福字 X 轴翻转 0→90°
    var amountScale by remember { mutableFloatStateOf(0.5f) }
    var amountOpacity by remember { mutableFloatStateOf(0f) }
    var sweepProgress by remember { mutableFloatStateOf(0f) } // R3：金额流光揭晓进度（0→1·替金币雨·历史回看/reduceMotion 保持 0）
    // 详情页直接以 accepted 载入（历史回看，非本次拆开）→ 金额满显、不重放动画。
    val preOpened = status == RedPacketStatus.ACCEPTED && animStage < 3 && !isAccepting

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // R4：全屏沉浸——红包窗口铺到状态栏 + 手势条之后（setLayout MATCH_PARENT + setDecorFitsSystemWindows
        // false·= WallpaperCropScreen 同模式·不设已弃用的 statusBarColor）；系统栏图标随底色翻色（金色浅底 →
        // 深图标·红/灰底 → 白图标）。
        val dialogView = LocalView.current
        val darkSystemIcons = status == RedPacketStatus.ACCEPTED && !isDarkTheme
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { w ->
                // 沉浸（铺到系统栏后）由 DialogProperties(decorFitsSystemWindows=false) 负责；此处仅关 3 键导航
                // 底部对比 scrim（手势导航无影响），其余手动 window flag 经实测在该参数下均冗余。
                w.isNavigationBarContrastEnforced = false
                WindowCompat.getInsetsController(w, dialogView).apply {
                    isAppearanceLightStatusBars = darkSystemIcons
                    isAppearanceLightNavigationBars = darkSystemIcons
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize().background(Brush.radialGradient(backgroundColors(status, economy)))) {
            com.situ.aichat.ui.designsystem.AppIconButton(
                icon = com.situ.aichat.ui.designsystem.AppActionIcons.Close,
                contentDescription = stringResource(R.string.action_close),
                onClick = onDismiss,
                tint = onBackdrop.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            )

            Column(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 32.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 顶部发送方信息
                if (senderIsUser) {
                    AvatarFallback("你")
                    Text("你", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = onBackdrop)
                } else {
                    CharacterAvatar(characterName, characterAvatarPath, 56.dp)
                    Text(characterName.ifEmpty { "TA" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = onBackdrop)
                }
                Text(
                    headlineDescription(senderIsUser, status, characterName),
                    style = MaterialTheme.typography.bodySmall,
                    color = onBackdrop.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )

                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    when {
                        status == RedPacketStatus.REJECTED -> ReturnedPacket("已退回", record?.rejectionReason.orEmpty())
                        status == RedPacketStatus.EXPIRED -> ReturnedPacket("已过期", "24 小时未拆开,自动退回")
                        // 第三幕：金额 spring 揭晓（本次拆开）。
                        animStage >= 3 -> OpenedPacket(amount, blessing, festivalName, openedDate, sweepProgress, amountScale, amountOpacity)
                        // 历史回看（直接以 accepted 载入）：金额满显、不重放流光。
                        preOpened -> OpenedPacket(amount, blessing, festivalName, openedDate, 0f, 1f, 1f)
                        // 待拆 / 第一二幕：封口红包（带放大 + 福字翻面）。
                        else -> SealedPacket(blessing, flipDegrees, packetScale.value)
                    }
                }

                // 底部操作 / 时间戳
                when {
                    status == RedPacketStatus.REJECTED || status == RedPacketStatus.EXPIRED -> {
                        record?.resolvedAt?.let {
                            Text(DateFormatters.monthDayHourMinute(it), style = MaterialTheme.typography.labelSmall, color = OnPacketField.copy(alpha = 0.65f))
                        }
                    }
                    animStage >= 3 || preOpened -> Unit // 拆开态：日期已移到纪念卡上
                    receiverIsUser -> {
                        OpenButton(enabled = !isAccepting, pressScale = openButtonScale.value) {
                            if (isAccepting) return@OpenButton
                            isAccepting = true
                            scope.launch {
                                if (reduceMotion) {
                                    // 减弱动效：跳过前两幕，直接入账 + 金额满显（对齐 iOS ReducedMotion 分支）。
                                    // 触觉取 success：本分支把三幕压成一拍，替代的正是幕2 那记成功入账（D-5 复核裁定）。
                                    appHaptics.success()
                                    onOpen()
                                    amountScale = 1f; amountOpacity = 1f; animStage = 3
                                    isAccepting = false
                                    return@launch
                                }
                                // 幕1：放大 + 中触感（0.35s）。
                                animStage = 1
                                appHaptics.medium()
                                launch { packetScale.animateTo(1.08f, tween(350, easing = EaseOut)) }
                                launch { openButtonScale.animateTo(0.92f, tween(350, easing = EaseOut)) }
                                delay(500)
                                // 幕2：福字翻面(0.6s) + 成功触感 →（此刻才真入账 onOpen）。
                                animStage = 2
                                launch {
                                    Animatable(0f).animateTo(90f, tween(600, easing = EaseIn)) { flipDegrees = value }
                                }
                                appHaptics.success()
                                onOpen() // accept + cancel 预警闹钟（金额入账，位置=iOS act2，逻辑不动）
                                delay(700)
                                // 幕3：切金色大红包 + 金额 spring(0.5→1) + 渐入 + 流光扫过（替金币雨·celebrate 单次·不爆闪）。
                                animStage = 3
                                launch { Animatable(0.5f).animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) { amountScale = value } }
                                launch { delay(100); Animatable(0f).animateTo(1f, tween(400, easing = EaseIn)) { amountOpacity = value } }
                                launch { delay(220); Animatable(0f).animateTo(1f, tween(720, easing = EaseOut)) { sweepProgress = value } }
                                isAccepting = false
                            }
                        }
                    }
                    else -> Text("对方还没拆开哦", style = MaterialTheme.typography.bodyMedium, color = OnPacketField.copy(alpha = 0.85f))
                }
            }
        }
    }
}

@Composable
private fun SealedPacket(blessing: String, flipDegrees: Float, scale: Float) {
    val economy = AppTheme.colors.economy
    Column(
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            // R1：哑光漆红封套（economy.redPacket* token·与气泡统一）+ 烫金细描边。
            modifier = Modifier.width(180.dp).height(240.dp).clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(listOf(economy.redPacketStart, economy.redPacketEnd)))
                .border(0.8.dp, economy.redPacketStroke, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(96.dp)
                    // redpacket-1：福字 X 轴翻面（0→90°）；过半即隐（对齐 iOS rotation3DEffect + opacity）。
                    .graphicsLayer {
                        rotationX = flipDegrees
                        cameraDistance = 12f * density
                    }
                    .alpha(if (flipDegrees >= 90f) 0f else 1f)
                    .clip(CircleShape)
                    // R1：烫金福印（sealGold 径向·与气泡福徽同源）。
                    .background(Brush.radialGradient(listOf(economy.sealGoldStart, economy.sealGoldEnd))),
                contentAlignment = Alignment.Center,
            ) {
                Text("福", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = economy.redPacketSeal)
            }
        }
        if (blessing.isNotEmpty()) {
            Text("「$blessing」", style = MaterialTheme.typography.bodySmall, color = OnPacketField.copy(alpha = 0.9f), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun OpenedPacket(
    amount: Int,
    blessing: String,
    festivalName: String?,
    dateText: String?,
    sweepProgress: Float,
    amountScale: Float,
    amountOpacity: Float,
) {
    val economy = AppTheme.colors.economy
    val amountBrush = Brush.linearGradient(listOf(economy.keepsakeAmountStart, economy.keepsakeAmountEnd))
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            // R2：奶油/深暖纪念卡（keepsakeCard token 浅深）+ 烫金细描边。
            modifier = Modifier.size(200.dp).clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(listOf(economy.keepsakeCardStart, economy.keepsakeCardEnd)))
                .border(0.8.dp, economy.redPacketStroke, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // R2：节日红印（右上角·festivalId 有才显；日常红包不显）。
            if (festivalName != null) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                        .clip(RoundedCornerShape(6.dp)).background(economy.keepsakeStamp)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(festivalName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = economy.onKeepsakeStamp)
                }
            }
            // 金额 spring 放大 + 渐入（act3）；烫金金属渐变 + tnum + 字重 640（不用 Black）。
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(amountOpacity)) {
                Text("$amount", style = AppTypography.amount.copy(brush = amountBrush, fontSize = 56.sp, lineHeight = 62.sp), modifier = Modifier.scale(amountScale))
                Text("金币", style = AppTypography.label, color = economy.onKeepsake.copy(alpha = 0.75f))
            }
            // R3：流光揭晓——一道柔光斜扫过卡（sweepProgress 0→1·历史回看/reduceMotion=0 不扫·不爆闪）。
            if (sweepProgress > 0f && sweepProgress < 1f) {
                Box(
                    Modifier.matchParentSize().drawBehind {
                        val center = size.width * (sweepProgress * 1.5f - 0.25f)
                        val half = size.width * 0.30f
                        drawRect(
                            brush = Brush.linearGradient(
                                0f to Color.Transparent,
                                0.5f to Color.White.copy(alpha = 0.32f),
                                1f to Color.Transparent,
                                start = Offset(center - half, 0f),
                                end = Offset(center + half, size.height),
                            ),
                        )
                    },
                )
            }
        }
        if (blessing.isNotEmpty()) {
            // R2：楷体祝福（kaiQuote·霞鹜文楷未打包→回退衬线）。
            Text("「$blessing」", style = AppTypography.kaiQuote, color = economy.onKeepsake.copy(alpha = 0.9f), textAlign = TextAlign.Center, modifier = Modifier.alpha(amountOpacity))
        }
        if (dateText != null) {
            Text(dateText, style = AppTypography.caption, color = economy.onKeepsake.copy(alpha = 0.55f), modifier = Modifier.alpha(amountOpacity))
        }
    }
}

@Composable
private fun ReturnedPacket(label: String, reason: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            modifier = Modifier.width(180.dp).height(240.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF5D5552)),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f))
        }
        if (reason.isNotEmpty()) {
            Text(reason, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun OpenButton(enabled: Boolean, pressScale: Float = 1f, onClick: () -> Unit) {
    val openLabel = stringResource(R.string.a11y_open_red_packet)
    val economy = AppTheme.colors.economy
    val reduceMotion = rememberReduceMotion()
    // R1：呼吸环——金色细环 alpha 在 0.16↔0.46 间缓动，邀请点击；reduceMotion → 静态淡环（不脉动）。
    val infinite = rememberInfiniteTransition(label = "breath")
    val breathAlpha by infinite.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.46f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "breathAlpha",
    )
    val ringAlpha = if (reduceMotion) 0.30f else breathAlpha
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(116.dp)) {
        Box(
            Modifier.size(108.dp).clip(CircleShape)
                .border(2.dp, economy.sealGoldStart.copy(alpha = ringAlpha), CircleShape),
        )
        Box(
            modifier = Modifier.size(96.dp)
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale } // redpacket-1：幕1 按下缩 0.92
                .clip(CircleShape)
                // R1：金属烫金圆钮（sealGold 径向·与福印同源）。
                .background(Brush.radialGradient(listOf(economy.sealGoldStart, economy.sealGoldEnd)))
                .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
                // 无障碍（14.7e）：主拆红包动作此前只读裸「拆」、无 button 语义。clearAndSetSemantics 给清晰标签 + Button role
                // + onClick（替被清掉的 clickable 语义；禁用态不挂动作）。
                .clearAndSetSemantics {
                    contentDescription = openLabel
                    if (enabled) {
                        role = Role.Button
                        onClick { onClick(); true }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("拆", fontSize = 32.sp, fontWeight = FontWeight.Black, color = economy.redPacketSeal)
        }
    }
}

@Composable
private fun AvatarFallback(name: String) {
    Box(
        modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/** 浮层背景径向渐变。R1：封口态(PENDING)换哑光漆红 token（与气泡统一·开包不跳色）；
 *  ACCEPTED 开盒背景留 R2 接 openedBackdrop token；REJECTED/EXPIRED 退回态暂保留。 */
private fun backgroundColors(status: RedPacketStatus, economy: AppEconomyColors): List<Color> = when (status) {
    RedPacketStatus.PENDING -> listOf(economy.redPacketStart, economy.redPacketEnd)
    RedPacketStatus.ACCEPTED -> listOf(economy.openedBackdropStart, economy.openedBackdropEnd)
    RedPacketStatus.REJECTED, RedPacketStatus.EXPIRED -> listOf(Color(0xFF7B6A68), Color(0xFF3D3636))
}

/** 顶部描述（1:1 iOS headlineDescription，senderType × status）。 */
private fun headlineDescription(senderIsUser: Boolean, status: RedPacketStatus, characterName: String): String {
    val name = characterName.ifEmpty { "TA" }
    return if (senderIsUser) {
        when (status) {
            RedPacketStatus.PENDING -> "你发出了一个红包"
            RedPacketStatus.ACCEPTED -> "${name}领取了你的红包"
            RedPacketStatus.REJECTED -> "${name}拒收了你的红包"
            RedPacketStatus.EXPIRED -> "24 小时未被拆开,已退回"
        }
    } else {
        when (status) {
            RedPacketStatus.PENDING -> "给你发了一个红包"
            RedPacketStatus.ACCEPTED -> "你领取了 $name 的红包"
            RedPacketStatus.REJECTED -> "你拒收了这个红包"
            RedPacketStatus.EXPIRED -> "24 小时未拆,已退回"
        }
    }
}
