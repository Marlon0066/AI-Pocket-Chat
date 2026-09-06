package com.situ.aichat.ui.liuli.glass

import com.situ.aichat.data.model.GlassTier
import org.junit.Assert.assertEquals
import org.junit.Test

/** T1：透明度档位的 API 门（契约 FABLE5_THEME_LIULI_PROPOSAL.md §4.1「API 29–30 强制着色无模糊」）。 */
class GlassTierTest {

    @Test
    fun `有实时模糊能力时请求什么档就是什么档`() {
        assertEquals(GlassTier.CLEAR, GlassTier.CLEAR.effective(blurSupported = true))
        assertEquals(GlassTier.TINTED, GlassTier.TINTED.effective(blurSupported = true))
    }

    @Test
    fun `没有实时模糊能力时一律着色`() {
        assertEquals(GlassTier.TINTED, GlassTier.CLEAR.effective(blurSupported = false))
        assertEquals(GlassTier.TINTED, GlassTier.TINTED.effective(blurSupported = false))
    }

    @Test
    fun `染色落值按契约 4_1 逐值`() {
        assertEquals(LiuliGlassSpec.tintLight to 0.60f, LiuliGlassSpec.tint(dark = false, tier = GlassTier.CLEAR))
        assertEquals(LiuliGlassSpec.tintLightTinted to 0.88f, LiuliGlassSpec.tint(dark = false, tier = GlassTier.TINTED))
        assertEquals(LiuliGlassSpec.tintDark to 0.52f, LiuliGlassSpec.tint(dark = true, tier = GlassTier.CLEAR))
        assertEquals(LiuliGlassSpec.tintDark to 0.86f, LiuliGlassSpec.tint(dark = true, tier = GlassTier.TINTED))
    }
}
