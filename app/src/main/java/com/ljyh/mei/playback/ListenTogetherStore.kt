package com.ljyh.mei.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.ljyh.mei.R
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.melox.ListenTogetherCommand
import com.ljyh.mei.data.model.melox.ListenTogetherInvitation
import com.ljyh.mei.data.model.melox.ListenTogetherRoom
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
)

/** Shared room session coordinating NetEase state with the Media3 player. */
@Singleton
class ListenTogetherStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MeloXRepository,
    private val apiService: ApiService,
) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ListenTogetherSessionState())
    val state = _state.asStateFlow()

    private var player: Player? = null
    private var monitorJob: Job? = null
    private var queueReportJob: Job? = null
    private var actionJob: Job? = null
    private var clientSequence = 0L
    private var localUserId: Long? = null
    private var applyingRemoteState = false
    private var suppressReportsUntilMs = 0L
    private var lastPlaylistSignature: String? = null
    private var lastCommandSignature: String? = null
    private var formerSongId: Long? = null
    private var consecutiveFailures = 0

    fun attachPlayer(value: Player) {
        if (player === value) return
        player?.removeListener(this)
        player = value
        formerSongId = value.currentMediaItem?.mediaId?.toLongOrNull()
        value.addListener(this)
        refresh()
    }

    fun detachPlayer(value: Player) {
        if (player !== value) return
        value.removeListener(this)
        player = null
        monitorJob?.cancel()
        monitorJob = null
    }

    fun refresh() {
        if (actionJob?.isActive == true) return
        actionJob = scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val status = repository.listenTogetherStatus()
                if (!status.isInRoom || status.room == null) {
                    clearSession()
                } else {
                    establish(status.room)
                    synchronizeFromServer(initial = true)
                    sendHeartbeat()
                    startMonitoring()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.value = _state.value.copy(isLoading = false, error = error.message)
            }
        }
    }

    fun create() = launchAction {
        val activePlayer = player ?: error(context.getString(R.string.listen_player_unavailable))
        check(activePlayer.currentMediaItem != null) { context.getString(R.string.listen_play_song_first) }
        val room = repository.createListenTogetherRoom()
        establish(room)
        reportPlaylist()
        reportCommand(ListenTogetherCommand.GoTo, formerSongId, activePlayer.currentMediaItem?.mediaId?.toLongOrNull())
        sendHeartbeat()
        startMonitoring()
    }

    fun join(roomId: String, inviterId: String) = launchAction {
        val roomCheck = repository.checkListenTogetherRoom(roomId.trim())
        check(roomCheck.first) {
            roomCheck.second?.takeIf(String::isNotBlank)
                ?: context.getString(R.string.listen_room_unavailable)
        }
        val room = repository.acceptListenTogetherRoom(roomId.trim(), inviterId.trim())
        establish(room)
        synchronizeFromServer(initial = true)
        sendHeartbeat()
        startMonitoring()
    }

    fun joinInvitation(text: String) {
        val invitation = ListenTogetherInvitation.parse(text)
        if (invitation == null) {
            _state.value = _state.value.copy(error = context.getString(R.string.listen_invitation_invalid))
            return
        }
        join(invitation.roomId, invitation.inviterId)
    }

    fun end() = launchAction {
        val room = _state.value.room ?: return@launchAction
        var failure: Throwable? = null
        try {
            repository.endListenTogetherRoom(room.id)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            failure = error
        }
        clearSession(
            failure?.let { context.getString(R.string.listen_local_end_notice, it.message.orEmpty()) },
        )
    }

    fun dismissError() { _state.value = _state.value.copy(error = null) }
    fun dismissNotice() { _state.value = _state.value.copy(notice = null) }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val target = mediaItem?.mediaId?.toLongOrNull()
        if (shouldReport()) {
            scope.launch { reportCommand(ListenTogetherCommand.GoTo, formerSongId, target) }
        }
        formerSongId = target
        updateInvitationUrl()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val activePlayer = player ?: return
        if (!shouldReport() || (!playWhenReady && activePlayer.playbackState == Player.STATE_BUFFERING)) return
        scope.launch {
            reportCommand(
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
        if (reason == Player.DISCONTINUITY_REASON_SEEK && shouldReport()) {
            val songId = player?.currentMediaItem?.mediaId?.toLongOrNull()
            scope.launch { reportCommand(ListenTogetherCommand.Progress, songId, songId) }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (!shouldReport()) return
        queueReportJob?.cancel()
        queueReportJob = scope.launch {
            delay(350)
            if (shouldReport()) runCatching { reportPlaylist() }.onFailure { markReconnecting() }
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        if (actionJob?.isActive == true) return
        actionJob = scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null, notice = null)
            try {
                block()
                _state.value = _state.value.copy(isLoading = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.value = _state.value.copy(isLoading = false, error = error.message)
            }
        }
    }

    private suspend fun establish(room: ListenTogetherRoom) {
        localUserId = context.dataStore.data.first()[UserIdKey]?.toLongOrNull()
            ?: room.creatorId.toLongOrNull()
        clientSequence = 0
        lastPlaylistSignature = null
        lastCommandSignature = null
        consecutiveFailures = 0
        suppressReportsUntilMs = SystemClock.elapsedRealtime() + 1_000
        _state.value = ListenTogetherSessionState(
            room = room,
            isHost = room.creatorId == localUserId?.toString(),
            connection = ListenTogetherConnection.Connected,
            invitationUrl = buildInvitationUrl(room),
        )
    }

    private fun clearSession(notice: String? = null) {
        monitorJob?.cancel()
        monitorJob = null
        queueReportJob?.cancel()
        queueReportJob = null
        clientSequence = 0
        localUserId = null
        applyingRemoteState = false
        lastPlaylistSignature = null
        lastCommandSignature = null
        _state.value = ListenTogetherSessionState(notice = notice)
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var tick = 0
            while (isActive && _state.value.room != null) {
                tick++
                try {
                    synchronizeFromServer(initial = false)
                    if (tick == 1 || tick % 5 == 0) {
                        refreshRoomStatus()
                        sendHeartbeat()
                    }
                    consecutiveFailures = 0
                    _state.value = _state.value.copy(
                        connection = ListenTogetherConnection.Connected,
                        lastSyncTimeMs = System.currentTimeMillis(),
                    )
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    consecutiveFailures++
                    if (consecutiveFailures >= 2) markReconnecting()
                }
                delay(1_000)
            }
        }
    }

    private suspend fun refreshRoomStatus() {
        val expected = _state.value.room ?: return
        val status = repository.listenTogetherStatus()
        if (!status.isInRoom || status.room == null) {
            clearSession(context.getString(R.string.listen_room_ended_notice))
            return
        }
        if (status.room.id != expected.id) {
            clearSession(context.getString(R.string.listen_other_room_notice))
            return
        }
        _state.value = _state.value.copy(
            room = status.room,
            isHost = status.room.creatorId == localUserId?.toString(),
            invitationUrl = buildInvitationUrl(status.room),
        )
    }

    private suspend fun synchronizeFromServer(initial: Boolean) {
        val room = _state.value.room ?: return
        val activePlayer = player ?: return
        val snapshot = repository.listenTogetherPlayback(room.id)
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
        } else loadMediaItems(completeIds)
        val targetIndex = items.indexOfFirst { it.mediaId == targetId.toString() }
        check(targetIndex >= 0) { context.getString(R.string.listen_invalid_playback) }

        applyingRemoteState = true
        suppressReportsUntilMs = SystemClock.elapsedRealtime() + 1_000
        try {
            if (completeIds != currentIds) {
                activePlayer.setMediaItems(items, targetIndex, command?.progressMs?.coerceAtLeast(0) ?: 0)
                activePlayer.prepare()
            } else if (initial || commandChanged) {
                activePlayer.seekTo(targetIndex, command?.progressMs?.coerceAtLeast(0) ?: activePlayer.currentPosition)
            }
            snapshot.playMode?.uppercase()?.let { mode ->
                activePlayer.shuffleModeEnabled = "RANDOM" in mode || "SHUFFLE" in mode
            }
            command?.isPlaying?.let { if (it) activePlayer.play() else activePlayer.pause() }
        } finally {
            applyingRemoteState = false
        }
        lastPlaylistSignature = playlistSignature
        lastCommandSignature = commandSignature
    }

    private suspend fun loadMediaItems(ids: List<Long>): List<MediaItem> {
        val byId = LinkedHashMap<Long, MediaItem>()
        ids.chunked(100).forEach { page ->
            val response = apiService.getSongDetail(GetSongDetails(page.joinToString(",")))
            response.songs.forEach { byId[it.id] = it.toMediaItem() }
        }
        return ids.mapNotNull(byId::get)
    }

    private suspend fun reportPlaylist() {
        val room = _state.value.room ?: return
        val activePlayer = player ?: return
        val userId = localUserId ?: error(context.getString(R.string.listen_account_missing))
        val displayIds = (0 until activePlayer.mediaItemCount).mapNotNull {
            activePlayer.getMediaItemAt(it).mediaId.toLongOrNull()
        }
        check(displayIds.isNotEmpty()) { context.getString(R.string.listen_play_song_first) }
        // The server receives the displayed list as a valid deterministic fallback. Its playMode
        // and subsequent commands still preserve the room's shuffle semantics.
        val randomIds = displayIds
        repository.reportListenTogetherPlaylist(room.id, userId, nextSequence(), displayIds, randomIds.ifEmpty { displayIds })
    }

    private suspend fun reportCommand(
        command: ListenTogetherCommand,
        formerSongId: Long?,
        targetSongId: Long?,
    ) {
        val room = _state.value.room ?: return
        val activePlayer = player ?: return
        val target = targetSongId ?: return
        try {
            repository.reportListenTogetherCommand(
                roomId = room.id,
                command = command,
                progressMs = activePlayer.currentPosition.coerceAtLeast(0),
                isPlaying = activePlayer.isPlaying,
                formerSongId = formerSongId,
                targetSongId = target,
                clientSequence = nextSequence(),
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            markReconnecting()
        }
    }

    private suspend fun sendHeartbeat() {
        val room = _state.value.room ?: return
        val activePlayer = player ?: return
        val songId = activePlayer.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        repository.sendListenTogetherHeartbeat(room.id, songId, activePlayer.isPlaying, activePlayer.currentPosition)
    }

    private fun shouldReport() = _state.value.room != null &&
        !applyingRemoteState && SystemClock.elapsedRealtime() >= suppressReportsUntilMs

    private fun nextSequence() = ++clientSequence

    private fun markReconnecting() {
        _state.value = _state.value.copy(connection = ListenTogetherConnection.Reconnecting)
    }

    private fun updateInvitationUrl() {
        val room = _state.value.room ?: return
        _state.value = _state.value.copy(invitationUrl = buildInvitationUrl(room))
    }

    private fun buildInvitationUrl(room: ListenTogetherRoom): String? {
        val inviterId = localUserId?.toString() ?: room.creatorId.takeIf(String::isNotBlank) ?: return null
        val songId = player?.currentMediaItem?.mediaId?.toLongOrNull() ?: return null
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
}
