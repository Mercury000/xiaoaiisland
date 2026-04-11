package com.xiaoai.islandnotify;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
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
    private static final String TARGET_WAKEUP = "com.suda.yzune.wakeupschedule";
    private static final String TARGET_SHIGUANG = "com.xingheyuzhuan.shiguangschedule";
    private static final String TARGET_DESKCLOCK = "com.android.deskclock";
    private static final String ACTION_RESCHEDULE_DAILY = "com.xiaoai.islandnotify.ACTION_RESCHEDULE_DAILY";
    private static final String ALIAS = "com.xiaoai.islandnotify.MainActivityAlias";
    private static final String HINT_KEY_PREFIX = "hint_";
    private static final String BACKUP_SCHEMA = "com.xiaoai.islandnotify.config_backup";
    private static final int BACKUP_VERSION = 1;
    private static final String PREFS_TYPE_CONFIG = "config";
    private static final String PREFS_TYPE_HOLIDAY = "holiday";
    private static final String PREFS_TYPE_UI = "ui";

    private static final String[] CUSTOM_SUFFIXES = ConfigDefaults.STAGE_SUFFIXES;

    private volatile boolean mFrameworkActive = false;
    private volatile String mFrameworkDesc = "";
    private volatile XposedService mXposedService;
    private volatile SharedPreferences mRemotePrefs;
    private volatile SharedPreferences mRemoteHolidayPrefs;
    private volatile boolean mScopeRequested = false;
    private ActivityResultLauncher<String> mCreateConfigBackupLauncher;
    private ActivityResultLauncher<String[]> mOpenConfigBackupLauncher;
    private String mPendingExportBackupJson;

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

    void uiOnCourseDataSourceChanged(String source) {
        try {
            Intent reschedule = new Intent(ACTION_RESCHEDULE_DAILY);
            reschedule.setPackage(TARGET_VOICEASSIST);
            sendBroadcast(reschedule);
        } catch (Throwable ignored) {
        }
    }

    int uiReadTotalWeekFromCourseData() {
        return readTotalWeekFromCourseData();
    }

    int uiResetAllConfigToDefaults() {
        int removed = resetAllConfigToDefaults();
        requestComposeRefresh();
        return removed;
    }

    void uiExportAllConfig() {
        try {
            JSONObject root = buildConfigBackupJson();
            mPendingExportBackupJson = root.toString(2);
            if (mCreateConfigBackupLauncher != null) {
                mCreateConfigBackupLauncher.launch(buildBackupFileName());
            } else {
                Toast.makeText(this, "导出功能初始化失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Log.e("IslandNotify", "uiExportAllConfig failed", t);
            Toast.makeText(this, "导出失败：" + safeError(t), Toast.LENGTH_SHORT).show();
        }
    }

    void uiImportAllConfig() {
        try {
            if (mOpenConfigBackupLauncher != null) {
                mOpenConfigBackupLauncher.launch(new String[]{"application/json", "text/plain", "*/*"});
            } else {
                Toast.makeText(this, "导入功能初始化失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Log.e("IslandNotify", "uiImportAllConfig failed", t);
            Toast.makeText(this, "导入失败：" + safeError(t), Toast.LENGTH_SHORT).show();
        }
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
                .commit();
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

    void uiOpenUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerConfigBackupLaunchers();
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
            if (!current.contains(TARGET_WAKEUP)) required.add(TARGET_WAKEUP);
            if (!current.contains(TARGET_SHIGUANG)) required.add(TARGET_SHIGUANG);
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

    private void registerConfigBackupLaunchers() {
        mCreateConfigBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"),
                this::handleExportBackupUri
        );
        mOpenConfigBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::handleImportBackupUri
        );
    }

    private void handleExportBackupUri(Uri uri) {
        if (uri == null) return;
        String payload = mPendingExportBackupJson;
        if (payload == null || payload.isEmpty()) {
            Toast.makeText(this, "没有可导出的配置内容", Toast.LENGTH_SHORT).show();
            return;
        }
        try (OutputStream os = getContentResolver().openOutputStream(uri, "wt");
             OutputStreamWriter osw = new OutputStreamWriter(os);
             BufferedWriter writer = new BufferedWriter(osw)) {
            if (os == null) throw new IllegalStateException("无法打开导出文件流");
            writer.write(payload);
            writer.flush();
            Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.e("IslandNotify", "handleExportBackupUri failed", t);
            Toast.makeText(this, "导出失败：" + safeError(t), Toast.LENGTH_SHORT).show();
        } finally {
            mPendingExportBackupJson = null;
        }
    }

    private void handleImportBackupUri(Uri uri) {
        if (uri == null) return;
        try (InputStream is = getContentResolver().openInputStream(uri);
             InputStreamReader isr = new InputStreamReader(is);
             BufferedReader reader = new BufferedReader(isr)) {
            if (is == null) throw new IllegalStateException("无法打开导入文件流");
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            int count = importConfigBackupJson(sb.toString());
            requestComposeRefresh();
            Toast.makeText(this, "导入成功：" + count + " 项", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.e("IslandNotify", "handleImportBackupUri failed", t);
            Toast.makeText(this, "导入失败：" + safeError(t), Toast.LENGTH_SHORT).show();
        }
    }

    private JSONObject buildConfigBackupJson() throws Exception {
        JSONObject root = new JSONObject();
        root.put("schema", BACKUP_SCHEMA);
        root.put("version", BACKUP_VERSION);
        root.put("createdAt", System.currentTimeMillis());
        root.put("appVersion", uiReadAppVersionName());

        JSONObject prefs = new JSONObject();
        prefs.put(PREFS_TYPE_CONFIG, sharedPrefsToJson(getConfigPrefs()));
        prefs.put(PREFS_TYPE_HOLIDAY, sharedPrefsToJson(getHolidayPrefs()));
        prefs.put(PREFS_TYPE_UI, sharedPrefsToJson(getSharedPreferences(PREFS_UI_NAME, Context.MODE_PRIVATE)));
        root.put("prefs", prefs);
        return root;
    }

    private int importConfigBackupJson(String jsonText) throws Exception {
        JSONObject root = new JSONObject(jsonText == null ? "" : jsonText.trim());
        String schema = root.optString("schema", "");
        if (!BACKUP_SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("配置文件 schema 不匹配");
        }
        JSONObject prefs = root.optJSONObject("prefs");
        if (prefs == null) throw new IllegalArgumentException("配置文件缺少 prefs");

        int count = 0;
        count += applyJsonToSharedPrefs(getConfigPrefs(), prefs.optJSONObject(PREFS_TYPE_CONFIG), true);
        count += applyJsonToSharedPrefs(getHolidayPrefs(), prefs.optJSONObject(PREFS_TYPE_HOLIDAY), true);
        count += applyJsonToSharedPrefs(getSharedPreferences(PREFS_UI_NAME, Context.MODE_PRIVATE),
                prefs.optJSONObject(PREFS_TYPE_UI), true);
        return count;
    }

    private JSONObject sharedPrefsToJson(SharedPreferences sp) throws Exception {
        JSONObject obj = new JSONObject();
        if (sp == null) return obj;
        Map<String, ?> all = sp.getAll();
        if (all == null || all.isEmpty()) return obj;
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || value == null) continue;
            JSONObject item = new JSONObject();
            if (value instanceof String) {
                item.put("type", "string");
                item.put("value", value);
            } else if (value instanceof Integer) {
                item.put("type", "int");
                item.put("value", (Integer) value);
            } else if (value instanceof Long) {
                item.put("type", "long");
                item.put("value", (Long) value);
            } else if (value instanceof Float) {
                item.put("type", "float");
                item.put("value", (Float) value);
            } else if (value instanceof Boolean) {
                item.put("type", "boolean");
                item.put("value", (Boolean) value);
            } else if (value instanceof Set) {
                item.put("type", "string_set");
                JSONArray arr = new JSONArray();
                Set<?> rawSet = (Set<?>) value;
                for (Object o : rawSet) {
                    if (o != null) arr.put(String.valueOf(o));
                }
                item.put("value", arr);
            } else {
                continue;
            }
            obj.put(key, item);
        }
        return obj;
    }

    private int applyJsonToSharedPrefs(SharedPreferences target, JSONObject data, boolean clearBefore) {
        if (target == null) return 0;
        SharedPreferences.Editor ed = target.edit();
        if (clearBefore) ed.clear();
        int count = 0;
        if (data != null) {
            JSONArray names = data.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.optString(i, "");
                    if (key.isEmpty()) continue;
                    JSONObject item = data.optJSONObject(key);
                    if (item == null) continue;
                    String type = item.optString("type", "");
                    Object value = item.opt("value");
                    if ("string".equals(type)) {
                        ed.putString(key, item.optString("value", ""));
                        count++;
                    } else if ("int".equals(type)) {
                        ed.putInt(key, item.optInt("value", 0));
                        count++;
                    } else if ("long".equals(type)) {
                        ed.putLong(key, item.optLong("value", 0L));
                        count++;
                    } else if ("float".equals(type)) {
                        double v = item.optDouble("value", 0.0d);
                        ed.putFloat(key, (float) v);
                        count++;
                    } else if ("boolean".equals(type)) {
                        ed.putBoolean(key, item.optBoolean("value", false));
                        count++;
                    } else if ("string_set".equals(type) && value instanceof JSONArray) {
                        JSONArray arr = (JSONArray) value;
                        Set<String> set = new HashSet<>();
                        for (int j = 0; j < arr.length(); j++) {
                            String s = arr.optString(j, null);
                            if (s != null) set.add(s);
                        }
                        ed.putStringSet(key, set);
                        count++;
                    }
                }
            }
        }
        ed.apply();
        return count;
    }

    private static String buildBackupFileName() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return "islandnotify_config_" + ts + ".json";
    }

    private static String safeError(Throwable t) {
        if (t == null) return "未知错误";
        String msg = t.getMessage();
        return (msg == null || msg.trim().isEmpty()) ? t.getClass().getSimpleName() : msg;
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
