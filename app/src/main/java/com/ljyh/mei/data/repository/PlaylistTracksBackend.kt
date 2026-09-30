package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.api.ManipulateTrackResult
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore

fun interface PlaylistTracksBackend {
    suspend fun modifySources(op: String, playlistId: Long, sources: List<SongSourceIdentity>, owner: SessionStamp): ManipulateTrackResult

    suspend fun modify(op: String, playlistId: Long, trackIds: List<Long>, owner: SessionStamp): ManipulateTrackResult =
        modifySources(op, playlistId, trackIds.map(::SongSourceIdentity), owner)
}

internal fun parsePlaylistTrackSources(keys: String, owner: SessionStamp): List<SongSourceIdentity> =
    keys.split(',').map { SongSourceIdentity.fromKey(it).also { source -> source.requireAccount(owner.identity) } }.distinct()

internal fun SessionStore.requirePlaylistMutationOwner(owner: SessionStamp) {
    requireCurrent(owner)
    if (recoveryRequired.value) throw SessionChangedException()
    check(owner.identity.authenticated && !owner.identity.anonymous && owner.identity.userId > 0) { "Sign-in required" }
}
