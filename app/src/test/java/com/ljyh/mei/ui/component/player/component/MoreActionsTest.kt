package com.ljyh.mei.ui.component.player.component

import com.ljyh.mei.ui.model.MoreAction
import com.ljyh.mei.ui.model.PlayerAction
import com.ljyh.mei.ui.model.SortOrder
import com.ljyh.mei.ui.model.resolveMoreActionOrder
import com.ljyh.mei.ui.model.availableFor
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.supportsNeteaseSongActions
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.api.MusicProvider
import com.ljyh.mei.source.api.SourceFeatureCapability
import com.ljyh.mei.source.api.SourceCapability
import com.ljyh.mei.source.api.SourceStatus
import com.ljyh.mei.source.api.PlaylistEditCapability
import com.ljyh.mei.source.api.PlaylistEditAction
import com.ljyh.mei.source.api.PlaylistRef
import com.ljyh.mei.source.api.SourcePlaylist
import org.junit.Assert.*
import org.junit.Test

class MoreActionsTest {
    @Test
    fun playerToolbarKeepsConfiguredOrderAndSupportsAnyOwningPlaylistEditor() {
        val metadata = MediaMetadata(0, "Song", "", emptyList(), 180000, MediaMetadata.Album(0, "Album"),
            trackRef = TrackRef("qq", "mid:opaque"))
        val editor = object : MusicProvider, PlaylistEditCapability {
            override val id = "qq"
            override val displayName = "Fixture"
            override val capabilities = setOf(SourceCapability.PLAYLIST_EDIT)
            override var status = SourceStatus.READY
            override var playlistEditActions = setOf(PlaylistEditAction.ADD_TRACKS)
            override suspend fun createPlaylist(title: String, description: String?): SourcePlaylist = error("Discovery must not write")
            override suspend fun updatePlaylist(ref: PlaylistRef, title: String?, description: String?): Unit = error("Discovery must not write")
            override suspend fun deletePlaylist(ref: PlaylistRef): Unit = error("Discovery must not write")
            override suspend fun addTracks(ref: PlaylistRef, tracks: List<TrackRef>): Unit = error("Discovery must not write")
            override suspend fun removeTracks(ref: PlaylistRef, tracks: List<TrackRef>): Unit = error("Discovery must not write")
            override suspend fun reorderTracks(ref: PlaylistRef, tracks: List<TrackRef>): Unit = error("Discovery must not write")
        }
        val configured = listOf(PlayerAction.SLEEP_TIMER, PlayerAction.ADD_TO_PLAYLIST, PlayerAction.LYRICS)
        assertEquals(configured, configured.filter { it.availableFor(metadata, editor) })
        assertTrue(MoreAction.ADD_TO_PLAYLIST.availableFor(metadata, editor))
        assertFalse(PlayerAction.ADD_TO_PLAYLIST.availableFor(metadata, null))
        assertFalse(PlayerAction.ADD_TO_PLAYLIST.availableFor(metadata.copy(trackRef = TrackRef("another", "mid")), editor))
        assertFalse(PlayerAction.ADD_TO_PLAYLIST.availableFor(metadata.copy(isLocal = true), editor))
        assertFalse(PlayerAction.ADD_TO_PLAYLIST.availableFor(metadata.copy(isPodcast = true), editor))
        editor.playlistEditActions = emptySet()
        assertEquals(listOf(PlayerAction.SLEEP_TIMER, PlayerAction.LYRICS), configured.filter { it.availableFor(metadata, editor) })
        editor.playlistEditActions = setOf(PlaylistEditAction.ADD_TRACKS)
        editor.status = SourceStatus.DISABLED
        assertFalse(PlayerAction.ADD_TO_PLAYLIST.availableFor(metadata, editor))
    }

    @Test
    fun onlineActionsUseTheOwningSourcesCapabilitiesAndHostActionsRemainAvailable() {
        val native = MediaMetadata(42, "Song", "", emptyList(), 180000, MediaMetadata.Album(1, "Album"))
        val external = native.copy(id = 0L, trackRef = TrackRef("demo", "42"))
        assertTrue(native.supportsNeteaseSongActions)
        assertFalse(external.supportsNeteaseSongActions)
        assertTrue(MoreAction.SHARE.availableFor(native))
        assertTrue(MoreAction.SHARE.availableFor(external))
        for (action in listOf(MoreAction.ADD_TO_PLAYLIST, MoreAction.COMMENT, MoreAction.SONG_WIKI)) {
            assertFalse(action.availableFor(native))
            assertFalse(action.availableFor(external))
        }
        val source = object : MusicProvider, SourceFeatureCapability {
            override val id = "demo"
            override val displayName = "Fixture"
            override val capabilities = setOf(SourceCapability.FEATURES)
            override val features = mapOf("social" to setOf("comments", "songWiki"))
            override var status = SourceStatus.READY
            override suspend fun invokeFeature(feature: String, action: String, parameters: com.google.gson.JsonObject) =
                error("Menu discovery must not invoke a source")
        }
        for (action in listOf(MoreAction.COMMENT, MoreAction.SONG_WIKI)) {
            assertTrue(action.availableFor(external, source))
            assertFalse(action.availableFor(native, source))
        }
        source.status = SourceStatus.DISABLED
        assertFalse(MoreAction.COMMENT.availableFor(external, source))
        for (action in listOf(MoreAction.DOWNLOAD, MoreAction.SONG_INFO, MoreAction.VIEW_PLAYLIST, MoreAction.SLEEP_TIMER)) {
            assertTrue(action.availableFor(external))
        }
    }
    @Test
    fun customOrderSurvivesRemovedDuplicateAndNewActionsWithoutLosingMeiloXFeatures() {
        val actions = resolveMoreActionOrder(SortOrder.CUSTOM, listOf("pip", "removed", "share", "pip"))
        assertEquals(listOf(MoreAction.PICTURE_IN_PICTURE, MoreAction.SHARE), actions.take(2))
        assertEquals(MoreAction.entries.toSet(), actions.toSet())
        assertEquals(MoreAction.entries.size, actions.size)
        assertTrue(actions.contains(MoreAction.SONG_WIKI))
    }

    @Test
    fun builtInOrdersDoNotDestroyTheSavedCustomOrder() {
        val ids = MoreAction.entries.reversed().map { it.id }
        assertEquals(MoreAction.entries.sortedByDescending { it.frequency }, resolveMoreActionOrder(SortOrder.FREQUENCY, ids))
        assertEquals(MoreAction.entries.reversed(), resolveMoreActionOrder(SortOrder.CUSTOM, ids))
    }
}
