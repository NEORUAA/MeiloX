package com.ljyh.mei.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.ljyh.mei.R
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.melox.ListenTogetherCommand
import com.ljyh.mei.data.model.melox.ListenTogetherInvitation
import com.ljyh.mei.data.model.melox.ListenTogetherRoom
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.data.repository.ListenTogetherSource
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.ApplicationContext
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class ListenTogetherConnection { Idle, Connected, Reconnecting }

data class ListenTogetherSessionState(
    val isLoading: Boolean = false,
    val room: ListenTogetherRoom? = null,
    val isHost: Boolean = false,
    val connection: ListenTogetherConnection = ListenTogetherConnection.Idle,
    val lastSyncTimeMs: Long? = null,
    val invitationUrl: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val session: SessionStamp? = null,
)

/** Shared room session coordinating NetEase state with the Media3 player. */
@Singleton
class ListenTogetherStore internal constructor(
    private val context: Context,
    private val repository: ListenTogetherSource,
    private val apiService: ApiService,
    private val sessions: SessionStore,
    private val scope: CoroutineScope,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : Player.Listener, Closeable {
    @Inject constructor(@ApplicationContext context: Context, repository: MeloXRepository,
        apiService: ApiService, sessions: SessionStore) : this(
        context, repository as ListenTogetherSource, apiService, sessions,
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )
    private val _state = MutableStateFlow(ListenTogetherSessionState())
    val state = _state.asStateFlow()
    private val stateLock = Any()
    private var version = 0L
    private var work = SupervisorJob(scope.coroutineContext[Job])
    private var workScope = CoroutineScope(scope.coroutineContext + work)
    private data class Owner(val session: SessionStamp, val version: Long, val player: Player?)

    private var player: Player? = null
    private var playerListener: Player.Listener? = null
    private var playerGeneration = 0L
    private var monitorJob: Job? = null
    private var queueReportJob: Job? = null
    private var actionJob: Job? = null
    private var clientSequence = 0L
    private var applyingRemoteState = false
    private var suppressReportsUntilMs = 0L
    private var lastPlaylistSignature: String? = null
    private var lastCommandSignature: String? = null
    private var formerSongId: Long? = null
    private var consecutiveFailures = 0
    private val invalidation = sessions.onInvalidated { revision ->
        synchronized(stateLock) {
            if ((state.value.session?.generation ?: -1) < revision) resetWork(null)
        }
    }
    private val observer = scope.launch {
        combine(sessions.changes, sessions.recoveryRequired) { _, recovery -> recovery }.collect { recovery ->
            val stamp = if (recovery) null else runCatching { sessions.snapshot() }.getOrNull()
            if (stamp == null) {
                synchronized(stateLock) {
                    resetWork(null)
                    if (recovery) _state.value = _state.value.copy(error = "Session recovery is required")
                }
            } else {
                val changed = runCatching {
                    sessions.withCurrent(stamp) {
                        synchronized(stateLock) {
                            if (state.value.session != stamp) { resetWork(stamp); true } else false
                        }
                    }
                }.getOrDefault(false)
                if (changed && authenticated(stamp) && player != null) refresh()
            }
        }
    }

    fun attachPlayer(value: Player) {
        if (player === value) return
        playerListener?.let { player?.removeListener(it) }
        synchronized(stateLock) { resetWork(state.value.session) }
        player = value
        val attachment = ++playerGeneration
        formerSongId = value.currentMediaItem?.mediaId?.toLongOrNull()
        val listener = object : Player.Listener {
            private fun forward(event: () -> Unit) {
                if (player === value && playerGeneration == attachment) event()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = forward {
                this@ListenTogetherStore.onMediaItemTransition(mediaItem, reason)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = forward {
                this@ListenTogetherStore.onPlayWhenReadyChanged(playWhenReady, reason)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = forward {
                this@ListenTogetherStore.onPositionDiscontinuity(oldPosition, newPosition, reason)
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = forward {
                this@ListenTogetherStore.onTimelineChanged(timeline, reason)
            }
        }
        playerListener = listener
        value.addListener(listener)
        refresh()
    }

    fun detachPlayer(value: Player) {
        if (player !== value) return
        playerListener?.let(value::removeListener)
        playerListener = null
        player = null
        playerGeneration++
        synchronized(stateLock) { resetWork(state.value.session) }
    }

    fun refresh() = launchAction { owner ->
        val status = request(owner) { repository.listenTogetherStatus(owner.session) }
        if (!status.isInRoom || status.room == null) {
            clearSession(owner)
        } else {
            establish(owner, status.room)
            synchronizeFromServer(owner, initial = true)
            sendHeartbeat(owner)
            startMonitoring(owner)
        }
    }

    fun create(expected: SessionStamp? = state.value.session) {
        if (expected == null) return
        launchAction(expected) { owner ->
            val activePlayer = owner.player ?: error(context.getString(R.string.listen_player_unavailable))
            check(activePlayer.currentMediaItem != null) { context.getString(R.string.listen_play_song_first) }
            val room = request(owner) { repository.createListenTogetherRoom(owner.session) }
            establish(owner, room)
            reportPlaylist(owner)
            reportCommand(owner, ListenTogetherCommand.GoTo, formerSongId, activePlayer.currentMediaItem?.mediaId?.toLongOrNull())
            sendHeartbeat(owner)
            startMonitoring(owner)
        }
    }

    fun join(roomId: String, inviterId: String, expected: SessionStamp? = state.value.session) {
        if (expected == null) return
        launchAction(expected) { owner ->
            val roomCheck = request(owner) { repository.checkListenTogetherRoom(owner.session, roomId.trim()) }
            check(roomCheck.first) {
                roomCheck.second?.takeIf(String::isNotBlank)
                    ?: context.getString(R.string.listen_room_unavailable)
            }
            val room = request(owner) { repository.acceptListenTogetherRoom(owner.session, roomId.trim(), inviterId.trim()) }
            establish(owner, room)
            synchronizeFromServer(owner, initial = true)
            sendHeartbeat(owner)
            startMonitoring(owner)
        }
    }

    fun joinInvitation(text: String, expected: SessionStamp? = state.value.session) {
        if (expected == null || state.value.session != expected || runCatching { sessions.requireCurrent(expected) }.isFailure) return
        val invitation = ListenTogetherInvitation.parse(text)
        if (invitation == null) {
            runCatching { sessions.withCurrent(expected) {
                synchronized(stateLock) {
                    if (state.value.session == expected) {
                        _state.value = state.value.copy(error = context.getString(R.string.listen_invitation_invalid))
                    }
                }
            } }
            return
        }
        join(invitation.roomId, invitation.inviterId, expected)
    }

    fun end(expected: ListenTogetherSessionState = state.value) {
        if (expected.session == null || expected.room == null) return
        launchAction(expected.session, expected.room.id) { owner ->
            val room = room(owner) ?: return@launchAction
            var failure: Throwable? = null
            try {
                request(owner) { repository.endListenTogetherRoom(owner.session, room.id) }
            } catch (error: Exception) {
                if (error is CancellationException || error is SessionChangedException) throw error
                failure = error
            }
            clearSession(owner,
                failure?.let { context.getString(R.string.listen_local_end_notice, it.message.orEmpty()) },
            )
        }
    }

    fun dismissError() { synchronized(stateLock) { _state.value = state.value.copy(error = null) } }
    fun dismissNotice() { synchronized(stateLock) { _state.value = state.value.copy(notice = null) } }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val target = mediaItem?.mediaId?.toLongOrNull()
        val former = formerSongId
        val owner = reportOwner()
        if (owner != null) {
            launchReport(owner) { reportCommand(owner, ListenTogetherCommand.GoTo, former, target) }
        }
        formerSongId = target
        updateInvitationUrl()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val owner = reportOwner() ?: return
        val activePlayer = owner.player ?: return
        if (!playWhenReady && activePlayer.playbackState == Player.STATE_BUFFERING) return
        launchReport(owner) {
            reportCommand(
                owner,
                if (playWhenReady) ListenTogetherCommand.Play else ListenTogetherCommand.Pause,
                activePlayer.currentMediaItem?.mediaId?.toLongOrNull(),
                activePlayer.currentMediaItem?.mediaId?.toLongOrNull(),
            )
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        val owner = reportOwner() ?: return
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            val songId = owner.player?.currentMediaItem?.mediaId?.toLongOrNull()
            launchReport(owner) { reportCommand(owner, ListenTogetherCommand.Progress, songId, songId) }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        val owner = reportOwner() ?: return
        queueReportJob?.cancel()
        queueReportJob = launchReport(owner) {
            delay(350)
            if (reportOwner() == owner) reportPlaylist(owner)
        }
    }

    private fun launchAction(
        expected: SessionStamp? = null, expectedRoom: String? = null, block: suspend (Owner) -> Unit,
    ) {
        if (actionJob?.isActive == true) return
        val stamp = runCatching { sessions.snapshot() }.getOrNull() ?: return
        if (sessions.recoveryRequired.value || (expected != null && expected != stamp)) return
        if (!authenticated(stamp)) {
            if (expected != null) runCatching { sessions.withCurrent(stamp) {
                synchronized(stateLock) {
                    if (state.value.session == stamp) {
                        _state.value = state.value.copy(error = context.getString(R.string.listen_account_missing))
                    }
                }
            } }
            return
        }
        val owner = runCatching {
            sessions.withCurrent(stamp) {
                synchronized(stateLock) {
                    if (expectedRoom != null && state.value.room?.id != expectedRoom) throw SessionChangedException()
                    val current = state.value.takeIf { it.session == stamp } ?: ListenTogetherSessionState(session = stamp)
                    resetWork(stamp, clear = false)
                    _state.value = current.copy(isLoading = true, error = null, notice = null)
                    Owner(stamp, version, player)
                }
            }
        }.getOrNull() ?: return
        actionJob = workScope.launch {
            try {
                checkOwner(owner)
                block(owner)
                withOwner(owner) { _state.value = state.value.copy(isLoading = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is SessionChangedException) return@launch
                currentCoroutineContext().ensureActive()
                runCatching {
                    withOwner(owner) { _state.value = state.value.copy(isLoading = false, error = error.message) }
                    if (room(owner) != null) startMonitoring(owner)
                }
            }
        }
    }

    private fun establish(owner: Owner, room: ListenTogetherRoom) = withOwner(owner) {
        check(room.id.isNotBlank()) { "Invalid Listen Together room" }
        clientSequence = 0
        lastPlaylistSignature = null
        lastCommandSignature = null
        consecutiveFailures = 0
        suppressReportsUntilMs = elapsedRealtime() + 1_000
        _state.value = ListenTogetherSessionState(
            room = room,
            isHost = room.creatorId == owner.session.identity.userId.toString(),
            connection = ListenTogetherConnection.Connected,
            invitationUrl = buildInvitationUrl(owner, room),
            session = owner.session,
        )
    }

    private fun clearSession(owner: Owner, notice: String? = null) = withOwner(owner) {
        resetWork(owner.session)
        _state.value = state.value.copy(notice = notice)
    }

    /** Retire the entire room job tree, including player-event reports already queued. */
    private fun resetWork(stamp: SessionStamp?, clear: Boolean = true) {
        version++
        val previous = work
        work = SupervisorJob(scope.coroutineContext[Job])
        workScope = CoroutineScope(scope.coroutineContext + work)
        previous.cancel()
        monitorJob = null
        queueReportJob = null
        actionJob = null
        if (!clear) return
        clientSequence = 0
        applyingRemoteState = false
        lastPlaylistSignature = null
        lastCommandSignature = null
        _state.value = ListenTogetherSessionState(session = stamp)
    }

    private fun startMonitoring(owner: Owner) {
        checkOwner(owner)
        monitorJob?.cancel()
        monitorJob = workScope.launch {
            var tick = 0
            while (isActive && _state.value.room != null) {
                tick++
                try {
                    synchronizeFromServer(owner, initial = false)
                    if (tick == 1 || tick % 5 == 0) {
                        refreshRoomStatus(owner)
                        sendHeartbeat(owner)
                    }
                    withOwner(owner) {
                        consecutiveFailures = 0
                        _state.value = state.value.copy(connection = ListenTogetherConnection.Connected, lastSyncTimeMs = currentTimeMillis())
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (error is SessionChangedException) return@launch
                    currentCoroutineContext().ensureActive()
                    try {
                        withOwner(owner) {
                            consecutiveFailures++
                            if (consecutiveFailures >= 2) {
                                _state.value = state.value.copy(connection = ListenTogetherConnection.Reconnecting)
                            }
                        }
                    } catch (_: SessionChangedException) { return@launch }
                }
                delay(1_000)
            }
        }
    }

    private suspend fun refreshRoomStatus(owner: Owner) {
        val expected = room(owner) ?: return
        val status = request(owner) { repository.listenTogetherStatus(owner.session) }
        if (!status.isInRoom || status.room == null) {
            clearSession(owner, context.getString(R.string.listen_room_ended_notice))
            return
        }
        if (status.room.id != expected.id) {
            clearSession(owner, context.getString(R.string.listen_other_room_notice))
            return
        }
        withOwner(owner) { _state.value = state.value.copy(
            room = status.room,
            isHost = status.room.creatorId == owner.session.identity.userId.toString(),
            invitationUrl = buildInvitationUrl(owner, status.room),
        ) }
    }

    private suspend fun synchronizeFromServer(owner: Owner, initial: Boolean) {
        val room = room(owner) ?: return
        val activePlayer = owner.player ?: return
        val snapshot = request(owner) { repository.listenTogetherPlayback(owner.session, room.id) }
        val playlistSignature = snapshot.playMode.orEmpty() + "|" + snapshot.songIds.joinToString(",")
        val command = snapshot.command
        val commandSignature = command?.let {
            "${it.serverSequence}|${it.clientSequence}|${it.commandType}|${it.targetSongId}|${it.progressMs}|${it.isPlaying}"
        }
        val playlistChanged = playlistSignature != lastPlaylistSignature
        val commandChanged = commandSignature != lastCommandSignature
        if (!initial && !playlistChanged && !commandChanged) return

        val currentIds = (0 until activePlayer.mediaItemCount).mapNotNull {
            activePlayer.getMediaItemAt(it).mediaId.toLongOrNull()
        }
        val songIds = snapshot.songIds.ifEmpty { currentIds }
        val targetId = command?.targetSongId ?: activePlayer.currentMediaItem?.mediaId?.toLongOrNull() ?: songIds.firstOrNull()
            ?: error(context.getString(R.string.listen_invalid_playback))
        val completeIds = if (targetId in songIds) songIds else songIds + targetId
        val items = if (completeIds == currentIds) {
            (0 until activePlayer.mediaItemCount).map(activePlayer::getMediaItemAt)
        } else loadMediaItems(owner, completeIds)
        val targetIndex = items.indexOfFirst { it.mediaId == targetId.toString() }
        check(targetIndex >= 0) { context.getString(R.string.listen_invalid_playback) }

        currentCoroutineContext().ensureActive()
        withOwner(owner) {
            applyingRemoteState = true
            suppressReportsUntilMs = elapsedRealtime() + 1_000
            try {
                if (completeIds != currentIds) {
                    activePlayer.setMediaItems(items, targetIndex, command?.progressMs?.coerceAtLeast(0) ?: 0)
                    requireOwnerFields(owner)
                    activePlayer.prepare()
                } else if (initial || commandChanged) {
                    activePlayer.seekTo(targetIndex, command?.progressMs?.coerceAtLeast(0) ?: activePlayer.currentPosition)
                }
                requireOwnerFields(owner)
                snapshot.playMode?.uppercase()?.let { mode ->
                    activePlayer.shuffleModeEnabled = "RANDOM" in mode || "SHUFFLE" in mode
                }
                requireOwnerFields(owner)
                command?.isPlaying?.let { if (it) activePlayer.play() else activePlayer.pause() }
                requireOwnerFields(owner)
                lastPlaylistSignature = playlistSignature
                lastCommandSignature = commandSignature
                _state.value = state.value.copy(invitationUrl = buildInvitationUrl(owner, room))
            } finally {
                applyingRemoteState = false
            }
        }
    }

    private suspend fun loadMediaItems(owner: Owner, ids: List<Long>): List<MediaItem> {
        val byId = LinkedHashMap<Long, MediaItem>()
        ids.chunked(100).forEach { page ->
            val response = request(owner) { apiService.getSongDetail(GetSongDetails(page.joinToString(",")), owner.session) }
            response.songs.forEach { byId[it.id] = it.toMediaItem() }
        }
        return ids.mapNotNull(byId::get)
    }

    private suspend fun reportPlaylist(owner: Owner) {
        val room = room(owner) ?: return
        val activePlayer = owner.player ?: return
        val displayIds = (0 until activePlayer.mediaItemCount).mapNotNull {
            activePlayer.getMediaItemAt(it).mediaId.toLongOrNull()
        }
        check(displayIds.isNotEmpty()) { context.getString(R.string.listen_play_song_first) }
        // The server receives the displayed list as a valid deterministic fallback. Its playMode
        // and subsequent commands still preserve the room's shuffle semantics.
        val randomIds = displayIds
        val sequence = nextSequence(owner)
        request(owner) { repository.reportListenTogetherPlaylist(owner.session, room.id, sequence, displayIds, randomIds.ifEmpty { displayIds }) }
    }

    private suspend fun reportCommand(
        owner: Owner,
        command: ListenTogetherCommand,
        formerSongId: Long?,
        targetSongId: Long?,
    ) {
        val room = room(owner) ?: return
        val activePlayer = owner.player ?: return
        val target = targetSongId ?: return
        try {
            val sequence = nextSequence(owner)
            request(owner) { repository.reportListenTogetherCommand(
                session = owner.session,
                roomId = room.id,
                command = command,
                progressMs = activePlayer.currentPosition.coerceAtLeast(0),
                isPlaying = activePlayer.isPlaying,
                formerSongId = formerSongId,
                targetSongId = target,
                clientSequence = sequence,
            ) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is SessionChangedException) throw error
            markReconnecting(owner)
        }
    }

    private suspend fun sendHeartbeat(owner: Owner) {
        val room = room(owner) ?: return
        val activePlayer = owner.player ?: return
        val songId = activePlayer.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        request(owner) { repository.sendListenTogetherHeartbeat(owner.session, room.id, songId, activePlayer.isPlaying, activePlayer.currentPosition) }
    }

    private fun reportOwner(): Owner? {
        val owner = synchronized(stateLock) {
            val stamp = state.value.session
            if (stamp == null || state.value.room == null || applyingRemoteState || elapsedRealtime() < suppressReportsUntilMs) null
            else Owner(stamp, version, player)
        } ?: return null
        return owner.takeIf { runCatching { checkOwner(it) }.isSuccess }
    }

    private fun nextSequence(owner: Owner) = withOwner(owner) { ++clientSequence }

    private fun markReconnecting(owner: Owner) = withOwner(owner) {
        _state.value = state.value.copy(connection = ListenTogetherConnection.Reconnecting)
    }

    private fun updateInvitationUrl() {
        val owner = synchronized(stateLock) {
            val stamp = state.value.session
            if (stamp == null || state.value.room == null || applyingRemoteState) null else Owner(stamp, version, player)
        } ?: return
        runCatching { withOwner(owner) {
            val room = state.value.room ?: return@withOwner
            _state.value = state.value.copy(invitationUrl = buildInvitationUrl(owner, room))
        } }
    }

    private fun buildInvitationUrl(owner: Owner, room: ListenTogetherRoom): String? {
        val inviterId = owner.session.identity.userId.toString()
        val songId = owner.player?.currentMediaItem?.mediaId?.toLongOrNull() ?: return null
        return Uri.Builder()
            .scheme("https")
            .authority("st.music.163.com")
            .appendPath("listen-together")
            .appendPath("share")
            .appendQueryParameter("songId", songId.toString())
            .appendQueryParameter("roomId", room.id)
            .appendQueryParameter("inviterId", inviterId)
            .build()
            .toString()
    }

    private fun authenticated(stamp: SessionStamp) = stamp.identity.authenticated && !stamp.identity.anonymous && stamp.identity.userId > 0

    private fun requireOwnerFields(owner: Owner) {
        if (version != owner.version || state.value.session != owner.session || player !== owner.player || sessions.recoveryRequired.value) {
            throw SessionChangedException()
        }
    }

    private fun <T> withOwner(owner: Owner, block: () -> T): T = sessions.withCurrent(owner.session) {
        synchronized(stateLock) { requireOwnerFields(owner); block() }
    }

    private fun checkOwner(owner: Owner) = withOwner(owner) {}
    private fun room(owner: Owner) = withOwner(owner) { state.value.room }

    private suspend fun <T> request(owner: Owner, block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        checkOwner(owner)
        val result = block()
        currentCoroutineContext().ensureActive()
        checkOwner(owner)
        return result
    }

    private fun launchReport(owner: Owner, block: suspend () -> Unit): Job? {
        if (runCatching { checkOwner(owner) }.isFailure) return null
        return workScope.launch {
            try { checkOwner(owner); block() }
            catch (error: CancellationException) { throw error }
            catch (_: SessionChangedException) {}
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { markReconnecting(owner) }
            }
        }
    }

    override fun close() {
        invalidation.close()
        observer.cancel()
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        player = null
        synchronized(stateLock) { resetWork(null); work.cancel() }
        scope.cancel()
    }
}
