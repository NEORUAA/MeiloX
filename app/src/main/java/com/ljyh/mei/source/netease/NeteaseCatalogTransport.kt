package com.ljyh.mei.source.netease

import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.GetSearch
import com.ljyh.mei.data.model.api.GetSearchSuggest
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.api.SearchResult
import com.ljyh.mei.data.model.api.SearchSuggest
import com.ljyh.mei.data.network.api.ApiService
import javax.inject.Inject
import javax.inject.Singleton

/** Compatibility operations for native screens whose contracts are specific to NetEase. */
@Singleton
class NeteaseCatalogTransport @Inject constructor(private val api: ApiService) {
    suspend fun search(request: GetSearch): SearchResult = api.search(
        request,
        cookieOs = if (request.type == 1009) "pc" else null,
    )

    suspend fun searchSuggest(request: GetSearchSuggest): SearchSuggest = api.searchSuggest(request)
    suspend fun getSongDetail(request: GetSongDetails, sessionFingerprint: String? = null): Tracks = api.getSongDetail(request, sessionFingerprint)
}
