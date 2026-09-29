package com.ljyh.mei.data.model.api

import com.google.gson.Gson

data class ManipulateTrack(
    val op: String,
    val pid: String,
    var trackIds: String,
    val reverse: Boolean? = true,
) {
    init {
        require(op == "add" || op == "del")
        require(pid.toLongOrNull()?.let { it > 0 } == true)
        val ids = trackIds.split(",").map(String::trim).distinct()
        require(ids.isNotEmpty() && ids.all { it.toLongOrNull()?.let { id -> id > 0 } == true })
        trackIds = Gson().toJson(ids)
    }
}
