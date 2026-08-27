package com.ljyh.mei.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.text.toLowerCase
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import coil3.ImageLoader
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.ljyh.mei.MainActivity
import com.ljyh.mei.R
import com.ljyh.mei.constants.IsShuffleModeKey
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.constants.MusicQualityKey
import com.ljyh.mei.constants.NoAudioSourceKey
import com.ljyh.mei.constants.RepeatModeKey
import com.ljyh.mei.constants.UserAgent
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.di.repository.HistoryRepository
import com.ljyh.mei.di.repository.SongRepository
import com.ljyh.mei.extensions.currentMetadata
import com.ljyh.mei.extensions.mediaItems
import com.ljyh.mei.playback.CacheManager.getCacheDataSourceFactory
import com.ljyh.mei.playback.CacheManager.isContentFullyCached
import com.ljyh.mei.utils.CoilBitmapLoader
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.get
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.util.Locale
import java.util.Locale.getDefault
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds


@UnstableApi
@AndroidEntryPoint
class MusicService : MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {

    lateinit var player: ExoPlayer
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var autoMixController: AutoMixController
    private lateinit var equalizerConfigurationState: EqualizerConfigurationState
    val context = this
    private lateinit var mediaSession: MediaLibrarySession

    lateinit var sleepTimer: SleepTimer
    private val serviceJob = SupervisorJob()
    var scope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var historyJob: Job? = null
    private var playbackSnapshotJob: Job? = null
    private var periodicSnapshotJob: Job? = null
    private lateinit var playbackPersistence: PlaybackPersistence
    private var isRestoringPlayback = true
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var baseMediaSourceFactory: DefaultMediaSourceFactory
    private lateinit var preloadManager: DefaultPreloadManager
    private val preloadStrategy = MusicPreloadStrategy()
    val currentMediaMetadata = MutableStateFlow<MediaMetadata?>(null)

    @Inject
    lateinit var mediaUriProvider: MediaUriProvider

    private var errorCount = 0 // 记录连续错误的次数，防止死循环

    private val binder = MusicBinder()

    lateinit var queueManager: PlaybackQueueManager
    var queueTitle: String? = null
    private var isAudioEffectSessionOpened = false

    @Inject
    lateinit var weApiService: WeApiService
    @Inject
    lateinit var apiService: ApiService


    @Inject
    lateinit var historyRepository: HistoryRepository

    @Inject
    lateinit var songRepository: SongRepository
    @Inject
    lateinit var listenTogetherStore: ListenTogetherStore
    @Inject
    lateinit var automaticCacheController: AutomaticCacheController
    override fun onCreate() {
        super.onCreate()
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
            DefaultMediaNotificationProvider(
                this,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.app_name_en
            )
        )

