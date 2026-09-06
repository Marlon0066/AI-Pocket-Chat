package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.util.AvatarStore
import com.situ.aichat.ui.components.AvatarColor
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.ui.liuli.designsystem.LiuliPalette
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.glass.LiuliGlassStyle
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** 无照片时的 monogram 圆 96 / 字 34/700（契约 §6.5「头图」）：圆本身 = 白 18% 底 + 0.5 白 35% 边（复核 R1 🔵-1·让「圆」看得见）。 */
private val MONOGRAM = 96.dp
private val MONOGRAM_TEXT = 34.sp
private const val MONOGRAM_FILL_ALPHA = 0.18f
private val MONOGRAM_RIM = 0.5.dp
private const val MONOGRAM_RIM_ALPHA = 0.35f
/** 名 28/700 · 左 20 底 16 · 关系胶囊在名右 8 · 副行 14 白 85%。 */
private val NAME_SIZE = 28.sp
private val NAME_BOTTOM = 16.dp
private val PILL_GAP = 8.dp
private val PILL_HEIGHT = 24.dp
private val PILL_PAD_H = 10.dp
private val PILL_TEXT = 12.sp
private val SUB_SIZE = 14.sp
private const val SUB_ALPHA = 0.85f
/** 遮罩：底 130 从透明到墨 55%。 */
private const val SCRIM_ALPHA = 0.55f

/**
 * 详情页头图（T3·契约 §6.5「头图」· A-8）。
 *
 * 有照片 = 280 高满宽 `Crop` + 底 130 遮罩（`ink@0 → 55%`）；无照片 / 加载中 = `AvatarColor.brush(name)`
 * 渐变底 + 96 圆 monogram（加载中**只渐变不闪字**·同 `CharacterAvatar` 的判据 E10）。
 *
 * 名 28/700 白左 20 底 16，右侧 8 处是关系胶囊（玻璃 pill 24·Button 档）；副行 14 白 85% 单行省略。
 * 视差由调用方在 `graphicsLayer` 里给（本件不读滚动态）。
 */
@Composable
fun LiuliHeroHeader(
    name: String,
    avatarPath: String?,
    relationshipLabel: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    /** 头像解码（测试可注入·默认 `AvatarStore.load`）。 */
    loadAvatar: suspend (String) -> ImageBitmap? = { AvatarStore.load(it)?.asImageBitmap() },
) {
    // 三态：加载中（只渐变）/ 解出图 / 解不出（路径坏了）→ 回落 monogram（复核 R1 🟡-7：别让坏路径把头图永远留成空渐变）。
    val image by produceState<HeroImage>(initialValue = HeroImage.Loading, avatarPath) {
        value = HeroImage.Done(if (avatarPath.isNullOrEmpty()) null else loadAvatar(avatarPath))
    }
    val dark = LocalIsDarkTheme.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(LiuliPageGeometry.hero)
            .background(AvatarColor.brush(name)),
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
            // 没设过头像、或路径解不出来 → monogram；「有路径但还没解出来」只留渐变，不闪一下字（E10）。
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(MONOGRAM)
                    .clip(CircleShape)
                    .background(Palette.White.copy(alpha = MONOGRAM_FILL_ALPHA))
                    .border(MONOGRAM_RIM, Palette.White.copy(alpha = MONOGRAM_RIM_ALPHA), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.take(1).uppercase().ifEmpty { "·" },
                    style = AppTypography.titleLarge.copy(fontSize = MONOGRAM_TEXT, fontWeight = FontWeight.W700),
                    color = Palette.White,
                )
            }
        }
        // 底 130 遮罩：从透明渐到墨 55%，让白名字在任何照片上都读得出。
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(LiuliPageGeometry.heroScrim)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            LiuliPalette.heroScrimInk.copy(alpha = 0f),
                            LiuliPalette.heroScrimInk.copy(alpha = SCRIM_ALPHA),
                        ),
                    ),
                ),
        )
        HeroCaption(name = name, relationshipLabel = relationshipLabel, subtitle = subtitle, dark = dark)
    }
}

/** 头图左下的名 + 关系胶囊 + 副行。 */
@Composable
private fun BoxScope.HeroCaption(name: String, relationshipLabel: String, subtitle: String, dark: Boolean) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .padding(start = LiuliPageGeometry.gutter, end = LiuliPageGeometry.gutter, bottom = NAME_BOTTOM),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PILL_GAP)) {
            Text(
                name,
                style = AppTypography.titleLarge.copy(fontSize = NAME_SIZE, fontWeight = FontWeight.W700),
                color = Palette.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Box(
                Modifier
                    .height(PILL_HEIGHT)
                    .liuliGlass(LiuliShapes.pill, dark = dark, style = LiuliGlassStyle.Button)
                    .padding(horizontal = PILL_PAD_H),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    relationshipLabel,
                    style = AppTypography.caption.copy(fontSize = PILL_TEXT, fontWeight = FontWeight.W600),
                    color = LiuliTheme.onGlass.primary,
                    maxLines = 1,
                )
            }
        }
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = AppTypography.listPreview.copy(fontSize = SUB_SIZE),
                color = Color.White.copy(alpha = SUB_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 头图三态（见 [LiuliHeroHeader]）。 */
private sealed interface HeroImage {
    data object Loading : HeroImage
    data class Done(val bitmap: ImageBitmap?) : HeroImage
}
