package com.ljyh.mei.data.repository

import androidx.datastore.preferences.core.Preferences
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import com.ljyh.mei.AppContext
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.model.eapi.HomePageResourceShow.Data.Block
import com.ljyh.mei.data.model.weapi.buildGetHomePageResourceShow
import com.ljyh.mei.data.network.NeteaseSessionType
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.neteaseSessionType
import com.ljyh.mei.data.network.safeApiCall
import com.ljyh.mei.utils.dataStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class HomePageOwner(
    @SerializedName("userId") val userId: String,
    @SerializedName("sessionType") val sessionType: NeteaseSessionType,
)

internal fun Preferences.homePageOwner(): HomePageOwner {
    val type = neteaseSessionType()
    return HomePageOwner(if (type == NeteaseSessionType.None) "" else this[UserIdKey].orEmpty(), type)
}

class HomeRepository internal constructor(
    private val cacheFile: File,
    preferences: Flow<Preferences>,
    private val fetch: suspend (Boolean) -> List<Block>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(
        eApiService: EApiService,
        @Suppress("UNUSED_PARAMETER") apiService: ApiService,
    ) : this(
        cacheFile = File(AppContext.instance.filesDir, "home_page_data_1.json"),
        preferences = AppContext.instance.dataStore.data,
        fetch = { refresh ->
            loadHomePageBlocks(
                buildGetHomePageResourceShow(refresh = refresh.toString()),
                eApiService::getHomePageResourceShow,
            )
        },
    )

    private data class CachedHomePage(
        @SerializedName("version") val version: Int,
        @SerializedName("owner") val owner: HomePageOwner?,
        @SerializedName("fetchedAt") val fetchedAt: Long,
        @SerializedName("blocks") val blocks: List<Block?>?,
    )

    internal val owners: Flow<HomePageOwner> = preferences.map { it.homePageOwner() }.distinctUntilChanged()
    private val gson = Gson()
    private val cacheMutex = Mutex()

    suspend fun getHomePageResourceShow(refresh: Boolean = false): Resource<List<Block>> =
        getHomePageResourceShow(refresh, owners.first())

    internal suspend fun getHomePageResourceShow(
        refresh: Boolean,
        owner: HomePageOwner,
    ): Resource<List<Block>> = withContext(Dispatchers.IO) {
        safeApiCall {
            requireOwner(owner)
            val cached = if (refresh) null else cacheMutex.withLock { readCache(owner) }
            val blocks = cached ?: fetch(refresh).also { writeCache(owner, it) }
            requireOwner(owner)
            blocks
        }
    }

    /** Read the same account/day artwork shown on Home without refreshing the feed. */
    internal suspend fun cachedRadarCover(playlistId: String): String? = withContext(Dispatchers.IO) {
        val owner = owners.first()
        requireOwner(owner)
        val blocks = cacheMutex.withLock { readCache(owner) }.orEmpty()
        fun find(node: JsonElement?): String? {
            if (node == null || node.isJsonNull) return null
            if (node.isJsonArray) return node.asJsonArray.firstNotNullOfOrNull { find(it) }
            if (!node.isJsonObject) return null
            val objectNode = node.asJsonObject
            val id = objectNode.get("resourceId")?.takeIf { it.isJsonPrimitive }?.asString
            val cover = objectNode.get("coverImg")?.takeIf { it.isJsonPrimitive }
                ?.asJsonPrimitive?.takeIf { it.isString }?.asString
            if (id == playlistId && cover != null &&
                (cover.startsWith("https://") || cover.startsWith("http://"))) {
                return com.ljyh.mei.utils.playlistCoverWithoutOverlays(cover)
            }
            return objectNode.entrySet().firstNotNullOfOrNull { find(it.value) }
        }
        val artwork = blocks.asSequence().filter { it.positionCode == "PAGE_RECOMMEND_RADAR" }
            .mapNotNull { find(it.dslData) }.firstOrNull()
        requireOwner(owner)
        artwork
    }

    private fun readCache(owner: HomePageOwner): List<Block>? = try {
        if (!cacheFile.isFile) null else {
            val page = gson.fromJson(cacheFile.readText(), CachedHomePage::class.java)
            val current = now()
            val localNow = Instant.ofEpochMilli(current).atZone(HOME_CACHE_ZONE)
            val todayBoundary = localNow.toLocalDate().atTime(LocalTime.of(6, 0)).atZone(HOME_CACHE_ZONE)
            val boundary = (if (localNow < todayBoundary) todayBoundary.minusDays(1) else todayBoundary)
                .toInstant().toEpochMilli()
            page?.takeIf {
                it.version == HOME_CACHE_VERSION && it.owner == owner && it.fetchedAt in boundary..current
            }?.blocks?.takeIf { blocks -> blocks.none { it?.positionCode.isNullOrBlank() } }?.filterNotNull()
        }
    } catch (_: IOException) {
        null
    } catch (_: JsonParseException) {
        null
    }

    private suspend fun writeCache(owner: HomePageOwner, blocks: List<Block>) = cacheMutex.withLock {
        requireOwner(owner)
        val temporary = File.createTempFile("home_page_", ".tmp", cacheFile.parentFile)
        try {
            temporary.writeText(gson.toJson(CachedHomePage(HOME_CACHE_VERSION, owner, now(), blocks)))
            // Keep the previous complete feed until every page and the replacement are ready.
            requireOwner(owner)
            Files.move(
                temporary.toPath(), cacheFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private suspend fun requireOwner(owner: HomePageOwner) {
        currentCoroutineContext().ensureActive()
        if (owners.first() != owner) throw CancellationException("Home account changed")
    }

    private companion object {
        const val HOME_CACHE_VERSION = 2
        val HOME_CACHE_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
