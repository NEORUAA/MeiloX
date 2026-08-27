package com.ljyh.mei.data.model
import com.google.gson.annotations.SerializedName


data class SongUrl(
    @SerializedName("code")
    val code: Int,
    @SerializedName("data")
    val `data`: List<Data>
) {
    /**
     * Returns the first complete, non-trial source for the requested song.
     *
     * The `payed` flag describes account/song metadata and is not a reliable
     * indicator that the returned URL is a short free-trial stream.
     */
    fun fullSourceFor(expectedId: String): Data? {
        if (code != 200) return null
        return data.firstOrNull { it.isFullSourceFor(expectedId) }
    }

    /** Returns all complete sources for the requested song ids in one pass. */
    fun fullSourcesFor(expectedIds: Set<String>): List<Data> {
        if (code != 200 || expectedIds.isEmpty()) return emptyList()
        return data.filter { source ->
            val sourceId = source.id.toString()
            sourceId in expectedIds && source.isFullSourceFor(sourceId)
        }
    }

    data class Data(
        @SerializedName("br")
        val br: Int,
        @SerializedName("canExtend")
        val canExtend: Boolean,
        @SerializedName("channelLayout")
        val channelLayout: Any,
        @SerializedName("closedGain")
        val closedGain: Any,
        @SerializedName("closedPeak")
        val closedPeak: Any,
        @SerializedName("code")
        val code: Int,
        @SerializedName("effectTypes")
        val effectTypes: Any,
        @SerializedName("encodeType")
        val encodeType: String,
        @SerializedName("expi")
        val expi: Int?,
        @SerializedName("fee")
        val fee: Int,
        @SerializedName("flag")
        val flag: Int,
        @SerializedName("freeTimeTrialPrivilege")
        val freeTimeTrialPrivilege: FreeTimeTrialPrivilege,
        @SerializedName("freeTrialInfo")
        val freeTrialInfo: FreeTrialInfo?,
        @SerializedName("freeTrialPrivilege")
        val freeTrialPrivilege: FreeTrialPrivilege,
        @SerializedName("gain")
        val gain: Any,
        @SerializedName("id")
        val id: Long,
        @SerializedName("level")
        val level: String,
        @SerializedName("levelConfuse")
        val levelConfuse: Any,
        @SerializedName("md5")
        val md5: String,
        @SerializedName("message")
        val message: Any,
        @SerializedName("musicId")
        val musicId: String,
        @SerializedName("payed")
        val payed: Int,
        @SerializedName("peak")
        val peak: Double,
        @SerializedName("podcastCtrp")
        val podcastCtrp: Any,
        @SerializedName("rightSource")
        val rightSource: Int,
        @SerializedName("size")
        val size: Int,
        @SerializedName("time")
        val time: Int,
        @SerializedName("type")
        val type: String,
        @SerializedName("uf")
        val uf: Any,
        @SerializedName("url")
        val url: String?,
        @SerializedName("urlSource")
        val urlSource: Int
    ) {
        data class FreeTrialInfo(
            @SerializedName("start")
            val start: Long? = null,
            @SerializedName("end")
            val end: Long? = null,
        )

        data class FreeTimeTrialPrivilege(
            @SerializedName("remainTime")
            val remainTime: Int,
            @SerializedName("resConsumable")
            val resConsumable: Boolean,
            @SerializedName("type")
            val type: Int,
            @SerializedName("userConsumable")
            val userConsumable: Boolean
        )

        data class FreeTrialPrivilege(
            @SerializedName("cannotListenReason")
            val cannotListenReason: Any,
            @SerializedName("freeLimitTagType")
            val freeLimitTagType: Any,
            @SerializedName("listenType")
            val listenType: Any,
            @SerializedName("playReason")
            val playReason: Any,
            @SerializedName("resConsumable")
            val resConsumable: Boolean,
            @SerializedName("userConsumable")
            val userConsumable: Boolean
        )
    }
}

/**
 * A playable source must belong to the requested song, be a successful source,
 * contain a URL, and not be marked as a free-trial source.
 */
fun SongUrl.Data.isFullSourceFor(expectedId: String): Boolean =
    isFullSource(
        expectedId = expectedId,
        sourceId = id,
        sourceCode = code,
        url = url,
        freeTrialInfo = freeTrialInfo,
    )

internal fun isFullSource(
    expectedId: String,
    sourceId: Long,
    sourceCode: Int,
    url: String?,
    freeTrialInfo: Any?,
): Boolean =
    sourceId.toString() == expectedId &&
        sourceCode == 200 &&
        !url.isNullOrBlank() &&
        freeTrialInfo == null
