package com.situ.aichat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.situ.aichat.R
import com.situ.aichat.ui.character.MemoryEditScreen
import com.situ.aichat.ui.designsystem.AppBottomNav
import com.situ.aichat.ui.designsystem.AppBottomNavHeight
import com.situ.aichat.ui.designsystem.AppBottomNavItem
import com.situ.aichat.ui.designsystem.AppNavIcons
import com.situ.aichat.ui.character.CharacterEditScreen
import com.situ.aichat.ui.character.CharacterProfileScreen
import com.situ.aichat.ui.offline.OfflineMeetingMemoryScreen
import com.situ.aichat.ui.promise.PromiseLedgerScreen
import com.situ.aichat.ui.schedule.ScheduleFullDayScreen
import com.situ.aichat.ui.starfield.StarfieldScreen
import com.situ.aichat.ui.backup.BackupScreen
import com.situ.aichat.ui.chat.ArchivedChatsScreen
import com.situ.aichat.ui.chat.ChatListScreen
import com.situ.aichat.ui.chat.ChatScreen
import com.situ.aichat.ui.voicecall.VoiceCallScreen
import com.situ.aichat.ui.contacts.ContactsScreen
import com.situ.aichat.ui.diary.ComposeDiaryScreen
import com.situ.aichat.ui.diary.DiaryDetailScreen
import com.situ.aichat.ui.diary.DiaryListScreen
import com.situ.aichat.ui.diary.DiarySettingsScreen
import com.situ.aichat.ui.gift.GiftBoxScreen
import com.situ.aichat.ui.gift.GiftReactionScreen
import com.situ.aichat.ui.gift.GiftShopScreen
import com.situ.aichat.ui.gift.ReceivedGiftDetailScreen
import com.situ.aichat.ui.moments.ComposeMomentScreen
import com.situ.aichat.ui.moments.MomentAuthorScreen
import com.situ.aichat.ui.moments.MomentDetailScreen
import com.situ.aichat.ui.moments.MomentNotificationListScreen
import com.situ.aichat.ui.moments.MomentSettingsScreen
import com.situ.aichat.ui.moments.MomentsHubScreen
import com.situ.aichat.ui.moments.MomentsListScreen
import com.situ.aichat.ui.pet.PetAdoptionScreen
import com.situ.aichat.ui.pet.PetDetailScreen
import com.situ.aichat.ui.pet.PetInventoryScreen
import com.situ.aichat.ui.pet.PetShopScreen
import com.situ.aichat.ui.pet.PetListScreen
import com.situ.aichat.ui.profile.ProfileScreen
import com.situ.aichat.ui.profile.UserProfileEditScreen
import com.situ.aichat.ui.promptmodule.PromptModuleSettingsScreen
import com.situ.aichat.ui.screens.PlaceholderScreen
import com.situ.aichat.ui.sticker.StickerImportScreen
import com.situ.aichat.ui.sticker.StickerManagementScreen
import com.situ.aichat.ui.story.StoryArchiveAllScreen
import com.situ.aichat.ui.story.StoryArchiveDetailScreen
import com.situ.aichat.ui.story.StoryBookHubScreen
import com.situ.aichat.ui.story.StoryBookshelfScreen
import com.situ.aichat.ui.worldbook.WorldBookDetailScreen
import com.situ.aichat.ui.worldbook.WorldBookEntryEditScreen
import com.situ.aichat.ui.worldbook.WorldBookSettingsScreen
import com.situ.aichat.ui.world.WorldScreen
import com.situ.aichat.ui.worldbook.WorldBookShelfScreen
import com.situ.aichat.ui.story.StoryChapterListScreen
import com.situ.aichat.ui.story.StoryCreationScreen
import com.situ.aichat.ui.story.StoryFieldEditorScreen
import com.situ.aichat.ui.story.StoryReaderScreen
import com.situ.aichat.ui.story.StoryTemplateWallScreen
import com.situ.aichat.ui.settings.AboutScreen
import com.situ.aichat.ui.settings.AgreementViewScreen
import com.situ.aichat.ui.settings.ApiConfigEditScreen
import com.situ.aichat.ui.settings.ApiConfigScreen
import com.situ.aichat.ui.settings.QrScanScreen
import com.situ.aichat.ui.contextlog.ContextLogDetailScreen
import com.situ.aichat.ui.contextlog.ContextLogListScreen
import com.situ.aichat.ui.contextlog.ContextLogSegmentsScreen
import com.situ.aichat.ui.contextlog.ContextLogSettingsScreen
import com.situ.aichat.ui.perflog.PerfCollectScreen
import com.situ.aichat.ui.contextlog.ContextLogTextScreen
import com.situ.aichat.ui.settings.AppearanceSettingsScreen
import com.situ.aichat.ui.settings.ContentFilterSettingsScreen
import com.situ.aichat.ui.settings.GrowthSettingsScreen
import com.situ.aichat.ui.settings.ReplyRuleSettingsScreen
import com.situ.aichat.ui.settings.MemoryHubScreen
import com.situ.aichat.ui.wallet.RedeemCodeScreen
import com.situ.aichat.ui.wallet.UserWalletScreen
import com.situ.aichat.ui.settings.SystemTogglesScreen
import com.situ.aichat.ui.settings.ApiFunctionAssignmentScreen
import com.situ.aichat.ui.settings.BackgroundReliabilityScreen
import com.situ.aichat.ui.settings.ReliabilityPromptDialog
import com.situ.aichat.ui.settings.CalendarAwarenessScreen
import com.situ.aichat.ui.settings.ImmersiveSettingsScreen
import com.situ.aichat.ui.settings.NotificationSettingsScreen
import com.situ.aichat.ui.settings.SettingsScreen
import com.situ.aichat.ui.settings.StoryGlobalSettingsScreen
import com.situ.aichat.ui.settings.WorldSettingsScreen
import com.situ.aichat.world.WorldFocusEntry
import com.situ.aichat.ui.settings.TtsConfigurationScreen
import com.situ.aichat.ui.settings.VoiceCallSettingsScreen
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
        val navSlide = 300
        val navFade = 160
        // 聊天页转场（2026-07-06 拍板·取代旧「壁纸沉浸重构①整页 fade」）：回归 iOS 式 push——聊天页从右整页
        // 滑入、底页 1/4 视差左推、返回镜像。与其他详情页唯一差别是**不掺 fade**：聊天页全屏不透明（壁纸或底色），
        // 掺 fade 会让壁纸半透明透出底页（=旧「横滑割裂」真凶）；壁纸铺满含状态栏后、是页面一部分，随整页同步滑。
        // 冷加载晚到的壁纸淡入兜底在 ChatScreen 壁纸层。
        fun isChatRoute(route: String?) = route?.startsWith("chat/") == true
        // A0·叠加层变体：Box 容纳 NavHost + 悬浮底栏叠加层。NavHost 仍按系统栏 inset 垫（保留 consume/E3），
        // 但底栏不再占 Scaffold 的 bottomBar 槽 → innerPadding 不随底栏显隐变化 → 详情页可用高度恒定（无沉降）。
        Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = TopDest.Chats.route,
            // 壁纸全屏沉浸重构②（参照 RikkaHub·2026-06-28）：NavHost 不再垫系统栏 inset、也不 consume——
            // 各屏自管 inset（M3 Scaffold/TopAppBar 默认 contentWindowInsets 自垫；4 个沉浸屏背景 fillMaxSize
            // 自然铺满系统栏后、顶/底栏各自 statusBarsPadding/navigationBarsPadding）。取代旧 E3 的 padding+consume。
            modifier = Modifier.fillMaxSize(),
            // 13.3 / nav-shell-1：详情页 push/pop 从右滑入 / 滑出（带视差，≈ iOS NavigationStack；配合 manifest 的
            // enableOnBackInvokedCallback，预测式返回手势会跟手预览上一屏）；底部 tab 之间切换不横滑、只交叉淡入（≈ iOS TabView）。
            enterTransition = {
                val from = initialState.destination.route
                val to = targetState.destination.route
                val tab = (from ?: "") in topLevelRoutes && (to ?: "") in topLevelRoutes
                when {
                    isChatRoute(from) || isChatRoute(to) -> slideInHorizontally(tween(navSlide)) { it }
                    tab -> fadeIn(tween(navFade))
                    else -> slideInHorizontally(tween(navSlide)) { it } + fadeIn(tween(navSlide))
                }
            },
            exitTransition = {
                val from = initialState.destination.route
                val to = targetState.destination.route
                val tab = (from ?: "") in topLevelRoutes && (to ?: "") in topLevelRoutes
                when {
                    isChatRoute(from) || isChatRoute(to) -> slideOutHorizontally(tween(navSlide)) { -it / 4 }
                    tab -> fadeOut(tween(navFade))
                    else -> slideOutHorizontally(tween(navSlide)) { -it / 4 } + fadeOut(tween(navSlide))
                }
            },
            popEnterTransition = {
                val from = initialState.destination.route
                val to = targetState.destination.route
                val tab = (from ?: "") in topLevelRoutes && (to ?: "") in topLevelRoutes
                when {
                    isChatRoute(from) || isChatRoute(to) -> slideInHorizontally(tween(navSlide)) { -it / 4 }
                    tab -> fadeIn(tween(navFade))
                    else -> slideInHorizontally(tween(navSlide)) { -it / 4 } + fadeIn(tween(navSlide))
                }
            },
            popExitTransition = {
                val from = initialState.destination.route
                val to = targetState.destination.route
                val tab = (from ?: "") in topLevelRoutes && (to ?: "") in topLevelRoutes
                when {
                    isChatRoute(from) || isChatRoute(to) -> slideOutHorizontally(tween(navSlide)) { it }
                    tab -> fadeOut(tween(navFade))
                    else -> slideOutHorizontally(tween(navSlide)) { it } + fadeOut(tween(navSlide))
                }
            },
        ) {
            composable(TopDest.Chats.route) {
                // A1：内容铺满整窗、延伸到半透底栏后；底部留白下沉进列表 contentPadding，末条仍能滑到栏上方（过渡丝滑化·A1）。
                ChatListScreen(
                    // 批4 4-7：launchSingleTop 防快速双击同一会话压两个同会话页/双 VM。
                    onOpenChat = { conversationUuid -> navController.navigate("chat/$conversationUuid") { launchSingleTop = true } },
                    onCreateCharacter = { navController.navigate("character/new") },
                    onOpenArchived = { navController.navigate("archivedChats") },
                    bottomContentPadding = AppBottomNavHeight,
                )
            }
            composable("archivedChats") {
                ArchivedChatsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenChat = { conversationUuid -> navController.navigate("chat/$conversationUuid") },
                )
            }
            composable(TopDest.Contacts.route) {
                ContactsScreen(
                    onOpenChat = { conversationUuid -> navController.navigate("chat/$conversationUuid") },
                    onCreateCharacter = { navController.navigate("character/new") },
                    onEditCharacter = { uuid -> navController.navigate("character/edit/$uuid") },
                    onOpenProfile = { uuid -> navController.navigate("characterProfile/$uuid") },
                    bottomContentPadding = AppBottomNavHeight,
                )
            }
            // P7.2.7 朋友圈（M06）：枢纽（Tab）→ 信息流 → 发布。详情/通知列表/角色动态 → 7.2.8（现路由占位）；
            // 故事 → P11、宠物 → P8（占位）。
            composable(TopDest.Moments.route) {
                MomentsHubScreen(
                    onOpenFeed = { navController.navigate("momentsFeed") },
                    onOpenDiary = { navController.navigate("diary") },
                    onOpenStory = { navController.navigate("momentsStory") },
                    onOpenWorld = { navController.navigate("world") { launchSingleTop = true } },
                    bottomContentPadding = AppBottomNavHeight,
                    onOpenPet = { characterUuid -> navController.navigate("petDetail/$characterUuid") { launchSingleTop = true } }, // W12.5 信息条宠物段直达
                )
            }
            composable("momentsFeed") {
                MomentsListScreen(
                    onBack = { navController.popBackStack() },
                    onCompose = { navController.navigate("momentCompose") },
                    onOpenPost = { uuid -> navController.navigate("moment/$uuid") },
                    onOpenNotifications = { navController.navigate("momentNotifications") },
                    onOpenCharacterMoments = { uuid -> navController.navigate("characterMoments/$uuid") },
                )
            }
            composable(
                route = "characterMoments/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                MomentAuthorScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPost = { uuid -> navController.navigate("moment/$uuid") },
                )
            }
            composable("userMoments") {
                MomentAuthorScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPost = { uuid -> navController.navigate("moment/$uuid") },
                )
            }
            composable("momentCompose") {
                ComposeMomentScreen(onClose = { navController.popBackStack() })
            }
            composable(
                route = "moment/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType }),
            ) {
                MomentDetailScreen(onBack = { navController.popBackStack() })
            }
            composable("momentNotifications") {
                MomentNotificationListScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPost = { uuid -> navController.navigate("moment/$uuid") },
                )
            }
            composable("momentSettings") {
                MomentSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("stickerManagement") {
                StickerManagementScreen(
                    onBack = { navController.popBackStack() },
                    onImport = { navController.navigate("stickerImport") },
                )
            }
            composable("stickerImport") {
                StickerImportScreen(onBack = { navController.popBackStack() })
            }
            // P11 互动故事（11.1h）：朋友圈枢纽 → 书架 → 章节列表 → 阅读器。阅读器=11.1i、创建/设定=11.1j（暂占位）。
            composable("momentsStory") {
                StoryBookshelfScreen(
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
            composable(
                route = "storyArchive/{storyId}",
                arguments = listOf(navArgument("storyId") { type = NavType.StringType }),
            ) {
                StoryArchiveDetailScreen(onBack = { navController.popBackStack() })
            }
            // ST8 结局档案全览：档案区「全部 ›」→ 全部已完结封面网格。
            composable("storyArchiveAll") {
                StoryArchiveAllScreen(
                    onBack = { navController.popBackStack() },
                    onOpenArchive = { storyId -> navController.navigate("storyArchive/$storyId") },
                )
            }
            // ST7b 创建两层流：模板墙（默认入口）→ 开书 sheet 直开 / 尾卡·改一改再开 → 高级自定义（storyCreation）。
            composable("storyTemplateWall") {
                StoryTemplateWallScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCustom = { templateId ->
                        if (templateId == null) navController.navigate("storyCreation")
                        else navController.navigate("storyCreation?templateId=$templateId")
                    },
                    // 开书成功回书架（新故事以「生成中」卡片出现·跳过模板墙）。
                    onCreated = { navController.popBackStack("momentsStory", inclusive = false) },
                )
            }
            composable(
                route = "story/{storyId}",
                arguments = listOf(navArgument("storyId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val storyId = backStackEntry.arguments?.getString("storyId").orEmpty()
                StoryChapterListScreen(
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
            composable(
                route = "storyReader/{chapterId}",
                arguments = listOf(navArgument("chapterId") { type = NavType.StringType }),
            ) {
                StoryReaderScreen(
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
            composable(
                route = "storyCreation?templateId={templateId}",
                arguments = listOf(navArgument("templateId") { type = NavType.StringType; defaultValue = "" }),
            ) {
                // templateId 非空 = 从开书 sheet「改一改再开」带模板预填值进来（VM init 按 arg 起底表单）。
                StoryCreationScreen(
                    onBack = { navController.popBackStack() },
                    // 创建后回书架（新故事以「生成中」卡片出现，首章在前台服务里生成·跳过模板墙）。
                    onCreated = { navController.popBackStack("momentsStory", inclusive = false) },
                )
            }
            composable(
                route = "storySettings/{storyId}",
                arguments = listOf(navArgument("storyId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val storyId = backStackEntry.arguments?.getString("storyId").orEmpty()
                // 卷二 D-10：书页取代旧故事设定屏（路由名与两处调用点复用，入口零改）。
                StoryBookHubScreen(
                    onBack = { navController.popBackStack() },
                    onStoryGone = { navController.popBackStack("momentsStory", inclusive = false) },
                    onOpenChapter = { chapterId -> navController.navigate("storyReader/$chapterId") },
                    onOpenField = { key -> navController.navigate("storyFieldEditor/$storyId/$key") },
                    onOpenGlobalSettings = { navController.navigate("storyGlobalSettings") },
                )
            }
            // 卷二 §11 统一编辑页：书页两 Tab 的全部文本设定（15 字段 + 全局忌口变体）共用这一个全屏长相。
            composable(
                route = "storyFieldEditor/{storyId}/{fieldKey}",
                arguments = listOf(
                    navArgument("storyId") { type = NavType.StringType },
                    navArgument("fieldKey") { type = NavType.StringType },
                ),
            ) { StoryFieldEditorScreen(onBack = { navController.popBackStack() }) }
            composable("storyGlobalSettings") { // 卷四 §4.2 全局创作偏好子屏（storyId 段 "-" 占位·全局分支不读它）
                StoryGlobalSettingsScreen({ navController.popBackStack() }, { key -> navController.navigate("storyFieldEditor/-/$key") })
            }
            // 宠物（M11）：枢纽列表 → 详情（按是否有宠物显示详情或领养进度）→ 领养。
            composable("momentsPet") {
                PetListScreen(
                    onOpenPet = { uuid -> navController.navigate("petDetail/$uuid") },
                    onBack = { navController.popBackStack() },
                )
            }
            // W9a 世界系统星球层：全屏 GL 星球（非 topRoute → 底栏自然隐藏）。入口=动态页临时行 / 世界通知深链。
            composable("world") {
                WorldScreen(
                    onBack = { navController.popBackStack() },
                    onOpenChat = { conversationUuid -> navController.navigate("chat/$conversationUuid") { launchSingleTop = true } },
                    onOpenPet = { characterUuid -> navController.navigate("petDetail/$characterUuid") { launchSingleTop = true } },
                    onOpenPetAdoption = { characterUuid -> navController.navigate("petAdoption/$characterUuid") { launchSingleTop = true } }, // W12.5 蛋巢「迎接」→ 现有领养三步流
                )
            }
            // 世界系统 W13 设置二级页（图纸 §4.3/§4.4）。
            composable("worldSettings") {
                WorldSettingsScreen(onBack = { navController.popBackStack() })
            }
            // 世界书 WB7（UI 名「设定集」）：书架 → 书详情；条目编辑器随 WB7b、触发设置随 WB7c。
            composable("worldBooks") {
                WorldBookShelfScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBook = { bookUuid -> navController.navigate("worldBook/$bookUuid") },
                    onOpenSettings = { navController.navigate("worldBookSettings") },
                )
            }
            composable("worldBookSettings") {
                WorldBookSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenMemorySettings = { navController.navigate("memorySettings") },
                )
            }
            composable(
                "worldBook/{bookUuid}",
                arguments = listOf(navArgument("bookUuid") { type = NavType.StringType }),
            ) { backStackEntry ->
                val worldBookUuid = backStackEntry.arguments?.getString("bookUuid").orEmpty()
                WorldBookDetailScreen(
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
            composable(
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
                WorldBookEntryEditScreen(onDone = { navController.popBackStack() })
            }
            composable(
                "petDetail/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) { backStackEntry ->
                val uuid = backStackEntry.arguments?.getString("characterUuid").orEmpty()
                // pet-ui-2：背包回传的反应文案（savedStateHandle 跨路由），用于头顶气泡。
                val petReaction by backStackEntry.savedStateHandle
                    .getStateFlow<String?>("petReaction", null)
                    .collectAsStateWithLifecycle()
                PetDetailScreen(
                    onBack = { navController.popBackStack() },
                    onAdopt = { navController.navigate("petAdoption/$uuid") },
                    onOpenShop = { navController.navigate("petShop/$uuid") },
                    onOpenInventory = { navController.navigate("petInventory/$uuid") },
                    pendingReaction = petReaction,
                    onReactionConsumed = { backStackEntry.savedStateHandle["petReaction"] = null },
                )
            }
            composable(
                "petAdoption/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                PetAdoptionScreen(onClose = { navController.popBackStack() })
            }
            // P9.3c 宠物商店 / 背包（按 characterUuid 定位宠物）。
            composable(
                "petShop/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                PetShopScreen(onClose = { navController.popBackStack() })
            }
            composable(
                "petInventory/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                PetInventoryScreen(
                    onClose = { navController.popBackStack() },
                    // pet-ui-2：把反应文案写回上一屏(详情页)的 savedStateHandle，返回时弹头顶气泡（最新一条胜出）。
                    onReaction = { text -> navController.previousBackStackEntry?.savedStateHandle?.set("petReaction", text) },
                )
            }
            // P9.2d 礼物店（M09）。无参=店内选对象；giftShop/{uuid}=带入角色（聊天等入口，暂未接）。
            composable("userWallet") {
                UserWalletScreen(
                    onBack = { navController.popBackStack() },
                    onOpenGiftShop = { navController.navigate("giftShop") },
                    onOpenRedeemCode = { navController.navigate("redeemCode") },
                )
            }
            composable("redeemCode") {
                RedeemCodeScreen(onClose = { navController.popBackStack() })
            }
            composable("giftShop") {
                GiftShopScreen(
                    onClose = { navController.popBackStack() },
                    onNavigateToReaction = { recordUuid -> navController.navigate("giftReaction/$recordUuid?send=true") },
                )
            }
            composable(
                route = "giftShop/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                GiftShopScreen(
                    onClose = { navController.popBackStack() },
                    onNavigateToReaction = { recordUuid -> navController.navigate("giftReaction/$recordUuid?send=true") },
                )
            }
            // 反应页：send=true 送礼流程（完成回上一页）；send=false 收礼盒回放（d-5，标准返回）。
            composable(
                route = "giftReaction/{recordUuid}?send={send}",
                arguments = listOf(
                    navArgument("recordUuid") { type = NavType.StringType },
                    navArgument("send") { type = NavType.BoolType; defaultValue = true },
                ),
            ) { backStackEntry ->
                val isSend = backStackEntry.arguments?.getBoolean("send") ?: true
                GiftReactionScreen(
                    isSendFlow = isSend,
                    onFinish = { navController.popBackStack() },
                    onBack = { navController.popBackStack() },
                )
            }
            // 收礼盒（Profile 入口）：收到/送出分段；点卡分流 DIY 详情 / 收礼详情 / 反应回放。
            composable("giftBox") {
                GiftBoxScreen(
                    onBack = { navController.popBackStack() },
                    onOpenReaction = { recordUuid -> navController.navigate("giftReaction/$recordUuid?send=false") },
                    onOpenReceived = { recordUuid -> navController.navigate("receivedGift/$recordUuid") },
                )
            }
            composable(
                route = "receivedGift/{recordUuid}",
                arguments = listOf(navArgument("recordUuid") { type = NavType.StringType }),
            ) {
                ReceivedGiftDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(TopDest.Profile.route) {
                ProfileScreen(
                    onEditProfile = { navController.navigate("userProfile/edit") },
                    onOpenUserMoments = { navController.navigate("userMoments") },
                    onOpenUserWallet = { navController.navigate("userWallet") },
                    onOpenGiftShop = { navController.navigate("giftShop") },
                    onOpenGiftBox = { navController.navigate("giftBox") },
                    onOpenSettings = { navController.navigate("settings") },
                    bottomContentPadding = AppBottomNavHeight,
                )
            }
            // Fable5「我」页重构：独立设置页（从 ProfileScreen 抽出·9 组重分组）。
            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenApiConfig = { navController.navigate("apiConfig") },
                    onOpenApiFunctions = { navController.navigate("apiFunctions") },
                    onOpenMemorySettings = { navController.navigate("memorySettings") },
                    onOpenSystemToggles = { navController.navigate("systemToggles") },
                    onOpenAppearance = { navController.navigate("appearance") },
                    onOpenNotificationSettings = { navController.navigate("notificationSettings") },
                    onOpenImmersiveSettings = { navController.navigate("immersiveSettings") },
                    onOpenStickerManagement = { navController.navigate("stickerManagement") },
                    onOpenGrowthSettings = { navController.navigate("growthSettings") },
                    onOpenReplyRules = { navController.navigate("replyRuleSettings") },
                    onOpenContentFilter = { navController.navigate("contentFilterSettings") },
                    onOpenCalendarAwareness = { navController.navigate("calendarAwareness") },
                    onOpenWorldBooks = { navController.navigate("worldBooks") },
                    onOpenPromptModules = { navController.navigate("promptModules") },
                    onOpenTtsConfig = { navController.navigate("ttsConfig") },
                    onOpenVoiceCallSettings = { navController.navigate("voiceCallSettings") },
                    onOpenDiarySettings = { navController.navigate("diarySettings") },
                    onOpenMomentSettings = { navController.navigate("momentSettings") },
                    onOpenStoryGlobalSettings = { navController.navigate("storyGlobalSettings") },
                    onOpenWorldSettings = { navController.navigate("worldSettings") },
                    onOpenBackup = { navController.navigate("backup") },
                    onOpenBackgroundReliability = { navController.navigate("backgroundReliability") },
                    onOpenContextLog = { navController.navigate("contextLog") },
                    onOpenPerfCollect = { navController.navigate("perfCollect") },
                    onOpenAbout = { navController.navigate("about") },
                )
            }
            composable("apiConfig") { backStackEntry ->
                // 13.10b 扫码导入：扫码屏把识别出的二维码文本放回本条目的 savedStateHandle，回到此屏后预填表单。
                val scanned by backStackEntry.savedStateHandle
                    .getStateFlow<String?>(KEY_SCANNED_API_CONFIG, null)
                    .collectAsStateWithLifecycle()
                ApiConfigScreen(
                    onBack = { navController.popBackStack() },
                    onEditConfig = { uuid -> navController.navigate("apiConfig/edit/$uuid") },
                    onOpenScan = { navController.navigate("apiConfig/scan") },
                    scannedConfig = scanned,
                    onScanConsumed = { backStackEntry.savedStateHandle[KEY_SCANNED_API_CONFIG] = null },
                )
            }
            composable("apiConfig/scan") {
                QrScanScreen(
                    onResult = { text ->
                        navController.previousBackStackEntry
                            ?.savedStateHandle?.set(KEY_SCANNED_API_CONFIG, text)
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("apiFunctions") {
                ApiFunctionAssignmentScreen(onBack = { navController.popBackStack() })
            }
            composable("ttsConfig") {
                TtsConfigurationScreen(onBack = { navController.popBackStack() })
            }
            composable("voiceCallSettings") {
                VoiceCallSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = "apiConfig/edit/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType }),
            ) { backStackEntry ->
                ApiConfigEditScreen(
                    uuid = backStackEntry.arguments?.getString("uuid").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("userProfile/edit") {
                UserProfileEditScreen(onClose = { navController.popBackStack() })
            }
            composable(
                route = "backup?focusFolder={focusFolder}",
                arguments = listOf(navArgument("focusFolder") { type = NavType.BoolType; defaultValue = false }),
            ) { entry ->
                // focusFolder=true（自动备份失败/目录丢失深链，P0-19）→ 进页自动开目录选择器重选；普通进入默认 false。
                BackupScreen(
                    onBack = { navController.popBackStack() },
                    autoPickFolder = entry.arguments?.getBoolean("focusFolder") == true,
                )
            }
            composable("backgroundReliability") {
                BackgroundReliabilityScreen(onBack = { navController.popBackStack() })
            }
            composable("calendarAwareness") {
                CalendarAwarenessScreen(onBack = { navController.popBackStack() })
            }
            composable("notificationSettings") {
                NotificationSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("immersiveSettings") {
                ImmersiveSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("appearance") {
                AppearanceSettingsScreen(onBack = { navController.popBackStack() })
            }
            // SETTINGS_REORG D3：记忆设置 + 记忆提示词二合一 hub，沿用 memorySettings 路由。
            composable("memorySettings") {
                MemoryHubScreen(onBack = { navController.popBackStack() })
            }
            composable("systemToggles") {
                SystemTogglesScreen(onBack = { navController.popBackStack() })
            }
            composable("growthSettings") {
                GrowthSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("replyRuleSettings") {
                ReplyRuleSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("contentFilterSettings") {
                ContentFilterSettingsScreen(onBack = { navController.popBackStack() })
            }
            // 批 D·D-3 上下文日志：列表 / 详情 / 分段 / 全文 / 保留设置（id 经 route arg → ViewModel SavedStateHandle）。
            composable("contextLog") {
                ContextLogListScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { id -> navController.navigate("contextLog/detail/$id") },
                    onOpenSettings = { navController.navigate("contextLog/settings") },
                )
            }
            composable("contextLog/detail/{id}") {
                ContextLogDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSegments = { id -> navController.navigate("contextLog/segments/$id") },
                    onOpenContextText = { id -> navController.navigate("contextLog/text/$id/context") },
                    onOpenResponseText = { id -> navController.navigate("contextLog/text/$id/response") },
                )
            }
            composable("contextLog/segments/{id}") {
                ContextLogSegmentsScreen(onBack = { navController.popBackStack() })
            }
            composable("contextLog/text/{id}/{kind}") { backStackEntry ->
                ContextLogTextScreen(
                    isContext = backStackEntry.arguments?.getString("kind") != "response",
                    onBack = { navController.popBackStack() },
                )
            }
            composable("contextLog/settings") {
                ContextLogSettingsScreen(onBack = { navController.popBackStack() })
            }
            // 性能采集（性能专项卷 0）：手机自采性能数字 + 一键导出报告。设置 ⑧「数据与诊断」组·高级门后。
            composable("perfCollect") {
                PerfCollectScreen(onBack = { navController.popBackStack() })
            }
            composable("about") {
                AboutScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAgreement = { navController.navigate("agreementView") },
                )
            }
            composable("agreementView") {
                AgreementViewScreen(onBack = { navController.popBackStack() })
            }
            composable("promptModules") {
                PromptModuleSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenImmersiveSettings = { navController.navigate("immersiveSettings") },
                )
            }
            // P7.1 日记本（M07）。读侧 7.1.4：列表 + 详情；写侧 ComposeDiaryScreen → 7.1.5（撰写/编辑暂用占位）。
            composable("diary") {
                DiaryListScreen(
                    onBack = { navController.popBackStack() },
                    onCompose = { navController.navigate("diaryCompose") },
                    onOpenEntry = { uuid -> navController.navigate("diary/$uuid") },
                )
            }
            composable(
                route = "diary/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType }),
            ) {
                DiaryDetailScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { uuid -> navController.navigate("diaryCompose/$uuid") },
                )
            }
            // 写侧（7.1.5）：撰写 / 编辑共用 ComposeDiaryScreen（编辑经 {uuid}），保存/放弃后返回。
            composable("diaryCompose") {
                ComposeDiaryScreen(
                    onClose = { navController.popBackStack() },
                    onNavigateToApiConfig = { navController.navigate("apiConfig") },
                )
            }
            composable(
                route = "diaryCompose/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType }),
            ) {
                ComposeDiaryScreen(
                    onClose = { navController.popBackStack() },
                    onNavigateToApiConfig = { navController.navigate("apiConfig") },
                )
            }
            composable("diarySettings") {
                DiarySettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = "promptModules/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                PromptModuleSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenImmersiveSettings = { navController.navigate("immersiveSettings") },
                )
            }
            composable(
                route = "chat/{conversationUuid}",
                arguments = listOf(navArgument("conversationUuid") { type = NavType.StringType }),
            ) {
                ChatScreen(
                    onBack = { navController.popBackStack() },
                    onOpenProfile = { characterUuid -> navController.navigate("characterProfile/$characterUuid") },
                    onOpenStickerManagement = { navController.navigate("stickerManagement") },
                    onOpenVoiceCall = { characterUuid -> navController.navigate("voiceCall/$characterUuid") },
                    // VU1 拨号门深链：无音色 → 角色编辑·语音区（focusVoice 滚动定位）/ 缺全局配置 → 全局语音设置。
                    onOpenCharacterVoiceSettings = { uuid -> navController.navigate("character/edit/$uuid?focusVoice=true") },
                    onOpenTtsConfig = { navController.navigate("ttsConfig") },
                    // W13：状态行胶囊点击 → 存聚焦意图 + 进世界屏（WorldViewModel init 消费落点·图纸 §3.6）。
                    onOpenWorldAt = { spec -> WorldFocusEntry.set(spec); navController.navigate("world") { launchSingleTop = true } },
                )
            }
            composable(
                route = "voiceCall/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                VoiceCallScreen(onCallFinished = { navController.popBackStack() })
            }
            composable("character/new") {
                CharacterEditScreen(
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
            composable(
                route = "character/edit/{characterUuid}?focusVoice={focusVoice}",
                arguments = listOf(
                    navArgument("characterUuid") { type = NavType.StringType },
                    // VU1 深链：可选 query·缺省 false → 既有无 query 调用点全兼容（B5）。
                    navArgument("focusVoice") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { backStackEntry ->
                CharacterEditScreen(
                    onCancel = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                    onEditModules = { uuid -> navController.navigate("promptModules/$uuid") },
                    onOpenOfflineMeetings = { uuid -> navController.navigate("offlineMeetings/$uuid") },
                    onOpenWorldBooks = { navController.navigate("worldBooks") },
                    focusVoiceSection = backStackEntry.arguments?.getBoolean("focusVoice") == true,
                )
            }
            // 14.1 角色资料页（只读·点亮成长智能）。入口=联系人头像 / 聊天顶栏标题。
            composable(
                route = "characterProfile/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                CharacterProfileScreen(
                    onBack = { navController.popBackStack() },
                    onEditCharacter = { uuid -> navController.navigate("character/edit/$uuid") },
                    onOpenOfflineMeetings = { uuid -> navController.navigate("offlineMeetings/$uuid") },
                    onOpenSchedule = { uuid -> navController.navigate("scheduleFullDay/$uuid") },
                    onOpenPromises = { uuid -> navController.navigate("promises/$uuid") },
                    onOpenStarfield = { uuid -> navController.navigate("starfield/$uuid") },
                    onEditMemory = { uuid -> navController.navigate("memoryEdit/$uuid") },
                )
            }
            // 记忆手动编辑（资料页共同记忆卡入口·图纸 2026-09-01 件③）。
            composable(
                route = "memoryEdit/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                MemoryEditScreen(onClose = { navController.popBackStack() })
            }
            // 记忆星空（资料页「故事」Tab 入口卡目标·全屏可漫游星空·转场走 NavHost 全局默认·J5）。
            composable(
                route = "starfield/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                StarfieldScreen(
                    onBack = { navController.popBackStack() },
                    onOpenMeetings = { uuid -> navController.navigate("offlineMeetings/$uuid") },
                    onOpenPromises = { uuid -> navController.navigate("promises/$uuid") },
                )
            }
            composable(
                route = "offlineMeetings/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                OfflineMeetingMemoryScreen(onBack = { navController.popBackStack() })
            }
            // 记忆改造三期：角色资料页「我们的约定」账本子页。
            composable(
                route = "promises/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                PromiseLedgerScreen(onBack = { navController.popBackStack() })
            }
            // 14.2 全天行程视图（资料页日程卡「查看全天行程」目标）。
            composable(
                route = "scheduleFullDay/{characterUuid}",
                arguments = listOf(navArgument("characterUuid") { type = NavType.StringType }),
            ) {
                ScheduleFullDayScreen(
                    onBack = { navController.popBackStack() },
                    onOpenChat = { conversationUuid -> navController.navigate("chat/$conversationUuid") },
                )
            }
        }
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
            AppBottomNav(
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
            )
        }
        } // end Box（NavHost + 悬浮底栏叠加层）
    }
}
