package com.situ.aichat.ui.liuli.character.edit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import com.situ.aichat.ui.designsystem.AppActionIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.ui.liuli.designsystem.LiuliAvatarRing
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliMaterials
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliAccentFill
import com.situ.aichat.ui.liuli.page.LiuliDangerRow
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import com.situ.aichat.util.WallpaperStore

// 琉璃 2.0 卷五 §4.3 锁定值。
private val AVATAR_RING = 100.dp
private val AVATAR = 96.dp
private val CAMERA_BADGE = 30.dp
private val CAMERA_ICON = 16.dp
private val WALLPAPER_EMPTY_H = 96.dp
private val WALLPAPER_SET_H = 168.dp
private val WALLPAPER_CORNER = 14.dp

/** 头像块（§4.3）：渐变光环里的头像 + 右下相机徽章（两处都触发选图）+ 有头像时的「移除头像」文字钮。 */
@Composable
internal fun LiuliEditAvatarBlock(
    name: String,
    avatarPath: String?,
    onPickAvatar: () -> Unit,
    onRemoveAvatar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            LiuliAvatarRing(AVATAR_RING) {
                CharacterAvatar(
                    name = name.ifEmpty { "?" },
                    avatarPath = avatarPath,
                    size = AVATAR,
                    modifier = Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.char_avatar_change),
                        onClick = onPickAvatar,
                    ),
                )
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(CAMERA_BADGE)
                    .liuliAccentFill(CircleShape)
                    .clickable(role = Role.Button, onClick = onPickAvatar),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PhotoCamera,
                    contentDescription = stringResource(R.string.char_avatar_change),
                    tint = Palette.White,
                    modifier = Modifier.size(CAMERA_ICON),
                )
            }
        }
        if (avatarPath != null) {
            LiuliButton(onClick = onRemoveAvatar, style = LiuliButtonStyle.Text, danger = true) {
                Text(stringResource(R.string.char_avatar_remove))
            }
        }
    }
}

/** 聊天壁纸组（§4.3）：空态 = 虚线圆角框「添加聊天壁纸」；已设 = 预览 + 右下「更换」胶囊 + 「移除壁纸」危险行。 */
@Composable
internal fun LiuliEditWallpaperGroup(
    wallpaperPath: String?,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    LiuliGroup(
        modifier = modifier,
        header = stringResource(R.string.char_section_wallpaper),
        footer = stringResource(R.string.char_wallpaper_hint),
    ) {
        if (wallpaperPath == null) {
            LiuliRowBase(divider = false, onClick = onPick, minHeight = 0.dp, verticalPadding = 12.dp) {
                val dash = LiuliMaterials.handle(dark)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(WALLPAPER_EMPTY_H)
                        .clip(RoundedCornerShape(WALLPAPER_CORNER))
                        .drawBehind {
                            val inset = 0.5.dp.toPx()
                            drawRoundRect(
                                color = dash,
                                topLeft = Offset(inset, inset),
                                size = Size(size.width - inset * 2, size.height - inset * 2),
                                cornerRadius = CornerRadius(WALLPAPER_CORNER.toPx()),
                                style = Stroke(
                                    width = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                                ),
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(AppActionIcons.AddPhoto, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.char_wallpaper_add), style = AppTypography.body, color = colors.accent.text)
                    }
                }
            }
        } else {
            val bitmap by produceState<ImageBitmap?>(initialValue = null, wallpaperPath) {
                value = WallpaperStore.load(wallpaperPath)?.asImageBitmap()
            }
            LiuliRowBase(divider = false, onClick = onPick, minHeight = 0.dp, verticalPadding = 12.dp) {
                Box(Modifier.fillMaxWidth().height(WALLPAPER_SET_H).clip(RoundedCornerShape(WALLPAPER_CORNER))) {
                    val bmp = bitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = stringResource(R.string.char_wallpaper_preview_desc),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(Modifier.fillMaxSize().background(LiuliMaterials.segTrack(dark)))
                    }
                    Text(
                        stringResource(R.string.char_wallpaper_change),
                        style = AppTypography.caption.copy(fontWeight = FontWeight.W600),
                        color = colors.text.primary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(colors.surface.raised.copy(alpha = 0.85f), LiuliShapes.pill)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            LiuliDangerRow(stringResource(R.string.char_wallpaper_remove), onClick = onRemove)
        }
    }
}
