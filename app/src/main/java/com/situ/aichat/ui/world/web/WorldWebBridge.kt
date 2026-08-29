package com.situ.aichat.ui.world.web

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * 网页大陆 / 星球 → 原生的回调桥（图纸「网页世界二期」§J2·**一个类服务两页**：共通四回调必填，
 * 大陆 / 星球各自的扩展回调可空默认空实现——不传即静默丢弃，页面互不知情）。
 * 函数名 = 前端契约 §3.2 逐字锁死；注入名 [NAME] 同一期。
 *
 * **线程**（同一期 [TownWebBridge] 范式）：`@JavascriptInterface` 方法跑在 WebView 的 JS 线程，凡要碰 Compose
 * 状态的一律 `post` 回主线程；唯 [onPose] 例外——2Hz 心跳直接在 JS 线程交给消费方写 `@Volatile` 缓存，
 * 不进重组热路径。[release] 后不再转发（WebView.destroy 与在途 post 的竞态）。
 *
 * **纪律**：非法/缺字段输入静默丢弃（不崩不打日志）；[onError] 的 msg 可能含 JS 栈 → 只作回落信号用，
 * 内容当场丢弃不外传不落日志（图纸 §5）。
 */
internal class WorldWebBridge(
    // ── 共通四回调（契约 §3.2·必填）──
    private val onReadyCb: () -> Unit,
    private val onFirstFrameCb: () -> Unit,
    /** 姿态心跳原文（**JS 线程直调**·由消费方解析并保留上一份合法值）。 */
    private val onPoseCb: (String) -> Unit,
    private val onErrorCb: () -> Unit,
    // ── 大陆页扩展（星球宿主不传）──
    private val onTapSiteCb: (String) -> Unit = {},
    private val onTapEmptyCb: () -> Unit = {},
    private val onReturnGestureCb: () -> Unit = {},
    private val onTownDiveCb: () -> Unit = {},
    // ── 星球页扩展（大陆宿主不传）──
    private val onTapHomeCb: () -> Unit = {},
    private val onSpinHomeCb: () -> Unit = {},
    private val onDiveGestureCb: () -> Unit = {},
) {

    private val main = Handler(Looper.getMainLooper())

    /** 卸载后不再转发（WebView.destroy 与在途 post 竞态）。 */
    @Volatile
    private var released = false

    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
    }

    private fun post(action: () -> Unit) {
        if (released) return
        main.post { if (!released) action() }
    }

    // ── 共通 ──

    @JavascriptInterface
    fun onReady() = post(onReadyCb)

    @JavascriptInterface
    fun onFirstFrame() = post(onFirstFrameCb)

    /** 姿态心跳：不回主线程（2Hz × 不重组）。非法报文由消费方丢弃、保留上一份。 */
    @JavascriptInterface
    fun onPose(poseJson: String?) {
        if (released) return
        onPoseCb(poseJson ?: return)
    }

    /** JS 运行期错误 / 资源加载失败 / WebGL 上下文丢失 / 渐变探针失败 → 回落该场景 GL（msg 不留存·§5）。 */
    @JavascriptInterface
    fun onError(msg: String?) = post(onErrorCb)

    // ── 大陆页 ──

    /** 点中站位（标记或名签）。 */
    @JavascriptInterface
    fun onTapSite(siteId: String?) {
        val id = siteId?.takeIf { it.isNotEmpty() } ?: return
        post { onTapSiteCb(id) }
    }

    /** 点空地（= GL 版空点清选中）。 */
    @JavascriptInterface
    fun onTapEmpty() = post(onTapEmptyCb)

    /** dist 顶格后继续外捏累积 ≥1.10 → 回星球（页面只发一次·松手复位）。 */
    @JavascriptInterface
    fun onReturnGesture() = post(onReturnGestureCb)

    /** dist 到底后继续内捏累积 ≤0.90 → App 决定是否进镇（照 GL：有选中且非奇观才进）。 */
    @JavascriptInterface
    fun onTownDive() = post(onTownDiveCb)

    // ── 星球页 ──

    /** 点中家标记（正面可见时）→ App 播俯冲进大陆。 */
    @JavascriptInterface
    fun onTapHome() = post(onTapHomeCb)

    /** 点中屏缘指路雪佛龙（页面自己把球转回家正面·此回调仅供 App 触觉反馈·§J8）。 */
    @JavascriptInterface
    fun onSpinHome() = post(onSpinHomeCb)

    /** dist 到底后继续内捏累积 ≤0.90 → App 播俯冲进大陆（页面只发一次·松手复位）。 */
    @JavascriptInterface
    fun onDiveGesture() = post(onDiveGestureCb)

    companion object {
        /** 注入名（两页均读 `window.AndroidBridge`·契约 §3.2 逐字锁死·与一期同名不同实例）。 */
        const val NAME = "AndroidBridge"

        /** 页面挂的桥命名空间（两页均为 `window.worldWeb`·契约 §3.1 逐字锁死）。 */
        const val JS_NAMESPACE = "worldWeb"
    }
}
