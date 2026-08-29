package com.situ.aichat.ui.world.web

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.situ.aichat.ui.world.WorldSceneColors
import com.situ.aichat.ui.world.WorldUiState
import kotlinx.coroutines.delay

/** 网页星球镜头控制口（[com.situ.aichat.ui.world.WorldTransitions] 消费 = GL 版 `setCinematicPose` 的 web 对位）。 */
internal interface PlanetWebController {
    /**
     * 镜头补间到目标姿态（JS EaseInOut·契约 §6）。俯冲目标由 [com.situ.aichat.ui.world.planet.PlanetMath]
     * 在**原生**算好传入（单源·JS 不复制这套数学·§J7）。
     */
    fun playPose(yaw: Float, pitch: Float, dist: Float, ms: Int)

    /** 当前相机姿态 yaw/pitch/dist（桥心跳缓存·最多 0.5s 旧·§J6）。未收到过 → 恢复值 / 契约默认姿态。 */
    fun poseSnapshot(): Triple<Float, Float, Float>
}

// ── 相机常数镜像（契约 §5.2·§5：只镜像，绝不在原生复刻页面的相机积分）──
private const val DEF_YAW = 0.6f
private const val DEF_PITCH = -0.25f
private const val DEF_DIST = 3.1f

/** 揭幕闸超时（§3）：挂载后这么久还没收到 web 首帧 → 视同场景失败，回落 GL 星球。 */
private const val REVEAL_TIMEOUT_MS = 8_000L

private const val PAGE_URL = "file:///android_asset/world_web/planet.html"

