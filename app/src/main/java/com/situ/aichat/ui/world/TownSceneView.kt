package com.situ.aichat.ui.world

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.world.continent.ContinentSite
import com.situ.aichat.ui.world.continent.SiteProjection
import com.situ.aichat.ui.world.town.TownData
import com.situ.aichat.ui.world.town.TownGLView
import com.situ.aichat.ui.world.town.TownGeometry
import com.situ.aichat.ui.world.town.TownMath
import com.situ.aichat.ui.world.town.TownOverlayGeometry
import com.situ.aichat.ui.world.town.TownPlace
import com.situ.aichat.ui.world.town.TownSkyParams
import com.situ.aichat.ui.world.town.TownTextures
import com.situ.aichat.ui.world.interior.InteriorSceneData
import com.situ.aichat.world.stage.WorldTownCast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 小镇场景宿主（W9c 图纸 §2/§3.6/§4.4）：AndroidView([TownGLView]) + 生命周期 + 数据装载（Default 建几何）+
 * 投影循环（地点标签锚 top+0.6 / 金环 & pick 锚 top×0.5）+ tap pick（52dp 就近·空点清选中相机不复位）+ 选中态
 * hoist（站点卡·复用 [WorldSiteSheet]·无按钮）+ 萤火（仅精修城）+ hint/up-hint chip。[onViewReady] 把 GL 视图交
 * 给 [WorldScreen] 驱动回大陆转场。
 */