        player = ExoPlayer.Builder(this)
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
            .setAudioAttributes(
                AudioAttributes.Builder()
                    //音频焦点变化 比如来电话了
                    .setUsage(C.USAGE_MEDIA)
                    //AUDIO_CONTENT_TYPE_MUSIC 表明内容类型是音乐。这有助于系统选择合适的音频处理方式和路由（比如通过扬声器还是耳机输出）。
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), true
            )
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
            .apply {
                //添加监听器
                addListener(this@MusicService)
                //睡眠定时
                sleepTimer = SleepTimer(scope, this)
                addListener(sleepTimer)
                // 添加监听，更新预加载索引
                addListener(object : Player.Listener {
                    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                        updatePreload()
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        updatePreload()
                        historyJob?.cancel()
                        if (mediaItem != null) {
                            historyJob = scope.launch {
                                delay(5000L.milliseconds)
                                try {
                                    recordHistory(mediaItem)
                                    automaticCacheController.recordPlayback(mediaItem)
                                } catch (e: Exception) {
                                    Timber.tag("MusicService").e("add history record error $e")
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                        schedulePlaybackSnapshot()
                        scope.launch {
                            context.dataStore.edit { preferences ->
                                preferences[IsShuffleModeKey] = shuffleModeEnabled
                            }
                        }
                    }

                    override fun onRepeatModeChanged(repeatMode: Int) {
                        schedulePlaybackSnapshot()
                        scope.launch {
                            context.dataStore.edit { preferences ->
                                preferences[RepeatModeKey] = repeatMode
                            }
                        }
                    }
                })
            }

        queueManager = PlaybackQueueManager(player, apiService, weApiService, scope)
        playbackPersistence = PlaybackPersistence(this)
        audioPlayer = AudioPlayer(player)
        val secondaryPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(createDataSourceFactory()))
            .setRenderersFactory(createRenderersFactory())
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false,
            )
            .build()
        autoMixController = AutoMixController(
            context = this,
            primary = player,
            secondary = secondaryPlayer,
            scope = scope,
            sourceResolver = { item ->
                val quality = context.dataStore[MusicQualityKey]
                    ?.lowercase(getDefault())
                    ?: MusicQuality.EXHIGH.text
                mediaUriProvider.resolveMediaUri(item.mediaId, quality)
            },
        )
        listenTogetherStore.attachPlayer(player)
        val singletonImageLoader = ImageLoader(this)
        val sessionPlayer = object : ForwardingSimpleBasePlayer(player) {
            override fun handleSeek(
                mediaItemIndex: Int,
                positionMs: Long,
                seekCommand: Int,
            ): ListenableFuture<*> {
                // System media controls map "previous" to seekToPrevious(), which may
                // restart the current item instead of moving to the previous track.
                if (seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS) {
                    if (player.hasPreviousMediaItem()) {
                        player.seekToPreviousMediaItem()
                    } else {
                        player.seekTo(0L)
                    }
                    return Futures.immediateVoidFuture()
                }
                return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
            }
        }
        mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, LibrarySessionCallback())
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
            ACTION_TOGGLE_PLAYBACK -> if (player.isPlaying) player.pause() else player.play()
            ACTION_PREVIOUS -> if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0L)
            ACTION_NEXT -> if (player.hasNextMediaItem()) player.seekToNextMediaItem()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun restorePlayerState() {
        try {
            val snapshot = runBlocking(Dispatchers.IO) { playbackPersistence.load() }
            if (snapshot != null && snapshot.items.isNotEmpty()) {
                val restoredItems = playbackPersistence.restoreItems(snapshot)
                val restoredIndex = snapshot.currentIndex.coerceIn(restoredItems.indices)
                queueTitle = snapshot.queueTitle
                queueManager.isFmMode = snapshot.isFmMode
                player.shuffleModeEnabled = false
                player.setMediaItems(
                    restoredItems,
                    restoredIndex,
                    snapshot.positionMs.coerceAtLeast(0L),
                )
                player.repeatMode = snapshot.repeatMode.coerceIn(
                    Player.REPEAT_MODE_OFF,
                    Player.REPEAT_MODE_ALL,
                )
                player.shuffleModeEnabled = snapshot.shuffleModeEnabled && !snapshot.isFmMode
                player.prepare()
                player.playWhenReady = snapshot.playWhenReady
                Timber.tag("MusicService").d(
                    "Restored playback snapshot -> items: ${restoredItems.size}, " +
                        "index: $restoredIndex, position: ${snapshot.positionMs}, " +
                        "source: ${snapshot.sourceType}",
                )
            } else {
                val preferences = runBlocking(Dispatchers.IO) {
                    context.dataStore.data.firstOrNull()
                } ?: return
                val savedShuffleMode = preferences[IsShuffleModeKey] ?: true
                val savedRepeatMode = preferences[RepeatModeKey] ?: Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = savedShuffleMode
                player.repeatMode = savedRepeatMode
                Timber.tag("MusicService").d(
                    "Restored legacy state -> shuffle: $savedShuffleMode, repeat: $savedRepeatMode",
                )
            }
        } catch (error: Exception) {
            Timber.tag("MusicService").e(error, "Unable to restore playback snapshot")
        } finally {
            isRestoringPlayback = false
        }
    }

    private fun schedulePlaybackSnapshot() {
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
        )
        runCatching { playbackPersistence.save(snapshot) }
            .onFailure { Timber.tag("MusicService").w(it, "Unable to save playback snapshot") }
    }

    private fun persistPlaybackSnapshotBlocking() {
        if (isRestoringPlayback || !::playbackPersistence.isInitialized) return
        val snapshot = playbackPersistence.capture(player, queueTitle, queueManager.isFmMode)
        runCatching {
            runBlocking(Dispatchers.IO) { playbackPersistence.save(snapshot) }
        }.onFailure { Timber.tag("MusicService").w(it, "Unable to save final playback snapshot") }
    }

    private fun updatePreload() {
        if (player.currentTimeline.isEmpty) return

        val currentIndex = player.currentMediaItemIndex
        preloadStrategy.currentPlayingIndex = currentIndex
        if (currentIndex + 1 < player.mediaItemCount) {
            val nextItem = player.getMediaItemAt(currentIndex + 1)
            preloadManager.add(nextItem, currentIndex + 1)
        }

        // 3. 触发检查
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
        }
    }


    private fun openAudioEffectSession() {
        if (isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = true
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
        )
    }

    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            }
        )
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED
            )
        ) {
            val isBufferingOrReady =
                player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            if (isBufferingOrReady && player.playWhenReady) {
                openAudioEffectSession()
                if (!::autoMixController.isInitialized || !autoMixController.isTransitioning) {
                    audioPlayer.startSmooth()
                }
            } else {
                closeAudioEffectSession()
                if (!::autoMixController.isInitialized || !autoMixController.isTransitioning) {
                    audioPlayer.pauseSmooth()
                }
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


    private suspend fun recordHistory(mediaItem: MediaItem) {
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
        val song = Song(
            id = mediaItem.mediaId,
            title = metadata.title?.toString() ?: "未知标题",
            artist = artistList,
            album = metadata.albumTitle?.toString() ?: "未知专辑",
            cover = metadata.artworkUri?.toString() ?: "",
            duration = metadata.durationMs ?: 0,
        )
        historyRepository.addToHistory(song)
    }


    class LibrarySessionCallback : MediaLibrarySession.Callback

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
        periodicSnapshotJob?.cancel()
        playbackSnapshotJob?.cancel()
        historyJob?.cancel()
        persistPlaybackSnapshotBlocking()
        CacheManager.release()
        mediaSession.release()
        player.removeListener(this)
        player.removeListener(sleepTimer)
        queueManager.release()
        preloadManager.release()
        autoMixController.release()
        equalizerConfigurationState.release()
        listenTogetherStore.detachPlayer(player)

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
            val localFilePath = runBlocking {
                val song = songRepository.getSong(mediaId).firstOrNull()
                    ?: songRepository.getSong("local_$mediaId").firstOrNull()
                song?.path
            }
            if (localFilePath != null) {
                val file = File(localFilePath)
                if (file.exists()) {
                    Timber.tag("ResolvingDataSource").d("Using local file for mediaId: $mediaId, filePath: ${file.path}")
                    return@Factory dataSpec.buildUpon()
                        .setUri(Uri.fromFile(file))
                        .setKey(null)
                        .build()
                }
            }
            val cacheKey = playbackCacheKey(mediaId, quality)
            if (isContentFullyCached(simpleCache, cacheKey)) {
                Timber.tag("ResolvingDataSource").d("Fully cached on disk: $mediaId")
                return@Factory dataSpec.buildUpon()
                    .setKey(cacheKey)
                    .build()
            }

            runBlocking {
                val resolved = mediaUriProvider.resolveMediaSource(mediaId, quality)
                dataSpec.buildUpon()
                    .setUri(resolved.uri)
                    .setKey(playbackCacheKey(mediaId, resolved.actualQuality))
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
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        arrayOf(TenBandEqualizerProcessor(equalizerConfigurationState)),
                        SilenceSkippingAudioProcessor(2_000_000, 0.01f, 2_000_000, 0, 256),
                        SonicAudioProcessor()
                    )
                ).build()
        }

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder
    override fun onPlayerError(error: PlaybackException) {
        Timber.tag("MusicService").e( "Player Error: ${error.errorCodeName}, ${error.message}")

        val isSourceError = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                error.cause is SourceNotFoundException ||
                error.message?.contains("Unable to resolve url") == true

        if (isSourceError) {
            errorCount++
            Timber.tag("MusicService").e( "Play failure detected. Count: $errorCount")

            // 如果连续错误超过5次，停止播放，避免无限刷 API
            if (errorCount > 5) {
                Toast.makeText(context, "播放失败，已连续跳过多首歌曲", Toast.LENGTH_LONG).show()
                player.stop()
                errorCount = 0
                return
            }

            // 尝试跳到下一首
            if (player.hasNextMediaItem()) {
                // 不要在后台线程 Toast，发送事件或者只打印日志
                // 如果非要提示，用 Handler
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "资源无法加载，自动跳过", Toast.LENGTH_SHORT).show()
                }
                player.seekToNext()
                player.prepare()
                player.play()
            } else {
                // 列表播完了，或者没有下一首
                player.stop()
                Toast.makeText(context, "播放结束，部分歌曲无法加载", Toast.LENGTH_SHORT).show()
            }
        } else {
            // 其他错误（如解码器错误），重置计数器并提示
            errorCount = 0
            Toast.makeText(context, "播放出错: ${error.errorCodeName}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // 如果成功切歌，重置错误计数器
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            // 注意：这里需要延迟一点或者确认新歌曲开始缓冲后再重置，
            // 简单的做法是：只要正常切歌了，我们暂时认为链条断了
            errorCount = 0

            // 触发 FM 模式检查 (见下文)
            checkFmModeLoadMore()
        }
        updatePreload()
    }

    private fun checkFmModeLoadMore() {
        if (!queueManager.isFmMode) return

        val current = player.currentMediaItemIndex
        val total = player.mediaItemCount
        val threshold = 3 // 剩余少于3首时加载

        if (total - current <= threshold) {
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
        private const val PLAYBACK_SNAPSHOT_DEBOUNCE_MS = 500L
        private const val PLAYBACK_SNAPSHOT_INTERVAL_MS = 5_000L
        const val ACTION_TOGGLE_PLAYBACK = "com.ljyh.mei.action.TOGGLE_PLAYBACK"
        const val ACTION_PREVIOUS = "com.ljyh.mei.action.PREVIOUS"
        const val ACTION_NEXT = "com.ljyh.mei.action.NEXT"
    }
}
