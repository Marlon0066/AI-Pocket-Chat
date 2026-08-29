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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.world.CastCardKind
import com.situ.aichat.ui.world.CastPlacement
import com.situ.aichat.ui.world.TownCastSheet
import com.situ.aichat.ui.world.WorldCastAnchors
import com.situ.aichat.ui.world.WorldGlassChip
import com.situ.aichat.ui.world.WorldSceneColors
import com.situ.aichat.ui.world.WorldSheetActionMatrix
import com.situ.aichat.ui.world.WorldSiteSheet
import com.situ.aichat.ui.world.continent.ContinentSite
import com.situ.aichat.ui.world.id
import com.situ.aichat.ui.world.interior.InteriorSceneData
import com.situ.aichat.ui.world.town.TownAmbience
import com.situ.aichat.ui.world.town.TownCamSnapshot
import com.situ.aichat.ui.world.town.TownData
import com.situ.aichat.ui.world.town.TownPlace
import com.situ.aichat.world.stage.WorldTownCast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 网页小镇镜头控制口（[com.situ.aichat.ui.world.WorldTransitions] 消费 = GL 版 `setCinematicPose` 的 web 对位）。 */
internal interface TownWebController {
    /** 拉升离场（JS：pitch→1.15 / dist→38·EaseInOut·§3.4B）。 */
    fun playExit(ms: Int)

    /** 俯冲进某地点（JS：target 滑向该点 + dist→13·§3.4B）。地点不在表里 → 停在当前 target 只收距（= GL 版 pitch/target 不动）。 */
    fun playDiveToPlace(placeId: String, ms: Int)

    /** 当前相机姿态（桥心跳缓存·最多 0.5s 旧·J6 已接受）。未收到过 → 恢复值 / 着陆姿态。 */
    fun poseSnapshot(): TownCamSnapshot
}

// ── 相机常数镜像（§4 = TownCamera R3 定版·§9：只镜像，绝不改 Kotlin 源）──
private const val INITIAL_YAW = 0.7f
private const val LANDED_PITCH = 0.36f
private const val LANDED_DIST = 30f
private const val UP_HINT_DIST = 34f
private const val TARGET_X = -1.5f
private const val TARGET_Z = -1.0f

/** JS intro 俯冲时长（§3.4A·38→30 900ms）——up-hint 与 GL 的 `introDone` 对位靠它。 */
private const val INTRO_MS = 900L

/** 氛围推送周期（J4：60s 定时 + resume + 进场·web 不自算时刻表）。 */
private const val AMBIENCE_PERIOD_MS = 60_000L

/**
 * 揭幕闸超时（§3.4C）：进镇后这么久还没收到 web 首帧 → 视同场景失败回落 GL 小镇。GL 无此风险、web 有
 * （页面/脚本/WebGL 任一环节可能悄无声息地不出帧）。
 */
private const val REVEAL_TIMEOUT_MS = 8_000L

/** 姿态轮询周期（= 桥心跳 2Hz·只驱动 up-hint chip，不进重组热路径）。 */
private const val POSE_POLL_MS = 500L

/** 页面挂的桥命名空间（town.js 读 `window.townWeb`·一期契约 §2.1 逐字锁死）。 */
private const val JS_NAMESPACE = "townWeb"

private const val PAGE_URL = "file:///android_asset/world_web/index.html"

