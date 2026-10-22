package com.ljyh.mei.data.repository

import androidx.room.Room
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.di.NeteaseInterceptor
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.constants.SDeviceIdKey
import com.ljyh.mei.R
import com.ljyh.mei.data.model.PlaylistOrderChangedException
import com.ljyh.mei.data.network.neteaseSessionFingerprint
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.ui.glass.LocalBlurBackdrop
import com.ljyh.mei.ui.glass.LocalGlassBackdrop
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.defaultGlassColors
import com.ljyh.mei.ui.screen.playlist.PlaylistOrderItem
import com.ljyh.mei.ui.screen.playlist.PlaylistOrderSheet
import com.ljyh.mei.ui.screen.playlist.PlaylistOrderState
import com.kyant.backdrop.backdrops.emptyBackdrop
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Room merge in a separate in-memory database. */
@RunWith(AndroidJUnit4::class)
class PlaylistLibraryInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun largeOrderSheetScrollsToUnavailableTracksAndCancelDoesNotSave() {
        val visible = mutableStateOf(true)
        val state = PlaylistOrderState(
            playlistId = "fixture",
            items = (1L..2_000L).map { PlaylistOrderItem(it, null) },
            originalIds = (1L..2_000L).toList(), loading = false,
        )
        var saves = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(false),
                    LocalGlassBackdrop provides emptyBackdrop(),
                    LocalBlurBackdrop provides emptyBackdrop(),
                ) {
                    if (visible.value) PlaylistOrderSheet(state, { _, _ -> }, {}, { saves++ }, {}, { visible.value = false })
                }
            }
        }
        compose.onNodeWithTag("playlist-order-list").performScrollToIndex(1_999)
        compose.onNodeWithTag("playlist-order-2000").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.playlist_order_cancel)).performClick()
        compose.runOnIdle { assertFalse(visible.value); assertEquals(0, saves) }
    }

    @Test
    fun orderTransportPreservesIdsAndRejectsConcurrentAndSessionChanges() = runBlocking<Unit>(Dispatchers.IO) {
        val credential = "fixture-order-session"
        val outgoingSession = AtomicReference(credential)
        var serverIds = listOf(1L, 2L, 3L)
        var switchSessionAfterRead = false
        var writes = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(NeteaseInterceptor {
                mutablePreferencesOf(CookieKey to outgoingSession.get(), DeviceIdKey to "fixture-device", SDeviceIdKey to "fixture-device")
            })
            .addInterceptor { chain ->
                val request = chain.request()
                val json = if (request.url.encodedPath.endsWith("/playlist/manipulate/tracks")) {
                    writes++
                    val form = request.body as FormBody
                    val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
                    assertEquals("update", fields["op"])
                    assertEquals("42", fields["pid"])
                    assertEquals("[\"3\",\"1\",\"2\"]", fields["trackIds"])
                    assertFalse(request.headers.names().any { it.startsWith("X-Netease-", true) })
                    """{"code":200}"""
                } else {
                    assertTrue(request.url.encodedPath.endsWith("/playlist/detail"))
                    if (switchSessionAfterRead) outgoingSession.set("different-fixture-session")
                    """{"code":200,"playlist":{"id":42,"creator":{"userId":7},"trackIds":[${serverIds.joinToString(",") { "{\"id\":$it}" }}],"tracks":[]}}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(json.toResponseBody("application/json".toMediaType())).build()
            }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://interface.music.163.com/")
            .addConverterFactory(GsonConverterFactory.create()).client(client).build()
        val repository = PlaylistRepository(
            retrofit.create(ApiService::class.java), retrofit.create(WeApiService::class.java),
            retrofit.create(EApiService::class.java), freshCheckToken = { "unused" }, subscriptionAccount = { credential },
        )
        suspend fun save() = repository.updatePlaylistTrackOrder(
            "42", listOf(1, 2, 3), listOf(3, 1, 2), 7, neteaseSessionFingerprint(credential), { true },
        )
        assertEquals(200, save().code)
        assertEquals(1, writes)
        serverIds = serverIds + 4L
        assertTrue(runCatching { save() }.exceptionOrNull() is PlaylistOrderChangedException)
        assertEquals(1, writes)
        serverIds = listOf(1, 2, 3)
        switchSessionAfterRead = true
        assertNotNull(runCatching { save() }.exceptionOrNull())
        assertEquals(1, writes)
    }

    @Test
    fun remoteRefreshPreservesLocalUsageIncludingPlaysAfterTheRequestStarted() = runBlocking<Unit>(Dispatchers.IO) {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
        ).build()
        try {
            val dao = database.playlistDao()
            val original = Playlist(
                "playlist", "Old title", "old-cover", "owner", "Owner", "avatar", 10,
                description = "Local description", createdAt = 100, updatedAt = 200,
                playCount = 300, lastPlayTime = 400, localPlayCount = 5,
            )
            dao.insertPlaylist(original)
            val remote = original.copy(title = "New title", count = 11, playCount = 600,
                lastPlayTime = 0, localPlayCount = 0, createdAt = 900, updatedAt = 900, description = null)
            dao.touchPlaylist(original.id, 800)
            dao.mergeRemotePlaylists(listOf(remote, remote.copy(id = "new", author = "other")))
            val merged = checkNotNull(dao.getPlaylist(original.id))
            assertEquals("New title", merged.title)
            assertEquals(11, merged.count)
            assertEquals(600L, merged.playCount)
            assertEquals(6, merged.localPlayCount)
            assertEquals(800L, merged.lastPlayTime)
            assertEquals(original.createdAt, merged.createdAt)
            assertEquals(original.updatedAt, merged.updatedAt)
            assertEquals(original.description, merged.description)
            assertNotNull(dao.getPlaylist("new"))
        } finally {
            database.close()
        }
    }
}
