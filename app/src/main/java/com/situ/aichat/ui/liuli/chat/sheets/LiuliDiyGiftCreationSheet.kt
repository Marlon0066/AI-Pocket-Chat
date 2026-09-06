package com.situ.aichat.ui.liuli.chat.sheets

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.data.model.GiftSender
import com.situ.aichat.gift.GiftCatalog
import com.situ.aichat.gift.GiftSendService
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.chat.LiuliGiftCard
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.liuli.designsystem.LiuliSlider
import com.situ.aichat.ui.liuli.page.liuliFootprint
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.util.ImageScaler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 字数上限与预览截断（照抄 F24 `TITLE_MAX` / `CONTENT_MAX` / 气泡 80 字截断）。 */
private const val TITLE_MAX = 12
private const val CONTENT_MAX = 300
private const val PREVIEW_CONTENT_MAX = 80

/** 选图区几何：预览高帽 180 · 14 圆角内衬（与 [LiuliField] 同一枚内衬形）。 */
private val INLAY_SHAPE = RoundedCornerShape(14.dp)
private const val INLAY_ALPHA = 0.62f
private val IMAGE_PREVIEW_MAX = 180.dp

/** 移除 ✕ 的视觉直径与压底（压在照片上·同图片戳的口径：浅色染色读不出，走黑压底）。 */
private val REMOVE_DOT = 26.dp
private const val REMOVE_DOT_ALPHA = 0.45f

/**
 * 琉璃版 DIY 手作创作底片（图纸 2026-09-05 卷二C C6b · A-16 / A-20 · 照抄源 F24
 * `ui/gift/DIYGiftCreationSheet.kt:82-268`）。
 *
 * **只换渲染皮·钱路零碰**：四字段 saveable 存活、超长**截断保留前 N 字**（不是整段拒绝）、
 * `canSend` 门、[ImageScaler].decodeSampled 1024 预览、滑杆 2..20（A-20 明令保持滑杆不改档位 chip）、
 * 确认框 `ifEmpty{"手作礼物"}` 兜底与两条失败文案，全部逐字照抄。预览卡换成琉璃的 [LiuliGiftCard]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiuliDiyGiftCreationSheet(
    onSend: suspend (title: String, content: String, imageUri: Uri?, cost: Int) -> GiftSendService.InChatSendOutcome,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    val scope = rememberCoroutineScope()

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
    val previewBitmap by produceState<Bitmap?>(initialValue = null, imageUri) {
        val uri = imageUri
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { ImageScaler.decodeSampled(it.readBytes(), 1024) }
            }.getOrNull()
        }
    }

    val previewCard = GiftCardData(
        type = "gift_card",
        giftItemId = GiftCatalog.userDIYIdPrefix + "preview",
        giftRecordId = "preview",
        cost = cost,
        giftName = trimmedTitle.ifEmpty { "手作礼物" },
        isHandmade = true,
        senderType = GiftSender.USER,
        diyTitle = trimmedTitle.ifEmpty { null },
        diyContent = trimmedContent.ifEmpty { null }?.let {
            if (it.length > PREVIEW_CONTENT_MAX) it.take(PREVIEW_CONTENT_MAX) + "…" else it
        },
    )

    LiuliSheetShell(onDismissRequest = { if (!isSending) onDismiss() }, title = "亲手做一份") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            errorText?.let { Text(it, style = AppTypography.snackbarBody, color = colors.status.onError) }

            LiuliField(
                value = title,
                onValueChange = { title = it.take(TITLE_MAX) },
                label = "标题",
                placeholder = "写一个短标题（最多 12 字）",
                supportingText = "${trimmedTitle.length}/$TITLE_MAX",
                modifier = Modifier.fillMaxWidth(),
            )
            LiuliField(
                value = content,
                onValueChange = { content = it.take(CONTENT_MAX) },
                label = "内容",
                placeholder = "写点什么给 TA（最多 300 字）",
                supportingText = "${trimmedContent.length}/$CONTENT_MAX",
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("图片（可选）", style = AppTypography.snackbarBody, color = onGlass.secondary)
                val bmp = previewBitmap
                if (imageUri != null && bmp != null) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().heightIn(max = IMAGE_PREVIEW_MAX).clip(INLAY_SHAPE),
                            contentScale = ContentScale.Crop,
                        )
                        // 移除 ✕：cd 照抄 F24 的 `a11y_remove_image`（**不是** action_close·§9 ①）；
                        // 视觉 26 圆压在照片上、触达 48 外溢不占版。
                        val removeLabel = stringResource(R.string.a11y_remove_image)
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .liuliFootprint(REMOVE_DOT)
                                .clickable(role = Role.Button, onClickLabel = removeLabel) { imageUri = null },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(REMOVE_DOT)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = REMOVE_DOT_ALPHA)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = removeLabel,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(INLAY_SHAPE)
                            .background(colors.surface.raised.copy(alpha = INLAY_ALPHA))
                            .clickable {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, tint = colors.economy.gold)
                        Text("添加图片", style = AppTypography.listPreview, color = onGlass.primary)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("花多少金币", style = AppTypography.snackbarBody, color = onGlass.secondary)
                    Box(Modifier.weight(1f))
                    Icon(Icons.Filled.MonetizationOn, contentDescription = null, tint = colors.economy.gold, modifier = Modifier.size(16.dp))
                    Text(" $cost 金币", style = AppTypography.amount, color = onGlass.primary)
                }
                LiuliSlider(
                    value = costValue,
                    onValueChange = { costValue = it },
                    valueRange = 2f..20f,
                    steps = 17, // 2..20 step 1 → 19 个离散值 → 中间 17 个停靠点（照抄 F24）
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "花多少不决定你的心意；你自己做的这件事，才是真正被记住的部分。",
                    style = AppTypography.snackbarBody,
                    color = onGlass.secondary,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("预览", style = AppTypography.snackbarBody, color = onGlass.secondary)
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LiuliGiftCard(data = previewCard, isFromUser = true, diyImage = null, onDiyClick = null)
                }
            }

            LiuliButton(
                onClick = { showConfirm = true },
                style = LiuliButtonStyle.Prominent,
                enabled = canSend,
                modifier = Modifier.align(Alignment.End),
            ) { Text("送出") }
        }
    }

    if (showConfirm) {
        LiuliDialog(
            onDismissRequest = { showConfirm = false },
            title = "送出这份 ${trimmedTitle.ifEmpty { "手作礼物" }}？",
            body = "将从余额扣 $cost 金币",
            confirmText = "确认送出",
            onConfirm = {
                showConfirm = false
                if (canSend) {
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
                }
            },
            dismissText = "取消",
            onDismiss = { showConfirm = false },
        )
    }
}