/**
 * 网页星球场景宿主（图纸「网页世界二期」§3/§J8）：`WebView` + 桥接线，与 GL 兜底整链
 * [com.situ.aichat.ui.world.PlanetSceneHost]（零碰）平行实现同一玩法面。
 *
 * **与 GL 版的分工差异（§J8）**：家标记 / 名签 / 屏缘指路雪佛龙这一整层由**页面**接管（GL 版是 Compose
 * 叠层逐帧投影 + GLView 点按路由），故本宿主不挂 `WorldHomeMarker` / `WorldHomeChevron`；页面的
 * `onTapHome` 与 `onDiveGesture` 都走 [onDive]，`onSpinHome` 由页面自己转球、原生只回一次触觉 [onSpinHome]。
 *
 * web 起不来 / 跑挂 / 8s 无首帧 → [onWebFailed] → 本会话内回落 GL 星球（不入库不持久·§J1）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BoxScope.PlanetWebSceneView(
    ui: WorldUiState,
    reduceMotion: Boolean,
    interactive: Boolean, // 转场输入锁：非 None 转场期间不响应（经 setFlags 同步给 web）。
    initialPose: Triple<Float, Float, Float>?,
    onFirstFrame: () -> Unit,
    onDive: () -> Unit,
    onSpinHome: () -> Unit,
    onWebFailed: () -> Unit,
    onViewReady: (PlanetWebController) -> Unit,
    /** 测试注入口：只替换「把报文发出去」这一步（默认走真 WebView），行为字节不变（R1 🔴-1 返修要求的可注入函数口）。 */
    jsSink: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val host = remember { PlanetWebHost() }
    var pageReady by remember { mutableStateOf(false) }
    var inited by remember { mutableStateOf(false) }
    var firstFrameSeen by remember { mutableStateOf(false) }

    val staticMode = ui.staticMode

    // 桥回调只碰 [host] 上每轮刷新的最新引用——`remember` 闭包直接捕获会永停首帧值（PITFALLS 1h）。
    SideEffect {
        // R1 🔴-1：三旗也挂 host —— 生命周期观察者只建一次，闭包按值捕获参数会永停首次组合值。
        host.reduceMotion = reduceMotion
        host.staticMode = staticMode
        host.interactive = interactive
        host.initialPose = initialPose
        host.onFirstFrame = { firstFrameSeen = true; onFirstFrame() }
        host.onDive = onDive
        host.onSpinHome = onSpinHome
        host.onFailed = onWebFailed
    }

    val bridge = remember {
        WorldWebBridge(
            onReadyCb = {},
            onFirstFrameCb = { host.onFirstFrame() },
            onPoseCb = { json -> host.pose = WorldWebData.planetPoseFrom(json) ?: host.pose },
            onErrorCb = { host.onFailed() },
            onTapHomeCb = { if (host.interactive) host.onDive() },
            onSpinHomeCb = { if (host.interactive) host.onSpinHome() },
            onDiveGestureCb = { if (host.interactive) host.onDive() },
        )
    }

    // WebView 建视图（E1：ROM 无 WebView / provider 损坏会抛 → 捕获后回落 GL）。
    val webView = remember {
        runCatching {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowFileToFileAccess()
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.blockNetworkLoads = true // §5 负向禁令：web 端零网络请求（平台级闸，不靠自觉）
                setBackgroundColor(WorldSceneColors.background.toArgb()) // 首帧前防闪白
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addJavascriptInterface(bridge, WorldWebBridge.NAME)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { pageReady = true }
                    override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                        if (req.isForMainFrame) host.onFailed()
                    }

                    /** E11（R1 🟡-1 补）：渲染进程被系统回收 / 崩溃 —— 不接管则框架默认连宿主 App 一起杀。 */
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        host.onFailed() // 走既有单一回落口·detail 内容一律丢弃不落日志（§5 零 Log）
                        return true
                    }
                }
            }
        }.getOrNull()
    }

    if (webView == null) {
        LaunchedEffect(Unit) { onWebFailed() } // E1：本会话回落 GL 星球
        return
    }
    // 发送口依赖 webView，故在其建好后单独刷（仍是 `SideEffect`·每轮更新·先于 LaunchedEffect 生效）。
    SideEffect { host.send = jsSink ?: { call -> webView.callWorldWeb(call) } }
    LaunchedEffect(webView) { onViewReady(host); webView.loadUrl(PAGE_URL) }

    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxSize(),
        onRelease = {
            bridge.release()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        },
    )

    // 揭幕闸（§3·E3）：挂载 8s 内没见首帧 → 回落 GL 星球。幕由转场编排保持不动，改挂的 GL 星球出首帧时
    // 经同一个 onPlanetFirstFrame 放行等待方 → 全程不闪黑。
    LaunchedEffect(Unit) {
        delay(REVEAL_TIMEOUT_MS)
        if (!firstFrameSeen) onWebFailed()
    }

    // 装载序（§3 锁定）：init → restorePose → setFlags。
    LaunchedEffect(webView, pageReady) {
        if (!pageReady) return@LaunchedEffect
        val json = WorldWebData.planetJson(ui.seed, ui.seedOff, ui.homeX, ui.homeY, ui.homeCityName)
        host.send("init(${jsArg(json)})")
        val pose = initialPose
        host.send("restorePose(${if (pose == null) "null" else "JSON.parse(${jsArg(WorldWebData.planetPoseJson(pose))})"})")
        host.send(setFlagsCall(reduceMotion, staticMode, interactive))
        inited = true
    }

    // 增量推送：三旗（转场输入锁 / 省电 / 减弱动画）。
    LaunchedEffect(inited, reduceMotion, staticMode, interactive) {
        if (inited) host.send(setFlagsCall(reduceMotion, staticMode, interactive))
    }

    // 生命周期：熄屏/后台停帧，回前台补渲一帧 + 补一次三旗（E7）。
    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    webView.onResume(); webView.resumeTimers()
                    // R1 🔴-1：推**回前台那一刻**的三旗（读 host 活引用），不是首次组合冻结的那一份。
                    if (inited) host.send(setFlagsCall(host.reduceMotion, host.staticMode, host.interactive))
                }
                Lifecycle.Event.ON_PAUSE -> { webView.pauseTimers(); webView.onPause() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); webView.pauseTimers(); webView.onPause() }
    }
}

/**
 * 桥与转场编排的共享落点：Compose 每轮经 `SideEffect` 刷新可变引用，桥闭包只读这里（防 Compose 捕获过期）。
 * 同时实现 [PlanetWebController] 供 [com.situ.aichat.ui.world.WorldTransitions] 驱动镜头。
 */
private class PlanetWebHost : PlanetWebController {
    /** 发往页面的唯一出口（Compose 每轮 `SideEffect` 刷新·测试可注入）。**主线程调**。 */
    var send: (String) -> Unit = {}
    var reduceMotion: Boolean = false
    var staticMode: Boolean = false
    var interactive: Boolean = false
    var initialPose: Triple<Float, Float, Float>? = null
    var onFirstFrame: () -> Unit = {}
    var onDive: () -> Unit = {}
    var onSpinHome: () -> Unit = {}
    var onFailed: () -> Unit = {}

    /** 最新相机姿态（web 手势结束 + 2Hz 心跳推送·JS 线程写 / 转场协程读）。 */
    @Volatile
    var pose: Triple<Float, Float, Float>? = null

    override fun playPose(yaw: Float, pitch: Float, dist: Float, ms: Int) {
        val json = WorldWebData.playPoseJson(yaw = yaw, pitch = pitch, dist = dist)
        send("playPose(JSON.parse(${jsArg(json)}), $ms)")
    }

    override fun poseSnapshot(): Triple<Float, Float, Float> =
        pose ?: initialPose ?: Triple(DEF_YAW, DEF_PITCH, DEF_DIST)
}
