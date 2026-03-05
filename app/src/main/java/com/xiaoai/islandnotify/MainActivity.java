package com.xiaoai.islandnotify;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.NotificationCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.tabs.TabLayout;

import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 模块主界面
 * - 展示模块激活状态（由 LSPosed Hook 动态注入）
 * - 展示权限状态
 * - 支持 Material You 动态取色（Android 12+）
 */
public class MainActivity extends AppCompatActivity {

    /** Android 13+ 通知权限运行时请求 */
    private ActivityResultLauncher<String> notifPermLauncher;
    /** 权限通过后执行的挂起动作 */
    private Runnable pendingTestAction;
    // 保存按钮引用与脏状态
    private MaterialButton btnSaveCustom;
    private MaterialButton btnSaveTimeout;
    private boolean customDirty  = false;
    private boolean timeoutDirty = false;

    // 假期/调休 Tab 相关成员
    private int           mCurrentHolidayYear;
    private LinearLayout  mLlHolidayList;
    private LinearLayout  mLlWorkswapList;
    private TextView      mTvHolidayEmpty;
    private TextView      mTvWorkswapEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须在 super.onCreate 前调用，才能正确应用动态色彩
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 注册通知权限请求（Android 13+）
        notifPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted && pendingTestAction != null) {
                        pendingTestAction.run();
                    } else {
                        showTestHint("通知权限被拒绝，无法发送测试通知");
                    }
                    pendingTestAction = null;
                });

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
        findViewById(R.id.btn_send_test).setOnClickListener(v ->
                requireNotifPermAndRun(() -> {
                    sendTestBroadcastToTarget(60_000L);
                    showTestHint("已发送测试通知（倒计时），请下拉通知栏查看超级岛效果");
                }));

        updateModuleStatus();
        initCustomCard();
        initTimeoutCard();
        initReminderCard();
        initMuteCard();
        initHideIconSwitch();
        initAboutSection(); // 初始化关于页面的版本信息
        setupTabs();
        initHolidayTab();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    // ─────────────────────────────────────────────────────────────

    /**
     * 根据模块是否激活，设置状态卡片的颜色、图标和文字。
     */
    private void updateModuleStatus() {
        boolean active = isModuleActive();

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
            desc.setText("Hook 正常运行，课程通知将自动转换为超级岛");
            desc.setTextColor(onColor);
        } else {
            int bg      = MaterialColors.getColor(this, com.google.android.material.R.attr.colorErrorContainer,   Color.LTGRAY);
            int onColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnErrorContainer, Color.BLACK);
            card.setCardBackgroundColor(bg);
            icon.setImageResource(R.drawable.ic_module_inactive);
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(onColor));
            title.setText("模块未激活");
            title.setTextColor(onColor);
            desc.setText("请在 LSPosed 管理器中启用，并将作用域设为超级小爱");
            desc.setTextColor(onColor);
        }
    }

    /** SharedPreferences 名称（与 MainHook 保持一致） */
    static final String PREFS_NAME = "island_custom";

    private void initCustomCard() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // 三个阶段 SP 后缀：_pre=上课前  _active=上课中  _post=下课后
        final String[] SUFFIXES = {"_pre", "_active", "_post"};
        // 各阶段 A/B/ticker 的默认值
        final String[] DEFAULT_A      = {"{教室}",             "{课名}",         "{课名}"};
        final String[] DEFAULT_B      = {"{开始}上课",         "{结束}下课",     "已经下课"};
        final String[] DEFAULT_TICKER = {"{教室}｜{开始}上课", "{课名}｜{结束}下课", "{课名}｜已经下课"};
        // 各阶段输入框 View ID
        final int[] IDS_A      = {R.id.et_tpl_a_pre,      R.id.et_tpl_a_active,      R.id.et_tpl_a_post};
        final int[] IDS_B      = {R.id.et_tpl_b_pre,      R.id.et_tpl_b_active,      R.id.et_tpl_b_post};
        final int[] IDS_TICKER = {R.id.et_tpl_ticker_pre,  R.id.et_tpl_ticker_active,  R.id.et_tpl_ticker_post};

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
            Intent sync = new Intent("com.xiaoai.islandnotify.ACTION_SYNC_PREFS");
            sync.setPackage("com.miui.voiceassist");

            for (int i = 0; i < 3; i++) {
                String tplA      = ((EditText) findViewById(IDS_A[i])).getText().toString().trim();
                String tplB      = ((EditText) findViewById(IDS_B[i])).getText().toString().trim();
                String tplTicker = ((EditText) findViewById(IDS_TICKER[i])).getText().toString().trim();
                ed.putString("tpl_a"      + SUFFIXES[i], tplA);
                ed.putString("tpl_b"      + SUFFIXES[i], tplB);
                ed.putString("tpl_ticker" + SUFFIXES[i], tplTicker);
                sync.putExtra("tpl_a"      + SUFFIXES[i], tplA);
                sync.putExtra("tpl_b"      + SUFFIXES[i], tplB);
                sync.putExtra("tpl_ticker" + SUFFIXES[i], tplTicker);
            }
            boolean iconA = swIconA.isChecked();
            ed.putBoolean("icon_a", iconA);
            sync.putExtra("icon_a", iconA);
            ed.apply();
            sendBroadcast(sync);

            tvHint.setText("已保存，下次通知生效");
            tvHint.setVisibility(View.VISIBLE);
            // 保存后清除指示
            customDirty = false;
            updateCustomDirtyIndicator();
        });

        // helper: perform actual save (used by confirmation)
        
    }

    private void doSaveCustom(SharedPreferences sp) {
        SharedPreferences.Editor ed = sp.edit();
        final String[] SUFFIXES = {"_pre", "_active", "_post"};
        final int[] IDS_A      = {R.id.et_tpl_a_pre,      R.id.et_tpl_a_active,      R.id.et_tpl_a_post};
        final int[] IDS_B      = {R.id.et_tpl_b_pre,      R.id.et_tpl_b_active,      R.id.et_tpl_b_post};
        final int[] IDS_TICKER = {R.id.et_tpl_ticker_pre,  R.id.et_tpl_ticker_active,  R.id.et_tpl_ticker_post};
        SwitchMaterial swIconA = findViewById(R.id.sw_icon_a);

        Intent sync = new Intent("com.xiaoai.islandnotify.ACTION_SYNC_PREFS");
        sync.setPackage("com.miui.voiceassist");

        for (int i = 0; i < 3; i++) {
            String tplA      = ((EditText) findViewById(IDS_A[i])).getText().toString().trim();
            String tplB      = ((EditText) findViewById(IDS_B[i])).getText().toString().trim();
            String tplTicker = ((EditText) findViewById(IDS_TICKER[i])).getText().toString().trim();
            ed.putString("tpl_a"      + SUFFIXES[i], tplA);
            ed.putString("tpl_b"      + SUFFIXES[i], tplB);
            ed.putString("tpl_ticker" + SUFFIXES[i], tplTicker);
            sync.putExtra("tpl_a"      + SUFFIXES[i], tplA);
            sync.putExtra("tpl_b"      + SUFFIXES[i], tplB);
            sync.putExtra("tpl_ticker" + SUFFIXES[i], tplTicker);
        }
        boolean iconA = swIconA.isChecked();
        ed.putBoolean("icon_a", iconA);
        sync.putExtra("icon_a", iconA);
        ed.apply();
        sendBroadcast(sync);

        TextView tvHint = findViewById(R.id.tv_save_hint);
        tvHint.setText("已保存，下次通知生效");
        tvHint.setVisibility(View.VISIBLE);
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
            String sA = sp.getString("tpl_a" + SUFFIXES[i], "");
            String sB = sp.getString("tpl_b" + SUFFIXES[i], "");
            String sT = sp.getString("tpl_ticker" + SUFFIXES[i], "");
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
        int savedIsVal = sp.getInt("to_island_val_" + TO_PHASES[idxIsland], -1);
        String savedIsUnit = sp.getString("to_island_unit_" + TO_PHASES[idxIsland], "m");
        if (curIslandVal != savedIsVal) return true;
        if (!curIslandUnit.equals(savedIsUnit)) return true;

        // 通知：当前阶段
        MaterialButtonToggleGroup toggleNotifPhase = findViewById(R.id.toggle_notif_phase);
        int checkedNotif = toggleNotifPhase.getCheckedButtonId();
        int idxNotif = (checkedNotif == R.id.btn_notif_phase_active) ? 1
                : (checkedNotif == R.id.btn_notif_phase_post) ? 2 : 0;
        EditText etNotif = findViewById(R.id.et_notif_to);
        String curNotifStr = etNotif.getText() != null ? etNotif.getText().toString().trim() : "";
        int curNotifVal = curNotifStr.isEmpty() ? -1 : tryParseInt(curNotifStr, -1);
        MaterialButtonToggleGroup toggleNotifUnit = findViewById(R.id.toggle_notif_unit);
        String curNotifUnit = (toggleNotifUnit.getCheckedButtonId() == R.id.btn_notif_s) ? "s" : "m";
        int savedNoVal = sp.getInt("to_notif_val_" + TO_PHASES[idxNotif], -1);
        String savedNoUnit = sp.getString("to_notif_unit_" + TO_PHASES[idxNotif], "m");
        if (curNotifVal != savedNoVal) return true;
        if (!curNotifUnit.equals(savedNoUnit)) return true;

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
        final int[] curNotifPhase = {0};
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

        toggleNotifPhase.check(R.id.btn_notif_phase_pre);
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
            if (notifGlobalDefault[0]) {
                for (int i = 0; i < 3; i++) notifVals[i] = -1;
            }

            SharedPreferences.Editor ed = sp.edit();
            Intent sync = new Intent("com.xiaoai.islandnotify.ACTION_SYNC_PREFS");
            sync.setPackage("com.miui.voiceassist");

            // 岛：三阶段键
            for (int i = 0; i < 3; i++) {
                ed.putInt   ("to_island_val_"  + TO_PHASES[i], islandVals[i]);
                ed.putString("to_island_unit_" + TO_PHASES[i], islandUnits[i]);
                sync.putExtra("to_island_val_"  + TO_PHASES[i], islandVals[i]);
                sync.putExtra("to_island_unit_" + TO_PHASES[i], islandUnits[i]);
            }

            // 通知：三阶段
            for (int i = 0; i < 3; i++) {
                ed.putInt   ("to_notif_val_"  + TO_PHASES[i], notifVals[i]);
                ed.putString("to_notif_unit_" + TO_PHASES[i], notifUnits[i]);
                sync.putExtra("to_notif_val_"  + TO_PHASES[i], notifVals[i]);
                sync.putExtra("to_notif_unit_" + TO_PHASES[i], notifUnits[i]);
            }

            // 删除旧版/遗留键，避免模块内部 SharedPreferences 与目标进程不一致
            ed.remove("to_island_val");
            ed.remove("to_island_unit");
            ed.remove("use_default_behavior");
            ed.remove("notif_dismiss_value");
            ed.remove("notif_dismiss_unit");
            ed.remove("notif_dismiss_trigger");
            ed.remove("island_dismiss_value");
            ed.remove("island_dismiss_unit");
            ed.remove("island_dismiss_trigger");
            ed.apply();
            sendBroadcast(sync);

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

            Intent sync = new Intent("com.xiaoai.islandnotify.ACTION_SYNC_PREFS");
            sync.setPackage("com.miui.voiceassist");
            sync.putExtra("reminder_minutes_before", minutes);
            sendBroadcast(sync);

            tvHint.setText("已保存，重新调度今日提醒（提前 " + minutes + " 分钟）");
            tvHint.setVisibility(View.VISIBLE);
        });
    }

    /**
     * 初始化「上课免打扰」卡片。
     * 支持静音/勿扰两种模式，与课前提醒卡片完全独立。
     */
    private void initMuteCard() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        RadioGroup     rgMuteMode    = findViewById(R.id.rg_mute_mode);
        SwitchMaterial swMute        = findViewById(R.id.sw_mute_enabled);
        View           llMute        = findViewById(R.id.ll_mute_content);
        EditText       etMuteBefore  = findViewById(R.id.et_mute_mins_before);
        SwitchMaterial swUnmute      = findViewById(R.id.sw_unmute_enabled);
        View           llUnmute      = findViewById(R.id.ll_unmute_content);
        EditText       etUnmuteAfter = findViewById(R.id.et_unmute_mins_after);
        TextView       tvMuteHint    = findViewById(R.id.tv_mute_hint);
        TextView       tvMuteSwitchTitle   = findViewById(R.id.tv_mute_switch_title);
        TextView       tvMuteSwitchDesc    = findViewById(R.id.tv_mute_switch_desc);
        TextView       tvUnmuteSwitchTitle = findViewById(R.id.tv_unmute_switch_title);
        TextView       tvUnmuteSwitchDesc  = findViewById(R.id.tv_unmute_switch_desc);
        TextView       tvMuteMinsLabel     = findViewById(R.id.tv_mute_mins_label);
        TextView       tvUnmuteMinsLabel   = findViewById(R.id.tv_unmute_mins_label);

        boolean savedMuteEnabled   = sp.getBoolean("mute_enabled", false);
        int     savedMuteBefore    = sp.getInt("mute_mins_before", 0);
        boolean savedUnmuteEnabled = sp.getBoolean("unmute_enabled", false);
        int     savedUnmuteAfter   = sp.getInt("unmute_mins_after", 0);
        boolean savedDndMode       = sp.getBoolean("dnd_mode", false);

        // 根据已保存的模式初始化 RadioGroup 和描述文字
        rgMuteMode.check(savedDndMode ? R.id.rb_mode_dnd : R.id.rb_mode_silent);
        applyMuteModeLabels(savedDndMode, tvMuteSwitchTitle, tvMuteSwitchDesc,
                tvUnmuteSwitchTitle, tvUnmuteSwitchDesc, tvMuteMinsLabel, tvUnmuteMinsLabel);

        swMute.setChecked(savedMuteEnabled);
        llMute.setVisibility(savedMuteEnabled ? View.VISIBLE : View.GONE);
        etMuteBefore.setText(String.valueOf(savedMuteBefore));

        swUnmute.setChecked(savedUnmuteEnabled);
        llUnmute.setVisibility(savedUnmuteEnabled ? View.VISIBLE : View.GONE);
        etUnmuteAfter.setText(String.valueOf(savedUnmuteAfter));

        // 静音/勿扰 模式选择
        rgMuteMode.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isDnd = (checkedId == R.id.rb_mode_dnd);
            sp.edit().putBoolean("dnd_mode", isDnd).apply();
            applyMuteModeLabels(isDnd, tvMuteSwitchTitle, tvMuteSwitchDesc,
                    tvUnmuteSwitchTitle, tvUnmuteSwitchDesc, tvMuteMinsLabel, tvUnmuteMinsLabel);
            Intent sync = new Intent("com.xiaoai.islandnotify.ACTION_SYNC_PREFS");
            sync.setPackage("com.miui.voiceassist");
            sync.putExtra("dnd_mode", isDnd);
            sendBroadcast(sync);
            tvMuteHint.setText(isDnd ? "已切换为勿扰模式，超级岛将显示「上课勿扰」" : "已切换为静音模式，超级岛将显示「上课静音」");
            tvMuteHint.setVisibility(View.VISIBLE);
        });

        // 静音开关
        swMute.setOnCheckedChangeListener((btn, checked) -> {
            llMute.setVisibility(checked ? View.VISIBLE : View.GONE);
            sp.edit().putBoolean("mute_enabled", checked).apply();
            Intent sync = new Intent("com.xiaoai.islandnotify.ACTION_SYNC_PREFS");
            sync.setPackage("com.miui.voiceassist");
            sync.putExtra("mute_enabled", checked);
            sendBroadcast(sync);
        });

        // 取消静音开关
        swUnmute.setOnCheckedChangeListener((btn, checked) -> {
            llUnmute.setVisibility(checked ? View.VISIBLE : View.GONE);
            sp.edit().putBoolean("unmute_enabled", checked).apply();
            Intent sync = new Intent("com.xiaoai.islandnotify.ACTION_SYNC_PREFS");
            sync.setPackage("com.miui.voiceassist");
            sync.putExtra("unmute_enabled", checked);
            sendBroadcast(sync);
        });

        // 保存静音时间设置
        findViewById(R.id.btn_save_mute).setOnClickListener(v -> {
            int muteBefore;
            int unmuteAfter;
            try {
                muteBefore = Integer.parseInt(
                        etMuteBefore.getText() != null ? etMuteBefore.getText().toString().trim() : "0");
                if (muteBefore < 0) muteBefore = 0;
                if (muteBefore > 60) muteBefore = 60;
            } catch (NumberFormatException e) { muteBefore = 0; }
            try {
                unmuteAfter = Integer.parseInt(
                        etUnmuteAfter.getText() != null ? etUnmuteAfter.getText().toString().trim() : "0");
                if (unmuteAfter < 0) unmuteAfter = 0;
                if (unmuteAfter > 60) unmuteAfter = 60;
            } catch (NumberFormatException e) { unmuteAfter = 0; }

            etMuteBefore.setText(String.valueOf(muteBefore));
            etUnmuteAfter.setText(String.valueOf(unmuteAfter));

            sp.edit()
              .putInt("mute_mins_before", muteBefore)
              .putInt("unmute_mins_after", unmuteAfter)
              .apply();

            Intent sync = new Intent("com.xiaoai.islandnotify.ACTION_SYNC_PREFS");
            sync.setPackage("com.miui.voiceassist");
            sync.putExtra("mute_mins_before", muteBefore);
            sync.putExtra("unmute_mins_after", unmuteAfter);
            sendBroadcast(sync);

            tvMuteHint.setText("设置已保存并重新调度");
            tvMuteHint.setVisibility(View.VISIBLE);
        });
    }

    /** 根据当前模式更新静音卡片内的描述文字 */
    private void applyMuteModeLabels(boolean isDnd,
            TextView tvMuteSwitchTitle, TextView tvMuteSwitchDesc,
            TextView tvUnmuteSwitchTitle, TextView tvUnmuteSwitchDesc,
            TextView tvMuteMinsLabel, TextView tvUnmuteMinsLabel) {
        if (isDnd) {
            tvMuteSwitchTitle.setText("上课自动开启勿扰");
            tvMuteSwitchDesc.setText("在课程开始前指定时间开启勿扰（DND）模式");
            tvUnmuteSwitchTitle.setText("下课自动关闭勿扰");
            tvUnmuteSwitchDesc.setText("在课程结束后指定时间关闭勿扰，恢复正常通知");
            if (tvMuteMinsLabel   != null) tvMuteMinsLabel.setText("上课前多少分钟开启勿扰");
            if (tvUnmuteMinsLabel != null) tvUnmuteMinsLabel.setText("下课后多少分钟关闭勿扰");
        } else {
            tvMuteSwitchTitle.setText("上课自动静音");
            tvMuteSwitchDesc.setText("在课程开始前指定时间将手机调为静音");
            tvUnmuteSwitchTitle.setText("下课自动恢复铃声");
            tvUnmuteSwitchDesc.setText("在课程结束后指定时间将手机恢复为正常响铃");
            if (tvMuteMinsLabel   != null) tvMuteMinsLabel.setText("上课前多少分钟静音");
            if (tvUnmuteMinsLabel != null) tvUnmuteMinsLabel.setText("下课后多少分钟恢复铃声");
        }
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

    /** 初始化关于页面，动态显示版本号 */
    private void initAboutSection() {
        TextView versionText = findViewById(R.id.version_text);
        if (versionText != null) {
            String versionName = getAppVersionName();
            versionText.setText(versionName);
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

    /** 检查/请求通知权限，通过后再执行 action。 */
    private void requireNotifPermAndRun(Runnable action) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingTestAction = action;
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        action.run();
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
        SharedPreferences sp = getSharedPreferences("island_custom", MODE_PRIVATE);
        boolean muteEnabled   = sp.getBoolean("mute_enabled",   false);
        int     muteBefore    = sp.getInt("mute_mins_before",    0);
        boolean unmuteEnabled = sp.getBoolean("unmute_enabled", false);
        int     unmuteAfter   = sp.getInt("unmute_mins_after",   0);

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
        // 把精确的课程开始/结束毫秒时间戳带入，让 voiceassist 端完全复用真实调度逻辑
        intent.putExtra("start_ms", startMs);
        intent.putExtra("end_ms",   endMs);
        sendBroadcast(intent);
    }

    /**
     * 发送一条模拟课程提醒通知。
     *
     * @param startOffsetMs 上课时间相对于"现在"的偏移毫秒：
     *                      正数 = N 毫秒后上课（倒计时），负数 = N 毫秒前已开始（正计时）
     */
    private void sendTestNotification(long startOffsetMs) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // 读取输入框内容，为空时使用默认值
        android.widget.EditText etName      = findViewById(R.id.et_course_name);
        android.widget.EditText etClassroom = findViewById(R.id.et_classroom);
        String courseName  = etName.getText() != null ? etName.getText().toString().trim() : "";
        String classroom   = etClassroom.getText() != null ? etClassroom.getText().toString().trim() : "";
        if (courseName.isEmpty()) courseName = "高等数学";
        if (classroom.isEmpty())  classroom  = "教科A-101";

        // 确保通知频道存在（channelId 必须包含 COURSE_SCHEDULER_REMINDER）
        final String CHANNEL_ID = "COURSE_SCHEDULER_REMINDER_sound";
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "课程提醒", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }

        // 开始时间 = 当前时间 + 偏移，结束时间 = 当前时间 + 2 分钟
        long now = System.currentTimeMillis();
        long startMs = now + startOffsetMs;
        long endMs   = now + 2 * 60_000L;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(startMs);
        String startTime = String.format(java.util.Locale.getDefault(), "%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
        cal.setTimeInMillis(endMs);
        String endTime = String.format(java.util.Locale.getDefault(), "%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));

        // 不再打印假 JSON，也不再依赖 lastPushedInfo 跨进程缓存。
        // 测试通知必须走与真实通知完全相同的代码路径：
        //   extractFromRemoteViews → 反射 bigContentView.mActions → 读 CharSequence 值
        //
        // 真实 voiceassist bigContentView mActions 文本顺序（实测）：
        //   [0] "[课程名]快到了，提前准备一下吧"
        //   [1] 开始时间 "HH:mm"
        //   [2] 结束时间 "HH:mm"
        //   [3] 课程名（纯文字）
        //   [4] 教室
        //   [5] "上课静音"（按钮，会被过滤）
        //   [6] "完整课表"（按钮，会被过滤）
        android.widget.RemoteViews bigView = new android.widget.RemoteViews(
                getPackageName(), R.layout.notification_test_big);
        bigView.setTextViewText(R.id.tv_notif_title, "[" + courseName + "]快到了，提前准备一下吧");
        bigView.setTextViewText(R.id.tv_notif_start, startTime);
        bigView.setTextViewText(R.id.tv_notif_end,   endTime);
        bigView.setTextViewText(R.id.tv_notif_name,  courseName);
        bigView.setTextViewText(R.id.tv_notif_room,  classroom);

        android.app.Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build();
        // 真实通知 EXTRA_TITLE / EXTRA_TEXT 均为 null，测试保持一致
        notif.extras.remove("android.title");
        notif.extras.remove("android.text");
        notif.extras.putBoolean("android.contains.customView", true);
        notif.bigContentView = bigView;

        nm.notify(1001, notif);
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

    private static final int[] SETTINGS_CARD_IDS = {
            R.id.card_status, R.id.card_test, R.id.card_custom,
            R.id.card_timeout, R.id.card_reminder, R.id.card_mute, R.id.card_about
    };

    private void setupTabs() {
        TabLayout tabLayout = findViewById(R.id.tab_layout);
        tabLayout.addTab(tabLayout.newTab().setText("设置"));
        tabLayout.addTab(tabLayout.newTab().setText("假期/调休"));
        showSettingsTab(true);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showSettingsTab(tab.getPosition() == 0);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void showSettingsTab(boolean showSettings) {
        for (int id : SETTINGS_CARD_IDS) {
            View v = findViewById(id);
            if (v != null) v.setVisibility(showSettings ? View.VISIBLE : View.GONE);
        }
        View hContainer = findViewById(R.id.container_holiday);
        if (hContainer != null)
            hContainer.setVisibility(showSettings ? View.GONE : View.VISIBLE);
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
        EditText etYear = findViewById(R.id.et_holiday_year);
        etYear.setText(String.valueOf(mCurrentHolidayYear));
        etYear.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String t = s.toString().trim();
                if (t.length() == 4) {
                    try {
                        int y = Integer.parseInt(t);
                        if (y >= 2020 && y <= 2099) {
                            mCurrentHolidayYear = y;
                            renderHolidayLists();
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        });

        // ── 从网络获取 ─────────────────────────────────────────────
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

                    // 合并：保留用户自定义条目，替换/追加 API 条目（按日期去重 API 部分）
                    List<HolidayManager.HolidayEntry> existing =
                            HolidayManager.loadEntries(this, year);
                    List<HolidayManager.HolidayEntry> merged = new ArrayList<>();
                    // 先保留自定义
                    for (HolidayManager.HolidayEntry e : existing) {
                        if (e.isCustom) merged.add(e);
                    }
                    // 追加 API 条目（若自定义里没有同日期的则直接加，否则跳过）
                    for (HolidayManager.HolidayEntry ae : apiEntries) {
                        boolean conflict = false;
                        for (HolidayManager.HolidayEntry ce : merged) {
                            if (ce.date.equals(ae.date)) { conflict = true; break; }
                        }
                        if (!conflict) merged.add(ae);
                    }
                    merged.sort((a, b) -> a.date.compareTo(b.date));

                    HolidayManager.saveEntries(this, year, merged);
                    syncHolidayToHook(year);

                    int hCount = 0, wCount = 0;
                    for (HolidayManager.HolidayEntry e : apiEntries)
                        if (e.type == HolidayManager.TYPE_HOLIDAY) hCount++; else wCount++;
                    int finalHCount = hCount, finalWCount = wCount;

                    runOnUiThread(() -> {
                        renderHolidayLists();
                        tvFetchHint.setText("获取成功：节假日 " + finalHCount + " 天，调休工作日 " + finalWCount + " 天");
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
        String dateDesc = entry.date;
        if (entry.endDate != null && !entry.endDate.isEmpty() && !entry.endDate.equals(entry.date)) {
            dateDesc += " ~ " + entry.endDate.substring(5); // 简写结束日期
        }
        tvMain.setText(dateDesc + "  " + entry.name);
        textArea.addView(tvMain);

        TextView tvTag = new TextView(this);
        tvTag.setTextSize(11f);
        tvTag.setText(entry.isCustom ? "自定义节假日" : "API 节假日");
        tvTag.setTextColor(entry.isCustom ? 0xFF7965AF : 0xFF389E0D);
        textArea.addView(tvTag);

        row.addView(textArea);

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
        dp.setMarginStart(dpToPx(8));
        btnDel.setLayoutParams(dp);
        btnDel.setOnClickListener(v -> {
            all.remove(entry);
            HolidayManager.saveEntries(this, mCurrentHolidayYear, all);
            syncHolidayToHook(mCurrentHolidayYear);
            renderHolidayLists();
        });
        row.addView(btnDel);
        return row;
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
        tvMain.setText(entry.date + " " + entry.name);
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
                    if (editEntry != null) all.remove(editEntry);
                    HolidayManager.HolidayEntry e =
                            new HolidayManager.HolidayEntry(startDate[0], endDate[0], name, HolidayManager.TYPE_HOLIDAY, true);
                    all.add(e);
                    all.sort((a, b) -> a.date.compareTo(b.date));
                    HolidayManager.saveEntries(this, mCurrentHolidayYear, all);
                    syncHolidayToHook(mCurrentHolidayYear);
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
                    if (editEntry != null) all.remove(editEntry);
                    HolidayManager.HolidayEntry e =
                            new HolidayManager.HolidayEntry(date[0], "", name, HolidayManager.TYPE_WORKSWAP, true);
                    e.followWeek    = week;
                    e.followWeekday = wd;
                    all.add(e);
                    all.sort((a, b) -> a.date.compareTo(b.date));
                    HolidayManager.saveEntries(this, mCurrentHolidayYear, all);
                    syncHolidayToHook(mCurrentHolidayYear);
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
        Intent sync = new Intent("com.xiaoai.islandnotify.ACTION_SYNC_PREFS");
        sync.setPackage("com.miui.voiceassist");
        sync.putExtra(HolidayManager.EXTRA_LIST_PREFIX + year, json);
        sendBroadcast(sync);
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

    /**
     * 此方法默认返回 false。
     * 当模块在 LSPosed 中激活并注入本进程后，
     * MainHook#hookSelfStatus 会将此方法的返回值替换为 true，
     * 从而让界面正确显示「模块已激活」状态。
     */
    public static boolean isModuleActive() {
        return false;
    }
}
