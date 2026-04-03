package dev.lackluster.hyperx.compose.base

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lackluster.hyperx.compose.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun AlertDialog(
    visible: Boolean = true,
    title: String?,
    message: String? = null,
    cancelable: Boolean = true,
    renderInRootScaffold: Boolean = true,
    mode: AlertDialogMode = AlertDialogMode.Positive,
    negativeText: String = stringResource(R.string.button_cancel),
    positiveText: String = stringResource(R.string.button_ok),
    onDismissRequest: (() -> Unit)? = null,
    onNegativeButton: (() -> Unit)? = null,
    onPositiveButton: (() -> Unit)? = null,
) {
    val hapticFeedback = LocalHapticFeedback.current
    OverlayDialog(
        show = visible,
        title = title,
        summary = message,
        renderInRootScaffold = renderInRootScaffold,
        onDismissRequest = {
            if (cancelable) {
                onDismissRequest?.invoke()
            }
        },
        content = {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (mode != AlertDialogMode.Positive) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = negativeText,
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            onNegativeButton?.invoke() ?: onDismissRequest?.invoke()
                        }
                    )
                }
                if (mode == AlertDialogMode.NegativeAndPositive) {
                    Spacer(Modifier.width(20.dp))
                }
                if (mode != AlertDialogMode.Negative) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = positiveText,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            onPositiveButton?.invoke() ?: onDismissRequest?.invoke()
                        }
                    )
                }
            }
        }
    )
}

@Composable
fun AlertDialog(
    visibility: MutableState<Boolean>,
    title: String?,
    message: String? = null,
    cancelable: Boolean = true,
    renderInRootScaffold: Boolean = true,
    mode: AlertDialogMode = AlertDialogMode.Positive,
    negativeText: String = stringResource(R.string.button_cancel),
    positiveText: String = stringResource(R.string.button_ok),
    onNegativeButton: (() -> Unit)? = null,
    onPositiveButton: (() -> Unit)? = null,
) {
    val hapticFeedback = LocalHapticFeedback.current
    OverlayDialog(
        show = visibility.value,
        title = title,
        summary = message,
        renderInRootScaffold = renderInRootScaffold,
        onDismissRequest = {
            if (cancelable) {
                visibility.value = false
            }
        },
        content = {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (mode != AlertDialogMode.Positive) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = negativeText,
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            onNegativeButton?.let { it1 -> it1() } ?: visibility.apply { value = false }
                        }
                    )
                }
                if (mode == AlertDialogMode.NegativeAndPositive) {
                    Spacer(Modifier.width(20.dp))
                }
                if (mode != AlertDialogMode.Negative) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = positiveText,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            onPositiveButton?.let { it1 -> it1() } ?: visibility.apply { value = false }
                        }
                    )
                }
            }
        }
    )
}

enum class AlertDialogMode {
    Negative,
    Positive,
    NegativeAndPositive,
}
