package com.situ.aichat.ui.world.web

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.situ.aichat.ui.world.town.TownCamSnapshot

/**
 * 网页小镇 → 原生的回调桥（图纸「网页世界一期」§J2 桥面 + §3.5 并发纪律）。
 *
 * **线程**：`@JavascriptInterface` 方法跑在 WebView 的 JS 线程，一律 `post` 回主线程再碰 Compose 状态；
 * 姿态缓存 [pose] 是 `@Volatile`，供转场编排在任意线程读最新值（最多 0.5s 旧·J6 已接受）。
 *
 * **纪律**：非法/缺字段输入静默丢弃（不崩不打日志）；[onError] 的 msg 可能含 JS 栈 → 只作回落信号用，
 * 内容当场丢弃不外传不落日志（图纸 §6）。
 */
internal class TownWebBridge(
    private val onReadyCb: () -> Unit,
    private val onFirstFrameCb: () -> Unit,
    private val onTapPlaceCb: (String) -> Unit,
    private val onTapCastCb: (String) -> Unit,
    private val onReturnGestureCb: () -> Unit,
    private val onErrorCb: () -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())

    /** 最新相机姿态（web 手势结束 + 2Hz 心跳推送）。未收到过任何一帧 → null，调用方回退到自存快照。 */
    @Volatile
    var pose: TownCamSnapshot? = null
        private set

    /** 卸载后不再转发（WebView.destroy 与在途 post 竞态·§3.5）。 */
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

    @JavascriptInterface
    fun onReady() = post(onReadyCb)

    @JavascriptInterface
    fun onFirstFrame() = post(onFirstFrameCb)

    /** 点中地点；空串 = 点空地（语义 = GL 版空点清选中·相机不复位）。 */
    @JavascriptInterface
    fun onTapPlace(placeId: String?) {
        val id = placeId ?: return
        post { onTapPlaceCb(id) }
    }

    @JavascriptInterface
    fun onTapCast(cardId: String?) {
        val id = cardId?.takeIf { it.isNotEmpty() } ?: return
        post { onTapCastCb(id) }
    }

    @JavascriptInterface
    fun onReturnGesture() = post(onReturnGestureCb)

    /** 姿态心跳：只更新 volatile 缓存，不碰 Compose（2Hz × 不重组）。非法报文丢弃、保留上一份。 */
    @JavascriptInterface
    fun onPose(poseJson: String?) {
        if (released) return
        val json = poseJson ?: return
        pose = TownWebData.poseFrom(json) ?: return
    }

    /** JS 运行期错误 / 资源加载失败 / WebGL 上下文丢失 → 回落 GL 小镇（msg 不留存·§6）。 */
    @JavascriptInterface
    fun onError(msg: String?) = post(onErrorCb)

    companion object {
        /** 注入名（town.js 读 `window.AndroidBridge`·契约 §2.2 逐字锁死）。 */
        const val NAME = "AndroidBridge"
    }
}
