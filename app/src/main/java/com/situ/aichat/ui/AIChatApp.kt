package com.situ.aichat.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppBottomNavHeight
import com.situ.aichat.ui.designsystem.AppBottomNavItem
import com.situ.aichat.ui.designsystem.AppNavIcons
import com.situ.aichat.ui.liuli.character.SkinnedCharacterEditScreen
import com.situ.aichat.ui.liuli.character.SkinnedCharacterProfileScreen
import com.situ.aichat.ui.liuli.character.SkinnedMemoryEditScreen
import com.situ.aichat.ui.liuli.offline.SkinnedOfflineMeetingMemoryScreen
import com.situ.aichat.ui.liuli.ourdays.SkinnedOurDayPageScreen
import com.situ.aichat.ui.liuli.ourdays.SkinnedOurDaysScreen
import com.situ.aichat.ui.liuli.promise.SkinnedPromiseLedgerScreen
import com.situ.aichat.ui.liuli.schedule.SkinnedScheduleFullDayScreen
import com.situ.aichat.ui.starfield.StarfieldScreen
import com.situ.aichat.ui.liuli.backup.SkinnedBackupScreen
import com.situ.aichat.ui.chat.ChatScreen
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.chat.LiuliChatScreen
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.liuli.home.LiuliHomeHost
import com.situ.aichat.ui.liuli.home.SkinnedBottomNav
import com.situ.aichat.ui.liuli.home.SkinnedChatListScreen
import com.situ.aichat.ui.liuli.home.SkinnedContactsScreen
import com.situ.aichat.ui.liuli.home.SkinnedMomentsHubScreen
import com.situ.aichat.ui.liuli.home.SkinnedProfileScreen
import com.situ.aichat.ui.liuli.home.rememberLiuliHomeChrome
import com.situ.aichat.ui.voicecall.VoiceCallScreen
import com.situ.aichat.ui.liuli.diary.SkinnedComposeDiaryScreen
import com.situ.aichat.ui.liuli.diary.SkinnedDiaryDetailScreen
import com.situ.aichat.ui.liuli.diary.SkinnedDiaryListScreen
import com.situ.aichat.ui.liuli.diary.SkinnedDiaryPromptPreviewScreen
import com.situ.aichat.ui.liuli.diary.SkinnedDiaryPromptSettingsScreen
import com.situ.aichat.ui.liuli.diary.SkinnedDiarySettingsScreen
import com.situ.aichat.ui.liuli.gift.SkinnedGiftBoxScreen
import com.situ.aichat.ui.liuli.gift.SkinnedGiftReactionScreen
import com.situ.aichat.ui.liuli.gift.SkinnedGiftShopScreen
import com.situ.aichat.ui.liuli.gift.SkinnedReceivedGiftDetailScreen
import com.situ.aichat.ui.liuli.moments.SkinnedComposeMomentScreen
import com.situ.aichat.ui.liuli.moments.SkinnedDayMomentsScreen
import com.situ.aichat.ui.liuli.moments.SkinnedMomentAuthorScreen
import com.situ.aichat.ui.liuli.moments.SkinnedMomentDetailScreen
import com.situ.aichat.ui.liuli.moments.SkinnedMomentNotificationListScreen
import com.situ.aichat.ui.liuli.moments.SkinnedMomentSettingsScreen
import com.situ.aichat.ui.liuli.moments.SkinnedMomentsListScreen
import com.situ.aichat.ui.liuli.pet.SkinnedPetAdoptionScreen
import com.situ.aichat.ui.liuli.pet.SkinnedPetDetailScreen
import com.situ.aichat.ui.liuli.pet.SkinnedPetInventoryScreen
import com.situ.aichat.ui.liuli.pet.SkinnedPetListScreen
import com.situ.aichat.ui.liuli.pet.SkinnedPetShopScreen
import com.situ.aichat.ui.liuli.profile.SkinnedUserProfileEditScreen
import com.situ.aichat.ui.liuli.promptmodule.SkinnedPromptModuleSettingsScreen
import com.situ.aichat.ui.screens.PlaceholderScreen
import com.situ.aichat.ui.liuli.sticker.SkinnedStickerImportScreen
import com.situ.aichat.ui.liuli.sticker.SkinnedStickerManagementScreen
import com.situ.aichat.ui.liuli.story.SkinnedStoryArchiveAllScreen
import com.situ.aichat.ui.liuli.story.SkinnedStoryArchiveDetailScreen
import com.situ.aichat.ui.liuli.story.SkinnedStoryBookHubScreen
import com.situ.aichat.ui.liuli.story.SkinnedStoryBookshelfScreen
import com.situ.aichat.ui.liuli.worldbook.SkinnedWorldBookDetailScreen
import com.situ.aichat.ui.liuli.worldbook.SkinnedWorldBookEntryEditScreen
import com.situ.aichat.ui.liuli.worldbook.SkinnedWorldBookSettingsScreen
import com.situ.aichat.ui.world.WorldScreen
import com.situ.aichat.ui.liuli.worldbook.SkinnedWorldBookShelfScreen
import com.situ.aichat.ui.liuli.story.SkinnedStoryChapterListScreen
import com.situ.aichat.ui.liuli.story.SkinnedStoryCreationScreen
import com.situ.aichat.ui.liuli.story.SkinnedStoryFieldEditorScreen
import com.situ.aichat.ui.liuli.story.SkinnedStoryReaderScreen
import com.situ.aichat.ui.liuli.story.SkinnedStoryTemplateWallScreen
import com.situ.aichat.ui.liuli.settings.SkinnedAboutScreen
import com.situ.aichat.ui.liuli.settings.SkinnedAgreementViewScreen
import com.situ.aichat.ui.liuli.settings.SkinnedApiConfigEditScreen
import com.situ.aichat.ui.liuli.settings.SkinnedApiConfigScreen
import com.situ.aichat.ui.liuli.settings.SkinnedQrScanScreen
import com.situ.aichat.ui.liuli.contextlog.SkinnedContextLogCharacterScreen
import com.situ.aichat.ui.liuli.contextlog.SkinnedContextLogEntryScreen
import com.situ.aichat.ui.liuli.contextlog.SkinnedContextLogFailureScreen
import com.situ.aichat.ui.liuli.contextlog.SkinnedContextLogHomeScreen
import com.situ.aichat.ui.liuli.contextlog.SkinnedContextLogMapScreen
import com.situ.aichat.ui.liuli.contextlog.SkinnedContextLogSentScreen
import com.situ.aichat.ui.liuli.contextlog.SkinnedContextLogSettingsScreen
import com.situ.aichat.ui.liuli.contextlog.SkinnedContextLogReplyScreen
import com.situ.aichat.ui.liuli.perflog.SkinnedPerfCollectScreen
import com.situ.aichat.ui.liuli.settings.SkinnedAppearanceSettingsScreen
import com.situ.aichat.ui.liuli.settings.SkinnedContentFilterSettingsScreen
import com.situ.aichat.ui.liuli.settings.SkinnedGrowthSettingsScreen
import com.situ.aichat.ui.liuli.settings.SkinnedKernelObservatoryScreen
import com.situ.aichat.ui.liuli.settings.SkinnedReplyRuleSettingsScreen
import com.situ.aichat.ui.liuli.settings.SkinnedMemoryHubScreen
import com.situ.aichat.ui.liuli.settings.SkinnedMemoryPromptsScreen
import com.situ.aichat.ui.liuli.wallet.SkinnedRedeemCodeScreen
import com.situ.aichat.ui.liuli.wallet.SkinnedUserWalletScreen
import com.situ.aichat.ui.liuli.settings.SkinnedPetSettingsScreen
import com.situ.aichat.ui.liuli.settings.SkinnedScheduleSettingsScreen
import com.situ.aichat.ui.liuli.settings.SkinnedSystemTogglesScreen
import com.situ.aichat.ui.liuli.settings.SkinnedWalletSettingsScreen
import com.situ.aichat.ui.liuli.settings.SkinnedApiFunctionAssignmentScreen
import com.situ.aichat.ui.liuli.settings.SkinnedBackgroundReliabilityScreen
import com.situ.aichat.ui.settings.ReliabilityPromptDialog
import com.situ.aichat.ui.liuli.settings.SkinnedAdvancedSettingsScreen
import com.situ.aichat.ui.liuli.settings.SkinnedCalendarAwarenessScreen
import com.situ.aichat.ui.liuli.settings.SkinnedImmersiveSettingsScreen
import com.situ.aichat.ui.liuli.settings.SkinnedNotificationSettingsScreen
import com.situ.aichat.ui.liuli.settings.SkinnedSettingsScreen
import com.situ.aichat.ui.liuli.settings.SkinnedStoryGlobalSettingsScreen
import com.situ.aichat.ui.liuli.settings.SkinnedWorldSettingsScreen
import com.situ.aichat.ui.settings.hub.SettingsHubEntry
import com.situ.aichat.world.WorldFocusEntry
import com.situ.aichat.ui.liuli.settings.SkinnedTtsConfigurationScreen
import com.situ.aichat.ui.liuli.settings.SkinnedVoiceCallSettingsScreen
import com.situ.aichat.ui.liuli.settings.SkinnedVoiceSettingsScreen
import com.situ.aichat.ui.navigation.BackCardHoldEnter
import com.situ.aichat.ui.navigation.LocalPredictiveBackMotion
import com.situ.aichat.ui.navigation.appEnterTransition
import com.situ.aichat.ui.navigation.appExitTransition
import com.situ.aichat.ui.navigation.appPopEnterTransition
import com.situ.aichat.ui.navigation.appPopExitTransition
import com.situ.aichat.ui.navigation.backCardComposable
import com.situ.aichat.ui.navigation.backCardHoldExit
import com.situ.aichat.ui.navigation.rememberPredictiveBackMotion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private sealed class TopDest(val route: String, val labelRes: Int, val icon: ImageVector) {
    data object Chats : TopDest("chats", R.string.tab_chats, AppNavIcons.Chat)
    data object Contacts : TopDest("contacts", R.string.tab_contacts, AppNavIcons.Contacts)
    data object Moments : TopDest("moments", R.string.tab_moments, AppNavIcons.Moments)
    data object Profile : TopDest("profile", R.string.tab_profile, AppNavIcons.Profile)
}

