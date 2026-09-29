package com.ljyh.mei.parasite

import com.ljyh.mei.data.model.api.GetLyricV1
import com.ljyh.mei.data.model.api.GetSearch
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.model.api.GetUserPlaylist
import com.ljyh.mei.data.model.weapi.buildGetHomePageResourceShow
import com.ljyh.mei.di.AppComponent
import kotlinx.coroutines.runBlocking

/** Exercises the production DI providers and existing DTO converters without publishing user data. */
internal class HostRetrofitProbe(private val component: AppComponent, private val report: (String) -> Unit) {
    fun run() {
        try {
            runBlocking {
                val sessions = component.hostRequests().sessions
                val stamp = sessions.snapshot()
                check(stamp.identity.authenticated)
                val api = component.apiService()
                val account = api.getAccountDetail()
                sessions.requireCurrent(stamp)
                report("retrofit=account code=${account.code} matches_session=${account.profile?.userId == stamp.identity.userId}")
                val playlists = api.getUserPlaylist(GetUserPlaylist(stamp.identity.userId.toString(), limit = "1"))
                report("retrofit=playlists code=${playlists.code} data_present=${playlists.playlist.isNotEmpty()}")
                val search = api.search(GetSearch("music", limit = 1))
                report("retrofit=search code=${search.code}")
                val songId = search.result.songs?.firstOrNull()?.id
                if (songId != null) {
                    report("retrofit=song_detail code=${api.getSongDetail(GetSongDetails(songId.toString())).code}")
                    report("retrofit=lyrics code=${api.getLyricV1(GetLyricV1(songId.toString())).code}")
                    val source = api.getSongUrlV1(GetSongUrlV1("[$songId]", "standard"))
                    report("retrofit=playback_url code=${source.code} data_present=${source.data.isNotEmpty()}")
                }
                report("retrofit=subcount code=${component.weapiService().getUserSubcount().code}")
                val home = component.eapiService().getHomePageResourceShow(buildGetHomePageResourceShow("false"))
                report("retrofit=home code=${home.code} blocks_present=${home.data.blocks.isNotEmpty()}")
                sessions.requireCurrent(stamp)
                report("retrofit_complete session_unchanged=true")
            }
        } catch (error: Throwable) {
            report("retrofit_aborted type=${error.javaClass.name}")
            error.stackTrace.take(8).forEach { frame ->
                report("retrofit_frame=${frame.className}.${frame.methodName}:${frame.lineNumber}")
            }
        }
    }
}
