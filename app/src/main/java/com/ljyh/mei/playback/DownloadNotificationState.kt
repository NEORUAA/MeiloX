package com.ljyh.mei.playback

/** Pure batch accounting; an old execution cannot publish into its replacement. */
internal class DownloadNotificationState {
    data class Lease(val requestId: String, val token: Long)
    enum class Outcome { SUCCESS, FAILED, CANCELED }
    data class Snapshot(val revision: Long, val title: String, val progress: Int,
        val active: Int, val total: Int, val lastRequestId: String?)
    private data class Entry(val lease: Lease, var title: String = "准备下载...", var progress: Int = 0)
    private val entries = linkedMapOf<String, Entry>()
    private var owner: Pair<Long, Long>? = null
    private var nextToken = 0L
    var revision = 0L
        private set
    private var succeeded = 0
    private var failed = 0
    private var canceled = 0
    private var failure: String? = null
    private var cancellation: String? = null
    private var lastRequestId: String? = null

    fun begin(requestId: String, account: Long, generation: Long): Lease {
        if (entries.isEmpty() || owner != (account to generation)) {
            entries.clear()
            succeeded = 0; failed = 0; canceled = 0; failure = null; cancellation = null; lastRequestId = null
            owner = account to generation
        }
        val lease = Lease(requestId, ++nextToken)
        entries[requestId] = Entry(lease)
        revision++
        return lease
    }

    fun update(lease: Lease, title: String, progress: Int): Boolean {
        val entry = entries[lease.requestId]?.takeIf { it.lease == lease } ?: return false
        entry.title = title
        entry.progress = progress.coerceIn(0, 99)
        revision++
        return true
    }

    fun finish(lease: Lease, outcome: Outcome?, title: String = ""): Boolean {
        if (entries[lease.requestId]?.lease != lease) return false
        entries.remove(lease.requestId)
        when (outcome) {
            Outcome.SUCCESS -> succeeded++
            Outcome.FAILED -> { failed++; if (failure == null) failure = title }
            Outcome.CANCELED -> { canceled++; if (cancellation == null) cancellation = title.ifBlank { "下载已取消" } }
            null -> Unit
        }
        if (outcome != null) lastRequestId = lease.requestId
        revision++
        return true
    }

    fun snapshot(): Snapshot? {
        val active = entries.size
        val total = active + succeeded + failed + canceled
        if (total == 0) return null
        val title = when {
            active == 1 && total == 1 -> entries.values.single().title
            active > 0 -> "正在下载 ($succeeded/$total)"
            failed > 0 -> if (total == 1) failure.orEmpty() else "部分下载失败"
            canceled > 0 -> cancellation ?: "下载已取消"
            else -> "全部下载完成"
        }
        val progress = when {
            active > 0 -> ((succeeded * 100L + entries.values.sumOf { it.progress.toLong() }) / total).toInt().coerceAtMost(99)
            failed == 0 && canceled == 0 -> 100
            else -> 0
        }
        return Snapshot(revision, title, progress, active, total, lastRequestId)
    }
}
