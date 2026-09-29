package com.ljyh.mei.data.model.api

data class SongLikeIds(val code: Int, val ids: List<Long>?)

data class SongLike(
    val trackId: Long,
    val like: Boolean,
    // Official catalog songs use zero, not the signed-in account ID.
    val userid: Long = 0,
)

data class SongLikeResult(val code: Int, val playlistId: Long?)
