package com.ljyh.mei.data.repository

import com.google.gson.JsonParser
import com.ljyh.mei.data.model.melox.ListeningStatsPeriod
import com.ljyh.mei.data.model.melox.ListeningStatsSection
import com.ljyh.mei.data.model.melox.ListeningStatsSectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ListeningStatsTest {
    @Test
    fun cumulativeAndAnnualDurationsUseSecondsWhileReportDurationsUseMinutes() {
        val summary = parseListeningStatsSummary(json("""{"code":200,"data":{"totalDuration":48689}}"""))
        val years = parseListeningStatsYears(json("""{"code":200,"data":{"yearItems":[
          {"year":2024,"playDuration":7200,"playNum":80},
          {"year":2025,"playDuration":3600,"playNum":0}
        ]}}"""))
        val report = report("""{"period":"2026-09-29","duration":120}""")

        assertEquals(48689L, summary.totalSeconds)
        assertEquals(listOf(2025, 2024), years.map { it.year })
        assertEquals(7200L, years.last().totalSeconds)
        assertEquals(80L, years.last().songCount)
        assertEquals(120L, report.totalMinutes)
        assertEquals(120L, report.bins.single().durationMinutes)
        assertFalse(report.isCurrent)
    }

    @Test
    fun timelineCombinesTheSameAudioTypesAsTheOfficialFootprintChartAndPreservesZeroDays() {
        val report = report("""
          {"period":"2026-09-30","duration":0},
          {"period":"2026-09-29","duration":90,"podcastDuration":20,"audiobookDuration":10}
        """.trimIndent())

        assertEquals(listOf("2026-09-29", "2026-09-30"), report.bins.map { it.period })
        assertEquals(listOf(120L, 0L), report.bins.map { it.durationMinutes })
    }

    @Test
    fun sectionsPreserveTheirDifferentUnitsAndIgnoreUnrecognizedOrUnavailableValues() {
        val report = report("""{"period":"2026-09-29","duration":120}""", """
          {"type":"earlySleep","value":"1790635203000"},
          {"type":"durationDiffPositive","value":"6533"},
          {"type":"maxPlayUserLikeChannel","value":80},
          {"type":"newServerMetric","value":42},
          {"type":"lateSleep","value":null},
          {"type":"maxPlayRcmdChannel","value":101}
        """.trimIndent())

        assertEquals(
            listOf(ListeningStatsSectionType.EarlySleep, ListeningStatsSectionType.DurationIncrease,
                ListeningStatsSectionType.LikedSongsPercent),
            report.sections.map { it.type },
        )
        assertEquals(listOf(1790635203000L, 6533L, 80L), report.sections.map { it.value })
    }

    @Test
    fun durationChangesRoundToTheNearestMinuteWithoutRoundingOtherSectionTypes() {
        val seconds = listOf(29L, 30L, 59L, 60L, 8630L, Long.MAX_VALUE)
        val minutes = listOf(0L, 1L, 1L, 1L, 144L, Long.MAX_VALUE / 60)
        listOf(ListeningStatsSectionType.DurationIncrease, ListeningStatsSectionType.DurationDecrease).forEach { type ->
            assertEquals(minutes, seconds.map { ListeningStatsSection(type, it).durationChangeMinutes })
        }
        assertEquals(null, ListeningStatsSection(ListeningStatsSectionType.EarlySleep, 1790635203000L).durationChangeMinutes)
        assertEquals(null, ListeningStatsSection(ListeningStatsSectionType.LikedSongsPercent, 80L).durationChangeMinutes)
    }

    @Test
    fun unavailableOrMalformedTotalsDoNotTurnIntoZeroListeningTime() {
        listOf(
            """{"code":403,"data":{"totalDuration":0}}""",
            """{"code":200,"data":null}""",
            """{"code":200,"data":{}}""",
            """{"code":200,"data":{"totalDuration":-1}}""",
            """{"code":200,"data":{"totalDuration":1.5}}""",
            """{"code":200,"data":{"totalDuration":"9223372036854775808"}}""",
        ).forEach { response ->
            assertThrows(IllegalStateException::class.java) { parseListeningStatsSummary(json(response)) }
        }
        assertEquals(0L, parseListeningStatsSummary(json("""{"code":200,"data":{"totalDuration":0}}""")).totalSeconds)
    }

    @Test
    fun accountsWithoutPublishedAnnualReportsAreEmptyWhileFailedOrMalformedResponsesRemainErrors() {
        listOf(
            """{"code":200,"message":"","data":{}}""",
            """{"code":200,"data":{"yearItems":[]}}""",
        ).forEach { response ->
            assertEquals(emptyList<Any>(), parseListeningStatsYears(json(response)))
        }
        listOf(
            """{"code":403,"data":{}}""",
            """{"code":200,"data":null}""",
            """{"code":200,"data":{"yearItems":{}}}""",
            """{"code":200,"data":{"yearItems":null}}""",
        ).forEach { response ->
            assertThrows(IllegalStateException::class.java) { parseListeningStatsYears(json(response)) }
        }
    }

    @Test
    fun malformedTimelineDoesNotProduceAPartialChart() {
        listOf(
            """{"period":"2026-09-29","duration":-1}""",
            """{"period":"2026-09-31","duration":120}""",
            """{"period":"2026-09-29","duration":120,"podcastDuration":"invalid"}""",
            """{"period":"2026-09-29","duration":60},{"period":"2026-09-29","duration":60}""",
        ).forEach { rows ->
            assertThrows(IllegalStateException::class.java) { report(rows) }
        }
    }

    private fun report(rows: String, sections: String = "") = parseListeningStatsReport(
        json("""{"code":200,"data":{
          "startTime":1788192000000,"endTime":1790697600000,
          "listenTimeDistributionBlock":{"playDuration":120,"listenDays":1,
            "durationDetails":[$rows],"sections":[$sections]}
        }}"""),
        ListeningStatsPeriod.Month,
        isCurrent = false,
    )

    private fun json(value: String) = JsonParser.parseString(value).asJsonObject
}
