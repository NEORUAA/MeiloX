package com.ljyh.mei.data.repository

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ljyh.mei.data.model.melox.ListeningStatsBin
import com.ljyh.mei.data.model.melox.ListeningStatsPeriod
import com.ljyh.mei.data.model.melox.ListeningStatsReport
import com.ljyh.mei.data.model.melox.ListeningStatsSection
import com.ljyh.mei.data.model.melox.ListeningStatsSectionType
import com.ljyh.mei.data.model.melox.ListeningStatsSummary
import com.ljyh.mei.data.model.melox.ListeningStatsYear
import java.time.LocalDate

internal fun parseListeningStatsSummary(response: JsonObject): ListeningStatsSummary =
    ListeningStatsSummary(listeningStatsData(response).requiredNonNegativeLong("totalDuration"))

internal fun parseListeningStatsReport(
    response: JsonObject,
    period: ListeningStatsPeriod,
    isCurrent: Boolean,
): ListeningStatsReport {
    val data = listeningStatsData(response)
    val startTime = data.requiredNonNegativeLong("startTime")
    val endTime = data.requiredNonNegativeLong("endTime")
    check(startTime > 0 && endTime >= startTime) { "The listening report date range is unavailable" }
    val block = data.get("listenTimeDistributionBlock")?.objectValue()
        ?: error("The listening report is unavailable")
    val totalMinutes = block.requiredNonNegativeLong("playDuration")
    val days = block.requiredNonNegativeLong("listenDays")
    check(days <= 366) { "The listening day count is invalid" }
    val details = block.get("durationDetails")
    check(details?.isJsonArray == true) { "The listening timeline is unavailable" }
    val bins = details.asJsonArray.map { element ->
        val item = element.objectValue() ?: error("The listening timeline is invalid")
        val date = item.get("period")?.stringValue()
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: error("The listening timeline date is invalid")
        // The official footprint chart combines songs, podcasts, and audiobooks.
        val duration = listOf("duration", "podcastDuration", "audiobookDuration").fold(0L) { sum, key ->
            val value = if (key == "duration") item.requiredNonNegativeLong(key)
            else if (item.get(key)?.isJsonNull != false) 0L else item.requiredNonNegativeLong(key)
            Math.addExact(sum, value)
        }
        ListeningStatsBin(date.toString(), duration)
    }.sortedBy { it.period }
    check(bins.map { it.period }.distinct().size == bins.size) { "The listening timeline contains duplicate dates" }
    val sections = block.get("sections")?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty().mapNotNull { element ->
        val item = element.objectValue() ?: return@mapNotNull null
        val type = ListeningStatsSectionType.entries.firstOrNull {
            it.wireValue == item.get("type")?.stringValue()
        } ?: return@mapNotNull null
        val value = item.optionalNonNegativeLong("value") ?: return@mapNotNull null
        if (type in listOf(ListeningStatsSectionType.LikedSongsPercent, ListeningStatsSectionType.RecommendedSongsPercent)
            && value > 100
        ) return@mapNotNull null
        ListeningStatsSection(type, value)
    }
    return ListeningStatsReport(period, startTime, endTime, totalMinutes, days.toInt(), bins, isCurrent, sections)
}

internal fun parseListeningStatsYears(response: JsonObject): List<ListeningStatsYear> {
    val years = listeningStatsData(response).get("yearItems")
        // Accounts without a published annual report receive a successful empty data object.
        ?: return emptyList()
    check(years.isJsonArray) { "The annual listening reports are unavailable" }
    return years.asJsonArray.map { element ->
        val item = element.objectValue() ?: error("The annual listening report is invalid")
        val year = item.requiredNonNegativeLong("year")
        check(year in 1..9999) { "The listening report year is invalid" }
        ListeningStatsYear(
            year.toInt(),
            item.requiredNonNegativeLong("playDuration"),
            item.optionalNonNegativeLong("playNum"),
        )
    }.distinctBy { it.year }.sortedByDescending { it.year }
}

private fun listeningStatsData(response: JsonObject): JsonObject {
    check(response.get("code")?.nonNegativeLong() == 200L) { "Listening statistics are unavailable" }
    return response.get("data")?.objectValue() ?: error("Listening statistics are unavailable")
}

private fun JsonElement.objectValue(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

private fun JsonElement.stringValue(): String? = takeIf { it.isJsonPrimitive }?.asJsonPrimitive
    ?.takeIf { it.isString || it.isNumber }?.asString

private fun JsonElement.nonNegativeLong(): Long? = stringValue()?.let {
    runCatching { it.toBigDecimal().toBigIntegerExact().longValueExact() }.getOrNull()?.takeIf { value -> value >= 0 }
}

private fun JsonObject.optionalNonNegativeLong(name: String): Long? = get(name)?.nonNegativeLong()

private fun JsonObject.requiredNonNegativeLong(name: String): Long = optionalNonNegativeLong(name)
    ?: error("The listening statistics field $name is unavailable")
