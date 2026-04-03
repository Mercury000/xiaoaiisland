package dev.lackluster.hyperx.compose.preference

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.lackluster.hyperx.compose.activity.SafeSP
import dev.lackluster.hyperx.compose.base.DrawableResIcon
import dev.lackluster.hyperx.compose.base.ImageIcon
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.preference.SwitchPreference as MiuixSwitchPreference

@Composable
fun SwitchPreference(
    icon: ImageIcon? = null,
    title: String,
    summary: String? = null,
    value: Boolean = false,
    enabled: Boolean = true,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    val updatedOnCheckedChange by rememberUpdatedState(onCheckedChange)

    MiuixSwitchPreference(
        checked = value,
        onCheckedChange = { newValue ->
            updatedOnCheckedChange?.invoke(newValue)
        },
        title = title,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = icon?.let { { DrawableResIcon(it) } },
        enabled = enabled,
    )
}

@Composable
fun SwitchPreference(
    icon: ImageIcon? = null,
    title: String,
    summary: String? = null,
    key: String? = null,
    defValue: Boolean = false,
    enabled: Boolean = true,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    var spValue by remember {
        mutableStateOf(
            key?.let { SafeSP.getBoolean(it, defValue) } ?: defValue
        )
    }
    val updatedOnCheckedChange by rememberUpdatedState(onCheckedChange)

    MiuixSwitchPreference(
        checked = spValue,
        onCheckedChange = { newValue ->
            spValue = newValue
            key?.let { SafeSP.putAny(it, newValue) }
            updatedOnCheckedChange?.invoke(newValue)
        },
        title = title,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = icon?.let { { DrawableResIcon(it) } },
        enabled = enabled,
    )
}

@Composable
fun SwitchPreference(
    icon: ImageIcon? = null,
    title: String,
    summary: String? = null,
    key: String? = null,
    defValue: Boolean = false,
    enabled: Boolean = true,
    checked: MutableState<Boolean>,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    key?.let {
        checked.value = SafeSP.getBoolean(it, defValue)
    }
    val updatedOnCheckedChange by rememberUpdatedState(onCheckedChange)

    MiuixSwitchPreference(
        checked = checked.value,
        onCheckedChange = { newValue ->
            checked.value = newValue
            key?.let { SafeSP.putAny(it, newValue) }
            updatedOnCheckedChange?.invoke(newValue)
        },
        title = title,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = icon?.let { { DrawableResIcon(it) } },
        enabled = enabled,
    )
}

