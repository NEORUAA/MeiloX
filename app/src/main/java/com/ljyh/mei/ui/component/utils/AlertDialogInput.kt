package com.ljyh.mei.ui.component.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioAlertFieldGroup
import io.github.neoruaa.glasmio.ui.GlasmioAlertTextField
import io.github.neoruaa.glasmio.core.LocalGlassColors


@Composable
fun AlertDialogInput(
    showDialog: Boolean,
    title: String,
    label:String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var inputText by remember { mutableStateOf(TextFieldValue()) }

    if (showDialog) {
        GlasmioAlertDialog(
            onDismissRequest = onDismiss,
            title = title,
            buttons = listOf(
                GlasmioAlertButtonSpec(
                    label = stringResource(android.R.string.cancel),
                    onClick = onDismiss,
                    role = GlasmioAlertButtonRole.Cancel,
                ),
                GlasmioAlertButtonSpec(
                    label = stringResource(android.R.string.ok),
                    onClick = {
                        onConfirm(inputText.text)
                        onDismiss()
                    },
                ),
            ),
        ) {
            GlasmioAlertFieldGroup {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(LocalGlassColors.current.separator),
                )
                GlasmioAlertTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = label,
                )
            }
        }
    }
}
