package com.situ.aichat.ui.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.pet.PetWalkService
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.gift.GiftColors

// 宠物弹窗组：改名 AppDialog + 散步结算 AppSheet（暖陶与琉璃共用）。
@Composable
internal fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(current)) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = "给宠物改名",
        confirmText = "确定",
        onConfirm = { onConfirm(value.text) },
        confirmEnabled = value.text.trim().isNotEmpty(),
        dismissText = "取消",
        onDismiss = onDismiss,
        content = { AppTextField(value = value, onValueChange = { value = it }, singleLine = true, label = "宠物名字") },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalkSettlementDialog(petName: String, settlement: PetWalkService.WalkSettlement, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    AppSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.DirectionsWalk,
                contentDescription = null,
                tint = colors.accent.text,
                modifier = Modifier.size(44.dp),
            )
            Text("$petName 散步回来了！", style = AppTypography.titleSmall, color = colors.text.primary)
            Text(
                settlement.eventDescription,
                style = AppTypography.secondary,
                color = colors.text.secondary,
                textAlign = TextAlign.Center,
            )
            settlement.souvenir?.let { s ->
                Column(
                    Modifier
                        .clip(AppShapes.medium)
                        .background(colors.surface.sunken)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(s.emoji, fontSize = 52.sp)
                    Text("获得了：${s.name}", style = AppTypography.label, fontWeight = FontWeight.SemiBold, color = colors.accent.text)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                RewardItem(Icons.Filled.Favorite, settlement.moodBonus, colors.pet.mood, "心情")
                RewardItem(Icons.Filled.ArrowCircleUp, settlement.growthBonus, colors.accent.text, "成长")
                RewardItem(AppActionIcons.Coin, settlement.coinsReward, GiftColors.Gold, "金币")
            }
            AppButton(onClick = onDismiss, style = AppButtonStyle.Primary, modifier = Modifier.fillMaxWidth()) { Text("好的") }
        }
    }
}

@Composable
private fun RewardItem(icon: ImageVector, value: Int, color: Color, label: String) {
    val colors = AppTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text("+$value", style = AppTypography.amount, color = colors.text.primary)
        Text(label, style = AppTypography.caption, color = colors.text.secondary)
    }
}
