package com.ljyh.mei.data.repository

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.ljyh.mei.AppContext
import com.ljyh.mei.data.model.eapi.HomePageResourceShow.Data.Block
import com.ljyh.mei.data.model.weapi.buildGetHomePageResourceShow
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HomeRepository internal constructor(
    private val directory: File,
    private val sessions: SessionStore,
    private val fetch: suspend (Boolean) -> List<Block>,
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(api: EApiService, sessions: SessionStore) : this(
        File(AppContext.instance.filesDir, "home_accounts"),
        sessions,
        { refresh ->
            val response = api.getHomePageResourceShow(buildGetHomePageResourceShow(refresh = refresh.toString()))
            if (response.code != 200) throw IOException("Official home request failed (${response.code})")
            response.data.blocks
        },
    )

    private data class CachedPage(
        @SerializedName("fetchedAt") val fetchedAt: Long,
        @SerializedName("blocks") val blocks: List<Block?>?,
    )
    private val gson = Gson()
    private val cacheMutex = Mutex()

    suspend fun getHomePageResourceShow(
        stamp: SessionStamp,
        refresh: Boolean = false,
    ): Resource<List<Block>> = try {
        sessions.requireCurrent(stamp)
        val cached = if (refresh) null else withContext(ioDispatcher) {
            cacheMutex.withLock { readCache(stamp) }
        }
        val blocks = cached ?: fetch(refresh).also {
            currentCoroutineContext().ensureActive()
            sessions.requireCurrent(stamp)
            withContext(ioDispatcher) {
                cacheMutex.withLock { writeCache(stamp, it) }
            }
        }
        currentCoroutineContext().ensureActive()
        sessions.requireCurrent(stamp)
        Resource.Success(blocks)
    } catch (error: CancellationException) {
        throw error
    } catch (error: SessionChangedException) {
        throw error
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        sessions.requireCurrent(stamp)
        Resource.Error(error.message ?: "Official home request failed")
    }

    private fun readCache(stamp: SessionStamp): List<Block>? {
        sessions.requireCurrent(stamp)
        return try {
            val file = cacheFile(stamp)
            if (!file.isFile) return null
            val page = gson.fromJson(file.readText(), CachedPage::class.java) ?: return null
            val current = now()
            val boundary = Instant.ofEpochMilli(current).atZone(ZoneId.of("Asia/Shanghai"))
                .toLocalDate().atTime(LocalTime.of(6, 0)).atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli()
            page.blocks
                ?.takeIf { page.fetchedAt in boundary..current && it.none { block -> block?.positionCode.isNullOrBlank() } }
                ?.filterNotNull()
        } catch (_: IOException) {
            null
        } catch (_: com.google.gson.JsonParseException) {
            null
        }
    }

    private suspend fun writeCache(stamp: SessionStamp, blocks: List<Block>) {
        sessions.requireCurrent(stamp)
        currentCoroutineContext().ensureActive()
        val file = cacheFile(stamp)
        var temporary: File? = null
        var replaced = false
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return
            temporary = File.createTempFile("home-", ".tmp", directory)
            temporary.writeText(gson.toJson(CachedPage(now(), blocks)))
            currentCoroutineContext().ensureActive()
            sessions.requireCurrent(stamp)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            replaced = true
            currentCoroutineContext().ensureActive()
            sessions.requireCurrent(stamp)
        } catch (error: CancellationException) {
            if (replaced) file.delete()
            throw error
        } catch (error: SessionChangedException) {
            if (replaced) file.delete()
            throw error
        } catch (_: IOException) {
            // A cache failure must not hide a valid response from the official pipeline.
        } finally {
            temporary?.delete()
        }
    }

    private fun cacheFile(stamp: SessionStamp): File {
        val identity = stamp.identity
        val kind = when {
            identity.authenticated -> "user"
            identity.anonymous -> "anonymous"
            else -> "guest"
        }
        return File(directory, "${kind}_${identity.userId}.json")
    }
}
