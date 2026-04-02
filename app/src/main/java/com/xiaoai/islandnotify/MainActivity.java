package com.xiaoai.islandnotify;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.color.DynamicColors;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class MainActivity extends AppCompatActivity {

    static final String PREFS_NAME = "island_custom";

    private static final String KEY_MIGRATION_DONE = "migration_config_v1_done";
    private static final String KEY_MIGRATION_V2_DONE = "migration_config_v2_done";
    private static final String KEY_ACTIVE_COUNTDOWN_TO_END = "active_countdown_to_end";
    private static final String PREFS_RUNTIME_NAME = "island_runtime";
    private static final String PREFS_UI_NAME = "island_ui";
    private static final String KEY_UI_MONET_ENABLED = "ui_monet_enabled";
    private static final String TARGET_VOICEASSIST = "com.miui.voiceassist";
    private static final String TARGET_DESKCLOCK = "com.android.deskclock";
    private static final String ACTION_RESCHEDULE_DAILY = "com.xiaoai.islandnotify.ACTION_RESCHEDULE_DAILY";
    private static final String ALIAS = "com.xiaoai.islandnotify.MainActivityAlias";
    private static final String HINT_KEY_PREFIX = "hint_";

    private static final String[] CUSTOM_SUFFIXES = ConfigDefaults.STAGE_SUFFIXES;

    private volatile boolean mFrameworkActive = false;
    private volatile String mFrameworkDesc = "";
    private volatile XposedService mXposedService;
    private volatile SharedPreferences mRemotePrefs;
    private volatile SharedPreferences mRemoteHolidayPrefs;
    private volatile boolean mScopeRequested = false;

    private SharedPreferences getConfigPrefs() {
        return PrefsAccess.resolve(mRemotePrefs);
    }

    private SharedPreferences.Editor editConfigPrefs() {
        return PrefsAccess.edit(mRemotePrefs);
    }

    private int readConfigInt(String key, int defaultValue) {
        return PrefsAccess.readConfigInt(mRemotePrefs, key, defaultValue);
    }

    private boolean readConfigBool(String key, boolean defaultValue) {
        return PrefsAccess.readConfigBool(mRemotePrefs, key, defaultValue);
    }

    private SharedPreferences getHolidayPrefs() {
        return PrefsAccess.resolve(mRemoteHolidayPrefs);
    }

    private SharedPreferences.Editor editHolidayPrefs() {
        return PrefsAccess.edit(mRemoteHolidayPrefs);
    }

    private void clearLocalPrefs(String prefsName) {
        PrefsAccess.clearLocal(this, prefsName);
    }

    void requestComposeRefresh() {
        ComposeRefreshBus.bump();
    }

    boolean uiFrameworkActive() {
        return mFrameworkActive;
    }

    String uiFrameworkDesc() {
        return mFrameworkDesc;
    }

    SharedPreferences uiConfigPrefs() {
        return getConfigPrefs();
    }

    SharedPreferences.Editor uiEditConfigPrefs() {
        return editConfigPrefs();
    }

    SharedPreferences uiHolidayPrefs() {
        return getHolidayPrefs();
    }

    SharedPreferences.Editor uiEditHolidayPrefs() {
        return editHolidayPrefs();
    }

    void uiSyncHolidayToHook(int year) {
        syncHolidayToHook(year);
        requestComposeRefresh();
    }

    void uiRescheduleIfCoversToday(String date, String endDate) {
        rescheduleIfCoversToday(date, endDate);
    }

    int uiReadTotalWeekFromCourseData() {
        return readTotalWeekFromCourseData();
    }

    int uiResetAllConfigToDefaults() {
        int removed = resetAllConfigToDefaults();
        requestComposeRefresh();
        return removed;
    }

    boolean uiIsHideIconEnabled() {
        android.content.pm.PackageManager pm = getPackageManager();
        android.content.ComponentName alias = new android.content.ComponentName(this, ALIAS);
        int state = pm.getComponentEnabledSetting(alias);
        return state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    void uiSetHideIconEnabled(boolean checked) {
        android.content.pm.PackageManager pm = getPackageManager();
        android.content.ComponentName alias = new android.content.ComponentName(this, ALIAS);
        pm.setComponentEnabledSetting(
                alias,
                checked
                        ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
        );
    }

    boolean uiIsMonetEnabled() {
        return getSharedPreferences(PREFS_UI_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_UI_MONET_ENABLED, false);
    }

    void uiSetMonetEnabled(boolean enabled) {
        getSharedPreferences(PREFS_UI_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_UI_MONET_ENABLED, enabled)
                .apply();
    }

    boolean uiIsHintDismissed(String key) {
        SharedPreferences ui = getSharedPreferences(PREFS_UI_NAME, Context.MODE_PRIVATE);
        if (ui.contains(key)) {
            return ui.getBoolean(key, false);
        }
        // One-time compatibility fallback from old config(remote) storage.
        boolean legacy = PrefsAccess.readConfigBool(mRemotePrefs, key, false);
        if (legacy) {
            ui.edit().putBoolean(key, true).apply();
        }
        return legacy;
    }

    void uiSetHintDismissed(String key, boolean dismissed) {
        getSharedPreferences(PREFS_UI_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(key, dismissed)
                .apply();
    }

    String uiReadAppVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "\u672a\u77e5\u7248\u672c";
        }
    }

    void uiOpenAuthorPage() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.coolapk.com/u/3336736"));
            startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        MainComposeEntry.install(this);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        initFrameworkServiceStatus();
        updateModuleStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestComposeRefresh();
    }

    @Override
    protected void onDestroy() {
        HolidayManager.clearRemotePrefs();
        super.onDestroy();
    }

    private void initFrameworkServiceStatus() {
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                mXposedService = service;
                mFrameworkActive = true;
                int apiVersion = 0;
                try {
                    apiVersion = service.getApiVersion();
                } catch (Throwable ignored) {
                }
                mFrameworkDesc = "Framework: " + service.getFrameworkName()
                        + "\nAPI: " + apiVersion
                        + "  Version: " + service.getFrameworkVersionCode();

                initRemotePrefsBridgeRemoteOnly(service);
                if (apiVersion >= 101) {
                    requestMissingScopeIfNeeded(service);
                }
                runOnUiThread(MainActivity.this::updateModuleStatus);
            }

            @Override
            public void onServiceDied(XposedService service) {
                mXposedService = null;
                mRemotePrefs = null;
                mRemoteHolidayPrefs = null;
                HolidayManager.clearRemotePrefs();
                mScopeRequested = false;
                mFrameworkActive = false;
                mFrameworkDesc = "";
                runOnUiThread(MainActivity.this::updateModuleStatus);
            }
        });
    }

    private void initRemotePrefsBridgeRemoteOnly(XposedService service) {
        try {
            SharedPreferences remote = service.getRemotePreferences(PREFS_NAME);
            mRemotePrefs = remote;

            SharedPreferences local = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            migrateLocalToRemoteIfNeeded(remote, local, true);
            migrateLegacyConfigOnce(remote);
            migrateHintFlagsToUiAndPurgeRemote(remote);
            clearLocalPrefs(PREFS_NAME);

            SharedPreferences remoteHoliday = service.getRemotePreferences(HolidayManager.PREFS_HOLIDAY);
            mRemoteHolidayPrefs = remoteHoliday;
            SharedPreferences localHoliday = getSharedPreferences(HolidayManager.PREFS_HOLIDAY, Context.MODE_PRIVATE);
            migrateLocalToRemoteIfNeeded(remoteHoliday, localHoliday, false);
            clearLocalPrefs(HolidayManager.PREFS_HOLIDAY);
            HolidayManager.setRemotePrefs(remoteHoliday);

            runOnUiThread(this::refreshAfterConfigSynced);
        } catch (Throwable t) {
            Log.w("IslandNotify", "initRemotePrefsBridgeRemoteOnly failed: " + t.getMessage());
        }
    }

    private void migrateHintFlagsToUiAndPurgeRemote(SharedPreferences remote) {
        if (remote == null) return;
        try {
            Map<String, ?> all = remote.getAll();
            if (all == null || all.isEmpty()) return;
            SharedPreferences ui = getSharedPreferences(PREFS_UI_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor remoteEd = remote.edit();
            SharedPreferences.Editor uiEd = ui.edit();
            boolean remoteChanged = false;
            boolean uiChanged = false;
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                String key = entry.getKey();
                if (key == null || !key.startsWith(HINT_KEY_PREFIX)) continue;
                Object value = entry.getValue();
                if (!ui.contains(key) && value instanceof Boolean && (Boolean) value) {
                    uiEd.putBoolean(key, true);
                    uiChanged = true;
                }
                remoteEd.remove(key);
                remoteChanged = true;
            }
            if (uiChanged) uiEd.apply();
            if (remoteChanged) remoteEd.apply();
        } catch (Throwable t) {
            Log.w("IslandNotify", "migrateHintFlagsToUiAndPurgeRemote failed: " + t.getMessage());
        }
    }

    private void migrateLocalToRemoteIfNeeded(SharedPreferences remote, SharedPreferences local, boolean configOnly) {
        if (remote == null || local == null) return;
        Map<String, ?> remoteAll = remote.getAll();
        Map<String, ?> localAll = local.getAll();
        boolean remoteEmpty = remoteAll == null || remoteAll.isEmpty();
        boolean localEmpty = localAll == null || localAll.isEmpty();
        if (remoteEmpty && !localEmpty) {
            copyAllToTargetFiltered(remote, localAll, configOnly);
            Log.d("IslandNotify", "first migration: local -> remote prefs");
        }
    }

    private void copyAllToTargetFiltered(SharedPreferences target, Map<String, ?> allValues, boolean configOnly) {
        PrefsAccess.copyAllFiltered(target, allValues, configOnly);
    }

    private void migrateLegacyConfigOnce(SharedPreferences sp) {
        if (sp == null) return;
        try {
            Map<String, ?> all = sp.getAll();
            if (all == null || all.isEmpty()) return;
            if (sp.getBoolean(KEY_MIGRATION_DONE, false)) {
                SharedPreferences.Editor ed = sp.edit();
                boolean changed = false;
                changed |= ConfigMigration.purgeLegacyConfigKeys(ed);
                changed |= migrateConfigV2Once(sp, ed);
                if (changed) ed.apply();
                return;
            }
            SharedPreferences.Editor ed = sp.edit();
            boolean changed = ConfigMigration.migrateBaseConfig(sp, ed, ConfigDefaults.KEY_NOTIF_DISMISS_TRIGGER);
            changed |= migrateLegacyActiveTimerSwitch(sp, ed);
            changed |= ConfigMigration.purgeLegacyConfigKeys(ed);
            changed |= migrateConfigV2Once(sp, ed);
            ed.putBoolean(KEY_MIGRATION_DONE, true);
            ed.apply();
        } catch (Throwable t) {
            Log.w("IslandNotify", "migrateLegacyConfigOnce failed: " + t.getMessage());
        }
    }

    private boolean migrateLegacyActiveTimerSwitch(SharedPreferences sp, SharedPreferences.Editor ed) {
        if (!sp.contains(KEY_ACTIVE_COUNTDOWN_TO_END)) return false;
        boolean oldCountdown = sp.getBoolean(KEY_ACTIVE_COUNTDOWN_TO_END, false);
        boolean changed = false;
        String keyHintContentActive = "tpl_hint_content_active";
        String keyHintTitleActive = "tpl_hint_title_active";
        if (safeString(sp.getString(keyHintContentActive, "")).isEmpty()) {
            ed.putString(keyHintContentActive, oldCountdown
                    ? "\u8ddd\u79bb\u4e0b\u8bfe {\u5012\u8ba1\u65f6}"
                    : "\u5df2\u7ecf\u4e0a\u8bfe {\u6b63\u8ba1\u65f6}");
            changed = true;
        }
        if (safeString(sp.getString(keyHintTitleActive, "")).isEmpty()) {
            ed.putString(keyHintTitleActive, oldCountdown
                    ? "{\u5012\u8ba1\u65f6}"
                    : "{\u6b63\u8ba1\u65f6}");
            changed = true;
        }
        ed.remove(KEY_ACTIVE_COUNTDOWN_TO_END);
        return true || changed;
    }

    private boolean migrateConfigV2Once(SharedPreferences sp, SharedPreferences.Editor ed) {
        if (sp.getBoolean(KEY_MIGRATION_V2_DONE, false)) return false;
        String keyHintTitleActive = "tpl_hint_title_active";
        String keyHintContentActive = "tpl_hint_content_active";
        String title = safeString(sp.getString(keyHintTitleActive, ""));
        String content = safeString(sp.getString(keyHintContentActive, ""));

        if ("{\u5012\u8ba1\u65f6}".equals(title)
                && ("\u5df2\u7ecf\u4e0a\u8bfe".equals(content)
                || "\u5df2\u7ecf\u4e0a\u8bfe {\u5012\u8ba1\u65f6}".equals(content))) {
            ed.putString(keyHintContentActive, "\u8ddd\u79bb\u4e0b\u8bfe");
        }
        ed.putBoolean(KEY_MIGRATION_V2_DONE, true);
        return true;
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private void requestMissingScopeIfNeeded(XposedService service) {
        if (mScopeRequested) return;
        try {
            List<String> required = new ArrayList<>();
            Set<String> current = new HashSet<>(service.getScope());
            if (!current.contains(TARGET_VOICEASSIST)) required.add(TARGET_VOICEASSIST);
            if (!current.contains(TARGET_DESKCLOCK)) required.add(TARGET_DESKCLOCK);
            if (required.isEmpty()) {
                mScopeRequested = true;
                return;
            }
            service.requestScope(required, new XposedService.OnScopeEventListener() {
                @Override
                public void onScopeRequestApproved(List<String> approved) {
                    mScopeRequested = true;
                    runOnUiThread(() -> Toast.makeText(
                            MainActivity.this,
                            "\u4f5c\u7528\u57df\u5df2\u6388\u6743: " + approved,
                            Toast.LENGTH_SHORT
                    ).show());
                }

                @Override
                public void onScopeRequestFailed(String message) {
                    runOnUiThread(() -> Toast.makeText(
                            MainActivity.this,
                            "\u4f5c\u7528\u57df\u8bf7\u6c42\u5931\u8d25: " + message,
                            Toast.LENGTH_SHORT
                    ).show());
                }
            });
        } catch (Throwable t) {
            Log.w("IslandNotify", "requestMissingScopeIfNeeded failed: " + t.getMessage());
        }
    }

    private void updateModuleStatus() {
        requestComposeRefresh();
    }

    void uiSendTestBroadcastToTarget(long startOffsetMs, String courseNameInput, String classroomInput) {
        sendTestBroadcastInternal(startOffsetMs, courseNameInput, classroomInput);
    }

    private void sendTestBroadcastInternal(long startOffsetMs, String courseNameInput, String classroomInput) {
        String courseName = courseNameInput == null ? "" : courseNameInput.trim();
        String classroom = classroomInput == null ? "" : classroomInput.trim();
        String sectionRange = "1-2";
        String teacher = "\u6d4b\u8bd5\u6559\u5e08";
        if (courseName.isEmpty()) courseName = "\u9ad8\u7b49\u6570\u5b66";
        if (classroom.isEmpty()) classroom = "\u6559\u79d1A-101";

        long now = System.currentTimeMillis();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now + startOffsetMs);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long startMs = cal.getTimeInMillis();
        long endMs = startMs + 60_000L;

        cal.setTimeInMillis(startMs);
        String startTime = String.format(java.util.Locale.getDefault(), "%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE)
        );
        cal.setTimeInMillis(endMs);
        String endTime = String.format(java.util.Locale.getDefault(), "%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE)
        );

        boolean muteEnabled = readConfigBool("mute_enabled", ConfigDefaults.SWITCH_DISABLED);
        int muteBefore = readConfigInt("mute_mins_before", ConfigDefaults.MINUTES_OFFSET);
        boolean unmuteEnabled = readConfigBool("unmute_enabled", ConfigDefaults.SWITCH_DISABLED);
        int unmuteAfter = readConfigInt("unmute_mins_after", ConfigDefaults.MINUTES_OFFSET);
        boolean dndEnabled = readConfigBool("dnd_enabled", ConfigDefaults.SWITCH_DISABLED);
        int dndBefore = readConfigInt("dnd_mins_before", ConfigDefaults.MINUTES_OFFSET);
        boolean unDndEnabled = readConfigBool("undnd_enabled", ConfigDefaults.SWITCH_DISABLED);
        int unDndAfter = readConfigInt("undnd_mins_after", ConfigDefaults.MINUTES_OFFSET);

        Intent intent = new Intent("com.xiaoai.islandnotify.ACTION_TEST_NOTIFY");
        intent.setPackage(TARGET_VOICEASSIST);
        intent.putExtra("course_name", courseName);
        intent.putExtra("start_time", startTime);
        intent.putExtra("end_time", endTime);
        intent.putExtra("classroom", classroom);
        intent.putExtra("section_range", sectionRange);
        intent.putExtra("teacher", teacher);
        intent.putExtra("mute_enabled", muteEnabled);
        intent.putExtra("mute_mins_before", muteBefore);
        intent.putExtra("unmute_enabled", unmuteEnabled);
        intent.putExtra("unmute_mins_after", unmuteAfter);
        intent.putExtra("dnd_enabled", dndEnabled);
        intent.putExtra("dnd_mins_before", dndBefore);
        intent.putExtra("undnd_enabled", unDndEnabled);
        intent.putExtra("undnd_mins_after", unDndAfter);
        intent.putExtra("start_ms", startMs);
        intent.putExtra("end_ms", endMs);
        sendBroadcast(intent);
    }

    private void syncHolidayToHook(int year) {
        List<HolidayManager.HolidayEntry> entries = HolidayManager.loadEntries(this, year);
        String json = HolidayManager.entriesToJson(entries);
        editHolidayPrefs().putString("list_" + year, json).apply();
    }

    private void rescheduleIfCoversToday(String date, String endDate) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
            String today = sdf.format(new java.util.Date());
            boolean covers;
            if (endDate != null && !endDate.isEmpty()) {
                covers = today.compareTo(date) >= 0 && today.compareTo(endDate) <= 0;
            } else {
                covers = today.equals(date);
            }
            if (covers) {
                Intent reschedule = new Intent(ACTION_RESCHEDULE_DAILY);
                reschedule.setPackage(TARGET_VOICEASSIST);
                sendBroadcast(reschedule);
            }
        } catch (Exception ignored) {
        }
    }

    private int readTotalWeekFromCourseData() {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE);
            int totalWeek = sp.getInt("course_total_week", 0);
            Log.d("IslandNotify", "readTotalWeek: " + totalWeek);
            if (totalWeek > 0) return totalWeek;
        } catch (Throwable e) {
            Log.e("IslandNotify", "readTotalWeek failed", e);
        }
        return 30;
    }

    private void refreshAfterConfigSynced() {
        requestComposeRefresh();
    }

    private int resetAllConfigToDefaults() {
        SharedPreferences remote = getConfigPrefs();
        int removedCount = 0;
        Map<String, ?> remoteAll = remote.getAll();
        if (remoteAll != null) removedCount += remoteAll.size();

        SharedPreferences.Editor remoteEd = remote.edit();
        remoteEd.clear();
        applyDefaultTemplateValues(remoteEd);
        remoteEd.apply();

        clearLocalPrefs(PREFS_NAME);
        return removedCount;
    }

    private void applyDefaultTemplateValues(SharedPreferences.Editor ed) {
        if (ed == null) return;
        for (int i = 0; i < CUSTOM_SUFFIXES.length; i++) {
            String suffix = CUSTOM_SUFFIXES[i];
            ed.putString("tpl_a" + suffix, ConfigDefaults.DEFAULT_TPL_A[i]);
            ed.putString("tpl_b" + suffix, ConfigDefaults.DEFAULT_TPL_B[i]);
            ed.putString("tpl_ticker" + suffix, ConfigDefaults.DEFAULT_TPL_TICKER[i]);
            for (int k = 0; k < ConfigDefaults.EXPANDED_TPL_KEYS.length; k++) {
                ed.putString(
                        ConfigDefaults.EXPANDED_TPL_KEYS[k] + suffix,
                        ConfigDefaults.expandedTemplateDefault(i, k, "")
                );
            }
        }
        ed.putBoolean("icon_a", true);
    }
}
