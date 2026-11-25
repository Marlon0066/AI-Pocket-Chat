package com.situ.aichat.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.util.AvatarStore

/**
 * Shared circular character avatar. Shows the stored image at [avatarPath] when present, otherwise a
 * monogram of the first character of [name] over a per-name Morandi gradient ([AvatarColor]).
 * Decoding happens off the main thread via [AvatarStore.load], keyed by path.
 *
 * Three render states (only missing avatar shows the letter):
 *  - has a decoded image → the image;
 *  - no avatar path → the monogram letter on the gradient;
 *  - has a path but still decoding → just the gradient (no letter), so we never flash a letter before the image.
 */
@Composable
fun CharacterAvatar(
    name: String,
    avatarPath: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val isDark = AppTheme.colors.isDark
    val bitmap by produceState<ImageBitmap?>(initialValue = null, avatarPath) {
        value = AvatarStore.load(avatarPath)?.asImageBitmap()
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(AvatarColor.brush(name, dark = isDark)),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            avatarPath.isNullOrEmpty() -> Text(
                text = name.take(1).uppercase().ifEmpty { "·" },
                color = AvatarColor.monogramInk(dark = isDark),
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
            )
            // else: has a path but bitmap not ready yet → show only the gradient background (no letter flash).
        }
    }
}
