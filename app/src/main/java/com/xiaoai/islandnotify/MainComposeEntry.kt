package com.xiaoai.islandnotify

import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.lackluster.hyperx.compose.base.Card
import dev.lackluster.hyperx.compose.base.CardDefaults
import dev.lackluster.hyperx.compose.base.AlertDialog as HyperAlertDialog
import dev.lackluster.hyperx.compose.base.AlertDialogMode
import dev.lackluster.hyperx.compose.component.Hint
import dev.lackluster.hyperx.compose.preference.DropDownEntry
import dev.lackluster.hyperx.compose.preference.DropDownMode
import dev.lackluster.hyperx.compose.preference.DropDownPreference
import dev.lackluster.hyperx.compose.preference.EditTextDialog
import dev.lackluster.hyperx.compose.preference.PreferenceGroup
import dev.lackluster.hyperx.compose.preference.SwitchPreference
import dev.lackluster.hyperx.compose.preference.TextPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.ThemeController

object MainComposeEntry {

    @JvmStatic
    fun install(activity: MainActivity) {
        activity.setContent {
            val themeController = remember {
                ThemeController(
                    colorSchemeMode = ColorSchemeMode.System,
                )
            }
            MiuixTheme(
                controller = themeController,
                smoothRounding = false,
            ) {
                MainComposeApp(activity)
            }
        }
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateCompat(): State<T> = collectAsState()

private data class StageCustomState(
    var tplA: String = "",
    var tplB: String = "",
    var tplTicker: String = "",
    var baseTitle: String = "",
    var hintTitle: String = "",
    var hintSubtitle: String = "",
    var hintContent: String = "",
    var hintSubcontent: String = "",
    var baseContent: String = "",
    var baseSubcontent: String = "",
)

private data class TimeoutUiState(
    val islandVals: MutableList<Int> = mutableListOf(-1, -1, -1),
    val islandUnits: MutableList<String> = mutableListOf("m", "m", "m"),
    val notifVals: MutableList<Int> = mutableListOf(-1, -1, -1),
    val notifUnits: MutableList<String> = mutableListOf("m", "m", "m"),
    var notifTriggerStage: Int = 0,
    var notifGlobalDefault: Boolean = true,
)

private data class WakeRule(
    var sec: String,
    var hour: String,
    var minute: String,
)

private data class HolidayDraft(
    var date: String,
    var endDate: String = "",
    var name: String = "",
)

private data class WorkSwapDraft(
    var date: String,
    var name: String = "",
    var followWeek: Int = 1,
    var followWeekday: Int = 1,
)

private object MaterialTheme {
    val colorScheme: ColorSchemeCompat
        @Composable get() = ColorSchemeCompat(MiuixTheme.colorScheme)

    val typography: TypographyCompat
        @Composable get() = TypographyCompat(MiuixTheme.textStyles)
}

private class ColorSchemeCompat(private val colors: Colors) {
    val primary: Color get() = colors.primary
    val primaryContainer: Color get() = colors.primaryContainer
    val onPrimaryContainer: Color get() = colors.onPrimaryContainer
    val secondaryContainer: Color get() = colors.secondaryContainer
    val onSecondaryContainer: Color get() = colors.onSecondaryContainer
    val errorContainer: Color get() = colors.errorContainer
    val onErrorContainer: Color get() = colors.onErrorContainer
    val surfaceContainer: Color get() = colors.surfaceContainer
    val onSurfaceContainer: Color get() = colors.onSurfaceContainer
    val surfaceContainerHigh: Color get() = colors.surfaceContainerHigh
    val onSurfaceVariant: Color get() = colors.onSurfaceContainerVariant
}

private class TypographyCompat(private val styles: TextStyles) {
    val titleMedium: TextStyle get() = styles.title4
    val labelLarge: TextStyle get() = styles.subtitle
    val labelMedium: TextStyle get() = styles.body2.copy(fontWeight = FontWeight.SemiBold)
    val bodyMedium: TextStyle get() = styles.main
    val bodySmall: TextStyle get() = styles.body2
}

private data class EditDialogSpec(
    val title: String,
    val initialValue: String,
    val numberOnly: Boolean = false,
    val onConfirm: (String) -> Unit,
)

private sealed interface AppRoute : androidx.navigation3.runtime.NavKey {
    data object TestNotify : AppRoute
    data object StatusCustom : AppRoute
    data object ExpandedCustom : AppRoute
    data object Timeout : AppRoute
    data object Reminder : AppRoute
    data object Mute : AppRoute
    data object Wakeup : AppRoute
    data object Holiday : AppRoute
    data object About : AppRoute
}

@Composable
private fun EditValueDialog(spec: EditDialogSpec, onDismiss: () -> Unit) {
    var closed by remember(spec) { mutableStateOf(false) }
    val visibility = remember(spec) { mutableStateOf(true) }
    EditTextDialog(
        visibility = visibility,
        title = spec.title,
        value = spec.initialValue,
        onInputConfirm = { raw ->
            val text = if (spec.numberOnly) raw.filter(Char::isDigit) else raw
            spec.onConfirm(text.trim())
            if (!closed) {
                closed = true
                onDismiss()
            }
        }
    )
    LaunchedEffect(visibility.value) {
        if (!visibility.value && !closed) {
            closed = true
            onDismiss()
        }
    }
}

@Composable
private fun MainComposeApp(activity: MainActivity) {
    val refreshTick by ComposeRefreshBus.tick.collectAsStateCompat()
    val settingsState = remember { SettingsComposeState() }
    val holidayState = remember { HolidayComposeState() }
    val aboutState = remember { AboutComposeState() }

    LaunchedEffect(refreshTick) {
        settingsState.loadFrom(activity)
        holidayState.loadFrom(activity)
        aboutState.loadFrom(activity)
    }

    dev.lackluster.hyperx.compose.base.HyperXApp(
        mainPageContent = { navigator, _, _ ->
            RouteScaffold(
                title = "课程表超级岛",
                canBack = false,
                onBack = {},
            ) { pageModifier ->
                HomeEntryPage(
                    modifier = pageModifier,
                    state = settingsState,
                    onOpen = { route -> navigator.push(route) },
                    onResetConfirmed = {
                        val count = activity.uiResetAllConfigToDefaults()
                        Toast.makeText(activity, "已恢复默认配置：$count 项", Toast.LENGTH_SHORT).show()
                        activity.requestComposeRefresh()
                    },
                )
            }
        },
        otherPageEntryProvider = { key, navigator, _, _ ->
            androidx.navigation3.runtime.NavEntry(key) {
                when (key) {
                    is AppRoute.TestNotify -> RouteScaffold(
                        title = "测试通知",
                        canBack = true,
                        onBack = { navigator.pop() },
                    ) { pageModifier ->
                        SingleCardPage(modifier = pageModifier) {
                            TestNotifyCard(activity = activity, state = settingsState)
                        }
                    }
                    is AppRoute.StatusCustom -> RouteScaffold(
                        title = "状态栏岛自定义",
                        canBack = true,
                        onBack = { navigator.pop() },
                    ) { pageModifier ->
                        StatusCustomPage(
                            activity = activity,
                            state = settingsState,
                            modifier = pageModifier,
                        )
                    }
                    is AppRoute.ExpandedCustom -> RouteScaffold(
                        title = "展开态自定义",
                        canBack = true,
                        onBack = { navigator.pop() },
                    ) { pageModifier ->
                        ExpandedCustomPage(
                            activity = activity,
                            state = settingsState,
                            modifier = pageModifier,
                        )
                    }
                    is AppRoute.Timeout -> RouteScaffold(
                        title = "消失时间",
                        canBack = true,
                        onBack = { navigator.pop() },
                    ) { pageModifier ->
                        SingleCardPage(modifier = pageModifier) {
                            TimeoutCard(activity = activity, state = settingsState)
                        }
                    }
                    is AppRoute.Reminder -> RouteScaffold(
                        title = "课前提醒",
                        canBack = true,
                        onBack = { navigator.pop() },
                    ) { pageModifier ->
                        SingleCardPage(modifier = pageModifier) {
                            ReminderCard(activity = activity, state = settingsState)
                        }
                    }
                    is AppRoute.Mute -> RouteScaffold(
                        title = "上课免打扰",
                        canBack = true,
                        onBack = { navigator.pop() },
                    ) { pageModifier ->
                        SingleCardPage(modifier = pageModifier) {
                            MuteCard(activity = activity, state = settingsState)
                        }
                    }
                    is AppRoute.Wakeup -> RouteScaffold(
                        title = "自动叫醒",
                        canBack = true,
                        onBack = { navigator.pop() },
                    ) { pageModifier ->
                        SingleCardPage(modifier = pageModifier) {
                            WakeupCard(activity = activity, state = settingsState)
                        }
                    }
                    is AppRoute.Holiday -> RouteScaffold(
                        title = "假期/调休",
                        canBack = true,
                        onBack = { navigator.pop() },
                    ) { pageModifier ->
                        HolidayTab(
                            activity = activity,
                            state = holidayState,
                            modifier = pageModifier,
                        )
                    }
                    is AppRoute.About -> RouteScaffold(
                        title = "关于",
                        canBack = true,
                        onBack = { navigator.pop() },
                    ) { pageModifier ->
                        AboutTab(
                            activity = activity,
                            state = aboutState,
                            modifier = pageModifier,
                        )
                    }
                    else -> {}
                }
            }
        },
    )
}

@Composable
private fun RouteScaffold(
    title: String,
    canBack: Boolean,
    onBack: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                title = title,
                navigationIcon = {
                    if (canBack) {
                        top.yukonga.miuix.kmp.basic.IconButton(
                            modifier = Modifier.size(40.dp),
                            onClick = onBack,
                        ) {
                            top.yukonga.miuix.kmp.basic.Icon(
                                modifier = Modifier.size(24.dp),
                                imageVector = MiuixIcons.Back,
                                contentDescription = "Back",
                                tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                defaultWindowInsetsPadding = false,
                horizontalPadding = 28.dp,
            )
        },
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Vertical),
    ) { innerPadding ->
        content(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        )
    }
}

private class SettingsComposeState {
    var frameworkActive by mutableStateOf(false)
    var frameworkDesc by mutableStateOf("")
    var courseName by mutableStateOf("高等数学")
    var classroom by mutableStateOf("教科A-101")
    val stageStates = mutableStateListOf(StageCustomState(), StageCustomState(), StageCustomState())
    var iconAEnabled by mutableStateOf(true)
    var timeoutState by mutableStateOf(TimeoutUiState())
    var reminderMinutes by mutableStateOf("15")
    var repostEnabled by mutableStateOf(true)
    var muteEnabled by mutableStateOf(false)
    var muteMinsBefore by mutableStateOf("0")
    var unmuteEnabled by mutableStateOf(false)
    var unmuteMinsAfter by mutableStateOf("0")
    var dndEnabled by mutableStateOf(false)
    var dndMinsBefore by mutableStateOf("0")
    var undndEnabled by mutableStateOf(false)
    var undndMinsAfter by mutableStateOf("0")
    var islandButtonMode by mutableIntStateOf(0)
    var wakeupMorningEnabled by mutableStateOf(false)
    var wakeupMorningLastSec by mutableStateOf("4")
    val wakeupMorningRules = mutableStateListOf<WakeRule>()
    var wakeupAfternoonEnabled by mutableStateOf(false)
    var wakeupAfternoonFirstSec by mutableStateOf("5")
    val wakeupAfternoonRules = mutableStateListOf<WakeRule>()

    fun loadFrom(activity: MainActivity) {
        frameworkActive = activity.uiFrameworkActive()
        frameworkDesc = activity.uiFrameworkDesc()
        val prefs = activity.uiConfigPrefs()
        val suffixes = ConfigDefaults.STAGE_SUFFIXES
        for (i in suffixes.indices) {
            val suffix = suffixes[i]
            val prev = stageStates[i]
            stageStates[i] = prev.copy(
                tplA = PrefsAccess.readStagedTemplate(prefs, "tpl_a", suffix, ""),
                tplB = PrefsAccess.readStagedTemplate(prefs, "tpl_b", suffix, ""),
                tplTicker = PrefsAccess.readStagedTemplate(prefs, "tpl_ticker", suffix, ""),
                baseTitle = PrefsAccess.readStagedString(
                    prefs,
                    ConfigDefaults.EXPANDED_TPL_KEYS[0],
                    suffix,
                    ConfigDefaults.expandedTemplateDefault(i, 0, ""),
                ),
                hintTitle = PrefsAccess.readStagedString(
                    prefs,
                    ConfigDefaults.EXPANDED_TPL_KEYS[1],
                    suffix,
                    ConfigDefaults.expandedTemplateDefault(i, 1, ""),
                ),
                hintSubtitle = PrefsAccess.readStagedString(
                    prefs,
                    ConfigDefaults.EXPANDED_TPL_KEYS[2],
                    suffix,
                    ConfigDefaults.expandedTemplateDefault(i, 2, ""),
                ),
                hintContent = PrefsAccess.readStagedString(
                    prefs,
                    ConfigDefaults.EXPANDED_TPL_KEYS[3],
                    suffix,
                    ConfigDefaults.expandedTemplateDefault(i, 3, ""),
                ),
                hintSubcontent = PrefsAccess.readStagedString(
                    prefs,
                    ConfigDefaults.EXPANDED_TPL_KEYS[4],
                    suffix,
                    ConfigDefaults.expandedTemplateDefault(i, 4, ""),
                ),
                baseContent = PrefsAccess.readStagedString(
                    prefs,
                    ConfigDefaults.EXPANDED_TPL_KEYS[5],
                    suffix,
                    ConfigDefaults.expandedTemplateDefault(i, 5, ""),
                ),
                baseSubcontent = PrefsAccess.readStagedString(
                    prefs,
                    ConfigDefaults.EXPANDED_TPL_KEYS[6],
                    suffix,
                    ConfigDefaults.expandedTemplateDefault(i, 6, ""),
                ),
            )
        }
        iconAEnabled = PrefsAccess.readConfigBool(prefs, "icon_a", true)
        timeoutState = readTimeoutState(prefs)
        reminderMinutes = PrefsAccess.readConfigInt(prefs, "reminder_minutes_before", 15).toString()
        repostEnabled = PrefsAccess.readConfigBool(prefs, "repost_enabled", true)
        muteEnabled = PrefsAccess.readConfigBool(prefs, "mute_enabled", false)
        muteMinsBefore = PrefsAccess.readConfigInt(prefs, "mute_mins_before", 0).toString()
        unmuteEnabled = PrefsAccess.readConfigBool(prefs, "unmute_enabled", false)
        unmuteMinsAfter = PrefsAccess.readConfigInt(prefs, "unmute_mins_after", 0).toString()
        dndEnabled = PrefsAccess.readConfigBool(prefs, "dnd_enabled", false)
        dndMinsBefore = PrefsAccess.readConfigInt(prefs, "dnd_mins_before", 0).toString()
        undndEnabled = PrefsAccess.readConfigBool(prefs, "undnd_enabled", false)
        undndMinsAfter = PrefsAccess.readConfigInt(prefs, "undnd_mins_after", 0).toString()
        islandButtonMode = PrefsAccess.readConfigInt(prefs, "island_button_mode", 0)
        wakeupMorningEnabled = PrefsAccess.readConfigBool(prefs, "wakeup_morning_enabled", false)
        wakeupMorningLastSec =
            PrefsAccess.readConfigInt(prefs, "wakeup_morning_last_sec", 4).toString()
        wakeupAfternoonEnabled = PrefsAccess.readConfigBool(prefs, "wakeup_afternoon_enabled", false)
        wakeupAfternoonFirstSec =
            PrefsAccess.readConfigInt(prefs, "wakeup_afternoon_first_sec", 5).toString()
        wakeupMorningRules.clear()
        wakeupMorningRules.addAll(parseWakeRules(PrefsAccess.readConfigString(
            prefs,
            "wakeup_morning_rules_json",
            ConfigDefaults.WAKEUP_MORNING_RULES_JSON,
        )))
        wakeupAfternoonRules.clear()
        wakeupAfternoonRules.addAll(parseWakeRules(PrefsAccess.readConfigString(
            prefs,
            "wakeup_afternoon_rules_json",
            ConfigDefaults.WAKEUP_AFTERNOON_RULES_JSON,
        )))
    }
}

private class HolidayComposeState {
    var year by mutableIntStateOf(Calendar.getInstance().get(Calendar.YEAR))
    var fetchHint by mutableStateOf("")
    val holidayEntries = mutableStateListOf<HolidayManager.HolidayEntry>()
    val workswapEntries = mutableStateListOf<HolidayManager.HolidayEntry>()

    fun loadFrom(activity: MainActivity) {
        val all = HolidayManager.loadEntries(activity, year)
        holidayEntries.clear()
        workswapEntries.clear()
        all.forEach {
            if (it.type == HolidayManager.TYPE_HOLIDAY) holidayEntries += it else workswapEntries += it
        }
    }
}

private class AboutComposeState {
    var version by mutableStateOf("未知版本")
    var hideIcon by mutableStateOf(false)

    fun loadFrom(activity: MainActivity) {
        version = activity.uiReadAppVersionName()
        hideIcon = activity.uiIsHideIconEnabled()
    }
}

@Composable
private fun SingleCardPage(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { content() }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun HomeEntryPage(
    modifier: Modifier = Modifier,
    state: SettingsComposeState,
    onOpen: (AppRoute) -> Unit,
    onResetConfirmed: () -> Unit,
) {
    var showResetDialog by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatusCardView(
                active = state.frameworkActive,
                frameworkDesc = state.frameworkDesc,
            )
        }
        item {
            PreferenceGroup(first = true) {
                TextPreference(
                    title = "测试通知",
                    summary = "发送模拟课程提醒，快速确认超级岛显示效果",
                ) { onOpen(AppRoute.TestNotify) }
            }
        }
        item {
            PreferenceGroup {
                TextPreference(
                    title = "状态栏岛自定义",
                    summary = "按上课前/中/后三个阶段分别配置岛A/岛B与息屏展示",
                ) { onOpen(AppRoute.StatusCustom) }
                TextPreference(
                    title = "展开态自定义",
                    summary = "按上课前/中/后三个阶段配置展开态全部文本模板",
                ) { onOpen(AppRoute.ExpandedCustom) }
                TextPreference(
                    title = "消失时间",
                    summary = "分别管理岛消息与通知消息的消失时间和阶段触发",
                ) { onOpen(AppRoute.Timeout) }
                TextPreference(
                    title = "课前提醒",
                    summary = "配置提前提醒分钟数与重发策略",
                ) { onOpen(AppRoute.Reminder) }
                TextPreference(
                    title = "上课免打扰",
                    summary = "课程前后自动静音、恢复与勿扰切换",
                ) { onOpen(AppRoute.Mute) }
                TextPreference(
                    title = "自动叫醒",
                    summary = "课前自动唤醒屏幕，支持上午/下午规则",
                ) { onOpen(AppRoute.Wakeup) }
            }
        }
        item {
            PreferenceGroup(last = true) {
                TextPreference(
                    title = "全局恢复默认",
                    summary = "一键恢复模块默认配置（本地 + RemotePrefs）",
                    onClick = { showResetDialog = true },
                )
                TextPreference(
                    title = "假期/调休",
                    summary = "管理节假日、补课与周次跟随规则",
                ) { onOpen(AppRoute.Holiday) }
                TextPreference(
                    title = "关于",
                    summary = "查看版本、项目地址与作者信息",
                ) { onOpen(AppRoute.About) }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }

    HyperAlertDialog(
        visible = showResetDialog,
        title = "恢复默认",
        message = "将清空所有配置（本地 + LSPosed RemotePrefs）并恢复默认值，是否继续？",
        cancelable = false,
        mode = AlertDialogMode.NegativeAndPositive,
        negativeText = "取消",
        positiveText = "恢复",
        onDismissRequest = { showResetDialog = false },
        onNegativeButton = { showResetDialog = false },
        onPositiveButton = {
            showResetDialog = false
            onResetConfirmed()
        },
    )
}

@Composable
private fun StatusCustomPage(
    activity: MainActivity,
    state: SettingsComposeState,
    modifier: Modifier = Modifier,
) {
    var editDialog by remember { mutableStateOf<EditDialogSpec?>(null) }
    val stageLabels = remember { listOf("上课前", "上课中", "下课后") }

    fun persistStatusConfig() {
        alignExpandedTimerWithStatus(state.stageStates)
        val editor = activity.uiEditConfigPrefs()
        ConfigDefaults.STAGE_SUFFIXES.forEachIndexed { idx, suffix ->
            val stageItem = state.stageStates[idx]
            editor.putString("tpl_a$suffix", stageItem.tplA.trim())
            editor.putString("tpl_b$suffix", stageItem.tplB.trim())
            editor.putString("tpl_ticker$suffix", stageItem.tplTicker.trim())
            editor.putString("tpl_hint_title$suffix", stageItem.hintTitle.trim())
            editor.putString("tpl_hint_subtitle$suffix", stageItem.hintSubtitle.trim())
        }
        editor.putBoolean("icon_a", state.iconAEnabled)
        editor.apply()
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Hint(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
                text = "可用变量：{课名} {开始} {结束} {教室} {节次} {教师} {倒计时} {正计时}",
            )
        }
        item {
            DismissibleHint(
                activity = activity,
                key = "hint_status_custom_timer_rule",
                text = "状态栏岛仅岛B支持计时变量，计时变量需放在开头，可在后面拼接文本；上课前不支持{正计时}，下课后不支持{倒计时}。",
            )
        }
        item {
            DismissibleHint(
                activity = activity,
                key = "hint_status_custom_conflict",
                text = "保存时会做同阶段计时冲突校验：保存状态栏岛时会将展开态主要小文本1/2对齐到状态栏岛B；保存展开态时会将状态栏岛B对齐到展开态。同阶段展开态与状态栏岛B只能保留一种计时类型（正计时或倒计时）。",
            )
        }
        items(stageLabels.indices.toList()) { i ->
            val stage = state.stageStates[i]
            val label = stageLabels[i]
            Column(modifier = Modifier.fillMaxWidth()) {
                PreferenceGroup(
                    title = label,
                    first = i == 0,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                    ) {
                        TextPreference(
                            title = "岛A（左侧文字）",
                            value = stage.tplA.ifBlank { "未设置" },
                            onClick = {
                                editDialog = EditDialogSpec(
                                    title = "$label - 岛A（左侧文字）",
                                    initialValue = stage.tplA,
                                    onConfirm = {
                                        state.stageStates[i] = state.stageStates[i].copy(tplA = it)
                                        persistStatusConfig()
                                    },
                                )
                            },
                        )
                        HorizontalDivider()
                        TextPreference(
                            title = "岛B（右侧文字）",
                            value = stage.tplB.ifBlank { "未设置" },
                            onClick = {
                                editDialog = EditDialogSpec(
                                    title = "$label - 岛B（右侧文字）",
                                    initialValue = stage.tplB,
                                    onConfirm = {
                                        state.stageStates[i] = state.stageStates[i].copy(tplB = it)
                                        persistStatusConfig()
                                    },
                                )
                            },
                        )
                        HorizontalDivider()
                        TextPreference(
                            title = "息屏显示",
                            value = stage.tplTicker.ifBlank { "未设置" },
                            onClick = {
                                editDialog = EditDialogSpec(
                                    title = "$label - 息屏显示",
                                    initialValue = stage.tplTicker,
                                    onConfirm = {
                                        state.stageStates[i] = state.stageStates[i].copy(tplTicker = it)
                                        persistStatusConfig()
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
        item {
            PreferenceGroup(last = true) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                ) {
                    SwitchPreference(
                        title = "岛A显示图标",
                        value = state.iconAEnabled,
                        onCheckedChange = {
                            state.iconAEnabled = it
                            persistStatusConfig()
                        },
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
    editDialog?.let { spec ->
        EditValueDialog(spec = spec, onDismiss = { editDialog = null })
    }
}

@Composable
private fun ExpandedCustomPage(
    activity: MainActivity,
    state: SettingsComposeState,
    modifier: Modifier = Modifier,
) {
    var editDialog by remember { mutableStateOf<EditDialogSpec?>(null) }
    val sectionTitles = remember { listOf("上课前", "上课中", "下课后") }

    fun persistExpandedConfig() {
        alignStatusTimerWithExpanded(state.stageStates)
        val editor = activity.uiEditConfigPrefs()
        ConfigDefaults.STAGE_SUFFIXES.forEachIndexed { idx, suffix ->
            val stageItem = state.stageStates[idx]
            editor.putString("tpl_b$suffix", stageItem.tplB.trim())
            editor.putString("${ConfigDefaults.EXPANDED_TPL_KEYS[0]}$suffix", stageItem.baseTitle.trim())
            editor.putString("${ConfigDefaults.EXPANDED_TPL_KEYS[1]}$suffix", stageItem.hintTitle.trim())
            editor.putString("${ConfigDefaults.EXPANDED_TPL_KEYS[2]}$suffix", stageItem.hintSubtitle.trim())
            editor.putString("${ConfigDefaults.EXPANDED_TPL_KEYS[3]}$suffix", stageItem.hintContent.trim())
            editor.putString("${ConfigDefaults.EXPANDED_TPL_KEYS[4]}$suffix", stageItem.hintSubcontent.trim())
            editor.putString("${ConfigDefaults.EXPANDED_TPL_KEYS[5]}$suffix", stageItem.baseContent.trim())
            editor.putString("${ConfigDefaults.EXPANDED_TPL_KEYS[6]}$suffix", stageItem.baseSubcontent.trim())
        }
        editor.apply()
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Hint(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
                text = "可用变量：{课名} {开始} {结束} {教室} {节次} {教师} {倒计时} {正计时}",
            )
        }
        item {
            DismissibleHint(
                activity = activity,
                key = "hint_expanded_custom_timer_rule",
                text = "上课前不支持{正计时}，下课后不支持{倒计时}。计时变量仅主要小文本1/2支持，且不可与其他字符串拼接。",
            )
        }
        item {
            DismissibleHint(
                activity = activity,
                key = "hint_expanded_custom_conflict",
                text = "保存时会做同阶段计时冲突校验：保存状态栏岛时会将展开态主要小文本1/2对齐到状态栏岛B；保存展开态时会将状态栏岛B对齐到展开态。同阶段展开态与状态栏岛B只能保留一种计时类型（正计时或倒计时）。",
            )
        }
        items(sectionTitles.indices.toList()) { i ->
            val stage = state.stageStates[i]
            val title = sectionTitles[i]
            Column(modifier = Modifier.fillMaxWidth()) {
                PreferenceGroup(
                    title = title,
                    first = i == 0,
                    last = i == sectionTitles.lastIndex,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                    ) {
                        TextPreference(
                            title = "主要标题",
                            value = stage.baseTitle.ifBlank { "未设置" },
                            onClick = {
                                editDialog = EditDialogSpec(
                                    title = "$title - 主要标题",
                                    initialValue = stage.baseTitle,
                                    onConfirm = {
                                        state.stageStates[i] = state.stageStates[i].copy(baseTitle = it)
                                        persistExpandedConfig()
                                    },
                                )
                            },
                        )
                        HorizontalDivider()
                        TextPreference(
                            title = "次要文本1",
                            value = stage.baseContent.ifBlank { "未设置" },
                            onClick = {
                                editDialog = EditDialogSpec(
                                    title = "$title - 次要文本1",
                                    initialValue = stage.baseContent,
                                    onConfirm = {
                                        state.stageStates[i] = state.stageStates[i].copy(baseContent = it)
                                        persistExpandedConfig()
                                    },
                                )
                            },
                        )
                        HorizontalDivider()
                        TextPreference(
                            title = "次要文本2",
                            value = stage.baseSubcontent.ifBlank { "未设置" },
                            onClick = {
                                editDialog = EditDialogSpec(
                                    title = "$title - 次要文本2",
                                    initialValue = stage.baseSubcontent,
                                    onConfirm = {
                                        state.stageStates[i] = state.stageStates[i].copy(baseSubcontent = it)
                                        persistExpandedConfig()
                                    },
                                )
                            },
                        )
                        HorizontalDivider()
                        TextPreference(
                            title = "前置文本1",
                            value = stage.hintContent.ifBlank { "未设置" },
                            onClick = {
                                editDialog = EditDialogSpec(
                                    title = "$title - 前置文本1",
                                    initialValue = stage.hintContent,
                                    onConfirm = {
                                        state.stageStates[i] = state.stageStates[i].copy(hintContent = it)
                                        persistExpandedConfig()
                                    },
                                )
                            },
                        )
                        HorizontalDivider()
                        TextPreference(
                            title = "前置文本2",
                            value = stage.hintSubcontent.ifBlank { "未设置" },
                            onClick = {
                                editDialog = EditDialogSpec(
                                    title = "$title - 前置文本2",
                                    initialValue = stage.hintSubcontent,
                                    onConfirm = {
                                        state.stageStates[i] = state.stageStates[i].copy(hintSubcontent = it)
                                        persistExpandedConfig()
                                    },
                                )
                            },
                        )
                        HorizontalDivider()
                        TextPreference(
                            title = "主要小文本1",
                            value = stage.hintTitle.ifBlank { "未设置" },
                            onClick = {
                                editDialog = EditDialogSpec(
                                    title = "$title - 主要小文本1",
                                    initialValue = stage.hintTitle,
                                    onConfirm = {
                                        state.stageStates[i] = state.stageStates[i].copy(hintTitle = it)
                                        persistExpandedConfig()
                                    },
                                )
                            },
                        )
                        HorizontalDivider()
                        TextPreference(
                            title = "主要小文本2",
                            value = stage.hintSubtitle.ifBlank { "未设置" },
                            onClick = {
                                editDialog = EditDialogSpec(
                                    title = "$title - 主要小文本2",
                                    initialValue = stage.hintSubtitle,
                                    onConfirm = {
                                        state.stageStates[i] = state.stageStates[i].copy(hintSubtitle = it)
                                        persistExpandedConfig()
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
    editDialog?.let { spec ->
        EditValueDialog(spec = spec, onDismiss = { editDialog = null })
    }
}

@Composable
private fun StatusCardView(active: Boolean, frameworkDesc: String) {
    val bg = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color(0xFFFFD6D6)
    }
    val onColor = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        Color(0xFF7A0000)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(if (active) R.drawable.ic_module_active else R.drawable.ic_module_inactive),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (active) "模块已激活" else "模块未激活",
                    color = onColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = if (active) {
                        if (frameworkDesc.isBlank()) "LSPosed Service 已连接" else frameworkDesc
                    } else {
                        "LSPosed Service 未连接，请检查模块启用与框架状态"
                    },
                    color = onColor.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TestNotifyCard(activity: MainActivity, state: SettingsComposeState) {
    var editDialog by remember { mutableStateOf<EditDialogSpec?>(null) }
    DismissibleHint(
        activity = activity,
        key = "hint_test_notify",
        text = "发送一条模拟课程提醒，验证超级岛效果是否正常。如果未发送，请强制停止作用域和模块重试。存在测试通知不发出的情况，但不影响实际通知。",
    )
    PreferenceGroup(first = true, last = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            TextPreference(
                title = "课程名称",
                value = state.courseName.ifBlank { "未设置" },
                onClick = {
                    editDialog = EditDialogSpec(
                        title = "课程名称",
                        initialValue = state.courseName,
                        onConfirm = { state.courseName = it },
                    )
                },
            )
            HorizontalDivider()
            TextPreference(
                title = "教室",
                value = state.classroom.ifBlank { "未设置" },
                onClick = {
                    editDialog = EditDialogSpec(
                        title = "教室",
                        initialValue = state.classroom,
                        onConfirm = { state.classroom = it },
                    )
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    activity.uiSendTestBroadcastToTarget(60_000L, state.courseName, state.classroom)
                    Toast.makeText(
                        activity,
                        "已发送测试通知（倒计时），请下拉通知栏查看超级岛效果",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("发送测试通知")
            }
        }
    }
    editDialog?.let { spec ->
        EditValueDialog(spec = spec, onDismiss = { editDialog = null })
    }
}

@Composable
private fun MutedText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
    )
}

@Composable
private fun DismissibleHint(
    activity: MainActivity,
    key: String,
    text: String,
) {
    var dismissed by remember(key) {
        mutableStateOf(PrefsAccess.readConfigBool(activity.uiConfigPrefs(), key, false))
    }
    if (!dismissed) {
        Hint(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
            text = text,
            closeable = true,
        ) {
            dismissed = true
            activity.uiEditConfigPrefs().putBoolean(key, true).apply()
        }
    }
}

@Composable
private fun TimeoutCard(activity: MainActivity, state: SettingsComposeState) {
    var editDialog by remember { mutableStateOf<EditDialogSpec?>(null) }
    val stageLabels = remember { listOf("通知后", "上课后", "下课后") }
    val unitEntries = remember { listOf(DropDownEntry(title = "秒"), DropDownEntry(title = "分")) }
    val stageEntries = remember(stageLabels) { stageLabels.map { DropDownEntry(title = it) } }

    val islandVals = remember(state.timeoutState) { state.timeoutState.islandVals.toMutableList() }
    val islandUnits = remember(state.timeoutState) { state.timeoutState.islandUnits.toMutableList() }
    val islandDefaults = remember(state.timeoutState) {
        mutableStateListOf<Boolean>().apply {
            repeat(stageLabels.size) { idx ->
                add(islandVals[idx] < 0)
            }
        }
    }
    val islandInputs = remember(state.timeoutState) {
        mutableStateListOf<String>().apply {
            repeat(stageLabels.size) { idx ->
                add(if (islandVals[idx] < 0) "" else islandVals[idx].toString())
            }
        }
    }
    val islandUnitStates = remember(state.timeoutState) {
        mutableStateListOf<String>().apply {
            repeat(stageLabels.size) { idx ->
                add(if (islandUnits[idx] == "s") "s" else "m")
            }
        }
    }

    val notifVals = remember(state.timeoutState) { state.timeoutState.notifVals.toMutableList() }
    val notifUnits = remember(state.timeoutState) { state.timeoutState.notifUnits.toMutableList() }
    var notifStage by remember(state.timeoutState) {
        mutableIntStateOf(state.timeoutState.notifTriggerStage.coerceIn(0, 2))
    }
    var notifGlobalDefault by remember(state.timeoutState) {
        mutableStateOf(state.timeoutState.notifGlobalDefault)
    }
    var notifInput by remember(state.timeoutState) {
        val idx = state.timeoutState.notifTriggerStage.coerceIn(0, 2)
        val value = state.timeoutState.notifVals[idx]
        mutableStateOf(if (!state.timeoutState.notifGlobalDefault && value > 0) value.toString() else "")
    }
    var notifUnit by remember(state.timeoutState) {
        val idx = state.timeoutState.notifTriggerStage.coerceIn(0, 2)
        mutableStateOf(if (state.timeoutState.notifUnits[idx] == "s") "s" else "m")
    }

    fun persistCurrentNotifUiToCache() {
        if (notifGlobalDefault) return
        notifVals[notifStage] = parseTimeoutValue(notifInput)
        notifUnits[notifStage] = if (notifUnit == "s") "s" else "m"
    }

    fun persistTimeoutStateNow() {
        repeat(stageLabels.size) { idx ->
            islandVals[idx] = if (islandDefaults[idx]) {
                ConfigDefaults.TIMEOUT_VALUE
            } else {
                parseTimeoutValue(islandInputs[idx])
            }
            islandUnits[idx] = if (islandUnitStates[idx] == "s") "s" else "m"
        }

        if (notifGlobalDefault) {
            repeat(stageLabels.size) { idx ->
                notifVals[idx] = ConfigDefaults.TIMEOUT_VALUE
                notifUnits[idx] = "m"
            }
        } else {
            repeat(stageLabels.size) { idx ->
                notifVals[idx] = ConfigDefaults.TIMEOUT_VALUE
                notifUnits[idx] = "m"
            }
            notifVals[notifStage] = parseTimeoutValue(notifInput)
            notifUnits[notifStage] = if (notifUnit == "s") "s" else "m"
        }

        val saved = TimeoutUiState(
            islandVals = islandVals.toMutableList(),
            islandUnits = islandUnits.toMutableList(),
            notifVals = notifVals.toMutableList(),
            notifUnits = notifUnits.toMutableList(),
            notifTriggerStage = notifStage,
            notifGlobalDefault = notifGlobalDefault,
        )
        val editor = activity.uiEditConfigPrefs()
        writeTimeoutState(editor, saved)
        editor.apply()
        state.timeoutState = saved
    }

    DismissibleHint(
        activity = activity,
        key = "hint_timeout",
        text = "通知消失时岛随之消失；岛消失不影响通知。默认 = 使用系统值（岛 3600 秒，通知 720 分钟）",
    )

    stageLabels.forEachIndexed { idx, label ->
        PreferenceGroup(
            title = "岛消失 · $label",
            first = idx == 0,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                TextPreference(
                    title = "时长",
                    value = if (islandDefaults[idx]) "默认" else islandInputs[idx].ifBlank { "默认" },
                    enabled = !islandDefaults[idx],
                    onClick = {
                        if (!islandDefaults[idx]) {
                            editDialog = EditDialogSpec(
                                title = "岛消失时长（$label）",
                                initialValue = islandInputs[idx],
                                numberOnly = true,
                                onConfirm = {
                                    islandInputs[idx] = it.filter(Char::isDigit)
                                    persistTimeoutStateNow()
                                },
                            )
                        }
                    },
                )
                HorizontalDivider()
                DropDownPreference(
                    title = "单位",
                    entries = unitEntries,
                    value = if (islandUnitStates[idx] == "s") 0 else 1,
                    enabled = !islandDefaults[idx],
                    mode = DropDownMode.Dialog,
                    onSelectedIndexChange = {
                        islandUnitStates[idx] = if (it == 0) "s" else "m"
                        persistTimeoutStateNow()
                    },
                )
                HorizontalDivider()
                SwitchPreference(
                    title = "默认",
                    value = islandDefaults[idx],
                    onCheckedChange = {
                        islandDefaults[idx] = it
                        if (it) {
                            islandInputs[idx] = ""
                        }
                        persistTimeoutStateNow()
                    },
                )
            }
        }
    }

    PreferenceGroup(
        title = "通知消失",
        last = true,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = "设置时间到达后，将取消通知，后续将不再更新状态（上课/下课）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            SwitchPreference(
                title = "默认",
                value = notifGlobalDefault,
                onCheckedChange = {
                    notifGlobalDefault = it
                    if (it) {
                        notifInput = ""
                    } else {
                        notifInput = if (notifVals[notifStage] < 0) "" else notifVals[notifStage].toString()
                        notifUnit = if (notifUnits[notifStage] == "s") "s" else "m"
                    }
                    persistTimeoutStateNow()
                },
            )
            HorizontalDivider()
            DropDownPreference(
                title = "触发阶段",
                entries = stageEntries,
                value = notifStage,
                enabled = !notifGlobalDefault,
                mode = DropDownMode.Dialog,
                onSelectedIndexChange = { newIndex ->
                    if (!notifGlobalDefault) {
                        persistCurrentNotifUiToCache()
                        notifStage = newIndex.coerceIn(0, stageLabels.lastIndex)
                        notifInput = if (notifVals[notifStage] < 0) "" else notifVals[notifStage].toString()
                        notifUnit = if (notifUnits[notifStage] == "s") "s" else "m"
                        persistTimeoutStateNow()
                    }
                },
            )
            HorizontalDivider()
            TextPreference(
                title = "时长",
                value = if (notifGlobalDefault) "默认" else notifInput.ifBlank { "默认" },
                enabled = !notifGlobalDefault,
                onClick = {
                    if (!notifGlobalDefault) {
                        editDialog = EditDialogSpec(
                            title = "通知消失时长",
                            initialValue = notifInput,
                            numberOnly = true,
                            onConfirm = {
                                notifInput = it.filter(Char::isDigit)
                                persistTimeoutStateNow()
                            },
                        )
                    }
                },
            )
            HorizontalDivider()
            DropDownPreference(
                title = "单位",
                entries = unitEntries,
                value = if (notifUnit == "s") 0 else 1,
                enabled = !notifGlobalDefault,
                mode = DropDownMode.Dialog,
                onSelectedIndexChange = {
                    notifUnit = if (it == 0) "s" else "m"
                    persistTimeoutStateNow()
                },
            )
        }
    }
    editDialog?.let { spec ->
        EditValueDialog(spec = spec, onDismiss = { editDialog = null })
    }
}

@Composable
private fun ReminderCard(activity: MainActivity, state: SettingsComposeState) {
    var editDialog by remember { mutableStateOf<EditDialogSpec?>(null) }
    DismissibleHint(
        activity = activity,
        key = "hint_reminder",
        text = "自定义设置通知发送时机",
    )
    PreferenceGroup(first = true, last = true) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
            SwitchPreference(
                title = "启用补发机制（全局）",
                summary = "关闭后：通知补发、课中即时静音/勿扰均停用，仅保留未来闹钟调度。如果出现手动关闭静音勿扰后仍被开启，请停用此功能。",
                value = state.repostEnabled,
                onCheckedChange = {
                    state.repostEnabled = it
                    activity.uiEditConfigPrefs().putBoolean("repost_enabled", it).apply()
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextPreference(
                title = "提前提醒（分钟）",
                value = state.reminderMinutes.ifBlank { "15" },
                onClick = {
                    editDialog = EditDialogSpec(
                        title = "提前提醒（分钟）",
                        initialValue = state.reminderMinutes,
                        numberOnly = true,
                        onConfirm = {
                            val minutes = it.toIntOrNull()?.coerceIn(1, 120) ?: 15
                            state.reminderMinutes = minutes.toString()
                            activity.uiEditConfigPrefs().putInt("reminder_minutes_before", minutes).apply()
                        },
                    )
                },
            )
        }
    }
    editDialog?.let { spec ->
        EditValueDialog(spec = spec, onDismiss = { editDialog = null })
    }
}

@Composable
private fun MuteCard(activity: MainActivity, state: SettingsComposeState) {
    val buttonModeEntries = remember {
        listOf(
            DropDownEntry(title = "仅静音"),
            DropDownEntry(title = "仅勿扰"),
            DropDownEntry(title = "两者"),
        )
    }
    fun persistMuteConfigNow() {
        val muteBefore = clamp0to60(state.muteMinsBefore)
        val unmuteAfter = clamp0to60(state.unmuteMinsAfter)
        val dndBefore = clamp0to60(state.dndMinsBefore)
        val undndAfter = clamp0to60(state.undndMinsAfter)
        state.muteMinsBefore = muteBefore.toString()
        state.unmuteMinsAfter = unmuteAfter.toString()
        state.dndMinsBefore = dndBefore.toString()
        state.undndMinsAfter = undndAfter.toString()
        activity.uiEditConfigPrefs()
            .putBoolean("mute_enabled", state.muteEnabled)
            .putBoolean("unmute_enabled", state.unmuteEnabled)
            .putBoolean("dnd_enabled", state.dndEnabled)
            .putBoolean("undnd_enabled", state.undndEnabled)
            .putInt("mute_mins_before", muteBefore)
            .putInt("unmute_mins_after", unmuteAfter)
            .putInt("dnd_mins_before", dndBefore)
            .putInt("undnd_mins_after", undndAfter)
            .putInt("island_button_mode", state.islandButtonMode.coerceIn(0, 2))
            .apply()
    }
    PreferenceGroup(first = true, last = false) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
            SwitchPreference(
                title = "上课自动静音",
                summary = "课程开始前指定时间将手机调为静音",
                value = state.muteEnabled,
                onCheckedChange = {
                    state.muteEnabled = it
                    persistMuteConfigNow()
                },
            )
            if (state.muteEnabled) {
                MinuteEditor("上课前多少分钟静音", state.muteMinsBefore) {
                    state.muteMinsBefore = it
                    persistMuteConfigNow()
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            SwitchPreference(
                title = "下课自动恢复铃声",
                summary = "课程结束后指定时间恢复正常响铃",
                value = state.unmuteEnabled,
                onCheckedChange = {
                    state.unmuteEnabled = it
                    persistMuteConfigNow()
                },
            )
            if (state.unmuteEnabled) {
                MinuteEditor("下课后多少分钟恢复铃声", state.unmuteMinsAfter) {
                    state.unmuteMinsAfter = it
                    persistMuteConfigNow()
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            SwitchPreference(
                title = "上课自动开启勿扰",
                summary = "课程开始前指定时间开启勿扰（DND）模式",
                value = state.dndEnabled,
                onCheckedChange = {
                    state.dndEnabled = it
                    persistMuteConfigNow()
                },
            )
            if (state.dndEnabled) {
                MinuteEditor("上课前多少分钟开启勿扰", state.dndMinsBefore) {
                    state.dndMinsBefore = it
                    persistMuteConfigNow()
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            SwitchPreference(
                title = "下课自动关闭勿扰",
                summary = "课程结束后指定时间关闭勿扰，恢复正常通知",
                value = state.undndEnabled,
                onCheckedChange = {
                    state.undndEnabled = it
                    persistMuteConfigNow()
                },
            )
            if (state.undndEnabled) {
                MinuteEditor("下课后多少分钟关闭勿扰", state.undndMinsAfter) {
                    state.undndMinsAfter = it
                    persistMuteConfigNow()
                }
            }
        }
    }
    DismissibleHint(
        activity = activity,
        key = "hint_island_button_mode",
        text = "设置上课岛上显示的按钮执行的操作（不受自动静音开关限制）",
    )
    PreferenceGroup(
        title = "超级岛按钮功能",
        first = false,
        last = true,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
            DropDownPreference(
                title = "按钮模式",
                entries = buttonModeEntries,
                value = state.islandButtonMode.coerceIn(0, 2),
                mode = DropDownMode.Dialog,
                onSelectedIndexChange = {
                    state.islandButtonMode = it
                    persistMuteConfigNow()
                },
            )
        }
    }
}

@Composable
private fun MinuteEditor(label: String, value: String, onValue: (String) -> Unit) {
    var editDialog by remember(label) { mutableStateOf<EditDialogSpec?>(null) }
    Spacer(modifier = Modifier.height(8.dp))
    TextPreference(
        title = label,
        value = value.ifBlank { "0" },
        onClick = {
            editDialog = EditDialogSpec(
                title = label,
                initialValue = value,
                numberOnly = true,
                onConfirm = { onValue(it.filter(Char::isDigit)) },
            )
        },
    )
    editDialog?.let { spec ->
        EditValueDialog(spec = spec, onDismiss = { editDialog = null })
    }
}

@Composable
private fun WakeupCard(activity: MainActivity, state: SettingsComposeState) {
    fun persistWakeupConfigNow() {
        val morningLast = (state.wakeupMorningLastSec.toIntOrNull() ?: 4).coerceAtLeast(1)
        val afternoonFirst = (state.wakeupAfternoonFirstSec.toIntOrNull() ?: 5).coerceAtLeast(1)
        state.wakeupMorningLastSec = morningLast.toString()
        state.wakeupAfternoonFirstSec = afternoonFirst.toString()
        activity.uiEditConfigPrefs()
            .putBoolean("wakeup_morning_enabled", state.wakeupMorningEnabled)
            .putInt("wakeup_morning_last_sec", morningLast)
            .putString("wakeup_morning_rules_json", toWakeRulesJson(state.wakeupMorningRules))
            .putBoolean("wakeup_afternoon_enabled", state.wakeupAfternoonEnabled)
            .putInt("wakeup_afternoon_first_sec", afternoonFirst)
            .putString("wakeup_afternoon_rules_json", toWakeRulesJson(state.wakeupAfternoonRules))
            .apply()
    }

    DismissibleHint(
        activity = activity,
        key = "hint_wakeup",
        text = "根据课表在系统时钟创建叫醒闹钟",
    )
    PreferenceGroup(first = true, last = false) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
            SwitchPreference(
                title = "上午自动叫醒",
                summary = "当今日有上午课程时创建叫醒闹钟",
                value = state.wakeupMorningEnabled,
                onCheckedChange = {
                    state.wakeupMorningEnabled = it
                    persistWakeupConfigNow()
                },
            )
            if (state.wakeupMorningEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "上午规则",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                WakeRuleList(
                    rules = state.wakeupMorningRules,
                    onAdd = {
                        state.wakeupMorningRules += WakeRule("1", "7", "00")
                        persistWakeupConfigNow()
                    },
                    onChanged = { persistWakeupConfigNow() },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            SwitchPreference(
                title = "下午自动叫醒",
                summary = "当今日有下午课程时创建叫醒闹钟",
                value = state.wakeupAfternoonEnabled,
                onCheckedChange = {
                    state.wakeupAfternoonEnabled = it
                    persistWakeupConfigNow()
                },
            )
            if (state.wakeupAfternoonEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "下午规则",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                WakeRuleList(
                    rules = state.wakeupAfternoonRules,
                    onAdd = {
                        state.wakeupAfternoonRules += WakeRule("5", "12", "00")
                        persistWakeupConfigNow()
                    },
                    onChanged = { persistWakeupConfigNow() },
                )
            }
        }
    }

    PreferenceGroup(
        title = "节次划分",
        first = false,
        last = true,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
            Text("用于区分上午/下午课程边界", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MinuteEditor("上午最大节次（≤此节为上午）", state.wakeupMorningLastSec) {
                state.wakeupMorningLastSec = it
                persistWakeupConfigNow()
            }
            MinuteEditor("下午起始节次（≥此节为下午）", state.wakeupAfternoonFirstSec) {
                state.wakeupAfternoonFirstSec = it
                persistWakeupConfigNow()
            }
        }
    }
}

@Composable
private fun WakeRuleList(
    rules: MutableList<WakeRule>,
    onAdd: () -> Unit,
    onChanged: () -> Unit,
) {
    var editingIndex by remember { mutableIntStateOf(-1) }
    var pendingDeleteIndex by remember { mutableIntStateOf(-1) }
    Column {
        rules.forEachIndexed { index, rule ->
            val hour = rule.hour.toIntOrNull()?.coerceIn(0, 23) ?: 0
            val minute = rule.minute.toIntOrNull()?.coerceIn(0, 59) ?: 0
            TextPreference(
                title = "规则 ${index + 1}",
                value = "第${rule.sec.ifBlank { "1" }}节 -> $hour:${String.format(Locale.getDefault(), "%02d", minute)}",
                onClick = { editingIndex = index },
            )
            if (index != rules.lastIndex) {
                HorizontalDivider()
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(onClick = onAdd) { Text("＋ 添加规则") }
    }
    if (editingIndex in rules.indices) {
        var sec by remember(editingIndex) { mutableStateOf(rules[editingIndex].sec) }
        var hour by remember(editingIndex) { mutableStateOf(rules[editingIndex].hour) }
        var minute by remember(editingIndex) { mutableStateOf(rules[editingIndex].minute) }
        SuperDialog(
            show = true,
            title = "编辑规则",
            onDismissRequest = { editingIndex = -1 },
        ) {
            Column {
                top.yukonga.miuix.kmp.basic.TextField(
                    value = sec,
                    onValueChange = { sec = it.filter(Char::isDigit) },
                    label = "第X节",
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MiuixTheme.textStyles.main.copy(color = MiuixTheme.colorScheme.onSurface),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(modifier = Modifier.height(8.dp))
                top.yukonga.miuix.kmp.basic.TextField(
                    value = hour,
                    onValueChange = { hour = it.filter(Char::isDigit) },
                    label = "时",
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MiuixTheme.textStyles.main.copy(color = MiuixTheme.colorScheme.onSurface),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(modifier = Modifier.height(8.dp))
                top.yukonga.miuix.kmp.basic.TextField(
                    value = minute,
                    onValueChange = { minute = it.filter(Char::isDigit) },
                    label = "分",
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MiuixTheme.textStyles.main.copy(color = MiuixTheme.colorScheme.onSurface),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { editingIndex = -1 }, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = { pendingDeleteIndex = editingIndex },
                    modifier = Modifier.weight(1f),
                ) { Text("删除") }
                Button(
                    onClick = {
                        if (editingIndex in rules.indices) {
                            rules[editingIndex] = rules[editingIndex].copy(
                                sec = (sec.toIntOrNull() ?: 1).coerceAtLeast(1).toString(),
                                hour = (hour.toIntOrNull() ?: 0).coerceIn(0, 23).toString(),
                                minute = String.format(
                                    Locale.getDefault(),
                                    "%02d",
                                    (minute.toIntOrNull() ?: 0).coerceIn(0, 59),
                                ),
                            )
                            onChanged()
                        }
                        editingIndex = -1
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("确定") }
            }
        }
    }

    if (pendingDeleteIndex in rules.indices) {
        HyperAlertDialog(
            visible = true,
            title = "删除规则",
            message = "确定删除规则 ${pendingDeleteIndex + 1} 吗？",
            mode = AlertDialogMode.NegativeAndPositive,
            negativeText = "取消",
            positiveText = "删除",
            onDismissRequest = { pendingDeleteIndex = -1 },
            onNegativeButton = { pendingDeleteIndex = -1 },
            onPositiveButton = {
                val idx = pendingDeleteIndex
                pendingDeleteIndex = -1
                if (idx in rules.indices) {
                    rules.removeAt(idx)
                    onChanged()
                }
                editingIndex = -1
            },
        )
    }
}

private fun clamp0to60(value: String): Int = value.toIntOrNull()?.coerceIn(0, 60) ?: 0

private fun parseTimeoutValue(value: String): Int {
    val text = value.trim()
    if (text.isEmpty()) return ConfigDefaults.TIMEOUT_VALUE
    return text.toIntOrNull() ?: ConfigDefaults.TIMEOUT_VALUE
}

private fun readTimeoutState(prefs: android.content.SharedPreferences): TimeoutUiState {
    val cfg = TimeoutConfig.read(PrefsAccess.resolve(prefs))
    return TimeoutUiState(
        islandVals = cfg.islandVals.toMutableList(),
        islandUnits = cfg.islandUnits.toMutableList(),
        notifVals = cfg.notifVals.toMutableList(),
        notifUnits = cfg.notifUnits.toMutableList(),
        notifTriggerStage = cfg.notifTriggerStage,
        notifGlobalDefault = cfg.notifGlobalDefault,
    )
}

private fun writeTimeoutState(
    editor: android.content.SharedPreferences.Editor,
    state: TimeoutUiState,
) {
    val save = TimeoutConfig.read(PrefsAccess.resolve(null))
    for (i in state.islandVals.indices) {
        save.islandVals[i] = state.islandVals[i]
        save.islandUnits[i] = if (state.islandUnits[i] == "s") "s" else "m"
        save.notifVals[i] = state.notifVals[i]
        save.notifUnits[i] = if (state.notifUnits[i] == "s") "s" else "m"
    }
    save.notifTriggerStage = state.notifTriggerStage
    save.notifGlobalDefault = state.notifGlobalDefault
    save.write(editor)
}

private fun parseWakeRules(json: String): List<WakeRule> {
    return try {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(
                    WakeRule(
                        sec = obj.optInt("sec", 1).toString(),
                        hour = obj.optInt("hour", 7).toString(),
                        minute = String.format(Locale.getDefault(), "%02d", obj.optInt("minute", 0)),
                    ),
                )
            }
        }.ifEmpty { listOf(WakeRule("1", "7", "00")) }
    } catch (_: Exception) {
        listOf(WakeRule("1", "7", "00"))
    }
}

private fun toWakeRulesJson(rules: List<WakeRule>): String {
    val arr = JSONArray()
    rules.forEach { rule ->
        val sec = (rule.sec.toIntOrNull() ?: 1).coerceAtLeast(1)
        val hour = (rule.hour.toIntOrNull() ?: 0).coerceIn(0, 23)
        val minute = (rule.minute.toIntOrNull() ?: 0).coerceIn(0, 59)
        arr.put(JSONObject().apply {
            put("sec", sec)
            put("hour", hour)
            put("minute", minute)
        })
    }
    return arr.toString()
}

private const val TOKEN_COUNTDOWN = "{倒计时}"
private const val TOKEN_ELAPSED = "{正计时}"

private fun alignExpandedTimerWithStatus(stages: MutableList<StageCustomState>): Int {
    var changed = 0
    stages.indices.forEach { index ->
        val stage = stages[index]
        val statusKind = detectTimerKind(stage.tplB.trim())
        val title = stage.hintTitle.trim()
        val subtitle = stage.hintSubtitle.trim()
        val titleKind = detectTimerKind(title)
        val subtitleKind = detectTimerKind(subtitle)
        var updated = stage
        if ((statusKind == -1 || statusKind == 1) && (titleKind == -1 || titleKind == 1) && statusKind != titleKind) {
            updated = updated.copy(hintTitle = forceTimerKind(title, statusKind))
            changed++
        }
        if ((statusKind == -1 || statusKind == 1) && (subtitleKind == -1 || subtitleKind == 1) && statusKind != subtitleKind) {
            updated = updated.copy(hintSubtitle = forceTimerKind(subtitle, statusKind))
            changed++
        }
        if (updated != stage) stages[index] = updated
    }
    return changed
}

private fun alignStatusTimerWithExpanded(stages: MutableList<StageCustomState>): Int {
    var changed = 0
    stages.indices.forEach { index ->
        val stage = stages[index]
        val expandedKind = detectExpandedTimerKind(stage.hintTitle.trim(), stage.hintSubtitle.trim())
        val statusKind = detectTimerKind(stage.tplB.trim())
        if ((expandedKind == -1 || expandedKind == 1) && (statusKind == -1 || statusKind == 1) && expandedKind != statusKind) {
            stages[index] = stage.copy(tplB = forceTimerKind(stage.tplB.trim(), expandedKind))
            changed++
        }
    }
    return changed
}

private fun detectExpandedTimerKind(hintTitle: String, hintSubtitle: String): Int {
    val titleKind = detectTimerKind(hintTitle)
    if (titleKind == -1 || titleKind == 1) return titleKind
    val subtitleKind = detectTimerKind(hintSubtitle)
    if (subtitleKind == -1 || subtitleKind == 1) return subtitleKind
    return 0
}

private fun detectTimerKind(text: String): Int {
    if (text.isBlank()) return 0
    val hasCountdown = text.contains(TOKEN_COUNTDOWN)
    val hasElapsed = text.contains(TOKEN_ELAPSED)
    if (hasCountdown && hasElapsed) return 2
    if (hasCountdown) return -1
    if (hasElapsed) return 1
    return 0
}

private fun forceTimerKind(text: String, targetKind: Int): String {
    if (targetKind >= 0) return text.replace(TOKEN_COUNTDOWN, TOKEN_ELAPSED)
    return text.replace(TOKEN_ELAPSED, TOKEN_COUNTDOWN)
}

@Composable
private fun HolidayTab(
    activity: MainActivity,
    state: HolidayComposeState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var showYearDialog by remember { mutableStateOf(false) }
    var showClearYearDialog by remember { mutableStateOf(false) }
    var holidayEditEntry by remember { mutableStateOf<HolidayManager.HolidayEntry?>(null) }
    var holidayDraft by remember { mutableStateOf<HolidayDraft?>(null) }
    var workswapEditEntry by remember { mutableStateOf<HolidayManager.HolidayEntry?>(null) }
    var workswapDraft by remember { mutableStateOf<WorkSwapDraft?>(null) }
    var pendingDeleteHoliday by remember { mutableStateOf<HolidayManager.HolidayEntry?>(null) }
    var pendingDeleteWorkswap by remember { mutableStateOf<HolidayManager.HolidayEntry?>(null) }
    val maxWeek = remember(state.year) { activity.uiReadTotalWeekFromCourseData().coerceAtLeast(1) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DismissibleHint(
            activity = activity,
            key = "hint_holiday_overview",
            text = "节假日当天不发课前提醒；调休工作日按指定周次/星期发提醒。",
        )
        PreferenceGroup(first = true) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("年份", color = MaterialTheme.colorScheme.onSurfaceContainer)
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { showYearDialog = true }) { Text(state.year.toString()) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                state.fetchHint = "正在获取…"
                                val result = withContext(Dispatchers.IO) { fetchHolidayEntries(state.year) }
                                result.error?.let {
                                    state.fetchHint = "获取失败：$it"
                                    return@launch
                                }
                                val entries = result.entries
                                if (entries.isEmpty()) {
                                    state.fetchHint = "${state.year}年暂无数据"
                                    return@launch
                                }
                                HolidayManager.mergeAndSave(activity, state.year, entries)
                                activity.uiSyncHolidayToHook(state.year)
                                entries.forEach { e ->
                                    val endDate = if (e.endDate.isNullOrEmpty()) e.date else e.endDate
                                    activity.uiRescheduleIfCoversToday(e.date, endDate)
                                }
                                state.loadFrom(activity)
                                state.fetchHint = "获取完成：节假日 ${result.holidayDays} 天，调休 ${result.workswapDays} 天"
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("网络获取") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { showClearYearDialog = true }) { Text("清除本年") }
                }
                if (state.fetchHint.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.fetchHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        PreferenceGroup(title = "节假日") {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                if (state.holidayEntries.isEmpty()) {
                    Text(
                        text = "暂无节假日数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    state.holidayEntries.forEach { entry ->
                        HolidayRow(
                            entry = entry,
                            onEdit = {
                                holidayEditEntry = entry
                                holidayDraft = HolidayDraft(
                                    date = entry.date,
                                    endDate = entry.endDate ?: "",
                                    name = entry.name,
                                )
                            },
                            onDelete = {
                                pendingDeleteHoliday = entry
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                AddEntryRow(
                    title = "新增节假日",
                    summary = "添加节假日日期或区间",
                    onClick = {
                        holidayEditEntry = null
                        holidayDraft = HolidayDraft(date = "${state.year}-01-01")
                    },
                )
            }
        }

        PreferenceGroup(
            title = "调休工作日",
            last = true,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                if (state.workswapEntries.isEmpty()) {
                    Text(
                        text = "暂无调休工作日数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                } else {
                    state.workswapEntries.forEach { entry ->
                        WorkswapRow(
                            entry = entry,
                            onEdit = {
                                workswapEditEntry = entry
                                workswapDraft = WorkSwapDraft(
                                    date = entry.date,
                                    name = entry.name,
                                    followWeek = if (entry.followWeek > 0) entry.followWeek else 1,
                                    followWeekday = if (entry.followWeekday > 0) entry.followWeekday else 1,
                                )
                            },
                            onDelete = {
                                pendingDeleteWorkswap = entry
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                AddEntryRow(
                    title = "新增调休工作日",
                    summary = "添加调休上班日与跟随周次",
                    onClick = {
                        workswapEditEntry = null
                        workswapDraft = WorkSwapDraft(date = "${state.year}-01-01")
                    },
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showYearDialog) {
        YearPickerDialog(
            currentYear = state.year,
            onDismiss = { showYearDialog = false },
            onConfirm = {
                state.year = it
                state.loadFrom(activity)
                showYearDialog = false
            },
        )
    }

    HyperAlertDialog(
        visible = showClearYearDialog,
        title = "清除本年",
        message = "将清除 ${state.year} 年已保存的全部假期和调休数据（包括自定义条目）。确定吗？",
        mode = AlertDialogMode.NegativeAndPositive,
        negativeText = "取消",
        positiveText = "清除",
        onDismissRequest = { showClearYearDialog = false },
        onNegativeButton = { showClearYearDialog = false },
        onPositiveButton = {
            showClearYearDialog = false
            val old = HolidayManager.loadEntries(activity, state.year)
            HolidayManager.saveEntries(activity, state.year, ArrayList())
            activity.uiSyncHolidayToHook(state.year)
            old.forEach { e ->
                val end = if (e.endDate.isNullOrEmpty()) e.date else e.endDate
                activity.uiRescheduleIfCoversToday(e.date, end)
            }
            state.loadFrom(activity)
            Toast.makeText(activity, "已清除 ${state.year} 年假期数据", Toast.LENGTH_SHORT).show()
        },
    )

    pendingDeleteHoliday?.let { target ->
        HyperAlertDialog(
            visible = true,
            title = "删除节假日",
            message = "确定删除“${target.name}”（${formatShortDate(target.date)}）吗？",
            mode = AlertDialogMode.NegativeAndPositive,
            negativeText = "取消",
            positiveText = "删除",
            onDismissRequest = { pendingDeleteHoliday = null },
            onNegativeButton = { pendingDeleteHoliday = null },
            onPositiveButton = {
                val all = HolidayManager.loadEntries(activity, state.year).toMutableList()
                all.removeIf { e ->
                    e.date == target.date &&
                        (e.endDate ?: "") == (target.endDate ?: "") &&
                        e.name == target.name &&
                        e.type == target.type
                }
                HolidayManager.saveEntries(activity, state.year, all)
                activity.uiSyncHolidayToHook(state.year)
                activity.uiRescheduleIfCoversToday(target.date, target.endDate)
                state.loadFrom(activity)
                pendingDeleteHoliday = null
            },
        )
    }

    pendingDeleteWorkswap?.let { target ->
        HyperAlertDialog(
            visible = true,
            title = "删除调休工作日",
            message = "确定删除“${target.name}”（${formatShortDate(target.date)}）吗？",
            mode = AlertDialogMode.NegativeAndPositive,
            negativeText = "取消",
            positiveText = "删除",
            onDismissRequest = { pendingDeleteWorkswap = null },
            onNegativeButton = { pendingDeleteWorkswap = null },
            onPositiveButton = {
                val all = HolidayManager.loadEntries(activity, state.year).toMutableList()
                all.removeIf { e ->
                    e.date == target.date && e.name == target.name && e.type == target.type
                }
                HolidayManager.saveEntries(activity, state.year, all)
                activity.uiSyncHolidayToHook(state.year)
                activity.uiRescheduleIfCoversToday(target.date, null)
                state.loadFrom(activity)
                pendingDeleteWorkswap = null
            },
        )
    }

    holidayDraft?.let { draft ->
        HolidayEditDialog(
            activity = activity,
            title = if (holidayEditEntry == null) "新增节假日" else "编辑节假日",
            draft = draft,
            onDismiss = { holidayDraft = null },
            onSave = { save ->
                val name = save.name.trim().ifBlank { "节假日" }
                val all = HolidayManager.loadEntries(activity, state.year).toMutableList()
                holidayEditEntry?.let { old ->
                    all.removeIf { e ->
                        e.date == old.date &&
                            (e.endDate ?: "") == (old.endDate ?: "") &&
                            e.name == old.name &&
                            e.type == old.type
                    }
                }
                all += HolidayManager.HolidayEntry(
                    save.date,
                    save.endDate,
                    name,
                    HolidayManager.TYPE_HOLIDAY,
                    true,
                )
                all.sortBy { it.date }
                HolidayManager.saveEntries(activity, state.year, all)
                activity.uiSyncHolidayToHook(state.year)
                val endDate = if (save.endDate.isBlank()) save.date else save.endDate
                activity.uiRescheduleIfCoversToday(save.date, endDate)
                holidayEditEntry?.let { old ->
                    activity.uiRescheduleIfCoversToday(old.date, if (old.endDate.isNullOrEmpty()) old.date else old.endDate)
                }
                state.loadFrom(activity)
                holidayDraft = null
            },
        )
    }

    workswapDraft?.let { draft ->
        WorkswapEditDialog(
            activity = activity,
            title = if (workswapEditEntry == null) "新增调休工作日" else "编辑调休工作日",
            draft = draft,
            maxWeek = maxWeek,
            onDismiss = { workswapDraft = null },
            onSave = { save ->
                val name = save.name.trim().ifBlank { "调休工作日" }
                val all = HolidayManager.loadEntries(activity, state.year).toMutableList()
                workswapEditEntry?.let { old ->
                    all.removeIf { e -> e.date == old.date && e.name == old.name && e.type == old.type }
                }
                val entry = HolidayManager.HolidayEntry(
                    save.date,
                    "",
                    name,
                    HolidayManager.TYPE_WORKSWAP,
                    true,
                )
                entry.followWeek = save.followWeek.coerceIn(1, maxWeek)
                entry.followWeekday = save.followWeekday.coerceIn(1, 7)
                all += entry
                all.sortBy { it.date }
                HolidayManager.saveEntries(activity, state.year, all)
                activity.uiSyncHolidayToHook(state.year)
                activity.uiRescheduleIfCoversToday(save.date, null)
                workswapEditEntry?.let { old -> activity.uiRescheduleIfCoversToday(old.date, null) }
                state.loadFrom(activity)
                workswapDraft = null
            },
        )
    }
}

private data class FetchHolidayResult(
    val entries: List<HolidayManager.HolidayEntry>,
    val holidayDays: Int,
    val workswapDays: Int,
    val error: String? = null,
)

private fun fetchHolidayEntries(year: Int): FetchHolidayResult {
    return try {
        val url = URL("https://unpkg.com/holiday-calendar@1.3.0/data/CN/$year.json")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "XiaoaiIsland/1.0")
        }
        val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        conn.disconnect()
        val entries = HolidayManager.parseApiResponse(text)
        var holidayDays = 0
        var workswapDays = 0
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        entries.forEach { e ->
            val days = if (e.endDate.isNullOrBlank()) {
                1
            } else {
                try {
                    val d1 = sdf.parse(e.date) ?: return@forEach
                    val d2 = sdf.parse(e.endDate) ?: return@forEach
                    ((d2.time - d1.time) / 86_400_000L).toInt() + 1
                } catch (_: Exception) {
                    1
                }
            }
            if (e.type == HolidayManager.TYPE_HOLIDAY) holidayDays += days else workswapDays += days
        }
        FetchHolidayResult(entries = entries, holidayDays = holidayDays, workswapDays = workswapDays)
    } catch (e: Exception) {
        FetchHolidayResult(entries = emptyList(), holidayDays = 0, workswapDays = 0, error = e.message ?: "未知错误")
    }
}

@Composable
private fun HolidayRow(
    entry: HolidayManager.HolidayEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            val dateLabel = if (!entry.endDate.isNullOrBlank() && entry.endDate != entry.date) {
                "${formatShortDate(entry.date)}–${formatShortDate(entry.endDate)}"
            } else {
                formatShortDate(entry.date)
            }
            Text("$dateLabel  ${entry.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                if (entry.isCustom) "自定义节假日" else "API 节假日",
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.isCustom) Color(0xFF7965AF) else Color(0xFF389E0D),
            )
        }
        Button(onClick = onEdit) { Text("编辑") }
        Spacer(modifier = Modifier.width(4.dp))
        Button(onClick = onDelete) { Text("删除", color = Color(0xFFBA1A1A)) }
    }
}

@Composable
private fun WorkswapRow(
    entry: HolidayManager.HolidayEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            val dateLabel = if (!entry.endDate.isNullOrBlank()) {
                "${formatShortDate(entry.date)}–${formatShortDate(entry.endDate)}"
            } else {
                formatShortDate(entry.date)
            }
            Text("$dateLabel  ${entry.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text("替换为: ${entry.followDesc()}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6750A4))
            Text(
                if (entry.isCustom) "自定义调休" else "API 调休",
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.isCustom) Color(0xFF7965AF) else Color(0xFF389E0D),
            )
        }
        Button(onClick = onEdit) { Text("编辑") }
        Spacer(modifier = Modifier.width(4.dp))
        Button(onClick = onDelete) { Text("删除", color = Color(0xFFBA1A1A)) }
    }
}

@Composable
private fun AddEntryRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun YearPickerDialog(
    currentYear: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var year by remember(currentYear) { mutableIntStateOf(currentYear.coerceIn(2020, 2099)) }
    SuperDialog(
        show = true,
        title = "选择年份",
        onDismissRequest = onDismiss,
    ) {
        NumberPicker(
            value = year,
            onValueChange = { year = it },
            range = 2020..2099,
            label = { it.toString() },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(
                onClick = { onConfirm(year) },
                modifier = Modifier.weight(1f),
            ) { Text("确定") }
        }
    }
}

private fun parseIsoDate(isoDate: String): Triple<Int, Int, Int> {
    val now = Calendar.getInstance()
    val defaultY = now.get(Calendar.YEAR)
    val defaultM = now.get(Calendar.MONTH) + 1
    val defaultD = now.get(Calendar.DAY_OF_MONTH)
    val parts = isoDate.split("-")
    val y = parts.getOrNull(0)?.toIntOrNull() ?: defaultY
    val m = (parts.getOrNull(1)?.toIntOrNull() ?: defaultM).coerceIn(1, 12)
    val d = (parts.getOrNull(2)?.toIntOrNull() ?: defaultD).coerceAtLeast(1)
    return Triple(y, m, d)
}

private fun formatIsoDate(year: Int, month: Int, day: Int): String {
    return "%04d-%02d-%02d".format(year, month, day)
}

private fun daysInMonth(year: Int, month: Int): Int {
    val cal = Calendar.getInstance()
    cal.set(Calendar.YEAR, year)
    cal.set(Calendar.MONTH, month - 1)
    cal.set(Calendar.DAY_OF_MONTH, 1)
    return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
}

@Composable
private fun MiuixDatePickerDialog(
    title: String,
    initialDate: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val parsed = remember(initialDate) { parseIsoDate(initialDate) }
    var year by remember(initialDate) { mutableIntStateOf(parsed.first.coerceIn(2020, 2099)) }
    var month by remember(initialDate) { mutableIntStateOf(parsed.second.coerceIn(1, 12)) }
    var day by remember(initialDate) { mutableIntStateOf(parsed.third) }
    val maxDay = remember(year, month) { daysInMonth(year, month) }
    if (day > maxDay) day = maxDay

    SuperDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumberPicker(
                value = year,
                onValueChange = { year = it },
                range = 2020..2099,
                label = { it.toString() },
                modifier = Modifier.weight(1f),
            )
            NumberPicker(
                value = month,
                onValueChange = { month = it },
                range = 1..12,
                label = { "%02d".format(it) },
                modifier = Modifier.weight(1f),
            )
            NumberPicker(
                value = day.coerceIn(1, maxDay),
                onValueChange = { day = it.coerceIn(1, maxDay) },
                range = 1..maxDay,
                label = { "%02d".format(it) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(
                onClick = { onConfirm(formatIsoDate(year, month, day.coerceIn(1, maxDay))) },
                modifier = Modifier.weight(1f),
            ) { Text("确定") }
        }
    }
}

@Composable
private fun HolidayEditDialog(
    activity: MainActivity,
    title: String,
    draft: HolidayDraft,
    onDismiss: () -> Unit,
    onSave: (HolidayDraft) -> Unit,
) {
    var form by remember(draft) { mutableStateOf(draft.copy()) }
    var editDialog by remember { mutableStateOf<EditDialogSpec?>(null) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    SuperDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column {
            Button(onClick = {
                showStartPicker = true
            }, modifier = Modifier.fillMaxWidth()) {
                Text("开始日期: ${form.date}")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                showEndPicker = true
            }, modifier = Modifier.fillMaxWidth()) {
                Text("结束日期: ${if (form.endDate.isBlank()) "仅当天" else form.endDate}")
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextPreference(
                title = "名称（如：春节、放假）",
                value = form.name.ifBlank { "未设置" },
                onClick = {
                    editDialog = EditDialogSpec(
                        title = "名称（如：春节、放假）",
                        initialValue = form.name,
                        onConfirm = { form = form.copy(name = it) },
                    )
                },
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = { onSave(form) }, modifier = Modifier.weight(1f)) { Text("确定") }
        }
    }
    editDialog?.let { spec ->
        EditValueDialog(spec = spec, onDismiss = { editDialog = null })
    }
    if (showStartPicker) {
        MiuixDatePickerDialog(
            title = "选择开始日期",
            initialDate = form.date,
            onDismiss = { showStartPicker = false },
            onConfirm = {
                form = form.copy(date = it)
                showStartPicker = false
            },
        )
    }
    if (showEndPicker) {
        MiuixDatePickerDialog(
            title = "选择结束日期",
            initialDate = if (form.endDate.isBlank()) form.date else form.endDate,
            onDismiss = { showEndPicker = false },
            onConfirm = {
                form = form.copy(endDate = it)
                showEndPicker = false
            },
        )
    }
}

@Composable
private fun WorkswapEditDialog(
    activity: MainActivity,
    title: String,
    draft: WorkSwapDraft,
    maxWeek: Int,
    onDismiss: () -> Unit,
    onSave: (WorkSwapDraft) -> Unit,
) {
    var form by remember(draft) { mutableStateOf(draft.copy()) }
    var editDialog by remember { mutableStateOf<EditDialogSpec?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    val weekEntries = remember(maxWeek) {
        (1..maxWeek.coerceAtLeast(1)).map { week ->
            DropDownEntry(title = "第 $week 周")
        }
    }
    val weekdayEntries = remember {
        listOf(
            DropDownEntry(title = "周一"),
            DropDownEntry(title = "周二"),
            DropDownEntry(title = "周三"),
            DropDownEntry(title = "周四"),
            DropDownEntry(title = "周五"),
            DropDownEntry(title = "周六"),
            DropDownEntry(title = "周日"),
        )
    }
    SuperDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column {
            Button(onClick = {
                showDatePicker = true
            }, modifier = Modifier.fillMaxWidth()) {
                Text("选择日期: ${form.date}")
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextPreference(
                title = "名称（如：补周一课）",
                value = form.name.ifBlank { "未设置" },
                onClick = {
                    editDialog = EditDialogSpec(
                        title = "名称（如：补周一课）",
                        initialValue = form.name,
                        onConfirm = { form = form.copy(name = it) },
                    )
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("当天按以下周次/星期的课表上课：", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(6.dp))
            DropDownPreference(
                title = "周次",
                entries = weekEntries,
                value = form.followWeek.coerceIn(1, maxWeek.coerceAtLeast(1)) - 1,
                mode = DropDownMode.Dialog,
                onSelectedIndexChange = {
                    form = form.copy(followWeek = it + 1)
                },
            )
            HorizontalDivider()
            DropDownPreference(
                title = "星期",
                entries = weekdayEntries,
                value = form.followWeekday.coerceIn(1, 7) - 1,
                mode = DropDownMode.Dialog,
                onSelectedIndexChange = {
                    form = form.copy(followWeekday = it + 1)
                },
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = { onSave(form) }, modifier = Modifier.weight(1f)) { Text("确定") }
        }
    }
    editDialog?.let { spec ->
        EditValueDialog(spec = spec, onDismiss = { editDialog = null })
    }
    if (showDatePicker) {
        MiuixDatePickerDialog(
            title = "选择日期",
            initialDate = form.date,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                form = form.copy(date = it)
                showDatePicker = false
            },
        )
    }
}

private fun formatShortDate(isoDate: String?): String {
    if (isoDate.isNullOrBlank() || isoDate.length < 10) return isoDate ?: ""
    return try {
        val m = isoDate.substring(5, 7).toInt()
        val d = isoDate.substring(8, 10).toInt()
        "$m/$d"
    } catch (_: Exception) {
        isoDate
    }
}

@Composable
private fun AboutTab(
    activity: MainActivity,
    state: AboutComposeState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PreferenceGroup(first = true) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.mipmap.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "课程表超级岛",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = state.version,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        PreferenceGroup(last = true) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
                TextPreference(
                    title = "版本",
                    value = state.version,
                )
                HorizontalDivider()
                TextPreference(
                    title = "作者",
                    value = "Mercury",
                    onClick = { activity.uiOpenAuthorPage() },
                )
                HorizontalDivider()
                SwitchPreference(
                    title = "隐藏桌面图标",
                    value = state.hideIcon,
                    onCheckedChange = {
                        state.hideIcon = it
                        activity.uiSetHideIconEnabled(it)
                    },
                )
            }
        }
    }
}
