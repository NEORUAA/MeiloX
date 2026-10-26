package com.ljyh.mei.ui.component

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.R
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import com.ljyh.mei.utils.VersionUpdateResult

@Composable
fun VersionUpdateAlert(
    result: VersionUpdateResult?,
    onDismiss: () -> Unit,
) {
    if (result == null) return
    val context = LocalContext.current
    when (result) {
        is VersionUpdateResult.UpdateAvailable -> {
            GlasmioAlertDialog(
                onDismissRequest = onDismiss,
                title = stringResource(
                    if (result.comparisonKnown) R.string.about_update_available_title
                    else R.string.about_beta_latest_build_title,
                ),
                message = stringResource(
                    if (result.comparisonKnown) R.string.about_update_available_message
                    else R.string.about_beta_comparison_unavailable_message,
                    result.latestTag,
                    result.currentBuildLabel ?: BuildConfig.VERSION_NAME,
                ),
                buttons = listOf(
                    GlasmioAlertButtonSpec(
                        label = stringResource(R.string.cancel),
                        role = GlasmioAlertButtonRole.Cancel,
                        onClick = onDismiss,
                    ),
                    GlasmioAlertButtonSpec(
                        label = stringResource(R.string.about_open_update),
                        onClick = {
                            onDismiss()
                            runCatching {
                                if (result.isBeta) {
                                    Toast.makeText(
                                        context,
                                        R.string.about_beta_download_login_required,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(result.releaseUrl)),
                                )
                            }
                        },
                    ),
                ),
            )
        }

        VersionUpdateResult.UpToDate -> {
            GlasmioAlertDialog(
                onDismissRequest = onDismiss,
                title = stringResource(R.string.about_update_up_to_date_title),
                message = stringResource(
                    R.string.about_update_up_to_date_message,
                    BuildConfig.VERSION_NAME,
                ),
                buttons = listOf(
                    GlasmioAlertButtonSpec(
                        label = stringResource(R.string.done),
                        role = GlasmioAlertButtonRole.Cancel,
                        onClick = onDismiss,
                    ),
                ),
            )
        }

        VersionUpdateResult.Failed -> {
            GlasmioAlertDialog(
                onDismissRequest = onDismiss,
                title = stringResource(R.string.about_update_failed_title),
                message = stringResource(R.string.about_update_failed_message),
                buttons = listOf(
                    GlasmioAlertButtonSpec(
                        label = stringResource(R.string.done),
                        role = GlasmioAlertButtonRole.Cancel,
                        onClick = onDismiss,
                    ),
                ),
            )
        }
    }
}
