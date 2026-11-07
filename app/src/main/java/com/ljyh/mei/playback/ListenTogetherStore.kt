package com.ljyh.mei.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.ljyh.mei.R
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.model.melox.ListenTogetherCommand
import com.ljyh.mei.data.model.melox.ListenTogetherRoom
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.shared.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

enum class ListenTogetherConnection { Idle, Connected, Reconnecting }
data class ListenTogetherSessionState(val isLoading: Boolean = false, val room: ListenTogetherRoom? = null,
    val isHost: Boolean = false, val connection: ListenTogetherConnection = ListenTogetherConnection.Idle,
    val lastSyncTimeMs: Long? = null, val invitationUrl: String? = null, val error: String? = null,
    val notice: String? = null, val providerId: String? = null)

/** Media3 synchronization is host-owned; each room is bound to one source instance and account. */
@Singleton
class ListenTogetherStore @Inject constructor(@ApplicationContext private val context: Context,
    private val repository: SourceTogetherRepository) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ListenTogetherSessionState()); val state = _state.asStateFlow()
    private var session: SourceTogetherSession? = null
    private var ownerJob: Job? = null
    private var ownerScope: CoroutineScope? = null
    private var player: Player? = null
    private var bindingJob: Job? = null
    private var monitorJob: Job? = null
    private var queueReportJob: Job? = null
    private var actionJob: Job? = null
    private var generation = 0L
    private var clientSequence = 0L
    private var lastRemoteSequence = 0L
    private var lastSentCommand: SourceTogetherCommand? = null
    private var applyingRemoteState = false
    private var suppressReportsUntilMs = 0L
    private var lastPlaylistSignature: String? = null
    private var lastCommandSignature: String? = null
    private var formerSong: TrackRef? = null
    private var consecutiveFailures = 0
    private val commandJobs = mutableSetOf<Job>()

    fun open(providerId: String? = null) {
        if (session != null || bindingJob?.isActive == true) return
        bindingJob = scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val bound = repository.open(providerId)
                session = bound
                val lifetime = SupervisorJob(scope.coroutineContext[Job]); ownerJob = lifetime
                ownerScope = CoroutineScope(scope.coroutineContext + lifetime)
                bound.owner.guard(ownerScope!!) { revoke() }
                _state.value = _state.value.copy(providerId = bound.providerId)
                refresh()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { _state.value = _state.value.copy(isLoading = false, error = error.message) }
        }
    }
    fun closeScreen() {
        if (_state.value.room == null) { bindingJob?.cancel(); bindingJob = null; ownerJob?.cancel(); ownerScope = null; session = null }
    }
    private fun revoke() {
        generation++; ownerJob?.cancel(); session = null; ownerScope = null
        bindingJob = null; clearSession(); _state.value = ListenTogetherSessionState(error = "音源或账号已变更")
    }
    fun attachPlayer(value: Player) {
        if (player === value) return
        player?.removeListener(this); player = value; formerSong = value.currentMediaItem?.sourceRef(); value.addListener(this)
        if (session != null) refresh()
    }
    fun detachPlayer(value: Player) {
        if (player !== value) return
        value.removeListener(this); player = null; monitorJob?.cancel(); monitorJob = null
    }
    fun refresh() = launchAction { bound ->
        val status = bound.status()
        if (!status.inRoom || status.room == null) clearSession()
        else { establish(status.room); synchronizeFromServer(true); sendHeartbeat(); startMonitoring() }
    }
    fun create() = launchAction { bound ->
        val active = player ?: error(context.getString(R.string.listen_player_unavailable))
        val target = active.currentMediaItem?.sourceRef() ?: error(context.getString(R.string.listen_play_song_first))
        check(target.providerId == bound.providerId) { "当前歌曲不属于此一起听音源" }
        val room = bound.create(); establish(room); reportPlaylist()
        reportCommand(ListenTogetherCommand.GoTo, formerSong, target); sendHeartbeat(); startMonitoring()
    }
    fun join(roomId: String, inviterId: String) = launchAction { bound -> join(bound, roomId.trim(), inviterId.trim()) }
    private suspend fun join(bound: SourceTogetherSession, roomId: String, inviterId: String) {
        establish(bound.join(roomId, inviterId)); synchronizeFromServer(true); sendHeartbeat(); startMonitoring()
    }
    fun joinInvitation(text: String) = launchAction { bound ->
        val invitation = bound.invitation(text) ?: error(context.getString(R.string.listen_invitation_invalid))
        join(bound, invitation.roomId, invitation.inviterId)
    }
    fun end() = launchAction { bound ->
        val room = _state.value.room ?: return@launchAction
        var failure: Throwable? = null
        try { bound.end(room.id) } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { failure = error }
        clearSession(failure?.let { context.getString(R.string.listen_local_end_notice, it.message.orEmpty()) })
    }
    fun dismissError() { _state.value = _state.value.copy(error = null) }
    fun dismissNotice() { _state.value = _state.value.copy(notice = null) }

    private fun launchAction(operation: suspend (SourceTogetherSession) -> Unit) {
        if (actionJob?.isActive == true) return
        val bound = session ?: return
        actionJob = ownerScope?.launch {
            _state.value = _state.value.copy(isLoading = true, error = null, notice = null)
            try { bound.owner.ensureCurrent(); operation(bound); bound.owner.ensureCurrent(); _state.value = _state.value.copy(isLoading = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { _state.value = _state.value.copy(isLoading = false, error = error.message) }
        }
    }
    private suspend fun establish(room: ListenTogetherRoom) {
        clearSession()
        val bound = session ?: throw CancellationException()
        clientSequence = 0; lastRemoteSequence = 0; consecutiveFailures = 0
        suppressReportsUntilMs = SystemClock.elapsedRealtime() + 1000
        _state.value = ListenTogetherSessionState(room = room, isHost = room.creatorId == bound.userId,
            connection = ListenTogetherConnection.Connected, providerId = bound.providerId)
        updateInvitationUrl()
    }
    private fun clearSession(notice: String? = null) {
        generation++; monitorJob?.cancel(); monitorJob = null; queueReportJob?.cancel(); queueReportJob = null
        commandJobs.toList().forEach { it.cancel() }; commandJobs.clear()
        clientSequence = 0; lastRemoteSequence = 0; lastSentCommand = null; applyingRemoteState = false; lastPlaylistSignature = null; lastCommandSignature = null
        _state.value = ListenTogetherSessionState(notice = notice, providerId = session?.providerId)
    }
    private fun startMonitoring() {
        monitorJob?.cancel()
        val expected = generation
        monitorJob = ownerScope?.launch {
            var tick = 0
            while (isActive && generation == expected && _state.value.room != null) {
                try {
                    synchronizeFromServer(false)
                    if (++tick == 1 || tick % 5 == 0) { refreshRoomStatus(); sendHeartbeat() }
                    if (generation != expected) return@launch
                    consecutiveFailures = 0
                    _state.value = _state.value.copy(connection = ListenTogetherConnection.Connected, lastSyncTimeMs = System.currentTimeMillis())
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { if (++consecutiveFailures >= 2) markReconnecting() }
                delay(1000)
            }
        }
    }
    private suspend fun refreshRoomStatus() {
        val bound = session ?: return; val expected = _state.value.room ?: return; val epoch = generation
        val status = bound.status()
        if (generation != epoch) return
        if (!status.inRoom || status.room == null) { clearSession(context.getString(R.string.listen_room_ended_notice)); return }
        if (status.room.id != expected.id) { clearSession(context.getString(R.string.listen_other_room_notice)); return }
        _state.value = _state.value.copy(room = status.room, isHost = status.room.creatorId == bound.userId)
        updateInvitationUrl()
    }
    private suspend fun synchronizeFromServer(initial: Boolean) {
        val bound = session ?: return; val room = _state.value.room ?: return; val active = player ?: return; val epoch = generation
        val snapshot = bound.playback(room.id)
        if (epoch != generation || _state.value.room?.id != room.id) return
        val remote = snapshot.command
        if (remote != null && remote.serverSequence > 0 && remote.serverSequence < lastRemoteSequence) return
        val playlistSignature = snapshot.playMode.orEmpty() + "|" + snapshot.tracks.joinToString { TrackRefCodec.encode(it) }
        val commandSignature = remote?.let { "${it.serverSequence}|${it.clientSequence}|${it.type}|${it.target}|${it.progressMs}|${it.isPlaying}" }
        val playlistChanged = playlistSignature != lastPlaylistSignature
        val commandChanged = commandSignature != lastCommandSignature
        if (!initial && !playlistChanged && !commandChanged) return
        val current = (0 until active.mediaItemCount).map { active.getMediaItemAt(it).sourceRef() ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE) }
        val refs = snapshot.tracks.ifEmpty { current }
        val target = remote?.target ?: active.currentMediaItem?.sourceRef() ?: refs.firstOrNull() ?: error(context.getString(R.string.listen_invalid_playback))
        val complete = if (target in refs) refs else refs + target
        val items = if (complete == current) (0 until active.mediaItemCount).map(active::getMediaItemAt) else bound.media(complete)
        bound.owner.ensureCurrent()
        if (epoch != generation || _state.value.room?.id != room.id || player !== active) return
        val targetIndex = complete.indexOf(target); check(targetIndex >= 0)
        val sent = lastSentCommand
        val echoed = !initial && remote != null && sent != null && remote.clientSequence == sent.clientSequence &&
            remote.type == sent.type && remote.target == sent.target && remote.isPlaying == sent.isPlaying &&
            abs(remote.progressMs - sent.progressMs) < 1500 && target == active.currentMediaItem?.sourceRef()
        applyingRemoteState = true; suppressReportsUntilMs = SystemClock.elapsedRealtime() + 1000
        try {
            if (complete != current) { active.setMediaItems(items, targetIndex, remote?.progressMs ?: 0); active.prepare() }
            else if (!echoed && (initial || commandChanged)) active.seekTo(targetIndex, remote?.progressMs ?: active.currentPosition)
            snapshot.playMode?.uppercase()?.let { active.shuffleModeEnabled = "RANDOM" in it || "SHUFFLE" in it }
            if (!echoed) remote?.isPlaying?.let { if (it) active.play() else active.pause() }
        } finally { applyingRemoteState = false }
        lastPlaylistSignature = playlistSignature; lastCommandSignature = commandSignature
        lastRemoteSequence = maxOf(lastRemoteSequence, remote?.serverSequence ?: 0)
    }
    private suspend fun reportPlaylist() {
        val bound = session ?: return; val room = _state.value.room ?: return; val active = player ?: return
        val refs = (0 until active.mediaItemCount).map { active.getMediaItemAt(it).sourceRef() ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE) }
        check(refs.isNotEmpty()) { context.getString(R.string.listen_play_song_first) }
        bound.reportPlaylist(room.id, ++clientSequence, refs)
    }
    private suspend fun reportCommand(command: ListenTogetherCommand, former: TrackRef?, target: TrackRef?) {
        val bound = session ?: return; val room = _state.value.room ?: return; val active = player ?: return; target ?: return
        val sequence = ++clientSequence; val progress = active.currentPosition.coerceAtLeast(0); val playing = active.playWhenReady; val epoch = generation
        try {
            bound.command(room.id, command, former, target, sequence, progress, playing)
            if (generation == epoch) lastSentCommand = SourceTogetherCommand(command.wireValue, target, former, progress, playing, sequence, 0)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { markReconnecting() }
    }
    private suspend fun sendHeartbeat() {
        val bound = session ?: return; val room = _state.value.room ?: return; val active = player ?: return; val ref = active.currentMediaItem?.sourceRef() ?: return
        bound.heartbeat(room.id, ref, active.isPlaying, active.currentPosition.coerceAtLeast(0))
    }
    private suspend fun updateInvitationUrl() {
        val bound = session ?: return; val room = _state.value.room ?: return; val song = player?.currentMediaItem?.sourceRef() ?: return; val epoch = generation
        if (song.providerId != bound.providerId) return
        val url = bound.invitationUrl(room.id, song)
        if (epoch == generation && _state.value.room?.id == room.id) _state.value = _state.value.copy(invitationUrl = url)
    }
    private fun launchCommand(block: suspend () -> Unit) {
        val job = ownerScope?.launch { try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { markReconnecting() } } ?: return
        commandJobs += job; job.invokeOnCompletion { commandJobs.remove(job) }
    }
    private fun shouldReport() = _state.value.room != null && !applyingRemoteState && SystemClock.elapsedRealtime() >= suppressReportsUntilMs
    private fun markReconnecting() { if (_state.value.room != null) _state.value = _state.value.copy(connection = ListenTogetherConnection.Reconnecting) }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val target = mediaItem?.sourceRef(); val former = formerSong
        if (shouldReport()) launchCommand { reportCommand(ListenTogetherCommand.GoTo, former, target) }
        formerSong = target; launchCommand { updateInvitationUrl() }
    }
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val active = player ?: return
        if (shouldReport() && (playWhenReady || active.playbackState != Player.STATE_BUFFERING)) launchCommand {
            val ref = active.currentMediaItem?.sourceRef(); reportCommand(if (playWhenReady) ListenTogetherCommand.Play else ListenTogetherCommand.Pause, ref, ref)
        }
    }
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK && shouldReport()) launchCommand { val ref = player?.currentMediaItem?.sourceRef(); reportCommand(ListenTogetherCommand.Progress, ref, ref) }
    }
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (!shouldReport()) return
        queueReportJob?.cancel(); queueReportJob = ownerScope?.launch { delay(350); if (shouldReport()) try { reportPlaylist() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { markReconnecting() } }
    }
}

private fun MediaItem.sourceRef(): TrackRef? = metadata?.trackRef ?: TrackRefCodec.decode(mediaId)
