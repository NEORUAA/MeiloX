package com.ljyh.mei.data.model.api

data class ManipulateTrackResult(
    val code:Int,
    val message: String? = null,
    val cloudCount:Int? = null,
    val count:Int? = null,
    val trackIds: com.google.gson.JsonElement? = null,
    val offlineIds: List<Long>? = null,
)
