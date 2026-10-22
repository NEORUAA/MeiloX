package com.ljyh.mei.utils.cache

import android.content.Context
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Precision


suspend fun preloadImage(context: Context, url: String) {
    if (url.isEmpty()) return

    val request = ImageRequest.Builder(context)
        .data(url)
        .size(1440)
        .precision(Precision.INEXACT)
        .build()

    // Structured cancellation drops a stale request when the next queue entry changes.
    context.imageLoader.execute(request)
}
