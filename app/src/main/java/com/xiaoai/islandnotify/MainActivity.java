package com.xiaoai.islandnotify;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.tabs.TabLayout;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模块主界面
 * - 展示模块激活状态（由 LSPosed Hook 动态注入）
 * - 展示权限状态
 * - 支持 Material You 动态取色（Android 12+）
 */
public class MainActivity extends AppCompatActivity {

    // 保存按钮引用与脏状态
    private MaterialButton btnSaveCustom;
    private MaterialButton btnSaveExpanded;
    private MaterialButton btnSaveTimeout;
    private boolean customDirty  = false;
    private boolean timeoutDirty = false;
    private boolean mCustomCardBound = false;

    // 假期/调休 Tab 相关成员
    private int           mCurrentHolidayYear;
    private LinearLayout  mLlHolidayList;
    private LinearLayout  mLlWorkswapList;
    private TextView      mTvHolidayEmpty;
    private TextView      mTvWorkswapEmpty;
    private volatile boolean mFrameworkActive = false;
    private volatile String mFrameworkDesc = "";
    private volatile XposedService mXposedService;
    private volatile SharedPreferences mRemotePrefs;
    private volatile SharedPreferences mRemoteHolidayPrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener mLocalPrefMirrorListener;
    private SharedPreferences.OnSharedPreferenceChangeListener mLocalHolidayMirrorListener;
    private volatile boolean mScopeRequested = false;

    private static final String[] CUSTOM_SUFFIXES = ConfigDefaults.STAGE_SUFFIXES;
    private static final String KEY_MIGRATION_DONE = "migration_config_v1_done";
    private static final String KEY_MIGRATION_V2_DONE = "migration_config_v2_done";
    private static final String PREFS_RUNTIME_NAME = "island_runtime";
    private static final int[] ISLAND_PHASE_BUTTON_IDS = {
            R.id.btn_island_phase_pre, R.id.btn_island_phase_active, R.id.btn_island_phase_post
    };
    private static final int[] NOTIF_PHASE_BUTTON_IDS = {
            R.id.btn_notif_phase_pre, R.id.btn_notif_phase_active, R.id.btn_notif_phase_post
    };
    private static final int[] CUSTOM_IDS_A = {
            R.id.et_tpl_a_pre, R.id.et_tpl_a_active, R.id.et_tpl_a_post
    };
    private static final int[] CUSTOM_IDS_B = {
            R.id.et_tpl_b_pre, R.id.et_tpl_b_active, R.id.et_tpl_b_post
    };
    private static final int[] CUSTOM_IDS_TICKER = {
            R.id.et_tpl_ticker_pre, R.id.et_tpl_ticker_active, R.id.et_tpl_ticker_post
    };
    private static final int[][] CUSTOM_IDS_EXPANDED_V2 = ConfigDefaults.EXPANDED_TPL_EDIT_IDS;
    private static final String TARGET_VOICEASSIST = "com.miui.voiceassist";
    private static final String TARGET_DESKCLOCK = "com.android.deskclock";
    private static final String ACTION_RESCHEDULE_DAILY = "com.xiaoai.islandnotify.ACTION_RESCHEDULE_DAILY";

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

