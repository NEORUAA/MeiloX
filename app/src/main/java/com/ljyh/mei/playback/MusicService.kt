package com.ljyh.mei.playback

import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.ui.text.toLowerCase
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaConstants
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import coil3.ImageLoader
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.ljyh.mei.MainActivity
import com.ljyh.mei.ui.component.player.floatingLyricsPipParams
import com.ljyh.mei.ui.component.player.hasFloatingLyricsBackgroundPermission
import androidx.lifecycle.Lifecycle
import com.ljyh.mei.R
import com.ljyh.mei.constants.IsShuffleModeKey
import com.ljyh.mei.constants.LoopPlaybackKey
import com.ljyh.mei.constants.NotificationAction
import com.ljyh.mei.constants.NotificationLeftActionKey
import com.ljyh.mei.constants.NotificationRightActionKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.constants.CloudShuffleEnabledKey
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.constants.MusicQualityKey
import com.ljyh.mei.constants.NoAudioSourceKey
import com.ljyh.mei.constants.RepeatModeKey
import com.ljyh.mei.constants.UserAgent
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.network.netease.NcblSongInfo
import com.ljyh.mei.data.network.netease.NeteaseClientLogClient
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.data.repository.PlayerRepository
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.neteaseSessionFingerprint
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.di.repository.HistoryRepository
import com.ljyh.mei.di.repository.SongRepository
import com.ljyh.mei.extensions.currentMetadata
import com.ljyh.mei.extensions.mediaItems
import com.ljyh.mei.playback.CacheManager.getCacheDataSourceFactory
import com.ljyh.mei.playback.CacheManager.findFullyCachedPlaybackKey
import com.ljyh.mei.playback.CacheManager.removePlaybackEntries
import com.ljyh.mei.utils.CoilBitmapLoader
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.get
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.Locale.getDefault
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject


@UnstableApi
@AndroidEntryPoint
class MusicService : MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {

    lateinit var player: StableDeckPlayer
    val beatMeter = PlaybackBeatMeter()
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var systemLyricsBridge: SystemLyricsBridge

    @Inject
    lateinit var lyricManager: com.ljyh.mei.utils.lyric.LyricManager
    private lateinit var autoMixController: AutoMixController
    private lateinit var equalizerConfigurationState: EqualizerConfigurationState
    val context = this
    private lateinit var mediaSession: MediaLibrarySession
    private var notificationLeftAction = NotificationAction.PlayMode
    private var notificationRightAction = NotificationAction.Like
    private var notificationSignedIn = false
    private var notificationAccount: Pair<String?, String?>? = null
    private var notificationLikeJob: Job? = null
    private var notificationButtons: List<CommandButton> = emptyList()
    private var floatingLyricsActivity = WeakReference<MainActivity>(null)
    private var floatingLyricsEnabled = false

    @Inject
    lateinit var playerRepository: PlayerRepository

    lateinit var sleepTimer: SleepTimer
    lateinit var sleepTimerNotification: SleepTimerNotification
        private set
    private val serviceJob = SupervisorJob()
    var scope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var automaticCacheJob: Job? = null
    private lateinit var playbackHistoryReporter: PlaybackHistoryReporter
    private val playbackHistorySession = PlaybackHistorySession()
    private var playbackSnapshotJob: Job? = null
    private var periodicSnapshotJob: Job? = null
    private var playbackRestoreJob: Job? = null
    private lateinit var playbackPersistence: PlaybackPersistence
    private var isRestoringPlayback = true
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var baseMediaSourceFactory: DefaultMediaSourceFactory
    private lateinit var preloadManager: DefaultPreloadManager
    private val preloadStrategy = MusicPreloadStrategy()
    private var preloadedMediaItem: MediaItem? = null
    val currentMediaMetadata = MutableStateFlow<MediaMetadata?>(null)
    val availableMusicQualities = MutableStateFlow<List<MusicQuality>>(emptyList())
    val currentMusicQuality = MutableStateFlow<MusicQuality?>(null)
    val localPlaybackInfo = MutableStateFlow<LocalPlaybackInfo?>(null)
    val isPlayingLocalSource = MutableStateFlow(false)
    val isLoadingMusicQualities = MutableStateFlow(false)
    private var availableQualitiesJob: Job? = null
    private var qualityMediaId: String? = null
    private var qualityGeneration = 0L
    private var isRefreshingPlaybackSource = false
    private val supportsDolby by lazy {
        runCatching {
            MediaCodecUtil.getDecoderInfos(MimeTypes.AUDIO_E_AC3_JOC, false, false).isNotEmpty() ||
                MediaCodecUtil.getDecoderInfos(MimeTypes.AUDIO_E_AC3, false, false).isNotEmpty()
        }.getOrDefault(false)
    }

    @Inject
    lateinit var mediaUriProvider: MediaUriProvider

    private val playbackFailures = PlaybackFailureTracker()
    private var failureProgressJob: Job? = null
    private var sourceRecoveryJob: Job? = null
    private var sourceRecoveryMediaId: String? = null
    private var sourceRecoveryEntryId: String? = null
    private var sourceRecoveryAttempts = 0
    private var rangeRecoveryAttempts = 0
    private var stallRecoveryAttempts = 0

    private val binder = MusicBinder()

    lateinit var queueManager: PlaybackQueueManager
    var queueTitle: String? = null
    private var openedAudioEffectSessionId = C.AUDIO_SESSION_ID_UNSET

    @Inject
    lateinit var weApiService: WeApiService
    @Inject
    lateinit var apiService: ApiService


    @Inject
    lateinit var historyRepository: HistoryRepository

    @Inject
    lateinit var meloXRepository: MeloXRepository

    @Inject
    lateinit var neteaseClientLogClient: NeteaseClientLogClient

    @Inject
    lateinit var songRepository: SongRepository
    @Inject
    lateinit var listenTogetherStore: ListenTogetherStore
    @Inject
    lateinit var automaticCacheController: AutomaticCacheController

