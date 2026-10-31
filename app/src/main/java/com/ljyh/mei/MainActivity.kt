package com.ljyh.mei

import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union

import android.content.ComponentName
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.neoruaa.glasmio.glass.render.configureGlassDynamicRange
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.zIndex
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.scene.rememberNavigationEventState
import androidx.navigation3.scene.rememberSceneState
import androidx.navigationevent.compose.NavigationBackHandler
import io.github.neoruaa.glasmio.scaffold.navigation.GlasmioNavDisplay
import io.github.neoruaa.glasmio.scaffold.navigation.IosNavigationTransitionEffects
import io.github.neoruaa.glasmio.scaffold.navigation.GlasmioNavHeaderMotion
import io.github.neoruaa.glasmio.scaffold.GlasmioNavigationScaffold
import io.github.neoruaa.glasmio.scaffold.GlasmioNavigationOperation
import com.ljyh.mei.ui.navigation.MeiloXPageHeaders
import com.ljyh.mei.ui.navigation.MeiloXPageEntry
import com.ljyh.mei.ui.navigation.rememberMeiloXNavigationScrollState
import com.ljyh.mei.ui.component.sheet.LocalMiniPlayerChromeMotion
import com.ljyh.mei.ui.component.sheet.LocalMiniPlayerChromeHint
import com.ljyh.mei.ui.component.sheet.MiniPlayerChromeHint
import com.ljyh.mei.ui.component.sheet.MiniPlayerChromeMotion
import com.ljyh.mei.ui.component.sheet.rememberMiniPlayerChromeMotion
import io.github.neoruaa.glasmio.scaffold.GlasmioNavigationBarWithTrailingItem
import io.github.neoruaa.glasmio.scaffold.glasmioNavigationScrollHint
import androidx.navigation3.ui.NavDisplayTransitionEffects
import io.github.neoruaa.glasmio.scaffold.navigation.iosPopTransitionSpec
import io.github.neoruaa.glasmio.scaffold.navigation.iosPredictivePopTransitionSpec
import io.github.neoruaa.glasmio.scaffold.navigation.iosTransitionSpec
import com.ljyh.mei.constants.AppBarHeight
import com.ljyh.mei.constants.AppAppearance
import com.ljyh.mei.constants.AppAppearanceKey
import com.ljyh.mei.constants.BetaUpdatesEnabledKey
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.constants.DynamicThemeKey
import com.ljyh.mei.constants.AccentColorKey
import com.ljyh.mei.constants.DefaultAccentColorArgb
import com.ljyh.mei.constants.PodcastsEnabledKey
import com.ljyh.mei.constants.DownloadsEnabledKey
import com.ljyh.mei.constants.CloudMusicEnabledKey
import com.ljyh.mei.constants.ListeningHistoryEnabledKey
import com.ljyh.mei.constants.FindMusicTabEnabledKey
import com.ljyh.mei.constants.LibraryTabEnabledKey
import com.ljyh.mei.constants.PodcastsTabEnabledKey
import com.ljyh.mei.constants.DownloadsTabEnabledKey
import com.ljyh.mei.constants.CloudMusicTabEnabledKey
import com.ljyh.mei.constants.ListeningHistoryTabEnabledKey
import com.ljyh.mei.constants.NavigationTabOrderKey
import com.ljyh.mei.constants.KeepBottomBarExpandedKey
import com.ljyh.mei.constants.BottomFloatingContentExtraSpacingKey
import com.ljyh.mei.constants.BottomBarHorizontalPadding
import com.ljyh.mei.constants.MiniPlayerInlineVerticalTravel
import com.ljyh.mei.constants.DefaultBottomFloatingContentExtraSpacing
import com.ljyh.mei.constants.MaxBottomFloatingContentExtraSpacing
import com.ljyh.mei.constants.GlassHdrEffectsKey
import com.ljyh.mei.constants.LiquidGlassEnabledKey
import com.ljyh.mei.constants.PredictiveBackAnimationEnabledKey
import com.ljyh.mei.constants.ReduceTransparencyKey
import com.ljyh.mei.constants.LastSelectedTabKey
import com.ljyh.mei.constants.PlayerKeepScreenOnKey
import com.ljyh.mei.constants.RecognizeClipboardLinksKey
import com.ljyh.mei.constants.MiniPlayerHeight
import com.ljyh.mei.constants.NavigationBarHeight
import com.ljyh.mei.constants.NavigationBarSurfaceHeight
import com.ljyh.mei.constants.NavigationBarBottomMargin
import com.ljyh.mei.data.model.UserData
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.melox.NeteaseMusicLink
import com.ljyh.mei.data.model.melox.NeteaseMusicLinkParser
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.di.repository.ColorRepository
import com.ljyh.mei.playback.MusicService
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.ClipboardLinkPrompt
import com.ljyh.mei.ui.component.IconButton
import com.ljyh.mei.ui.component.VersionUpdateAlert
import com.ljyh.mei.ui.component.player.BottomSheetPlayer
import com.ljyh.mei.ui.component.player.FloatingLyricsPipBackdrop
import com.ljyh.mei.ui.component.player.FloatingLyricsPipScreen
import com.ljyh.mei.ui.component.player.floatingLyricsPipParams
import com.ljyh.mei.playback.ENABLE_FLOATING_LYRICS
import com.ljyh.mei.playback.DISABLE_FLOATING_LYRICS
import androidx.lifecycle.Lifecycle
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioBottomSheetState
import io.github.neoruaa.glasmio.scaffold.GlasmioBottomSheetState
import com.ljyh.mei.ui.component.sheet.syncPlaybackVisibility
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioAppBarScrollBehavior
import io.github.neoruaa.glasmio.scaffold.resetHeightOffset
import com.ljyh.mei.ui.local.LocalDatabase
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.local.LocalPageAppearance
import com.ljyh.mei.ui.local.LocalColorfulPageBackgrounds
import com.ljyh.mei.ui.component.player.LocalMiniPlayerAppearance
import com.ljyh.mei.ui.local.LocalUserData
import io.github.neoruaa.glasmio.ui.GlasmioNavigationBar
import io.github.neoruaa.glasmio.glass.BottomNavigationContact
import io.github.neoruaa.glasmio.glass.LocalBottomControlContact
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioTabItem
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.ui.GlasmioSearchToolbar
import io.github.neoruaa.glasmio.ui.GlasmioTextSelectionHost
import io.github.neoruaa.glasmio.ui.GlasmioTextSelectionLabels
import io.github.neoruaa.glasmio.ui.CompactBottomControlIconSize
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.GlassAppearance
import io.github.neoruaa.glasmio.core.LocalGlassAppearance
import io.github.neoruaa.glasmio.glass.LocalBlurBackdrop
import io.github.neoruaa.glasmio.core.defaultGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.glass.rememberSharedGlassBackdrop
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import com.ljyh.mei.ui.screen.Index
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.account.NeteaseLegacyWebSessionNotice
import com.ljyh.mei.ui.screen.navigationEntry
import com.ljyh.mei.ui.screen.search.SearchScreen
import com.ljyh.mei.ui.navigation.MeiNavEntryViewModelStoreOwner
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.navigation.MeiRoute
import com.ljyh.mei.ui.theme.MusicTheme
import com.ljyh.mei.ui.theme.LocalAccentSeedColor
import com.ljyh.mei.utils.log.CrashHandler
import com.ljyh.mei.utils.cache.preloadImage
import com.ljyh.mei.playback.nextPlaybackMediaItemIndex
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.get
import com.ljyh.mei.utils.log.FileLoggingTree
import com.ljyh.mei.utils.netease.NeteaseUtils.getAndroidId
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.VersionUpdateChecker
import com.ljyh.mei.utils.VersionUpdateResult
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop
import io.github.neoruaa.glasmio.glass.render.Backdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberCanvasBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var database: AppDatabase

    @Inject
    lateinit var colorRepository: ColorRepository

    @Inject
    lateinit var apiService: ApiService
    private var userData by mutableStateOf(UserData.VISITOR)
    private var pictureInPictureMode by mutableStateOf(false)
    private var floatingLyricsRequest: Boolean? = null
    private var musicService: MusicService? = null


    @androidx.annotation.OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.S)
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pictureInPictureMode = isInPictureInPictureMode
        readFloatingLyricsIntent(intent)
        enableEdgeToEdge()
        CrashHandler.init(this)
        if (BuildConfig.DEBUG) {
            // 开发模式：既输出到 Logcat，也输出到文件
            Timber.plant(Timber.DebugTree())
            Timber.plant(FileLoggingTree(this))
        } else {
            // Release 模式：主要是植入文件记录器
            Timber.plant(FileLoggingTree(this))
        }
        lifecycleScope.launch {
            val initialPreferences = dataStore.data.first()
            configureGlassDynamicRange(window, hdrEnabled = initialPreferences.glassHdrOutputEnabled())
            initializeContent(initialPreferences)
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.S)
    @OptIn(ExperimentalMaterial3Api::class)
    private fun initializeContent(initialPreferences: Preferences) {
        val initialHdrOutput = initialPreferences.glassHdrOutputEnabled()
        setContent {
            val context = this@MainActivity
            val lifecycleOwner = LocalLifecycleOwner.current
            val backStack = rememberNavBackStack(MeiRoute(Screen.Home.route))
            val pageHeaders = remember { MeiloXPageHeaders() }
            val navController = remember(backStack) {
                MeiNavigator(context = context, backStack = backStack)
            }
            val pageAppearanceOverrides = remember {
                mutableStateMapOf<String, Pair<Any, Boolean>>()
            }
            val registerPageAppearance: (String, Any, Boolean?) -> Unit = remember {
                { route, owner, isDark ->
                    if (isDark != null) {
                        pageAppearanceOverrides[route] = owner to isDark
                    } else if (pageAppearanceOverrides[route]?.first === owner) {
                        pageAppearanceOverrides.remove(route)
                    }
                }
            }
            // Search chrome belongs to the search destination, including back navigation.
            val active = navController.currentRoute == Screen.Search.route
            var focusSearchOnEntry by remember { mutableStateOf(false) }
            val dynamicTheme by rememberPreference(DynamicThemeKey, defaultValue = false)
            val accentColorArgb by rememberPreference(AccentColorKey, DefaultAccentColorArgb)
            val appAppearance by rememberEnumPreference(AppAppearanceKey, AppAppearance.System)
            val (lastSelectedTab, setLastSelectedTab) = rememberPreference(LastSelectedTabKey, Index.Home.name)
            val recognizeClipboardLinks by rememberPreference(RecognizeClipboardLinksKey, false)
            val navigationPreferences by context.dataStore.data
                .collectAsState<Preferences, Preferences?>(initial = initialPreferences)
            LaunchedEffect(navigationPreferences?.glassHdrOutputEnabled()) {
                if (navigationPreferences?.glassHdrOutputEnabled() != initialHdrOutput) {
                    // Recreate material layers in the new window color space, as in the demo.
                    recreate()
                }
            }
            val colorfulPageBackgrounds = navigationPreferences?.let {
                it[com.ljyh.mei.constants.ColorfulDetailBackgroundsKey] ?: true
            } ?: false
            val keepBottomBarExpanded = navigationPreferences?.get(KeepBottomBarExpandedKey) ?: false
            val bottomFloatingContentExtraSpacing =
                (navigationPreferences?.get(BottomFloatingContentExtraSpacingKey)
                    ?: DefaultBottomFloatingContentExtraSpacing)
                    .coerceIn(0, MaxBottomFloatingContentExtraSpacing).dp
            val liquidGlassEnabled = navigationPreferences?.get(LiquidGlassEnabledKey) ?: true
            val reduceTransparency = navigationPreferences?.get(ReduceTransparencyKey) ?: false
            val predictiveBackAnimationEnabled =
                navigationPreferences?.get(PredictiveBackAnimationEnabledKey) ?: true
            val podcastsEnabled = navigationPreferences?.get(PodcastsEnabledKey) ?: true
            val downloadsEnabled = navigationPreferences?.get(DownloadsEnabledKey) ?: true
            val cloudMusicEnabled = navigationPreferences?.get(CloudMusicEnabledKey) ?: true
            val listeningHistoryEnabled = navigationPreferences?.get(ListeningHistoryEnabledKey) ?: true
            val findMusicTabEnabled = navigationPreferences?.get(FindMusicTabEnabledKey) ?: true
            val libraryTabEnabled = navigationPreferences?.get(LibraryTabEnabledKey) ?: true
            val podcastsTabEnabled = navigationPreferences?.get(PodcastsTabEnabledKey) ?: false
            val downloadsTabEnabled = navigationPreferences?.get(DownloadsTabEnabledKey) ?: false
            val cloudMusicTabEnabled = navigationPreferences?.get(CloudMusicTabEnabledKey) ?: false
            val listeningHistoryTabEnabled = navigationPreferences?.get(ListeningHistoryTabEnabledKey) ?: false
            val keepScreenOnInPlayer by rememberPreference(PlayerKeepScreenOnKey, false)
            val navigationTabOrder = navigationPreferences?.get(NavigationTabOrderKey)
                ?: Index.DefaultOrder.joinToString(",", transform = Index::name)
            var playerConnection by remember { mutableStateOf<PlayerConnection?>(null) }
            var clipboardLink by remember { mutableStateOf<NeteaseMusicLink?>(null) }
            var clipboardInspected by rememberSaveable { mutableStateOf(false) }
            var startupUpdateResult by remember { mutableStateOf<VersionUpdateResult?>(null) }

            LaunchedEffect(Unit) {
                try {
                    val betaUpdatesEnabled = context.dataStore.data.first()[BetaUpdatesEnabledKey] ?: false
                    val result = VersionUpdateChecker.check(
                        BuildConfig.VERSION_NAME,
                        betaUpdatesEnabled = betaUpdatesEnabled,
                    )
                    val currentBetaUpdatesEnabled = context.dataStore.data.first()[BetaUpdatesEnabledKey] ?: false
                    if (result is VersionUpdateResult.UpdateAvailable &&
                        result.comparisonKnown &&
                        currentBetaUpdatesEnabled == betaUpdatesEnabled
                    ) {
                        startupUpdateResult = result
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    return@LaunchedEffect
                }
            }

            var isMeasured by remember { mutableStateOf(false) }
            DisposableEffect(Unit) {
                val intent = Intent(context, MusicService::class.java)

                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        Timber.tag("MainActivity").d("Service Connected") // 添加日志
                        if (service is MusicService.MusicBinder) {
                            musicService = service.service
                            service.service.attachFloatingLyricsActivity(this@MainActivity)
                            applyFloatingLyricsRequest()
                            // 更新 State，触发 Recomposition
                            playerConnection = PlayerConnection(
                                context,
                                service,
                                database,
                                lifecycleOwner.lifecycleScope
                            )
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        musicService?.detachFloatingLyricsActivity(this@MainActivity)
                        musicService = null
                        Timber.tag("MainActivity").d("Service Disconnected")
                        playerConnection?.dispose() // 假设你有 dispose 方法清理资源
                        playerConnection = null
                    }
                }

                // 启动并绑定服务
                context.startService(intent)
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)

                onDispose {
                    // Compose 销毁时解绑
                    playerConnection?.dispose()
                    context.unbindService(connection)
                    playerConnection = null
                }
            }
            var targetThemeColor by remember { mutableStateOf<Color?>(null) }
            var nextCoverUrl by remember(playerConnection) { mutableStateOf<String?>(null) }

            DisposableEffect(playerConnection) {
                val player = playerConnection?.player
                fun updateNextCover() {
                    val nextIndex = player?.nextPlaybackMediaItemIndex() ?: C.INDEX_UNSET
                    nextCoverUrl = if (player != null && nextIndex in 0 until player.mediaItemCount &&
                        nextIndex != player.currentMediaItemIndex
                    ) {
                        player.getMediaItemAt(nextIndex).mediaMetadata.artworkUri?.toString()
                            ?.takeIf { it.isNotBlank() }
                    } else null
                }
                val listener = object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (events.containsAny(
                                Player.EVENT_MEDIA_ITEM_TRANSITION,
                                Player.EVENT_TIMELINE_CHANGED,
                                Player.EVENT_MEDIA_METADATA_CHANGED,
                                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                                Player.EVENT_REPEAT_MODE_CHANGED,
                            )
                        ) updateNextCover()
                    }
                }
                player?.addListener(listener)
                updateNextCover()
                onDispose { player?.removeListener(listener) }
            }
            val preloadThemeColor by rememberUpdatedState(dynamicTheme)
            LaunchedEffect(nextCoverUrl) {
                // Match the shared player artwork request, regardless of theme settings.
                nextCoverUrl?.let { url ->
                    preloadImage(this@MainActivity, url)
                    // The palette request can now reuse the downloaded source bytes.
                    snapshotFlow { preloadThemeColor }.collectLatest { enabled ->
                        if (enabled) colorRepository.getColorOrExtract(this@MainActivity, url)
                    }
                }
            }

            LaunchedEffect(playerConnection, dynamicTheme) {
                Timber.tag("MainActivity").d("playerConnection: $playerConnection")
                if (!dynamicTheme || playerConnection == null) {
                    targetThemeColor = null
                    return@LaunchedEffect
                }
                val playerConnection = playerConnection ?: return@LaunchedEffect
                playerConnection.service.currentMediaMetadata.collectLatest { song ->
                    if (song == null) {
                        targetThemeColor = null
                        return@collectLatest
                    }
                    val context = this@MainActivity
                    Timber.tag("MainActivity").d("获取当前歌曲颜色: $song")
                    val color = colorRepository.getColorOrExtract(context, song.coverUrl)
                    targetThemeColor = color.takeUnless { it == Color.Black }
                    Timber.tag("MainActivity").d("获取歌曲颜色: $targetThemeColor")

                }

            }
            val scrollPlayerSheet = remember { mutableStateOf<GlasmioBottomSheetState?>(null) }
            val navigationScrollAllowed = remember { mutableStateOf(false) }
            val scrollSheet = scrollPlayerSheet.value
            val navigationScroll = rememberMeiloXNavigationScrollState(
                viewport = pageHeaders.entry(navController.currentRoute ?: Screen.Home.route).viewport,
                enabled = navigationScrollAllowed.value && !active && !keepBottomBarExpanded &&
                    (scrollSheet == null || scrollSheet.isCollapsed || scrollSheet.isDismissed),
            )
            val navigationBarExpanded = keepBottomBarExpanded || !navigationScroll.compactTarget
            LaunchedEffect(keepBottomBarExpanded) {
                if (keepBottomBarExpanded) navigationScroll.expand()
            }



            val systemDark = isSystemInDarkTheme()
            val effectiveDark = when (appAppearance) {
                AppAppearance.System -> systemDark
                AppAppearance.Light -> false
                AppAppearance.Dark -> true
            }
            val defaultAccent = if (effectiveDark) Color(0xFFFF4245) else Color(DefaultAccentColorArgb.toInt())
            val configuredAccent = if (accentColorArgb == DefaultAccentColorArgb) {
                defaultAccent
            } else {
                Color(accentColorArgb.toInt())
            }
            val effectiveAccent = if (dynamicTheme) targetThemeColor ?: defaultAccent else configuredAccent
            val accentSeedColor = if (dynamicTheme) {
                targetThemeColor
            } else {
                Color(accentColorArgb.toInt()).takeUnless { accentColorArgb == DefaultAccentColorArgb }
            }
            MusicTheme(
                seedColor = effectiveAccent,
                isDark = effectiveDark,
                dynamicAccent = dynamicTheme && targetThemeColor != null,
            ) {
                val glassColors = defaultGlassColors(
                    isDark = effectiveDark,
                    accent = MaterialTheme.colorScheme.primary,
                )
                // The regular page backdrop is a static color, so avoid recording a full-screen
                // layer just to replay the same pixels for every glass consumer.
                val staticGlassBackdrop = rememberCanvasBackdrop {
                    drawRect(glassColors.groupedBackground)
                }
                // Picture-in-picture still needs a recorded cover image backdrop.
                val pipBackdrop = rememberLayerBackdrop()
                val glassBackdrop = if (pictureInPictureMode && playerConnection != null) {
                    pipBackdrop
                } else {
                    staticGlassBackdrop
                }
                // Keep the base page backdrop and the bottom controls' sample
                // layer separate. Page glass samples [glassBackdrop], while the
                // bottom layer records the page after it has rendered. Controls
                // then sample [bottomBackdrop] without sampling themselves.
                val bottomBackdrop = rememberLayerBackdrop()
                // Popups are hosted in a separate Android window. Wrap the recorded page layer
                // before combining it so its sample coordinates use the popup's real screen
                // position rather than the popup-local overshoot origin.
                val bottomControlsBackdrop = rememberSharedGlassBackdrop(glassBackdrop, bottomBackdrop)
                CompositionLocalProvider(
                    LocalGlassBackdrop provides glassBackdrop,
                    LocalGlassColors provides glassColors,
                    LocalGlassAppearance provides GlassAppearance(liquidGlassEnabled, reduceTransparency),
                    LocalBlurBackdrop provides bottomControlsBackdrop,
                    LocalAccentSeedColor provides accentSeedColor,
                    LocalPageAppearance provides registerPageAppearance,
                    LocalColorfulPageBackgrounds provides colorfulPageBackgrounds,
                ) {
                    GlasmioTextSelectionHost(
                        backdrop = bottomControlsBackdrop,
                        labels = GlasmioTextSelectionLabels(
                            cut = stringResource(android.R.string.cut),
                            copy = stringResource(android.R.string.copy),
                            paste = stringResource(android.R.string.paste),
                            selectAll = stringResource(android.R.string.selectAll),
                            more = stringResource(R.string.more_actions_title),
                            expanded = stringResource(R.string.menu_expanded),
                            collapsed = stringResource(R.string.menu_collapsed),
                        ),
                    ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .nestedScroll(navigationScroll.nestedScrollConnection)
                            .onSizeChanged {
                                isMeasured = true
                            }
                    ) {
                    val focusManager = LocalFocusManager.current
                    val keyboardController = LocalSoftwareKeyboardController.current
                    val density = LocalDensity.current
                    val navigationLayoutDirection = LocalLayoutDirection.current
                    val windowsInsets = WindowInsets.systemBars
                    val navigationInsets = WindowInsets.navigationBars
                    val currentRoute = navController.currentRoute
                    val handlesKeyboardInsets = active || currentRoute == Screen.Search.route ||
                        currentRoute?.startsWith("${Screen.PrivateConversation.route}/") == true ||
                        currentRoute?.startsWith("${Screen.Comment.route}/") == true
                    DisposableEffect(handlesKeyboardInsets) {
                        val previousMode = window.attributes.softInputMode
                        if (handlesKeyboardInsets) {
                            window.setSoftInputMode(
                                (previousMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
                            )
                        }
                        onDispose {
                            if (handlesKeyboardInsets) window.setSoftInputMode(previousMode)
                        }
                    }
                    val imeInsets = WindowInsets.ime
                    // Keep IME animation reads in measurement instead of recomposing the page.
                    // Plain padding retains the existing unconsumed-inset behavior.
                    val bottomControlInsets = remember(navigationInsets, bottomFloatingContentExtraSpacing) {
                        navigationInsets.only(WindowInsetsSides.Bottom)
                            .union(WindowInsets(bottom = bottomNavigationHorizontalMargin))
                            .add(WindowInsets(bottom = bottomFloatingContentExtraSpacing))
                    }
                    val searchBottomInsets = remember(
                        imeInsets, bottomControlInsets, bottomFloatingContentExtraSpacing,
                    ) {
                        bottomControlInsets.union(
                            imeInsets.add(WindowInsets(
                                bottom = NavigationBarBottomMargin + bottomFloatingContentExtraSpacing,
                            )),
                        ).only(WindowInsetsSides.Bottom)
                    }
                    val searchToolbarBottomPadding = remember(searchBottomInsets, density) {
                        searchBottomInsets.asPaddingValues(density)
                    }
                    val navigationBottomMargin by remember(bottomControlInsets, density) {
                        derivedStateOf {
                            with(density) { bottomControlInsets.getBottom(density).toDp() }
                        }
                    }

                    val bottomInset by remember {
                        derivedStateOf {
                            with(density) {
                                windowsInsets.getBottom(density).toDp()
                            }
                        }
                    }

                    val navigationItems = remember(
                        podcastsEnabled,
                        downloadsEnabled,
                        cloudMusicEnabled,
                        listeningHistoryEnabled,
                        findMusicTabEnabled,
                        libraryTabEnabled,
                        podcastsTabEnabled,
                        downloadsTabEnabled,
                        cloudMusicTabEnabled,
                        listeningHistoryTabEnabled,
                        navigationTabOrder,
                    ) {
                        val available = buildList {
                            add(Index.Home)
                            if (findMusicTabEnabled) add(Index.FindMusic)
                            if (podcastsEnabled && podcastsTabEnabled) add(Index.Podcasts)
                            if (libraryTabEnabled) add(Index.Library)
                            if (downloadsEnabled && downloadsTabEnabled) add(Index.Downloads)
                            if (cloudMusicEnabled && cloudMusicTabEnabled) add(Index.Cloud)
                            if (listeningHistoryEnabled && listeningHistoryTabEnabled) add(Index.History)
                            add(Index.Settings)
                        }
                        val ordered = navigationTabOrder.split(',')
                            .mapNotNull { name -> Index.entries.firstOrNull { it.name == name } }
                            .filter { it in available }
                            .distinct()
                            .toMutableList()
                        available.forEach { item ->
                            if (item !in ordered) {
                                val settingsIndex = ordered.indexOf(Index.Settings)
                                if (settingsIndex >= 0 && item != Index.Settings) ordered.add(settingsIndex, item)
                                else ordered.add(item)
                            }
                        }
                        ordered.remove(Index.Home)
                        ordered.add(0, Index.Home)
                        ordered.remove(Index.Settings)
                        ordered.add(Index.Settings)
                        ordered
                    }


                    val currentNavigationItems = rememberUpdatedState(navigationItems)
                    val shouldAllowNavigationBar =
                        (currentRoute == null ||
                                navigationItems.fastAny { it.route == currentRoute } ||
                                currentRoute == Screen.Search.route) &&
                                !active
                    SideEffect { navigationScrollAllowed.value = shouldAllowNavigationBar }
                    // Route visibility must not reset the expanded/compact shape. This keeps a
                    // MiniNav compact while it fades out. The expanded preference overrides
                    // scrolling without changing which routes can display the navigation bar.
                    val shouldCompactMiniPlayer =
                        shouldAllowNavigationBar && !navigationBarExpanded



                    val searchBarFocusRequester = remember { FocusRequester() }
                    // Keep the anchor stable while the navigation morphs. The mini player
                    // moves inside the collapsed host with the same continuous spring.
                    val collapsedBottomReservation = when {
                        active -> navigationBottomMargin + searchToolbarSurfaceHeight -
                            searchToolbarVerticalOffset + searchControlSpacing
                        shouldAllowNavigationBar ->
                            navigationBottomMargin + NavigationBarHeight - NavigationBarBottomMargin
                        else -> navigationBottomMargin
                    }
                    val collapsedBound = collapsedBottomReservation + MiniPlayerHeight
                    val searchMiniPlayerOffset = remember(
                        active, searchToolbarBottomPadding, navigationBottomMargin,
                    ) {
                        {
                            // Read IME padding during placement so both controls move together.
                            if (active) -(searchToolbarBottomPadding.calculateBottomPadding() -
                                navigationBottomMargin).coerceAtLeast(0.dp)
                            else 0.dp
                        }
                    }

                    val compactNavigationProgress = navigationScroll.compactProgress
                    val playerBottomSheetState = rememberGlasmioBottomSheetState(
                        dismissedBound = 0.dp,
                        collapsedBound = collapsedBound,
                        expandedBound = maxHeight,
                    )
                    LaunchedEffect(playerBottomSheetState, focusManager, keyboardController) {
                        snapshotFlow { playerBottomSheetState.isContentVisible }.collect { visible ->
                            if (visible) {
                                // The player overlays the current page, so release its input focus.
                                focusSearchOnEntry = false
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                            }
                        }
                    }
                    val miniPlayerCenterTravel = MiniPlayerInlineVerticalTravel
                    val miniPlayerChromeMotion = rememberMiniPlayerChromeMotion(
                        compact = shouldCompactMiniPlayer,
                        sheet = playerBottomSheetState,
                        centerTravel = miniPlayerCenterTravel,
                    )
                    val chromeHintProgress = navigationScroll.scrollHintProgress
                    // UIKit's hint wrapper retains the regular accessory reservation even
                    // while the visible accessory is inline. Include the bottom allocation.
                    val chromeReservation = remember(playerBottomSheetState, navigationBottomMargin) {
                        {
                            NavigationBarSurfaceHeight + navigationBottomMargin +
                                if (playerBottomSheetState.isDismissed) 0.dp
                                else NavigationBarBottomMargin + MiniPlayerHeight
                        }
                    }
                    val navigationHint = remember(chromeHintProgress, chromeReservation, navigationBottomMargin) {
                        MiniPlayerChromeHint(chromeHintProgress) {
                            NavigationBarSurfaceHeight + navigationBottomMargin - chromeReservation() / 2
                        }
                    }
                    val miniPlayerHint = remember(
                        chromeHintProgress, chromeReservation, miniPlayerChromeMotion,
                        playerBottomSheetState, miniPlayerCenterTravel, searchMiniPlayerOffset,
                    ) {
                        MiniPlayerChromeHint(chromeHintProgress) {
                            val miniAnchor = if (playerBottomSheetState.progress == 0f &&
                                !playerBottomSheetState.isDragging
                            ) playerBottomSheetState.value else playerBottomSheetState.collapsedBound
                            miniAnchor -
                                miniPlayerCenterTravel * miniPlayerChromeMotion.verticalProgress.value -
                                searchMiniPlayerOffset() -
                                chromeReservation() / 2
                        }
                    }
                    val playerBackgroundReady = remember { mutableStateOf(false) }
                    val onPlayerBackgroundReadyChanged = remember {
                        { ready: Boolean -> playerBackgroundReady.value = ready }
                    }
                    DisposableEffect(playerBottomSheetState) {
                        scrollPlayerSheet.value = playerBottomSheetState
                        onDispose {
                            if (scrollPlayerSheet.value === playerBottomSheetState) {
                                scrollPlayerSheet.value = null
                            }
                        }
                    }
                    val windowInsetsController = remember {
                        WindowInsetsControllerCompat(window, window.decorView)
                    }
                    val isPlayerPage = remember(playerBottomSheetState) {
                        derivedStateOf {
                            playerBottomSheetState.isExpanded ||
                                playerBottomSheetState.progress >= 0.99f
                        }
                    }.value
                    val playerDismissed by remember(playerBottomSheetState) {
                        derivedStateOf { playerBottomSheetState.isDismissed }
                    }
                    val searchResultsBottomPadding = remember(searchBottomInsets, density, playerDismissed) {
                        searchBottomInsets.add(
                            WindowInsets(bottom = NavigationBarHeight - NavigationBarBottomMargin +
                                if (playerDismissed) 0.dp else MiniPlayerHeight),
                        ).asPaddingValues(density)
                    }
                    val pageDarkAppearance = currentRoute?.let { pageAppearanceOverrides[it]?.second }
                    val statusBarDark = isPlayerPage || (pageDarkAppearance ?: effectiveDark)
                    val windowConfiguration = LocalConfiguration.current
                    DisposableEffect(
                        window, windowInsetsController, windowConfiguration,
                        isPlayerPage, statusBarDark, effectiveDark, keepScreenOnInPlayer,
                    ) {
                        val transparent = android.graphics.Color.TRANSPARENT
                        enableEdgeToEdge(
                            statusBarStyle = if (isPlayerPage) {
                                // The expanded player renders artwork behind the status bar;
                                // keep its foreground white regardless of the app theme.
                                SystemBarStyle.dark(transparent)
                            } else {
                                SystemBarStyle.auto(transparent, transparent) { statusBarDark }
                            },
                            navigationBarStyle = SystemBarStyle.auto(transparent, transparent) {
                                effectiveDark
                            },
                        )
                        // Keep this explicit because the API 35+ edge-to-edge implementation can
                        // retain the previous appearance while the bottom sheet settles.
                        windowInsetsController.isAppearanceLightStatusBars =
                            !statusBarDark
                        // Even an unchanged flag dispatches window attributes and a traversal.
                        val keepScreenOnFlag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        val wantsScreenOn = isPlayerPage && keepScreenOnInPlayer
                        val hasScreenOn = window.attributes.flags and keepScreenOnFlag != 0
                        if (hasScreenOn != wantsScreenOn) {
                            if (wantsScreenOn) window.addFlags(keepScreenOnFlag)
                            else window.clearFlags(keepScreenOnFlag)
                        }
                        // Keep the navigation bar transparent instead of applying the platform
                        // contrast scrim.
                        window.navigationBarColor = transparent
                        window.isNavigationBarContrastEnforced = false
                        window.navigationBarDividerColor = transparent
                        onDispose { }
                    }
                    val immersiveLandscapePlayer = isPlayerPage &&
                        windowConfiguration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    DisposableEffect(windowInsetsController, immersiveLandscapePlayer) {
                        val previousBehavior = windowInsetsController.systemBarsBehavior
                        if (immersiveLandscapePlayer) {
                            windowInsetsController.systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
                        }
                        onDispose {
                            if (immersiveLandscapePlayer) {
                                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
                                windowInsetsController.systemBarsBehavior = previousBehavior
                            }
                        }
                    }
                    val (query, onQueryChange) = rememberSaveable(stateSaver = TextFieldValue.Saver) {
                        mutableStateOf(TextFieldValue())
                    }
                    val clearSearchInput = {
                        focusManager.clearFocus()
                        onQueryChange(TextFieldValue())
                        focusSearchOnEntry = false
                    }
                    LaunchedEffect(active) {
                        if (!active) {
                            clearSearchInput()
                        }
                    }

                    val onSearchCancel = {
                        clearSearchInput()
                        if (navController.currentRoute == Screen.Search.route) {
                            navController.popBackStack()
                        }
                    }

                    val onSearch: (String) -> Unit = {
                        if (it.isNotEmpty()) {
                            clearSearchInput()
                            Screen.SearchResult.navigate(navController){
                                addPath(Uri.encode(it))
                                addPath("1") // 默认所搜单曲
                            }
                        }
                    }

                    LaunchedEffect(Unit) {
                        lifecycleScope.launch {
                            getAndroidId(this@MainActivity)
                            if (dataStore.get(DeviceIdKey, "").isEmpty()) {
                                dataStore.edit { settings ->
                                    settings[DeviceIdKey] = com.ljyh.mei.utils.getDeviceId()
                                }
                            }
                        }
                    }
                    DisposableEffect(playerConnection, playerBottomSheetState, isMeasured) {
                        val player =
                            playerConnection?.player ?: return@DisposableEffect onDispose { }
                        if (!isMeasured) return@DisposableEffect onDispose { }
                        fun syncPlayerVisibility() {
                            playerBottomSheetState.syncPlaybackVisibility(player.currentMediaItem != null)
                        }
                        val listener = object : Player.Listener {
                            override fun onEvents(player: Player, events: Player.Events) {
                                if (events.containsAny(
                                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                                        Player.EVENT_TIMELINE_CHANGED,
                                    )
                                ) {
                                    syncPlayerVisibility()
                                }
                            }
                        }
                        player.addListener(listener)
                        syncPlayerVisibility()
                        onDispose {
                            player.removeListener(listener)
                        }
                    }
                    val selectionToolbar = remember { com.ljyh.mei.ui.local.SelectionToolbarState() }
                    val pageVisible = remember(playerBottomSheetState, playerBackgroundReady) {
                        derivedStateOf {
                            !playerBottomSheetState.isExpanded || !playerBackgroundReady.value
                        }
                    }
                    val pageCacheActive = remember(playerBottomSheetState) {
                        derivedStateOf { playerBottomSheetState.isTransitioning }
                    }
                    CompositionLocalProvider(
                        com.ljyh.mei.ui.local.LocalSelectionToolbar provides selectionToolbar,
                        LocalDatabase provides database,
                        LocalNavController provides navController,
                        LocalPlayerConnection provides playerConnection,
                        LocalUserData provides userData,
                    ) {
                        if (pictureInPictureMode && playerConnection != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .layerBackdrop(pipBackdrop)
                                    .trackBackdropPosition(pipBackdrop),
                            ) {
                                FloatingLyricsPipBackdrop(playerConnection = playerConnection!!)
                            }
                        }

                        if (pictureInPictureMode && playerConnection != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer()
                                    .layerBackdrop(bottomBackdrop)
                                    .trackBackdropPosition(bottomBackdrop),
                            ) {
                                CompositionLocalProvider(
                                    // Child glass controls sample the base page,
                                    // never the bottom sample layer they create.
                                    LocalGlassBackdrop provides glassBackdrop,
                                ) {
                                    FloatingLyricsPipScreen(playerConnection = playerConnection!!)
                                }
                            }
                        } else {
                            // Page content is the backdrop source for the
                            // floating mini player and bottom controls. Its own
                            // glass controls sample the static backdrop instead
                            // of this source, avoiding a feedback loop.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // Keep sibling player/nav draw invalidations from recording
                                    // the unchanged page again. Child updates remain live.
                                    .layout { measurable, constraints ->
                                        val placeable = measurable.measure(constraints)
                                        layout(placeable.width, placeable.height) {
                                            // Stop drawing page text, images and glass once the
                                            // expanded mesh background can cover them completely.
                                            if (pageVisible.value) {
                                                placeable.place(0, 0)
                                            }
                                        }
                                    }
                                    .graphicsLayer {
                                        // Reuse native-resolution page pixels while the sheet moves.
                                        // Child updates still invalidate this live render layer.
                                        compositingStrategy = if (pageCacheActive.value) {
                                            CompositingStrategy.Offscreen
                                        } else {
                                            CompositingStrategy.Auto
                                        }
                                    }
                                    // Match the opaque root behind transparent page content. Keep
                                    // this outside the backdrop recording so glass samples are intact.
                                    .background(MaterialTheme.colorScheme.background)
                                    .layerBackdrop(bottomBackdrop)
                                    .trackBackdropPosition(bottomBackdrop),
                            ) {
                                CompositionLocalProvider(
                                    LocalGlassBackdrop provides glassBackdrop,
                                ) {
                                    val entryViewModelOwners = remember {
                                        mutableMapOf<String, MeiNavEntryViewModelStoreOwner>()
                                    }
                                    val headerMotion = remember {
                                        GlasmioNavHeaderMotion((backStack.last() as MeiRoute).route) { from, to, pop ->
                                            if (from in homeNavigationRoutes && to in homeNavigationRoutes
                                            ) GlasmioNavigationOperation.Tab
                                            else if (pop) GlasmioNavigationOperation.Pop
                                            else GlasmioNavigationOperation.Push
                                        }
                                    }
                                    LaunchedEffect(pageHeaders, headerMotion, backStack) {
                                        snapshotFlow {
                                            val frame = headerMotion.frame.value
                                            backStack.map { (it as MeiRoute).route }.toSet() + frame.fromEntryKey + frame.toEntryKey
                                        }.collect(pageHeaders::retain)
                                    }
                                    val viewModelEntryDecorator = remember(context) {
                                        NavEntryDecorator<NavKey>(
                                            onPop = { contentKey ->
                                                entryViewModelOwners.remove(contentKey.toString())?.clear()
                                            },
                                            decorate = { entry ->
                                                val route = entry.contentKey.toString()
                                                val owner = entryViewModelOwners.getOrPut(route) {
                                                    MeiNavEntryViewModelStoreOwner(this@MainActivity)
                                                }
                                                CompositionLocalProvider(
                                                    LocalViewModelStoreOwner provides owner,
                                                ) {
                                                    entry.Content()
                                                }
                                            },
                                        )
                                    }
                                    val navEntries = rememberDecoratedNavEntries(
                                        backStack = backStack,
                                        entryDecorators = listOf(
                                            rememberSaveableStateHolderNavEntryDecorator(),
                                            viewModelEntryDecorator,
                                        ),
                                        entryProvider = { route ->
                                            val meiRoute = route as MeiRoute
                                            NavEntry(
                                                key = route,
                                                contentKey = meiRoute.route,
                                            ) {
                                                // Retained entries must observe preferences loaded after recreation.
                                                val entryNavigationItems = currentNavigationItems.value
                                                val entryTopAppBarScrollBehavior = rememberGlasmioAppBarScrollBehavior(
                                                    canScroll = {
                                                        !meiRoute.route.startsWith("search_result/") &&
                                                            (playerBottomSheetState.isCollapsed ||
                                                                playerBottomSheetState.isDismissed)
                                                    },
                                                )
                                                LaunchedEffect(currentRoute) {
                                                    if (meiRoute.route == currentRoute) {
                                                        entryTopAppBarScrollBehavior.state
                                                            .resetHeightOffset()
                                                    }
                                                }
                                                LaunchedEffect(active) {
                                                    if (active && meiRoute.route == currentRoute) {
                                                        entryTopAppBarScrollBehavior.state
                                                            .resetHeightOffset()
                                                    }
                                                }
                                                val entryPlayerAwareWindowInsets = remember(
                                                    meiRoute.route,
                                                    bottomInset,
                                                    navigationBottomMargin,
                                                    entryNavigationItems,
                                                    navigationBarExpanded,
                                                    playerDismissed,
                                                    windowsInsets,
                                                ) {
                                                    playerAwareWindowInsetsForRoute(
                                                        route = meiRoute.route,
                                                        navigationItems = entryNavigationItems,
                                                        navigationBarVisible = navigationBarExpanded,
                                                        playerDismissed = playerDismissed,
                                                        windowsInsets = windowsInsets,
                                                        bottomInset = bottomInset,
                                                        navigationBottomMargin = navigationBottomMargin,
                                                    )
                                                }
                                                CompositionLocalProvider(
                                                    LocalPlayerAwareWindowInsets provides
                                                        if (meiRoute.route == Screen.Search.route) entryPlayerAwareWindowInsets.union(imeInsets)
                                                        else entryPlayerAwareWindowInsets,
                                                ) {
                                                    MeiloXPageEntry(meiRoute, pageHeaders) {
                                                        navigationEntry(
                                                            route = meiRoute.route,
                                                            scrollBehavior = entryTopAppBarScrollBehavior,
                                                            isNavigationTab = entryNavigationItems.any {
                                                                it.route == meiRoute.route
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                    )
                                    val sceneStrategies = remember { listOf(SinglePaneSceneStrategy<NavKey>()) }
                                    val sceneState = rememberSceneState(
                                        entries = navEntries,
                                        sceneStrategies = sceneStrategies,
                                        onBack = navController::popBackStack,
                                    )
                                    val navigationEventState = rememberNavigationEventState(sceneState)
                                    val nonDisplayNavigationEventState = rememberNavigationEventState(sceneState)
                                    val canGoBack = sceneState.currentScene.previousEntries.isNotEmpty()
                                    // Keep both handlers registered so toggling preserves child back-handler priority.
                                    NavigationBackHandler(
                                        state = navigationEventState,
                                        isBackEnabled = predictiveBackAnimationEnabled && canGoBack,
                                        onBackCompleted = { navController.popBackStack() },
                                    )
                                    NavigationBackHandler(
                                        state = nonDisplayNavigationEventState,
                                        isBackEnabled = !predictiveBackAnimationEnabled && canGoBack,
                                        onBackCompleted = { navController.popBackStack() },
                                    )
                                    GlasmioNavigationScaffold(
                                        motion = headerMotion,
                                        header = { pageHeaders.entry(it).spec },
                                    ) {
                                        GlasmioNavDisplay(
                                            sceneState = sceneState,
                                            motion = headerMotion,
                                            // NavDisplay retains this state in remembered gesture calculations.
                                            navigationEventState = navigationEventState,
                                            // The scene-state overload needs this callback for in-content swipe back.
                                            onBack = { navController.popBackStack() },
                                            swipeBackEnabled = !active && !playerBottomSheetState.isContentVisible,
                                            modifier = Modifier.fillMaxSize(),
                                            transitionSpec = {
                                                if (usesHomeAlphaTransition()) {
                                                    homeAlphaTransition()
                                                } else {
                                                    iosTransitionSpec<NavKey>(navigationLayoutDirection, density.density).invoke(this)
                                                }
                                            },
                                            popTransitionSpec = {
                                                if (usesHomeAlphaTransition()) {
                                                    homeAlphaTransition()
                                                } else {
                                                    iosPopTransitionSpec<NavKey>(navigationLayoutDirection, density.density).invoke(this)
                                                }
                                            },
                                            predictivePopTransitionSpec = { swipeEdge ->
                                                if (usesHomeAlphaTransition()) {
                                                    homeAlphaTransition()
                                                } else {
                                                    iosPredictivePopTransitionSpec<NavKey>(navigationLayoutDirection, density.density)
                                                        .invoke(this, swipeEdge)
                                                }
                                            },
                                            transitionEffects = remember(
                                                navController.usesMiuixTransitionEffects,
                                            ) {
                                                if (navController.usesMiuixTransitionEffects) {
                                                    IosNavigationTransitionEffects
                                                } else {
                                                    NavDisplayTransitionEffects(
                                                        enableCornerClip = false,
                                                        dimAmount = 0f,
                                                        blockInputDuringTransition = false,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    AnimatedVisibility(
                                        visible = active,
                                        enter = fadeIn(),
                                        exit = fadeOut(),
                                    ) {
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .background(
                                                    if (query.text.isEmpty()) Color.Transparent
                                                    else MaterialTheme.colorScheme.background,
                                                ),
                                        ) {
                                            if (query.text.isNotEmpty()) {
                                                SearchScreen(
                                                    query = query.text,
                                                    onQueryChange = onQueryChange,
                                                    onSearch = { query, type ->
                                                        Screen.SearchResult.navigate(navController){
                                                            addPath(Uri.encode(query))
                                                            addPath(type.toString())
                                                        }
                                                    },
                                                    onDismiss = clearSearchInput,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(top = windowsInsets.asPaddingValues().calculateTopPadding())
                                                        .padding(searchResultsBottomPadding),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        if (selectionToolbar.content.value == null) AnimatedMiniPlayerLayer(
                            motion = miniPlayerChromeMotion,
                            hint = miniPlayerHint,
                            centerTravel = miniPlayerCenterTravel,
                            searchVerticalOffset = searchMiniPlayerOffset,
                            state = playerBottomSheetState,
                            backdrop = bottomControlsBackdrop,
                            pageDarkAppearance = pageDarkAppearance,
                            onBackgroundReadyChanged = onPlayerBackgroundReadyChanged,
                        )
                        AnimatedVisibility(
                            visible = active,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.align(Alignment.BottomCenter),
                        ) {
                            CompositionLocalProvider(
                                LocalGlassBackdrop provides bottomControlsBackdrop,
                            ) {
                                GlasmioSearchToolbar(
                                    query = query,
                                    onQueryChange = onQueryChange,
                                    onSearch = onSearch,
                                    onCancel = onSearchCancel,
                                    cancelLabel = stringResource(R.string.cancel),
                                    placeholder = stringResource(R.string.search_bar_search),
                                    focusRequester = searchBarFocusRequester,
                                    autoFocus = focusSearchOnEntry,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = bottomNavigationHorizontalMargin)
                                        .padding(searchToolbarBottomPadding)
                                        .offset(y = searchToolbarVerticalOffset),
                                )
                            }
                        }

                        val selectedIndex = navigationItems.firstOrNull { it.route == currentRoute }
                            ?: navigationItems.firstOrNull { it.name == lastSelectedTab }
                            ?: Index.Home
                        val navigationTabs = navigationItems.map { item ->
                            GlasmioTabItem(
                                key = item,
                                label = stringResource(item.labelRes),
                                symbol = item.symbol,
                            )
                        }
                        AnimatedVisibility(
                            visible = navigationPreferences != null && shouldAllowNavigationBar &&
                                selectionToolbar.content.value == null,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .zIndex(2f),
                        ) {
                            AnimatedBottomNavigationRow(
                                compactProgress = compactNavigationProgress,
                                hint = navigationHint,
                                items = navigationTabs,
                                selectedKey = selectedIndex,
                                onExpand = navigationScroll::expand,
                                onSelected = { screen ->
                                    setLastSelectedTab(screen.name)
                                    if (currentRoute == screen.route) {
                                        navController.currentBackStackEntry?.savedStateHandle?.set(
                                            "scrollToTop",
                                            true,
                                        )
                                    } else {
                                        navController.navigateTopLevel(screen.route)
                                    }
                                },
                                onSearchClick = {
                                    focusSearchOnEntry = true
                                    if (currentRoute != Screen.Search.route) {
                                        navController.navigate(Screen.Search.route)
                                    }
                                },
                                backdrop = bottomControlsBackdrop,
                                playerBottomSheetState = playerBottomSheetState,
                                bottomMargin = navigationBottomMargin,
                            )
                        }
                        selectionToolbar.content.value?.let { toolbar ->
                            Box(Modifier.align(Alignment.BottomCenter).zIndex(3f)
                                .fillMaxWidth().padding(horizontal = 16.dp)
                                .padding(bottom = bottomInset + NavigationBarBottomMargin)) {
                                CompositionLocalProvider(LocalGlassBackdrop provides bottomControlsBackdrop) {
                                    toolbar()
                                }
                            }
                        }
                        LaunchedEffect(recognizeClipboardLinks) {
                            if (recognizeClipboardLinks && !clipboardInspected) {
                                clipboardInspected = true
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val text = clipboard.primaryClip
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.coerceToText(this@MainActivity)
                                    ?.toString()
                                clipboardLink = text?.let(NeteaseMusicLinkParser::parse)
                            }
                        }
                        ClipboardLinkPrompt(
                            link = clipboardLink,
                            onDismiss = { clipboardLink = null },
                            onOpen = { detectedLink ->
                                clipboardLink = null
                                when (detectedLink) {
                                    is NeteaseMusicLink.ListenTogether -> {
                                        navController.currentBackStackEntry?.savedStateHandle?.set(
                                            "listen_invitation",
                                            detectedLink.invitationText,
                                        )
                                        Screen.ListenTogether.navigate(navController)
                                    }
                                    is NeteaseMusicLink.Song -> {
                                        lifecycleScope.launch {
                                            runCatching {
                                                apiService.getSongDetail(GetSongDetails(detectedLink.id.toString()))
                                                    .songs
                                                    .firstOrNull()
                                                    ?: error(getString(R.string.clipboard_song_not_found))
                                            }.onSuccess { song ->
                                                playerConnection?.playQueue(
                                                    ListQueue(
                                                        id = "clipboard-${song.id}",
                                                        title = song.name,
                                                        items = listOf(song.id.toString() to song.toMediaItem()),
                                                    ),
                                                )
                                            }.onFailure { error ->
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    getString(R.string.clipboard_open_failed, error.message.orEmpty()),
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            },
                        )
                        if (!pictureInPictureMode) NeteaseLegacyWebSessionNotice()
                        VersionUpdateAlert(
                            result = startupUpdateResult,
                            onDismiss = { startupUpdateResult = null },
                        )
                        }
                    }
                    }
                }
            }

        }
    }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readFloatingLyricsIntent(intent)
        applyFloatingLyricsRequest()
    }

    override fun onPostResume() {
        super.onPostResume()
        applyFloatingLyricsRequest()
    }

    private fun readFloatingLyricsIntent(intent: Intent?) {
        floatingLyricsRequest = when (intent?.action) {
            ENABLE_FLOATING_LYRICS -> true
            DISABLE_FLOATING_LYRICS -> false
            else -> return
        }
        intent.action = null
    }

    private fun applyFloatingLyricsRequest() {
        if (floatingLyricsRequest == null) return
        window.decorView.post {
            if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@post
            val enabled = floatingLyricsRequest ?: return@post
            val service = musicService ?: return@post
            floatingLyricsRequest = null
            if (enabled && !isInPictureInPictureMode) {
                enterPictureInPictureMode(floatingLyricsPipParams(this, service.player.isPlaying))
            } else if (!enabled) {
                moveTaskToBack(true)
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPictureMode = isInPictureInPictureMode
        musicService?.updateFloatingLyricsState(this)
    }
    override fun onDestroy() {
        musicService?.detachFloatingLyricsActivity(this)
        musicService = null
        super.onDestroy()
    }

}

private fun Preferences.glassHdrOutputEnabled(): Boolean =
    (this[GlassHdrEffectsKey] ?: false) &&
        (this[LiquidGlassEnabledKey] ?: true) &&
        !(this[ReduceTransparencyKey] ?: false)

private val homeNavigationRoutes = Screen.MainScreens.map { it.route }.toSet()
private val bottomNavigationHorizontalMargin = BottomBarHorizontalPadding
private val searchToolbarSurfaceHeight = 48.dp
private val searchToolbarVerticalOffset = 4.dp
private val searchControlSpacing = 8.dp

private fun Scene<NavKey>.currentRoute(): String? =
    entries.lastOrNull()?.contentKey as? String

@Composable
private fun BoxScope.AnimatedMiniPlayerLayer(
    motion: MiniPlayerChromeMotion,
    hint: MiniPlayerChromeHint,
    centerTravel: Dp,
    searchVerticalOffset: () -> Dp,
    state: GlasmioBottomSheetState,
    backdrop: Backdrop,
    pageDarkAppearance: Boolean?,
    onBackgroundReadyChanged: (Boolean) -> Unit,
) {
    val miniPlayerVerticalOffset = remember(motion, centerTravel, searchVerticalOffset) {
        {
            centerTravel * motion.verticalProgress.value +
                searchVerticalOffset()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1f),
    ) {
        CompositionLocalProvider(
            // MiniPlayer is rendered by BottomSheetPlayer's collapsed content. Give it the page
            // sample layer while keeping page/player glass out of that source.
            LocalGlassBackdrop provides backdrop,
            LocalMiniPlayerAppearance provides pageDarkAppearance,
            LocalMiniPlayerChromeMotion provides motion,
            LocalMiniPlayerChromeHint provides hint,
        ) {
            BottomSheetPlayer(
                state = state,
                modifier = Modifier.fillMaxSize(),
                compactMiniPlayerProgress = motion.contentProgress,
                miniPlayerVerticalOffset = miniPlayerVerticalOffset,
                onBackgroundReadyChanged = onBackgroundReadyChanged,
            )
        }
    }
}

@Composable
private fun AnimatedBottomNavigationRow(
    compactProgress: State<Float>,
    hint: MiniPlayerChromeHint,
    items: List<GlasmioTabItem<Index>>,
    selectedKey: Index,
    onExpand: () -> Unit,
    onSelected: (Index) -> Unit,
    onSearchClick: () -> Unit,
    backdrop: Backdrop,
    playerBottomSheetState: GlasmioBottomSheetState,
    bottomMargin: Dp,
) {
    GlasmioNavigationBarWithTrailingItem(
        items = items,
        trailingItem = GlasmioTabItem(Index.Search, stringResource(R.string.app_tab_search), SfSymbol.Search),
        selectedKey = selectedKey,
        onExpand = onExpand,
        onSelected = { item -> if (item == Index.Search) onSearchClick() else onSelected(item) },
        compactProgress = compactProgress,
        backdrop = backdrop,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = bottomNavigationHorizontalMargin)
            .glasmioNavigationScrollHint(hint.progress) {
                TransformOrigin(.5f, hint.pivotFromTop().toPx() / size.height.coerceAtLeast(1f))
            }
            .padding(bottom = bottomMargin)
            .offset {
                val slideOffset = (bottomMargin + NavigationBarHeight) *
                    playerBottomSheetState.progress.coerceIn(0f, 1f)
                IntOffset(x = 0, y = slideOffset.roundToPx())
            },
    )
}

private fun playerAwareWindowInsetsForRoute(
    route: String,
    navigationItems: List<Index>,
    navigationBarVisible: Boolean,
    playerDismissed: Boolean,
    windowsInsets: WindowInsets,
    bottomInset: Dp,
    navigationBottomMargin: Dp,
): WindowInsets {
    val allowsNavigationBar =
        navigationItems.fastAny { it.route == route } || route == Screen.Search.route
    var bottom = if (allowsNavigationBar || !playerDismissed) navigationBottomMargin else bottomInset
    if (allowsNavigationBar && (navigationBarVisible || route == Screen.Setting.route)) {
        bottom += NavigationBarHeight - NavigationBarBottomMargin
    }
    if (!playerDismissed || route == Screen.Setting.route) {
        bottom += MiniPlayerHeight
    }
    return windowsInsets
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
        .add(WindowInsets(top = 0.dp, bottom = bottom))
}

private fun AnimatedContentTransitionScope<Scene<NavKey>>.usesHomeAlphaTransition(): Boolean {
    return initialState.currentRoute() in homeNavigationRoutes &&
        targetState.currentRoute() in homeNavigationRoutes
}

private fun AnimatedContentTransitionScope<Scene<NavKey>>.homeAlphaTransition(): ContentTransform =
    fadeIn() togetherWith fadeOut()
