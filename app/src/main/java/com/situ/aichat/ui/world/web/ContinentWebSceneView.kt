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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.world.WorldGlassChip
import com.situ.aichat.ui.world.WorldSceneColors
import com.situ.aichat.ui.world.WorldSheetActionMatrix
import com.situ.aichat.ui.world.WorldSiteSheet
import com.situ.aichat.ui.world.continent.ContinentCamSnapshot
import com.situ.aichat.ui.world.continent.ContinentSceneData
import com.situ.aichat.ui.world.continent.ContinentSite
import kotlinx.coroutines.delay

/** 网页大陆镜头控制口（[com.situ.aichat.ui.world.WorldTransitions] 消费 = GL 版 `setCinematicPose` 的 web 对位）。 */
internal interface ContinentWebController {
    /** 拉升离场（JS：pitch→1.12 / dist→95·EaseInOut·§3）。 */
    fun playExit(ms: Int)

    /** 俯冲进某站位（JS：target 滑向该站位 + dist→4.5·§J7）。站位不在表里 → 只收距（target 不动）。 */
    fun playDiveToSite(siteId: String, ms: Int)

    /** 关卡片（JS：tDist 回 max(当前, 34)）。 */
    fun closeSheet()

    /** 当前相机姿态 + 目标距（桥心跳缓存·最多 0.5s 旧·§J6）。未收到过 → 恢复值 / 契约默认姿态。 */
    fun poseSnapshot(): Pair<ContinentCamSnapshot, Float>
}

// ── 相机常数镜像（契约 §4.2·§5：只镜像，绝不在原生复刻页面的相机积分）──
private const val DEF_YAW = 0.78f
private const val LANDED_PITCH = 0.72f
private const val LANDED_DIST = 34f

/** up-hint chip 判据距离（= [com.situ.aichat.ui.world.continent.ContinentCamera] 的 `UP_HINT_DIST`）。 */
private const val UP_HINT_DIST = 50f

/** JS 入场俯冲时长（契约 §4.2：dist 95→34 约 1.8s）——up-hint 与 GL 的 `introDone` 对位靠它（一期 D-7 同款折算）。 */
private const val INTRO_MS = 1_800L

/** 姿态轮询周期（= 桥心跳 2Hz·只驱动 up-hint chip，不进重组热路径）。 */
private const val POSE_POLL_MS = 500L

/** 揭幕闸超时（§3）：进某区这么久还没收到 web 首帧 → 视同场景失败，回落 GL 大陆。 */
private const val REVEAL_TIMEOUT_MS = 8_000L

private const val PAGE_URL = "file:///android_asset/world_web/continent.html"