private val topDestinations = listOf(TopDest.Chats, TopDest.Contacts, TopDest.Moments, TopDest.Profile)
private val topRoutes = topDestinations.map { it.route }.toSet()

/** 13.10b 扫码导入：扫码屏 → API 配置屏回传识别出的二维码文本的 savedStateHandle 键。 */
private const val KEY_SCANNED_API_CONFIG = "scannedApiConfigQr"

@Composable
fun AIChatApp(
    pendingNavConversation: StateFlow<String?> = MutableStateFlow<String?>(null),
    onNavConsumed: () -> Unit = {},
    pendingNavMoment: StateFlow<String?> = MutableStateFlow<String?>(null),
    onMomentNavConsumed: () -> Unit = {},
    pendingNavMomentsFeed: StateFlow<Boolean> = MutableStateFlow(false),
    onMomentsFeedNavConsumed: () -> Unit = {},
    pendingNavPet: StateFlow<String?> = MutableStateFlow<String?>(null),
    onPetNavConsumed: () -> Unit = {},
    pendingNavContacts: StateFlow<Boolean> = MutableStateFlow(false),
    onContactsNavConsumed: () -> Unit = {},
    pendingNavCharacterProfile: StateFlow<String?> = MutableStateFlow<String?>(null),
    onCharacterProfileNavConsumed: () -> Unit = {},
    pendingNavBackup: StateFlow<Boolean?> = MutableStateFlow(null),
    onBackupNavConsumed: () -> Unit = {},
    pendingNavStory: StateFlow<String?> = MutableStateFlow<String?>(null),
    onStoryNavConsumed: () -> Unit = {},
    pendingNavWorld: StateFlow<Boolean> = MutableStateFlow(false),
    onWorldNavConsumed: () -> Unit = {},
    showReliabilityPrompt: StateFlow<Boolean> = MutableStateFlow(false),
    onDismissReliabilityPrompt: () -> Unit = {},
    navBadgeViewModel: NavBadgeViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in topRoutes

    // nav-shell-2：底部「聊天」/「动态」Tab 未读角标（对齐 iOS MainTabView chats/moments .badge）。
    val chatsUnread by navBadgeViewModel.chatsUnread.collectAsStateWithLifecycle()
    val momentsUnread by navBadgeViewModel.momentsUnread.collectAsStateWithLifecycle()
    // 过渡丝滑化·A1：悬浮底栏背景不透明度（外观设置可调·即时生效）。
    val bottomNavOpacity by navBadgeViewModel.bottomNavOpacity.collectAsStateWithLifecycle()
    // 琉璃卷三：底栏缩丸的滚动信号（暖陶下建了也不挂·[LiuliHomeHost] 只在琉璃分支接 nestedScroll）。
    val homeChrome = rememberLiuliHomeChrome()

    // P6.1d：通知点击 → 跳转到对应会话（[NotificationNavigator] 由点击物化后投放目标 uuid）。
    val navTarget by pendingNavConversation.collectAsStateWithLifecycle()
    LaunchedEffect(navTarget) {
        val target = navTarget ?: return@LaunchedEffect
        // nav-shell-3 / 对齐 iOS MainTabView.swift:155-163 + 216-218：通知深链统一回根到聊天列表
        // （等价 iOS 先切回 chats tab 再压会话），保证返回键回到聊天列表，而非通知到达时所在的无关子页/Tab。
        // popUpTo(chats) 非 inclusive：保留聊天列表根、把会话压在其上；launchSingleTop 防重复压同一会话。
        navController.navigate("chat/$target") {
            popUpTo(TopDest.Chats.route)
            launchSingleTop = true
        }
        onNavConsumed()
    }

    // P7.2.8 决策① / 13.7e 单帖：朋友圈互动通知 / 「X 发了新动态」单帖点击 → 跳转到帖子详情。
    val momentTarget by pendingNavMoment.collectAsStateWithLifecycle()
    LaunchedEffect(momentTarget) {
        val target = momentTarget ?: return@LaunchedEffect
        navController.navigate("moment/$target") { launchSingleTop = true }
        onMomentNavConsumed()
    }

    // 13.7e 合并：「N 位好友发了新动态」点击 → 跳转到朋友圈 feed（无单帖 uuid）。
    val momentsFeedTarget by pendingNavMomentsFeed.collectAsStateWithLifecycle()
    LaunchedEffect(momentsFeedTarget) {
        if (!momentsFeedTarget) return@LaunchedEffect
        navController.navigate("momentsFeed") { launchSingleTop = true }
        onMomentsFeedNavConsumed()
    }

    // P1-33：里程碑庆祝通知点击 → 跳转到该角色资料页（关系历程卡在页内）。
    val characterProfileTarget by pendingNavCharacterProfile.collectAsStateWithLifecycle()
    LaunchedEffect(characterProfileTarget) {
        val target = characterProfileTarget ?: return@LaunchedEffect
        navController.navigate("characterProfile/$target") { launchSingleTop = true }
        onCharacterProfileNavConsumed()
    }

    // U4：故事章节解锁/完成/失败通知点击 → 跳转到该故事详情（11.1g 深链此前无消费方=死链，落到空首屏）。
    val storyTarget by pendingNavStory.collectAsStateWithLifecycle()
    LaunchedEffect(storyTarget) {
        val target = storyTarget ?: return@LaunchedEffect
        navController.navigate("story/$target") { launchSingleTop = true }
        onStoryNavConsumed()
    }

    // W9a：世界通知深链（ACTION_OPEN_WORLD）→ 压栈世界屏。禁 restoreState（这是压栈详情页，非跳 Tab）。
    val worldTarget by pendingNavWorld.collectAsStateWithLifecycle()
    LaunchedEffect(worldTarget) {
        if (!worldTarget) return@LaunchedEffect
        navController.navigate("world") { launchSingleTop = true }
        onWorldNavConsumed()
    }

    // P11.3：宠物小组件点击 → 跳转到该宠物详情。
    val petTarget by pendingNavPet.collectAsStateWithLifecycle()
    LaunchedEffect(petTarget) {
        val target = petTarget ?: return@LaunchedEffect
        navController.navigate("petDetail/$target") { launchSingleTop = true }
        onPetNavConsumed()
    }

    // 跳「联系人」Tab 的一次性信号（13.10a 分享给角色的通用分享落地 / 13.10c QS 磁贴「找角色」），**导航后即 consume**
    // （不残留致意外重导航）。分享场景的选择条文本由 ShareTargetCoordinator 单独持有，ContactsScreen 据其显示选择条 + 点选即发。
    // 必须经 navigateToContactsTab 保证联系人真落栈顶——裸 restoreState 会把压在联系人之上的详情页一起恢复回来（分享被吞真伤）。
    val navContacts by pendingNavContacts.collectAsStateWithLifecycle()
    LaunchedEffect(navContacts) {
        if (!navContacts) return@LaunchedEffect
        navController.navigateToContactsTab(TopDest.Contacts.route)
        onContactsNavConsumed()
    }

    // P15·P0-19：自动备份通知点击 → 跳备份设置（focusFolder 经 nav arg 透传，true 时备份页进页自动重选目录），导航后即 consume。
    val navBackup by pendingNavBackup.collectAsStateWithLifecycle()
    LaunchedEffect(navBackup) {
        val focusFolder = navBackup ?: return@LaunchedEffect
        navController.navigate("backup?focusFolder=$focusFolder") { launchSingleTop = true }
        onBackupNavConsumed()
    }

    // 13.7a：首次开启依赖后台的功能时主动弹一次 HyperOS 可靠性引导（一次性由 ReliabilityPromptController 决定）。
    // 「去设置」跳后台运行保障页（电池 + 自启动两张卡都在那），复用现成页，不重复实现。
    val reliabilityVisible by showReliabilityPrompt.collectAsStateWithLifecycle()
    if (reliabilityVisible) {
        ReliabilityPromptDialog(
            onGoToSettings = {
                onDismissReliabilityPrompt()
                navController.navigate("backgroundReliability")
            },
            onDismiss = onDismissReliabilityPrompt,
        )
    }

    Scaffold { innerPadding ->
        val topLevelRoutes = setOf(
            TopDest.Chats.route, TopDest.Contacts.route, TopDest.Moments.route, TopDest.Profile.route,
        )
        // 预测返回原生卡片（图纸 2026-09-24-预测性返回原生卡片）：会话状态机；由 NavHost 的 predictivePopExitTransition 喂入。
        val backMotion = rememberPredictiveBackMotion(navController)
        // A0·叠加层变体：Box 容纳 NavHost + 悬浮底栏叠加层。NavHost 仍按系统栏 inset 垫（保留 consume/E3），
        // 但底栏不再占 Scaffold 的 bottomBar 槽 → innerPadding 不随底栏显隐变化 → 详情页可用高度恒定（无沉降）。
        LiuliHomeHost(
            modifier = Modifier.fillMaxSize(),
            chrome = homeChrome,
            active = showBottomBar,
            bottomBar = {
            // A0·叠加层变体：底栏移出 Scaffold 槽 → 浮在 NavHost 之上的叠加层（align 底部·自带 navigationBarsPadding）。
            // 只在 4 个 Tab 路由显示；进/出详情时它自己在这层淡入淡出，不再改变 NavHost 内容高度（=进会话输入框不再下降）。
            AnimatedVisibility(
                visible = showBottomBar,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium),
                ) { it } + fadeIn(spring(stiffness = Spring.StiffnessMedium)),
                exit = slideOutVertically(
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium),
                ) { it } + fadeOut(spring(stiffness = Spring.StiffnessMedium)),
            ) {
                val currentDestination = backStackEntry?.destination
                SkinnedBottomNav(
                    items = topDestinations.map { dest ->
                        val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                        // nav-shell-2：聊天=未读消息总数、动态=未读通知条数；其余 Tab 无角标（对齐 iOS）。
                        val badgeCount = when (dest) {
                            TopDest.Chats -> chatsUnread
                            TopDest.Moments -> momentsUnread
                            else -> 0
                        }
                        AppBottomNavItem(
                            icon = dest.icon,
                            label = stringResource(dest.labelRes),
                            selected = selected,
                            badgeCount = badgeCount,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    },
                    opacity = bottomNavOpacity,
                    chrome = homeChrome,
                )
            }
            },
        ) {
        CompositionLocalProvider(LocalPredictiveBackMotion provides backMotion) {
        NavHost(
            navController = navController,
            startDestination = TopDest.Chats.route,
            // 壁纸全屏沉浸重构②（参照 RikkaHub·2026-06-28）：NavHost 不再垫系统栏 inset、也不 consume——
            // 各屏自管 inset（M3 Scaffold/TopAppBar 默认 contentWindowInsets 自垫；4 个沉浸屏背景 fillMaxSize
            // 自然铺满系统栏后、顶/底栏各自 statusBarsPadding/navigationBarsPadding）。取代旧 E3 的 padding+consume。
            modifier = Modifier.fillMaxSize(),
            // 常规转场（push / 非手势 pop）照旧——2026-09-24 原样搬到 ui/navigation/AppNavTransitions.kt（只搬不改）。
            enterTransition = { appEnterTransition(topLevelRoutes) },
            exitTransition = { appExitTransition(topLevelRoutes) },
            popEnterTransition = { appPopEnterTransition(topLevelRoutes) },
            popExitTransition = { appPopExitTransition(topLevelRoutes) },
            // 手势返回：导航库只拿占位转场保两页存活，画面由 backCardComposable 的卡片包装自画（占位与会话同生）。
            predictivePopEnterTransition = { BackCardHoldEnter },
            predictivePopExitTransition = { edge -> backCardHoldExit(backMotion, edge) },
        ) {
            backCardComposable(TopDest.Chats.route) {
                // A1：内容铺满整窗、延伸到半透底栏后；底部留白下沉进列表 contentPadding，末条仍能滑到栏上方（过渡丝滑化·A1）。
                SkinnedChatListScreen(
                    // 批4 4-7：launchSingleTop 防快速双击同一会话压两个同会话页/双 VM。
                    onOpenChat = { conversationUuid -> navController.navigate("chat/$conversationUuid") { launchSingleTop = true } },
                    onCreateCharacter = { navController.navigate("character/new") },
                    bottomContentPadding = AppBottomNavHeight,
                )
            }
            backCardComposable(TopDest.Contacts.route) {
                SkinnedContactsScreen(
                    onOpenChat = { conversationUuid -> navController.navigate("chat/$conversationUuid") },
                    onCreateCharacter = { navController.navigate("character/new") },
                    onEditCharacter = { uuid -> navController.navigate("character/edit/$uuid") },
                    onOpenProfile = { uuid -> navController.navigate("characterProfile/$uuid") },
                    bottomContentPadding = AppBottomNavHeight,
                )
            }
            // P7.2.7 朋友圈（M06）：枢纽（Tab）→ 信息流 → 发布。详情/通知列表/角色动态 → 7.2.8（现路由占位）；
            // 故事 → P11、宠物 → P8（占位）。
            backCardComposable(TopDest.Moments.route) {
                SkinnedMomentsHubScreen(
                    onOpenFeed = { navController.navigate("momentsFeed") },
                    onOpenDiary = { navController.navigate("diary") },
                    onOpenOurDays = { navController.navigate("ourDays") },
                    onOpenStory = { navController.navigate("momentsStory") },
                    onOpenWorld = { navController.navigate("world") { launchSingleTop = true } },
                    bottomContentPadding = AppBottomNavHeight,
                    onOpenPet = { characterUuid -> navController.navigate("petDetail/$characterUuid") { launchSingleTop = true } }, // W12.5 信息条宠物段直达
                    // 图纸 2026-09-06-宠物总览页复活 D-1：宠物条 → 总览页。**全库唯一** navigate("momentsPet")；
                    // 此前该路由零导航入口 = 死路由，PetListScreen 在 App 内点不到（图纸 §0.1）。
                    onOpenPetHub = { navController.navigate("momentsPet") { launchSingleTop = true } },
                )
            }
            backCardComposable("momentsFeed") {
                SkinnedMomentsListScreen(
                    onBack = { navController.popBackStack() },
                    onCompose = { navController.navigate("momentCompose") },
                    onOpenPost = { uuid -> navController.navigate("moment/$uuid") },
                    onOpenNotifications = { navController.navigate("momentNotifications") },
                    onOpenCharacterMoments = { uuid -> navController.navigate("characterMoments/$uuid") },
                )
            }
            backCardComposable(
                route = "characterMoments/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                SkinnedMomentAuthorScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPost = { uuid -> navController.navigate("moment/$uuid") },
                )
            }
            backCardComposable("userMoments") {
                SkinnedMomentAuthorScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPost = { uuid -> navController.navigate("moment/$uuid") },
                )
            }
            backCardComposable("momentCompose") {
                SkinnedComposeMomentScreen(onClose = { navController.popBackStack() })
            }
            backCardComposable(
                route = "moment/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType }),
            ) {
                SkinnedMomentDetailScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("momentNotifications") {
                SkinnedMomentNotificationListScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPost = { uuid -> navController.navigate("moment/$uuid") },
                )
            }
            backCardComposable("momentSettings") {
                SkinnedMomentSettingsScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("stickerManagement") {
                SkinnedStickerManagementScreen(
                    onBack = { navController.popBackStack() },
                    onImport = { navController.navigate("stickerImport") },
                )
            }
            backCardComposable("stickerImport") {
                SkinnedStickerImportScreen(onBack = { navController.popBackStack() })
            }
            // P11 互动故事（11.1h）：朋友圈枢纽 → 书架 → 章节列表 → 阅读器。阅读器=11.1i、创建/设定=11.1j（暂占位）。
            backCardComposable("momentsStory") {
                SkinnedStoryBookshelfScreen(
                    onBack = { navController.popBackStack() },
                    onOpenStory = { storyId -> navController.navigate("story/$storyId") },
                    onOpenChapter = { chapterId -> navController.navigate("storyReader/$chapterId") },
                    onCreateStory = { navController.navigate("storyTemplateWall") },
                    onOpenSettings = { storyId -> navController.navigate("storySettings/$storyId") },
                    onOpenArchive = { storyId -> navController.navigate("storyArchive/$storyId") },
                    onViewAllArchive = { navController.navigate("storyArchiveAll") },
                )
            }
            // ST8 结局档案卡：书架档案分组「完结卡」tap 打开（全屏·分享长图 / 导出全文）。
            backCardComposable(
                route = "storyArchive/{storyId}",
                arguments = listOf(navArgument("storyId") { type = NavType.StringType }),
            ) {
                SkinnedStoryArchiveDetailScreen(onBack = { navController.popBackStack() })
            }
            // ST8 结局档案全览：档案区「全部 ›」→ 全部已完结封面网格。
            backCardComposable("storyArchiveAll") {
                SkinnedStoryArchiveAllScreen(
                    onBack = { navController.popBackStack() },
                    onOpenArchive = { storyId -> navController.navigate("storyArchive/$storyId") },
                )
            }
            // ST7b 创建两层流：模板墙（默认入口）→ 开书 sheet 直开 / 尾卡·改一改再开 → 高级自定义（storyCreation）。
            backCardComposable("storyTemplateWall") {
                SkinnedStoryTemplateWallScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCustom = { templateId ->
                        if (templateId == null) navController.navigate("storyCreation")
                        else navController.navigate("storyCreation?templateId=$templateId")
                    },
                    // 开书成功回书架（新故事以「生成中」卡片出现·跳过模板墙）。
                    onCreated = { navController.popBackStack("momentsStory", inclusive = false) },
                )
            }
            backCardComposable(
                route = "story/{storyId}",
                arguments = listOf(navArgument("storyId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val storyId = backStackEntry.arguments?.getString("storyId").orEmpty()
                SkinnedStoryChapterListScreen(
                    onBack = { navController.popBackStack() },
                    // 深链兜底（2026-08-04）：解锁通知落到已删书——书架在栈上就回书架（同书页 onStoryGone 姿势）；
                    // 深链冷启栈上没书架则普通返回（pop 指定路由失败返回 false，绝不能让屏幕停着不动）。
                    onStoryGone = {
                        if (!navController.popBackStack("momentsStory", inclusive = false)) navController.popBackStack()
                    },
                    onOpenChapter = { chapterId -> navController.navigate("storyReader/$chapterId") },
                    onOpenSettings = { navController.navigate("storySettings/$storyId") },
                )
            }
            backCardComposable(
                route = "storyReader/{chapterId}",
                arguments = listOf(navArgument("chapterId") { type = NavType.StringType }),
            ) {
                SkinnedStoryReaderScreen(
                    onBack = { navController.popBackStack() },
                    // 卷三 §4.6：⋮ 菜单「书页」——与章节列表屏同一条路由（书页取代旧故事设定屏·卷二 D-10）。
                    onOpenBookHub = { id -> navController.navigate("storySettings/$id") },
                    onGoToChat = {
                        // 「去聊天，好了通知我」：切回聊天 tab（生成在前台服务里继续，完成发通知）。
                        navController.navigate(TopDest.Chats.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            backCardComposable(
                route = "storyCreation?templateId={templateId}",
                arguments = listOf(navArgument("templateId") { type = NavType.StringType; defaultValue = "" }),
            ) {
                // templateId 非空 = 从开书 sheet「改一改再开」带模板预填值进来（VM init 按 arg 起底表单）。
                SkinnedStoryCreationScreen(
                    onBack = { navController.popBackStack() },
                    // 创建后回书架（新故事以「生成中」卡片出现，首章在前台服务里生成·跳过模板墙）。
                    onCreated = { navController.popBackStack("momentsStory", inclusive = false) },
                )
            }
            backCardComposable(
                route = "storySettings/{storyId}",
                arguments = listOf(navArgument("storyId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val storyId = backStackEntry.arguments?.getString("storyId").orEmpty()
                // 卷二 D-10：书页取代旧故事设定屏（路由名与两处调用点复用，入口零改）。
                SkinnedStoryBookHubScreen(
                    onBack = { navController.popBackStack() },
                    onStoryGone = { navController.popBackStack("momentsStory", inclusive = false) },
                    onOpenChapter = { chapterId -> navController.navigate("storyReader/$chapterId") },
                    onOpenField = { key -> navController.navigate("storyFieldEditor/$storyId/$key") },
                    onOpenGlobalSettings = { navController.navigate("storyGlobalSettings") },
                )
            }
            // 卷二 §11 统一编辑页：书页两 Tab 的全部文本设定（15 字段 + 全局忌口变体）共用这一个全屏长相。
            backCardComposable(
                route = "storyFieldEditor/{storyId}/{fieldKey}",
                arguments = listOf(
                    navArgument("storyId") { type = NavType.StringType },
                    navArgument("fieldKey") { type = NavType.StringType },
                ),
            ) { SkinnedStoryFieldEditorScreen(onBack = { navController.popBackStack() }) }
            backCardComposable("storyGlobalSettings") { // 卷四 §4.2 全局创作偏好子屏（storyId 段 "-" 占位·全局分支不读它）
                SkinnedStoryGlobalSettingsScreen({ navController.popBackStack() }, { key -> navController.navigate("storyFieldEditor/-/$key") })
            }
            // 宠物（M11）：枢纽列表 → 详情（按是否有宠物显示详情或领养进度）→ 领养。
            backCardComposable("momentsPet") {
                SkinnedPetListScreen(
                    onOpenPet = { uuid -> navController.navigate("petDetail/$uuid") },
                    onBack = { navController.popBackStack() },
                )
            }
            // W9a 世界系统星球层：全屏 GL 星球（非 topRoute → 底栏自然隐藏）。入口=动态页临时行 / 世界通知深链。
            backCardComposable("world") {
                WorldScreen(
                    onBack = { navController.popBackStack() },
                    onOpenChat = { conversationUuid -> navController.navigate("chat/$conversationUuid") { launchSingleTop = true } },
                    onOpenPet = { characterUuid -> navController.navigate("petDetail/$characterUuid") { launchSingleTop = true } },
                    onOpenPetAdoption = { characterUuid -> navController.navigate("petAdoption/$characterUuid") { launchSingleTop = true } }, // W12.5 蛋巢「迎接」→ 现有领养三步流
                )
            }
            // 世界系统 W13 设置二级页（图纸 §4.3/§4.4）。
            backCardComposable("worldSettings") {
                SkinnedWorldSettingsScreen(onBack = { navController.popBackStack() })
            }
            // 世界书 WB7（UI 名「设定集」）：书架 → 书详情；条目编辑器随 WB7b、触发设置随 WB7c。
            backCardComposable("worldBooks") {
                SkinnedWorldBookShelfScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBook = { bookUuid -> navController.navigate("worldBook/$bookUuid") },
                    onOpenSettings = { navController.navigate("worldBookSettings") },
                )
            }
            backCardComposable("worldBookSettings") {
                SkinnedWorldBookSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenMemorySettings = { navController.navigate("memorySettings") },
                )
            }
            backCardComposable(
                "worldBook/{bookUuid}",
                arguments = listOf(navArgument("bookUuid") { type = NavType.StringType }),
            ) { backStackEntry ->
                val worldBookUuid = backStackEntry.arguments?.getString("bookUuid").orEmpty()
                SkinnedWorldBookDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenEntry = { entryUuid ->
                        navController.navigate("worldBookEntry/$worldBookUuid?entryUuid=$entryUuid")
                    },
                    onCreateEntry = { guideKey ->
                        navController.navigate(
                            "worldBookEntry/$worldBookUuid" + (guideKey?.let { "?guide=$it" } ?: ""),
                        )
                    },
                )
            }
            backCardComposable(
                "worldBookEntry/{bookUuid}?entryUuid={entryUuid}&guide={guide}",
                arguments = listOf(
                    navArgument("bookUuid") { type = NavType.StringType },
                    navArgument("entryUuid") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("guide") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                SkinnedWorldBookEntryEditScreen(onDone = { navController.popBackStack() })
            }
            backCardComposable(
                "petDetail/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) { backStackEntry ->
                val uuid = backStackEntry.arguments?.getString("characterUuid").orEmpty()
                // pet-ui-2：背包回传的反应文案（savedStateHandle 跨路由），用于头顶气泡。
                val petReaction by backStackEntry.savedStateHandle
                    .getStateFlow<String?>("petReaction", null)
                    .collectAsStateWithLifecycle()
                SkinnedPetDetailScreen(
                    onBack = { navController.popBackStack() },
                    onAdopt = { navController.navigate("petAdoption/$uuid") },
                    onOpenShop = { navController.navigate("petShop/$uuid") },
                    onOpenInventory = { navController.navigate("petInventory/$uuid") },
                    pendingReaction = petReaction,
                    onReactionConsumed = { backStackEntry.savedStateHandle["petReaction"] = null },
                )
            }
            backCardComposable(
                "petAdoption/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                SkinnedPetAdoptionScreen(onClose = { navController.popBackStack() })
            }
            // P9.3c 宠物商店 / 背包（按 characterUuid 定位宠物）。
            backCardComposable(
                "petShop/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                SkinnedPetShopScreen(onClose = { navController.popBackStack() })
            }
            backCardComposable(
                "petInventory/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                SkinnedPetInventoryScreen(
                    onClose = { navController.popBackStack() },
                    // pet-ui-2：把反应文案写回上一屏(详情页)的 savedStateHandle，返回时弹头顶气泡（最新一条胜出）。
                    onReaction = { text -> navController.previousBackStackEntry?.savedStateHandle?.set("petReaction", text) },
                )
            }
            // P9.2d 礼物店（M09）。无参=店内选对象；giftShop/{uuid}=带入角色（聊天等入口，暂未接）。
            backCardComposable("userWallet") {
                SkinnedUserWalletScreen(
                    onBack = { navController.popBackStack() },
                    onOpenGiftShop = { navController.navigate("giftShop") },
                    onOpenRedeemCode = { navController.navigate("redeemCode") },
                )
            }
            backCardComposable("redeemCode") {
                SkinnedRedeemCodeScreen(onClose = { navController.popBackStack() })
            }
            backCardComposable("giftShop") {
                SkinnedGiftShopScreen(
                    onClose = { navController.popBackStack() },
                    onNavigateToReaction = { recordUuid -> navController.navigate("giftReaction/$recordUuid?send=true") },
                )
            }
            backCardComposable(
                route = "giftShop/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                SkinnedGiftShopScreen(
                    onClose = { navController.popBackStack() },
                    onNavigateToReaction = { recordUuid -> navController.navigate("giftReaction/$recordUuid?send=true") },
                )
            }
            // 反应页：send=true 送礼流程（完成回上一页）；send=false 收礼盒回放（d-5，标准返回）。
            backCardComposable(
                route = "giftReaction/{recordUuid}?send={send}",
                arguments = listOf(
                    navArgument("recordUuid") { type = NavType.StringType },
                    navArgument("send") { type = NavType.BoolType; defaultValue = true },
                ),
            ) { backStackEntry ->
                val isSend = backStackEntry.arguments?.getBoolean("send") ?: true
                SkinnedGiftReactionScreen(
                    isSendFlow = isSend,
                    onFinish = { navController.popBackStack() },
                    onBack = { navController.popBackStack() },
                )
            }
            // 收礼盒（Profile 入口）：收到/送出分段；点卡分流 DIY 详情 / 收礼详情 / 反应回放。
            backCardComposable("giftBox") {
                SkinnedGiftBoxScreen(
                    onBack = { navController.popBackStack() },
                    onOpenReaction = { recordUuid -> navController.navigate("giftReaction/$recordUuid?send=false") },
                    onOpenReceived = { recordUuid -> navController.navigate("receivedGift/$recordUuid") },
                )
            }
            backCardComposable(
                route = "receivedGift/{recordUuid}",
                arguments = listOf(navArgument("recordUuid") { type = NavType.StringType }),
            ) {
                SkinnedReceivedGiftDetailScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable(TopDest.Profile.route) {
                SkinnedProfileScreen(
                    onEditProfile = { navController.navigate("userProfile/edit") },
                    onOpenUserMoments = { navController.navigate("userMoments") },
                    onOpenUserWallet = { navController.navigate("userWallet") },
                    onOpenGiftShop = { navController.navigate("giftShop") },
                    onOpenGiftBox = { navController.navigate("giftBox") },
                    onOpenSettings = { navController.navigate("settings") },
                    bottomContentPadding = AppBottomNavHeight,
                )
            }
            // 设置全面重构（四卷）：主页单源目录 + 独立「高级」页。
            backCardComposable("settings") {
                SkinnedSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenEntry = { entry ->
                        val route = when (entry) {
                            SettingsHubEntry.AI_SERVICE -> "apiConfig"
                            SettingsHubEntry.APPEARANCE -> "appearance"
                            SettingsHubEntry.REPLY -> "replyRuleSettings"
                            SettingsHubEntry.VOICE -> "voiceSettings"
                            SettingsHubEntry.OFFLINE_MEETING -> "immersiveSettings"
                            SettingsHubEntry.STICKERS -> "stickerManagement"
                            SettingsHubEntry.MEMORY -> "memorySettings"
                            SettingsHubEntry.WORLDBOOKS -> "worldBooks"
                            SettingsHubEntry.SCHEDULE -> "scheduleSettings"
                            SettingsHubEntry.GROWTH -> "growthSettings"
                            SettingsHubEntry.PET -> "petSettings"
                            SettingsHubEntry.WALLET -> "walletSettings"
                            SettingsHubEntry.WORLD -> "worldSettings"
                            SettingsHubEntry.DIARY -> "diarySettings"
                            SettingsHubEntry.MOMENTS -> "momentSettings"
                            SettingsHubEntry.STORY -> "storyGlobalSettings"
                            SettingsHubEntry.NOTIFICATIONS -> "notificationSettings"
                            SettingsHubEntry.CALENDAR -> "calendarAwareness"
                            SettingsHubEntry.BACKGROUND_RELIABILITY -> "backgroundReliability"
                            SettingsHubEntry.BACKUP -> "backup"
                            SettingsHubEntry.LANGUAGE -> null
                            SettingsHubEntry.ADVANCED -> "advancedSettings"
                            SettingsHubEntry.ABOUT -> "about"
                        }
                        if (route != null) navController.navigate(route)
                    },
                )
            }
            backCardComposable("advancedSettings") {
                SkinnedAdvancedSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPromptModules = { navController.navigate("promptModules") },
                    onOpenMemoryPrompts = { navController.navigate("memoryPrompts") },
                    onOpenContentFilter = { navController.navigate("contentFilterSettings") },
                    onOpenContextLog = { navController.navigate("contextLog") },
                    onOpenPerfCollect = { navController.navigate("perfCollect") },
                )
            }
            backCardComposable("memoryPrompts") {
                SkinnedMemoryPromptsScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("voiceSettings") {
                SkinnedVoiceSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTtsConfig = { navController.navigate("ttsConfig") },
                )
            }
            backCardComposable("apiConfig") { backStackEntry ->
                // 13.10b 扫码导入：扫码屏把识别出的二维码文本放回本条目的 savedStateHandle，回到此屏后预填表单。
                val scanned by backStackEntry.savedStateHandle
                    .getStateFlow<String?>(KEY_SCANNED_API_CONFIG, null)
                    .collectAsStateWithLifecycle()
                SkinnedApiConfigScreen(
                    onBack = { navController.popBackStack() },
                    onEditConfig = { uuid -> navController.navigate("apiConfig/edit/$uuid") },
                    onOpenScan = { navController.navigate("apiConfig/scan") },
                    scannedConfig = scanned,
                    onScanConsumed = { backStackEntry.savedStateHandle[KEY_SCANNED_API_CONFIG] = null },
                    onOpenApiFunctions = { navController.navigate("apiFunctions") },
                )
            }
            backCardComposable("apiConfig/scan") {
                SkinnedQrScanScreen(
                    onResult = { text ->
                        navController.previousBackStackEntry
                            ?.savedStateHandle?.set(KEY_SCANNED_API_CONFIG, text)
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            backCardComposable("apiFunctions") {
                SkinnedApiFunctionAssignmentScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("ttsConfig") {
                SkinnedTtsConfigurationScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("voiceCallSettings") {
                SkinnedVoiceCallSettingsScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable(
                route = "apiConfig/edit/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType }),
            ) { backStackEntry ->
                SkinnedApiConfigEditScreen(
                    uuid = backStackEntry.arguments?.getString("uuid").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            backCardComposable("userProfile/edit") {
                SkinnedUserProfileEditScreen(onClose = { navController.popBackStack() })
            }
            backCardComposable(
                route = "backup?focusFolder={focusFolder}",
                arguments = listOf(navArgument("focusFolder") { type = NavType.BoolType; defaultValue = false }),
            ) { entry ->
                // focusFolder=true（自动备份失败/目录丢失深链，P0-19）→ 进页自动开目录选择器重选；普通进入默认 false。
                SkinnedBackupScreen(
                    onBack = { navController.popBackStack() },
                    autoPickFolder = entry.arguments?.getBoolean("focusFolder") == true,
                )
            }
            backCardComposable("backgroundReliability") {
                SkinnedBackgroundReliabilityScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("calendarAwareness") {
                SkinnedCalendarAwarenessScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("notificationSettings") {
                SkinnedNotificationSettingsScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("immersiveSettings") {
                SkinnedImmersiveSettingsScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("appearance") {
                SkinnedAppearanceSettingsScreen(onBack = { navController.popBackStack() })
            }
            // SETTINGS_REORG D3：记忆设置 + 记忆提示词二合一 hub，沿用 memorySettings 路由。
            backCardComposable("memorySettings") {
                SkinnedMemoryHubScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("systemToggles") {
                SkinnedSystemTogglesScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("scheduleSettings") {
                SkinnedScheduleSettingsScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("petSettings") {
                SkinnedPetSettingsScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("walletSettings") {
                SkinnedWalletSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenNotifications = { navController.navigate("notificationSettings") },
                )
            }
            backCardComposable("growthSettings") {
                SkinnedGrowthSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenObservatory = { navController.navigate("kernelObservatory") },
                    onOpenNotifications = { navController.navigate("notificationSettings") },
                )
            }
            // 活人感内核卷零：开发者调试页（DEBUG 守卫在 Screen 内部，本文件只接线）。
            backCardComposable("kernelObservatory") {
                SkinnedKernelObservatoryScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("replyRuleSettings") {
                SkinnedReplyRuleSettingsScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("contentFilterSettings") {
                SkinnedContentFilterSettingsScreen(onBack = { navController.popBackStack() })
            }
            // 四期·图纸四：上下文日志——首页三分段（tab 可空：带参数时覆盖一次并记住）/ 角色页 / 条目页四种 / 回复全文 / 设置（两张脸选脸包装）。
            backCardComposable(
                route = "contextLog?tab={tab}",
                arguments = listOf(navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) {
                SkinnedContextLogHomeScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCharacter = { key -> navController.navigate("contextLog/character/${Uri.encode(key)}") },
                    onOpenEntry = { id, failed -> navController.navigate(if (failed) "contextLog/failure/$id" else "contextLog/entry/$id") },
                    onOpenSettings = { navController.navigate("contextLog/settings") },
                )
            }
            backCardComposable("contextLog/character/{key}") {
                SkinnedContextLogCharacterScreen(
                    onBack = { navController.popBackStack() },
                    onOpenEntry = { id, failed -> navController.navigate(if (failed) "contextLog/failure/$id" else "contextLog/entry/$id") },
                )
            }
            backCardComposable("contextLog/entry/{id}") {
                SkinnedContextLogEntryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenEntry = { id, failed -> navController.navigate(if (failed) "contextLog/failure/$id" else "contextLog/entry/$id") },
                    onOpenMap = { id -> navController.navigate("contextLog/map/$id") },
                    onOpenSent = { id -> navController.navigate("contextLog/sent/$id") },
                    onOpenReply = { id -> navController.navigate("contextLog/reply/$id") },
                )
            }
            backCardComposable("contextLog/map/{id}") {
                SkinnedContextLogMapScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSaver = { navController.navigate("promptModules") },
                )
            }
            backCardComposable("contextLog/sent/{id}") {
                SkinnedContextLogSentScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLogSettings = { navController.navigate("contextLog/settings") },
                    onOpenMap = { id -> navController.navigate("contextLog/map/$id") },
                )
            }
            backCardComposable("contextLog/failure/{id}") {
                SkinnedContextLogFailureScreen(
                    onBack = { navController.popBackStack() },
                    onOpenApiSettings = { navController.navigate("apiConfig") },
                )
            }
            backCardComposable("contextLog/reply/{id}") {
                SkinnedContextLogReplyScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("contextLog/settings") {
                SkinnedContextLogSettingsScreen(onBack = { navController.popBackStack() })
            }
            // 性能采集（性能专项卷 0）：手机自采性能数字 + 一键导出报告。设置 ⑧「数据与诊断」组·高级门后。
            backCardComposable("perfCollect") {
                SkinnedPerfCollectScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("about") {
                SkinnedAboutScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAgreement = { navController.navigate("agreementView") },
                )
            }
            backCardComposable("agreementView") {
                SkinnedAgreementViewScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable("promptModules") {
                SkinnedPromptModuleSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenImmersiveSettings = { navController.navigate("immersiveSettings") },
                    onOpenContextLog = { navController.navigate("contextLog?tab=trend") },
                )
            }
            // P7.1 日记本（M07）。读侧 7.1.4：列表 + 详情；写侧 ComposeDiaryScreen → 7.1.5（撰写/编辑暂用占位）。
            backCardComposable("diary") {
                SkinnedDiaryListScreen(
                    onBack = { navController.popBackStack() },
                    onCompose = { navController.navigate("diaryCompose") },
                    onOpenEntry = { uuid -> navController.navigate("diary/$uuid") },
                )
            }
            backCardComposable(
                route = "diary/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType }),
            ) {
                SkinnedDiaryDetailScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { uuid -> navController.navigate("diaryCompose/$uuid") },
                )
            }
            // 写侧（7.1.5）：撰写 / 编辑共用 ComposeDiaryScreen（编辑经 {uuid}），保存/放弃后返回。
            backCardComposable("diaryCompose") {
                SkinnedComposeDiaryScreen(
                    onClose = { navController.popBackStack() },
                    onNavigateToApiConfig = { navController.navigate("apiConfig") },
                )
            }
            backCardComposable(
                route = "diaryCompose/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType }),
            ) {
                SkinnedComposeDiaryScreen(
                    onClose = { navController.popBackStack() },
                    onNavigateToApiConfig = { navController.navigate("apiConfig") },
                )
            }
            backCardComposable("diarySettings") {
                SkinnedDiarySettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenWritingRules = { navController.navigate("diaryWritingRules") },
                )
            }
            backCardComposable("diaryWritingRules") {
                SkinnedDiaryPromptSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPreviewMine = { navController.navigate("diaryPromptPreview/mine") },
                    onOpenPreviewExchange = { navController.navigate("diaryPromptPreview/exchange") },
                )
            }
            backCardComposable(
                route = "diaryPromptPreview/{section}",
                arguments = listOf(navArgument("section") { type = NavType.StringType }),
            ) {
                SkinnedDiaryPromptPreviewScreen(onBack = { navController.popBackStack() })
            }
            backCardComposable(
                route = "promptModules/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                SkinnedPromptModuleSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenImmersiveSettings = { navController.navigate("immersiveSettings") },
                    onOpenContextLog = { navController.navigate("contextLog?tab=trend") },
                )
            }
            backCardComposable(
                route = "chat/{conversationUuid}",
                arguments = listOf(navArgument("conversationUuid") { type = NavType.StringType }),
            ) {
                if (LocalAppSkin.current == AppSkin.LIULI) LiuliChatScreen( // 琉璃第二张脸·选脸（卷二A 图纸 §2.2·两分支实参逐字相同）
                    onBack = { navController.popBackStack() },
                    onOpenProfile = { characterUuid -> navController.navigate("characterProfile/$characterUuid") },
                    onOpenStickerManagement = { navController.navigate("stickerManagement") },
                    onOpenVoiceCall = { characterUuid -> navController.navigate("voiceCall/$characterUuid") },
                    onOpenCharacterVoiceSettings = { uuid -> navController.navigate("character/edit/$uuid?focusVoice=true") },
                    onOpenTtsConfig = { navController.navigate("ttsConfig") },
                    onOpenWorldAt = { spec -> WorldFocusEntry.set(spec); navController.navigate("world") { launchSingleTop = true } },
                    onOpenPromises = { uuid -> navController.navigate("promises/$uuid") },
                ) else ChatScreen(
                    onBack = { navController.popBackStack() },
                    onOpenProfile = { characterUuid -> navController.navigate("characterProfile/$characterUuid") },
                    onOpenStickerManagement = { navController.navigate("stickerManagement") },
                    onOpenVoiceCall = { characterUuid -> navController.navigate("voiceCall/$characterUuid") },
                    // VU1 拨号门深链：无音色 → 角色编辑·语音区（focusVoice 滚动定位）/ 缺全局配置 → 全局语音设置。
                    onOpenCharacterVoiceSettings = { uuid -> navController.navigate("character/edit/$uuid?focusVoice=true") },
                    onOpenTtsConfig = { navController.navigate("ttsConfig") },
                    // W13：状态行胶囊点击 → 存聚焦意图 + 进世界屏（WorldViewModel init 消费落点·图纸 §3.6）。
                    onOpenWorldAt = { spec -> WorldFocusEntry.set(spec); navController.navigate("world") { launchSingleTop = true } },
                    // 约定记账提示点整条 → 该角色的「我们的约定」账本页（图纸 2026-09-06）。
                    onOpenPromises = { uuid -> navController.navigate("promises/$uuid") },
                )
            }
            backCardComposable(
                route = "voiceCall/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                VoiceCallScreen(onCallFinished = { navController.popBackStack() })
            }
            backCardComposable("character/new") {
                SkinnedCharacterEditScreen(
                    onCancel = { navController.popBackStack() },
                    onSaved = { conversationUuid ->
                        if (conversationUuid != null) {
                            navController.navigate("chat/$conversationUuid") {
                                popUpTo(TopDest.Chats.route)
                            }
                        } else {
                            navController.popBackStack()
                        }
                    },
                )
            }
            backCardComposable(
                route = "character/edit/{characterUuid}?focusVoice={focusVoice}",
                arguments = listOf(
                    navArgument("characterUuid") { type = NavType.StringType },
                    // VU1 深链：可选 query·缺省 false → 既有无 query 调用点全兼容（B5）。
                    navArgument("focusVoice") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { backStackEntry ->
                SkinnedCharacterEditScreen(
                    onCancel = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                    onEditModules = { uuid -> navController.navigate("promptModules/$uuid") },
                    onOpenOfflineMeetings = { uuid -> navController.navigate("offlineMeetings/$uuid") },
                    onOpenWorldBooks = { navController.navigate("worldBooks") },
                    focusVoiceSection = backStackEntry.arguments?.getBoolean("focusVoice") == true,
                )
            }
            // 14.1 角色资料页（只读·点亮成长智能）。入口=联系人头像 / 聊天顶栏标题。
            backCardComposable(
                route = "characterProfile/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                SkinnedCharacterProfileScreen(
                    onBack = { navController.popBackStack() },
                    onEditCharacter = { uuid -> navController.navigate("character/edit/$uuid") },
                    onOpenOfflineMeetings = { uuid -> navController.navigate("offlineMeetings/$uuid") },
                    onOpenSchedule = { uuid -> navController.navigate("scheduleFullDay/$uuid") },
                    onOpenPromises = { uuid -> navController.navigate("promises/$uuid") },
                    onOpenStarfield = { uuid -> navController.navigate("starfield/$uuid") },
                    onOpenOurDays = { uuid -> navController.navigate("ourDays?character=$uuid") },
                    onEditMemory = { uuid -> navController.navigate("memoryEdit/$uuid") },
                )
            }
            // 记忆手动编辑（资料页共同记忆卡入口·图纸 2026-09-01 件③）。
            backCardComposable(
                route = "memoryEdit/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                SkinnedMemoryEditScreen(onClose = { navController.popBackStack() })
            }
            // 记忆星空（资料页「故事」Tab 入口卡目标·全屏可漫游星空·转场走 NavHost 全局默认·J5）。
            backCardComposable(
                route = "starfield/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                StarfieldScreen(
                    onBack = { navController.popBackStack() },
                    onOpenMeetings = { uuid -> navController.navigate("offlineMeetings/$uuid") },
                    onOpenPromises = { uuid -> navController.navigate("promises/$uuid") },
                )
            }
            backCardComposable(
                route = "offlineMeetings/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                SkinnedOfflineMeetingMemoryScreen(onBack = { navController.popBackStack() })
            }
            // 记忆改造三期：角色资料页「我们的约定」账本子页。
            backCardComposable(
                route = "promises/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                SkinnedPromiseLedgerScreen(onBack = { navController.popBackStack() })
            }
            // 14.2 全天行程视图（资料页日程卡「查看全天行程」目标）。
            backCardComposable(
                route = "scheduleFullDay/{characterUuid}?date={date}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }, navArgument("date") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) {
                SkinnedScheduleFullDayScreen(
                    onBack = { navController.popBackStack() },
                    onOpenChat = { conversationUuid -> navController.navigate("chat/$conversationUuid") },
                )
            }
            // 「我们的日子」卷三（图纸 §3.6）：日历页（两入口共用·可选预选角色 + 日期）/ 一天的页（"all" = 全部模式）。
            backCardComposable(
                route = "ourDays?character={character}&date={date}",
                arguments = listOf(
                    navArgument("character") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("date") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) {
                SkinnedOurDaysScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDay = { uuid, dayKey -> navController.navigate("ourDays/day/$uuid/$dayKey") },
                )
            }
            backCardComposable(
                route = "ourDays/day/{characterUuid}/{dayKey}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }, navArgument("dayKey") { type = NavType.StringType }),
            ) {
                SkinnedOurDayPageScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDay = { uuid, dayKey -> navController.navigate("ourDays/day/$uuid/$dayKey") },
                    onOpenMeetings = { uuid -> navController.navigate("offlineMeetings/$uuid") },
                    onOpenPromises = { uuid -> navController.navigate("promises/$uuid") },
                    onOpenMoments = { uuid, dayKey -> navController.navigate("dayMoments/$uuid/$dayKey") },
                    onOpenDiary = { uuid -> navController.navigate("diary/$uuid") },
                    onOpenSchedule = { uuid, dayKey -> navController.navigate("scheduleFullDay/$uuid?date=$dayKey") },
                )
            }
            backCardComposable(
                route = "dayMoments/{characterUuid}/{dayKey}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }, navArgument("dayKey") { type = NavType.StringType }),
            ) {
                SkinnedDayMomentsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPost = { uuid -> navController.navigate("moment/$uuid") },
                )
            }
        }
        } // end CompositionLocalProvider（预测返回卡片）
        } // end LiuliHomeHost（NavHost + 悬浮底栏叠加层）
    }
}
