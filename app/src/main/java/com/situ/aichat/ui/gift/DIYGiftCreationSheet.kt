package com.situ.aichat.ui.gift

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.data.model.GiftSender
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppIconButton
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppSlider
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.gift.GiftCatalog
import com.situ.aichat.gift.GiftSendService
import com.situ.aichat.util.ImageScaler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val TITLE_MAX = 12
private const val CONTENT_MAX = 300

/**
 * DIY 手作礼物创建底片（9.2d d-3，1:1 iOS `DIYGiftCreationView`）。
 *
 * 标题（≤12）+ 内容（≤300）+ 可选图片 + 价格 Slider（2–20，step 1，金色）+ 实时预览（复用 [GiftCardBubble]）。
 * 送出 → 确认 → [onSend]（保存图片 + sendUserDIYInChat）→ Success 关闭；余额不足/失败显示提示。
 *
 * @param onSend 由 ChatViewModel 提供（保存图片落盘 + 扣币建 DIY record + 插消息 + 写 growthLog），返回 outcome。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DIYGiftCreationSheet(
    onSend: suspend (title: String, content: String, imageUri: Uri?, cost: Int) -> GiftSendService.InChatSendOutcome,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 审计 B2（拍板 2026-07-02）：DIY 表单四字段跨重建存活（Uri 是 Parcelable 天然可存）——写一半转屏/切深色不丢；
    // isSending/errorText/showConfirm 留瞬态。
    var title by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var imageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var costValue by rememberSaveable { mutableFloatStateOf(5f) }
    var isSending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showConfirm by remember { mutableStateOf(false) }

    val cost = costValue.roundToInt()
    val trimmedTitle = title.trim()
    val trimmedContent = content.trim()
    val canSend = trimmedTitle.isNotEmpty() && trimmedContent.isNotEmpty() && !isSending

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) imageUri = uri
    }
    // 表单内 180dp 图片预览（降采样解码选中图）
    val previewBitmap by produceState<Bitmap?>(initialValue = null, imageUri) {
        val uri = imageUri
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { ImageScaler.decodeSampled(it.readBytes(), 1024) }
            }.getOrNull()
        }
    }

    // 预览气泡数据（diy_user_preview 前缀，与 iOS previewCardData 一致；气泡不显选中图，走兜底图标）
    val previewCard = GiftCardData(
        type = "gift_card",
        giftItemId = GiftCatalog.userDIYIdPrefix + "preview",
        giftRecordId = "preview",
        cost = cost,
        giftName = trimmedTitle.ifEmpty { "手作礼物" },
        isHandmade = true,
        senderType = GiftSender.USER,
        diyTitle = trimmedTitle.ifEmpty { null },
        diyContent = trimmedContent.ifEmpty { null }?.let { if (it.length > 80) it.take(80) + "…" else it },
    )

    AppSheet(onDismissRequest = { if (!isSending) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("亲手做一份", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            errorText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GiftColors.Unafford) }

            // 标题
            AppTextField(
                value = title,
                // gift-6：超长截断保留前 N 字（1:1 iOS DIYGiftCreationView onChange prefix），不再整段拒绝（粘贴超长文本原先毫无反应＝手感 bug）。
                onValueChange = { title = it.take(TITLE_MAX) },
                label = "标题",
                placeholder = "写一个短标题（最多 12 字）",
                supportingText = "${trimmedTitle.length}/$TITLE_MAX",
                modifier = Modifier.fillMaxWidth(),
            )

            // 内容
            AppTextArea(
                value = content,
                onValueChange = { content = it.take(CONTENT_MAX) }, // gift-6：同上，截断而非拒绝。
                label = "内容",
                placeholder = "写点什么给 TA（最多 300 字）",
                supportingText = "${trimmedContent.length}/$CONTENT_MAX",
                modifier = Modifier.fillMaxWidth(),
            )

            // 图片（可选）
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("图片（可选）", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val bmp = previewBitmap
                if (imageUri != null && bmp != null) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        AppIconButton(
                            icon = AppActionIcons.Close,
                            contentDescription = stringResource(R.string.a11y_remove_image),
                            onClick = { imageUri = null },
                            tint = Color.White,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                } else {
                    Surface(
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppActionIcons.AddPhoto, contentDescription = null, tint = GiftColors.Gold)
                            Text("添加图片", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // 价格 Slider 2–20
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("花多少金币", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.weight(1f))
                    Icon(AppActionIcons.Coin, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(16.dp))
                    Text(" $cost 金币", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                AppSlider(
                    value = costValue,
                    onValueChange = { costValue = it },
                    valueRange = 2f..20f,
                    steps = 17, // 2..20 step 1 → 19 个离散值 → 中间 17 个停靠点
                    thumbColor = GiftColors.Gold,
                    activeColor = GiftColors.Gold,
                )
                Text(
                    "花多少不决定你的心意；你自己做的这件事，才是真正被记住的部分。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 预览
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("预览", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    GiftCardBubble(data = previewCard, isFromUser = true)
                }
            }

            // 送出按钮
            AppButton(
                onClick = { showConfirm = true },
                style = AppButtonStyle.Text,
                enabled = canSend,
                modifier = Modifier.align(Alignment.End),
            ) { Text("送出") }
        }
    }

    if (showConfirm) {
        AppDialog(
            onDismissRequest = { showConfirm = false },
            title = "送出这份 ${trimmedTitle.ifEmpty { "手作礼物" }}？",
            body = "将从余额扣 $cost 金币",
            confirmText = "确认送出",
            onConfirm = {
                showConfirm = false
                if (!canSend) return@AppDialog
                scope.launch {
                    isSending = true
                    errorText = null
                    when (val outcome = onSend(trimmedTitle, trimmedContent, imageUri, cost)) {
                        is GiftSendService.InChatSendOutcome.Success -> onSuccess()
                        is GiftSendService.InChatSendOutcome.InsufficientCoins -> {
                            errorText = "余额不足，还差 ${outcome.need - outcome.have} 金币"
                            isSending = false
                        }
                        GiftSendService.InChatSendOutcome.SpendFailed -> {
                            errorText = "送礼失败，请稍后重试"
                            isSending = false
                        }
                    }
                }
            },
            dismissText = "取消",
            onDismiss = { showConfirm = false },
        )
    }
}