/**
 * 网页小镇场景宿主（图纸「网页世界一期」§2.1/§3.1/§3.4）：`WebView` + 桥接线 + 原生 sheet 复用。
 *
 * 与 [com.situ.aichat.ui.world.TownSceneView]（GL 兜底整链·零碰）平行实现同一回调面——**变的只有 3D 画面
 * 与场内标签/头像卡这一层**（改由 web 的 HTML 层画）：点地点 / 点居民 / 走进去 / 旅行 / 去聊天 / 送离 / 初遇
 * 全部经桥回原生，sheet、导航、扣款恒原生（§2.3）。web 起不来或跑挂 → [onWebFailed] → 本会话内回落 GL
 * 小镇（不入库不持久·J1）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BoxScope.TownWebSceneView(
    cityId: String,
    reduceMotion: Boolean,
    staticMode: Boolean,
    interactive: Boolean, // 转场输入锁：非 None 转场期间不响应（经 setFlags 同步给 web·§3.3）。
    townOf: suspend (String) -> TownData,
    onFirstFrame: () -> Unit,
    onReturnToContinent: () -> Unit,
    onWebFailed: () -> Unit,
    onViewReady: (TownWebController) -> Unit,
    cast: WorldTownCast? = null,
    userPresent: Boolean = false,
    userTraveling: Boolean = false,
    homePlaceId: String? = null,
    initialCamera: Pair<TownCamSnapshot, Float>? = null,
    onEnterInterior: (String) -> Unit = {},
    onTravelToCity: () -> Unit = {},
    onOpenChat: (String, String) -> Unit = { _, _ -> },
    onOpenPet: (String) -> Unit = {},
    onMeetNative: suspend (String) -> Boolean = { false },
    onDismissResident: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val haptics = LocalAppHaptics.current

    val host = remember { TownWebHost() }
    var townData by remember { mutableStateOf<TownData?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var inited by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<TownPlace?>(null) }
    var castSelectedId by remember { mutableStateOf<String?>(null) }
    var metNativeId by remember { mutableStateOf<String?>(null) }
    var upHint by remember { mutableStateOf(false) }
    var firstFrameSeen by remember { mutableStateOf(false) }

    val places = townData?.places.orEmpty()
    val curated = townData?.curated ?: false
    val cityName = townData?.cityName.orEmpty()
    val fillers = remember(townData) { townData?.layout?.fillers?.map { it.cx.toFloat() to it.cz.toFloat() }.orEmpty() }

    // 演员卡摆位（纯函数单源 [WorldCastAnchors]·web 只拿坐标画卡·J3：规则不在两侧各写一遍）。
    val placement = remember(cast, places, fillers, homePlaceId) {
        if (cast == null) CastPlacement(emptyList(), emptyList())
        else WorldCastAnchors.place(
            cast,
            places.associate { it.id to Triple(it.x, it.top, it.z) },
            fillers, homePlaceId, InteriorSceneData::hasInterior,
        )
    }

    fun onCastHit(kind: CastCardKind) {
        selected = null
        val n = (kind as? CastCardKind.Native)?.staged
        // 已认识原住民每天点卡也记一次偶遇燃料（服务侧幂等）；新发现触发 haptics light（= GL 版 §4.6C 语义）。
        if (n != null && userPresent) {
            scope.launch { if (onMeetNative(n.nativeId)) { haptics.light(); metNativeId = n.nativeId } }
        }
        castSelectedId = kind.id()
    }

    // 桥回调只碰 [host] 上每轮刷新的最新引用——`remember` 闭包直接捕获会永停首帧值（PITFALLS 1h）。
    SideEffect {
        // R1 🔴-1（本报告显式授权的一期同形修复）：三旗挂 host —— 生命周期观察者只建一次，
        // 闭包按值捕获参数会永停首次组合值，回前台补推就会把陈旧的「不可交互」推回页面。
        host.reduceMotion = reduceMotion
        host.staticMode = staticMode
        host.places = places
        host.placement = placement
        host.interactive = interactive
        host.initialPose = initialCamera?.first
        host.onFirstFrame = { firstFrameSeen = true; onFirstFrame() }
        host.onReturn = onReturnToContinent
        host.onFailed = onWebFailed
        host.onPlaceTap = { p -> castSelectedId = null; selected = p }
        host.onCastTap = { kind -> onCastHit(kind) }
    }

    val bridge = remember {
        TownWebBridge(
            onReadyCb = {},
            onFirstFrameCb = { host.onFirstFrame() },
            onTapPlaceCb = { id -> if (host.interactive) host.onPlaceTap(host.places.firstOrNull { it.id == id }) },
            onTapCastCb = { id ->
                if (host.interactive) host.placement.cards.firstOrNull { it.kind.id() == id }?.let { host.onCastTap(it.kind) }
            },
            onReturnGestureCb = { host.onReturn() },
            onErrorCb = { host.onFailed() },
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
                settings.blockNetworkLoads = true // §9 负向禁令：web 端零网络请求（平台级闸，不靠自觉）
                setBackgroundColor(WorldSceneColors.background.toArgb()) // 首帧前防闪白
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addJavascriptInterface(bridge, TownWebBridge.NAME)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { pageReady = true }
                    override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                        if (req.isForMainFrame) host.onFailed()
                    }

                    /** E11（二期 R1 🟡-1 补）：渲染进程被系统回收 / 崩溃 —— 不接管则框架默认连宿主 App 一起杀。 */
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        host.onFailed() // 走既有单一回落口·detail 内容一律丢弃不落日志
                        return true
                    }
                }
            }
        }.getOrNull()
    }

    if (webView == null) {
        LaunchedEffect(Unit) { onWebFailed() } // E1：本会话回落 GL 小镇
        return
    }
    LaunchedEffect(webView) { host.view = webView; host.bridge = bridge; onViewReady(host); webView.loadUrl(PAGE_URL) }

    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxSize(),
        onRelease = {
            bridge.release()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        },
    )

    // 装载：小镇数据（Default 线程·同 GL 版走 VM 缓存）。
    LaunchedEffect(cityId) { inited = false; selected = null; townData = townOf(cityId) }

    // 揭幕闸（§3.4C）：进镇 8s 内没见首帧 → 回落 GL 小镇。幕由转场编排保持不动（phase 仍 ToTown），
    // 改挂的 GL 小镇出首帧时经同一个 onTownFirstFrame 揭幕 → 全程不闪黑。
    LaunchedEffect(cityId) {
        firstFrameSeen = false
        delay(REVEAL_TIMEOUT_MS)
        if (!firstFrameSeen) onWebFailed()
    }

    // 装载序（§3.1 锁定）：init → restorePose → setCast → setAmbience → setFlags。
    LaunchedEffect(webView, pageReady, townData) {
        val data = townData ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        webView.callJs("init(${jsArg(TownWebData.townJson(data))})")
        val pose = initialCamera?.first
        webView.callJs("restorePose(${if (pose == null) "null" else "JSON.parse(${jsArg(TownWebData.poseJson(pose))})"})")
        val avatars = withContext(Dispatchers.IO) { host.avatarsFor(host.placement) }
        webView.pushCast(host.placement, avatars)
        webView.pushAmbience(reduceMotion)
        webView.callJs("setFlags(JSON.parse(${jsArg(TownWebData.flagsJson(reduceMotion, staticMode, interactive))}))")
        inited = true
    }

    // 增量推送：演员表（cast 刷新）/ 三旗 / 氛围（60s 定时·J4）。
    LaunchedEffect(inited, placement) {
        if (inited) webView.pushCast(placement, withContext(Dispatchers.IO) { host.avatarsFor(placement) })
    }
    // W-5（契约 v1.2·一期 D-3 清账）：选中地点 → 页面收距 min(当前,19)+金环；收卡/空点 → 只熄环（相机不复位=GL 同义）。
    LaunchedEffect(inited, selected) {
        if (!inited) return@LaunchedEffect
        val p = selected
        if (p != null) webView.callJs("focusPlace(${jsArg(p.id)})") else webView.callJs("clearPlaceFocus()")
    }
    LaunchedEffect(inited, reduceMotion, staticMode, interactive) {
        if (inited) webView.callJs("setFlags(JSON.parse(${jsArg(TownWebData.flagsJson(reduceMotion, staticMode, interactive))}))")
    }
    LaunchedEffect(inited, reduceMotion) {
        if (!inited) return@LaunchedEffect
        while (true) { delay(AMBIENCE_PERIOD_MS); webView.pushAmbience(reduceMotion) }
    }

    // up-hint 判据（= GL 版 `dist > 34 && introDone`·web 侧 introDone 折算成首帧后 intro 时长·§11 D-7）。
    LaunchedEffect(inited, reduceMotion) {
        if (!inited) { upHint = false; return@LaunchedEffect }
        delay(if (reduceMotion) 0L else INTRO_MS)
        while (true) {
            upHint = (bridge.pose?.dist ?: LANDED_DIST) > UP_HINT_DIST
            delay(POSE_POLL_MS)
        }
    }

    // 生命周期：熄屏/后台停帧，回前台补渲一帧 + 补一次氛围（E7）。
    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    webView.onResume(); webView.resumeTimers()
                    // R1 🔴-1：推**回前台那一刻**的三旗与氛围（读 host 活引用），不是首次组合冻结的那一份。
                    if (inited) {
                        webView.pushAmbience(host.reduceMotion)
                        webView.callJs("setFlags(JSON.parse(${jsArg(TownWebData.flagsJson(host.reduceMotion, host.staticMode, host.interactive))}))")
                    }
                }
                Lifecycle.Event.ON_PAUSE -> { webView.pauseTimers(); webView.onPause() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); webView.pauseTimers(); webView.onPause() }
    }

    // ── 以下为原生层（§2.3.1：sheet 呈现与玩法回调完全不变）──

    if (selected == null) {
        WorldGlassChip(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp)) {
            Text(
                stringResource(if (curated) R.string.world_town_hint else R.string.world_town_hint_generated),
                color = WorldSceneColors.onGlass, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    val upAlpha by animateFloatAsState(if (upHint) 1f else 0f, tween(300, easing = AppMotion.EaseInOut), label = "townWebUpHint")
    if (upAlpha > 0f) {
        WorldGlassChip(modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp).alpha(upAlpha)) {
            Text(
                stringResource(R.string.world_town_zoom_out_hint), color = WorldSceneColors.onGlass, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    // 开旅行单前先关地点卡/人物卡（单一底部卡）。
    val travelToCity = { selected = null; castSelectedId = null; onTravelToCity() }

    val enterLabel = stringResource(R.string.world_enter_place)
    val comeHereLabel = stringResource(R.string.world_travel_come_here)
    val inTransitHint = stringResource(R.string.world_travel_in_transit_body)
    val sheetSite = selected?.let {
        val body = WorldSheetActionMatrix.placeBody(it.body, InteriorSceneData.hasInterior(it.id), userTraveling, inTransitHint)
        ContinentSite(it.id, it.name, isWonder = false, isHome = false, curated = true, x = it.x, z = it.z, markerTop = it.top, buildingCount = 0, body = body)
    }
    val placeActions: List<Pair<String, () -> Unit>> = selected?.let { p ->
        WorldSheetActionMatrix.placeButtons(InteriorSceneData.hasInterior(p.id), userPresent, userTraveling).map { btn ->
            when (btn) {
                WorldSheetActionMatrix.PlaceBtn.ENTER -> enterLabel to { onEnterInterior(p.id) }
                WorldSheetActionMatrix.PlaceBtn.COME_HERE -> comeHereLabel to travelToCity
            }
        }
    } ?: emptyList()
    WorldSiteSheet(sheetSite, reduceMotion, onClose = { selected = null }, Modifier.align(Alignment.BottomCenter), placeActions)

    val castSelection = castSelectedId?.let { id -> placement.cards.firstOrNull { it.kind.id() == id }?.kind }
    TownCastSheet(
        selection = castSelection, cityName = cityName, userPresent = userPresent, reduceMotion = reduceMotion, metNativeId = metNativeId,
        onClose = { castSelectedId = null; metNativeId = null }, onOpenChat = onOpenChat, onOpenPet = onOpenPet, onTravelToCity = travelToCity,
        onDismissResident = { nid, name -> castSelectedId = null; metNativeId = null; onDismissResident(nid, name) },
    )
}

/**
 * 桥与转场编排的共享落点：Compose 每轮经 `SideEffect` 刷新可变引用，桥闭包只读这里（防 Compose 捕获过期）。
 * 同时实现 [TownWebController] 供 [com.situ.aichat.ui.world.WorldTransitions] 驱动镜头。
 */
private class TownWebHost : TownWebController {
    var view: WebView? = null
    var bridge: TownWebBridge? = null
    var reduceMotion: Boolean = false
    var staticMode: Boolean = false
    var places: List<TownPlace> = emptyList()
    var placement: CastPlacement = CastPlacement(emptyList(), emptyList())
    var interactive: Boolean = false
    var initialPose: TownCamSnapshot? = null
    var onFirstFrame: () -> Unit = {}
    var onReturn: () -> Unit = {}
    var onFailed: () -> Unit = {}
    var onPlaceTap: (TownPlace?) -> Unit = {}
    var onCastTap: (CastCardKind) -> Unit = {}

    /** 头像 base64 表（**IO 线程调**·卡 id → base64|null）。 */
    fun avatarsFor(p: CastPlacement): Map<String, String?> = p.cards.associate { c ->
        c.kind.id() to (c.kind as? CastCardKind.Character)?.staged?.avatarPath?.let { TownWebData.avatarBase64(it) }
    }

    override fun playExit(ms: Int) { view?.callJs("playExit($ms)") }

    override fun playDiveToPlace(placeId: String, ms: Int) {
        val p = places.firstOrNull { it.id == placeId }
        val pose = poseSnapshot()
        view?.callJs("playDiveTo(${p?.x ?: pose.tx}, ${p?.z ?: pose.tz}, $ms)")
    }

    override fun poseSnapshot(): TownCamSnapshot = bridge?.pose ?: initialPose
        ?: TownCamSnapshot(INITIAL_YAW, LANDED_PITCH, LANDED_DIST, TARGET_X, TownWebData.TARGET_Y, TARGET_Z)
}

/** 调 `window.townWeb.<call>`（脚本未就绪时静默 no-op）。**主线程调**·实现见 [callJs]（三视图共用）。 */
private fun WebView.callJs(call: String) = callJs(JS_NAMESPACE, call)

private fun WebView.pushCast(placement: CastPlacement, avatars: Map<String, String?>) {
    callJs("setCast(JSON.parse(${jsArg(TownWebData.castJson(placement, avatars))}))")
}

private fun WebView.pushAmbience(reduceMotion: Boolean) {
    callJs("setAmbience(JSON.parse(${jsArg(TownWebData.ambienceJson(TownAmbience.current(reduceMotion = reduceMotion)))}))")
}
