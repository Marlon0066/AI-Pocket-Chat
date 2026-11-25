package com.situ.aichat.ui.character

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppChoiceChip
import com.situ.aichat.ui.offline.parseHexColorOrNull
import com.situ.aichat.util.WallpaperStore

/** 聊天壁纸选择器（chunk2·契约 §3.1）：空态=虚线「添加聊天壁纸」；已设=预览缩略图 + 右下「更换」胶囊 + 下方「移除壁纸」。
 *  点预览/占位=重选（PickVisualMedia→WallpaperStore.save，旧文件 save 时在 VM 落定后清理）。 */
@Composable
internal fun WallpaperPicker(
    wallpaperPath: String?,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    if (wallpaperPath == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(116.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .clickable(onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppActionIcons.AddPhoto, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.char_wallpaper_add), color = MaterialTheme.colorScheme.primary)
            }
        }
    } else {
        val bitmap by produceState<ImageBitmap?>(initialValue = null, wallpaperPath) {
            value = WallpaperStore.load(wallpaperPath)?.asImageBitmap()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onPick),
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = stringResource(R.string.char_wallpaper_preview_desc),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            }
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.char_wallpaper_change),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        AppButton(onClick = onRemove, style = AppButtonStyle.Text, danger = true) {
            Text(stringResource(R.string.char_wallpaper_remove))
        }
    }
}

// 线下主题色预设（6 位 hex·RRGGBB·与 OfflineTheme.parseHexColorOrNull 同格式；默认=空→teal）。
internal val OfflineThemePresets = listOf("14B8A6", "FF2D55", "AF52DE", "0A84FF", "FF9F0A", "34C759", "FF3B30", "5E5CE6")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OfflineThemeColorPicker(selectedHex: String, onSelect: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        AppChoiceChip(
            selected = selectedHex.isBlank(),
            onClick = { onSelect("") },
            label = stringResource(R.string.char_theme_default),
        )
        OfflineThemePresets.forEachIndexed { index, hex ->
            val color = parseHexColorOrNull(hex) ?: MaterialTheme.colorScheme.surfaceVariant
            val isSelected = selectedHex.equals(hex, ignoreCase = true)
            val ring = if (isSelected) {
                Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
            } else {
                Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            }
            // 无障碍（14.7e）：色块仅靠视觉边框环表达选中、无标签 → TalkBack 读「未加标签按钮」。
            // 给单选语义（Role.RadioButton + selected）+ 以序号命名（预设无人类色名，序号最稳）。
            val swatchDesc = stringResource(R.string.char_theme_swatch_desc, index + 1)
            Box(
                Modifier
                    .size(36.dp)
                    .background(color, CircleShape)
                    .then(ring)
                    .clickable { onSelect(hex) }
                    .semantics {
                        role = Role.RadioButton
                        selected = isSelected
                        contentDescription = swatchDesc
                    },
            )
        }
    }
}
