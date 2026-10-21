package com.ljyh.mei.ui.screen.account

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.R
import com.ljyh.mei.data.model.melox.ListeningStatsBin
import com.ljyh.mei.data.model.melox.ListeningStatsPeriod
import com.ljyh.mei.data.model.melox.ListeningStatsSectionType
import com.ljyh.mei.data.model.melox.ListeningStatsReport
import com.ljyh.mei.data.model.melox.ListeningStatsYear
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.GlassSegmentedControl
import com.ljyh.mei.ui.glass.GlassSurfaceStyle
import com.ljyh.mei.ui.glass.IosAlertButtonRole
import com.ljyh.mei.ui.glass.IosAlertButtonSpec
import com.ljyh.mei.ui.glass.IosAlertDialog
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.LocalGlassDimensions
import com.ljyh.mei.ui.glass.LocalGlassSurfaceBrightness
import com.ljyh.mei.ui.glass.LocalGlassSurfaceStyle
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil

private val ListeningStatsZone = ZoneId.of("Asia/Shanghai")

@Composable
fun ListeningStatsScreen(viewModel: ListeningStatsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val navController = LocalNavController.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val colors = LocalGlassColors.current
    var showInfo by rememberSaveable { mutableStateOf(false) }
    IosPinnedListPage(
        title = stringResource(R.string.listening_stats_title),
        subtitle = state.summary?.let {
            stringResource(R.string.listening_stats_total) + " " + listeningDuration(it.totalSeconds / 60, it.totalSeconds > 0)
        },
        bottomPadding = insets.calculateBottomPadding(),
        onNavigateBack = navController::navigateUp,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        actions = {
            GlassIconButton({ showInfo = true }) {
                SfIcon("info.circle", stringResource(R.string.listening_stats_info_title))
            }
            GlassIconButton(viewModel::refresh, enabled = !state.loading && !state.refreshing) {
                SfIcon(SfSymbol.ArrowClockwise, stringResource(R.string.refresh))
            }
        },
    ) {
        item {
            GlassSegmentedControl(
                items = listOf(
                    ListeningStatsPeriod.Week to stringResource(R.string.listening_stats_week),
                    ListeningStatsPeriod.Month to stringResource(R.string.listening_stats_month),
                    ListeningStatsPeriod.Year to stringResource(R.string.listening_stats_year),
                ),
                selected = state.period,
                onSelected = viewModel::selectPeriod,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.period != ListeningStatsPeriod.Year) {
            item {
                CompositionLocalProvider(
                    LocalGlassSurfaceStyle provides GlassSurfaceStyle.Navigation,
                    LocalGlassSurfaceBrightness provides 1f,
                    LocalContentColor provides colors.content,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        GlassIconButton(viewModel::previous, enabled = state.canGoPrevious && !state.loading) {
                            SfIcon("chevron.left", stringResource(R.string.listening_stats_previous), size = 24.dp,
                                tint = if (state.canGoPrevious && !state.loading) colors.content else colors.tertiaryContent)
                        }
                        Text(
                            state.report?.let(::reportDateRange) ?: stringResource(R.string.listening_stats_duration),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        if (!state.isLatest) {
                            GlassButton(
                                viewModel::current,
                                modifier = Modifier.height(LocalGlassDimensions.current.iconButtonSize),
                            ) {
                                Text(
                                    stringResource(R.string.listening_stats_current),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        GlassIconButton(viewModel::next, enabled = state.canGoNext && !state.loading) {
                            SfIcon("chevron.right", stringResource(R.string.listening_stats_next), size = 24.dp,
                                tint = if (state.canGoNext && !state.loading) colors.content else colors.tertiaryContent)
                        }
                    }
                }
            }
        }
        when {
            state.loading && state.report == null && state.years.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = colors.accent)
                }
            }
            state.report != null -> item {
                ListeningReportCard(checkNotNull(state.report))
            }
            state.period == ListeningStatsPeriod.Year && state.years.isNotEmpty() -> {
                state.years.forEach { year -> item(key = "listening-year:${year.year}") { ListeningYearCard(year) } }
            }
            state.error == null -> item {
                ListeningEmptyCard(state.period == ListeningStatsPeriod.Year)
            }
        }
        state.error?.let {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.listening_stats_error), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.listening_stats_retry_message), color = colors.secondaryContent)
                        GlassButton(viewModel::refresh) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
        }
        if (state.summaryError != null && state.summary == null && !state.loading) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    IosListRow(
                        title = stringResource(R.string.listening_stats_total),
                        detail = stringResource(R.string.listening_stats_load_failed),
                        onClick = viewModel::refresh, showTopSeparator = false,
                    )
                }
            }
        }
        if (state.refreshing) {
            item {
                Text(stringResource(R.string.listening_stats_refreshing), color = colors.secondaryContent,
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
        item {
            Text(
                stringResource(R.string.listening_stats_source),
                color = colors.secondaryContent,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
    if (showInfo) {
        IosAlertDialog(
            onDismissRequest = { showInfo = false },
            title = stringResource(R.string.listening_stats_info_title),
            message = stringResource(R.string.listening_stats_info_message),
            buttons = listOf(IosAlertButtonSpec(stringResource(R.string.done), { showInfo = false }, IosAlertButtonRole.Cancel)),
        )
    }
}

@Composable
private fun ListeningReportCard(report: ListeningStatsReport) {
    val colors = LocalGlassColors.current
    var showDays by rememberSaveable(report.period, report.startTimeMs, report.endTimeMs) { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.listening_stats_duration), color = colors.secondaryContent,
                    style = MaterialTheme.typography.bodyMedium)
                Text(listeningDuration(report.totalMinutes), fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.listening_stats_days, report.listenedDays), color = colors.secondaryContent,
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (report.bins.any { it.durationMinutes > 0 }) {
                ListeningDurationChart(report.bins, report.period, Modifier.padding(top = 24.dp, start = 16.dp, end = 16.dp))
                Text(stringResource(R.string.listening_stats_chart_hint), style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryContent, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
                ListeningReportSections(report)
                IosListRow(
                    title = stringResource(if (showDays) R.string.listening_stats_hide_days else R.string.listening_stats_show_days),
                    systemName = "calendar",
                    onClick = { showDays = !showDays },
                    trailing = { SfIcon(if (showDays) "chevron.up" else "chevron.down", null, size = 12.dp, tint = colors.secondaryContent) },
                )
                if (showDays) {
                    report.bins.forEach { bin ->
                        IosListRow(title = binDateLabel(bin), detail = listeningDuration(bin.durationMinutes))
                    }
                }
            } else {
                Text(stringResource(R.string.listening_stats_empty_message), color = colors.secondaryContent,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp))
            }
        }
    }
}

@Composable
private fun ListeningReportSections(report: ListeningStatsReport) {
    report.sections.forEach { section ->
        val title = when (section.type) {
            ListeningStatsSectionType.LateSleep -> R.string.listening_stats_late
            ListeningStatsSectionType.EarlySleep -> R.string.listening_stats_early
            ListeningStatsSectionType.DurationIncrease -> if (report.period == ListeningStatsPeriod.Week) R.string.listening_stats_more_week else R.string.listening_stats_more_month
            ListeningStatsSectionType.DurationDecrease -> if (report.period == ListeningStatsPeriod.Week) R.string.listening_stats_less_week else R.string.listening_stats_less_month
            ListeningStatsSectionType.LikedSongsPercent -> R.string.listening_stats_liked
            ListeningStatsSectionType.RecommendedSongsPercent -> R.string.listening_stats_recommended
        }
        val detail = when (section.type) {
            ListeningStatsSectionType.LateSleep, ListeningStatsSectionType.EarlySleep ->
                Instant.ofEpochMilli(section.value).atZone(ListeningStatsZone).format(DateTimeFormatter.ofPattern("HH:mm"))
            ListeningStatsSectionType.DurationIncrease, ListeningStatsSectionType.DurationDecrease ->
                listeningDuration(checkNotNull(section.durationChangeMinutes), section.value > 0)
            ListeningStatsSectionType.LikedSongsPercent, ListeningStatsSectionType.RecommendedSongsPercent ->
                NumberFormat.getPercentInstance().format(section.value / 100.0)
        }
        IosListRow(title = stringResource(title), detail = detail)
    }
}

@Composable
private fun ListeningDurationChart(bins: List<ListeningStatsBin>, period: ListeningStatsPeriod, modifier: Modifier = Modifier) {
    val colors = LocalGlassColors.current
    var selected by remember(bins) { mutableIntStateOf(bins.indices.maxByOrNull { bins[it].durationMinutes } ?: 0) }
    val selectedBin = bins[selected.coerceIn(bins.indices)]
    val selectedDate = binDateLabel(selectedBin)
    val selectedDuration = listeningDuration(selectedBin.durationMinutes)
    val chartDescription = stringResource(R.string.listening_stats_chart_summary, selectedDate, selectedDuration)
    val previousDay = stringResource(R.string.listening_stats_previous_day)
    val nextDay = stringResource(R.string.listening_stats_next_day)
    val maximum = bins.maxOf { it.durationMinutes }.coerceAtLeast(1)
    val step = when {
        maximum <= 30 -> 10L
        maximum <= 60 -> 30L
        maximum <= 180 -> 60L
        maximum <= 600 -> 120L
        else -> 300L
    }
    val ceiling = (ceil(maximum.toDouble() / step) * step).toLong().coerceAtLeast(2)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(selectedDate, style = MaterialTheme.typography.bodyMedium, color = colors.secondaryContent)
                Text(selectedDuration, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colors.accent)
            }
            ListeningDayArrow(
                systemName = "chevron.left",
                description = previousDay,
                enabled = selected > 0,
                onClick = { selected = (selected - 1).coerceAtLeast(0) },
            )
            ListeningDayArrow(
                systemName = "chevron.right",
                description = nextDay,
                enabled = selected < bins.lastIndex,
                onClick = { selected = (selected + 1).coerceAtMost(bins.lastIndex) },
            )
        }
        Row(Modifier.fillMaxWidth().height(174.dp)) {
            Column(Modifier.width(44.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                listOf(ceiling, ceiling / 2, 0L).forEach {
                    Text(chartAxisLabel(it), style = MaterialTheme.typography.labelSmall, color = colors.secondaryContent)
                }
            }
            Canvas(
                Modifier.weight(1f).fillMaxSize()
                    .clearAndSetSemantics {
                        contentDescription = chartDescription
                        customActions = listOf(
                            CustomAccessibilityAction(previousDay) { if (selected > 0) { selected--; true } else false },
                            CustomAccessibilityAction(nextDay) { if (selected < bins.lastIndex) { selected++; true } else false },
                        )
                    }
                    .pointerInput(bins) {
                        detectTapGestures { selected = (it.x / size.width * bins.size).toInt().coerceIn(bins.indices) }
                    }
                    .pointerInput(bins) {
                        detectHorizontalDragGestures { change, _ ->
                            selected = (change.position.x / size.width * bins.size).toInt().coerceIn(bins.indices)
                        }
                    },
            ) {
                val plotHeight = size.height - 10.dp.toPx()
                val top = 5.dp.toPx()
                listOf(0f, 0.5f, 1f).forEach { fraction ->
                    drawLine(colors.separator.copy(alpha = 0.6f), Offset(0f, top + fraction * plotHeight),
                        Offset(size.width, top + fraction * plotHeight), 0.5.dp.toPx())
                }
                val slot = size.width / bins.size
                val barWidth = (slot * if (period == ListeningStatsPeriod.Week) 0.56f else 0.65f).coerceAtMost(24.dp.toPx())
                bins.forEachIndexed { index, bin ->
                    val height = (bin.durationMinutes.toFloat() / ceiling * plotHeight).coerceAtLeast(if (bin.durationMinutes > 0) 2.dp.toPx() else 0f)
                    if (height > 0f) {
                        drawRoundRect(
                            color = colors.accent.copy(alpha = if (index == selected) 1f else 0.23f),
                            topLeft = Offset(slot * index + (slot - barWidth) / 2, top + plotHeight - height),
                            size = Size(barWidth, height), cornerRadius = CornerRadius(barWidth / 2),
                        )
                    } else {
                        drawLine(if (index == selected) colors.accent else colors.tertiaryContent,
                            Offset(slot * (index + 0.5f), top + plotHeight - 1.dp.toPx()),
                            Offset(slot * (index + 0.5f), top + plotHeight), 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 44.dp, top = 8.dp).height(20.dp)) {
            val slot = maxWidth / bins.size
            bins.forEachIndexed { index, bin ->
                val label = chartDayLabel(bin, period, index, bins.lastIndex)
                if (label.isNotBlank()) Text(
                    label,
                    modifier = Modifier.width(28.dp).offset(x = slot * (index + 0.5f) - 14.dp),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                    color = if (index == selected) colors.accent else colors.secondaryContent,
                )
            }
        }
    }
}

/** Bare chevrons from the Figma date picker, with full-size touch targets. */
@Composable
private fun ListeningDayArrow(
    systemName: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalGlassColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(LocalGlassDimensions.current.iconButtonSize)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        SfIcon(
            systemName,
            description,
            modifier = Modifier.alpha(if (pressed) 0.55f else 1f),
            size = 24.dp,
            fontSize = 20.sp,
            weight = FontWeight.Medium,
            tint = if (enabled) colors.content else colors.tertiaryContent,
        )
    }
}

@Composable
private fun ListeningYearCard(year: ListeningStatsYear) {
    val colors = LocalGlassColors.current
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(year.year.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                SfIcon("waveform", null, tint = colors.accent, size = 24.dp)
            }
            Text(stringResource(R.string.listening_stats_duration), color = colors.secondaryContent, style = MaterialTheme.typography.bodyMedium)
            Text(listeningDuration(year.totalSeconds / 60, year.totalSeconds > 0), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            year.songCount?.let {
                Text(stringResource(R.string.listening_stats_year_songs, NumberFormat.getIntegerInstance().format(it)), color = colors.secondaryContent)
            }
        }
    }
}

