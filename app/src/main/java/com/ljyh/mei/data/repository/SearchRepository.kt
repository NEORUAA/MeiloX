package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.api.GetSearch
import com.ljyh.mei.data.model.api.GetSearchSuggest
import com.ljyh.mei.data.model.api.SearchResult
import com.ljyh.mei.data.model.api.SearchSuggest
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.safeApiCall
import com.ljyh.mei.data.session.SessionStamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface SearchSource {
    suspend fun search(session: SessionStamp, keyword: String, type: Int, limit: Int, offset: Int): Resource<SearchResult>
    suspend fun searchSuggest(session: SessionStamp, keyword: String): Resource<SearchSuggest>
}

class SearchRepository(
    val apiService: ApiService
) : SearchSource {
    override suspend fun search(session: SessionStamp, keyword: String, type: Int, limit: Int, offset: Int): Resource<SearchResult> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                apiService.search(
                    GetSearch(
                        s = keyword,
                        type = type,
                        limit = limit,
                        offset = offset,
                    ),
                    expectedSession = session,
                ).also { check(it.code == 200) { "Search request failed (${it.code})" } }
            }
        }
    }

    override suspend fun searchSuggest(session: SessionStamp, keyword: String): Resource<SearchSuggest> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                apiService.searchSuggest(
                    GetSearchSuggest(
                        s = keyword
                    ),
                    expectedSession = session,
                ).also { check(it.code == 200) { "Search suggestions failed (${it.code})" } }
            }
        }
    }
}
