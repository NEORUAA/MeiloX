package com.ljyh.mei.playback

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.PlatformLyricsSessionAccess
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.MediaMetadata as SongMetadata
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemLyricsBridgeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun serializesLineKaraokeTranslationAndNoLyricsWithinBounds() {
        val metadata = SongMetadata(
            id = 48,
            title = "Example song",
            coverUrl = "",
            artists = listOf(SongMetadata.Artist(id = 1, name = "Example artist")),
            duration = 12_000,
            album = SongMetadata.Album(id = 1, title = "Example album"),
        )
        val lines = LyricData(
            source = LyricSource.NetEaseCloudMusic,
            lyricLine = SyncedLyrics(lines = listOf(
                SyncedLine("First\nline", "First translation", 1_000, 2_000),
                SyncedLine("Second line", null, 2_000, 3_000),
            )),
        )
        val linePayload = JSONObject(requireNotNull(metadata.toSystemLyricSong(lines)
            .toColorOsLyricInfo(context.packageName, 7)))
        assertEquals("[00:01.000]First line\n[00:02.000]Second line\n", linePayload.getString("lyric"))
        assertEquals("[00:01.000]First translation\n", linePayload.getString("translationLyric"))
        assertFalse(linePayload.has("rawLyric"))
        assertFalse(linePayload.getBoolean("noLyric"))
        assertEquals("48", linePayload.getString("songId"))
        assertEquals(7L, linePayload.getLong("sessionGeneration"))

        val karaoke = LyricData(
            isVerbatim = true,
            source = LyricSource.NetEaseCloudMusic,
            lyricLine = SyncedLyrics(lines = listOf(
                KaraokeLine.MainKaraokeLine(
                    syllables = listOf(KaraokeSyllable("Hello ", 1_000, 1_400), KaraokeSyllable("world", 1_400, 2_000)),
                    translation = "Translation",
                    phonetic = "Pronunciation",
                    alignment = KaraokeAlignment.Start,
                    start = 1_000,
                    end = 2_000,
                ),
            )),
        )
        val karaokeSong = metadata.toSystemLyricSong(karaoke)
        val karaokePayload = JSONObject(requireNotNull(karaokeSong.toColorOsLyricInfo(context.packageName, 8)))
        assertEquals("[00:01.000]Hello world\n", karaokePayload.getString("lyric"))
        assertEquals("[00:01.000]<00:01.000>Hello <00:01.400>world<00:02.000>\n", karaokePayload.getString("rawLyric"))
        assertEquals("[00:01.000]Translation\n", karaokePayload.getString("translationLyric"))
        val pronunciationOnly = metadata.toSystemLyricSong(karaoke.copy(lyricLine = SyncedLyrics(lines = listOf(
            (karaoke.lyricLine!!.lines.single() as KaraokeLine.MainKaraokeLine).copy(translation = null),
        ))))
        assertFalse(JSONObject(requireNotNull(pronunciationOnly.toColorOsLyricInfo(context.packageName, 8)))
            .has("translationLyric"))

        listOf(null, lines.copy(source = LyricSource.Loading), lines.copy(isPureMusic = true)).forEach { data ->
            val empty = JSONObject(requireNotNull(metadata.toSystemLyricSong(data)
                .toColorOsLyricInfo(context.packageName, 9)))
            assertTrue(empty.getBoolean("noLyric"))
            assertEquals("", empty.getString("lyric"))
            assertFalse(empty.has("translationLyric"))
        }
        assertNull(metadata.toSystemLyricSong(lines.copy(lyricLine = SyncedLyrics(lines = listOf(
            SyncedLine("x".repeat(128 * 1024), null, 0, 1_000),
        )))).toColorOsLyricInfo(context.packageName, 10))
    }

    @Test
    fun externalLyricsPreserveSharingArtworkAndPlaybackAndRestoreTheLatestTitle() = withSession { fixture ->
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val state = state(PlaybackState.STATE_PAUSED, 2_500)
        onMain {
            fixture.platform.setMetadata(metadata("song-1")
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap).build())
            fixture.platform.setPlaybackState(state)
            fixture.publisher.setLyrics("song-1", TRANSLATED_PAYLOAD, hasTranslation = true)
            fixture.publisher.setExternalLyrics("song-1", "Current lyric")
        }
        await("External lyrics, shared lyrics and legacy artwork must coexist") {
            fixture.metadata.getString(MediaMetadata.METADATA_KEY_TITLE) == "Current lyric" &&
                fixture.metadata.getString("lyricInfo") == TRANSLATED_PAYLOAD &&
                fixture.metadata.getBitmap(MediaMetadata.METADATA_KEY_ART) != null && fixture.hasTranslationAction()
        }
        assertTrue(bitmap.sameAs(fixture.metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)))
        assertStateFields(state, fixture.playbackState)
        onMain { fixture.publisher.setExternalLyrics(null, null) }
        await("Disabling external lyrics must leave lyric sharing enabled") {
            fixture.metadata.getString(MediaMetadata.METADATA_KEY_TITLE) == "Example song" &&
                fixture.metadata.getString("lyricInfo") == TRANSLATED_PAYLOAD && fixture.hasTranslationAction()
        }
        onMain { fixture.publisher.setExternalLyrics("song-1", "Current lyric") }
        onMain {
            fixture.platform.setMetadata(metadata("song-1")
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Updated song title")
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap).build())
        }
        await("Media3 metadata refresh must retain both lyric outputs") {
            fixture.metadata.getString(MediaMetadata.METADATA_KEY_TITLE) == "Current lyric" &&
                fixture.metadata.getString("lyricInfo") == TRANSLATED_PAYLOAD
        }
        onMain { fixture.publisher.setLyrics(null, null) }
        await("Disabling sharing must leave external lyrics and artwork intact") {
            fixture.metadata.getString("lyricInfo") == null &&
                fixture.metadata.getString(MediaMetadata.METADATA_KEY_TITLE) == "Current lyric" && !fixture.hasTranslationAction()
        }
        onMain { fixture.publisher.setExternalLyrics(null, null) }
        await("Disabling external lyrics must restore the latest host title") {
            fixture.metadata.getString(MediaMetadata.METADATA_KEY_TITLE) == "Updated song title"
        }
        assertTrue(bitmap.sameAs(fixture.metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)))
        assertStateFields(state, fixture.playbackState)
        onMain {
            fixture.publisher.setExternalLyrics("song-1", "Old song lyric")
            fixture.platform.setMetadata(metadata("song-2").build())
        }
        await("A previous song's lyric must not overwrite the new song") {
            fixture.metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == "song-2" &&
                fixture.metadata.getString(MediaMetadata.METADATA_KEY_TITLE) == "Example song"
        }
        assertNull(fixture.metadata.getBitmap(MediaMetadata.METADATA_KEY_ART))
    }

    @Test
    fun legacyTransportPermissionRetainsReadAccessWithoutGrantingQueueEditing() {
        val defaults = Player.Commands.Builder().addAllReadOnlyCommands().build()
        val commands = externalTransportCommands(defaults)
        for (index in 0 until defaults.size()) assertTrue(commands.contains(defaults[index]))
        listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SET_MEDIA_ITEM).forEach { assertTrue(commands.contains(it)) }
        assertFalse(commands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertEquals(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            externalTransportCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS))
    }

    @Test
    fun preservesTypedMetadataArtworkAndPlaybackFieldsAcrossUpdates() = withSession { fixture ->
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val rating = Rating.newHeartRating(true)
        val original = metadata("song-1")
            .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, 4)
            .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, 11)
            .putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
            .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)
            .putString(MediaMetadata.METADATA_KEY_ART_URI, "content://example/art")
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, "content://example/album")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, "content://example/icon")
            .putRating(MediaMetadata.METADATA_KEY_USER_RATING, rating)
            .putText("host.text", SpannableString("Host text"))
            .putLong("host.number", 42)
            .putBitmap("host.bitmap", bitmap)
            .putRating("host.rating", rating)
            .build()
        val state = state(PlaybackState.STATE_PAUSED, 2_500)
        onMain {
            fixture.platform.setMetadata(original)
            fixture.platform.setPlaybackState(state)
            fixture.publisher.setLyrics("song-1", TRANSLATED_PAYLOAD, hasTranslation = true)
        }
        await("Typed metadata and translation action were not published") {
            fixture.metadata?.getString("lyricInfo") == TRANSLATED_PAYLOAD && fixture.hasTranslationAction()
        }
        val actual = requireNotNull(fixture.metadata)
        original.keySet().forEach { assertTrue("Missing metadata key $it", actual.containsKey(it)) }
        assertEquals(4L, actual.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER))
        assertEquals(11L, actual.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS))
        assertEquals("Host text", actual.getText("host.text").toString())
        assertEquals(42L, actual.getLong("host.number"))
        listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON, "host.bitmap").forEach {
            assertTrue("Artwork changed at $it", bitmap.sameAs(actual.getBitmap(it)))
        }
        listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).forEach {
            assertEquals(original.getString(it), actual.getString(it))
        }
        assertTrue(requireNotNull(actual.getRating(MediaMetadata.METADATA_KEY_USER_RATING)).hasHeart())
        assertTrue(requireNotNull(actual.getRating("host.rating")).hasHeart())
        assertStateFields(state, requireNotNull(fixture.playbackState))
        assertEquals(listOf(HOST_ACTION, COLOR_OS_TRANSLATION_ACTION), fixture.playbackState.customActions.map { it.action })
        assertTrue(fixture.playbackState.customActions.last().icon != 0)
        assertNotNull(context.resources.getDrawable(fixture.playbackState.customActions.last().icon, context.theme))

        // These updates originate from the real Media3 player and replace platform objects.
        onMain { fixture.player.play() }
        await("Media3 play update lost the translation action") {
            fixture.playbackState?.state == PlaybackState.STATE_PLAYING && fixture.hasTranslationAction()
        }
        onMain { fixture.player.pause() }
        await("Media3 pause update lost the translation action") {
            fixture.playbackState?.state == PlaybackState.STATE_PAUSED && fixture.hasTranslationAction()
        }
        onMain { fixture.player.seekTo(4_000) }
        await("Media3 seek update lost the position or translation action") {
            fixture.playbackState?.position == 4_000L && fixture.hasTranslationAction()
        }
        onMain {
            fixture.player.replaceMediaItem(0, item("song-1", fixture.file).buildUpon()
                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle("Refreshed title").build()).build())
        }
        await("Media3 metadata refresh lost the lyric overlay") {
            fixture.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == "Refreshed title" &&
                fixture.metadata?.getString("lyricInfo") == TRANSLATED_PAYLOAD && fixture.hasTranslationAction()
        }
        onMain { fixture.publisher.setLyrics("song-1", "line lyrics", hasTranslation = false) }
        await("A song without translation retained the public action") { !fixture.hasTranslationAction() }
        assertEquals("line lyrics", fixture.metadata.getString("lyricInfo"))
    }

    @Test
    fun removesOnlyOwnedAdditionsAndRejectsStaleSongsAndReleasedUpdates() {
        withSession(translationActionEnabled = false) { fixture ->
            val state = state(PlaybackState.STATE_PAUSED, 1_000)
            onMain {
                fixture.platform.setMetadata(metadata("song-1").build())
                fixture.platform.setPlaybackState(state)
                fixture.publisher.setLyrics("song-1", TRANSLATED_PAYLOAD, hasTranslation = true)
            }
            await("Lyrics must remain available without the OPlus action capability") {
                fixture.metadata.getString("lyricInfo") == TRANSLATED_PAYLOAD
            }
            assertFalse(fixture.hasTranslationAction())
            assertStateFields(state, fixture.playbackState)
            assertEquals(listOf(HOST_ACTION), fixture.playbackState.customActions.map { it.action })
            onMain { fixture.publisher.setLyrics(null, null) }
            await("Disabling lyrics must still clear metadata without the action capability") {
                !fixture.metadata.containsKey("lyricInfo")
            }
            assertFalse(fixture.hasTranslationAction())
        }
        withSession { fixture ->
            onMain {
                fixture.platform.setMetadata(metadata("song-1").build())
                fixture.platform.setPlaybackState(state(PlaybackState.STATE_PAUSED, 1_000))
                fixture.publisher.setLyrics("song-1", TRANSLATED_PAYLOAD, hasTranslation = true)
            }
            await("Initial lyric overlay is missing") { fixture.hasTranslationAction() }
            val beforeClear = requireNotNull(fixture.playbackState)
            onMain { fixture.publisher.setLyrics(null, null) }
            await("Disabling lyrics did not remove owned additions") {
                !fixture.metadata.containsKey("lyricInfo") && !fixture.hasTranslationAction()
            }
            assertStateFields(beforeClear, requireNotNull(fixture.playbackState))
            assertEquals(listOf(HOST_ACTION), fixture.playbackState.customActions.map { it.action })

            onMain {
                fixture.publisher.setLyrics("song-1", TRANSLATED_PAYLOAD, hasTranslation = true)
                fixture.platform.setMetadata(metadata("song-2").putString("lyricInfo", "foreign lyrics").build())
            }
            await("Stale song retained its translation action") { !fixture.hasTranslationAction() }
            onMain { fixture.publisher.setLyrics("song-1", "stale lyrics", hasTranslation = true) }
            assertEquals("foreign lyrics", fixture.metadata.getString("lyricInfo"))
            assertFalse(fixture.hasTranslationAction())

            val foreignAction = PlaybackState.CustomAction.Builder(COLOR_OS_TRANSLATION_ACTION, "Host translation", android.R.drawable.ic_menu_info_details).build()
            onMain {
                fixture.platform.setPlaybackState(PlaybackState.Builder(state(PlaybackState.STATE_PAUSED, 5_000)).addCustomAction(foreignAction).build())
                fixture.publisher.release()
                fixture.publisher.setLyrics("song-2", "late lyrics", hasTranslation = true)
            }
            instrumentation.waitForIdleSync()
            assertEquals("foreign lyrics", fixture.metadata.getString("lyricInfo"))
            assertEquals(listOf(HOST_ACTION, COLOR_OS_TRANSLATION_ACTION), fixture.playbackState.customActions.map { it.action })
            onMain {
                fixture.platform.setMetadata(metadata("song-1").build())
                fixture.platform.setPlaybackState(state(PlaybackState.STATE_PAUSED, 6_000))
            }
            instrumentation.waitForIdleSync()
            assertNull(fixture.metadata.getString("lyricInfo"))
            assertFalse(fixture.hasTranslationAction())
        }
    }

    @Test
    fun oversizedMetadataFailsOpenAndCanStillRemoveItsPreviousLyrics() = withSession { fixture ->
        val oversized = metadata("song-1").putString("host.large", "x".repeat(260 * 1024)).build()
        onMain {
            fixture.platform.setMetadata(oversized)
            fixture.platform.setPlaybackState(state(PlaybackState.STATE_PAUSED, 1_000))
            fixture.publisher.setLyrics("song-1", TRANSLATED_PAYLOAD, hasTranslation = true)
        }
        instrumentation.waitForIdleSync()
        assertEquals(260 * 1024, requireNotNull(fixture.metadata.getString("host.large")).length)
        assertNull(fixture.metadata.getString("lyricInfo"))
        assertFalse(fixture.hasTranslationAction())
        onMain {
            fixture.platform.setMetadata(metadata("song-1").build())
            fixture.publisher.setLyrics("song-1", TRANSLATED_PAYLOAD, hasTranslation = true)
        }
        await("Small metadata did not accept the lyric overlay") { fixture.hasTranslationAction() }
        onMain {
            fixture.platform.setMetadata(MediaMetadata.Builder(oversized).putString("lyricInfo", TRANSLATED_PAYLOAD).build())
            fixture.publisher.setLyrics(null, null)
        }
        await("Oversized metadata prevented owned lyric removal") {
            fixture.metadata.getString("lyricInfo") == null && !fixture.hasTranslationAction()
        }
        assertEquals(260 * 1024, requireNotNull(fixture.metadata.getString("host.large")).length)
    }

    private fun assertStateFields(expected: PlaybackState, actual: PlaybackState) {
        assertEquals(expected.state, actual.state)
        assertEquals(expected.position, actual.position)
        assertEquals(expected.playbackSpeed, actual.playbackSpeed, 0f)
        assertEquals(expected.lastPositionUpdateTime, actual.lastPositionUpdateTime)
        assertEquals(expected.bufferedPosition, actual.bufferedPosition)
        assertEquals(expected.actions, actual.actions)
        assertEquals(expected.activeQueueItemId, actual.activeQueueItemId)
        assertEquals(expected.errorMessage, actual.errorMessage)
        assertEquals(expected.extras?.getString("host.extra"), actual.extras?.getString("host.extra"))
        expected.customActions.filter { it.action == HOST_ACTION }.forEach { action ->
            val copied = actual.customActions.single { it.action == HOST_ACTION }
            assertEquals(action.name, copied.name)
            assertEquals(action.icon, copied.icon)
            assertEquals(action.extras?.getString("host.action.extra"), copied.extras?.getString("host.action.extra"))
        }
    }

    private fun metadata(mediaId: String) = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, mediaId)
        .putString(MediaMetadata.METADATA_KEY_TITLE, "Example song")
        .putString(MediaMetadata.METADATA_KEY_ARTIST, "Example artist")
        .putString(MediaMetadata.METADATA_KEY_ALBUM, "Example album")
        .putLong(MediaMetadata.METADATA_KEY_DURATION, 10_000)

    private fun state(value: Int, position: Long) = PlaybackState.Builder()
        .setState(value, position, 1.25f, SystemClock.elapsedRealtime() - 123)
        .setBufferedPosition(9_000)
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SEEK_TO)
        .setActiveQueueItemId(17)
        .setErrorMessage("Host error message")
        .setExtras(Bundle().apply { putString("host.extra", "value") })
        .addCustomAction(PlaybackState.CustomAction.Builder(HOST_ACTION, "Host action", android.R.drawable.ic_menu_info_details)
            .setExtras(Bundle().apply { putString("host.action.extra", "action value") }).build())
        .build()

    private fun item(mediaId: String, file: File) = MediaItem.Builder().setMediaId(mediaId).setUri(file.toURI().toString()).build()

    private fun withSession(translationActionEnabled: Boolean = true, block: (Fixture) -> Unit) {
        val pcmBytes = 8_000 * 10 * 2
        val wav = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcmBytes)
        val file = File.createTempFile("system-lyrics-", ".wav", context.cacheDir).apply { writeBytes(wav.array()) }
        val fixture = onMain {
            val player = ExoPlayer.Builder(context).build().apply { volume = 0f }
            val session = MediaSession.Builder(context, player).setId("system-lyrics-${SystemClock.elapsedRealtimeNanos()}").build()
            Fixture(player, session, PlatformLyricsSessionAccess.get(session), ColorOsLyricsPublisher(context, session, translationActionEnabled = translationActionEnabled), file)
                .also { player.setMediaItem(item("song-1", file)); player.prepare() }
        }
        try {
            await("Local Media3 fixture did not become ready") { fixture.player.playbackState == Player.STATE_READY }
            block(fixture)
        } finally {
            onMain { fixture.publisher.release(); fixture.session.release(); fixture.player.release() }
            file.delete()
        }
    }

    private fun await(message: String, matches: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(matches)) return
            SystemClock.sleep(20)
        }
        throw AssertionError(message)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private data class Fixture(
        val player: ExoPlayer,
        val session: MediaSession,
        val platform: android.media.session.MediaSession,
        val publisher: ColorOsLyricsPublisher,
        val file: File,
    ) {
        val metadata: MediaMetadata get() = requireNotNull(platform.controller.metadata)
        val playbackState: PlaybackState get() = requireNotNull(platform.controller.playbackState)

        fun hasTranslationAction() = platform.controller.playbackState?.customActions.orEmpty()
            .any { it.action == COLOR_OS_TRANSLATION_ACTION }
    }

    private companion object {
        const val HOST_ACTION = "com.ljyh.mei.test.HOST_ACTION"
        const val TRANSLATED_PAYLOAD = "{\"lyric\":\"[00:01.000]Line\\n\",\"translationLyric\":\"[00:01.000]Translation\\n\"}"
    }
}
