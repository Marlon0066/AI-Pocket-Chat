package com.situ.aichat.ui.world

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.diagnostics.perf.FrameSceneObserver
import com.situ.aichat.diagnostics.perf.PerfScenes
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.world.eggnest.EggNestSheet
import com.situ.aichat.ui.world.eggnest.EggNestViewModel
import com.situ.aichat.ui.world.onboarding.WorldOnboardingOverlay
import com.situ.aichat.ui.world.quickchat.WorldQuickChatSheet
import com.situ.aichat.ui.world.planet.MarkerProjection
import com.situ.aichat.ui.world.planet.PlanetGLView
import com.situ.aichat.ui.world.planet.PlanetMath
import com.situ.aichat.ui.world.web.ContinentWebSceneView
import com.situ.aichat.ui.world.web.PlanetWebSceneView
import com.situ.aichat.ui.world.web.TownWebSceneView
import kotlin.math.roundToInt

/**
 * 世界屏（W9a §4.2 + W9b §3.6 + W9c §3.5）：Planet / Continent / Town 三场景切换 + 星↔陆↔镇三级双向俯冲转场
 * （同色幕 #0D1220·编排下沉 [WorldTransitions]）+ chrome 分场景 + 三级返回链。世界屏恒暗（[WorldSceneColors]），
 * 不随 App 明暗主题。
 */
