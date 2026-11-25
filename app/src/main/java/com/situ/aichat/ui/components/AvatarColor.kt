package com.situ.aichat.ui.components

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 确定性单字头像莫兰迪色板（去谷歌化批一过审）——按角色名 UTF-8 字节跑 djb2 哈希，从 8 组低饱和莫兰迪双色里稳定取色。
 *
 * 明暗双套色板均保证与温润米白字色（[monogramInk]）的对比度充足，消除旧版 iOS 荧光饱和蓝/粉/绿与暖陶/琉璃纸面的违和感。
 */
object AvatarColor {

    /** 浅色莫兰迪 8 色（上轻下沉纵向渐变，陶土/鼠尾草/雾蓝/烟玫/焦糖/熏紫/海雾/焙棕）。 */
    internal val lightPalette: List<Pair<Color, Color>> = listOf(
        Color(0xFFB07862) to Color(0xFF9E6650), // 陶土
        Color(0xFF6D846E) to Color(0xFF5B725C), // 鼠尾草
        Color(0xFF667D94) to Color(0xFF556C82), // 雾蓝
        Color(0xFFA66D78) to Color(0xFF945B66), // 烟玫
        Color(0xFFA67F4E) to Color(0xFF936D3E), // 焦糖
        Color(0xFF7E6E9E) to Color(0xFF6C5C8C), // 熏紫
        Color(0xFF5B8282) to Color(0xFF4B7070), // 海雾
        Color(0xFF8C6F63) to Color(0xFF7A5E52), // 焙棕
    )

    /** 暗色莫兰迪 8 色（降亮度防眩，与暗夜纸面/琉璃深底相融）。 */
    internal val darkPalette: List<Pair<Color, Color>> = listOf(
        Color(0xFF8E5F4D) to Color(0xFF7C5040),
        Color(0xFF566B57) to Color(0xFF475B48),
        Color(0xFF506478) to Color(0xFF425466),
        Color(0xFF875862) to Color(0xFF754953),
        Color(0xFF86663E) to Color(0xFF745632),
        Color(0xFF665882) to Color(0xFF564870),
        Color(0xFF486A6A) to Color(0xFF3B5A5A),
        Color(0xFF725A50) to Color(0xFF614B42),
    )

    /** 单字头像温润米白字色（浅色偏象牙白、暗色略柔化防刺眼）。 */
    fun monogramInk(dark: Boolean = false): Color =
        if (dark) Color(0xFFEDE8E2) else Color(0xFFFAF7F2)

    private fun indexFor(name: String): Int {
        var hash = 5381UL
        for (b in name.toByteArray(Charsets.UTF_8)) {
            hash = ((hash shl 5) + hash) + (b.toInt() and 0xFF).toULong()
        }
        return (hash % lightPalette.size.toULong()).toInt()
    }

    /** djb2 稳定哈希取底色（跨进程/跨重启确定性）。 */
    fun color(name: String, dark: Boolean = false): Color {
        val pair = (if (dark) darkPalette else lightPalette)[indexFor(name)]
        return pair.second
    }

    /** 温和纵向渐变（顶微亮 → 底微沉）。 */
    fun brush(name: String, dark: Boolean = false): Brush {
        val (top, bottom) = (if (dark) darkPalette else lightPalette)[indexFor(name)]
        return Brush.verticalGradient(listOf(top, bottom))
    }
}