@Composable
internal fun BoxScope.TownSceneView(
    cityId: String,
    worldSeed: Long,
    reduceMotion: Boolean,
    staticMode: Boolean,
    interactive: Boolean, // 转场输入锁：非 None 转场期间 Compose 标记不响应（GL 触摸路另有 setInputLocked·E11 精神）。
    townOf: suspend (String) -> TownData,
    onGlError: () -> Unit,
    onFirstFrame: () -> Unit,
    onReturnToContinent: () -> Unit,
    onViewReady: (TownGLView) -> Unit,
    // W9d 加法：演员表 + 卡片交互回调（cast 空 = 无卡·9c 行为；回调由 WorldScreen 接）。
    cast: WorldTownCast? = null,
    userPresent: Boolean = false,
    userTraveling: Boolean = false,
    homePlaceId: String? = null,
    initialCamera: Pair<com.situ.aichat.ui.world.town.TownCamSnapshot, Float>? = null,
    onEnterInterior: (String) -> Unit = {},
    onTravelToCity: () -> Unit = {},
    onOpenChat: (String, String) -> Unit = { _, _ -> },
    onOpenPet: (String) -> Unit = {},
    onMeetNative: suspend (String) -> Boolean = { false },
    onDismissResident: (String, String) -> Unit = { _, _ -> }, // 战役 B（O6·§4.3）：自建未招募居民「送 TA 离开」→ 暖纸确认
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val density = LocalDensity.current
    var glView by remember { mutableStateOf<TownGLView?>(null) }
    var sceneSize by remember { mutableStateOf(IntSize.Zero) }
    var places by remember { mutableStateOf<List<TownPlace>>(emptyList()) }
    var fillers by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var cityName by remember { mutableStateOf("") }
    var curated by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<TownPlace?>(null) }
    var castSelectedId by remember { mutableStateOf<String?>(null) } // 🔴-2：存 id 非冻结对象·渲染时从当前 cast 解析最新卡
    var metNativeId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = LocalAppHaptics.current
    val castProj = remember { mutableStateOf<List<SiteProjection>>(emptyList()) }
    val overflowProj = remember { mutableStateOf<List<SiteProjection>>(emptyList()) }
    var upHint by remember { mutableStateOf(false) }
    val labelProj = remember { mutableStateOf<List<SiteProjection>>(emptyList()) }
    val ringProj = remember { mutableStateOf<List<SiteProjection>>(emptyList()) }
    val labelSizes = remember { mutableStateMapOf<String, IntSize>() }
    val pickRadiusPx = with(density) { 52.dp.toPx() }
    val ringHalfPx = with(density) { 22.dp.toPx() }
    val cullMarginXPx = with(density) { 60.dp.toPx() }
    val cullMarginYPx = with(density) { 40.dp.toPx() }

    // 演员卡摆位（§4.6A·纯函数·cast 空 = 无卡）。
    val placement = remember(cast, places, fillers, homePlaceId) {
        if (cast == null) CastPlacement(emptyList(), emptyList())
        else WorldCastAnchors.place(
            cast,
            places.associate { it.id to Triple(it.x, it.top, it.z) },
            fillers, homePlaceId, InteriorSceneData::hasInterior,
        )
    }

    fun select(place: TownPlace) {
        castSelectedId = null // 🟡-1：单一底部卡·开地点卡即清人物卡
        selected = place
        glView?.focusSelected() // tDist = min(tDist, 19)（demo:L270·不改 target）
    }

    fun onCastHit(kind: CastCardKind) {
        selected = null
        val n = (kind as? CastCardKind.Native)?.staged
        // 🔴-2：去掉 !discovered 条件——已认识原住民每天点卡也记一次偶遇燃料（服务侧幂等 + 同日去重兜底）。
        // 🟡-3：新发现（onMeetNative 返 true）触发 haptics light（§4.6C）。
        if (n != null && userPresent) {
            scope.launch { if (onMeetNative(n.nativeId)) { haptics.light(); metNativeId = n.nativeId } }
        }
        castSelectedId = kind.id()
    }

    fun pick(px: Float, py: Float) {
        if (!interactive) return
        val v = glView ?: return
        val w = sceneSize.width.toFloat(); val h = sceneSize.height.toFloat()
        if (w == 0f || h == 0f) return
        val snap = v.cameraSnapshot()
        val mvp = TownMath.townMvp(snap.yaw, snap.pitch, snap.dist, snap.tx, snap.ty, snap.tz, w / h)
        var bestPlace: TownPlace? = null
        var bestCast: CastCardKind? = null
        var bestD = pickRadiusPx
        for (p in places) {
            val proj = TownMath.projectPlace(mvp, p.x, p.top * 0.5f, p.z, w, h)
            if (!proj.visible) continue
            val d = hypot(proj.x - px, proj.y - py)
            if (d < bestD) { bestD = d; bestPlace = p; bestCast = null }
        }
        for (c in placement.cards) {
            val proj = TownMath.projectPlace(mvp, c.x, c.y, c.z, w, h)
            if (!proj.visible) continue
            val d = hypot(proj.x - px, proj.y - py)
            if (d < bestD) { bestD = d; bestCast = c.kind; bestPlace = null }
        }
        when {
            bestCast != null -> onCastHit(bestCast)
            bestPlace != null -> select(bestPlace) // select() 内已清 castSelectedId（🟡-1）
            else -> { selected = null; castSelectedId = null } // 空点清选中·相机不复位
        }
    }

    AndroidView(
        factory = { ctx ->
            TownGLView(
                context = ctx, worldSeed = worldSeed, reduceMotion = reduceMotion,
                onGlError = onGlError, onFirstFrame = onFirstFrame,
                onTap = { x, y -> pick(x, y) }, onReturnGesture = onReturnToContinent,
                initialSnapshot = initialCamera,
            ).also { glView = it; onViewReady(it) }
        },
        modifier = Modifier.fillMaxSize().onSizeChanged { sceneSize = it },
        update = { it.setRenderFlags(reduceMotion, staticMode) },
    )

    // ── 画层天空已入 GL（R2 修订·用户 2026-08-28 观感打回幕布方案）：水彩天空由渲染器画在一切几何**之前**、
    // 经深度被屋顶/山影自然遮挡——「房子抠出来显示在天空前面」的正确图层序；Compose 侧不再叠画层与幕布，
    // 只在下方装载效应里做 IO 解码注入。时段选择与 2.5s 渐显由渲染器逐帧驱动（reduceMotion 冻结即直切）。──

    DisposableEffect(lifecycleOwner, glView) {
        val v = glView
        if (v != null && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) v.resumeWorld()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> v?.resumeWorld()
                Lifecycle.Event.ON_PAUSE -> v?.pauseWorld()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); v?.pauseWorld() }
    }

    // 数据装载（单座小镇·一次性·Default 建几何）。
    LaunchedEffect(glView, cityId) {
        val v = glView ?: return@LaunchedEffect
        val data = townOf(cityId)
        val geom = withContext(Dispatchers.Default) { TownGeometry.buildTown(data.layout) }
        val overlay = withContext(Dispatchers.Default) { TownOverlayGeometry.build(data.layout) }
        v.submitTown(geom, overlay, TownSkyParams.of(data.sky, data.glowA))
        places = data.places
        fillers = data.layout.fillers.map { it.cx.toFloat() to it.cz.toFloat() }
        cityName = data.cityName
        curated = data.curated
        selected = null
    }

    // 材质贴图 + 画层天空装载（§3.5/R2·IO 线程解码 → 交 GL 线程懒上传）：缺图/解码失败自动回落程序化路径。
    LaunchedEffect(glView) {
        val v = glView ?: return@LaunchedEffect
        v.submitDetailTextures(withContext(Dispatchers.IO) { TownTextures.decodeAll(context) })
        val skies = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.world_town_sky_dusk) }.getOrNull() to
                runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.world_town_sky_night) }.getOrNull()
        }
        v.submitPaintedSkies(skies.first, skies.second)
    }

    // 投影循环（每帧读快照复算 mvp·offset 在布局阶段读 → 不触发重组）。placement 变化（cast 刷新）重启循环。
    LaunchedEffect(glView, sceneSize, placement) {
        val v = glView ?: return@LaunchedEffect
        if (sceneSize.width == 0 || sceneSize.height == 0) return@LaunchedEffect
        val w = sceneSize.width.toFloat(); val h = sceneSize.height.toFloat()
        while (true) {
            withFrameNanos { }
            val snap = v.cameraSnapshot()
            val mvp = TownMath.townMvp(snap.yaw, snap.pitch, snap.dist, snap.tx, snap.ty, snap.tz, w / h)
            labelProj.value = places.map { TownMath.projectPlace(mvp, it.x, it.top + 0.6f, it.z, w, h) }
            ringProj.value = places.map { TownMath.projectPlace(mvp, it.x, it.top * 0.5f, it.z, w, h) }
            castProj.value = placement.cards.map { TownMath.projectPlace(mvp, it.x, it.y, it.z, w, h) }
            overflowProj.value = placement.overflows.map { TownMath.projectPlace(mvp, it.x, it.y, it.z, w, h) }
            upHint = v.wantsUpHint()
        }
    }

    // 地点标签（投影经 offset/alpha 在布局/绘制阶段读）。
    places.forEachIndexed { i, place ->
        TownPlaceLabel(
            name = place.name,
            onClick = { if (interactive) select(place) },
            modifier = Modifier
                .onSizeChanged { labelSizes[place.id] = it }
                .offset {
                    val p = labelProj.value.getOrNull(i)
                    val size = labelSizes[place.id] ?: IntSize.Zero
                    if (p == null || !p.visible) IntOffset(-9999, -9999)
                    else IntOffset((p.x - size.width / 2f).roundToInt(), (p.y - size.height).roundToInt())
                }
                .alpha(labelAlpha(labelProj.value.getOrNull(i), sceneSize, cullMarginXPx, cullMarginYPx)),
        )
    }

    // 选中金环（锚 top×0.5·中心对齐）。
    val sel = selected
    if (sel != null) {
        val idx = places.indexOf(sel)
        TownSelectedRing(
            reduceMotion = reduceMotion,
            modifier = Modifier
                .offset {
                    val p = ringProj.value.getOrNull(idx)
                    if (p == null || !p.visible) IntOffset(-9999, -9999)
                    else IntOffset((p.x - ringHalfPx).roundToInt(), (p.y - ringHalfPx).roundToInt())
                },
        )
    }

    // 萤火（仅精修城·reduce/static 隐）。
    if (curated && !reduceMotion && !staticMode) TownFireflies()

    // 提示 chip（底部中央·精修/程序文案·开卡时隐）。
    if (selected == null) {
        WorldGlassChip(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp),
        ) {
            Text(
                stringResource(if (curated) R.string.world_town_hint else R.string.world_town_hint_generated),
                color = WorldSceneColors.onGlass,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    // up-hint chip（顶部中央·dist>34 && intro 完成）。
    val upAlpha by animateFloatAsState(if (upHint) 1f else 0f, tween(300, easing = AppMotion.EaseInOut), label = "townUpHint")
    if (upAlpha > 0f) {
        WorldGlassChip(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp).alpha(upAlpha),
        ) {
            Text(
                stringResource(R.string.world_town_zoom_out_hint),
                color = WorldSceneColors.onGlass,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    // 演员卡（§4.6A·投影 offset 定位·底部中心锚）。
    placement.cards.forEachIndexed { i, card ->
        CastCardAt(castProj, i) {
            TownCastCard(kind = card.kind, phaseIndex = i, onClick = { if (interactive) onCastHit(card.kind) })
        }
    }
    placement.overflows.forEachIndexed { i, of ->
        TownOverflowCard(
            of.count,
            modifier = Modifier.offset {
                val p = overflowProj.value.getOrNull(i)
                if (p == null || !p.visible) IntOffset(-9999, -9999) else IntOffset(p.x.roundToInt(), p.y.roundToInt())
            },
        )
    }

    // 🟡-1：开旅行单前先关本镇地点卡/人物卡（单一底部卡·否则旅行单打开时残卡在底下透出）。
    val travelToCity = { selected = null; castSelectedId = null; onTravelToCity() }

    // 地点站点卡（+走进去/出发来 actions·§4.7·环境地点/在途无按钮·在途正文追加提示行·🟡-5）。
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

    // 人物站点卡（§4.6C·🔴-2：按 id 从当前 placement 解析最新卡→发现后神秘卡自动变名卡·cast 里已无该 id→关卡）。
    val castSelection = castSelectedId?.let { id -> placement.cards.firstOrNull { it.kind.id() == id }?.kind }
    TownCastSheet(
        selection = castSelection, cityName = cityName, userPresent = userPresent, reduceMotion = reduceMotion, metNativeId = metNativeId,
        onClose = { castSelectedId = null; metNativeId = null }, onOpenChat = onOpenChat, onOpenPet = onOpenPet, onTravelToCity = travelToCity,
        onDismissResident = { nid, name -> castSelectedId = null; metNativeId = null; onDismissResident(nid, name) }, // 收卡再开确认弹窗
    )
}

/** 演员卡定位包装（投影 index → 底部中心锚·不可见移出屏）。 */
@Composable
private fun BoxScope.CastCardAt(proj: androidx.compose.runtime.State<List<SiteProjection>>, index: Int, content: @Composable () -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier.onSizeChanged { size = it }.offset {
            val p = proj.value.getOrNull(index)
            if (p == null || !p.visible) IntOffset(-9999, -9999)
            else IntOffset((p.x - size.width / 2f).roundToInt(), (p.y - size.height).roundToInt())
        },
    ) { content() }
}

private fun labelAlpha(p: SiteProjection?, size: IntSize, marginXPx: Float, marginYPx: Float): Float {
    if (p == null || !p.visible || size.width == 0) return 0f
    // 屏外剔除 [−60, w+60]×[−40, h+40] dp 域外（town demo:L299·边距按 dp→px 换算·§4.4）。
    val w = size.width.toFloat(); val h = size.height.toFloat()
    return if (p.x > -marginXPx && p.x < w + marginXPx && p.y > -marginYPx && p.y < h + marginYPx) 1f else 0f
}