    override fun onCreate() {
        super.onCreate()
        mediaUriProvider.clearQualityState()
        mediaUriProvider.onSourceResolved = { mediaId, source ->
            scope.launch {
                if (::player.isInitialized && player.currentMediaItem?.mediaId == mediaId &&
                    mediaUriProvider.resolvedMediaSource(mediaId) == source
                ) {
                    publishResolvedQuality(source)
                }
            }
        }
        playbackHistoryReporter = PlaybackHistoryReporter(meloXRepository, neteaseClientLogClient)
        equalizerConfigurationState = EqualizerConfigurationState(this, scope)
        baseMediaSourceFactory = DefaultMediaSourceFactory(createDataSourceFactory())
            .setLoadErrorHandlingPolicy(MusicLoadErrorHandlingPolicy()) // 应用自定义错误策略
        val preloadManagerBuilder = DefaultPreloadManager.Builder(
            this,
            preloadStrategy
        )
            .setMediaSourceFactory(baseMediaSourceFactory) // 告诉管理器用什么去下载

        val playerMediaSourceFactory = object : MediaSource.Factory {
            // 必须实现的方法，委托给 baseMediaSourceFactory
            override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = apply {
                baseMediaSourceFactory.setDrmSessionManagerProvider(provider)
            }

            override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = apply {
                baseMediaSourceFactory.setLoadErrorHandlingPolicy(policy)
            }

            override fun getSupportedTypes(): IntArray = baseMediaSourceFactory.supportedTypes

            // 创建 MediaSource
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                // 优先问 PreloadManager 要预加载好的 Source
                return preloadManager.getMediaSource(mediaItem)
                // 如果没预加载过，就创建一个新的
                    ?: baseMediaSourceFactory.createMediaSource(mediaItem)
            }
        }


        setMediaNotificationProvider(
            PlaybackNotificationProvider(this, NOTIFICATION_ID, CHANNEL_ID)
        )

