package com.situ.aichat.util

/** codePoint 安全截断（图纸件⑧）：绝不切开代理对（emoji/CJK 扩展区）。n ≥ codePoint 数原样返回；n ≤ 0 返空。 */
fun String.takeCodePoints(n: Int): String {
    if (n <= 0) return ""
    if (codePointCount(0, length) <= n) return this
    return substring(0, offsetByCodePoints(0, n))
}