    private String readConfigString(String key, String defaultValue) {
        return PrefsAccess.readConfigString(mRemotePrefs, key, defaultValue);
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须在 super.onCreate 前调用，才能正确应用动态色彩
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 设置 Toolbar 为 ActionBar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // 设置边缘到边缘沉浸式（Edge-to-Edge）
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.coordinator_root), (v, insets) -> {
            androidx.core.graphics.Insets systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            
            // 顶栏避让状态栏
            findViewById(R.id.app_bar).setPadding(0, systemBars.top, 0, 0);

            // 底部 Tab栏 避让导航栏（小白条）
            View tabLayout = findViewById(R.id.tab_layout);
            if (tabLayout != null) {
                tabLayout.setPadding(0, 0, 0, systemBars.bottom);
            }

            // 中间滚动区域增加底部 padding，防止被 Tab栏和小白条挡住
            View scrollView = findViewById(R.id.scroll_view);
            if (scrollView != null) {
                // 预留 Tab 高度 (通常约 56dp) + 小白条高度
                int tabHeight = Math.round(56 * getResources().getDisplayMetrics().density);
                scrollView.setPadding(0, 0, 0, tabHeight + systemBars.bottom);
            }
            return insets;
        });

        // 测试通知：1分钟后上课（倒计时）
        findViewById(R.id.btn_send_test).setOnClickListener(v -> {
            sendTestBroadcastToTarget(60_000L);
            showTestHint("已发送测试通知（倒计时），请下拉通知栏查看超级岛效果");
        });

        initFrameworkServiceStatus();
        updateModuleStatus();
        initCustomCard();
        initTimeoutCard();
        initReminderCard();
        initMuteCard();
        initWakeupCard();
        initHideIconSwitch();
        initAboutSection(); // 初始化关于页面的版本信息
        setupTabs();
        initHolidayTab();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUiFromPrefs(false);
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
                } catch (Throwable ignored) {}
                mFrameworkDesc = "Framework: " + service.getFrameworkName()
                        + "\nAPI: " + apiVersion
                        + "  Version: " + service.getFrameworkVersionCode();
                if (apiVersion >= 101) {
                    initRemotePrefsBridgeRemoteOnly(service);
                    requestMissingScopeIfNeeded(service);
                } else {
                    initRemotePrefsBridgeRemoteOnly(service);
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
            migrateLocalToRemoteIfNeeded(remote, local, "配置", true);
            migrateLegacyConfigOnce(remote);
            clearLocalPrefs(PREFS_NAME);

            SharedPreferences remoteHoliday = service.getRemotePreferences(HolidayManager.PREFS_HOLIDAY);
            mRemoteHolidayPrefs = remoteHoliday;
            SharedPreferences localHoliday = getSharedPreferences(HolidayManager.PREFS_HOLIDAY, Context.MODE_PRIVATE);
            migrateLocalToRemoteIfNeeded(remoteHoliday, localHoliday, "节假日", false);
            clearLocalPrefs(HolidayManager.PREFS_HOLIDAY);
            HolidayManager.setRemotePrefs(remoteHoliday);

            runOnUiThread(this::refreshAfterConfigSynced);
        } catch (Throwable t) {
            Log.w("IslandNotify", "initRemotePrefsBridgeRemoteOnly failed: " + t.getMessage());
        }
    }

    private void migrateLocalToRemoteIfNeeded(SharedPreferences remote, SharedPreferences local,
                                              String label, boolean configOnly) {
        if (remote == null || local == null) return;
        Map<String, ?> remoteAll = remote.getAll();
        Map<String, ?> localAll = local.getAll();
        boolean remoteEmpty = remoteAll == null || remoteAll.isEmpty();
        boolean localEmpty = localAll == null || localAll.isEmpty();
        if (remoteEmpty && !localEmpty) {
            copyAllToTargetFiltered(remote, localAll, configOnly);
            Log.d("IslandNotify", "首次迁移(" + label + "): local -> remote prefs");
        }
    }

    private void initRemotePrefsBridgeV2(XposedService service) {
        try {
            SharedPreferences remote = service.getRemotePreferences(PREFS_NAME);
            mRemotePrefs = remote;

            SharedPreferences local = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            migrateLegacyConfigOnce(remote);
            migrateLegacyConfigOnce(local);
            if (runInitialMigrationV2(remote, local, "配置", true)) {
                runOnUiThread(this::refreshAfterConfigSynced);
            }

            if (mLocalPrefMirrorListener == null) {
                mLocalPrefMirrorListener = (sp, changedKey) -> {
                    SharedPreferences rp = mRemotePrefs;
                    if (rp == null || changedKey == null || !isConfigKey(changedKey)) return;
                    copySingleKeyToTarget(rp, sp, changedKey);
                };
                local.registerOnSharedPreferenceChangeListener(mLocalPrefMirrorListener);
            }

            SharedPreferences remoteHoliday = service.getRemotePreferences(HolidayManager.PREFS_HOLIDAY);
            mRemoteHolidayPrefs = remoteHoliday;
            SharedPreferences localHoliday = getSharedPreferences(HolidayManager.PREFS_HOLIDAY, Context.MODE_PRIVATE);
            runInitialMigrationV2(remoteHoliday, localHoliday, "节假日", false);
            if (mLocalHolidayMirrorListener == null) {
                mLocalHolidayMirrorListener = (sp, changedKey) -> {
                    SharedPreferences rh = mRemoteHolidayPrefs;
                    if (rh == null || changedKey == null) return;
                    copySingleKeyToTarget(rh, sp, changedKey);
                };
                localHoliday.registerOnSharedPreferenceChangeListener(mLocalHolidayMirrorListener);
            }
        } catch (Throwable t) {
            Log.w("IslandNotify", "initRemotePrefsBridgeV2 failed: " + t.getMessage());
        }
    }

    private boolean runInitialMigrationV2(SharedPreferences remote, SharedPreferences local,
                                          String label, boolean configOnly) {
        if (remote == null || local == null) return false;
        Map<String, ?> remoteAll = remote.getAll();
        Map<String, ?> localAll = local.getAll();
        boolean remoteEmpty = remoteAll == null || remoteAll.isEmpty();
        boolean localEmpty = localAll == null || localAll.isEmpty();

        if (remoteEmpty && !localEmpty) {
            copyAllToTargetFiltered(remote, localAll, configOnly);
            Log.d("IslandNotify", "首次迁移(" + label + ")：模块本地 -> remote prefs");
            return false;
        }
        if (!remoteEmpty && localEmpty) {
            copyAllToTargetFiltered(local, remoteAll, configOnly);
            Log.d("IslandNotify", "首次迁移(" + label + ")：remote prefs -> 模块本地");
            return true;
        }
        return false;
    }

    private void copyAllToTargetFiltered(SharedPreferences target, Map<String, ?> allValues, boolean configOnly) {
        PrefsAccess.copyAllFiltered(target, allValues, configOnly);
    }

    private void copySingleKeyToTarget(SharedPreferences target, SharedPreferences source, String key) {
        PrefsAccess.copySingleKey(target, source, key);
    }

    private void initRemotePrefsBridge(XposedService service) {
        try {
            SharedPreferences remote = service.getRemotePreferences(PREFS_NAME);
            mRemotePrefs = remote;

            SharedPreferences local = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            migrateLegacyConfigOnce(remote);
            migrateLegacyConfigOnce(local);
            if (runInitialMigration(remote, local, "配置")) {
                runOnUiThread(this::refreshAfterConfigSynced);
            }

            if (mLocalPrefMirrorListener == null) {
                mLocalPrefMirrorListener = (sp, changedKey) -> {
                    SharedPreferences rp = mRemotePrefs;
                    if (rp == null || changedKey == null) return;
                    copyKeysToTarget(rp, sp, changedKey);
                };
                local.registerOnSharedPreferenceChangeListener(mLocalPrefMirrorListener);
            }

            SharedPreferences remoteHoliday = service.getRemotePreferences(HolidayManager.PREFS_HOLIDAY);
            mRemoteHolidayPrefs = remoteHoliday;
            SharedPreferences localHoliday = getSharedPreferences(HolidayManager.PREFS_HOLIDAY, Context.MODE_PRIVATE);
            runInitialMigration(remoteHoliday, localHoliday, "节假日");
            if (mLocalHolidayMirrorListener == null) {
                mLocalHolidayMirrorListener = (sp, changedKey) -> {
                    SharedPreferences rh = mRemoteHolidayPrefs;
                    if (rh == null || changedKey == null) return;
                    copyKeysToTarget(rh, sp, changedKey);
                };
                localHoliday.registerOnSharedPreferenceChangeListener(mLocalHolidayMirrorListener);
            }
        } catch (Throwable t) {
            Log.w("IslandNotify", "initRemotePrefsBridge failed: " + t.getMessage());
        }
    }

    /**
     * 统一首次迁移规则：
     * 1) remote 为空且 local 非空：local -> remote
     * 2) remote 非空且 local 为空：remote -> local
     *
     * @return 是否发生了 remote -> local 回填（需要刷新 UI）。
     */
    private boolean runInitialMigration(SharedPreferences remote, SharedPreferences local, String label) {
        if (remote == null || local == null) return false;
        Map<String, ?> remoteAll = remote.getAll();
        Map<String, ?> localAll = local.getAll();
        boolean remoteEmpty = remoteAll == null || remoteAll.isEmpty();
        boolean localEmpty = localAll == null || localAll.isEmpty();

        if (remoteEmpty && !localEmpty) {
            copyAllToTarget(remote, localAll);
            Log.d("IslandNotify", "首次迁移(" + label + ")：模块本地 -> remote prefs");
            return false;
        }
        if (!remoteEmpty && localEmpty) {
            copyAllToTarget(local, remoteAll);
            Log.d("IslandNotify", "首次迁移(" + label + ")：remote prefs -> 模块本地");
            return true;
        }
        return false;
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
                if (changed) {
                    ed.apply();
                }
                return;
            }
            SharedPreferences.Editor ed = sp.edit();
            boolean changed = ConfigMigration.migrateBaseConfig(sp, ed, KEY_NOTIF_DISMISS_TRIGGER);
            changed |= migrateLegacyActiveTimerSwitch(sp, ed);

            if (changed) {
                Log.d("IslandNotify", "一次性迁移完成（旧配置 -> 三阶段）");
            }
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
            ed.putString(keyHintContentActive, oldCountdown ? "距离下课 {倒计时}" : "已经上课 {正计时}");
            changed = true;
        }
        if (safeString(sp.getString(keyHintTitleActive, "")).isEmpty()) {
            ed.putString(keyHintTitleActive, oldCountdown ? "{倒计时}" : "{正计时}");
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
        boolean changed = false;

        // If active title uses countdown and content is legacy "already in class",
        // migrate content text to "distance to class end" without overriding custom text.
        if ("{\u5012\u8ba1\u65f6}".equals(title)
                && ("\u5df2\u7ecf\u4e0a\u8bfe".equals(content)
                || "\u5df2\u7ecf\u4e0a\u8bfe {\u5012\u8ba1\u65f6}".equals(content))) {
            ed.putString(keyHintContentActive, "\u8ddd\u79bb\u4e0b\u8bfe");
            changed = true;
        }
        ed.putBoolean(KEY_MIGRATION_V2_DONE, true);
        return true;
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private boolean isConfigKey(String key) {
        return ConfigDefaults.isConfigKey(key);
    }

    private void requestMissingScopeIfNeeded(XposedService service) {
        if (mScopeRequested) return;
        try {
            List<String> required = new ArrayList<>();
            Set<String> current = new HashSet<>(service.getScope());
            if (!current.contains("com.miui.voiceassist")) required.add("com.miui.voiceassist");
            if (!current.contains("com.android.deskclock")) required.add("com.android.deskclock");
            if (required.isEmpty()) {
                mScopeRequested = true;
                return;
            }
            service.requestScope(required, new XposedService.OnScopeEventListener() {
                @Override
                public void onScopeRequestApproved(List<String> approved) {
                    mScopeRequested = true;
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this,
                                    "作用域已授权: " + approved, Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onScopeRequestFailed(String message) {
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this,
                                    "作用域请求失败: " + message, Toast.LENGTH_SHORT).show());
                }
            });
        } catch (Throwable t) {
            Log.w("IslandNotify", "requestMissingScopeIfNeeded failed: " + t.getMessage());
        }
    }

    private void copyKeysToTarget(SharedPreferences target, SharedPreferences source, String... keys) {
        if (source == null || keys == null || keys.length == 0) return;
        for (String key : keys) {
            PrefsAccess.copySingleKey(target, source, key);
        }
    }

    private void copyAllToTarget(SharedPreferences target, Map<String, ?> allValues) {
        PrefsAccess.copyAll(target, allValues);
    }

    // ─────────────────────────────────────────────────────────────

    /**
     * 根据模块是否激活，设置状态卡片的颜色、图标和文字。
     */
    private void updateModuleStatus() {
        StatusCardController.render(this, mFrameworkActive, mFrameworkDesc);
    }

    /** SharedPreferences 名称（与 MainHook 保持一致） */
    static final String PREFS_NAME = "island_custom";
    private static final String KEY_ACTIVE_COUNTDOWN_TO_END = "active_countdown_to_end";

    private void initCustomCard() {
        if (mCustomCardBound) {
            refreshCustomCardFromPrefs();
            return;
        }
        SharedPreferences sp = getConfigPrefs();
        applyExpandedFieldOrderHints();

        // 三个阶段 SP 后缀：_pre=上课前  _active=上课中  _post=下课后
        final String[] SUFFIXES = ConfigDefaults.STAGE_SUFFIXES;
        // 各阶段 A/B/ticker 的默认值
        final String[] DEFAULT_A = ConfigDefaults.DEFAULT_TPL_A;
        final String[] DEFAULT_B = ConfigDefaults.DEFAULT_TPL_B;
        final String[] DEFAULT_TICKER = ConfigDefaults.DEFAULT_TPL_TICKER;
        // 各阶段输入框 View ID
        final int[] IDS_A = CUSTOM_IDS_A;
        final int[] IDS_B = CUSTOM_IDS_B;
        final int[] IDS_TICKER = CUSTOM_IDS_TICKER;

        SwitchMaterial swIconA = findViewById(R.id.sw_icon_a);
        TextView tvHint        = findViewById(R.id.tv_save_hint);
        TextView tvExpandedHint = findViewById(R.id.tv_save_hint_expanded);

        // 读取已保存配置，无则用默认值
        for (int i = 0; i < 3; i++) {
            ((EditText) findViewById(IDS_A[i])).setText(
                    PrefsAccess.readStagedTemplate(sp, "tpl_a", SUFFIXES[i], ""));
            ((EditText) findViewById(IDS_B[i])).setText(
                    PrefsAccess.readStagedTemplate(sp, "tpl_b", SUFFIXES[i], ""));
            ((EditText) findViewById(IDS_TICKER[i])).setText(
                    PrefsAccess.readStagedTemplate(sp, "tpl_ticker", SUFFIXES[i], ""));
            for (int k = 0; k < ConfigDefaults.EXPANDED_TPL_KEYS.length; k++) {
                ((EditText) findViewById(CUSTOM_IDS_EXPANDED_V2[k][i])).setText(
                        PrefsAccess.readStagedString(sp, ConfigDefaults.EXPANDED_TPL_KEYS[k],
                                SUFFIXES[i], defaultExpandedTpl(i, k)));
            }
        }
        swIconA.setChecked(PrefsAccess.readConfigBool(sp, "icon_a", true));

        // 保存按钮引用 & 监控输入变化用于即时指示未保存状态
        btnSaveCustom = findViewById(R.id.btn_save_custom);
        btnSaveExpanded = findViewById(R.id.btn_save_expanded);
        for (int i = 0; i < 3; i++) {
            bindCustomDirtyWatcher((EditText) findViewById(IDS_A[i]));
            bindCustomDirtyWatcher((EditText) findViewById(IDS_B[i]));
            bindCustomDirtyWatcher((EditText) findViewById(IDS_TICKER[i]));
            for (int k = 0; k < ConfigDefaults.EXPANDED_TPL_KEYS.length; k++) {
                bindCustomDirtyWatcher((EditText) findViewById(CUSTOM_IDS_EXPANDED_V2[k][i]));
            }
        }
        swIconA.setOnCheckedChangeListener((b, checked) -> updateCustomDirtyIndicator());

        findViewById(R.id.btn_save_custom).setOnClickListener(v -> {

            int autoAlignedCount = alignExpandedTimerDirectionWithStatusBarFromUi();
            SharedPreferences.Editor ed = editConfigPrefs();
            // 通知 voiceassist 进程同步最新配置（绕过 SELinux 跨 UID 文件读取限制）

            for (int i = 0; i < 3; i++) {
                String tplA      = ((EditText) findViewById(IDS_A[i])).getText().toString().trim();
                String tplB      = ((EditText) findViewById(IDS_B[i])).getText().toString().trim();
                String tplTicker = ((EditText) findViewById(IDS_TICKER[i])).getText().toString().trim();
                ed.putString("tpl_a"      + SUFFIXES[i], tplA);
                ed.putString("tpl_b"      + SUFFIXES[i], tplB);
                ed.putString("tpl_ticker" + SUFFIXES[i], tplTicker);
                String alignedHintTitle = ((EditText) findViewById(CUSTOM_IDS_EXPANDED_V2[1][i]))
                        .getText().toString().trim();
                String alignedHintSubTitle = ((EditText) findViewById(CUSTOM_IDS_EXPANDED_V2[2][i]))
                        .getText().toString().trim();
                ed.putString("tpl_hint_title" + SUFFIXES[i], alignedHintTitle);
                ed.putString("tpl_hint_subtitle" + SUFFIXES[i], alignedHintSubTitle);
            }
            boolean iconA = swIconA.isChecked();
            ed.putBoolean("icon_a", iconA);
            ed.apply();

            if (autoAlignedCount > 0) {
                tvHint.setText("已保存，下次通知生效（已自动对齐 " + autoAlignedCount + " 处计时方向）");
            } else {
                tvHint.setText("已保存，下次通知生效");
            }
            tvHint.setVisibility(View.VISIBLE);
            // 保存后清除指示
            customDirty = false;
            updateCustomDirtyIndicator();
        });

        View btnSaveExpandedView = findViewById(R.id.btn_save_expanded);
        if (btnSaveExpandedView != null) {
            btnSaveExpandedView.setOnClickListener(v -> {
                int autoAlignedCount = alignStatusBarTimerDirectionWithExpandedFromUi();
                SharedPreferences.Editor ed = editConfigPrefs();
                for (int i = 0; i < 3; i++) {
                    // 保存展开态前，先把因“同阶段计时类型统一”产生的状态栏 tpl_b 同步写回，
                    // 避免 UI 已变更但配置未保存导致状态栏卡片误报“未保存”。
                    String alignedTplB = ((EditText) findViewById(IDS_B[i]))
                            .getText().toString().trim();
                    ed.putString("tpl_b" + SUFFIXES[i], alignedTplB);
                    for (int k = 0; k < ConfigDefaults.EXPANDED_TPL_KEYS.length; k++) {
                        String expandedValue = ((EditText) findViewById(CUSTOM_IDS_EXPANDED_V2[k][i]))
                                .getText().toString().trim();
                        ed.putString(ConfigDefaults.EXPANDED_TPL_KEYS[k] + SUFFIXES[i], expandedValue);
                    }
                }
                ed.apply();
                if (tvExpandedHint != null) {
                    if (autoAlignedCount > 0) {
                        tvExpandedHint.setText("已保存，下次通知生效（已自动对齐 " + autoAlignedCount + " 处计时方向）");
                    } else {
                        tvExpandedHint.setText("已保存，下次通知生效");
                    }
                    tvExpandedHint.setVisibility(View.VISIBLE);
                }
                updateCustomDirtyIndicator();
            });
        }

        View btnResetDefaults = findViewById(R.id.btn_reset_defaults);
        if (btnResetDefaults != null) {
            btnResetDefaults.setOnClickListener(v -> showResetDefaultsDialog());
        }

        mCustomCardBound = true;

    }

    private void bindCustomDirtyWatcher(EditText editText) {
        if (editText == null) return;
        editText.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                updateCustomDirtyIndicator();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
    }

    private int alignExpandedTimerDirectionWithStatusBarFromUi() {
        int changed = 0;
        for (int i = 0; i < CUSTOM_SUFFIXES.length; i++) {
            EditText etStatusBarB = findViewById(CUSTOM_IDS_B[i]);
            EditText etExpandedHintTitle = findViewById(CUSTOM_IDS_EXPANDED_V2[1][i]); // tpl_hint_title
            EditText etExpandedHintSubTitle = findViewById(CUSTOM_IDS_EXPANDED_V2[2][i]); // tpl_hint_subtitle
            if (etStatusBarB == null || etExpandedHintTitle == null || etExpandedHintSubTitle == null) continue;

            String statusBarText = etStatusBarB.getText() == null ? "" : etStatusBarB.getText().toString().trim();
            int statusKind = detectTimerKind(statusBarText);
            changed += alignOneExpandedTimerField(etExpandedHintTitle, statusKind);
            changed += alignOneExpandedTimerField(etExpandedHintSubTitle, statusKind);
        }
        return changed;
    }

    private int alignStatusBarTimerDirectionWithExpandedFromUi() {
        int changed = 0;
        for (int i = 0; i < CUSTOM_SUFFIXES.length; i++) {
            EditText etStatusBarB = findViewById(CUSTOM_IDS_B[i]);
            EditText etExpandedHintTitle = findViewById(CUSTOM_IDS_EXPANDED_V2[1][i]); // tpl_hint_title
            EditText etExpandedHintSubTitle = findViewById(CUSTOM_IDS_EXPANDED_V2[2][i]); // tpl_hint_subtitle
            if (etStatusBarB == null || etExpandedHintTitle == null || etExpandedHintSubTitle == null) continue;

            int expandedKind = detectExpandedTimerKindForStage(
                    etExpandedHintTitle.getText() == null ? "" : etExpandedHintTitle.getText().toString().trim(),
                    etExpandedHintSubTitle.getText() == null ? "" : etExpandedHintSubTitle.getText().toString().trim());
            if (expandedKind != -1 && expandedKind != 1) continue;
            changed += alignStatusBarTimerField(etStatusBarB, expandedKind);
        }
        return changed;
    }

    private int detectExpandedTimerKindForStage(String hintTitle, String hintSubTitle) {
        int titleKind = detectTimerKind(hintTitle);
        if (titleKind == -1 || titleKind == 1) return titleKind;
        int subKind = detectTimerKind(hintSubTitle);
        if (subKind == -1 || subKind == 1) return subKind;
        return 0;
    }

    private int alignStatusBarTimerField(EditText target, int expandedKind) {
        if (target == null) return 0;
        String text = target.getText() == null ? "" : target.getText().toString().trim();
        int kind = detectTimerKind(text);
        if ((kind == -1 || kind == 1) && kind != expandedKind) {
            String aligned = forceTimerKind(text, expandedKind);
            if (!aligned.equals(text)) {
                target.setText(aligned);
                return 1;
            }
        }
        return 0;
    }

    private int alignOneExpandedTimerField(EditText target, int statusKind) {
        if (target == null) return 0;
        String text = target.getText() == null ? "" : target.getText().toString().trim();
        int kind = detectTimerKind(text);
        if ((statusKind == -1 || statusKind == 1)
                && (kind == -1 || kind == 1)
                && statusKind != kind) {
            String aligned = forceTimerKind(text, statusKind);
            if (!aligned.equals(text)) {
                target.setText(aligned);
                return 1;
            }
        }
        return 0;
    }

    private int detectTimerKind(String text) {
        if (text == null || text.isEmpty()) return 0;
        boolean hasCountdown = text.contains("{\u5012\u8ba1\u65f6}");
        boolean hasElapsed = text.contains("{\u6b63\u8ba1\u65f6}");
        if (hasCountdown && hasElapsed) return 2;
        if (hasCountdown) return -1;
        if (hasElapsed) return 1;
        return 0;
    }

    private String forceTimerKind(String text, int targetKind) {
        if (text == null) return "";
        final String countdown = "{\u5012\u8ba1\u65f6}";
        final String elapsed = "{\u6b63\u8ba1\u65f6}";
        if (targetKind >= 0) {
            return text.replace(countdown, elapsed);
        }
        return text.replace(elapsed, countdown);
    }

    private void refreshCustomCardFromPrefs() {
        SharedPreferences sp = getConfigPrefs();
        for (int i = 0; i < 3; i++) {
            ((EditText) findViewById(CUSTOM_IDS_A[i])).setText(
                    PrefsAccess.readStagedTemplate(sp, "tpl_a", CUSTOM_SUFFIXES[i], ""));
            ((EditText) findViewById(CUSTOM_IDS_B[i])).setText(
                    PrefsAccess.readStagedTemplate(sp, "tpl_b", CUSTOM_SUFFIXES[i], ""));
            ((EditText) findViewById(CUSTOM_IDS_TICKER[i])).setText(
                    PrefsAccess.readStagedTemplate(sp, "tpl_ticker", CUSTOM_SUFFIXES[i], ""));
            for (int k = 0; k < ConfigDefaults.EXPANDED_TPL_KEYS.length; k++) {
                ((EditText) findViewById(CUSTOM_IDS_EXPANDED_V2[k][i])).setText(
                        PrefsAccess.readStagedString(sp, ConfigDefaults.EXPANDED_TPL_KEYS[k],
                                CUSTOM_SUFFIXES[i], defaultExpandedTpl(i, k)));
            }
        }
        ((SwitchMaterial) findViewById(R.id.sw_icon_a))
                .setChecked(PrefsAccess.readConfigBool(sp, "icon_a", true));
        customDirty = false;
        updateCustomDirtyIndicator();
    }

    /**
     * 检查「自定义模板」卡片是否有未保存变更
     */
    private boolean isStatusCustomDirty() {
        SharedPreferences sp = getConfigPrefs();
        final String[] SUFFIXES = ConfigDefaults.STAGE_SUFFIXES;
        final int[] IDS_A      = {R.id.et_tpl_a_pre,      R.id.et_tpl_a_active,      R.id.et_tpl_a_post};
        final int[] IDS_B      = {R.id.et_tpl_b_pre,      R.id.et_tpl_b_active,      R.id.et_tpl_b_post};
        final int[] IDS_TICKER = {R.id.et_tpl_ticker_pre,  R.id.et_tpl_ticker_active,  R.id.et_tpl_ticker_post};
        for (int i = 0; i < 3; i++) {
            String curA = ((EditText) findViewById(IDS_A[i])).getText().toString().trim();
            String curB = ((EditText) findViewById(IDS_B[i])).getText().toString().trim();
            String curT = ((EditText) findViewById(IDS_TICKER[i])).getText().toString().trim();
            String sA = PrefsAccess.readStagedTemplate(sp, "tpl_a", SUFFIXES[i], "");
            String sB = PrefsAccess.readStagedTemplate(sp, "tpl_b", SUFFIXES[i], "");
            String sT = PrefsAccess.readStagedTemplate(sp, "tpl_ticker", SUFFIXES[i], "");
            if (!curA.equals(sA) || !curB.equals(sB) || !curT.equals(sT)) return true;
        }
        SwitchMaterial swIconA = findViewById(R.id.sw_icon_a);
        if (swIconA.isChecked() != PrefsAccess.readConfigBool(sp, "icon_a", true)) return true;
        return false;
    }

    private boolean isExpandedCustomDirty() {
        SharedPreferences sp = getConfigPrefs();
        final String[] SUFFIXES = ConfigDefaults.STAGE_SUFFIXES;
        for (int i = 0; i < 3; i++) {
            for (int k = 0; k < ConfigDefaults.EXPANDED_TPL_KEYS.length; k++) {
                String curV = ((EditText) findViewById(CUSTOM_IDS_EXPANDED_V2[k][i])).getText().toString().trim();
                String saveV = PrefsAccess.readStagedString(sp, ConfigDefaults.EXPANDED_TPL_KEYS[k],
                        SUFFIXES[i], defaultExpandedTpl(i, k));
                if (!curV.equals(saveV)) return true;
            }
        }
        return false;
    }

    private String defaultExpandedTpl(int stageIndex, int keyIndex) {
        return ConfigDefaults.expandedTemplateDefault(stageIndex, keyIndex, "");
    }

    private void applyExpandedFieldOrderHints() {
        for (int stage = 0; stage < ConfigDefaults.STAGE_PHASES.length; stage++) {
            for (int field = 0; field < ConfigDefaults.EXPANDED_TPL_EDIT_IDS.length
                    && field < ConfigDefaults.EXPANDED_TPL_HINTS.length; field++) {
                setTextInputLayoutHint(
                        ConfigDefaults.EXPANDED_TPL_EDIT_IDS[field][stage],
                        ConfigDefaults.EXPANDED_TPL_HINTS[field]);
            }
        }
    }

    private void setTextInputLayoutHint(int editTextId, String hint) {
        View child = findViewById(editTextId);
        if (child == null) return;
        View parent = (View) child.getParent();
        if (parent instanceof TextInputLayout) {
            ((TextInputLayout) parent).setHint(hint);
        }
    }
    /**
     * 检查「超时设置」卡片当前可见项是否与已保存值不同（表明存在未保存修改）
     */
    private boolean isTimeoutDirty() {
        TimeoutConfig saved = TimeoutConfig.read(getConfigPrefs());
        // 岛：当前阶段
        MaterialButtonToggleGroup toggleIslandPhase = findViewById(R.id.toggle_island_phase);
        int idxIsland = TimeoutCardController.stageIndexFromButtonId(
                toggleIslandPhase.getCheckedButtonId(), ISLAND_PHASE_BUTTON_IDS);
        EditText etIsland = findViewById(R.id.et_island_to);
        String curIslandStr = etIsland.getText() != null ? etIsland.getText().toString().trim() : "";
        int curIslandVal = curIslandStr.isEmpty() ? ConfigDefaults.TIMEOUT_VALUE : tryParseInt(curIslandStr, ConfigDefaults.TIMEOUT_VALUE);
        MaterialButtonToggleGroup toggleIslandUnit = findViewById(R.id.toggle_island_unit);
        String curIslandUnit = (toggleIslandUnit.getCheckedButtonId() == R.id.btn_island_s) ? "s" : "m";
        SwitchMaterial swIslandDefault = findViewById(R.id.sw_island_to_default);
        int savedIsVal = saved.islandVals[idxIsland];
        String savedIsUnit = saved.islandUnits[idxIsland];
        boolean savedIslandDefault = savedIsVal < 0;
        if (swIslandDefault.isChecked() != savedIslandDefault) return true;
        if (!swIslandDefault.isChecked()) {
            if (curIslandVal != savedIsVal) return true;
            if (!curIslandUnit.equals(savedIsUnit)) return true;
        }

        // 通知：仅允许单选一个触发阶段
        MaterialButtonToggleGroup toggleNotifPhase = findViewById(R.id.toggle_notif_phase);
        int idxNotif = TimeoutCardController.stageIndexFromButtonId(
                toggleNotifPhase.getCheckedButtonId(), NOTIF_PHASE_BUTTON_IDS);
        SwitchMaterial swNotifDefault  = findViewById(R.id.sw_notif_to_default);
        EditText etNotif = findViewById(R.id.et_notif_to);
        String curNotifStr = etNotif.getText() != null ? etNotif.getText().toString().trim() : "";
        int curNotifVal = curNotifStr.isEmpty() ? ConfigDefaults.TIMEOUT_VALUE : tryParseInt(curNotifStr, ConfigDefaults.TIMEOUT_VALUE);
        MaterialButtonToggleGroup toggleNotifUnit = findViewById(R.id.toggle_notif_unit);
        String curNotifUnit = (toggleNotifUnit.getCheckedButtonId() == R.id.btn_notif_s) ? "s" : "m";
        if (swNotifDefault.isChecked() != saved.notifGlobalDefault) return true;
        if (!swNotifDefault.isChecked()) {
            if (idxNotif != saved.notifTriggerStage) return true;
            int savedNoVal = saved.notifVals[saved.notifTriggerStage];
            String savedNoUnit = saved.notifUnits[saved.notifTriggerStage];
            if (curNotifVal != savedNoVal) return true;
            if (!curNotifUnit.equals(savedNoUnit)) return true;
        }

        // 若都一致，则无未保存项
        return false;
    }

    private int tryParseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private void updateCustomDirtyIndicator() {
        boolean statusDirty = isStatusCustomDirty();
        boolean expandedDirty = isExpandedCustomDirty();
        boolean d = statusDirty || expandedDirty;
        customDirty = d;
        if (btnSaveCustom != null) {
            int statusColor = statusDirty ? Color.parseColor("#FF9800")
                    : MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#6200EE"));
            btnSaveCustom.setBackgroundTintList(ColorStateList.valueOf(statusColor));
        }
        if (btnSaveExpanded != null) {
            int expandedColor = expandedDirty ? Color.parseColor("#FF9800")
                    : MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#6200EE"));
            btnSaveExpanded.setBackgroundTintList(ColorStateList.valueOf(expandedColor));
        }
        int color = d ? Color.parseColor("#FF9800")
                : MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#6200EE"));
        if (btnSaveCustom != null && btnSaveExpanded == null) {
            btnSaveCustom.setBackgroundTintList(ColorStateList.valueOf(color));
        }
    }

    private void updateTimeoutDirtyIndicator() {
        boolean d = isTimeoutDirty();
        timeoutDirty = d;
        if (btnSaveTimeout == null) return;
        int color = d ? Color.parseColor("#FF9800")
                : MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#6200EE"));
        btnSaveTimeout.setBackgroundTintList(ColorStateList.valueOf(color));
    }

    // ─────────────────────────────────────────────────────────────
    // 超时设置卡片
    // ─────────────────────────────────────────────────────────────

    private static final String KEY_NOTIF_DISMISS_TRIGGER = ConfigDefaults.KEY_NOTIF_DISMISS_TRIGGER;

    /**
     * 初始化「消失时间」卡片。
     * 岛消失：全局单一值（每次 notify 都会重置，分阶段无意义）。
     * 通知消失：三阶段独立（实现为 AlarmManager + nm.cancel()）。
     * val == -1 表示系统默认；val > 0 表示自定义。
     */
    private void initTimeoutCard() {
        btnSaveTimeout = findViewById(R.id.btn_save_timeout);
        TimeoutCardController.bind(this, getConfigPrefs(), this::updateTimeoutDirtyIndicator);
    }

    private static final String ALIAS = "com.xiaoai.islandnotify.MainActivityAlias";

    /**
     * 初始化「课前提醒」卡片。
     * 仅开放提醒时间自定义，保存时同步 reminder_minutes_before 到 voiceassist 进程。
     */
    private void initReminderCard() {
        ReminderCardController.bind(
                this,
                readConfigInt("reminder_minutes_before", ConfigDefaults.REMINDER_MINUTES),
                ConfigDefaults.REMINDER_MINUTES,
                minutes -> editConfigPrefs().putInt("reminder_minutes_before", minutes).apply());
    }
    /**
     * Initialize mute and DND card.
     */

    private void initMuteCard() {
        MuteCardController.bind(this, getConfigPrefs());
    }

    private void initWakeupCard() {
        WakeupCardController.bind(this, getConfigPrefs());
    }

    private void initHideIconSwitch() {
        SwitchMaterial sw = findViewById(R.id.sw_hide_icon);
        PackageManager pm = getPackageManager();
        ComponentName alias = new ComponentName(this, ALIAS);

        // 读取当前状态：第一次安装后为 DEFAULT（显示）或 ENABLED
        int state = pm.getComponentEnabledSetting(alias);
        sw.setChecked(state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED);

        sw.setOnCheckedChangeListener((btn, checked) -> {
            pm.setComponentEnabledSetting(alias,
                    checked ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        });
    }

    // ─────────────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    /** 初始化关于页面，动态显示版本号、作者跳转 */
    private void initAboutSection() {
        TextView versionText = findViewById(R.id.version_text);
        if (versionText != null) versionText.setText(getAppVersionName());

        TextView tvAuthor = findViewById(R.id.tv_author);
        if (tvAuthor != null) {
            tvAuthor.setOnClickListener(v -> {
                try {
                    android.content.Intent intent = new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.coolapk.com/u/3336736"));
                    startActivity(intent);
                } catch (Exception ignored) {}
            });
        }

    }

    private String getTargetVersion(String pkg) {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(pkg, 0);
            long code = 0L;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                code = info.getLongVersionCode();
            } else {
                code = info.versionCode;
            }
            return info.versionName + " (" + code + ")";
        } catch (Throwable t) {
            return "未安装或不可见";
        }
    }

    private int getTodayMarker() {
        Calendar cal = Calendar.getInstance();
        return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR);
    }

    private int countHolidayEntries(String json) {
        if (json == null || json.isEmpty()) return 0;
        try {
            return new org.json.JSONArray(json).length();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 获取应用版本名称 */
    private String getAppVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            Log.e("MainActivity", "获取版本信息失败", e);
            return "未知版本";
        }
    }

    /**
     * 向目标进程（com.miui.voiceassist）发送广播，由目标进程在其自身上下文中发出测试通知。
     * 通知将经过完整的 Xposed Hook 路径，与真实课程提醒行为一致。
     */
    private void sendTestBroadcastToTarget(long startOffsetMs) {
        android.widget.EditText etName      = findViewById(R.id.et_course_name);
        android.widget.EditText etClassroom = findViewById(R.id.et_classroom);
        String courseName = etName.getText() != null ? etName.getText().toString().trim() : "";
        String classroom  = etClassroom.getText() != null ? etClassroom.getText().toString().trim() : "";
        String sectionRange = "1-2";
        String teacher = "\u6d4b\u8bd5\u6559\u5e08";
        if (courseName.isEmpty()) courseName = "高等数学";
        if (classroom.isEmpty())  classroom  = "教科A-101";

        long now     = System.currentTimeMillis();
        // 对齐到整分钟（second=0, ms=0），与 MainHook.computeClassStartMs 行为一致，
        // 避免 start_ms 带亚秒偏移导致静音闹钟在"整点"后若干秒才触发
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now + startOffsetMs);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long startMs = cal.getTimeInMillis();
        long endMs   = startMs + 60_000L;   // 测试课程时长 1 分钟，同样整分钟对齐
        cal.setTimeInMillis(startMs);
        String startTime = String.format(java.util.Locale.getDefault(), "%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
        cal.setTimeInMillis(endMs);
        String endTime = String.format(java.util.Locale.getDefault(), "%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));

        // 直接从 SP 读取当前静音设置一起带入 intent，避免 voiceassist 读 SP 缓存旧値
        boolean muteEnabled   = readConfigBool("mute_enabled", ConfigDefaults.SWITCH_DISABLED);
        int     muteBefore    = readConfigInt("mute_mins_before", ConfigDefaults.MINUTES_OFFSET);
        boolean unmuteEnabled = readConfigBool("unmute_enabled", ConfigDefaults.SWITCH_DISABLED);
        int     unmuteAfter   = readConfigInt("unmute_mins_after", ConfigDefaults.MINUTES_OFFSET);
        boolean dndEnabled    = readConfigBool("dnd_enabled", ConfigDefaults.SWITCH_DISABLED);
        int     dndBefore     = readConfigInt("dnd_mins_before", ConfigDefaults.MINUTES_OFFSET);
        boolean unDndEnabled  = readConfigBool("undnd_enabled", ConfigDefaults.SWITCH_DISABLED);
        int     unDndAfter    = readConfigInt("undnd_mins_after", ConfigDefaults.MINUTES_OFFSET);

        Intent intent = new Intent("com.xiaoai.islandnotify.ACTION_TEST_NOTIFY");
        intent.setPackage("com.miui.voiceassist");
        intent.putExtra("course_name", courseName);
        intent.putExtra("start_time",  startTime);
        intent.putExtra("end_time",    endTime);
        intent.putExtra("classroom",   classroom);
        intent.putExtra("section_range", sectionRange);
        intent.putExtra("teacher", teacher);
        intent.putExtra("mute_enabled",      muteEnabled);
        intent.putExtra("mute_mins_before",  muteBefore);
        intent.putExtra("unmute_enabled",    unmuteEnabled);
        intent.putExtra("unmute_mins_after", unmuteAfter);
        intent.putExtra("dnd_enabled",       dndEnabled);
        intent.putExtra("dnd_mins_before",   dndBefore);
        intent.putExtra("undnd_enabled",     unDndEnabled);
        intent.putExtra("undnd_mins_after",  unDndAfter);
        // 把精确的课程开始/结束毫秒时间戳带入，让 voiceassist 端完全复用真实调度逻辑
        intent.putExtra("start_ms", startMs);
        intent.putExtra("end_ms",   endMs);
        sendBroadcast(intent);
    }

    private void showTestHint(String msg) {
        TextView tv = findViewById(R.id.tv_test_hint);
        tv.setText(msg);
        tv.setVisibility(View.VISIBLE);
    }

    // ─────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────
    // Tab 切换
    // ─────────────────────────────────────────────────────────────

    private int mCurrentTabIndex = -1;

    private void setupTabs() {
        TabLayout tabLayout = findViewById(R.id.tab_layout);
        tabLayout.addTab(tabLayout.newTab().setText("设置"));
        tabLayout.addTab(tabLayout.newTab().setText("假期/调休"));
        tabLayout.addTab(tabLayout.newTab().setText("关于"));
        showTab(0);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showTab(tab.getPosition());
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // 水平滑动手势切换 Tab
        android.view.GestureDetector swipeDetector = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                          float velocityX, float velocityY) {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 80
                                && Math.abs(velocityX) > 100) {
                            int pos = tabLayout.getSelectedTabPosition();
                            if (dx < 0 && pos < tabLayout.getTabCount() - 1)
                                tabLayout.selectTab(tabLayout.getTabAt(pos + 1));
                            else if (dx > 0 && pos > 0)
                                tabLayout.selectTab(tabLayout.getTabAt(pos - 1));
                            return true;
                        }
                        return false;
                    }
                });
        androidx.core.widget.NestedScrollView scrollView = findViewById(R.id.scroll_view);
        scrollView.setOnTouchListener((v, event) -> {
            swipeDetector.onTouchEvent(event);
            return false;
        });
    }

    private void showTab(int newIndex) {
        int[] ids = {R.id.container_settings, R.id.container_holiday, R.id.container_about};
        View[] tabs = new View[ids.length];
        for (int i = 0; i < ids.length; i++) tabs[i] = findViewById(ids[i]);
        if (tabs[0] == null) return;

        if (mCurrentTabIndex == -1) {
            for (int i = 0; i < tabs.length; i++) {
                tabs[i].setTranslationX(0f);
                tabs[i].setVisibility(i == newIndex ? View.VISIBLE : View.GONE);
            }
            mCurrentTabIndex = newIndex;
            return;
        }
        if (newIndex == mCurrentTabIndex) return;

        View outView = tabs[mCurrentTabIndex];
        View inView  = tabs[newIndex];
        float sign = newIndex > mCurrentTabIndex ? 1f : -1f;
        View parent = (View) tabs[0].getParent();
        float w = (parent != null && parent.getWidth() > 0) ? parent.getWidth() : 1080f;
        outView.animate().cancel();
        inView.animate().cancel();
        inView.setTranslationX(sign * w);
        inView.setVisibility(View.VISIBLE);
        android.view.animation.Interpolator interp =
                new android.view.animation.DecelerateInterpolator(1.5f);
        outView.animate().translationX(-sign * w).setDuration(300)
                .setInterpolator(interp)
                .withEndAction(() -> {
                    outView.setVisibility(View.GONE);
                    outView.setTranslationX(0f);
                }).start();
        inView.animate().translationX(0f).setDuration(300)
                .setInterpolator(interp).start();
        mCurrentTabIndex = newIndex;
    }

    // ─────────────────────────────────────────────────────────────
    // 假期/调休 Tab 初始化
    // ─────────────────────────────────────────────────────────────

    private void initHolidayTab() {
        // 默认年份：取当前年
        java.util.Calendar cal = java.util.Calendar.getInstance();
        mCurrentHolidayYear = cal.get(java.util.Calendar.YEAR);

        // View 引用
        mLlHolidayList    = findViewById(R.id.ll_holiday_list);
        mLlWorkswapList   = findViewById(R.id.ll_workswap_list);
        mTvHolidayEmpty   = findViewById(R.id.tv_holiday_empty);
        mTvWorkswapEmpty  = findViewById(R.id.tv_workswap_empty);
        TextView tvFetchHint = findViewById(R.id.tv_holiday_fetch_hint);

        // ── 年份选择（自由输入） ───────────────────────────────────
        MaterialButton btnYear = findViewById(R.id.btn_year_picker);
        btnYear.setText(String.valueOf(mCurrentHolidayYear));
        btnYear.setOnClickListener(v -> {
            android.widget.NumberPicker np = new android.widget.NumberPicker(this);
            np.setMinValue(2020);
            np.setMaxValue(2099);
            np.setValue(mCurrentHolidayYear);
            np.setWrapSelectorWheel(false);
            android.widget.FrameLayout fl = new android.widget.FrameLayout(this);
            android.widget.FrameLayout.LayoutParams nlp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            np.setLayoutParams(nlp);
            fl.addView(np);
            new AlertDialog.Builder(this)
                    .setTitle("选择年份")
                    .setView(fl)
                    .setPositiveButton("确定", (d, w) -> {
                        mCurrentHolidayYear = np.getValue();
                        btnYear.setText(String.valueOf(mCurrentHolidayYear));
                        renderHolidayLists();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        // ── 从网络获取（当年 + 次年） ──────────────────────────────
        findViewById(R.id.btn_fetch_holiday).setOnClickListener(v -> {
            tvFetchHint.setText("正在获取…");
            tvFetchHint.setVisibility(View.VISIBLE);
            int year = mCurrentHolidayYear;
            new Thread(() -> {
                try {
                    URL url = new URL("https://unpkg.com/holiday-calendar@1.3.0/data/CN/" + year + ".json");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10_000);
                    conn.setReadTimeout(10_000);
                    conn.setRequestProperty("User-Agent", "XiaoaiIsland/1.0");
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    conn.disconnect();

                    List<HolidayManager.HolidayEntry> apiEntries =
                            HolidayManager.parseApiResponse(sb.toString());
                    if (apiEntries.isEmpty()) {
                        runOnUiThread(() -> {
                            tvFetchHint.setText(year + "年暂无数据");
                            tvFetchHint.setVisibility(View.VISIBLE);
                        });
                        return;
                    }
                    HolidayManager.mergeAndSave(this, year, apiEntries);
                    syncHolidayToHook(year);
                    for (HolidayManager.HolidayEntry e : apiEntries)
                        rescheduleIfCoversToday(e.date,
                                e.endDate != null && !e.endDate.isEmpty() ? e.endDate : e.date);
                    int hCount = 0, wCount = 0;
                    java.text.SimpleDateFormat sCnt =
                            new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
                    for (HolidayManager.HolidayEntry e : apiEntries) {
                        int days = 1;
                        if (e.endDate != null && !e.endDate.isEmpty()) {
                            try {
                                java.util.Date d1 = sCnt.parse(e.date);
                                java.util.Date d2 = sCnt.parse(e.endDate);
                                days = (int) ((d2.getTime() - d1.getTime()) / 86400000L) + 1;
                            } catch (Exception ignored) {}
                        }
                        if (e.type == HolidayManager.TYPE_HOLIDAY) hCount += days; else wCount += days;
                    }
                    int fH = hCount, fW = wCount;
                    runOnUiThread(() -> {
                        renderHolidayLists();
                        tvFetchHint.setText("获取完成：节假日 " + fH + " 天，调休 " + fW + " 天");
                        tvFetchHint.setVisibility(View.VISIBLE);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        tvFetchHint.setText("获取失败：" + e.getMessage());
                        tvFetchHint.setVisibility(View.VISIBLE);
                    });
                }
            }).start();
        });

        // ── 清除本年 ───────────────────────────────────────────────
        findViewById(R.id.btn_clear_all_holiday).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("清除本年")
                        .setMessage("将清除 " + mCurrentHolidayYear + " 年已保存的全部假期和调休数据（包括自定义条目）。确定吗？")
                        .setPositiveButton("清除", (d, w) -> {
                            List<HolidayManager.HolidayEntry> old =
                                    HolidayManager.loadEntries(this, mCurrentHolidayYear);
                            HolidayManager.saveEntries(this, mCurrentHolidayYear, new java.util.ArrayList<>());
                            syncHolidayToHook(mCurrentHolidayYear);
                            for (HolidayManager.HolidayEntry e : old)
                                rescheduleIfCoversToday(e.date,
                                        e.endDate != null && !e.endDate.isEmpty() ? e.endDate : e.date);
                            renderHolidayLists();
                            Toast.makeText(this, "已清除 " + mCurrentHolidayYear + " 年假期数据", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("取消", null)
                        .show());

        // ── 新增节假日 ─────────────────────────────────────────────
        findViewById(R.id.btn_add_holiday).setOnClickListener(v ->
                showAddHolidayDialog(null));

        // ── 新增调休工作日 ─────────────────────────────────────────
        findViewById(R.id.btn_add_workswap).setOnClickListener(v ->
                showAddWorkSwapDialog(null));

        // 初始渲染
        renderHolidayLists();
    }

    // ── 渲染列表 ───────────────────────────────────────────────────

    private void renderHolidayLists() {
        if (mLlHolidayList == null) return;
        List<HolidayManager.HolidayEntry> all = HolidayManager.loadEntries(this, mCurrentHolidayYear);

        List<HolidayManager.HolidayEntry> holidays  = new ArrayList<>();
        List<HolidayManager.HolidayEntry> workswaps = new ArrayList<>();
        for (HolidayManager.HolidayEntry e : all) {
            if (e.type == HolidayManager.TYPE_HOLIDAY) holidays.add(e);
            else                                       workswaps.add(e);
        }

        // 节假日列表
        mLlHolidayList.removeAllViews();
        for (HolidayManager.HolidayEntry e : holidays)
            mLlHolidayList.addView(createHolidayRow(e, all));
        mTvHolidayEmpty.setVisibility(holidays.isEmpty() ? View.VISIBLE : View.GONE);

        // 调休工作日列表
        mLlWorkswapList.removeAllViews();
        for (HolidayManager.HolidayEntry e : workswaps)
            mLlWorkswapList.addView(createWorkSwapRow(e, all));
        mTvWorkswapEmpty.setVisibility(workswaps.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** 节假日条目行视图 */
    private View createHolidayRow(HolidayManager.HolidayEntry entry,
                                   List<HolidayManager.HolidayEntry> all) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dpToPx(8), 0, dpToPx(8));

        // 信息区
        android.widget.LinearLayout textArea = new android.widget.LinearLayout(this);
        textArea.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textArea.setLayoutParams(lp);

        TextView tvMain = new TextView(this);
        tvMain.setTextSize(15f);
        tvMain.setTypeface(null, android.graphics.Typeface.BOLD);
        String dateLabel;
        if (entry.endDate != null && !entry.endDate.isEmpty() && !entry.endDate.equals(entry.date)) {
            dateLabel = formatShortDate(entry.date) + "–" + formatShortDate(entry.endDate);
        } else {
            dateLabel = formatShortDate(entry.date);
        }
        tvMain.setText(dateLabel + "  " + entry.name);
        textArea.addView(tvMain);

        TextView tvTag = new TextView(this);
        tvTag.setTextSize(11f);
        tvTag.setText(entry.isCustom ? "自定义节假日" : "API 节假日");
        tvTag.setTextColor(entry.isCustom ? 0xFF7965AF : 0xFF389E0D);
        textArea.addView(tvTag);

        row.addView(textArea);

        // 编辑按钮
        MaterialButton btnEdit = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnEdit.setText("编辑");
        btnEdit.setTextSize(12f);
        android.widget.LinearLayout.LayoutParams ep =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        dpToPx(36));
        ep.setMarginStart(dpToPx(8));
        btnEdit.setLayoutParams(ep);
        btnEdit.setOnClickListener(v -> showAddHolidayDialog(entry));
        row.addView(btnEdit);

        // 删除按钮
        MaterialButton btnDel = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnDel.setText("删除");
        btnDel.setTextSize(12f);
        btnDel.setStrokeColor(ColorStateList.valueOf(0xFFBA1A1A));
        btnDel.setTextColor(0xFFBA1A1A);
        android.widget.LinearLayout.LayoutParams dp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        dpToPx(36));
        dp.setMarginStart(dpToPx(4));
        btnDel.setLayoutParams(dp);
        btnDel.setOnClickListener(v -> {
            all.remove(entry);
            HolidayManager.saveEntries(this, mCurrentHolidayYear, all);
            syncHolidayToHook(mCurrentHolidayYear);
            rescheduleIfCoversToday(entry.date, entry.endDate);
            renderHolidayLists();
        });
        row.addView(btnDel);
        return row;
    }

    /** 将 "yyyy-MM-dd" 格式化为 "M/d" 短形式 */
    private String formatShortDate(String isoDate) {
        if (isoDate == null || isoDate.length() < 10) return isoDate != null ? isoDate : "";
        try {
            int m = Integer.parseInt(isoDate.substring(5, 7));
            int d = Integer.parseInt(isoDate.substring(8, 10));
            return m + "/" + d;
        } catch (Exception e) {
            return isoDate;
        }
    }

    /** 调休工作日条目行视图（带编辑按钮） */
    private View createWorkSwapRow(HolidayManager.HolidayEntry entry,
                                    List<HolidayManager.HolidayEntry> all) {
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dpToPx(8), 0, dpToPx(4));

        // 信息区
        android.widget.LinearLayout textArea = new android.widget.LinearLayout(this);
        textArea.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textArea.setLayoutParams(lp);

        TextView tvMain = new TextView(this);
        tvMain.setTextSize(15f);
        tvMain.setTypeface(null, android.graphics.Typeface.BOLD);
        String wsDateLabel;
        if (entry.endDate != null && !entry.endDate.isEmpty())
            wsDateLabel = formatShortDate(entry.date) + "\u2013" + formatShortDate(entry.endDate);
        else
            wsDateLabel = formatShortDate(entry.date);
        tvMain.setText(wsDateLabel + "  " + entry.name);
        textArea.addView(tvMain);

        TextView tvFollow = new TextView(this);
        tvFollow.setTextSize(13f);
        tvFollow.setText("替换为: " + entry.followDesc());
        tvFollow.setTextColor(0xFF6750A4);
        textArea.addView(tvFollow);

        TextView tvTag = new TextView(this);
        tvTag.setTextSize(11f);
        tvTag.setText(entry.isCustom ? "自定义调休" : "API 调休");
        tvTag.setTextColor(entry.isCustom ? 0xFF7965AF : 0xFF389E0D);
        textArea.addView(tvTag);

        row.addView(textArea);

        // 编辑按钮
        MaterialButton btnEdit = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnEdit.setText("编辑");
        btnEdit.setTextSize(12f);
        btnEdit.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(36)));
        btnEdit.setOnClickListener(v -> showAddWorkSwapDialog(entry));
        row.addView(btnEdit);

        // 删除按钮
        MaterialButton btnDel = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnDel.setText("删除");
        btnDel.setTextSize(12f);
        btnDel.setStrokeColor(ColorStateList.valueOf(0xFFBA1A1A));
        btnDel.setTextColor(0xFFBA1A1A);
        android.widget.LinearLayout.LayoutParams dp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        dpToPx(36));
        dp.setMarginStart(dpToPx(4));
        btnDel.setLayoutParams(dp);
        btnDel.setOnClickListener(v -> {
            all.remove(entry);
            HolidayManager.saveEntries(this, mCurrentHolidayYear, all);
            syncHolidayToHook(mCurrentHolidayYear);
            rescheduleIfCoversToday(entry.date, null);
            renderHolidayLists();
        });
        row.addView(btnDel);

        container.addView(row);
        return container;
    }

    // ── 新增/编辑对话框 ──────────────────────────────────────────────

    /** 弹出新增/编辑节假日对话框 */
    private void showAddHolidayDialog(HolidayManager.HolidayEntry editEntry) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int p = dpToPx(24);
        layout.setPadding(p, dpToPx(16), p, 0);

        final String[] startDate = {editEntry != null ? editEntry.date : mCurrentHolidayYear + "-01-01"};
        final String[] endDate = {editEntry != null && editEntry.endDate != null ? editEntry.endDate : ""};

        MaterialButton btnStartDate = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnStartDate.setText("开始日期: " + startDate[0]);
        btnStartDate.setAllCaps(false);
        layout.addView(btnStartDate);

        MaterialButton btnEndDate = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnEndDate.setText("结束日期: " + (endDate[0].isEmpty() ? "仅当天" : endDate[0]));
        btnEndDate.setAllCaps(false);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dpToPx(8);
        btnEndDate.setLayoutParams(elp);
        layout.addView(btnEndDate);

        btnStartDate.setOnClickListener(v -> {
            String[] parts = startDate[0].split("-");
            new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                startDate[0] = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth);
                btnStartDate.setText("开始日期: " + startDate[0]);
            }, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2])).show();
        });

        btnEndDate.setOnClickListener(v -> {
            String base = endDate[0].isEmpty() ? startDate[0] : endDate[0];
            String[] parts = base.split("-");
            new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                endDate[0] = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth);
                btnEndDate.setText("结束日期: " + endDate[0]);
            }, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2])).show();
        });

        EditText etName = new EditText(this);
        etName.setHint("名称（如：春节、放假）");
        etName.setSingleLine(true);
        android.widget.LinearLayout.LayoutParams np = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dpToPx(12);
        etName.setLayoutParams(np);
        if (editEntry != null) etName.setText(editEntry.name);
        layout.addView(etName);

        new AlertDialog.Builder(this)
                .setTitle(editEntry == null ? "新增节假日" : "编辑节假日")
                .setView(layout)
                .setPositiveButton("确定", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) name = "节假日";
                    List<HolidayManager.HolidayEntry> all = HolidayManager.loadEntries(this, mCurrentHolidayYear);
                    if (editEntry != null) {
                        final String ed = editEntry.date;
                        final String ee = editEntry.endDate != null ? editEntry.endDate : "";
                        final String en = editEntry.name;
                        final int    et = editEntry.type;
                        all.removeIf(e -> ed.equals(e.date)
                                && ee.equals(e.endDate != null ? e.endDate : "")
                                && en.equals(e.name) && et == e.type);
                    }
                    HolidayManager.HolidayEntry e =
                            new HolidayManager.HolidayEntry(startDate[0], endDate[0], name, HolidayManager.TYPE_HOLIDAY, true);
                    all.add(e);
                    all.sort((a, b) -> a.date.compareTo(b.date));
                    HolidayManager.saveEntries(this, mCurrentHolidayYear, all);
                    syncHolidayToHook(mCurrentHolidayYear);
                    rescheduleIfCoversToday(startDate[0], endDate[0].isEmpty() ? startDate[0] : endDate[0]);
                    if (editEntry != null)
                        rescheduleIfCoversToday(editEntry.date,
                                editEntry.endDate != null ? editEntry.endDate : editEntry.date);
                    renderHolidayLists();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 弹出新增/编辑调休工作日对话框 */
    private void showAddWorkSwapDialog(HolidayManager.HolidayEntry editEntry) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int p = dpToPx(24);
        layout.setPadding(p, dpToPx(16), p, dpToPx(8));

        final String[] date = {editEntry != null ? editEntry.date : mCurrentHolidayYear + "-01-01"};

        MaterialButton btnDate = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnDate.setText("选择日期: " + date[0]);
        btnDate.setAllCaps(false);
        layout.addView(btnDate);

        btnDate.setOnClickListener(v -> {
            String[] parts = date[0].split("-");
            new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                date[0] = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth);
                btnDate.setText("选择日期: " + date[0]);
            }, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2])).show();
        });

        EditText etName = new EditText(this);
        etName.setHint("名称（如：补周一课）");
        etName.setSingleLine(true);
        android.widget.LinearLayout.LayoutParams np = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dpToPx(12);
        etName.setLayoutParams(np);
        if (editEntry != null) etName.setText(editEntry.name);
        layout.addView(etName);

        TextView tvDesc = new TextView(this);
        tvDesc.setText("当天按以下周次/星期的课表上课：");
        tvDesc.setTextSize(13f);
        android.widget.LinearLayout.LayoutParams tp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dpToPx(16);
        tp.bottomMargin = dpToPx(6);
        tvDesc.setLayoutParams(tp);
        layout.addView(tvDesc);

        // 周次 + 星期 选择行
        android.widget.LinearLayout followRow = new android.widget.LinearLayout(this);
        followRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        followRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvPre = new TextView(this); tvPre.setText("第 ");
        followRow.addView(tvPre);

        Spinner spinnerWeek = new Spinner(this);
        int maxWeek = readTotalWeekFromCourseData();
        String[] weekItems = new String[maxWeek];
        for (int i = 0; i < maxWeek; i++) weekItems[i] = String.valueOf(i + 1);
        ArrayAdapter<String> wkAdp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, weekItems);
        wkAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerWeek.setAdapter(wkAdp);
        if (editEntry != null && editEntry.followWeek >= 1 && editEntry.followWeek <= maxWeek)
            spinnerWeek.setSelection(editEntry.followWeek - 1);
        followRow.addView(spinnerWeek);

        TextView tvMid = new TextView(this); tvMid.setText(" 周 ");
        followRow.addView(tvMid);

        Spinner spinnerWd = new Spinner(this);
        String[] wdItems = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        ArrayAdapter<String> wdAdp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, wdItems);
        wdAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerWd.setAdapter(wdAdp);
        if (editEntry != null && editEntry.followWeekday >= 1 && editEntry.followWeekday <= 7)
            spinnerWd.setSelection(editEntry.followWeekday - 1);
        followRow.addView(spinnerWd);

        TextView tvSuf = new TextView(this); tvSuf.setText(" 的课表");
        followRow.addView(tvSuf);
        layout.addView(followRow);

        new AlertDialog.Builder(this)
                .setTitle(editEntry == null ? "新增调休工作日" : "编辑调休工作日")
                .setView(layout)
                .setPositiveButton("确定", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) name = "调休工作日";
                    int week = spinnerWeek.getSelectedItemPosition() + 1;
                    int wd   = spinnerWd.getSelectedItemPosition()   + 1;
                    List<HolidayManager.HolidayEntry> all =
                            HolidayManager.loadEntries(this, mCurrentHolidayYear);
                    if (editEntry != null) {
                        final String ed = editEntry.date;
                        final String en = editEntry.name;
                        final int    et = editEntry.type;
                        all.removeIf(e -> ed.equals(e.date) && en.equals(e.name) && et == e.type);
                    }
                    HolidayManager.HolidayEntry e =
                            new HolidayManager.HolidayEntry(date[0], "", name, HolidayManager.TYPE_WORKSWAP, true);
                    e.followWeek    = week;
                    e.followWeekday = wd;
                    all.add(e);
                    all.sort((a, b) -> a.date.compareTo(b.date));
                    HolidayManager.saveEntries(this, mCurrentHolidayYear, all);
                    syncHolidayToHook(mCurrentHolidayYear);
                    rescheduleIfCoversToday(date[0], null);
                    if (editEntry != null) rescheduleIfCoversToday(editEntry.date, null);
                    renderHolidayLists();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 同步到 Hook 进程 ─────────────────────────────────────────────

    /**
     * 将指定年份的假期数据广播到 voiceassist 进程，使课前提醒调度能感知节假日 / 调休。
     */
    private void syncHolidayToHook(int year) {
        List<HolidayManager.HolidayEntry> entries = HolidayManager.loadEntries(this, year);
        String json = HolidayManager.entriesToJson(entries);
        editHolidayPrefs().putString("list_" + year, json).apply();
    }

    /** 若今天落在 [date, endDate] 范围内（含），向 Hook 发送重新调度广播。 */
    private void rescheduleIfCoversToday(String date, String endDate) {
        try {
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
            String today = sdf.format(new java.util.Date());
            boolean covers;
            if (endDate != null && !endDate.isEmpty()) {
                covers = today.compareTo(date) >= 0 && today.compareTo(endDate) <= 0;
            } else {
                covers = today.equals(date);
            }
            if (covers) {
                Intent reschedule = new Intent("com.xiaoai.islandnotify.ACTION_RESCHEDULE_DAILY");
                reschedule.setPackage("com.miui.voiceassist");
                sendBroadcast(reschedule);
            }
        } catch (Exception ignored) {}
    }

    // ── 工具 ─────────────────────────────────────────────────────────

    /** 读取目标进程通过广播同步到本地的学期总周数；失败时返回 30。 */
    private int readTotalWeekFromCourseData() {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE);
            int tw = sp.getInt("course_total_week", 0);
            android.util.Log.d("IslandNotify", "readTotalWeek: local tw=" + tw);
            if (tw > 0) return tw;
        } catch (Throwable e) {
            android.util.Log.e("IslandNotify", "readTotalWeek failed: " + e);
        }
        return 30;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void refreshUiFromPrefs(boolean fullRecreate) {
        if (mCustomCardBound) refreshCustomCardFromPrefs();
        refreshTimeoutCardFromPrefs();
        updateCustomDirtyIndicator();
        updateTimeoutDirtyIndicator();
    }

    private void refreshTimeoutCardFromPrefs() {
        TimeoutConfig cfg = TimeoutConfig.read(getConfigPrefs());

        MaterialButtonToggleGroup toggleIslandPhase = findViewById(R.id.toggle_island_phase);
        TextInputLayout tilIsland = findViewById(R.id.til_island_to);
        EditText etIsland = findViewById(R.id.et_island_to);
        MaterialButtonToggleGroup toggleIslandUnit = findViewById(R.id.toggle_island_unit);
        SwitchMaterial swIslandDefault = findViewById(R.id.sw_island_to_default);

        MaterialButtonToggleGroup toggleNotifPhase = findViewById(R.id.toggle_notif_phase);
        TextInputLayout tilNotif = findViewById(R.id.til_notif_to);
        EditText etNotif = findViewById(R.id.et_notif_to);
        MaterialButtonToggleGroup toggleNotifUnit = findViewById(R.id.toggle_notif_unit);
        SwitchMaterial swNotifDefault = findViewById(R.id.sw_notif_to_default);

        if (toggleIslandPhase == null || tilIsland == null || etIsland == null
                || toggleIslandUnit == null || swIslandDefault == null
                || toggleNotifPhase == null || tilNotif == null || etNotif == null
                || toggleNotifUnit == null || swNotifDefault == null) {
            return;
        }

        int checkedIsland = toggleIslandPhase.getCheckedButtonId();
        int idxIsland = TimeoutCardController.stageIndexFromButtonId(
                checkedIsland, ISLAND_PHASE_BUTTON_IDS);
        int savedIsVal = cfg.islandVals[idxIsland];
        String savedIsUnit = cfg.islandUnits[idxIsland];
        boolean islandDefault = savedIsVal < 0;
        swIslandDefault.setChecked(islandDefault);
        etIsland.setText(islandDefault ? "" : String.valueOf(savedIsVal));
        toggleIslandUnit.check("s".equals(savedIsUnit) ? R.id.btn_island_s : R.id.btn_island_m);
        TimeoutCardController.setTimeoutRowEnabled(tilIsland, toggleIslandUnit, !islandDefault);

        int triggerIdx = cfg.notifTriggerStage;
        swNotifDefault.setChecked(cfg.notifGlobalDefault);
        toggleNotifPhase.check(
                TimeoutCardController.buttonIdForStage(NOTIF_PHASE_BUTTON_IDS, triggerIdx));
        int savedNoVal = cfg.notifVals[triggerIdx];
        String savedNoUnit = cfg.notifUnits[triggerIdx];
        etNotif.setText(cfg.notifGlobalDefault || savedNoVal < 0 ? "" : String.valueOf(savedNoVal));
        toggleNotifUnit.check("s".equals(savedNoUnit) ? R.id.btn_notif_s : R.id.btn_notif_m);
        boolean notifEnabled = !cfg.notifGlobalDefault;
        TimeoutCardController.setTimeoutRowEnabled(tilNotif, toggleNotifUnit, notifEnabled);
        toggleNotifPhase.setEnabled(notifEnabled);
        toggleNotifPhase.setAlpha(notifEnabled ? 1f : 0.4f);
    }

    private void refreshAfterConfigSynced() {
        refreshUiFromPrefs(false);
    }

    private void showResetDefaultsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("恢复默认")
                .setMessage("将清空所有配置（本地 + LSPosed RemotePrefs）并恢复默认值，是否继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (d, w) -> {
                    int count = resetAllConfigToDefaults();
                    Toast.makeText(this, "已恢复默认配置：" + count + " 项", Toast.LENGTH_SHORT).show();
                    refreshUiFromPrefs(true);
                })
                .show();
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
                ed.putString(ConfigDefaults.EXPANDED_TPL_KEYS[k] + suffix, defaultExpandedTpl(i, k));
            }
        }
        ed.putBoolean("icon_a", true);
    }

    // ─────────────────────────────────────────────────────────────

}
