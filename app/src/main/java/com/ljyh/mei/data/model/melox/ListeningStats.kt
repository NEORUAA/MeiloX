package com.ljyh.mei.data.model.melox

data class ListeningStatsSummary(val totalSeconds: Long)

enum class ListeningStatsPeriod(val wireValue: String) {
    Week("week"),
    Month("month"),
    Year("year"),
}

data class ListeningStatsReport(
    val period: ListeningStatsPeriod,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val totalMinutes: Long,
    val listenedDays: Int,
    val bins: List<ListeningStatsBin>,
    val isCurrent: Boolean,
    val sections: List<ListeningStatsSection> = emptyList(),
)

data class ListeningStatsBin(
    val period: String,
    val durationMinutes: Long,
)

enum class ListeningStatsSectionType(val wireValue: String) {
    LateSleep("lateSleep"),
    EarlySleep("earlySleep"),
    DurationIncrease("durationDiffPositive"),
    DurationDecrease("durationDiffNegative"),
    LikedSongsPercent("maxPlayUserLikeChannel"),
    RecommendedSongsPercent("maxPlayRcmdChannel"),
}

data class ListeningStatsSection(
    val type: ListeningStatsSectionType,
    // Sleep values are epoch milliseconds, duration changes seconds, and channel shares percentages.
    val value: Long,
) {
    val durationChangeMinutes: Long?
        get() = when (type) {
            ListeningStatsSectionType.DurationIncrease, ListeningStatsSectionType.DurationDecrease ->
                value / 60 + if (value % 60 >= 30) 1 else 0
            else -> null
        }
}

data class ListeningStatsYear(
    val year: Int,
    val totalSeconds: Long,
    val songCount: Long?,
)
