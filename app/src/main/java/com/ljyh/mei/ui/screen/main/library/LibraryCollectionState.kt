package com.ljyh.mei.ui.screen.main.library

import com.ljyh.mei.source.netease.repository.LibraryCollectionPage

data class LibraryCollectionState<T>(
    val items: List<T> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
    val error: String? = null,
    val retryFromStart: Boolean = false,
) {
    fun failed(message: String, retryFromStart: Boolean = false): LibraryCollectionState<T> = copy(
        isLoading = false, isLoadingMore = false, error = message, retryFromStart = retryFromStart,
    )

    fun append(page: LibraryCollectionPage<T>, key: (T) -> Any): LibraryCollectionState<T> = copy(
        items = (items + page.items).distinctBy(key),
        isLoading = false,
        isLoadingMore = false,
        nextOffset = page.nextOffset,
        hasMore = page.hasMore && page.nextOffset > nextOffset,
        error = null,
        retryFromStart = false,
    )
}

enum class LibraryPlaylistFilter { All, Created, Subscribed }
