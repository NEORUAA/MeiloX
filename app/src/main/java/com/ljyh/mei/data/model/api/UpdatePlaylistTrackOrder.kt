package com.ljyh.mei.data.model.api

import com.google.gson.Gson

data class UpdatePlaylistTrackOrder(
    val pid: String,
    val trackIds: String,
    val op: String = "update",
) {
    constructor(pid: String, ids: List<Long>) : this(pid, Gson().toJson(ids.map(Long::toString)))
}
