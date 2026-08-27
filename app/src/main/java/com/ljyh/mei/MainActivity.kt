package com.ljyh.mei

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.zIndex
import androidx.datastore.preferences.core.edit
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.NavDisplayTransitionEffects
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.ljyh.mei.constants.AppBarHeight
import com.ljyh.mei.constants.AppAppearance
import com.ljyh.mei.constants.AppAppearanceKey
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.constants.DynamicThemeKey
import com.ljyh.mei.constants.AccentColorKey
import com.ljyh.mei.constants.PodcastsEnabledKey
import com.ljyh.mei.constants.DownloadsEnabledKey
import com.ljyh.mei.constants.CloudMusicEnabledKey
import com.ljyh.mei.constants.ListeningHistoryEnabledKey
import com.ljyh.mei.constants.PodcastsTabEnabledKey
import com.ljyh.mei.constants.DownloadsTabEnabledKey
import com.ljyh.mei.constants.CloudMusicTabEnabledKey
import com.ljyh.mei.constants.ListeningHistoryTabEnabledKey
import com.ljyh.mei.constants.NavigationTabOrderKey
import com.ljyh.mei.constants.LastSelectedTabKey
import com.ljyh.mei.constants.PlayerKeepScreenOnKey
import com.ljyh.mei.constants.RecognizeClipboardLinksKey
import com.ljyh.mei.constants.MiniPlayerHeight
import com.ljyh.mei.constants.NavigationBarHeight
import com.ljyh.mei.constants.NavigationBarBottomMargin
import com.ljyh.mei.constants.UserAgent
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
import com.ljyh.mei.ui.component.sheet.rememberBottomSheetState
import com.ljyh.mei.ui.component.sheet.BottomSheetState
import com.ljyh.mei.ui.component.utils.appBarScrollBehavior
import com.ljyh.mei.ui.component.utils.resetHeightOffset
import com.ljyh.mei.ui.local.LocalDatabase
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.local.LocalUserData
import com.ljyh.mei.ui.glass.GlassBottomBar
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.GlassTabItem
import com.ljyh.mei.ui.glass.GlassSurfaceStyle
import com.ljyh.mei.ui.glass.IosBottomSearchToolbar
import com.ljyh.mei.ui.glass.CompactBottomControlIconSize
import com.ljyh.mei.ui.glass.LocalGlassBackdrop
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.LocalBlurBackdrop
import com.ljyh.mei.ui.glass.defaultGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.glass.rememberCrossWindowBackdrop
import com.ljyh.mei.ui.glass.trackBackdropPosition
import com.ljyh.mei.ui.screen.Index
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.navigationEntry
import com.ljyh.mei.ui.screen.search.SearchScreen
import com.ljyh.mei.ui.navigation.MeiNavEntryViewModelStoreOwner
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.navigation.MeiRoute
import com.ljyh.mei.ui.theme.MusicTheme
import com.ljyh.mei.utils.log.CrashHandler
import com.ljyh.mei.utils.cache.preloadImage
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.get
import com.ljyh.mei.utils.log.FileLoggingTree
import com.ljyh.mei.utils.netease.NeteaseUtils.getAndroidId
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.VersionUpdateChecker
import com.ljyh.mei.utils.VersionUpdateResult
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
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


    @androidx.annotation.OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.S)
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val headerInterceptor = Interceptor { chain ->
            val newRequest = chain.request().newBuilder()
                .addHeader("User-Agent", UserAgent)
                .build()
            chain.proceed(newRequest)
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(headerInterceptor)
            .build()

        setContent {
            val context = this@MainActivity
            val lifecycleOwner = LocalLifecycleOwner.current
            val backStack = rememberNavBackStack(MeiRoute(Screen.Home.route))
            val navController = remember(backStack) {
                MeiNavigator(context = context, backStack = backStack)
            }
            var active by rememberSaveable {
                mutableStateOf(false)
            }
            val dynamicTheme by rememberPreference(DynamicThemeKey, defaultValue = true)
            val accentColorArgb by rememberPreference(AccentColorKey, 0xFFFF3B30L)
            val appAppearance by rememberEnumPreference(AppAppearanceKey, AppAppearance.System)
            val (lastSelectedTab, setLastSelectedTab) = rememberPreference(LastSelectedTabKey, Index.Home.name)
            val recognizeClipboardLinks by rememberPreference(RecognizeClipboardLinksKey, false)
            val podcastsEnabled by rememberPreference(PodcastsEnabledKey, defaultValue = true)
            val downloadsEnabled by rememberPreference(DownloadsEnabledKey, defaultValue = true)
            val cloudMusicEnabled by rememberPreference(CloudMusicEnabledKey, defaultValue = true)
            val listeningHistoryEnabled by rememberPreference(ListeningHistoryEnabledKey, defaultValue = true)
            val podcastsTabEnabled by rememberPreference(PodcastsTabEnabledKey, defaultValue = false)
            val downloadsTabEnabled by rememberPreference(DownloadsTabEnabledKey, defaultValue = false)
            val cloudMusicTabEnabled by rememberPreference(CloudMusicTabEnabledKey, defaultValue = false)
            val listeningHistoryTabEnabled by rememberPreference(ListeningHistoryTabEnabledKey, defaultValue = false)
            val keepScreenOnInPlayer by rememberPreference(PlayerKeepScreenOnKey, false)
            val navigationTabOrder by rememberPreference(
                NavigationTabOrderKey,
                Index.DefaultOrder.joinToString(",", transform = Index::name),
            )
            var playerConnection by remember { mutableStateOf<PlayerConnection?>(null) }
            var clipboardLink by remember { mutableStateOf<NeteaseMusicLink?>(null) }
            var clipboardInspected by rememberSaveable { mutableStateOf(false) }
            var startupUpdateResult by remember { mutableStateOf<VersionUpdateResult?>(null) }

            LaunchedEffect(Unit) {
                val result = VersionUpdateChecker.check(BuildConfig.VERSION_NAME)
                if (result is VersionUpdateResult.UpdateAvailable) {
                    startupUpdateResult = result
                }
            }

            var isMeasured by remember { mutableStateOf(false) }
            DisposableEffect(Unit) {
                val intent = Intent(context, MusicService::class.java)

                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        Timber.tag("MainActivity").d("Service Connected") // 添加日志
                        if (service is MusicService.MusicBinder) {
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
                    context.unbindService(connection)
                    playerConnection = null
                }
            }
            setSingletonImageLoaderFactory {
                ImageLoader.Builder(this)
                    .components {
                        add(OkHttpNetworkFetcherFactory(okHttpClient))
                        add(AnimatedImageDecoder.Factory())
                    }
                    .crossfade(true)
                    .diskCache {
                        DiskCache.Builder()
                            .directory(File(this@MainActivity.cacheDir, "image_cache"))
                            .maxSizePercent(0.1)
                            .build()
                    }
                    .build()
            }
            var targetThemeColor by remember { mutableStateOf(Color.Black) }

            LaunchedEffect(playerConnection) {
                Timber.tag("MainActivity").d("playerConnection: $playerConnection")
                val playerConnection = playerConnection ?: return@LaunchedEffect
                val player = playerConnection.service.player
                playerConnection.service.currentMediaMetadata.collect { song->
                    if (dynamicTheme && song != null) {
                        val context = this@MainActivity
                        launch {
                            Timber.tag("MainActivity").d("获取当前歌曲颜色: $song")
                            val color = colorRepository.getColorOrExtract(context, song.coverUrl)
                            targetThemeColor = color
                        }
                        Timber.tag("MainActivity").d("获取歌曲颜色: $targetThemeColor")

                        val nextIndex = player.nextMediaItemIndex
                        if (nextIndex != C.INDEX_UNSET) {
                            val nextUrl = player.getMediaItemAt(nextIndex).mediaMetadata.artworkUri?.toString()
                            if (!nextUrl.isNullOrEmpty()) {
                                Timber.tag("MainActivity").d("获取下一首歌曲颜色: $nextUrl")
                                launch(Dispatchers.IO) {
                                    colorRepository.getColorOrExtract(context, nextUrl)
                                    preloadImage(context, nextUrl)
                                }
                            }
                        }
                    }
                }

            }
            var navigationBarVisible by remember { mutableStateOf(true) }
            val nestedScrollConnection = remember {
                object : NestedScrollConnection {

                    override fun onPreScroll(
                        available: Offset,
                        source: NestedScrollSource
                    ): Offset {

                        if (available.y < -10f) {
                            // 向下滑
                            navigationBarVisible = false
                        }

                        if (available.y > 10f) {
                            // 向上滑
                            navigationBarVisible = true
                        }

                        return Offset.Zero
                    }
                }
            }



            val systemDark = isSystemInDarkTheme()
            val effectiveDark = when (appAppearance) {
                AppAppearance.System -> systemDark
                AppAppearance.Light -> false
                AppAppearance.Dark -> true
            }
            MusicTheme(
                seedColor = if (dynamicTheme) targetThemeColor else Color(accentColorArgb.toInt()),
                isDark = effectiveDark,
            ) {
                val glassColors = defaultGlassColors(
                    isDark = effectiveDark,
                    accent = if (effectiveDark) Color(0xFFFF4245) else Color(0xFFFF3B30),
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
                val bottomControlsBackdrop = rememberCombinedBackdrop(
                    glassBackdrop,
                    rememberCrossWindowBackdrop(bottomBackdrop),
                )
                CompositionLocalProvider(
                    LocalGlassBackdrop provides glassBackdrop,
                    LocalGlassColors provides glassColors,
                    LocalBlurBackdrop provides bottomControlsBackdrop,
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .nestedScroll(nestedScrollConnection)
                            .onSizeChanged {
                                isMeasured = true
                            }
                    ) {
                    val focusManager = LocalFocusManager.current
                    val density = LocalDensity.current
                    val windowsInsets = WindowInsets.systemBars
                    val currentRoute = navController.currentRoute

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
                        podcastsTabEnabled,
                        downloadsTabEnabled,
                        cloudMusicTabEnabled,
                        listeningHistoryTabEnabled,
                        navigationTabOrder,
                    ) {
                        val available = buildList {
                            add(Index.Home)
                            add(Index.FindMusic)
                            if (podcastsEnabled && podcastsTabEnabled) add(Index.Podcasts)
                            add(Index.Library)
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


                    val shouldAllowNavigationBar = remember(currentRoute, active) {
                        (currentRoute == null ||
                                navigationItems.fastAny { it.route == currentRoute } ||
                                currentRoute == Screen.Search.route) &&
                                !active
                    }
                    val shouldCompactNavigationBar = shouldAllowNavigationBar && !navigationBarVisible



                    val searchBarFocusRequester = remember { FocusRequester() }
                    // Keep the anchor stable while the navigation morphs. The mini player
                    // moves inside the collapsed host with the same continuous spring.
                    val collapsedBottomReservation = if (shouldAllowNavigationBar || active) {
                        NavigationBarHeight
                    } else {
                        NavigationBarBottomMargin
                    }
                    val collapsedBound = bottomInset +
                        collapsedBottomReservation +
                        MiniPlayerHeight

                    val compactNavigationProgress = animateFloatAsState(
                        targetValue = if (shouldCompactNavigationBar) 1f else 0f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "CompactBottomNavigation",
                    )
                    val playerBottomSheetState = rememberBottomSheetState(
                        dismissedBound = 0.dp,
                        collapsedBound = collapsedBound,
                        expandedBound = maxHeight,
                    )
                    val windowInsetsController = remember {
                        WindowInsetsControllerCompat(window, window.decorView)
                    }
                    val isPlayerPage = playerBottomSheetState.isExpanded ||
                        playerBottomSheetState.progress >= 0.99f
                    SideEffect {
                        val transparent = android.graphics.Color.TRANSPARENT
                        enableEdgeToEdge(
                            statusBarStyle = if (isPlayerPage) {
                                // The expanded player renders artwork behind the status bar;
                                // keep its foreground white regardless of the app theme.
                                SystemBarStyle.dark(transparent)
                            } else {
                                SystemBarStyle.auto(transparent, transparent) { effectiveDark }
                            },
                            navigationBarStyle = SystemBarStyle.auto(transparent, transparent) {
                                effectiveDark
                            },
                        )
                        // Keep this explicit because the API 35+ edge-to-edge implementation can
                        // retain the previous appearance while the bottom sheet settles.
                        windowInsetsController.isAppearanceLightStatusBars =
                            !isPlayerPage && !effectiveDark
                        if (isPlayerPage && keepScreenOnInPlayer) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        // Keep the navigation bar transparent instead of applying the platform
                        // contrast scrim.
                        window.navigationBarColor = transparent
                        window.isNavigationBarContrastEnforced = false
                        window.navigationBarDividerColor = transparent
                    }
                    val (query, onQueryChange) = rememberSaveable(stateSaver = TextFieldValue.Saver) {
                        mutableStateOf(TextFieldValue())
                    }
                    val onActiveChange: (Boolean) -> Unit = { newActive ->
                        active = newActive
                        if (!newActive) {
                            focusManager.clearFocus()
                            if (navigationItems.fastAny { it.route == currentRoute } ||
                                currentRoute == Screen.Search.route
                            ) {
                                onQueryChange(TextFieldValue())
                            }
                        }
                    }

                    val onSearchCancel = {
                        val isSearchRoute = currentRoute == Screen.Search.route
                        onActiveChange(false)
                        if (isSearchRoute) {
                            navController.popBackStack()
                        }
                    }

                    val onSearch: (String) -> Unit = {
                        if (it.isNotEmpty()) {
                            onActiveChange(false)
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
                    LaunchedEffect(isMeasured, playerConnection) {
                        if (isMeasured && playerConnection?.player?.currentMediaItem != null) {
                            playerBottomSheetState.collapseSoft()
                        }
                    }

                    LaunchedEffect(playerConnection) {
                        val player = playerConnection?.player ?: return@LaunchedEffect
                        if (player.currentMediaItem == null) {
                            if (!playerBottomSheetState.isDismissed) {
                                playerBottomSheetState.dismiss()
                            }
                        } else {
                            if (playerBottomSheetState.isDismissed) {
                                playerBottomSheetState.collapseSoft()
                            }
                        }
                    }


                    DisposableEffect(playerConnection, playerBottomSheetState) {
                        val player =
                            playerConnection?.player ?: return@DisposableEffect onDispose { }
                        val listener = object : Player.Listener {
                            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && mediaItem != null && playerBottomSheetState.isDismissed) {
                                    playerBottomSheetState.collapseSoft()
                                }
                            }
                        }
                        player.addListener(listener)
                        onDispose {
                            player.removeListener(listener)
                        }
                    }
                    CompositionLocalProvider(
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
                                    .layerBackdrop(bottomBackdrop)
                                    .trackBackdropPosition(bottomBackdrop),
                            ) {
                                CompositionLocalProvider(
                                    LocalGlassBackdrop provides glassBackdrop,
                                ) {
                                    val entryViewModelOwners = remember {
                                        mutableMapOf<String, MeiNavEntryViewModelStoreOwner>()
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
                                    NavDisplay(
                                        backStack = backStack,
                                        modifier = Modifier.fillMaxSize(),
                                        onBack = navController::popBackStack,
                                        entryDecorators = listOf(
                                            rememberSaveableStateHolderNavEntryDecorator(),
                                            viewModelEntryDecorator,
                                        ),
                                        transitionSpec = {
                                            if (usesHomeAlphaTransition()) {
                                                homeAlphaTransition()
                                            } else {
                                                defaultTransitionSpec<NavKey>().invoke(this)
                                            }
                                        },
                                        popTransitionSpec = {
                                            if (usesHomeAlphaTransition()) {
                                                homeAlphaTransition()
                                            } else {
                                                defaultPopTransitionSpec<NavKey>().invoke(this)
                                            }
                                        },
                                        predictivePopTransitionSpec = { swipeEdge ->
                                            if (usesHomeAlphaTransition()) {
                                                homeAlphaTransition()
                                            } else {
                                                defaultPredictivePopTransitionSpec<NavKey>()
                                                    .invoke(this, swipeEdge)
                                            }
                                        },
                                        transitionEffects = remember(
                                            navController.usesMiuixTransitionEffects,
                                        ) {
                                            if (navController.usesMiuixTransitionEffects) {
                                                NavDisplayTransitionEffects(
                                                    enableCornerClip = true,
                                                    dimAmount = 0.5f,
                                                    blockInputDuringTransition = false,
                                                )
                                            } else {
                                                NavDisplayTransitionEffects(
                                                    enableCornerClip = false,
                                                    dimAmount = 0f,
                                                    blockInputDuringTransition = false,
                                                )
                                            }
                                        },
                                        entryProvider = { route ->
                                            val meiRoute = route as MeiRoute
                                            NavEntry(
                                                key = route,
                                                contentKey = meiRoute.route,
                                            ) {
                                                val entryTopAppBarScrollBehavior = appBarScrollBehavior(
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
                                                    navigationItems,
                                                    navigationBarVisible,
                                                    playerBottomSheetState.isDismissed,
                                                    windowsInsets,
                                                ) {
                                                    playerAwareWindowInsetsForRoute(
                                                        route = meiRoute.route,
                                                        navigationItems = navigationItems,
                                                        navigationBarVisible = navigationBarVisible,
                                                        playerDismissed = playerBottomSheetState.isDismissed,
                                                        windowsInsets = windowsInsets,
                                                        bottomInset = bottomInset,
                                                    )
                                                }
                                                CompositionLocalProvider(
                                                    LocalPlayerAwareWindowInsets provides
                                                        entryPlayerAwareWindowInsets,
                                                ) {
                                                    navigationEntry(
                                                        route = meiRoute.route,
                                                        scrollBehavior = entryTopAppBarScrollBehavior,
                                                    )
                                                }
                                            }
                                        },
                                    )
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
                                                    onDismiss = {
                                                        onActiveChange(false)
                                                    },
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(top = windowsInsets.asPaddingValues().calculateTopPadding())
                                                        .padding(bottom = bottomInset + NavigationBarHeight),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        AnimatedMiniPlayerLayer(
                            compactProgress = compactNavigationProgress,
                            state = playerBottomSheetState,
                            backdrop = bottomControlsBackdrop,
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
                                IosBottomSearchToolbar(
                                    query = query,
                                    onQueryChange = onQueryChange,
                                    onSearch = onSearch,
                                    onCancel = onSearchCancel,
                                    cancelLabel = stringResource(R.string.cancel),
                                    placeholder = stringResource(R.string.search_bar_search),
                                    focusRequester = searchBarFocusRequester,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp)
                                        .padding(bottom = bottomInset + NavigationBarBottomMargin),
                                )
                            }
                        }

                        val selectedIndex = navigationItems.firstOrNull { it.route == currentRoute }
                            ?: navigationItems.firstOrNull { it.name == lastSelectedTab }
                            ?: Index.Home
                        val navigationTabs = navigationItems.map { item ->
                            GlassTabItem(
                                key = item,
                                label = stringResource(item.labelRes),
                                symbol = item.symbol,
                            )
                        }
                        AnimatedVisibility(
                            visible = shouldAllowNavigationBar,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .zIndex(2f),
                        ) {
                            AnimatedBottomNavigationRow(
                                compactProgress = compactNavigationProgress,
                                items = navigationTabs,
                                selectedKey = selectedIndex,
                                onExpand = { navigationBarVisible = true },
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
                                    if (currentRoute != Screen.Search.route) {
                                        navController.navigate(Screen.Search.route)
                                    }
                                    onActiveChange(true)
                                },
                                backdrop = bottomControlsBackdrop,
                                playerBottomSheetState = playerBottomSheetState,
                                bottomInset = bottomInset,
                            )
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
                        clipboardLink?.let { detectedLink ->
                            ClipboardLinkPrompt(
                                link = detectedLink,
                                onDismiss = { clipboardLink = null },
                                onOpen = {
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
                        }
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

    override fun onStart() {
        super.onStart()
    }
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPictureMode = isInPictureInPictureMode
    }
    override fun onDestroy() {
        super.onDestroy()
    }

}

private val homeNavigationRoutes = Screen.MainScreens.map { it.route }.toSet()

private fun Scene<NavKey>.currentRoute(): String? =
    entries.lastOrNull()?.contentKey as? String

@Composable
private fun BoxScope.AnimatedMiniPlayerLayer(
    compactProgress: State<Float>,
    state: BottomSheetState,
    backdrop: Backdrop,
) {
    val miniPlayerVerticalOffset = remember(compactProgress) {
        { (NavigationBarHeight - 16.dp) * compactProgress.value }
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
        ) {
            BottomSheetPlayer(
                state = state,
                modifier = Modifier.fillMaxSize(),
                compactMiniPlayerProgress = compactProgress,
                miniPlayerVerticalOffset = miniPlayerVerticalOffset,
            )
        }
    }
}

@Composable
private fun AnimatedBottomNavigationRow(
    compactProgress: State<Float>,
    items: List<GlassTabItem<Index>>,
    selectedKey: Index,
    onExpand: () -> Unit,
    onSelected: (Index) -> Unit,
    onSearchClick: () -> Unit,
    backdrop: Backdrop,
    playerBottomSheetState: BottomSheetState,
    bottomInset: Dp,
) {
    val progress = compactProgress.value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = bottomInset + NavigationBarBottomMargin)
            .offset {
                val slideOffset = (bottomInset + NavigationBarHeight) *
                    playerBottomSheetState.progress.coerceIn(0f, 1f)
                IntOffset(x = 0, y = slideOffset.roundToPx())
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassBottomBar(
            items = items,
            selectedKey = selectedKey,
            onExpand = onExpand,
            onSelected = onSelected,
            backdrop = backdrop,
            compactProgress = progress,
            compactSize = MiniPlayerHeight,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(width = 8.dp, height = 1.dp))
        Box(
            modifier = Modifier.size(64.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            GlassIconButton(
                onClick = onSearchClick,
                backdrop = backdrop,
                style = GlassSurfaceStyle.Navigation,
                modifier = Modifier.size(64.dp - 16.dp * progress),
            ) {
                SfIcon(
                    SfSymbol.Search,
                    contentDescription = stringResource(R.string.app_tab_search),
                    tint = LocalGlassColors.current.content,
                    size = 26.dp + (CompactBottomControlIconSize - 26.dp) * progress,
                    weight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun playerAwareWindowInsetsForRoute(
    route: String,
    navigationItems: List<Index>,
    navigationBarVisible: Boolean,
    playerDismissed: Boolean,
    windowsInsets: WindowInsets,
    bottomInset: Dp,
): WindowInsets {
    val allowsNavigationBar =
        navigationItems.fastAny { it.route == route } || route == Screen.Search.route
    var bottom = bottomInset
    if (allowsNavigationBar && navigationBarVisible) bottom += NavigationBarHeight
    if (!playerDismissed) {
        bottom += MiniPlayerHeight
        if (!allowsNavigationBar) bottom += NavigationBarBottomMargin
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
