package com.ljyh.mei.source.script

import com.ljyh.mei.source.api.*
import kotlinx.coroutines.CancellationException

/** Typed process-local data belongs to the host, scoped to one live provider session. */
internal class SourceCatalogCache(private val nowMs: () -> Long = System::currentTimeMillis) {
    class Owner(val instance: String, val version: String, val account: String?) {
        internal var generation = 0L
    }
    class Mutation internal constructor(val generation: Long, private val finish: () -> Long?) : AutoCloseable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        var completedGeneration: Long? = null
            private set
        override fun close() {
            if (closed.compareAndSet(false, true)) completedGeneration = finish()
        }
    }
    data class Snapshot(val playlist: SourcePlaylist, val ids: List<TrackRef>?, val metadataLimit: Int = 1)
    data class AlbumSnapshot(val album: SourceAlbumInfo, val ids: List<TrackRef>?)
    data class PageKey(val operation: String, val identity: String, val page: PageRequest)

    private enum class Kind { TRACK, SNAPSHOT, ALBUM_SNAPSHOT, LIKED_PLAYLIST, PAGE }
    private data class Key(val owner: Owner, val kind: Kind, val identity: Any = Unit)
    private data class Entry(val value: Any, val weight: Long, val expiresAtMs: Long? = null)
    private val owners = mutableMapOf<String, Owner>()
    private val verifiedAccounts = mutableMapOf<Owner, SourceAccount?>()
    private val entries = LinkedHashMap<Key, Entry>(16, .75f, true)
    private var bytes = 0L

    @Synchronized fun bind(instance: String, version: String, account: String?): Owner {
        owners[instance]?.let(::release)
        return Owner(instance, version, account).also { owners[instance] = it }
    }

    @Synchronized fun rebind(owner: Owner, account: String?): Owner {
        ensureOwner(owner)
        return bind(owner.instance, owner.version, account)
    }

    @Synchronized fun release(owner: Owner) {
        if (owners[owner.instance] === owner) owners.remove(owner.instance)
        verifiedAccounts.remove(owner)
        removeEntries { it.owner === owner }
    }

    @Synchronized fun ensureOwner(owner: Owner, validate: () -> Unit = {}) {
        validate()
        if (owners[owner.instance] !== owner) throw CancellationException("Source cache owner changed")
    }

    @Synchronized fun tracks(owner: Owner, refs: List<TrackRef>, validate: () -> Unit = {}): Map<TrackRef, Track> {
        ensureOwner(owner, validate)
        return linkedMapOf<TrackRef, Track>().apply {
            refs.forEach { ref ->
                (this@SourceCatalogCache.get(Key(owner, Kind.TRACK, ref)) as? Track)?.let { put(ref, copyTrack(it)) }
            }
        }
    }

    @Synchronized fun putTracks(owner: Owner, tracks: List<Track>, validate: () -> Unit = {}) {
        ensureOwner(owner, validate)
        val values = tracks.map { copyTrack(it) }
        ensureOwner(owner, validate)
        values.forEach { put(Key(owner, Kind.TRACK, it.ref), it, trackWeight(it)) }
    }

    @Synchronized fun snapshot(owner: Owner, playlistId: String, validate: () -> Unit = {}): Snapshot? {
        ensureOwner(owner, validate)
        return (get(Key(owner, Kind.SNAPSHOT, playlistId)) as? Snapshot)?.let(::copySnapshot)
    }

    @Synchronized fun putSnapshot(owner: Owner, snapshot: Snapshot, validate: () -> Unit = {}) {
        ensureOwner(owner, validate)
        val value = copySnapshot(snapshot)
        val weight = playlistWeight(value.playlist) + (value.ids?.sumOf { refWeight(it) + 8 } ?: 0)
        ensureOwner(owner, validate)
        put(Key(owner, Kind.SNAPSHOT, value.playlist.ref.playlistId), value, weight)
    }

    @Synchronized fun albumSnapshot(owner: Owner, albumId: String, validate: () -> Unit = {}): AlbumSnapshot? {
        ensureOwner(owner, validate)
        return (get(Key(owner, Kind.ALBUM_SNAPSHOT, albumId)) as? AlbumSnapshot)?.let(::copyAlbumSnapshot)
    }

    @Synchronized fun putAlbumSnapshot(owner: Owner, snapshot: AlbumSnapshot, validate: () -> Unit = {}) {
        ensureOwner(owner, validate)
        val value = copyAlbumSnapshot(snapshot)
        val weight = itemWeight(SourceItem.AlbumItem(value.album)) + (value.ids?.sumOf { refWeight(it) + 8 } ?: 0)
        ensureOwner(owner, validate)
        put(Key(owner, Kind.ALBUM_SNAPSHOT, value.album.ref.albumId), value, weight)
    }

    @Synchronized fun likedPlaylist(owner: Owner, validate: () -> Unit = {}): String? {
        ensureOwner(owner, validate)
        return get(Key(owner, Kind.LIKED_PLAYLIST)) as? String
    }

    @Synchronized fun putLikedPlaylist(owner: Owner, id: String, validate: () -> Unit = {}) {
        ensureOwner(owner, validate)
        put(Key(owner, Kind.LIKED_PLAYLIST), id, textWeight(id))
    }

    @Synchronized fun page(owner: Owner, key: PageKey, validate: () -> Unit = {}): SourcePage<SourceItem>? {
        ensureOwner(owner, validate)
        @Suppress("UNCHECKED_CAST")
        return (get(Key(owner, Kind.PAGE, key)) as? SourcePage<SourceItem>)?.let(::copyPage)
    }

    @Synchronized fun putPage(owner: Owner, key: PageKey, value: SourcePage<SourceItem>, validate: () -> Unit = {}) {
        ensureOwner(owner, validate)
        val copied = copyPage(value)
        val weight = 128L + textWeight(key.operation) + textWeight(key.identity) + textWeight(key.page.cursor) +
            textWeight(copied.nextCursor) + copied.items.sumOf(::itemWeight)
        ensureOwner(owner, validate)
        put(Key(owner, Kind.PAGE, key), copied, weight, nowMs() + PAGE_TTL_MS)
    }

    @Synchronized fun account(owner: Owner, validate: () -> Unit = {}): Pair<Boolean, SourceAccount?> {
        ensureOwner(owner, validate)
        return verifiedAccounts.containsKey(owner) to verifiedAccounts[owner]
    }

    @Synchronized fun putAccount(owner: Owner, value: SourceAccount?, validate: () -> Unit = {}) {
        ensureOwner(owner, validate)
        // Verification lives with the owner even when metadata pressure evicts catalog entries.
        verifiedAccounts[owner] = value
    }

    @Synchronized fun generation(owner: Owner): Long {
        ensureOwner(owner)
        return owner.generation
    }

    @Synchronized fun invalidateCatalog(owner: Owner, validate: () -> Unit = {}): Long {
        ensureOwner(owner, validate)
        owner.generation++
        removeEntries { it.owner === owner && it.kind != Kind.TRACK }
        return owner.generation
    }

    @Synchronized fun beginMutation(owner: Owner, validate: () -> Unit = {}): Mutation {
        val generation = invalidateCatalog(owner, validate)
        return Mutation(generation) {
            synchronized(this) {
                if (owners[owner.instance] === owner) invalidateCatalog(owner) else null
            }
        }
    }

    private fun get(key: Key): Any? {
        val entry = entries[key] ?: return null
        if (entry.expiresAtMs?.let { it <= nowMs() } == true) {
            entries.remove(key)
            bytes -= entry.weight
            return null
        }
        return entry.value
    }

    private fun put(key: Key, value: Any, valueWeight: Long, expiresAtMs: Long? = null) {
        entries.remove(key)?.let { bytes -= it.weight }
        val weight = 96L + textWeight(key.owner.instance) + textWeight(key.owner.version) +
            textWeight(key.owner.account) + valueWeight
        // Oversized data remains usable by the caller without displacing every reusable entry.
        if (weight > MAX_BYTES) return
        entries[key] = Entry(value, weight, expiresAtMs)
        bytes += weight
        while (bytes > MAX_BYTES || entries.size > MAX_ENTRIES) {
            val iterator = entries.entries.iterator()
            bytes -= iterator.next().value.weight
            iterator.remove()
        }
    }

    private fun removeEntries(predicate: (Key) -> Boolean) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.key)) {
                bytes -= entry.value.weight
                iterator.remove()
            }
        }
    }

    private fun copyTrack(track: Track) = track.copy(artists = track.artists.toList())
    private fun copyPlaylist(playlist: SourcePlaylist) = playlist.copy(editableActions = playlist.editableActions.toSet())
    private fun copySnapshot(snapshot: Snapshot) = snapshot.copy(playlist = copyPlaylist(snapshot.playlist), ids = snapshot.ids?.toList())
    private fun copyAlbumSnapshot(snapshot: AlbumSnapshot) = snapshot.copy(
        album = snapshot.album.copy(artists = snapshot.album.artists.toList()), ids = snapshot.ids?.toList())
    private fun copyPage(page: SourcePage<SourceItem>) = page.copy(items = page.items.map { item -> when (item) {
        is SourceItem.TrackItem -> SourceItem.TrackItem(copyTrack(item.track))
        is SourceItem.PlaylistItem -> SourceItem.PlaylistItem(copyPlaylist(item.playlist))
        is SourceItem.AlbumItem -> SourceItem.AlbumItem(item.album.copy(artists = item.album.artists.toList()))
        is SourceItem.ArtistItem -> SourceItem.ArtistItem(item.artist.copy(alternateNames = item.artist.alternateNames.toList(),
            identities = item.artist.identities.toList(), expertise = item.artist.expertise.toList(),
            translatedNames = item.artist.translatedNames.toList(), aliases = item.artist.aliases.toList()))
    } })

    private fun textWeight(value: String?): Long = if (value == null) 0 else 40L + value.length * 2L
    private fun refWeight(ref: ContentRef): Long = 48L + textWeight(ref.providerId) + textWeight(ref.contentId)
    private fun artistWeight(artist: SourceArtist): Long = 48L + textWeight(artist.name) + (artist.ref?.let(::refWeight) ?: 0)
    private fun trackWeight(track: Track): Long = 192L + refWeight(track.ref) + textWeight(track.title) +
        textWeight(track.artworkUrl) + textWeight(track.isrc) + textWeight(track.version) + textWeight(track.album.title) +
        (track.album.ref?.let(::refWeight) ?: 0) + track.artists.sumOf(::artistWeight)
    private fun playlistWeight(playlist: SourcePlaylist): Long = 160L + refWeight(playlist.ref) + textWeight(playlist.title) +
        textWeight(playlist.artworkUrl) + textWeight(playlist.creatorName) + textWeight(playlist.description) +
        textWeight(playlist.subtitle) + playlist.editableActions.size * 16L
    private fun itemWeight(item: SourceItem): Long = 32L + when (item) {
        is SourceItem.TrackItem -> trackWeight(item.track)
        is SourceItem.PlaylistItem -> playlistWeight(item.playlist)
        is SourceItem.AlbumItem -> 128L + refWeight(item.ref) + textWeight(item.title) + textWeight(item.artworkUrl) +
            textWeight(item.album.description) + item.album.artists.sumOf(::artistWeight)
        is SourceItem.ArtistItem -> 128L + refWeight(item.ref) + textWeight(item.title) + textWeight(item.artworkUrl) +
            textWeight(item.artist.description) + listOf(item.artist.alternateNames, item.artist.identities,
                item.artist.translatedNames, item.artist.aliases).sumOf { list -> list.sumOf(::textWeight) } +
            item.artist.expertise.sumOf { 32L + textWeight(it.name) }
    }

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        const val MAX_ENTRIES = 8192
        const val PAGE_TTL_MS = 60_000L
    }
}