/**
 * 网页大陆场景宿主（图纸「网页世界二期」§3/§J3/§J4）：`WebView` + 桥接线 + **原生选中态与站点卡**，
 * 与 GL 兜底整链 [com.situ.aichat.ui.world.ContinentSceneView]（零碰）平行实现同一玩法面。
 *
 * **分工（§J3）**：页面只画地形 / 站位标记 / 名签 / presence 徽记，点中什么经桥回原生；选中态、站点卡
 * （复用 [WorldSiteSheet] + [WorldSheetActionMatrix.cityButtons]）、进镇、旅行、扣款**全在原生**。
 * 页面 `onTownDive`（捏到底）照 GL 语义只在「有选中且非奇观」时进镇。
 *
 * **换区（§J9）**：`regionId` 变 → 重新取数 + 再 `init()` = 整场重建（契约设计如此·GL 版的 1s 天空过渡不复刻）。
 *
 * web 起不来 / 跑挂 / 8s 无首帧 → [onWebFailed] → 本会话内回落 GL 大陆（不入库不持久·§J1）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BoxScope.ContinentWebSceneView(
    regionId: String,
    reduceMotion: Boolean,
    staticMode: Boolean,
    interactive: Boolean, // 转场输入锁：非 None 转场期间不响应（经 setFlags 同步给 web）。
    continentOf: suspend (String) -> ContinentSceneData,
    onFirstFrame: () -> Unit,
    onReturnToPlanet: () -> Unit,
    onEnterTown: (String) -> Unit,
    onWebFailed: () -> Unit,
    onViewReady: (ContinentWebController) -> Unit,
    initialRestore: Pair<ContinentCamSnapshot, Float>? = null,
    userPresenceCityId: String? = null,
    userTraveling: Boolean = false,
    userHomeCityId: String? = null,
    onDepartToCity: (String) -> Unit = {},
    /** 测试注入口：只替换「把报文发出去」这一步（默认走真 WebView），行为字节不变（R1 🔴-1 返修要求的可注入函数口）。 */
    jsSink: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val host = remember { ContinentWebHost() }
    var pageReady by remember { mutableStateOf(false) }
    var inited by remember { mutableStateOf(false) }
    var loadedOnce by remember { mutableStateOf(false) }
    var firstFrameSeen by remember { mutableStateOf(false) }
    var sites by remember { mutableStateOf<List<ContinentSite>>(emptyList()) }
    var selected by remember { mutableStateOf<ContinentSite?>(null) }
    var upHint by remember { mutableStateOf(false) }
    // R1 🟡-2：**这次装载**有没有真用上恢复姿态（换区恒为 false → 页面会跑 1.8s 入场，必须等）。
    var restoredThisLoad by remember { mutableStateOf(false) }

    val presence = WorldWebPresence(userPresenceCityId, userTraveling, userHomeCityId)

    // 桥回调只碰 [host] 上每轮刷新的最新引用——`remember` 闭包直接捕获会永停首帧值（PITFALLS 1h）。
    SideEffect {
        // R1 🔴-1：三旗与 presence 也挂 host —— 生命周期观察者只建一次，闭包按值捕获会永停首次组合值。
        host.reduceMotion = reduceMotion
        host.staticMode = staticMode
        host.presence = presence
        host.sites = sites
        host.interactive = interactive
        host.initialRestore = initialRestore
        host.onFirstFrame = { firstFrameSeen = true; onFirstFrame() }
        host.onFailed = onWebFailed
        host.onReturn = onReturnToPlanet
        host.onSelect = { selected = it }
        // §J3：捏到底进镇照 GL 语义——有选中且非奇观才进（未选中 / 选中奇观忽略·页面侧已复位）。
        host.onTownDive = { selected?.takeIf { !it.isWonder }?.let { onEnterTown(it.id) } }
    }

    val bridge = remember {
        WorldWebBridge(
            onReadyCb = {},
            onFirstFrameCb = { host.onFirstFrame() },
            onPoseCb = { json -> host.pose = WorldWebData.continentPoseFrom(json) ?: host.pose },
            onErrorCb = { host.onFailed() },
            onTapSiteCb = { id ->
                if (host.interactive) {
                    host.sites.firstOrNull { it.id == id }?.let { site ->
                        host.onSelect(site)
                        host.focusSite(site.id) // 相机聚焦拉近（= GL 版 focusSite·契约 §3.1）
                    }
                }
            },
            onTapEmptyCb = { if (host.interactive) { host.onSelect(null); host.clearFocus() } },
            onReturnGestureCb = { host.onReturn() },
            onTownDiveCb = { if (host.interactive) host.onTownDive() },
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
        LaunchedEffect(Unit) { onWebFailed() } // E1：本会话回落 GL 大陆
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

    // 揭幕闸（§3·E3）：进某区 8s 内没见首帧 → 回落 GL 大陆。幕由转场编排保持不动，改挂的 GL 大陆出首帧时
    // 经同一个 onContinentFirstFrame 揭幕 → 全程不闪黑。
    LaunchedEffect(regionId) {
        firstFrameSeen = false
        delay(REVEAL_TIMEOUT_MS)
        if (!firstFrameSeen) onWebFailed()
    }

    // 装载序（§3 锁定）：init → restorePose → setPresence → setFlags。换区走同一条 = 整场重建（§J9）；
    // **恢复姿态只在首次装载用**（= GL 版 initialRestore 只进 GLView 构造），换区一律 null 走页面入场动画。
    LaunchedEffect(webView, pageReady, regionId) {
        if (!pageReady) return@LaunchedEffect
        inited = false
        selected = null
        host.pose = null // R1 🔵-1：换区先清姿态缓存，否则 up-hint 首拍读的是上一区的 dist
        val data = continentOf(regionId)
        sites = data.sites
        val first = !loadedOnce
        host.send("init(${jsArg(WorldWebData.continentJson(data, presence))})")
        val restore = initialRestore?.takeIf { first }
        restoredThisLoad = restore != null
        host.send(
            "restorePose(${
                if (restore == null) "null"
                else "JSON.parse(${jsArg(WorldWebData.continentPoseJson(restore.first, restore.second))})"
            })",
        )
        host.send(setPresenceCall(presence))
        host.send(setFlagsCall(reduceMotion, staticMode, interactive))
        loadedOnce = true
        inited = true
    }

    // 增量推送：presence（§J4·原生单源·页面只画徽记）/ 三旗。
    LaunchedEffect(inited, presence) {
        if (inited) host.send(setPresenceCall(presence))
    }
    LaunchedEffect(inited, reduceMotion, staticMode, interactive) {
        if (inited) host.send(setFlagsCall(reduceMotion, staticMode, interactive))
    }

    // up-hint 判据（= GL 版 `dist > 50 && introDone`·web 侧 introDone 折算成首帧后 intro 时长·一期 D-7 同款；
    // 有恢复姿态时页面不跑入场动画，故不等待）。
    LaunchedEffect(inited, reduceMotion, regionId) {
        if (!inited) { upHint = false; return@LaunchedEffect }
        delay(if (reduceMotion || restoredThisLoad) 0L else INTRO_MS)
        while (true) {
            upHint = (host.pose?.first?.dist ?: LANDED_DIST) > UP_HINT_DIST
            delay(POSE_POLL_MS)
        }
    }

    // 生命周期：熄屏/后台停帧，回前台补渲一帧 + 补 presence / 三旗（E7）。
    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    webView.onResume(); webView.resumeTimers()
                    // R1 🔴-1：推**回前台那一刻**的 presence 与三旗（读 host 活引用），不是首次组合冻结的那一份。
                    if (inited) {
                        host.send(setPresenceCall(host.presence))
                        host.send(setFlagsCall(host.reduceMotion, host.staticMode, host.interactive))
                    }
                }
                Lifecycle.Event.ON_PAUSE -> { webView.pauseTimers(); webView.onPause() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); webView.pauseTimers(); webView.onPause() }
    }

    // ── 以下为原生层（§J3：站点卡呈现与玩法回调完全不变·= GL 版 ContinentSceneView 同构搬装）──

    // 提示 chip（底部中央·大陆文案）·开站点卡时隐（否则从暖纸面下透出）。
    if (selected == null) {
        WorldGlassChip(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp)) {
            Text(
                stringResource(R.string.world_region_hint), color = WorldSceneColors.onGlass, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    // up-hint chip（顶部中央·dist>50 && intro 完成）。
    val upAlpha by animateFloatAsState(if (upHint) 1f else 0f, tween(300, easing = AppMotion.EaseInOut), label = "continentWebUpHint")
    if (upAlpha > 0f) {
        WorldGlassChip(modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp).alpha(upAlpha)) {
            Text(
                stringResource(R.string.world_zoom_out_hint), color = WorldSceneColors.onGlass, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    // 站点卡（城市：走进小镇 +（异地未在途）出发去这里/回家·奇观无按钮）。
    val enterTownLabel = stringResource(R.string.world_enter_town)
    val travelHereLabel = stringResource(R.string.world_travel_here)
    val travelHomeLabel = stringResource(R.string.world_travel_home)
    val sheetActions: List<Pair<String, () -> Unit>> = selected?.takeIf { !it.isWonder }?.let { site ->
        WorldSheetActionMatrix.cityButtons(userPresenceCityId, site.id, userTraveling, userHomeCityId).map { btn ->
            when (btn) {
                WorldSheetActionMatrix.CityBtn.ENTER_TOWN -> enterTownLabel to { onEnterTown(site.id) }
                // 开旅行单前先关城市卡（单一底部卡·否则旅行单打开时城市卡在底下透出）。
                WorldSheetActionMatrix.CityBtn.TRAVEL_HERE -> travelHereLabel to { selected = null; host.closeSheet(); onDepartToCity(site.id) }
                WorldSheetActionMatrix.CityBtn.TRAVEL_HOME -> travelHomeLabel to { selected = null; host.closeSheet(); onDepartToCity(site.id) }
            }
        }
    } ?: emptyList()
    WorldSiteSheet(
        site = selected,
        reduceMotion = reduceMotion,
        onClose = { selected = null; host.closeSheet() },
        modifier = Modifier.align(Alignment.BottomCenter),
        actions = sheetActions,
    )
}

/**
 * 桥与转场编排的共享落点：Compose 每轮经 `SideEffect` 刷新可变引用，桥闭包只读这里（防 Compose 捕获过期）。
 * 同时实现 [ContinentWebController] 供 [com.situ.aichat.ui.world.WorldTransitions] 驱动镜头。
 */
private class ContinentWebHost : ContinentWebController {
    /** 发往页面的唯一出口（Compose 每轮 `SideEffect` 刷新·测试可注入）。**主线程调**。 */
    var send: (String) -> Unit = {}
    var reduceMotion: Boolean = false
    var staticMode: Boolean = false
    var presence: WorldWebPresence = WorldWebPresence(null, false, null)
    var sites: List<ContinentSite> = emptyList()
    var interactive: Boolean = false
    var initialRestore: Pair<ContinentCamSnapshot, Float>? = null
    var onFirstFrame: () -> Unit = {}
    var onFailed: () -> Unit = {}
    var onReturn: () -> Unit = {}
    var onSelect: (ContinentSite?) -> Unit = {}
    var onTownDive: () -> Unit = {}

    /** 最新相机姿态 + 目标距（web 手势结束 + 2Hz 心跳推送·JS 线程写 / 主线程与转场协程读）。 */
    @Volatile
    var pose: Pair<ContinentCamSnapshot, Float>? = null

    fun focusSite(siteId: String) { send("focusSite(${jsArg(siteId)})") }

    fun clearFocus() { send("clearFocus()") }

    override fun closeSheet() { send("closeSheet()") }

    override fun playExit(ms: Int) {
        send("playPose(JSON.parse(${jsArg(WorldWebData.playPoseJson(pitch = 1.12f, dist = 95f))}), $ms)")
    }

    override fun playDiveToSite(siteId: String, ms: Int) {
        val site = sites.firstOrNull { it.id == siteId }
        val json = WorldWebData.playPoseJson(dist = 4.5f, tx = site?.x, tz = site?.z)
        send("playPose(JSON.parse(${jsArg(json)}), $ms)")
    }

    override fun poseSnapshot(): Pair<ContinentCamSnapshot, Float> = pose ?: initialRestore
        ?: (ContinentCamSnapshot(DEF_YAW, LANDED_PITCH, LANDED_DIST, 0f, WorldWebData.CONTINENT_TARGET_Y, 0f) to LANDED_DIST)
}

/** presence 调用串（§J4：原生单源算「用户在哪座城」，页面只画徽记）。只造串，发送统一走 host 单一出口。 */
private fun setPresenceCall(presence: WorldWebPresence): String =
    "setPresence(JSON.parse(${jsArg(WorldWebData.presenceJson(presence))}))"
