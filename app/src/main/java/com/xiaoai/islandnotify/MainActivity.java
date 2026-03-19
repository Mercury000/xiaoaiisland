package com.xiaoai.islandnotify;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
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
    private volatile SharedPreferences mRemoteDebugPrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener mLocalPrefMirrorListener;
    private SharedPreferences.OnSharedPreferenceChangeListener mLocalHolidayMirrorListener;
    private volatile boolean mScopeRequested = false;

    private static final String[] CUSTOM_SUFFIXES = {"_pre", "_active", "_post"};
    private static final String[] DEFAULT_TPL_A = {
            "{\u6559\u5ba4}", "{\u8bfe\u540d}", "{\u8bfe\u540d}"
    };
    private static final String[] DEFAULT_TPL_B = {
            "{\u5f00\u59cb}\u4e0a\u8bfe",
            "{\u7ed3\u675f}\u4e0b\u8bfe",
            "\u5df2\u7ecf\u4e0b\u8bfe"
    };
    /*
    private static final String[] DEFAULT_TPL_TICKER = {
            "{鏁欏}锝渰寮€濮媫涓婅",
            "{璇惧悕}锝渰缁撴潫}涓嬭",
            "{璇惧悕}锝滃凡缁忎笅璇?"
    };
    */
    private static final String[] DEFAULT_TPL_TICKER = {
            "{\u6559\u5ba4}\uFF5C{\u5f00\u59cb}\u4e0a\u8bfe",
            "{\u8bfe\u540d}\uFF5C{\u7ed3\u675f}\u4e0b\u8bfe",
            "{\u8bfe\u540d}\uFF5C\u5df2\u7ecf\u4e0b\u8bfe"
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
    private static final String TARGET_VOICEASSIST = "com.miui.voiceassist";
    private static final String TARGET_DESKCLOCK = "com.android.deskclock";
    private static final String ACTION_RESCHEDULE_DAILY = "com.xiaoai.islandnotify.ACTION_RESCHEDULE_DAILY";

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
    }

    @Override
    protected void onDestroy() {
        SharedPreferences local = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (mLocalPrefMirrorListener != null) {
            local.unregisterOnSharedPreferenceChangeListener(mLocalPrefMirrorListener);
            mLocalPrefMirrorListener = null;
        }
        SharedPreferences holiday = getSharedPreferences(HolidayManager.PREFS_HOLIDAY, Context.MODE_PRIVATE);
        if (mLocalHolidayMirrorListener != null) {
            holiday.unregisterOnSharedPreferenceChangeListener(mLocalHolidayMirrorListener);
            mLocalHolidayMirrorListener = null;
        }
        super.onDestroy();
    }

    private void initFrameworkServiceStatus() {
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                mXposedService = service;
                mFrameworkActive = true;
                mFrameworkDesc = "Framework: " + service.getFrameworkName()
                        + "\nAPI: " + service.getApiVersion()
                        + "  Version: " + service.getFrameworkVersionCode();
                initRemotePrefsBridge(service);
                requestMissingScopeIfNeeded(service);
                runOnUiThread(MainActivity.this::updateModuleStatus);
            }

            @Override
            public void onServiceDied(XposedService service) {
                mXposedService = null;
                mRemotePrefs = null;
                mRemoteHolidayPrefs = null;
                mRemoteDebugPrefs = null;
                mScopeRequested = false;
                mFrameworkActive = false;
                mFrameworkDesc = "";
                runOnUiThread(MainActivity.this::updateModuleStatus);
            }
        });
    }

    private void initRemotePrefsBridge(XposedService service) {
        try {
            SharedPreferences remote = service.getRemotePreferences(PREFS_NAME);
            mRemotePrefs = remote;
            SharedPreferences remoteDebug = service.getRemotePreferences(PREFS_DEBUG_NAME);
            mRemoteDebugPrefs = remoteDebug;

            SharedPreferences local = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (runInitialMigration(remote, local, "配置")) {
                runOnUiThread(this::recreate);
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
        if (target == null || source == null || keys == null || keys.length == 0) return;
        SharedPreferences.Editor ed = target.edit();
        for (String key : keys) {
            if (key == null) continue;
            if (!source.contains(key)) {
                ed.remove(key);
                continue;
            }
            Object value = source.getAll().get(key);
            putTyped(ed, key, value);
        }
        ed.apply();
    }

    private void copyAllToTarget(SharedPreferences target, Map<String, ?> allValues) {
        if (target == null || allValues == null) return;
        SharedPreferences.Editor ed = target.edit();
        for (Map.Entry<String, ?> e : allValues.entrySet()) {
            putTyped(ed, e.getKey(), e.getValue());
        }
        ed.apply();
    }

    private void putTyped(SharedPreferences.Editor ed, String key, Object value) {
        if (ed == null || key == null) return;
        if (value == null) {
            ed.remove(key);
        } else if (value instanceof String) {
            ed.putString(key, (String) value);
        } else if (value instanceof Integer) {
            ed.putInt(key, (Integer) value);
        } else if (value instanceof Boolean) {
            ed.putBoolean(key, (Boolean) value);
        } else if (value instanceof Long) {
            ed.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            ed.putFloat(key, (Float) value);
        } else if (value instanceof Set) {
            @SuppressWarnings("unchecked")
            Set<String> valueSet = (Set<String>) value;
            ed.putStringSet(key, valueSet);
        }
    }

    // ─────────────────────────────────────────────────────────────

    /**
     * 根据模块是否激活，设置状态卡片的颜色、图标和文字。
     */
    private void updateModuleStatus() {
        boolean active = mFrameworkActive;

        MaterialCardView card = findViewById(R.id.card_status);
        ImageView icon = findViewById(R.id.iv_status);
        TextView title = findViewById(R.id.tv_status_title);
        TextView desc = findViewById(R.id.tv_status_desc);

        if (active) {
            int bg      = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer,   Color.LTGRAY);
            int onColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.WHITE);
            card.setCardBackgroundColor(bg);
            icon.setImageResource(R.drawable.ic_module_active);
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(onColor));
            title.setText("模块已激活");
            title.setTextColor(onColor);
            desc.setText(mFrameworkDesc.isEmpty() ? "LSPosed Service 已连接 (API 101)" : mFrameworkDesc);
            desc.setTextColor(onColor);
        } else {
            int bg      = MaterialColors.getColor(this, com.google.android.material.R.attr.colorErrorContainer,   Color.LTGRAY);
            int onColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnErrorContainer, Color.BLACK);
            card.setCardBackgroundColor(bg);
            icon.setImageResource(R.drawable.ic_module_inactive);
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(onColor));
            title.setText("模块未激活");
            title.setTextColor(onColor);
            desc.setText("LSPosed Service 未连接，请检查模块启用与框架状态");
            desc.setTextColor(onColor);
        }
    }

    /** SharedPreferences 名称（与 MainHook 保持一致） */
    static final String PREFS_NAME = "island_custom";
    static final String PREFS_DEBUG_NAME = "island_debug";
    private static final String KEY_REPOST_ENABLED = "repost_enabled";

    private void initCustomCard() {
        if (mCustomCardBound) {
            refreshCustomCardFromPrefs();
            return;
        }
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // 三个阶段 SP 后缀：_pre=上课前  _active=上课中  _post=下课后
        final String[] SUFFIXES = {"_pre", "_active", "_post"};
        // 各阶段 A/B/ticker 的默认值
        final String[] DEFAULT_A = DEFAULT_TPL_A;
        final String[] DEFAULT_B = DEFAULT_TPL_B;
        final String[] DEFAULT_TICKER = DEFAULT_TPL_TICKER;
        // 各阶段输入框 View ID
        final int[] IDS_A = CUSTOM_IDS_A;
        final int[] IDS_B = CUSTOM_IDS_B;
        final int[] IDS_TICKER = CUSTOM_IDS_TICKER;

        SwitchMaterial swIconA = findViewById(R.id.sw_icon_a);
        TextView tvHint        = findViewById(R.id.tv_save_hint);

        // 读取已保存配置，无则用默认值
        for (int i = 0; i < 3; i++) {
            ((EditText) findViewById(IDS_A[i])).setText(
                    sp.getString("tpl_a"      + SUFFIXES[i], DEFAULT_A[i]));
            ((EditText) findViewById(IDS_B[i])).setText(
                    sp.getString("tpl_b"      + SUFFIXES[i], DEFAULT_B[i]));
            ((EditText) findViewById(IDS_TICKER[i])).setText(
                    sp.getString("tpl_ticker" + SUFFIXES[i], DEFAULT_TICKER[i]));
        }
        swIconA.setChecked(sp.getBoolean("icon_a", true));

        // 保存按钮引用 & 监控输入变化用于即时指示未保存状态
        btnSaveCustom = findViewById(R.id.btn_save_custom);
        for (int i = 0; i < 3; i++) {
            ((EditText) findViewById(IDS_A[i])).addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) { updateCustomDirtyIndicator(); }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
            ((EditText) findViewById(IDS_B[i])).addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) { updateCustomDirtyIndicator(); }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
            ((EditText) findViewById(IDS_TICKER[i])).addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) { updateCustomDirtyIndicator(); }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
        }
        swIconA.setOnCheckedChangeListener((b, checked) -> updateCustomDirtyIndicator());

        findViewById(R.id.btn_save_custom).setOnClickListener(v -> {

            SharedPreferences.Editor ed = sp.edit();
            // 通知 voiceassist 进程同步最新配置（绕过 SELinux 跨 UID 文件读取限制）

            for (int i = 0; i < 3; i++) {
                String tplA      = ((EditText) findViewById(IDS_A[i])).getText().toString().trim();
                String tplB      = ((EditText) findViewById(IDS_B[i])).getText().toString().trim();
                String tplTicker = ((EditText) findViewById(IDS_TICKER[i])).getText().toString().trim();
                ed.putString("tpl_a"      + SUFFIXES[i], tplA);
                ed.putString("tpl_b"      + SUFFIXES[i], tplB);
                ed.putString("tpl_ticker" + SUFFIXES[i], tplTicker);
            }
            boolean iconA = swIconA.isChecked();
            ed.putBoolean("icon_a", iconA);
            ed.apply();

            tvHint.setText("已保存，下次通知生效");
            tvHint.setVisibility(View.VISIBLE);
            // 保存后清除指示
            customDirty = false;
            updateCustomDirtyIndicator();
        });

        mCustomCardBound = true;

    }

    private void refreshCustomCardFromPrefs() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        for (int i = 0; i < 3; i++) {
            ((EditText) findViewById(CUSTOM_IDS_A[i])).setText(
                    sp.getString("tpl_a" + CUSTOM_SUFFIXES[i], DEFAULT_TPL_A[i]));
            ((EditText) findViewById(CUSTOM_IDS_B[i])).setText(
                    sp.getString("tpl_b" + CUSTOM_SUFFIXES[i], DEFAULT_TPL_B[i]));
            ((EditText) findViewById(CUSTOM_IDS_TICKER[i])).setText(
                    sp.getString("tpl_ticker" + CUSTOM_SUFFIXES[i], DEFAULT_TPL_TICKER[i]));
        }
        ((SwitchMaterial) findViewById(R.id.sw_icon_a)).setChecked(sp.getBoolean("icon_a", true));
        customDirty = false;
        updateCustomDirtyIndicator();
    }

    /**
     * 检查「自定义模板」卡片是否有未保存变更
     */
    private boolean isCustomDirty() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        final String[] SUFFIXES = {"_pre", "_active", "_post"};
        final int[] IDS_A      = {R.id.et_tpl_a_pre,      R.id.et_tpl_a_active,      R.id.et_tpl_a_post};
        final int[] IDS_B      = {R.id.et_tpl_b_pre,      R.id.et_tpl_b_active,      R.id.et_tpl_b_post};
        final int[] IDS_TICKER = {R.id.et_tpl_ticker_pre,  R.id.et_tpl_ticker_active,  R.id.et_tpl_ticker_post};
        for (int i = 0; i < 3; i++) {
            String curA = ((EditText) findViewById(IDS_A[i])).getText().toString().trim();
            String curB = ((EditText) findViewById(IDS_B[i])).getText().toString().trim();
            String curT = ((EditText) findViewById(IDS_TICKER[i])).getText().toString().trim();
            String sA = sp.getString("tpl_a" + SUFFIXES[i], DEFAULT_TPL_A[i]);
            String sB = sp.getString("tpl_b" + SUFFIXES[i], DEFAULT_TPL_B[i]);
            String sT = sp.getString("tpl_ticker" + SUFFIXES[i], DEFAULT_TPL_TICKER[i]);
            if (!curA.equals(sA) || !curB.equals(sB) || !curT.equals(sT)) return true;
        }
        SwitchMaterial swIconA = findViewById(R.id.sw_icon_a);
        if (swIconA.isChecked() != sp.getBoolean("icon_a", true)) return true;
        return false;
    }

    /**
     * 检查「超时设置」卡片当前可见项是否与已保存值不同（表明存在未保存修改）
     */
    private boolean isTimeoutDirty() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // 岛：当前阶段
        MaterialButtonToggleGroup toggleIslandPhase = findViewById(R.id.toggle_island_phase);
        int checkedIsland = toggleIslandPhase.getCheckedButtonId();
        int idxIsland = (checkedIsland == R.id.btn_island_phase_active) ? 1
                : (checkedIsland == R.id.btn_island_phase_post) ? 2 : 0;
        EditText etIsland = findViewById(R.id.et_island_to);
        String curIslandStr = etIsland.getText() != null ? etIsland.getText().toString().trim() : "";
        int curIslandVal = curIslandStr.isEmpty() ? -1 : tryParseInt(curIslandStr, -1);
        MaterialButtonToggleGroup toggleIslandUnit = findViewById(R.id.toggle_island_unit);
        String curIslandUnit = (toggleIslandUnit.getCheckedButtonId() == R.id.btn_island_s) ? "s" : "m";
        SwitchMaterial swIslandDefault = findViewById(R.id.sw_island_to_default);
        int savedIsVal = sp.getInt("to_island_val_" + TO_PHASES[idxIsland], -1);
        String savedIsUnit = sp.getString("to_island_unit_" + TO_PHASES[idxIsland], "m");
        boolean savedIslandDefault = savedIsVal < 0;
        if (swIslandDefault.isChecked() != savedIslandDefault) return true;
        if (!swIslandDefault.isChecked()) {
            if (curIslandVal != savedIsVal) return true;
            if (!curIslandUnit.equals(savedIsUnit)) return true;
        }

        // 通知：仅允许单选一个触发阶段
        MaterialButtonToggleGroup toggleNotifPhase = findViewById(R.id.toggle_notif_phase);
        int checkedNotif = toggleNotifPhase.getCheckedButtonId();
        int idxNotif = (checkedNotif == R.id.btn_notif_phase_active) ? 1
                : (checkedNotif == R.id.btn_notif_phase_post) ? 2 : 0;
        SwitchMaterial swNotifDefault  = findViewById(R.id.sw_notif_to_default);
        EditText etNotif = findViewById(R.id.et_notif_to);
        String curNotifStr = etNotif.getText() != null ? etNotif.getText().toString().trim() : "";
        int curNotifVal = curNotifStr.isEmpty() ? -1 : tryParseInt(curNotifStr, -1);
        MaterialButtonToggleGroup toggleNotifUnit = findViewById(R.id.toggle_notif_unit);
        String curNotifUnit = (toggleNotifUnit.getCheckedButtonId() == R.id.btn_notif_s) ? "s" : "m";
        String savedTrigger = sp.getString(KEY_NOTIF_DISMISS_TRIGGER, "pre");
        int savedTriggerIdx = "active".equals(savedTrigger) ? 1 : ("post".equals(savedTrigger) ? 2 : 0);
        if (sp.getInt("to_notif_val_" + TO_PHASES[savedTriggerIdx], -1) < 0) {
            for (int i = 0; i < 3; i++) {
                if (sp.getInt("to_notif_val_" + TO_PHASES[i], -1) >= 0) {
                    savedTriggerIdx = i;
                    break;
                }
            }
        }
        boolean savedDefault = true;
        for (int i = 0; i < 3; i++) {
            if (sp.getInt("to_notif_val_" + TO_PHASES[i], -1) >= 0) {
                savedDefault = false;
                break;
            }
        }
        if (swNotifDefault.isChecked() != savedDefault) return true;
        if (!swNotifDefault.isChecked()) {
            if (idxNotif != savedTriggerIdx) return true;
            int savedNoVal = sp.getInt("to_notif_val_" + TO_PHASES[savedTriggerIdx], -1);
            String savedNoUnit = sp.getString("to_notif_unit_" + TO_PHASES[savedTriggerIdx], "m");
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
        boolean d = isCustomDirty();
        customDirty = d;
        if (btnSaveCustom == null) return;
        int color = d ? Color.parseColor("#FF9800")
                : MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#6200EE"));
        btnSaveCustom.setBackgroundTintList(ColorStateList.valueOf(color));
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

    /** 三阶段 key 后缀（与 MainHook/SP 保持一致） */
    private static final String[] TO_PHASES = {"pre", "active", "post"};
    private static final String KEY_NOTIF_DISMISS_TRIGGER = "notif_dismiss_trigger";

    /**
     * 初始化「消失时间」卡片。
     * 岛消失：全局单一值（每次 notify 都会重置，分阶段无意义）。
     * 通知消失：三阶段独立（实现为 AlarmManager + nm.cancel()）。
     * val == -1 表示系统默认；val > 0 表示自定义。
     */
    private void initTimeoutCard() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // ── 岛：三阶段独立设置 ─────────────────────────────────────────
        final int[]    islandVals  = new int[3];
        final String[] islandUnits = new String[3];
        for (int i = 0; i < 3; i++) {
            islandVals[i]  = sp.getInt   ("to_island_val_"  + TO_PHASES[i], -1);
            islandUnits[i] = sp.getString("to_island_unit_" + TO_PHASES[i], "m");
        }
        final int[] curIslandPhase = {0};

        // ── 通知：三阶段独立 ───────────────────────────────────────────
        final int[]    notifVals  = new int[3];
        final String[] notifUnits = new String[3];
        for (int i = 0; i < 3; i++) {
            notifVals[i]  = sp.getInt   ("to_notif_val_"  + TO_PHASES[i], -1);
            notifUnits[i] = sp.getString("to_notif_unit_" + TO_PHASES[i], "m");
        }
        int initialNotifPhase = 0;
        String savedTrigger = sp.getString(KEY_NOTIF_DISMISS_TRIGGER, "pre");
        if ("active".equals(savedTrigger)) initialNotifPhase = 1;
        else if ("post".equals(savedTrigger)) initialNotifPhase = 2;
        if (notifVals[initialNotifPhase] < 0) {
            for (int i = 0; i < 3; i++) {
                if (notifVals[i] >= 0) {
                    initialNotifPhase = i;
                    break;
                }
            }
        }
        final int[] curNotifPhase = {initialNotifPhase};
        // 通知默认为全局开关：当所有阶段均为 -1 时视为默认
        boolean notifAllDefault = true;
        for (int i = 0; i < 3; i++) if (notifVals[i] >= 0) { notifAllDefault = false; break; }
        final boolean[] notifGlobalDefault = {notifAllDefault};
        // ── View 引用 ──────────────────────────────────────────────────
        TextInputLayout  tilIsland       = findViewById(R.id.til_island_to);
        EditText         etIsland        = findViewById(R.id.et_island_to);
        MaterialButtonToggleGroup toggleIslandUnit = findViewById(R.id.toggle_island_unit);
        SwitchMaterial   swIslandDefault = findViewById(R.id.sw_island_to_default);

        MaterialButtonToggleGroup toggleIslandPhase = findViewById(R.id.toggle_island_phase);

        MaterialButtonToggleGroup toggleNotifPhase = findViewById(R.id.toggle_notif_phase);
        TextInputLayout  tilNotif        = findViewById(R.id.til_notif_to);
        EditText         etNotif         = findViewById(R.id.et_notif_to);
        MaterialButtonToggleGroup toggleNotifUnit = findViewById(R.id.toggle_notif_unit);
        SwitchMaterial   swNotifDefault  = findViewById(R.id.sw_notif_to_default);

        TextView tvHint = findViewById(R.id.tv_timeout_hint);
        btnSaveTimeout = findViewById(R.id.btn_save_timeout);

        final boolean[] updatingIsland = {false};
        final boolean[] updatingNotif  = {false};

        // ── 岛默认开关 listener（按当前阶段生效） ─────────────────────────
        swIslandDefault.setOnCheckedChangeListener((btn, checked) -> {
            if (updatingIsland[0]) return;
            setTimeoutRowEnabled(tilIsland, toggleIslandUnit, !checked);
            if (checked) { etIsland.setText(""); islandVals[curIslandPhase[0]] = -1; }
            updateTimeoutDirtyIndicator();
        });

        // ── 通知默认开关 listener（全局开关：控制所有阶段） ────────────
        swNotifDefault.setOnCheckedChangeListener((btn, checked) -> {
            if (updatingNotif[0]) return;
            notifGlobalDefault[0] = checked;
            if (checked) {
                // 全局默认：清空三个阶段的自定义值
                for (int j = 0; j < 3; j++) notifVals[j] = -1;
                etNotif.setText("");
            }
            boolean en = !checked;
            setTimeoutRowEnabled(tilNotif, toggleNotifUnit, en);
            toggleNotifPhase.setEnabled(en);
            toggleNotifPhase.setAlpha(en ? 1f : 0.4f);
            updateTimeoutDirtyIndicator();
        });

        // ── 初始化岛 UI（按阶段加载） ─────────────────────────────────
        final Runnable loadIslandUI = () -> {
            int idx = curIslandPhase[0];
            boolean def = (islandVals[idx] < 0);
            updatingIsland[0] = true;
            swIslandDefault.setChecked(def);
            updatingIsland[0] = false;
            etIsland.setText(def ? "" : String.valueOf(islandVals[idx]));
            toggleIslandUnit.check("s".equals(islandUnits[idx])
                    ? R.id.btn_island_s : R.id.btn_island_m);
            setTimeoutRowEnabled(tilIsland, toggleIslandUnit, !def);
        };

        final Runnable saveIslandUI = () -> {
            int idx = curIslandPhase[0];
            if (swIslandDefault.isChecked()) {
                islandVals[idx] = -1;
            } else {
                String s = etIsland.getText() != null ? etIsland.getText().toString().trim() : "";
                try { islandVals[idx] = s.isEmpty() ? -1 : Integer.parseInt(s); }
                catch (NumberFormatException e) { islandVals[idx] = -1; }
            }
            islandUnits[idx] = (toggleIslandUnit.getCheckedButtonId() == R.id.btn_island_s) ? "s" : "m";
        };

        toggleIslandPhase.check(R.id.btn_island_phase_pre);
        loadIslandUI.run();

        // ── 岛阶段切换 ───────────────────────────────────────────────
        toggleIslandPhase.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            saveIslandUI.run();
            if      (checkedId == R.id.btn_island_phase_pre)    curIslandPhase[0] = 0;
            else if (checkedId == R.id.btn_island_phase_active) curIslandPhase[0] = 1;
            else                                                curIslandPhase[0] = 2;
            loadIslandUI.run();
            updateTimeoutDirtyIndicator();
        });

        // ── 通知 UI 刷新 ────────────────────────────────────────────
        final Runnable loadNotifUI = () -> {
            int idx = curNotifPhase[0];
            // 默认开关由全局状态控制
            updatingNotif[0] = true;
            swNotifDefault.setChecked(notifGlobalDefault[0]);
            updatingNotif[0] = false;
            etNotif.setText((notifVals[idx] > 0) ? String.valueOf(notifVals[idx]) : "");
            toggleNotifUnit.check("s".equals(notifUnits[idx])
                ? R.id.btn_notif_s : R.id.btn_notif_m);
            boolean en = !notifGlobalDefault[0];
            setTimeoutRowEnabled(tilNotif, toggleNotifUnit, en);
            toggleNotifPhase.setEnabled(en);
            toggleNotifPhase.setAlpha(en ? 1f : 0.4f);
        };

        // ── 通知 UI 写回内存 ─────────────────────────────────────────
        final Runnable saveNotifUI = () -> {
            int idx = curNotifPhase[0];
            if (swNotifDefault.isChecked()) {
                notifVals[idx] = -1;
            } else {
                String s = etNotif.getText() != null ? etNotif.getText().toString().trim() : "";
                try { notifVals[idx] = s.isEmpty() ? -1 : Integer.parseInt(s); }
                catch (NumberFormatException e) { notifVals[idx] = -1; }
            }
            notifUnits[idx] = (toggleNotifUnit.getCheckedButtonId() == R.id.btn_notif_s) ? "s" : "m";
        };

        toggleNotifPhase.check(curNotifPhase[0] == 1
                ? R.id.btn_notif_phase_active
                : (curNotifPhase[0] == 2 ? R.id.btn_notif_phase_post : R.id.btn_notif_phase_pre));
        loadNotifUI.run();

        // ── 通知阶段切换 ──────────────────────────────────────────────
        toggleNotifPhase.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            saveNotifUI.run();
            if      (checkedId == R.id.btn_notif_phase_pre)    curNotifPhase[0] = 0;
            else if (checkedId == R.id.btn_notif_phase_active) curNotifPhase[0] = 1;
            else                                                curNotifPhase[0] = 2;
            loadNotifUI.run();
            updateTimeoutDirtyIndicator();
        });

        // ── 保存按钮 ──────────────────────────────────────────────────
        findViewById(R.id.btn_save_timeout).setOnClickListener(v -> {
            saveNotifUI.run();

            // 读取并保存岛当前 UI 值（按三阶段写回）
            saveIslandUI.run();

            // 若全局默认被选中，确保所有 notifVals 为 -1
            SharedPreferences.Editor ed = sp.edit();

            // 岛：三阶段键
            for (int i = 0; i < 3; i++) {
                ed.putInt   ("to_island_val_"  + TO_PHASES[i], islandVals[i]);
                ed.putString("to_island_unit_" + TO_PHASES[i], islandUnits[i]);
            }

            // 通知：三阶段
            int selectedNotifPhase = curNotifPhase[0];
            String selectedNotifPhaseKey = TO_PHASES[selectedNotifPhase];
            ed.putString(KEY_NOTIF_DISMISS_TRIGGER, selectedNotifPhaseKey);
            for (int i = 0; i < 3; i++) {
                ed.putInt("to_notif_val_" + TO_PHASES[i], -1);
                ed.putString("to_notif_unit_" + TO_PHASES[i], notifUnits[i]);
            }
            if (!notifGlobalDefault[0] && notifVals[selectedNotifPhase] >= 0) {
                ed.putInt("to_notif_val_" + selectedNotifPhaseKey, notifVals[selectedNotifPhase]);
                ed.putString("to_notif_unit_" + selectedNotifPhaseKey, notifUnits[selectedNotifPhase]);
            }

            // 删除旧版/遗留键，避免模块内部 SharedPreferences 与目标进程不一致
            ed.remove("to_island_val");
            ed.remove("to_island_unit");
            ed.remove("use_default_behavior");
            ed.remove("notif_dismiss_value");
            ed.remove("notif_dismiss_unit");
            ed.remove("island_dismiss_value");
            ed.remove("island_dismiss_unit");
            ed.remove("island_dismiss_trigger");
            ed.apply();

            tvHint.setText("已保存，下次通知生效");
            tvHint.setVisibility(View.VISIBLE);
            timeoutDirty = false;
            updateTimeoutDirtyIndicator();
        });
        // 监听输入变化以即时更新保存按钮指示
        etIsland.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { updateTimeoutDirtyIndicator(); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        etNotif.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { updateTimeoutDirtyIndicator(); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        updateTimeoutDirtyIndicator();
    }

    /** 设置超时行（TextInputLayout + 单位切换按钮）的启用/禁用状态。 */
    private void setTimeoutRowEnabled(TextInputLayout til,
            MaterialButtonToggleGroup unitToggle, boolean enabled) {
        til.setEnabled(enabled);
        til.setAlpha(enabled ? 1f : 0.4f);
        unitToggle.setEnabled(enabled);
        unitToggle.setAlpha(enabled ? 1f : 0.4f);
    }

    private static final String ALIAS = "com.xiaoai.islandnotify.MainActivityAlias";

    /**
     * 初始化「课前提醒」卡片。
     * 仅开放提醒时间自定义，保存时同步 reminder_minutes_before 到 voiceassist 进程。
     */
    private void initReminderCard() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        EditText etMinutes = findViewById(R.id.et_reminder_minutes);
        TextView tvHint    = findViewById(R.id.tv_reminder_hint);

        int savedMinutes = sp.getInt("reminder_minutes_before", 15);
        etMinutes.setText(String.valueOf(savedMinutes));

        // 保存分钟数并重新调度
        findViewById(R.id.btn_save_reminder).setOnClickListener(v -> {
            String str = etMinutes.getText() != null ? etMinutes.getText().toString().trim() : "";
            int minutes;
            try {
                minutes = Integer.parseInt(str);
                if (minutes < 1) minutes = 1;
                if (minutes > 120) minutes = 120;
            } catch (NumberFormatException e) {
                minutes = 15;
            }
            etMinutes.setText(String.valueOf(minutes));
            sp.edit().putInt("reminder_minutes_before", minutes).apply();


            tvHint.setText("已保存，重新调度今日提醒（提前 " + minutes + " 分钟）");
            tvHint.setVisibility(View.VISIBLE);
        });
    }

    /**
    /**
     * 初始化「上课免打扰」卡片。
     * 静音与勿扰（DND）完全独立，可同时启用，各自独立配置触发时间。
     */
    private void initMuteCard() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        SwitchMaterial swMute        = findViewById(R.id.sw_mute_enabled);
        SwitchMaterial swRepost      = findViewById(R.id.sw_repost_enabled);
        View           llMute        = findViewById(R.id.ll_mute_content);
        EditText       etMuteBefore  = findViewById(R.id.et_mute_mins_before);
        SwitchMaterial swUnmute      = findViewById(R.id.sw_unmute_enabled);
        View           llUnmute      = findViewById(R.id.ll_unmute_content);
        EditText       etUnmuteAfter = findViewById(R.id.et_unmute_mins_after);
        SwitchMaterial swDnd         = findViewById(R.id.sw_dnd_enabled);
        View           llDnd         = findViewById(R.id.ll_dnd_content);
        EditText       etDndBefore   = findViewById(R.id.et_dnd_mins_before);
        SwitchMaterial swUnDnd       = findViewById(R.id.sw_undnd_enabled);
        View           llUnDnd       = findViewById(R.id.ll_undnd_content);
        EditText       etUnDndAfter  = findViewById(R.id.et_undnd_mins_after);
        TextView       tvMuteHint    = findViewById(R.id.tv_mute_hint);

        // 加载已保存状态
        swRepost.setChecked(sp.getBoolean(KEY_REPOST_ENABLED, true));

        swMute.setChecked(sp.getBoolean("mute_enabled", false));
        llMute.setVisibility(swMute.isChecked() ? View.VISIBLE : View.GONE);
        etMuteBefore.setText(String.valueOf(sp.getInt("mute_mins_before", 0)));

        swUnmute.setChecked(sp.getBoolean("unmute_enabled", false));
        llUnmute.setVisibility(swUnmute.isChecked() ? View.VISIBLE : View.GONE);
        etUnmuteAfter.setText(String.valueOf(sp.getInt("unmute_mins_after", 0)));

        swDnd.setChecked(sp.getBoolean("dnd_enabled", false));
        llDnd.setVisibility(swDnd.isChecked() ? View.VISIBLE : View.GONE);
        etDndBefore.setText(String.valueOf(sp.getInt("dnd_mins_before", 0)));

        swUnDnd.setChecked(sp.getBoolean("undnd_enabled", false));
        llUnDnd.setVisibility(swUnDnd.isChecked() ? View.VISIBLE : View.GONE);
        etUnDndAfter.setText(String.valueOf(sp.getInt("undnd_mins_after", 0)));

        // 全局补发开关
        swRepost.setOnCheckedChangeListener((btn, checked) -> {
            sp.edit().putBoolean(KEY_REPOST_ENABLED, checked).apply();
        });

        // 静音开关
        swMute.setOnCheckedChangeListener((btn, checked) -> {
            llMute.setVisibility(checked ? View.VISIBLE : View.GONE);
            sp.edit().putBoolean("mute_enabled", checked).apply();
        });

        // 取消静音开关
        swUnmute.setOnCheckedChangeListener((btn, checked) -> {
            llUnmute.setVisibility(checked ? View.VISIBLE : View.GONE);
            sp.edit().putBoolean("unmute_enabled", checked).apply();
        });

        // 勿扰开关
        swDnd.setOnCheckedChangeListener((btn, checked) -> {
            llDnd.setVisibility(checked ? View.VISIBLE : View.GONE);
            sp.edit().putBoolean("dnd_enabled", checked).apply();
        });

        // 取消勿扰开关
        swUnDnd.setOnCheckedChangeListener((btn, checked) -> {
            llUnDnd.setVisibility(checked ? View.VISIBLE : View.GONE);
            sp.edit().putBoolean("undnd_enabled", checked).apply();
        });

        com.google.android.material.button.MaterialButtonToggleGroup toggleMode = findViewById(R.id.toggle_island_button_mode);
        int savedMode = sp.getInt("island_button_mode", 0); // 0=Mute, 1=DND, 2=Both
        if (savedMode == 0) toggleMode.check(R.id.btn_mode_mute);
        else if (savedMode == 1) toggleMode.check(R.id.btn_mode_dnd);
        else toggleMode.check(R.id.btn_mode_both);

        // 保存时间设置（静音 + 勿扰共 4 个字段 + 按钮模式）
        findViewById(R.id.btn_save_mute).setOnClickListener(v -> {
            int muteBefore  = parseMinutes(etMuteBefore);
            int unmuteAfter = parseMinutes(etUnmuteAfter);
            int dndBefore   = parseMinutes(etDndBefore);
            int unDndAfter  = parseMinutes(etUnDndAfter);

            int selectedId = toggleMode.getCheckedButtonId();
            int buttonMode = (selectedId == R.id.btn_mode_mute) ? 0 : (selectedId == R.id.btn_mode_dnd ? 1 : 2);

            etMuteBefore.setText(String.valueOf(muteBefore));
            etUnmuteAfter.setText(String.valueOf(unmuteAfter));
            etDndBefore.setText(String.valueOf(dndBefore));
            etUnDndAfter.setText(String.valueOf(unDndAfter));

            sp.edit()
              .putInt("mute_mins_before",  muteBefore)
              .putInt("unmute_mins_after", unmuteAfter)
              .putInt("dnd_mins_before",   dndBefore)
              .putInt("undnd_mins_after",  unDndAfter)
              .putInt("island_button_mode", buttonMode)
              .apply();


            tvMuteHint.setText("设置已保存并重新调度");
            tvMuteHint.setVisibility(View.VISIBLE);
        });
    }

    /** 解析 EditText 中的分钟数（0–60），非法输入返回 0 */
    private static int parseMinutes(EditText et) {
        try {
            int v = Integer.parseInt(et.getText() != null ? et.getText().toString().trim() : "0");
            return Math.max(0, Math.min(60, v));
        } catch (NumberFormatException e) { return 0; }
    }

    private void initWakeupCard() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        com.google.android.material.switchmaterial.SwitchMaterial swMorning = findViewById(R.id.sw_wakeup_morning);
        View llMorning = findViewById(R.id.ll_wakeup_morning_content);
        android.widget.EditText etLastSec = findViewById(R.id.et_wakeup_morning_last_sec);
        android.widget.LinearLayout llMorningRules = findViewById(R.id.ll_wakeup_morning_rules);
        com.google.android.material.switchmaterial.SwitchMaterial swAfternoon = findViewById(R.id.sw_wakeup_afternoon);
        View llAfternoon = findViewById(R.id.ll_wakeup_afternoon_content);
        android.widget.EditText etFirstSec = findViewById(R.id.et_wakeup_afternoon_first_sec);
        android.widget.LinearLayout llAfternoonRules = findViewById(R.id.ll_wakeup_afternoon_rules);
        android.widget.TextView tvHint = findViewById(R.id.tv_wakeup_hint);

        // 加载已保存的规则行
        loadRuleRows(llMorningRules, sp.getString("wakeup_morning_rules_json",
                "[{\"sec\":1,\"hour\":7,\"minute\":0}]"));
        loadRuleRows(llAfternoonRules, sp.getString("wakeup_afternoon_rules_json",
                "[{\"sec\":5,\"hour\":12,\"minute\":0}]"));

        swMorning.setChecked(sp.getBoolean("wakeup_morning_enabled", false));
        llMorning.setVisibility(swMorning.isChecked() ? View.VISIBLE : View.GONE);
        etLastSec.setText(String.valueOf(sp.getInt("wakeup_morning_last_sec", 4)));

        swAfternoon.setChecked(sp.getBoolean("wakeup_afternoon_enabled", false));
        llAfternoon.setVisibility(swAfternoon.isChecked() ? View.VISIBLE : View.GONE);
        etFirstSec.setText(String.valueOf(sp.getInt("wakeup_afternoon_first_sec", 5)));

        swMorning.setOnCheckedChangeListener((btn, checked) -> {
            llMorning.setVisibility(checked ? View.VISIBLE : View.GONE);
            sp.edit().putBoolean("wakeup_morning_enabled", checked).apply();
        });

        swAfternoon.setOnCheckedChangeListener((btn, checked) -> {
            llAfternoon.setVisibility(checked ? View.VISIBLE : View.GONE);
            sp.edit().putBoolean("wakeup_afternoon_enabled", checked).apply();
        });

        findViewById(R.id.btn_add_morning_rule).setOnClickListener(v -> addRuleRow(llMorningRules, 1, 7, 0));
        findViewById(R.id.btn_add_afternoon_rule).setOnClickListener(v -> addRuleRow(llAfternoonRules, 5, 12, 0));

        findViewById(R.id.btn_save_wakeup).setOnClickListener(v -> {
            int lastSec  = parseRuleInt(etLastSec,  4); if (lastSec  < 1) lastSec  = 1;
            int firstSec = parseRuleInt(etFirstSec, 5); if (firstSec < 1) firstSec = 1;
            etLastSec.setText(String.valueOf(lastSec));
            etFirstSec.setText(String.valueOf(firstSec));

            String morningRulesJson   = collectRulesJson(llMorningRules);
            String afternoonRulesJson = collectRulesJson(llAfternoonRules);

            sp.edit()
              .putInt("wakeup_morning_last_sec",         lastSec)
              .putInt("wakeup_afternoon_first_sec",      firstSec)
              .putString("wakeup_morning_rules_json",    morningRulesJson)
              .putString("wakeup_afternoon_rules_json",  afternoonRulesJson)
              .apply();


            tvHint.setText("设置已保存并重新调度叫醒闹钟");
            tvHint.setVisibility(View.VISIBLE);
        });
    }

    /** 从 JSON 字符串加载规则行到容器 */
    private void loadRuleRows(android.widget.LinearLayout container, String rulesJson) {
        container.removeAllViews();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(rulesJson);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                addRuleRow(container, obj.getInt("sec"), obj.getInt("hour"), obj.getInt("minute"));
            }
        } catch (Exception e) {
            addRuleRow(container, 1, 7, 0);
        }
    }

    /** 动态添加一条规则行：第[sec]节 → [hour]:[minute]  [−] */
    private void addRuleRow(android.widget.LinearLayout container, int sec, int hour, int minute) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams rowLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = (int)(4 * getResources().getDisplayMetrics().density);
        row.setLayoutParams(rowLp);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        float dp = getResources().getDisplayMetrics().density;

        android.widget.TextView tvSec = new android.widget.TextView(this);
        tvSec.setText("第");
        row.addView(tvSec);

        android.widget.EditText etSec = new android.widget.EditText(this);
        android.widget.LinearLayout.LayoutParams secLp = new android.widget.LinearLayout.LayoutParams(
                (int)(56 * dp), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        etSec.setLayoutParams(secLp);
        etSec.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etSec.setMaxLines(1);
        etSec.setText(String.valueOf(sec));
        etSec.setGravity(android.view.Gravity.CENTER);
        row.addView(etSec);

        android.widget.TextView tvArrow = new android.widget.TextView(this);
        tvArrow.setText(" 节 → ");
        row.addView(tvArrow);

        android.widget.EditText etHour = new android.widget.EditText(this);
        android.widget.LinearLayout.LayoutParams hourLp = new android.widget.LinearLayout.LayoutParams(
                (int)(52 * dp), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        etHour.setLayoutParams(hourLp);
        etHour.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etHour.setMaxLines(1);
        etHour.setText(String.valueOf(hour));
        etHour.setGravity(android.view.Gravity.CENTER);
        row.addView(etHour);

        android.widget.TextView tvColon = new android.widget.TextView(this);
        tvColon.setText(" : ");
        row.addView(tvColon);

        android.widget.EditText etMin = new android.widget.EditText(this);
        android.widget.LinearLayout.LayoutParams minLp = new android.widget.LinearLayout.LayoutParams(
                (int)(52 * dp), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        etMin.setLayoutParams(minLp);
        etMin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etMin.setMaxLines(1);
        etMin.setText(String.format(java.util.Locale.getDefault(), "%02d", minute));
        etMin.setGravity(android.view.Gravity.CENTER);
        row.addView(etMin);

        com.google.android.material.button.MaterialButton btnDel =
                new com.google.android.material.button.MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnDel.setText("删除");
        btnDel.setTextSize(12f);
        btnDel.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFFBA1A1A));
        btnDel.setTextColor(0xFFBA1A1A);
        android.widget.LinearLayout.LayoutParams delLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                (int)(36 * dp));
        delLp.setMarginStart((int)(4 * dp));
        btnDel.setLayoutParams(delLp);
        btnDel.setOnClickListener(delV -> container.removeView(row));
        row.addView(btnDel);

        container.addView(row);
    }

    /** 收集容器中所有规则行 → JSON 字符串 */
    private String collectRulesJson(android.widget.LinearLayout container) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (int i = 0; i < container.getChildCount(); i++) {
            android.view.View rowView = container.getChildAt(i);
            if (!(rowView instanceof android.widget.LinearLayout)) continue;
            android.widget.LinearLayout row = (android.widget.LinearLayout) rowView;
            // 结构：[tvSec(0)][etSec(1)][tvArrow(2)][etHour(3)][tvColon(4)][etMin(5)][btnDel(6)]
            try {
                android.widget.EditText etSec  = (android.widget.EditText) row.getChildAt(1);
                android.widget.EditText etHour = (android.widget.EditText) row.getChildAt(3);
                android.widget.EditText etMin  = (android.widget.EditText) row.getChildAt(5);
                int s = Integer.parseInt(etSec.getText().toString().trim());
                int h = parseHour(etHour);
                int m = parseMinute(etMin);
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("sec", s); obj.put("hour", h); obj.put("minute", m);
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    /** 解析 EditText 中的整数，失败时返回 defaultVal */
    private static int parseRuleInt(android.widget.EditText et, int defaultVal) {
        try { return Integer.parseInt(et.getText().toString().trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    /** 解析小时（0–23） */
    private static int parseHour(android.widget.EditText et) {
        try {
            int v = Integer.parseInt(et.getText() != null ? et.getText().toString().trim() : "0");
            return Math.max(0, Math.min(23, v));
        } catch (NumberFormatException e) { return 0; }
    }

    /** 解析分钟（0–59） */
    private static int parseMinute(android.widget.EditText et) {
        try {
            int v = Integer.parseInt(et.getText() != null ? et.getText().toString().trim() : "0");
            return Math.max(0, Math.min(59, v));
        } catch (NumberFormatException e) { return 0; }
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

        View debugRow = findViewById(R.id.row_debug);
        if (debugRow != null) {
            debugRow.setOnClickListener(v -> showDebugDialog());
        }
    }

    private void showDebugDialog() {
        TextView tv = new TextView(this);
        int p = dpToPx(16);
        tv.setPadding(p, p, p, p);
        tv.setTextIsSelectable(true);
        tv.setText(buildDebugInfoText());
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(12f);
        tv.setVerticalScrollBarEnabled(true);

        androidx.core.widget.NestedScrollView sv = new androidx.core.widget.NestedScrollView(this);
        sv.addView(tv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Debug")
                .setView(sv)
                .setNeutralButton("刷新", (d, w) -> {
                    // 占位，真实刷新逻辑在 show() 后覆写点击事件，避免自动 dismiss
                })
                .setPositiveButton("复制", (d, w) -> copyDebugInfo(tv.getText() == null ? "" : tv.getText().toString()))
                .setNegativeButton("关闭", null)
                .create();

        dialog.show();
        android.widget.Button btnRefresh = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                requestRuntimeDebugRefresh();
                tv.setText(buildDebugInfoText());
                Handler h2 = new Handler(Looper.getMainLooper());
                h2.postDelayed(() -> tv.setText(buildDebugInfoText()), 500);
                h2.postDelayed(() -> tv.setText(buildDebugInfoText()), 1200);
            });
        }

        // 打开时先触发一次运行态刷新，再异步回填，避免首次打开看到空缓存。
        requestRuntimeDebugRefresh();
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(() -> tv.setText(buildDebugInfoText()), 500);
        h.postDelayed(() -> tv.setText(buildDebugInfoText()), 1200);
    }

    private void requestRuntimeDebugRefresh() {
        try {
            Intent i = new Intent(ACTION_RESCHEDULE_DAILY);
            i.setPackage(TARGET_VOICEASSIST);
            sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    private void copyDebugInfo(String content) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("IslandNotify Debug", content));
                Toast.makeText(this, "Debug 信息已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String buildDebugInfoText() {
        SharedPreferences local = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences remote = mRemotePrefs;
        SharedPreferences localDebug = getSharedPreferences(PREFS_DEBUG_NAME, Context.MODE_PRIVATE);
        SharedPreferences remoteDebug = mRemoteDebugPrefs;
        SharedPreferences debug = pickDebugPrefs(localDebug, remoteDebug);
        String debugSource = (debug == remoteDebug) ? "remote_debug" : "local_debug";
        SharedPreferences holidayLocal = getSharedPreferences(HolidayManager.PREFS_HOLIDAY, Context.MODE_PRIVATE);
        SharedPreferences holidayRemote = mRemoteHolidayPrefs;
        Set<String> scope = new HashSet<>();
        try {
            if (mXposedService != null) {
                scope.addAll(mXposedService.getScope());
            }
        } catch (Throwable ignored) {}

        int year = Calendar.getInstance().get(Calendar.YEAR);
        String holidayListJson = holidayLocal.getString("list_" + year, "");

        StringBuilder sb = new StringBuilder(1280);
        sb.append("生成时间：").append(new Date()).append('\n');
        sb.append('\n');

        sb.append("【模块状态】").append('\n');
        sb.append("模块版本：").append(getAppVersionName()).append('\n');
        sb.append("框架已连接：").append(boolCn(mFrameworkActive)).append('\n');
        sb.append("Service 已绑定：").append(boolCn(mXposedService != null)).append('\n');
        sb.append("框架信息：").append(mFrameworkDesc == null ? "" : mFrameworkDesc.replace('\n', ' ')).append('\n');
        sb.append('\n');

        sb.append("【宿主与作用域】").append('\n');
        sb.append("超级小爱版本：").append(getTargetVersion(TARGET_VOICEASSIST)).append('\n');
        sb.append("时钟版本：").append(getTargetVersion(TARGET_DESKCLOCK)).append('\n');
        sb.append("已授权作用域(超级小爱)：").append(boolCn(scope.contains(TARGET_VOICEASSIST))).append('\n');
        sb.append("已授权作用域(时钟)：").append(boolCn(scope.contains(TARGET_DESKCLOCK))).append('\n');
        sb.append('\n');

        sb.append("【配置同步】").append('\n');
        sb.append("本地配置条目数：").append(local.getAll().size()).append('\n');
        sb.append("远程配置条目数：").append(remote != null ? remote.getAll().size() : -1).append('\n');
        sb.append("运行态数据源：").append(debugSource).append('\n');
        sb.append("运行态字段命中：").append(countDebugSignals(debug)).append('\n');
        sb.append("本地 Debug 条目数：").append(localDebug.getAll().size()).append('\n');
        sb.append("远程 Debug 条目数：").append(remoteDebug != null ? remoteDebug.getAll().size() : -1).append('\n');
        sb.append("本地节假日条目数：").append(holidayLocal.getAll().size()).append('\n');
        sb.append("远程节假日条目数：").append(holidayRemote != null ? holidayRemote.getAll().size() : -1).append('\n');
        sb.append('\n');

        sb.append("【功能开关】").append('\n');
        sb.append("提醒提前分钟：").append(local.getInt("reminder_minutes_before", 15)).append('\n');
        sb.append("上课静音：").append(boolCn(local.getBoolean("mute_enabled", false)))
                .append("（提前 ").append(local.getInt("mute_mins_before", 0)).append(" 分钟）").append('\n');
        sb.append("下课恢复铃声：").append(boolCn(local.getBoolean("unmute_enabled", false)))
                .append("（延后 ").append(local.getInt("unmute_mins_after", 0)).append(" 分钟）").append('\n');
        sb.append("上课勿扰：").append(boolCn(local.getBoolean("dnd_enabled", false)))
                .append("（提前 ").append(local.getInt("dnd_mins_before", 0)).append(" 分钟）").append('\n');
        sb.append("下课关闭勿扰：").append(boolCn(local.getBoolean("undnd_enabled", false)))
                .append("（延后 ").append(local.getInt("undnd_mins_after", 0)).append(" 分钟）").append('\n');
        sb.append("补发机制：").append(boolCn(local.getBoolean("repost_enabled", true))).append('\n');
        sb.append("岛按钮模式：").append(islandButtonModeText(local.getInt("island_button_mode", 0))).append('\n');
        sb.append("上午叫醒：").append(boolCn(local.getBoolean("wakeup_morning_enabled", false))).append('\n');
        sb.append("下午叫醒：").append(boolCn(local.getBoolean("wakeup_afternoon_enabled", false))).append('\n');
        sb.append("岛超时配置：").append(formatTimeoutSummary(local, "island")).append('\n');
        sb.append("通知超时配置：").append(formatTimeoutSummary(local, "notif")).append('\n');
        sb.append('\n');

        sb.append("【调度状态】").append('\n');
        sb.append("课程总周数：").append(local.getInt("course_total_week", 0)).append('\n');
        sb.append("上次跨日重调标记：").append(local.getInt("last_daily_reschedule_day", -1)).append('\n');
        sb.append("今日标记：").append(getTodayMarker()).append('\n');
        sb.append("本年节假日条目数：").append(countHolidayEntries(holidayListJson)).append('\n');
        sb.append('\n');

        String courseStatus = debug.getString("debug_course_status", "");
        String wakeupStatus = debug.getString("debug_wakeup_status", "");
        sb.append("【运行态诊断】").append('\n');
        sb.append("CourseData 检查：").append(courseStatus.isEmpty() ? "暂无记录" : courseStatus).append('\n');
        sb.append("最近课程调度：").append(formatDebugTime(debug.getLong("debug_course_last_ms", 0))).append('\n');
        sb.append("课程数据哈希：").append(debug.getInt("debug_course_hash", 0)).append('\n');
        sb.append("当前周次/总周数：")
                .append(debug.getInt("debug_course_present_week", 0)).append("/")
                .append(debug.getInt("debug_course_total_week", 0)).append('\n');
        sb.append("课程总数/今日有效：")
                .append(debug.getInt("debug_course_total_count", 0)).append("/")
                .append(debug.getInt("debug_course_today_count", 0)).append('\n');
        sb.append("课程调度错误：").append(nonEmptyOrDash(debug.getString("debug_course_error", ""))).append('\n');
        sb.append("自动叫醒检查：").append(wakeupStatus.isEmpty() ? "暂无记录" : wakeupStatus).append('\n');
        sb.append("最近叫醒调度：").append(formatDebugTime(debug.getLong("debug_wakeup_last_ms", 0))).append('\n');
        sb.append("最近清空闹钟：").append(formatDebugTime(debug.getLong("debug_wakeup_clear_ms", 0))).append('\n');
        sb.append("叫醒调度错误：").append(nonEmptyOrDash(debug.getString("debug_wakeup_error", ""))).append('\n');
        sb.append("自动静音/勿扰检查：")
                .append(nonEmptyOrDash(debug.getString("debug_mute_status", "暂无记录"))).append('\n');
        sb.append("最近静音/勿扰调度：").append(formatDebugTime(debug.getLong("debug_mute_last_ms", 0))).append('\n');
        sb.append("静音/勿扰调度数：").append(debug.getInt("debug_mute_count", 0)).append('\n');
        sb.append("静音/勿扰调度错误：").append(nonEmptyOrDash(debug.getString("debug_mute_error", ""))).append('\n');
        return sb.toString();
    }

    private String boolCn(boolean value) {
        return value ? "是" : "否";
    }

    private String islandButtonModeText(int mode) {
        if (mode == 1) return "仅勿扰 (1)";
        if (mode == 2) return "静音+勿扰 (2)";
        return "仅静音 (0)";
    }

    private String notifTriggerText(String phase) {
        if ("active".equals(phase)) return "上课后";
        if ("post".equals(phase)) return "下课后";
        return "通知后";
    }

    private SharedPreferences pickDebugPrefs(SharedPreferences local, SharedPreferences remote) {
        if (remote == null) return local;
        int remoteScore = countDebugSignals(remote);
        int localScore = countDebugSignals(local);
        return remoteScore >= localScore ? remote : local;
    }

    private int countDebugSignals(SharedPreferences sp) {
        if (sp == null) return 0;
        int score = 0;
        if (sp.getLong("debug_course_last_ms", 0) > 0) score++;
        if (!sp.getString("debug_course_status", "").isEmpty()) score++;
        if (sp.getLong("debug_wakeup_last_ms", 0) > 0) score++;
        if (!sp.getString("debug_wakeup_status", "").isEmpty()) score++;
        if (sp.getLong("debug_mute_last_ms", 0) > 0) score++;
        if (!sp.getString("debug_mute_status", "").isEmpty()) score++;
        return score;
    }

    private String formatTimeoutSummary(SharedPreferences sp, String prefix) {
        if ("notif".equals(prefix)) {
            String phase = sp.getString(KEY_NOTIF_DISMISS_TRIGGER, "pre");
            int val = sp.getInt("to_notif_val_" + phase, -1);
            if (val < 0) return "系统默认";
            String unit = sp.getString("to_notif_unit_" + phase, "m");
            String unitText = "s".equals(unit) ? "秒" : "分钟";
            return notifTriggerText(phase) + " " + val + unitText;
        }
        String[] phases = {"pre", "active", "post"};
        for (String phase : phases) {
            int val = sp.getInt("to_" + prefix + "_val_" + phase, -1);
            if (val >= 0) {
                String unit = sp.getString("to_" + prefix + "_unit_" + phase, "m");
                String unitText = "s".equals(unit) ? "秒" : "分钟";
                return notifTriggerText(phase) + " " + val + unitText;
            }
        }
        return "系统默认";
    }

    private String formatDebugTime(long ts) {
        if (ts <= 0) return "暂无";
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date(ts));
    }

    private String nonEmptyOrDash(String value) {
        return (value == null || value.isEmpty()) ? "-" : value;
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
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean muteEnabled   = sp.getBoolean("mute_enabled",   false);
        int     muteBefore    = sp.getInt("mute_mins_before",    0);
        boolean unmuteEnabled = sp.getBoolean("unmute_enabled", false);
        int     unmuteAfter   = sp.getInt("unmute_mins_after",   0);
        boolean dndEnabled    = sp.getBoolean("dnd_enabled",    false);
        int     dndBefore     = sp.getInt("dnd_mins_before",    0);
        boolean unDndEnabled  = sp.getBoolean("undnd_enabled",  false);
        int     unDndAfter    = sp.getInt("undnd_mins_after",   0);

        Intent intent = new Intent("com.xiaoai.islandnotify.ACTION_TEST_NOTIFY");
        intent.setPackage("com.miui.voiceassist");
        intent.putExtra("course_name", courseName);
        intent.putExtra("start_time",  startTime);
        intent.putExtra("end_time",    endTime);
        intent.putExtra("classroom",   classroom);
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
        SharedPreferences remoteHoliday = mRemoteHolidayPrefs;
        if (remoteHoliday != null) {
            remoteHoliday.edit().putString("list_" + year, json).apply();
        }
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
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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

    // ─────────────────────────────────────────────────────────────

}
