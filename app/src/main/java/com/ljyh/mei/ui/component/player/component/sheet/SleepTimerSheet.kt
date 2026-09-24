package com.ljyh.mei.ui.component.player.component.sheet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.ljyh.mei.R
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.playback.SleepTimer
import com.ljyh.mei.playback.SleepTimerNotification
import com.ljyh.mei.playback.SleepTimerState
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.IosGroupedList
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosModalSheet
import com.ljyh.mei.ui.glass.IosSheetTopToolbar
import com.ljyh.mei.ui.glass.IosSheetTopToolbarButton
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.LocalGroupedListBackgroundAlpha
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.utils.TimeUtils.makeTimeString
import java.util.Date

@Composable
fun SleepTimerSheet(
    playerConnection: PlayerConnection,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val timer = playerConnection.service.sleepTimer
    val notification = playerConnection.service.sleepTimerNotification
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val state = timer.state
    val active = timer.isActive
    val activeOption = when (state) {
        is SleepTimerState.Countdown -> state.minutes
        SleepTimerState.EndOfTrack -> SleepTimer.END_OF_TRACK
        SleepTimerState.Off -> null
    }
    var selectedOption by remember(state) { mutableIntStateOf(activeOption ?: 30) }
    var notificationsEnabled by remember { mutableStateOf(notification.isEnabled()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsEnabled = notification.isEnabled()
        notification.update(timer.state)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsEnabled = notification.isEnabled()
        notification.update(timer.state)
    }

    IosModalSheet(onDismissRequest = onDismiss) {
        val colors = LocalGlassColors.current
        val buttonTint = colors.elevatedBackground.copy(alpha = LocalGroupedListBackgroundAlpha.current)
        IosSheetTopToolbar(
            title = stringResource(R.string.more_action_sleep_timer),
            actions = {
                IosSheetTopToolbarButton(onClick = onDismiss) {
                    SfIcon("xmark", stringResource(R.string.sleep_timer_close), size = 18.dp)
                }
            },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SleepTimerStatus(timer, selectedOption, mediaMetadata?.title)
            Text(
                stringResource(if (active) R.string.sleep_timer_adjust else R.string.sleep_timer_duration),
                style = IosTypography.subheadline,
                color = colors.secondaryContent,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(listOf(5, 10, 15), listOf(30, 45, 60)).forEach { options ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        options.forEach { minutes ->
                            val selected = selectedOption == minutes
                            GlassButton(
                                onClick = { selectedOption = minutes },
                                modifier = Modifier.weight(1f).semantics { this.selected = selected },
                                backdrop = emptyBackdrop(),
                                navigationSurfaceColor = if (selected) colors.accent else buttonTint,
                                emphasis = if (selected) GlassEmphasis.Prominent else GlassEmphasis.Regular,
                            ) {
                                Text(
                                    stringResource(R.string.sleep_timer_minutes, minutes),
                                    style = IosTypography.subheadline,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
                IosGroupedList {
                    val selected = selectedOption == SleepTimer.END_OF_TRACK
                    IosListRow(
                        title = stringResource(R.string.sleep_timer_end_of_track),
                        systemName = "music.note",
                        modifier = Modifier.semantics { this.selected = selected },
                        showTopSeparator = false,
                        onClick = { selectedOption = SleepTimer.END_OF_TRACK },
                        trailing = {
                            SfIcon(
                                if (selected) "checkmark.circle.fill" else "circle",
                                null,
                                tint = if (selected) colors.accent else colors.tertiaryContent,
                                size = 22.dp,
                            )
                        },
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(
                    onClick = {
                        timer.start(selectedOption)
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    enabled = !active || selectedOption != activeOption,
                    modifier = Modifier.fillMaxWidth(),
                    backdrop = emptyBackdrop(),
                    navigationSurfaceColor = colors.accent,
                    emphasis = GlassEmphasis.Prominent,
                ) {
                    Text(
                        stringResource(when {
                            !active -> R.string.sleep_timer_start
                            selectedOption == activeOption -> R.string.sleep_timer_running
                            else -> R.string.sleep_timer_update
                        }),
                        style = IosTypography.headline,
                    )
                }
                if (active) {
                    GlassButton(
                        onClick = timer::clear,
                        modifier = Modifier.fillMaxWidth(),
                        backdrop = emptyBackdrop(),
                        navigationSurfaceColor = buttonTint,
                    ) {
                        Text(stringResource(R.string.sleep_timer_cancel), style = IosTypography.body)
                    }
                }
            }
            if (active && !notificationsEnabled) {
                IosGroupedList {
                    IosListRow(
                        title = stringResource(R.string.sleep_timer_notification_disabled),
                        subtitle = stringResource(R.string.sleep_timer_notification_hint),
                        systemName = "bell.slash",
                        showTopSeparator = false,
                        onClick = {
                            val permissionGranted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) == PackageManager.PERMISSION_GRANTED
                            val settings = if (permissionGranted) {
                                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_CHANNEL_ID, SleepTimerNotification.CHANNEL_ID)
                            } else {
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            }
                            context.startActivity(settings.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepTimerStatus(timer: SleepTimer, selectedOption: Int, trackTitle: String?) {
    val colors = LocalGlassColors.current
    val context = LocalContext.current
    val state = timer.state
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SfIcon("moon.fill", null, size = 18.dp, tint = colors.accent)
            Text(
                stringResource(if (timer.isActive) R.string.sleep_timer_active else R.string.sleep_timer_inactive),
                style = IosTypography.subheadline,
                color = colors.secondaryContent,
            )
        }
        if (state is SleepTimerState.Countdown) {
            Text(
                makeTimeString(((timer.remainingMillis + 999L) / 1_000L) * 1_000L),
                style = IosTypography.largeTitle.copy(
                    fontSize = 48.sp,
                    lineHeight = 56.sp,
                    fontWeight = FontWeight.Light,
                    fontFeatureSettings = "tnum",
                ),
                color = colors.content,
            )
            Text(
                stringResource(
                    R.string.sleep_timer_stops_at,
                    DateFormat.getTimeFormat(context).format(Date(
                        System.currentTimeMillis() +
                            (state.deadlineElapsedRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
                    )),
                ),
                style = IosTypography.subheadline,
                color = colors.secondaryContent,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                stringResource(if (state == SleepTimerState.EndOfTrack) R.string.sleep_timer_track_status else R.string.sleep_timer_title),
                style = IosTypography.title2,
                color = colors.content,
                textAlign = TextAlign.Center,
            )
            Text(
                when {
                    state == SleepTimerState.EndOfTrack ->
                        trackTitle ?: stringResource(R.string.sleep_timer_end_of_track)
                    selectedOption == SleepTimer.END_OF_TRACK ->
                        stringResource(R.string.sleep_timer_end_of_track)
                    else -> stringResource(R.string.sleep_timer_stops_in, selectedOption)
                },
                style = IosTypography.subheadline,
                color = colors.secondaryContent,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