        val playbackAudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // Media3 1.11 dynamic scheduling batches position updates. Both decks need
        // continuous timestamps for karaoke highlighting and beat synchronization.
        val firstDeck = ExoPlayer.Builder(this)
            .experimentalSetDynamicSchedulingEnabled(false)
            .setStuckBufferingDetectionTimeoutMs(STUCK_BUFFERING_TIMEOUT_MS)
            //媒体源工厂
            .setMediaSourceFactory(playerMediaSourceFactory)
            //渲染器工厂
            .setRenderersFactory(createRenderersFactory())
            //处理音频焦点变化和音频播放行为
            .setHandleAudioBecomingNoisy(true)
            //设置音频的唤醒模式，保证设备在网络连接上不进入休眠状态。
            //它并不会阻止屏幕变暗或关闭；它主要是为了防止CPU进入睡眠状态以及确保网络连接的活跃，从而避免播放中断。
            .setWakeMode(C.WAKE_MODE_NETWORK)
            //音频属性
            .setAudioAttributes(playbackAudioAttributes, true)
            //快进快退时间
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()
            .also { builtPlayer ->
                // PreloadMediaSource must be prepared on the same looper that consumes it.
                preloadManager = preloadManagerBuilder
                    .setPreloadLooper(builtPlayer.playbackLooper)
                    .build()
            }
        val secondDeck = ExoPlayer.Builder(this)
            .experimentalSetDynamicSchedulingEnabled(false)
            .setStuckBufferingDetectionTimeoutMs(STUCK_BUFFERING_TIMEOUT_MS)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(createDataSourceFactory())
                    .setLoadErrorHandlingPolicy(MusicLoadErrorHandlingPolicy()),
            )
            .setRenderersFactory(createRenderersFactory())
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(playbackAudioAttributes, false)
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()
        player = StableDeckPlayer(firstDeck, secondDeck, playbackAudioAttributes)
        listOf(firstDeck, secondDeck).forEachIndexed { index, deck ->
            deck.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    Timber.tag("PlaybackAudio").i("deck=%s decoder=%s initMs=%s", index, decoderName, initializationDurationMs)
                }

                override fun onAudioInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    format: Format,
                    decoderReuseEvaluation: DecoderReuseEvaluation?,
                ) {
                    Timber.tag("PlaybackAudio").i(
                        "deck=%s mime=%s sampleRate=%s channels=%s pcm=%s",
                        index, format.sampleMimeType, format.sampleRate, format.channelCount, format.pcmEncoding,
                    )
                }

                override fun onAudioTrackInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    audioTrackConfig: AudioSink.AudioTrackConfig,
                ) {
                    Timber.tag("PlaybackAudio").i(
                        "deck=%s outputRate=%s encoding=%s channels=%s buffer=%s offload=%s",
                        index, audioTrackConfig.sampleRate, audioTrackConfig.encoding,
                        audioTrackConfig.channelConfig, audioTrackConfig.bufferSize, audioTrackConfig.offload,
                    )
                }
            })
        }
        player.addListener(this)
        sleepTimerNotification = SleepTimerNotification(this)
        sleepTimer = SleepTimer(scope, player, onStateChanged = sleepTimerNotification::update)
        player.addListener(sleepTimer)
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (isRefreshingPlaybackSource) return
                if (::playbackPersistence.isInitialized) {
                    playbackPersistence.invalidateQueue()
                    schedulePlaybackSnapshot()
                }
                updatePreload()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (isRefreshingPlaybackSource) return
                updatePreload()
                automaticCacheJob?.cancel()
                if (mediaItem != null) {
                    automaticCacheJob = scope.launch {
                        delay(AUTOMATIC_CACHE_DELAY_MS)
                        try {
                            automaticCacheController.recordPlayback(mediaItem)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Timber.tag("MusicService").e(error, "record automatic cache playback error")
                        }
                    }
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updatePreload()
                schedulePlaybackSnapshot()
                scope.launch {
                    context.dataStore.edit { preferences ->
                        preferences[IsShuffleModeKey] = shuffleModeEnabled
                    }
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                updatePreload()
                schedulePlaybackSnapshot()
                scope.launch {
                    context.dataStore.edit { preferences ->
                        preferences[RepeatModeKey] = repeatMode
                    }
                }
            }
        })

        queueManager = PlaybackQueueManager(
            player, apiService, weApiService, scope,
            serverShuffleAllowed = { listenTogetherStore.state.value.room == null },
        )
        scope.launch {
            context.dataStore.data
                .map { it[CloudShuffleEnabledKey] ?: true }
                .distinctUntilChanged()
                .collect { queueManager.setCloudShuffleEnabled(it) }
        }
        scope.launch {
            listenTogetherStore.state.collect { state ->
                if (state.room != null) queueManager.cancelServerShuffle()
            }
        }
        playbackPersistence = PlaybackPersistence(this)
        autoMixController = AutoMixController(
            context = this,
            player = player,
            scope = scope,
            sourceResolver = { item ->
                val quality = context.dataStore[MusicQualityKey]
                    ?.lowercase(getDefault())
                    ?: MusicQuality.EXHIGH.text
                mediaUriProvider.resolveMediaSource(item.mediaId, quality)
            },
        )
        audioPlayer = AudioPlayer(player) {
            autoMixController.isTransitioning
        }
        listenTogetherStore.attachPlayer(player)
        val singletonImageLoader = ImageLoader(this)
        mediaSession = MediaLibrarySession.Builder(this, player, LibrarySessionCallback())
            .setSessionExtras(Bundle().apply {
                putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV, true)
                putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT, true)
            })
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setBitmapLoader(CoilBitmapLoader(this, singletonImageLoader))
            .build()


        scope.launch {
            context.dataStore.data.map { preferences ->
                Triple(
                    NotificationAction.fromPreference(preferences[NotificationLeftActionKey], NotificationAction.PlayMode),
                    NotificationAction.fromPreference(preferences[NotificationRightActionKey], NotificationAction.Like),
                    preferences[CookieKey] to preferences[UserIdKey],
                )
            }.distinctUntilChanged().collect { (left, right, account) ->
                notificationLeftAction = left
                notificationRightAction = right
                val accountChanged = notificationAccount != account
                if (accountChanged) {
                    notificationAccount = account
                    playerRepository.resetLikeState()
                }
                notificationSignedIn = !account.first.isNullOrBlank() && !account.second.isNullOrBlank()
                refreshNotificationLike(force = accountChanged)
                updateNotificationButtons()
            }
        }
        scope.launch {
            playerRepository.songLikes.collect { updateNotificationButtons() }
        }
        systemLyricsBridge = SystemLyricsBridge(this, player, lyricManager, mediaSession)
        scope.launch {
            context.dataStore.data.map { it[CookieKey] }.distinctUntilChanged().drop(1).collect {
                val mediaId = player.currentMediaItem?.mediaId
                val hasOpenedSource = player.playbackState == Player.STATE_READY
                val playingQuality = currentMusicQuality.value
                val playingLocal = isPlayingLocalSource.value
                val localInfo = localPlaybackInfo.value
                mediaUriProvider.clearQualityState()
                resetCurrentQualityState(mediaId)
                // Already prepared bytes retain their actual format. Entitlement and URL
                // caches are still cleared, and the new account gets a fresh quality catalog.
                if (hasOpenedSource && player.currentMediaItem?.mediaId == mediaId) {
                    currentMusicQuality.value = playingQuality
                    isPlayingLocalSource.value = playingLocal
                    localPlaybackInfo.value = localInfo
                }
            }
        }
        restorePlayerState()
        periodicSnapshotJob = scope.launch {
            while (true) {
                delay(PLAYBACK_SNAPSHOT_INTERVAL_MS)
                persistPlaybackSnapshot()
            }
        }
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService(ConnectivityManager::class.java)

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_SLEEP_TIMER -> sleepTimer.clear()
            ACTION_TOGGLE_PLAYBACK -> if (player.isPlaying) player.pause() else player.play()
            ACTION_PREVIOUS -> if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0L)
            ACTION_NEXT -> if (player.hasNextMediaItem()) player.seekToNextMediaItem()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun restorePlayerState() {
        playbackRestoreJob = scope.launch {
            try {
                val restoredState = withContext(Dispatchers.IO) {
                    val preferences = context.dataStore.data.firstOrNull() ?: return@withContext null
                    playbackPersistence.load()?.withRestorePreferences(preferences)?.let { snapshot ->
                        snapshot to playbackPersistence.restoreItems(snapshot)
                    }
                }
                // The service is already usable while disk data loads. A new queue wins.
                if (player.mediaItemCount > 0 || queueTitle != null) return@launch
                val snapshot = restoredState?.first
                if (snapshot != null && snapshot.items.isNotEmpty()) {
                    val restoredItems = restoredState.second
                    val restoredIndex = snapshot.currentIndex.coerceIn(restoredItems.indices)
                    queueTitle = snapshot.queueTitle
                    queueManager.restoreFmMode(
                        snapshot.isFmMode,
                        snapshot.similarFmSourceIds,
                        snapshot.queueAutoplayEnabled,
                    )
                    player.shuffleModeEnabled = false
                    player.setMediaItems(
                        restoredItems,
                        restoredIndex,
                        snapshot.positionMs.coerceAtLeast(0L),
                    )
                    queueManager.restoreQueueAutoplayStartIndex(snapshot.queueAutoplayStartIndex)
                    player.repeatMode = snapshot.repeatMode.coerceIn(
                        Player.REPEAT_MODE_OFF,
                        Player.REPEAT_MODE_ALL,
                    )
                    snapshot.shuffleOrder?.takeIf { it.isPlaybackPermutation(restoredItems.size) }
                        ?.let { player.setPlaybackOrder(it) }
                    player.shuffleModeEnabled = snapshot.shuffleModeEnabled &&
                        (!snapshot.isFmMode || snapshot.queueAutoplayEnabled)
                    queueManager.restorePlaylistSource(snapshot.playlistSource)
                    player.playWhenReady = snapshot.playWhenReady
                    player.prepare()
                    Timber.tag("MusicService").d(
                        "Restored playback snapshot -> items: ${restoredItems.size}, " +
                            "index: $restoredIndex, position: ${snapshot.positionMs}, " +
                            "source: ${snapshot.sourceType}",
                    )
                } else {
                    val preferences = context.dataStore.data.firstOrNull() ?: return@launch
                    if (player.mediaItemCount > 0 || queueTitle != null) return@launch
                    val savedShuffleMode = preferences[IsShuffleModeKey] ?: true
                    val savedRepeatMode = preferences[RepeatModeKey] ?: Player.REPEAT_MODE_ALL
                    player.shuffleModeEnabled = savedShuffleMode
                    player.repeatMode = savedRepeatMode
                    Timber.tag("MusicService").d(
                        "Restored legacy state -> shuffle: $savedShuffleMode, repeat: $savedRepeatMode",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag("MusicService").e(error, "Unable to restore playback snapshot")
            } finally {
                isRestoringPlayback = false
                // Save a queue selected during loading, but never replace disk data
                // with an empty queue after a failed or cancelled startup read.
                if (currentCoroutineContext().isActive && player.mediaItemCount > 0) {
                    schedulePlaybackSnapshot()
                }
            }
        }
    }

    internal fun schedulePlaybackSnapshot() {
        if (isRestoringPlayback || !::playbackPersistence.isInitialized) return
        playbackSnapshotJob?.cancel()
        playbackSnapshotJob = scope.launch {
            delay(PLAYBACK_SNAPSHOT_DEBOUNCE_MS)
            persistPlaybackSnapshot()
        }
    }

    private suspend fun persistPlaybackSnapshot() {
        if (isRestoringPlayback || !::playbackPersistence.isInitialized) return
        val snapshot = playbackPersistence.capture(
            player = player,
            queueTitle = queueTitle,
            isFmMode = queueManager.isFmMode,
            playlistSource = queueManager.playlistSource,
            similarFmSourceIds = queueManager.similarFmSourceIds,
            queueAutoplayEnabled = queueManager.queueAutoplayEnabled.value,
            queueAutoplayStartEntryId = queueManager.queueAutoplayStartEntryId.value,
        )
        withContext(NonCancellable) {
            runCatching { playbackPersistence.save(snapshot) }
                .onFailure { Timber.tag("MusicService").w(it, "Unable to save playback snapshot") }
        }
    }

    private fun persistPlaybackSnapshotBlocking() {
        if (isRestoringPlayback || !::playbackPersistence.isInitialized) return
        val snapshot = playbackPersistence.capture(
            player, queueTitle, queueManager.isFmMode, queueManager.playlistSource,
            queueManager.similarFmSourceIds,
            queueManager.queueAutoplayEnabled.value, queueManager.queueAutoplayStartEntryId.value,
        )
        runCatching {
            runBlocking(Dispatchers.IO) { playbackPersistence.save(snapshot) }
        }.onFailure { Timber.tag("MusicService").w(it, "Unable to save final playback snapshot") }
    }

    private fun updatePreload() {
        val nextIndex = player.nextPlaybackMediaItemIndex().takeIf {
            it != player.currentMediaItemIndex && it in 0 until player.mediaItemCount
        } ?: C.INDEX_UNSET
        val nextItem = if (nextIndex == C.INDEX_UNSET) null else player.getMediaItemAt(nextIndex)
        if (nextItem == preloadedMediaItem && nextIndex == preloadStrategy.nextPlayingIndex) return
        preloadedMediaItem?.let(preloadManager::remove)
        preloadedMediaItem = nextItem
        preloadStrategy.nextPlayingIndex = nextIndex
        if (nextItem != null) {
            preloadManager.add(nextItem, nextIndex)
        }
        preloadManager.invalidate()
    }

    /** Clears sources tied to the previous quality while keeping the player playlist intact. */
    fun resetPlaybackSourcesForQualityChange() {
        if (::autoMixController.isInitialized) {
            autoMixController.resetForQualityChange()
        }
        if (::preloadManager.isInitialized) {
            // BasePreloadManager requires all lifecycle calls on its construction thread.
            preloadManager.reset()
            preloadedMediaItem = null
            preloadStrategy.nextPlayingIndex = C.INDEX_UNSET
        }
    }

    /** Recreates prepared sources while preserving the current entry, position, and queue order. */
    internal fun refreshPlaybackSource(
        index: Int,
        positionMs: Long,
        resumePlayback: Boolean,
        refreshQueuedSources: Boolean = false,
    ) {
        resetPlaybackSourcesForQualityChange()
        isRefreshingPlaybackSource = true
        try {
            player.refreshMediaItemSource(index, refreshQueuedSources)
            player.seekTo(index, positionMs)
            player.prepare()
            player.playWhenReady = resumePlayback
        } finally {
            isRefreshingPlaybackSource = false
        }
        updatePreload()
        refreshAvailableMusicQualities()
    }

    fun resetRejectedPlaybackSources() {
        sourceRecoveryJob?.cancel()
        sourceRecoveryJob = null
        sourceRecoveryAttempts = 0
        rangeRecoveryAttempts = 0
        stallRecoveryAttempts = 0
        mediaUriProvider.resetRejectedSources()
        resetCurrentQualityState(player.currentMediaItem?.mediaId, refresh = false)
    }

    private fun publishResolvedQuality(source: ResolvedMediaSource) {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (source.cacheKey?.let { mediaUriProvider.isRejectedCachedSource(mediaId, it) } == true) return
        val isLocal = source.cacheKey == null
        isPlayingLocalSource.value = isLocal
        if (isLocal) localPlaybackInfo.value = source.localPlaybackInfo ?: LocalPlaybackInfo()
        val quality = source.actualQuality.takeIf { !isLocal }?.let { actual ->
            MusicQuality.entries.firstOrNull { it.text == normalizePlaybackQuality(actual) }
        }
        currentMusicQuality.value = quality
        if (quality != null && quality !in availableMusicQualities.value) {
            availableMusicQualities.value = (availableMusicQualities.value + quality).sortedBy { it.ordinal }
        }
    }

    private fun resetCurrentQualityState(mediaId: String?, refresh: Boolean = true) {
        availableQualitiesJob?.cancel()
        availableQualitiesJob = null
        qualityGeneration++
        qualityMediaId = mediaId
        availableMusicQualities.value = emptyList()
        currentMusicQuality.value = null
        localPlaybackInfo.value = null
        isPlayingLocalSource.value = false
        isLoadingMusicQualities.value = false
        if (mediaId != null) {
            mediaUriProvider.resolvedMediaSource(mediaId)?.let(::publishResolvedQuality)
            if (refresh) refreshAvailableMusicQualities()
        }
    }

    fun refreshAvailableMusicQualities() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (qualityMediaId != mediaId) {
            resetCurrentQualityState(mediaId)
            return
        }
        if (availableQualitiesJob?.isActive == true) return
        val generation = qualityGeneration
        isLoadingMusicQualities.value = true
        availableQualitiesJob = scope.launch {
            try {
                val localInfo = withContext(Dispatchers.IO) {
                    mediaUriProvider.getLocalPlaybackInfo(mediaId)
                }
                if (generation != qualityGeneration || player.currentMediaItem?.mediaId != mediaId) return@launch
                localPlaybackInfo.value = localInfo
                val qualities = if (player.currentMediaItem?.metadata?.isLocal == true) emptyList() else {
                    withContext(Dispatchers.IO) {
                        mediaUriProvider.getAvailableMusicQualities(mediaId, supportsDolby)
                    }
                }
                if (generation == qualityGeneration && player.currentMediaItem?.mediaId == mediaId) {
                    availableMusicQualities.value = (qualities + listOfNotNull(currentMusicQuality.value))
                        .distinct().sortedBy { it.ordinal }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag("MusicService").w(error, "Unable to obtain available qualities for %s", mediaId)
            } finally {
                if (generation == qualityGeneration && player.currentMediaItem?.mediaId == mediaId) {
                    isLoadingMusicQualities.value = false
                }
            }
        }
    }


    private fun openAudioEffectSession() {
        val currentSessionId = player.audioSessionId
        if (currentSessionId == C.AUDIO_SESSION_ID_UNSET ||
            openedAudioEffectSessionId == currentSessionId
        ) {
            return
        }
        closeAudioEffectSession()
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, currentSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
        )
        openedAudioEffectSessionId = currentSessionId
    }

    private fun closeAudioEffectSession() {
        val sessionId = openedAudioEffectSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        openedAudioEffectSessionId = C.AUDIO_SESSION_ID_UNSET
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            }
        )
    }

    private fun updateAudioEffectSession() {
        val isBufferingOrReady =
            player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
        if (isBufferingOrReady && player.playWhenReady) {
            openAudioEffectSession()
        } else {
            closeAudioEffectSession()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
            playbackFailures.reset()
        }
        updateAudioEffectSession()
        if (!::audioPlayer.isInitialized) return
        if (
            ::autoMixController.isInitialized &&
            autoMixController.isTransitioning
        ) {
            return
        }
        if (playWhenReady) {
            audioPlayer.startSmooth()
        } else {
            audioPlayer.pauseSmooth()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            recordPlaybackDuration(
                playbackHistorySession.finish(
                    SystemClock.elapsedRealtime(),
                    endReason = if (playbackState == Player.STATE_ENDED) "playend" else "interrupt",
                ),
            )
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) playbackFailures.resetProgress()
        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            monitorFailureRecovery()
        }
        if (events.containsAny(Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED)) {
            updateNotificationButtons()
        }
        if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED)) {
            refreshNotificationLike()
        }
        // Playback state changes only maintain the audio-effect session. They are not
        // playback intent and must not restart the 500ms volume animations.
        if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
            events.contains(Player.EVENT_AUDIO_SESSION_ID)
        ) {
            updateAudioEffectSession()
        }
        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
            )
        ) {
            val mediaItem = player.currentMediaItem
            val update = playbackHistorySession.update(
                mediaId = mediaItem?.mediaId,
                isPlaying = player.isPlaying,
                wallClockMs = System.currentTimeMillis(),
                realtimeMs = SystemClock.elapsedRealtime(),
            )
            recordPlaybackDuration(update.completed)
            if (update.startedAtMs != null && mediaItem != null) {
                recordPlaybackStart(mediaItem, update.startedAtMs)
            }
        }
        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
        }
        if (events.containsAny(
                EVENT_TIMELINE_CHANGED,
                EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
            )
        ) {
            schedulePlaybackSnapshot()
        }
    }

    private fun monitorFailureRecovery() {
        failureProgressJob?.cancel()
        failureProgressJob = null
        playbackFailures.resetProgress()
        if (!player.isPlaying || playbackFailures.consecutiveFailures == 0) return
        failureProgressJob = scope.launch {
            while (player.isPlaying && playbackFailures.consecutiveFailures > 0) {
                val item = player.currentMediaItem
                playbackFailures.recordProgress(
                    entryId = item?.queueEntryId ?: item?.mediaId,
                    positionMs = player.currentPosition,
                    realtimeMs = SystemClock.elapsedRealtime(),
                    isPlaying = player.isPlaying,
                )
                delay(FAILURE_PROGRESS_SAMPLE_MS)
            }
        }
    }


    private suspend fun recordHistory(mediaItem: MediaItem, playedAt: Long) {
        val trackMetadata = mediaItem.metadata
        if (trackMetadata?.isPodcast == true) return
        val metadata = mediaItem.mediaMetadata
        val artistList = metadata.extras?.getStringArrayList("artist_list")
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: metadata.artist?.toString()
                ?.split(Regex("\\s*[/,&、]\\s*"))
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.takeIf(List<String>::isNotEmpty)
            ?: listOf("未知歌手")
        val storageId = playbackHistoryStorageId(mediaItem.mediaId, trackMetadata?.isLocal == true)
        val storedSong = songRepository.getSong(storageId).firstOrNull()
        val title = metadata.title?.toString().orEmpty().ifBlank {
            storedSong?.title ?: "未知标题"
        }
        val album = metadata.albumTitle?.toString().orEmpty().ifBlank {
            storedSong?.album ?: "未知专辑"
        }
        val cover = metadata.artworkUri?.toString().orEmpty().ifBlank {
            storedSong?.cover.orEmpty()
        }
        val song = storedSong?.copy(
            title = title,
            artist = artistList,
            album = album,
            cover = cover,
            duration = metadata.durationMs?.takeIf { it > 0 } ?: storedSong.duration,
            updatedAt = System.currentTimeMillis(),
        ) ?: Song(
            id = storageId,
            title = title,
            artist = artistList,
            album = album,
            cover = cover,
            duration = metadata.durationMs ?: 0,
            sourceType = if (trackMetadata?.isLocal == true) com.ljyh.mei.data.model.room.SourceType.LOCAL
                else com.ljyh.mei.data.model.room.SourceType.STREAM,
        )
        historyRepository.addToHistory(song, playedAt)
    }

    private fun MediaItem.playbackHistorySongIdOrNull(): Long? {
        val metadata = metadata ?: return null
        if (metadata.isPodcast || metadata.isLocal) return null
        return mediaId.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun recordPlaybackStart(
        mediaItem: MediaItem,
        startedAtMs: Long,
    ) {
        scope.launchPlaybackHistoryPersistence {
            try {
                recordHistory(mediaItem, startedAtMs)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag("MusicService").e(error, "add history record error")
            }
        }

        val songId = mediaItem.playbackHistorySongIdOrNull() ?: return
        val source = resolvePlaybackHistorySource(songId) ?: return
        val metadata = mediaItem.metadata
        val artist = metadata?.artists
            ?.map { it.name }
            ?.filter(String::isNotBlank)
            ?.joinToString(", ")
            .orEmpty()
            .ifBlank { mediaItem.mediaMetadata.artist?.toString().orEmpty() }
        playbackHistoryReporter.recordStart(
            mediaId = mediaItem.mediaId,
            songId = songId,
            source = source,
            startedAtMs = startedAtMs,
            song = NcblSongInfo(
                id = songId,
                name = metadata?.title.orEmpty().ifBlank {
                    mediaItem.mediaMetadata.title?.toString().orEmpty()
                },
                artist = artist,
                durationMs = metadata?.duration?.takeIf { it > 0L }
                    ?: player.duration.takeIf { it > 0L && it != C.TIME_UNSET },
            ),
        )
    }

    private fun recordPlaybackDuration(completed: CompletedPlaybackHistorySession?) {
        completed ?: return
        playbackHistoryReporter.recordDuration(
            completed = completed,
            endedAtMs = System.currentTimeMillis(),
        )
    }


    private fun notificationLikeSongId(): String? {
        val metadata = player.currentMetadata ?: return null
        return player.currentMediaItem?.mediaId?.takeIf {
            !metadata.isLocal && !metadata.isPodcast && (it.toLongOrNull() ?: 0L) > 0L
        }
    }

    private fun refreshNotificationLike(force: Boolean = false) {
        notificationLikeJob?.cancel()
        val id = notificationLikeSongId()?.toLongOrNull() ?: return
        if (!notificationSignedIn ||
            (!force && notificationLeftAction != NotificationAction.Like && notificationRightAction != NotificationAction.Like)
        ) return
        val expectedSession = notificationAccount?.first?.let(::neteaseSessionFingerprint) ?: return
        notificationLikeJob = scope.launch { playerRepository.checkSongLike(id, expectedSession) }
    }

    fun attachFloatingLyricsActivity(activity: MainActivity) {
        floatingLyricsActivity = WeakReference(activity)
        NotificationManagerCompat.from(this).cancel(FLOATING_LYRICS_NOTIFICATION_ID)
        updateFloatingLyricsState(activity)
    }

    fun detachFloatingLyricsActivity(activity: MainActivity) {
        if (floatingLyricsActivity.get() !== activity) return
        floatingLyricsActivity.clear()
        floatingLyricsEnabled = false
        updateNotificationButtons()
    }

    fun updateFloatingLyricsState(activity: MainActivity) {
        if (floatingLyricsActivity.get() !== activity) return
        floatingLyricsEnabled = activity.isInPictureInPictureMode
        if (floatingLyricsEnabled) NotificationManagerCompat.from(this).cancel(FLOATING_LYRICS_NOTIFICATION_ID)
        updateNotificationButtons()
    }

    private fun toggleFloatingLyrics(): Boolean {
        val activity = floatingLyricsActivity.get()
        if (activity?.isInPictureInPictureMode == true) return activity.moveTaskToBack(true)
        if (activity != null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return activity.enterPictureInPictureMode(floatingLyricsPipParams(activity, player.isPlaying))
        }
        if (hasFloatingLyricsBackgroundPermission(this)) {
            try {
                startActivity(floatingLyricsIntent(this, true))
                return true
            } catch (error: Exception) {
                Timber.w(error, "Unable to open floating lyrics from the background")
            }
        }
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) {
            Toast.makeText(this, R.string.notification_floating_lyrics_open_app, Toast.LENGTH_LONG).show()
            return false
        }
        manager.createNotificationChannel(NotificationChannel(
            "floating_lyrics", getString(R.string.more_action_floating_lyrics), NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
        })
        // System media commands cannot launch an activity from the background. Let System UI
        // send the activity PendingIntent directly when the user taps this short-lived entry.
        val notification = NotificationCompat.Builder(this, "floating_lyrics")
            .setSmallIcon(R.drawable.ic_notification_sf_text_rectangle)
            .setContentTitle(getString(R.string.notification_enable_floating_lyrics))
            .setContentText(getString(R.string.notification_floating_lyrics_open_hint))
            .setContentIntent(floatingLyricsPendingIntent(this, true))
            .setAutoCancel(true)
            .setSilent(true)
            .setTimeoutAfter(15_000L)
            .build()
        return try {
            manager.notify(FLOATING_LYRICS_NOTIFICATION_ID, notification)
            true
        } catch (error: SecurityException) {
            Toast.makeText(this, R.string.notification_floating_lyrics_open_app, Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun updateNotificationButtons() {
        if (!::mediaSession.isInitialized) return
        val songId = notificationLikeSongId()
        val liked = playerRepository.songLikes.value[songId] == true
        val buttons = listOfNotNull(
            notificationButton(
                this, notificationLeftAction, true, player.repeatMode, player.shuffleModeEnabled, liked,
                if (notificationLeftAction == NotificationAction.Like) songId != null && notificationSignedIn
                else player.currentMediaItem != null,
                floatingLyricsEnabled = floatingLyricsEnabled,
            ),
            notificationButton(
                this, notificationRightAction, false, player.repeatMode, player.shuffleModeEnabled, liked,
                if (notificationRightAction == NotificationAction.Like) songId != null && notificationSignedIn
                else player.currentMediaItem != null,
                floatingLyricsEnabled = floatingLyricsEnabled,
            ),
        )
        if (buttons != notificationButtons) {
            notificationButtons = buttons
            mediaSession.setMediaButtonPreferences(buttons)
        }
    }

    /** Shares the same mode cycle between the player UI and system media controls. */
    fun switchPlayMode() {
        val loopMode = if (dataStore[LoopPlaybackKey] ?: true) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        when {
            player.shuffleModeEnabled -> {
                setShuffleModeEnabled(false)
                player.repeatMode = Player.REPEAT_MODE_ONE
            }
            player.repeatMode == Player.REPEAT_MODE_ONE -> {
                setShuffleModeEnabled(false)
                player.repeatMode = loopMode
            }
            else -> {
                setShuffleModeEnabled(true)
                player.repeatMode = loopMode
            }
        }
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (!controller.isTrusted && controller.packageName != packageName) {
                return Futures.immediateFuture(result.build())
            }
            val commands = result.build().availableSessionCommands.buildUpon()
                .add(SessionCommand(NOTIFICATION_LEFT_COMMAND, Bundle.EMPTY))
                .add(SessionCommand(NOTIFICATION_RIGHT_COMMAND, Bundle.EMPTY))
                .build()
            return Futures.immediateFuture(result.setAvailableSessionCommands(commands).build())
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val action = when (customCommand.customAction) {
                NOTIFICATION_LEFT_COMMAND -> notificationLeftAction
                NOTIFICATION_RIGHT_COMMAND -> notificationRightAction
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            when (action) {
                NotificationAction.None -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                NotificationAction.PlayMode -> {
                    if (player.currentMediaItem == null) {
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                    }
                    switchPlayMode()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                NotificationAction.FloatingLyrics -> {
                    val success = player.currentMediaItem != null && toggleFloatingLyrics()
                    return Futures.immediateFuture(SessionResult(
                        if (success) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_INVALID_STATE,
                    ))
                }
                NotificationAction.Like -> {
                    val id = notificationLikeSongId()
                        ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                    if (!notificationSignedIn) {
                        Toast.makeText(this@MusicService, R.string.notification_like_login_required, Toast.LENGTH_SHORT).show()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
                    }
                    val expectedSession = neteaseSessionFingerprint(notificationAccount?.first.orEmpty())
                    val future = SettableFuture.create<SessionResult>()
                    scope.launch {
                        val result = playerRepository.toggleLike(id, expectedSession)
                        if (result is Resource.Success) {
                            future.set(SessionResult(SessionResult.RESULT_SUCCESS))
                        } else {
                            Toast.makeText(this@MusicService, R.string.notification_like_failed, Toast.LENGTH_SHORT).show()
                            future.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
                        }
                    }.invokeOnCompletion { error ->
                        if (error != null) future.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
                    }
                    return future
                }
            }
        }
    }

    fun playNext(items: List<MediaItem>) {
        scope.launch {
            queueManager.playNext(items)
        }
    }

    fun addToQueue(items: List<MediaItem>) {
        scope.launch {
            queueManager.addToQueue(items)
        }
    }

    fun setShuffleModeEnabled(isShuffle: Boolean) {
        queueManager.setShuffleModeEnabled(isShuffle)
    }

    override fun onDestroy() {
        NotificationManagerCompat.from(this).cancel(FLOATING_LYRICS_NOTIFICATION_ID)
        if (::systemLyricsBridge.isInitialized) systemLyricsBridge.release()
        mediaUriProvider.onSourceResolved = null
        availableQualitiesJob?.cancel()
        sourceRecoveryJob?.cancel()
        periodicSnapshotJob?.cancel()
        playbackRestoreJob?.cancel()
        playbackSnapshotJob?.cancel()
        automaticCacheJob?.cancel()
        if (::playbackHistoryReporter.isInitialized) {
            recordPlaybackDuration(playbackHistorySession.finish(SystemClock.elapsedRealtime()))
            playbackHistoryReporter.close()
        }
        persistPlaybackSnapshotBlocking()
        CacheManager.release()
        mediaSession.release()
        player.removeListener(this)
        sleepTimer.clear()
        player.removeListener(sleepTimer)
        queueManager.release()
        preloadManager.release()
        autoMixController.release()
        if (::audioPlayer.isInitialized) audioPlayer.release()
        equalizerConfigurationState.release()
        listenTogetherStore.detachPlayer(player)

        closeAudioEffectSession()
        player.release()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistPlaybackSnapshotBlocking()
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession
    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats
    ) {

    }

    private fun createDataSourceFactory(): DataSource.Factory {
        val simpleCache = CacheManager.getSimpleCache(context)

        return ResolvingDataSource.Factory(getCacheDataSourceFactory(context)) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media key")
            val quality = context.dataStore[MusicQualityKey]
                ?.let(::normalizePlaybackQuality)
                ?: MusicQuality.EXHIGH.text
            val localSource = runBlocking { mediaUriProvider.resolveLocalSource(mediaId) }
            if (localSource != null) {
                Timber.tag("ResolvingDataSource").d("Using local source for mediaId: %s", mediaId)
                return@Factory dataSpec.buildUpon()
                    .setUri(localSource.uri)
                    .setKey(null)
                    .build()
            }
            val fullyCachedKey = findFullyCachedPlaybackKey(simpleCache, mediaId, quality)
            if (fullyCachedKey != null && !mediaUriProvider.isRejectedCachedSource(mediaId, fullyCachedKey)) {
                Timber.tag("ResolvingDataSource").d("Fully cached on disk: $mediaId")
                mediaUriProvider.rememberCachedSource(mediaId, quality, fullyCachedKey, dataSpec.uri)
                return@Factory dataSpec.buildUpon()
                    .setKey(fullyCachedKey)
                    .build()
            }

            runBlocking {
                val resolved = mediaUriProvider.resolveMediaSource(mediaId, quality)
                dataSpec.buildUpon()
                    .setUri(resolved.uri)
                    .setKey(resolved.cacheKey)
                    .build()
            }
        }
    }

    private fun createRenderersFactory() =
        object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = DefaultAudioSink.Builder(this@MusicService)
                .setAudioOutputProvider(
                    beatMeter.wrap(AudioTrackAudioOutputProvider.Builder(this@MusicService).build()),
                )
                .setEnableFloatOutput(enableFloatOutput)
                // AutoMix continuously changes tempo. Sonic's parameter changes drain and
                // restart the PCM processor chain, producing periodic gaps during overlap.
                // Apply tempo at AudioTrack instead, keeping the EQ/PCM pipeline continuous.
                .setEnableAudioOutputPlaybackParameters(true)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        arrayOf(TenBandEqualizerProcessor(equalizerConfigurationState)),
                        SilenceSkippingAudioProcessor(2_000_000, 0.01f, 2_000_000, 0, 256),
                        SonicAudioProcessor()
                    )
                ).build()
        }.setEnableDecoderFallback(true)

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder
    override fun onPlayerError(error: PlaybackException) {
        failureProgressJob?.cancel()
        failureProgressJob = null
        playbackFailures.resetProgress()
        val causeTypes = generateSequence(error.cause) { it.cause }.take(16)
            .joinToString(" -> ") { it.javaClass.simpleName }
        Timber.tag("MusicService").e(
            "Player Error: %s, %s, causes=%s", error.errorCodeName, error.message, causeTypes,
        )

        val stalled = shouldRecoverStalledPlayback(error.errorCode, error.cause)
        if (stalled) {
            val stuck = generateSequence(error.cause) { it.cause }.take(16)
                .filterIsInstance<StuckPlayerException>().first()
            Timber.tag("PlaybackAudio").w(
                "Playback stalled: id=%s quality=%s position=%s buffered=%s type=%s timeoutMs=%s",
                player.currentMediaItem?.mediaId, currentMusicQuality.value,
                player.currentPosition, player.bufferedPosition, stuck.stuckType, stuck.timeoutMs,
            )
        }
        if (stalled && scheduleSourceRecovery(preserveCache = true)) return

        if (shouldRefreshPlaybackSource(error.errorCode)) {
            if (!scheduleSourceRecovery()) {
                Toast.makeText(context, "播放源刷新失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (stalled || shouldTryLowerPlaybackQuality(error.errorCode)) {
            val mediaId = player.currentMediaItem?.mediaId
            val failedQuality = currentMusicQuality.value
            val failedCacheKey = mediaId?.let { mediaUriProvider.resolvedMediaSource(it)?.cacheKey }
            if (mediaId != null && mediaUriProvider.rejectCurrentSource(mediaId)) {
                availableQualitiesJob?.cancel()
                availableQualitiesJob = null
                qualityGeneration++
                currentMusicQuality.value = null
                availableMusicQualities.value = availableMusicQualities.value - listOfNotNull(failedQuality).toSet()
                isLoadingMusicQualities.value = false
                if (scheduleSourceRecovery(qualityFallback = true, failedCacheKey = failedCacheKey)) return
            }
        }

        val isSourceError = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                error.cause is SourceNotFoundException ||
                error.message?.contains("Unable to resolve url") == true

        if (isSourceError) {
            val limitReached = playbackFailures.recordFailure()
            Timber.tag("MusicService").e("Play failure detected. Count: %s", playbackFailures.consecutiveFailures)
            if (limitReached) {
                Toast.makeText(context, "播放失败，已连续跳过多首歌曲", Toast.LENGTH_LONG).show()
                player.pause()
                player.stop()
                return
            }
            val nextIndex = player.nextMediaItemIndex
            if (nextIndex in 0 until player.mediaItemCount && nextIndex != player.currentMediaItemIndex) {
                val nextItem = player.getMediaItemAt(nextIndex)
                playbackFailures.expectAutomaticSkip(nextItem.queueEntryId ?: nextItem.mediaId)
                player.seekToDefaultPosition(nextIndex)
                player.prepare()
            } else {
                player.pause()
                player.stop()
                Toast.makeText(context, "播放结束，部分歌曲无法加载", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "播放出错: ${error.errorCodeName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleSourceRecovery(
        qualityFallback: Boolean = false,
        failedCacheKey: String? = null,
        preserveCache: Boolean = false,
    ): Boolean {
        val mediaItem = player.currentMediaItem ?: return false
        val mediaId = mediaItem.mediaId.takeIf(String::isNotBlank) ?: return false
        val entryId = mediaItem.queueEntryId
        val index = player.currentMediaItemIndex
        if (sourceRecoveryMediaId != mediaId || sourceRecoveryEntryId != entryId) {
            sourceRecoveryMediaId = mediaId
            sourceRecoveryEntryId = entryId
            sourceRecoveryAttempts = 0
            rangeRecoveryAttempts = 0
            stallRecoveryAttempts = 0
        }
        if (sourceRecoveryJob?.isActive == true) return true
        val attemptLimit = if (qualityFallback) MusicQuality.entries.size else MAX_SOURCE_RECOVERY_ATTEMPTS
        val attempts = when {
            qualityFallback -> sourceRecoveryAttempts
            preserveCache -> stallRecoveryAttempts
            else -> rangeRecoveryAttempts
        }
        if (attempts >= attemptLimit) return false

        when {
            qualityFallback -> sourceRecoveryAttempts++
            preserveCache -> stallRecoveryAttempts++
            else -> rangeRecoveryAttempts++
        }
        val recoveryPositionMs = player.currentPosition.coerceAtLeast(0L)
        sourceRecoveryJob = scope.launch {
            try {
                Timber.tag("MusicService").w(
                    "Refreshing playback source: id=%s position=%s attempt=%s qualityFallback=%s preserveCache=%s",
                    mediaId,
                    recoveryPositionMs,
                    attempts + 1,
                    qualityFallback,
                    preserveCache,
                )
                mediaUriProvider.invalidate(mediaId)
                resetPlaybackSourcesForQualityChange()
                // A stalled loader or decoder does not prove that cached media is corrupt.
                // Retry the selected quality once before rejecting its exact source bytes.
                val removedEntries = if (preserveCache) 0 else withContext(Dispatchers.IO) {
                    val cache = CacheManager.getSimpleCache(context)
                    if (qualityFallback) {
                        failedCacheKey?.let(cache::removeResource)
                        if (failedCacheKey == null) 0 else 1
                    } else {
                        removePlaybackEntries(cache, mediaId)
                    }
                }
                if (player.currentMediaItem?.mediaId != mediaId ||
                    player.currentMediaItem?.queueEntryId != entryId ||
                    player.currentMediaItemIndex != index
                ) return@launch

                Timber.tag("MusicService").d(
                    "Retrying refreshed source: id=%s removedCacheEntries=%s",
                    mediaId,
                    removedEntries,
                )
                // Honor seeking and playback intent received during asynchronous cache cleanup.
                refreshPlaybackSource(index, player.currentPosition.coerceAtLeast(0L), player.playWhenReady)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag("MusicService").e(error, "Unable to refresh source for %s", mediaId)
                Toast.makeText(context, "播放源刷新失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (isRefreshingPlaybackSource) return
        playbackFailures.onMediaItemTransition(mediaItem?.queueEntryId ?: mediaItem?.mediaId, reason)
        if (mediaItem?.mediaId != qualityMediaId) resetCurrentQualityState(mediaItem?.mediaId)
        recordPlaybackDuration(
            playbackHistorySession.onMediaItemTransition(
                mediaId = mediaItem?.mediaId,
                reason = reason,
                realtimeMs = SystemClock.elapsedRealtime(),
            ),
        )
        if (mediaItem?.mediaId != sourceRecoveryMediaId || mediaItem?.queueEntryId != sourceRecoveryEntryId) {
            sourceRecoveryJob?.cancel()
            sourceRecoveryJob = null
            sourceRecoveryMediaId = mediaItem?.mediaId
            sourceRecoveryEntryId = mediaItem?.queueEntryId
            sourceRecoveryAttempts = 0
            rangeRecoveryAttempts = 0
            stallRecoveryAttempts = 0
        } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
            // A completed loop allows one fresh retry without reopening rejected source bytes.
            stallRecoveryAttempts = 0
        }
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            checkFmModeLoadMore()
        }
        updatePreload()
    }

    private fun checkFmModeLoadMore() {
        if (!queueManager.isFmMode) return

        val current = player.currentMediaItemIndex
        val total = player.mediaItemCount
        val threshold = 3 // 剩余少于3首时加载
        val remaining = if (queueManager.queueAutoplayEnabled.value) {
            val order = player.playbackOrderIndices()
            order.indexOf(current).takeIf { it >= 0 }?.let { order.size - it } ?: (total - current)
        } else {
            total - current
        }

        if (remaining <= threshold) {
            scope.launch {
                queueManager.fetchAndAppendFmRecommendations()
            }
        }
    }

    fun isFmMode(): Boolean {
        return queueManager.isFmMode
    }

    companion object {
        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888
        private const val FLOATING_LYRICS_NOTIFICATION_ID = 889
        private const val PLAYBACK_SNAPSHOT_DEBOUNCE_MS = 500L
        private const val MAX_SOURCE_RECOVERY_ATTEMPTS = 1
        private const val STUCK_BUFFERING_TIMEOUT_MS = 30_000
        private const val PLAYBACK_SNAPSHOT_INTERVAL_MS = 5_000L
        private const val AUTOMATIC_CACHE_DELAY_MS = 5_000L
        private const val FAILURE_PROGRESS_SAMPLE_MS = 500L
        const val ACTION_CANCEL_SLEEP_TIMER = "com.ljyh.mei.action.CANCEL_SLEEP_TIMER"
        const val ACTION_TOGGLE_PLAYBACK = "com.ljyh.mei.action.TOGGLE_PLAYBACK"
        const val ACTION_PREVIOUS = "com.ljyh.mei.action.PREVIOUS"
        const val ACTION_NEXT = "com.ljyh.mei.action.NEXT"
    }
}