@Composable
fun WorldScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit = {},
    onOpenPet: (String) -> Unit = {},
    onOpenPetAdoption: (String) -> Unit = {}, // W12.5：蛋巢「迎接小家伙」→ 现有领养三步流（petAdoption/{uuid}）
    viewModel: WorldViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // 性能采集·尺 3（卷 0）：三个 GL 场景各算一个被观测场景（GL-1 星球屏静置 / M11 buildRegion）；
    // 室内与星图不在锁定的 6 个场景名里 → 传 null = 本次不观测。采集关时零成本。
    FrameSceneObserver(
        when (ui.scene) {
            WorldScene.Planet -> PerfScenes.WORLD_PLANET
            is WorldScene.Continent -> PerfScenes.WORLD_CONTINENT
            is WorldScene.Town -> PerfScenes.WORLD_TOWN
            else -> null
        },
    )
    val reduceMotion = rememberReduceMotion()
    val haptics = LocalAppHaptics.current
    val scope = rememberCoroutineScope()
    val transitions = remember { WorldTransitions(scope, viewModel) }

    // W12 C6：快聊弹窗 VM（独立 hiltViewModel·宿主持有 + 接线）。
    val qcVm: com.situ.aichat.ui.world.quickchat.WorldQuickChatViewModel = hiltViewModel()
    val qcState by qcVm.ui.collectAsStateWithLifecycle()

    // W12.5 家的蛋巢 VM（决策 42·宿主持有）：巢态喂室内叠层/站点卡·候选喂「孵蛋之约」sheet·定约经 setPact。
    val nestVm: EggNestViewModel = hiltViewModel()
    val nestState by nestVm.state.collectAsStateWithLifecycle()
    val nestCandidates by nestVm.candidates.collectAsStateWithLifecycle()
    var nestPactOpen by remember { mutableStateOf(false) }
    LaunchedEffect(ui.scene) { if (ui.scene !is WorldScene.Interior) nestPactOpen = false } // 离室内即收「孵蛋之约」

    // 网页小镇（一期绞杀第一刀·J1）：默认 web 渲染；web 起不来/跑挂/8s 无首帧 → 置位后本会话回落 GL 小镇整链
    // （瞬时态·不入库不持久·下次进世界屏重新试 web）。
    var webTownFailed by remember { mutableStateOf(false) }

    // 网页星球 / 网页大陆（二期绞杀第二刀·J1）：同上，两态各自独立——失败只降本场景，另一场景照跑 web。
    var webPlanetFailed by remember { mutableStateOf(false) }
    var webContinentFailed by remember { mutableStateOf(false) }

    // W15 顶栏方案 A：大区切换器展开态 hoist（场景/大区变更即收起·进程死亡即收起可接受）。
    var switcherExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(ui.scene) { switcherExpanded = false }

    // W9d：演员表 / 在途 / 位置 / 旅行单 状态 + 去聊天导航。
    val cast by viewModel.cast.collectAsStateWithLifecycle()
    val travelChip by viewModel.travelChip.collectAsStateWithLifecycle()
    val presence by viewModel.presence.collectAsStateWithLifecycle()
    val travelQuote by viewModel.travelQuote.collectAsStateWithLifecycle()
    val travelResult by viewModel.travelResult.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.chatNav.collect { onOpenChat(it) } }
    // W12 C7·E6：初遇未确认关闭 → 早退 toast（宿主格式化 name）。
    val toastCtx = LocalContext.current
    LaunchedEffect(Unit) {
        qcVm.leaveToast.collect { name ->
            android.widget.Toast.makeText(toastCtx, toastCtx.getString(R.string.world_meet_leave_toast, name), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    // 战役 B（O6·§4.3）：送离 toast「{name} 离开了」。
    LaunchedEffect(Unit) {
        viewModel.dismissedToast.collect { name ->
            android.widget.Toast.makeText(toastCtx, toastCtx.getString(R.string.world_resident_dismissed_toast, name), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val pendingDismiss by viewModel.pendingDismiss.collectAsStateWithLifecycle()
    LaunchedEffect(ui.scene) {
        when (val s = ui.scene) {
            is WorldScene.Town -> { viewModel.startCastRefresh(s.cityId); viewModel.tryLightUpLore(s.cityId) } // W12 C4：进小镇试首访点亮
            is WorldScene.Interior -> viewModel.startCastRefresh(s.cityId)
            else -> viewModel.stopCastRefresh()
        }
    }

    // 状态栏/导航栏浅色图标（世界屏恒暗·同 9a e936c41b 机制）：进屏强制浅、退屏恢复原样。
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevStatus = controller?.isAppearanceLightStatusBars
        val prevNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (controller != null) {
                if (prevStatus != null) controller.isAppearanceLightStatusBars = prevStatus
                if (prevNav != null) controller.isAppearanceLightNavigationBars = prevNav
            }
        }
    }

    val coverAlpha by animateFloatAsState(
        targetValue = if (ui.ready && !transitions.glFailed) 0f else 1f,
        animationSpec = AppMotion.smoothSpring(),
        label = "worldReady",
    )

    // 三级返回链（BackHandler + 左上返回钮同语义）：Town→Continent→Planet→退出世界屏；转场中忽略（glFailed 旁路除外·E11）。
    fun handleBack() {
        if (transitions.glFailed) { onBack(); return } // 兜底屏：转场态可能卡住，返回必须仍能退出（E11）。
        if (qcState.target != null) { qcVm.close(); return } // W12 C6：快聊开着 → 返回先关弹窗
        if (nestPactOpen) { nestPactOpen = false; return } // W12.5：「孵蛋之约」开着 → 返回先关
        if (switcherExpanded) { switcherExpanded = false; return } // W15：切换器展开 → 返回先收列表
        if (transitions.phase != WorldTransition.None) return
        when (ui.scene) {
            is WorldScene.Interior -> transitions.startReturnToTown(reduceMotion)
            is WorldScene.Town -> transitions.startReturnToContinent(reduceMotion)
            is WorldScene.Continent -> transitions.startReturnToPlanet(reduceMotion)
            WorldScene.StarMap -> transitions.startReturnFromStarmap(reduceMotion)
            WorldScene.Planet -> onBack()
        }
    }

    BackHandler(enabled = ui.scene !is WorldScene.Planet || transitions.phase != WorldTransition.None || qcState.target != null || nestPactOpen || switcherExpanded) { handleBack() }

    // chrome 标题分场景（§4.3）。
    val title: String
    val subtitle: String
    when (val s = ui.scene) {
        WorldScene.Planet -> {
            title = stringResource(R.string.world_title); subtitle = stringResource(R.string.world_subtitle)
        }
        is WorldScene.Continent -> {
            val chip = ui.regionChips.firstOrNull { it.id == s.regionId }
            title = chip?.let { if (it.isHome) stringResource(R.string.world_region_home_title, it.name) else it.name }.orEmpty()
            subtitle = chip?.flavor.orEmpty()
        }
        is WorldScene.Town -> {
            val chrome = viewModel.townChrome(s.cityId); title = chrome.first; subtitle = chrome.second
        }
        is WorldScene.Interior -> {
            val chrome = viewModel.interiorChrome(s.cityId, s.placeId); title = chrome.first; subtitle = chrome.second
        }
        WorldScene.StarMap -> {
            title = stringResource(R.string.world_starmap_title); subtitle = stringResource(R.string.world_starmap_subtitle)
        }
    }

    Box(Modifier.fillMaxSize().background(WorldSceneColors.background)) {
        if (ui.ready && !transitions.glFailed) {
            when (val s = ui.scene) {
                WorldScene.Planet -> if (!webPlanetFailed) PlanetWebSceneView(
                    ui = ui, reduceMotion = reduceMotion,
                    interactive = transitions.phase == WorldTransition.None,
                    initialPose = viewModel.savedPlanetCamera,
                    onFirstFrame = transitions::onPlanetFirstFrame,
                    // J8：家标记 / 雪佛龙整层归页面——点家与捏到底都是俯冲；点雪佛龙由页面自己转球，原生只回触觉。
                    onDive = { transitions.startDiveToContinent(reduceMotion, ui.homeX, ui.homeY) { haptics.light() } },
                    onSpinHome = { haptics.light() },
                    onWebFailed = { webPlanetFailed = true },
                    onViewReady = { transitions.planetWeb = it; transitions.planetView = null },
                ) else PlanetSceneHost(
                    ui = ui, reduceMotion = reduceMotion, initialPose = viewModel.savedPlanetCamera,
                    onGlError = transitions::onSceneGlError, onFirstFrame = transitions::onPlanetFirstFrame,
                    onDive = { transitions.startDiveToContinent(reduceMotion, ui.homeX, ui.homeY) { haptics.light() } },
                    onSpinHome = { transitions.spinHomeToFront(reduceMotion, ui.homeX, ui.homeY) { haptics.light() } },
                    onViewReady = { transitions.planetView = it; transitions.planetWeb = null },
                )
                is WorldScene.Continent -> if (!webContinentFailed) ContinentWebSceneView(
                    regionId = s.regionId, reduceMotion = reduceMotion, staticMode = ui.staticMode,
                    interactive = transitions.phase == WorldTransition.None,
                    continentOf = viewModel::continentOf,
                    onFirstFrame = transitions::onContinentFirstFrame,
                    onReturnToPlanet = { transitions.startReturnToPlanet(reduceMotion) },
                    onEnterTown = { transitions.startDiveToTown(it, reduceMotion) { haptics.light() } },
                    onWebFailed = { webContinentFailed = true },
                    onViewReady = { transitions.continentWeb = it; transitions.continentView = null },
                    initialRestore = viewModel.savedContinentCamera,
                    userPresenceCityId = presence?.cityId, userTraveling = presence?.traveling ?: false, userHomeCityId = com.situ.aichat.world.WorldIds.HOME_CITY_ID,
                    onDepartToCity = { viewModel.openTravel(it) },
                ) else ContinentSceneView(
                    regionId = s.regionId, worldSeed = ui.seed, reduceMotion = reduceMotion, staticMode = ui.staticMode,
                    interactive = transitions.phase == WorldTransition.None,
                    continentOf = viewModel::continentOf,
                    onGlError = transitions::onSceneGlError, onFirstFrame = transitions::onContinentFirstFrame,
                    onReturnToPlanet = { transitions.startReturnToPlanet(reduceMotion) },
                    onEnterTown = { transitions.startDiveToTown(it, reduceMotion) { haptics.light() } },
                    initialRestore = viewModel.savedContinentCamera,
                    userPresenceCityId = presence?.cityId, userTraveling = presence?.traveling ?: false, userHomeCityId = com.situ.aichat.world.WorldIds.HOME_CITY_ID,
                    onDepartToCity = { viewModel.openTravel(it) },
                    onViewReady = { transitions.continentView = it; transitions.continentWeb = null },
                )
                is WorldScene.Town -> if (!webTownFailed) TownWebSceneView(
                    cityId = s.cityId, reduceMotion = reduceMotion, staticMode = ui.staticMode,
                    interactive = transitions.phase == WorldTransition.None,
                    townOf = viewModel::townOf,
                    onFirstFrame = transitions::onTownFirstFrame,
                    onReturnToContinent = { transitions.startReturnToContinent(reduceMotion) },
                    onWebFailed = { webTownFailed = true },
                    onViewReady = { transitions.townWeb = it; transitions.townView = null },
                    cast = cast?.takeIf { it.cityId == s.cityId },
                    userPresent = presence?.let { it.cityId == s.cityId && !it.traveling } ?: false,
                    userTraveling = presence?.traveling ?: false,
                    homePlaceId = "yunye_home",
                    initialCamera = viewModel.savedTownCamera,
                    onEnterInterior = { transitions.startDiveToInterior(s.cityId, it, reduceMotion) { haptics.light() } },
                    onTravelToCity = { viewModel.openTravel(s.cityId) },
                    onOpenChat = { uuid, name -> viewModel.openChat(uuid, name) },
                    onOpenPet = onOpenPet,
                    onMeetNative = { viewModel.onMeetNative(it) },
                    onDismissResident = { nid, name -> viewModel.requestDismissResident(nid, name) },
                ) else TownSceneView(
                    cityId = s.cityId, worldSeed = ui.seed, reduceMotion = reduceMotion, staticMode = ui.staticMode,
                    interactive = transitions.phase == WorldTransition.None,
                    townOf = viewModel::townOf,
                    onGlError = transitions::onSceneGlError, onFirstFrame = transitions::onTownFirstFrame,
                    onReturnToContinent = { transitions.startReturnToContinent(reduceMotion) },
                    cast = cast?.takeIf { it.cityId == s.cityId },
                    userPresent = presence?.let { it.cityId == s.cityId && !it.traveling } ?: false,
                    userTraveling = presence?.traveling ?: false,
                    homePlaceId = "yunye_home",
                    initialCamera = viewModel.savedTownCamera,
                    onEnterInterior = { transitions.startDiveToInterior(s.cityId, it, reduceMotion) { haptics.light() } },
                    onTravelToCity = { viewModel.openTravel(s.cityId) },
                    onOpenChat = { uuid, name -> viewModel.openChat(uuid, name) },
                    onOpenPet = onOpenPet,
                    onMeetNative = { viewModel.onMeetNative(it) },
                    onDismissResident = { nid, name -> viewModel.requestDismissResident(nid, name) }, // 战役 B（O6）：送 TA 离开 → 确认弹窗
                    onViewReady = { transitions.townView = it; transitions.townWeb = null },
                )
                is WorldScene.Interior -> InteriorSceneView(
                    placeId = s.placeId,
                    cast = cast?.takeIf { it.cityId == s.cityId },
                    reduceMotion = reduceMotion, staticMode = ui.staticMode,
                    interactive = transitions.phase == WorldTransition.None,
                    userPresent = presence?.let { it.cityId == s.cityId && !it.traveling } ?: false,
                    interiorOf = viewModel::interiorOf,
                    onGlError = transitions::onSceneGlError, onFirstFrame = transitions::onInteriorFirstFrame,
                    onReturnToTown = { transitions.startReturnToTown(reduceMotion) },
                    onViewReady = { transitions.interiorView = it },
                    onOpenQuickChat = { uuid, name, status -> qcVm.openKnown(uuid, name, status) }, // W12 C6：坐下说两句 → 快聊
                    onOpenMeet = { nid, name, place -> qcVm.openMeet(nid, name, place) }, // W12 C7：去打个招呼 → 初遇
                    onDismissResident = { nid, name -> viewModel.requestDismissResident(nid, name) }, // 战役 B（O6）：送 TA 离开 → 确认弹窗
                    onOpenPet = onOpenPet,
                    onMeetNative = { viewModel.onMeetNative(it) },
                    quickChatOpen = qcState.target != null, // 弹窗开着不触发偷听（§4.6）
                    onEavesdrop = { viewModel.eavesdrop(it) },
                    // W12.5 家的蛋巢（决策 42）：巢态 + 空态开「孵蛋之约」；「迎接」→ 庆祝爆发 + petAdoption 三步流。
                    nestState = nestState,
                    onOpenNestPact = { nestPactOpen = true },
                    onOpenPetAdoption = onOpenPetAdoption,
                )
                WorldScene.StarMap -> {
                    val starmapVm: com.situ.aichat.ui.world.starmap.StarmapViewModel = hiltViewModel()
                    val starmapState by starmapVm.uiState.collectAsStateWithLifecycle()
                    com.situ.aichat.ui.world.starmap.StarmapScene(
                        state = starmapState,
                        seed = ui.seed,
                        reduceMotion = reduceMotion,
                        staticMode = ui.staticMode,
                        onSelect = starmapVm::select,
                        onClearSelection = starmapVm::clearSelection,
                        onToggleList = starmapVm::toggleListMode,
                        onJumpToTown = { cityId -> transitions.startJumpToTownFromStarmap(cityId, reduceMotion) },
                    )
                }
            }
        }
        if (coverAlpha > 0f && !transitions.glFailed) Box(Modifier.fillMaxSize().alpha(coverAlpha).background(WorldSceneColors.background))
        if (transitions.curtain.value > 0f) Box(Modifier.fillMaxSize().alpha(transitions.curtain.value).background(WorldSceneColors.background))
        if (transitions.glFailed) WorldFallback()

        if (ui.scene is WorldScene.Planet && ui.ready && !transitions.glFailed) {
            WorldHintChip(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp))
        }
        // W10：关系星图入口 chip（仅 Planet · ready · 非兜底 · 无转场进行·§4.6/§3.3）。
        if (ui.scene is WorldScene.Planet && ui.ready && !transitions.glFailed && transitions.phase == WorldTransition.None) {
            WorldStarmapEntryChip(
                onClick = { transitions.startFadeToStarmap(reduceMotion) { haptics.light() } },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 16.dp, top = 16.dp),
            )
        }

        // 在途 chip（title chip 下·常驻至到达·§4.8·🔵-4：zoneId 用世界时区展示，与窗景/ETA 一致）。
        travelChip?.let { chip ->
            WorldTravelChip(chip.destName, chip.arriveAtMs, zoneId = ui.userTimezoneId, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 66.dp))
        }

        // 顶栏方案 A（W15 §4.2·z 序移到在途 chip 之后·展开列表须压过在途 chip）：展开时全屏收起层 + 顶栏。
        if (switcherExpanded) {
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { switcherExpanded = false },
            )
        }
        WorldTopBar(
            onBack = ::handleBack,
            title = title,
            subtitle = subtitle,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp, top = 16.dp, end = 16.dp),
            switcher = (ui.scene as? WorldScene.Continent)?.let { s ->
                WorldTopBarSwitcher(
                    chips = ui.regionChips,
                    currentId = s.regionId,
                    expanded = switcherExpanded,
                    onToggle = { if (transitions.phase == WorldTransition.None) switcherExpanded = !switcherExpanded },
                    onSelect = {
                        if (transitions.phase == WorldTransition.None) { switcherExpanded = false; viewModel.selectRegion(it) }
                    },
                )
            },
        )

        // 旅行单（§4.8·🔵-4：ETA 用世界时区）。
        WorldTravelSheet(
            quote = travelQuote, result = travelResult, reduceMotion = reduceMotion,
            nowMs = System.currentTimeMillis(), zoneId = ui.userTimezoneId,
            onDepart = { viewModel.departTravel(it) }, onClose = { viewModel.closeTravel() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        // W12 C6/C7：快聊 / 初遇弹窗（「聊天页 ›」复用既有深链导航·关闭前先 close 复位）。
        WorldQuickChatSheet(
            state = qcState, reduceMotion = reduceMotion,
            onSend = qcVm::send, onRetry = qcVm::retry, onConfirmMeet = qcVm::confirmMeet, onClose = qcVm::close,
            onOpenChatPage = { convUuid -> qcVm.close(); onOpenChat(convUuid) },
        )
        // W12.5「孵蛋之约」sheet（决策 42·仅室内可见·定约 = setPact + toast + 即时切在孵态）。
        EggNestSheet(
            visible = nestPactOpen && ui.scene is WorldScene.Interior,
            candidates = nestCandidates,
            reduceMotion = reduceMotion,
            onConfirm = { candidate ->
                nestVm.setPact(candidate.characterUuid)
                nestPactOpen = false
                android.widget.Toast.makeText(toastCtx, toastCtx.getString(R.string.world_nest_pact_done_toast), android.widget.Toast.LENGTH_SHORT).show()
            },
            onClose = { nestPactOpen = false },
        )
        // W13 首启轻三步（图纸 §3.5/§4.5）：世界屏最顶层·自我门控（仅 !worldOnboardingDone 露脸）。
        WorldOnboardingOverlay(scene = ui.scene, quickChatOpen = qcState.target != null)

        // 战役 B（O6·§4.3）：送 TA 离开二次确认（暖纸弹窗惯例·AppButton 主/文样式）。确认 → deleteUnrecruited + 刷 cast + toast。
        pendingDismiss?.let { pd ->
            AppDialog(
                onDismissRequest = viewModel::cancelDismissResident,
                title = stringResource(R.string.world_resident_dismiss_title, pd.name),
                body = stringResource(R.string.world_resident_dismiss_body),
                confirmText = stringResource(R.string.world_resident_dismiss_confirm),
                onConfirm = viewModel::confirmDismissResident,
                dismissText = stringResource(R.string.world_resident_dismiss_cancel),
                onDismiss = viewModel::cancelDismissResident,
            )
        }
    }
}
