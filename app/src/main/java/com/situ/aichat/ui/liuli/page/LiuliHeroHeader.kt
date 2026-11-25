package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.util.AvatarStore
import com.situ.aichat.ui.components.AvatarColor
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliAvatarRing
import com.situ.aichat.ui.liuli.home.LiuliRelationshipPill

/** 头部落值（卷四 §4.9）：顶距 12 · 光环 100 / 头像 96 · 名行 36（名 26/700）· 名下 4 · 副行 20（14）。 */
private val HEAD_TOP = 12.dp
private val HEAD_RING = 100.dp
private val HEAD_AVATAR = 96.dp
private val HEAD_NAME_GAP = 12.dp
private val HEAD_NAME_ROW = 36.dp
private val HEAD_SUB_ROW = 20.dp
/** 无照片首字 34/700（契约 §6.5「头图」·原值）。 */
private val MONOGRAM_TEXT = 34.sp
private val NAME_SIZE = 26.sp
private val SUB_SIZE = 14.sp
private val PILL_TEXT = 13.sp

/**
 * 资料页头部（卷四 §0.2-9·设计稿 ⑤）：主题柔光底上居中 100 光环头像（照片 / 首字 / 加载中三态同 R1 🟡-7）+ 名 26/700 +
 * 关系标签 + 副行；总高恒 = topInset + [LiuliPageGeometry.profileHead]。
 *
 * 头像三态：有照片 = 圆内 `Crop`；没设过头像、或路径解不出来 → 首字（复核 R1 🟡-7）；「有路径但还没解出来」只留渐变底、
 * 不闪一下字（E10）。视差由调用方在 `graphicsLayer` 里给（本件不读滚动态）。
 */
@Composable
fun LiuliHeroHeader(
    name: String,
    avatarPath: String?,
    relationshipLabel: String,
    subtitle: String,
    topInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
    /** 头像解码（测试可注入·默认 `AvatarStore.load`）。 */
    loadAvatar: suspend (String) -> ImageBitmap? = { AvatarStore.load(it)?.asImageBitmap() },
) {
    // 三态：加载中（只渐变）/ 解出图 / 解不出（路径坏了）→ 回落 monogram（复核 R1 🟡-7：别让坏路径把头图永远留成空渐变）。
    val image by produceState<HeroImage>(initialValue = HeroImage.Loading, avatarPath) {
        value = HeroImage.Done(if (avatarPath.isNullOrEmpty()) null else loadAvatar(avatarPath))
    }
    val colors = AppTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(topInset + LiuliPageGeometry.profileHead)
            .padding(top = topInset + HEAD_TOP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LiuliAvatarRing(HEAD_RING) {
            Box(
                Modifier.size(HEAD_AVATAR).clip(CircleShape).background(AvatarColor.brush(name, dark = colors.isDark)),
                contentAlignment = Alignment.Center,
            ) {
                val done = image as? HeroImage.Done
                if (done?.bitmap != null) {
                    Image(
                        bitmap = done.bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (done != null) {
                    Text(
                        name.take(1).uppercase().ifEmpty { "·" },
                        style = AppTypography.titleLarge.copy(fontSize = MONOGRAM_TEXT, fontWeight = FontWeight.W700),
                        color = AvatarColor.monogramInk(dark = colors.isDark),
                    )
                }
                // Loading：只渐变。
            }
        }
        Spacer(Modifier.height(HEAD_NAME_GAP))
        Row(
            modifier = Modifier.height(HEAD_NAME_ROW),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                name,
                style = AppTypography.titleLarge.copy(fontSize = NAME_SIZE, fontWeight = FontWeight.W700),
                color = colors.text.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            LiuliRelationshipPill(relationshipLabel, fontSize = PILL_TEXT)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.height(HEAD_SUB_ROW), contentAlignment = Alignment.Center) {
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = AppTypography.listPreview.copy(fontSize = SUB_SIZE),
                    color = colors.text.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 头图三态（见 [LiuliHeroHeader]）。 */
private sealed interface HeroImage {
    data object Loading : HeroImage
    data class Done(val bitmap: ImageBitmap?) : HeroImage
}
