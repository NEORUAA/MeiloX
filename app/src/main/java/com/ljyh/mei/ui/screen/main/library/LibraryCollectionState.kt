package com.ljyh.mei.ui.screen.main.library


/** Raw offsets belong to the collection page even when visible rows are filtered or deduplicated. */
data class LibraryCollectionPage<T>(val items: List<T>, val nextOffset: Int, val hasMore: Boolean)

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

/** Keep raw offsets while rejecting a continuation that repeats the same page. */
internal suspend fun <T> collectLibraryPages(
    key: (T) -> String,
    loadPage: suspend (Int) -> LibraryCollectionPage<T>,
): List<T> {
    val items = linkedMapOf<String, T>()
    var offset = 0
    do {
        val page = loadPage(offset)
        val previousSize = items.size
        page.items.forEach { items.putIfAbsent(key(it), it) }
        if (!page.hasMore) return items.values.toList()
        check(page.nextOffset > offset && items.size > previousSize) {
            "Collection pagination did not advance"
        }
        offset = page.nextOffset
    } while (true)
}