@Composable
private fun ListeningEmptyCard(year: Boolean) {
    val colors = LocalGlassColors.current
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SfIcon("headphones", null, size = 38.dp, tint = colors.secondaryContent)
            Text(stringResource(if (year) R.string.listening_stats_year_empty else R.string.listening_stats_empty), fontWeight = FontWeight.SemiBold)
            Text(stringResource(if (year) R.string.listening_stats_year_empty_message else R.string.listening_stats_empty_message),
                color = colors.secondaryContent, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun listeningDuration(minutes: Long, hasSeconds: Boolean = false): String {
    val number = NumberFormat.getIntegerInstance()
    return when {
        minutes == 0L && hasSeconds -> stringResource(R.string.listening_stats_less_minute)
        minutes < 60 -> stringResource(R.string.listening_stats_minutes, number.format(minutes))
        minutes % 60 == 0L -> stringResource(R.string.listening_stats_hours, number.format(minutes / 60))
        else -> stringResource(R.string.listening_stats_hours_minutes, number.format(minutes / 60), number.format(minutes % 60))
    }
}

@Composable
private fun chartAxisLabel(minutes: Long): String = if (minutes < 60) {
    listeningDuration(minutes)
} else {
    val hours = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(minutes / 60.0)
    stringResource(R.string.listening_stats_hours, hours)
}

private fun reportDateRange(report: ListeningStatsReport): String {
    val start = Instant.ofEpochMilli(report.startTimeMs).atZone(ListeningStatsZone).toLocalDate()
    val end = Instant.ofEpochMilli(report.endTimeMs).atZone(ListeningStatsZone).toLocalDate()
    return if (report.period == ListeningStatsPeriod.Month) {
        start.format(DateTimeFormatter.ofPattern(if (Locale.getDefault().language == "zh") "yyyy年M月" else "MMMM yyyy"))
    } else {
        val pattern = DateTimeFormatter.ofPattern(if (start.year == end.year) "M.d" else "yyyy.M.d")
        "${start.format(pattern)} – ${end.format(pattern)}"
    }
}

private fun binDateLabel(bin: ListeningStatsBin): String = runCatching {
    LocalDate.parse(bin.period).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}.getOrDefault(bin.period)

private fun chartDayLabel(bin: ListeningStatsBin, period: ListeningStatsPeriod, index: Int, lastIndex: Int): String {
    val date = runCatching { LocalDate.parse(bin.period) }.getOrNull() ?: return ""
    return if (period == ListeningStatsPeriod.Week) date.format(DateTimeFormatter.ofPattern("EEEEE"))
    else if (index == 0 || index == lastIndex || (date.dayOfMonth % 5 == 0 && index < lastIndex - 2)) date.dayOfMonth.toString() else ""
}
