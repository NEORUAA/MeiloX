package com.ljyh.mei.data.repository

import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.AppContext
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.NeteaseLoginSecurity
import com.ljyh.mei.data.network.NeteaseSessionType
import com.ljyh.mei.data.network.NeteaseUrsSmsLogin
import com.ljyh.mei.data.network.neteaseSessionType
import com.ljyh.mei.data.network.setNeteaseWebSession
import com.ljyh.mei.data.model.melox.AccountConnectionType
import androidx.datastore.preferences.core.edit
import com.ljyh.mei.ui.screen.cloud.CloudMusicViewModel
import com.ljyh.mei.di.RetrofitModule
import com.ljyh.mei.utils.dataStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read through the installed app's existing session without publishing account changes. */
@RunWith(AndroidJUnit4::class)
class CatalogReadsInstrumentedTest {
    private val client = RetrofitModule.provideOkHttpClient()
    private val retrofit = RetrofitModule.provideRetrofit(client)
    private val webRetrofit = RetrofitModule.provideWeApiRetrofit(client)
    private val api = RetrofitModule.provideApiService(retrofit)
    private val eapi = RetrofitModule.provideMeloXEapiService(retrofit)
    private val weapi = RetrofitModule.provideMeloXWeapiService(webRetrofit)

    @Test
    fun accountConnectionsUseTheCurrentSessionAndAdvanceBothLists() = runBlocking<Unit> {
        val context = AppContext.instance
        val before = context.dataStore.data.first()
        assumeTrue("A signed-in account is required", before.neteaseSessionType() != NeteaseSessionType.None)
        val readOnly = readOnlyCatalogClient()
        try {
            val repository = MeloXRepository(
                RetrofitModule.provideMeloXEapiService(RetrofitModule.provideRetrofit(readOnly)),
                RetrofitModule.provideMeloXWeapiService(RetrofitModule.provideWeApiRetrofit(readOnly)),
                context, readOnly, NeteaseLoginSecurity(context, readOnly), NeteaseUrsSmsLogin(context, readOnly),
            )
            val account = repository.accountProfile()
            val detail = repository.accountDetail(account.id)
            for (type in AccountConnectionType.entries) {
                val count = if (type == AccountConnectionType.Following) detail.profile.follows else detail.profile.followers
                val first = repository.accountConnections(account.id, type, limit = 1)
                if ((count ?: 0) > 0) assertTrue("Expected an existing $type user", first.users.isNotEmpty())
                assertEquals(first.users.size, first.users.distinctBy { it.id }.size)
                if (first.hasMore) {
                    val next = repository.accountConnections(account.id, type, offset = first.nextOffset, limit = 1)
                    assertTrue("The $type cursor must advance", next.nextOffset > first.nextOffset || !next.hasMore)
                    assertTrue("The $type page must not repeat the first user", next.users.none { user -> first.users.any { it.id == user.id } })
                }
                val full = repository.accountConnections(account.id, type)
                for (user in full.users) {
                    val expected = repository.accountDetail(user.id).profile
                    assertEquals("The user profile must match the requested account", user.id, expected.id)
                    assertEquals("A bound artist must remain clickable in the list", expected.artistId, user.artistId)
                }
                val artists = full.users.count { it.artistId != null }
                Log.i("CatalogReads", "$type rows=${full.users.size} nextOffset=${first.nextOffset} more=${first.hasMore} artists=$artists")
            }
        } finally {
            assertTrue("The existing session must remain unchanged", before[CookieKey] == context.dataStore.data.first()[CookieKey])
            readOnly.connectionPool.evictAll()
            readOnly.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun commentPagesAndRepliesDecodeThroughTheCurrentTransport() = runBlocking {
        val repository = CommentRepository(
            api, RetrofitModule.provideWeApiService(webRetrofit), eapi, weapi,
        )
        val result = repository.getComment("28639182")
        assertTrue("Comment request failed: $result", result is Resource.Success)
        val data = checkNotNull((result as Resource.Success).data.data)
        val comments = checkNotNull(data.comments)
        assertNotNull(data.hasMore)
        val parent = comments.firstOrNull { (it.showFloorComment?.replyCount ?: 0) > 0 }
        assertNotNull("Expected a public comment with replies", parent)
        val replies = repository.getFloorComment(checkNotNull(parent).commentId, "28639182")
        assertTrue("Reply request failed: $replies", replies is Resource.Success)
        assertNotNull((replies as Resource.Success).data.data?.comments)
    }

    @Test
    fun allPlaylistChoicesUseTheExistingAccountWithoutReplacingItsSession() = runBlocking {
        val context = AppContext.instance
        val before = context.dataStore.data.first()[CookieKey]
        val repository = LibraryCollectionsRepository(api, weapi, eapi, context)
        val account = checkNotNull(repository.accounts.first()) { "An existing account is required" }
        val playlists = repository.allPlaylists(account)
        assertEquals(playlists.size, playlists.distinctBy { it.id }.size)
        assertTrue("The existing account should have a playlist", playlists.isNotEmpty())
        assertEquals(account, repository.accounts.first())
        assertTrue("The account credential must remain unchanged", before == context.dataStore.data.first()[CookieKey])
    }

    @Test
    fun podcastPagesUseRawOffsetsThroughTheCurrentTransport() = runBlocking {
        val context = AppContext.instance
        val before = context.dataStore.data.first()[CookieKey]
        val recommendations = weapi.post("/weapi/djradio/recommend/v1")
        assertEquals(200, recommendations.get("code").asInt)
        val radio = checkNotNull(recommendations.getAsJsonArray("djRadios"))
            .map { it.asJsonObject }
            .firstOrNull { (it.get("programCount")?.asInt ?: 0) > 1 }
        assertNotNull("Expected a public podcast with more than one episode", radio)
        val radioId = checkNotNull(radio).get("id").asLong
        suspend fun page(offset: Int) = weapi.post(
            "/weapi/dj/program/byradio",
            mapOf("radioId" to radioId, "offset" to offset, "limit" to 1, "asc" to false),
        ).let { response ->
            assertEquals(200, response.get("code").asInt)
            parsePodcastProgramPage(response, offset)
        }

        val first = page(0)
        assertTrue(first.hasMore)
        assertEquals(1, first.fetchedCount)
        assertEquals(1, first.programs.size)
        val second = page(first.fetchedCount)
        assertEquals(1, second.fetchedCount)
        assertEquals(1, second.programs.size)
        assertTrue(first.programs.single().id != second.programs.single().id)
        assertTrue("The account credential must remain unchanged", before == context.dataStore.data.first()[CookieKey])
    }

    @Test
    fun cloudPagesContinueBeyondTwoHundredSongsWithoutAccountWrites() = runBlocking<Unit> {
        val context = AppContext.instance
        val before = context.dataStore.data.first()
        val argument = InstrumentationRegistry.getArguments().getString("cloudReadOnlyCredentialFile")
        val credentialFile = argument?.takeIf(String::isNotBlank)?.let { name ->
            val directory = context.filesDir.canonicalFile
            val candidate = File(name).let { if (it.isAbsolute) it else File(directory, name) }.canonicalFile
            check(candidate.path.startsWith(directory.path + File.separator)) {
                "Cloud read-only credential must be a private app file"
            }
            candidate
        }
        val readOnly = readOnlyCatalogClient()
        var viewModel: CloudMusicViewModel? = null
        try {
            if (credentialFile != null) {
                assertEquals("Read-only account checks require no active downloads", 0,
                    com.ljyh.mei.di.AppDatabase.getDatabase(context).downloadDao().activeCount().first())
                val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                @Suppress("DEPRECATION")
                check(activityManager.getRunningServices(Int.MAX_VALUE).none {
                    it.service.packageName == context.packageName && it.service.className == "com.ljyh.mei.playback.MusicService"
                }) { "Read-only account checks require the player service to be stopped" }
                check(credentialFile.isFile && credentialFile.length() in 1..65_536) {
                    "Cloud read-only credential file is unavailable"
                }
                val input = credentialFile.readText().trim()
                val credential = if ("MUSIC_U=" in input) {
                    input.split(';', '\n', '\r').firstOrNull { it.trim().startsWith("MUSIC_U=") }
                        ?.trim()?.substringAfter("MUSIC_U=").orEmpty()
                } else input
                check(credential.isNotBlank() && credential.none { it.isWhitespace() || it == ';' }) {
                    "Cloud read-only credential format is invalid"
                }
                context.dataStore.edit { it.setNeteaseWebSession(credential) }
            } else {
                assumeTrue("A signed-in account is required", before.neteaseSessionType() != NeteaseSessionType.None)
            }
            val sessionType = context.dataStore.data.first().neteaseSessionType()
            val cloudEapi = RetrofitModule.provideMeloXEapiService(RetrofitModule.provideRetrofit(readOnly))
            val cloudWeapi = RetrofitModule.provideMeloXWeapiService(RetrofitModule.provideWeApiRetrofit(readOnly))
            val repository = MeloXRepository(
                cloudEapi, cloudWeapi, context, readOnly,
                NeteaseLoginSecurity(context, readOnly), NeteaseUrsSmsLogin(context, readOnly),
            )
            val offsets = mutableListOf<Int>()
            val model = withContext(Dispatchers.Main) {
                CloudMusicViewModel(
                    loadPage = { offset ->
                        offsets += offset
                        repository.cloudSongs(offset = offset)
                    },
                    deleteSong = { error("Cloud deletion is forbidden in this read-only regression") },
                    uploadSong = { _, _ -> error("Cloud upload is forbidden in this read-only regression") },
                ).also { viewModel = it }
            }
            val firstState = withTimeout(60_000) { model.state.first { !it.isLoading } }
            assertNull("Cloud first page failed", firstState.error)
            val first = checkNotNull(firstState.page)
            if (credentialFile == null) assumeTrue("The account must have more than 200 cloud songs", first.count > 200)
            else assertTrue("The read-only account must have more than 200 cloud songs", first.count > 200)
            assertTrue("The first request must stay bounded", first.songs.size in 1..200)
            assertTrue("The first raw cursor must stay bounded", first.nextOffset in 1..200)
            assertTrue("A continuation is required", first.hasMore)
            assertEquals(listOf(0), offsets)

            withContext(Dispatchers.Main) { model.loadMore() }
            val appendedState = withTimeout(60_000) { model.state.first { !it.isLoadingMore } }
            assertNull("Cloud continuation failed", appendedState.loadMoreError)
            val appended = checkNotNull(appendedState.page)
            assertEquals(listOf(0, first.nextOffset), offsets)
            assertTrue("Cloud continuation must append beyond the first 200 songs", appended.songs.size > 200)
            assertEquals(appended.songs.size, appended.songs.distinctBy { it.id }.size)
            assertTrue("The raw cloud cursor must advance", appended.nextOffset > first.nextOffset)
            assertTrue("Each continuation must stay bounded", appended.nextOffset - first.nextOffset <= 200)
            if (credentialFile != null) {
                assertEquals(NeteaseSessionType.Web, sessionType)
                val search = SearchRepository(RetrofitModule.provideApiService(RetrofitModule.provideRetrofit(readOnly)))
                    .search("HOYO", type = 1009, limit = 10)
                assertTrue("Web-session podcast search failed", search is Resource.Success)
                val radios = (search as Resource.Success).data.result.podcasts.orEmpty()
                assertTrue("Web-session radio results must match HOYO",
                    radios.take(5).any { it.name.contains("HOYO", ignoreCase = true) })
            }
            Log.i(
                "CatalogReads",
                "cloud pages=2 first=${first.songs.size} appended=${appended.songs.size} " +
                    "rawOffset=${appended.nextOffset} session=${sessionType.name}",
            )
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) {
                    viewModel?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                }
                try {
                    context.dataStore.updateData { before }
                    assertTrue(
                        "The original complete preferences must be restored",
                        before.asMap() == context.dataStore.data.first().asMap(),
                    )
                } finally {
                    credentialFile?.let { file ->
                        assertTrue("The private credential input must be removed", !file.exists() || file.delete())
                    }
                    readOnly.connectionPool.evictAll()
                    readOnly.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    @Test
    fun podcastSearchUsesRelevantDesktopResultsWithoutReplacingTheMobileSession() = runBlocking<Unit> {
        val context = AppContext.instance
        val before = context.dataStore.data.first()
        assumeTrue("An existing mobile session is required", before.neteaseSessionType() == NeteaseSessionType.Mobile)
        val readOnly = readOnlyCatalogClient()
        try {
            val repository = SearchRepository(
                RetrofitModule.provideApiService(RetrofitModule.provideRetrofit(readOnly)),
            )
            val result = repository.search("HOYO", type = 1009, limit = 10)
            assertTrue("Podcast search failed", result is Resource.Success)
            val podcasts = (result as Resource.Success).data.result.podcasts.orEmpty()
            val matches = podcasts.take(5).count { it.name.contains("HOYO", ignoreCase = true) }
            assertTrue("Leading radio results must match the HOYO query", matches > 0)
            Log.i("CatalogReads", "podcast results=${podcasts.size} matchingLeading=$matches session=Mobile")
        } finally {
            assertTrue(
                "The existing account credential must remain unchanged",
                before[CookieKey] == context.dataStore.data.first()[CookieKey],
            )
            assertEquals(NeteaseSessionType.Mobile, context.dataStore.data.first().neteaseSessionType())
            readOnly.connectionPool.evictAll()
            readOnly.dispatcher.executorService.shutdown()
        }
    }

    private fun readOnlyCatalogClient(): OkHttpClient {
        val allowedPaths = setOf(
            "/weapi/v1/cloud/get", "/eapi/v1/cloud/get",
            "/api/search/get", "/weapi/search/get", "/eapi/search/get",
            "/eapi/w/nuser/account/get", "/eapi/nuser/account/get",
        )
        val accountReadPath = Regex("/(?:weapi|eapi)/user/getfollow(?:s|eds)/[0-9]+|/(?:weapi/v1|eapi/w/v1)/user/detail/[0-9]+")
        val guard = Interceptor { chain ->
            val url = chain.request().url
            if (url.host !in setOf("music.163.com", "interface.music.163.com") ||
                (url.encodedPath.trimEnd('/') !in allowedPaths && !accountReadPath.matches(url.encodedPath.trimEnd('/')))
            ) throw IOException("This regression allows catalog reads only")
            // Reading another account must not accept background session-cookie updates.
            chain.proceed(chain.request()).newBuilder()
                .removeHeader("Set-Cookie")
                .removeHeader("x-encr-ssid")
                .removeHeader("x-encr-sskey")
                .build()
        }
        return RetrofitModule.provideOkHttpClient().newBuilder().apply {
            interceptors().add(0, guard)
            addNetworkInterceptor(guard)
            followRedirects(false)
            followSslRedirects(false)
        }.build()
    }

}
