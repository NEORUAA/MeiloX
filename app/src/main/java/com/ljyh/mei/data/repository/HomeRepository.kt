package com.ljyh.mei.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ljyh.mei.AppContext
import com.ljyh.mei.constants.LastHomePageTime
import com.ljyh.mei.data.model.eapi.HomePageResourceShow
import com.ljyh.mei.data.model.weapi.buildGetHomePageResourceShow
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.safeApiCall
import com.ljyh.mei.utils.cache.CacheFile.isNewDay
import com.ljyh.mei.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class HomeRepository(private val eApiService: EApiService, private val apiService: ApiService) {

    val context = AppContext.instance
    suspend fun getHomePageResourceShow(
        refresh: Boolean = false
    ): Resource<List<HomePageResourceShow.Data.Block>> = withContext(Dispatchers.IO) {
        safeApiCall {
            if (refresh || isNewDay(getLastFetchTime(context))) {
                Timber.tag("getHomePageResourceShow").d("Fetching home page")
                val page = eApiService.getHomePageResourceShow(
                    buildGetHomePageResourceShow(refresh = refresh.toString())
                )
                saveLastHomePage(context, 1, page.data.blocks)
                page.data.blocks
            } else {
                Timber.tag("getHomePageResourceShow").d("Loading cached home page")
                getLastHomePage(context, 1)
            }
        }
    }


    private suspend fun saveLastHomePage(
        context: Context,
        page: Int,
        newData: List<HomePageResourceShow.Data.Block>
    ) {
        withContext(Dispatchers.IO) {
            val file = getFileForPage(context, page)
            val json = Gson().toJson(newData)
            file.writeText(json)
            context.dataStore.edit {
                it[LastHomePageTime] = System.currentTimeMillis()
            }
        }
    }

    private suspend fun getLastHomePage(
        context: Context,
        page: Int
    ): List<HomePageResourceShow.Data.Block> {
        return withContext(Dispatchers.IO) {
            val file = getFileForPage(context, page)
            if (file.exists()) {
                try {
                    val json = file.readText()
                    if (json.isBlank()) {
                        return@withContext emptyList()
                    }
                    val gson = Gson()
                    gson.fromJson(
                        json,
                        object : TypeToken<List<HomePageResourceShow.Data.Block>>() {}.type
                    )
                } catch (e: Exception) {
                    // 捕获 JSON 语法错误 (JsonSyntaxException)、IO读取错误等所有异常
                    e.printStackTrace() // 打印错误日志方便调试，不需要的话可以删掉这行
                    emptyList()
                }
            } else {
                emptyList()
            }
        }
    }



    private fun getFileForPage(context: Context, page: Int): File {
        return File(context.filesDir, "home_page_data_$page.json")
    }

    private suspend fun getLastFetchTime(context: Context): Long {
        val preferences = context.dataStore.data.first()
        Timber.tag("getLastFetchTime").d((preferences[LastHomePageTime] ?: 0L).toString())
        return preferences[LastHomePageTime] ?: 0L
    }
}
