package com.xiaoai.islandnotify;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
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
import com.google.android.material.switchmaterial.SwitchMaterial;

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

        // 测试通知：1分钟后上课（倒计时）
        findViewById(R.id.btn_send_test).setOnClickListener(v ->
                requireNotifPermAndRun(() -> {
                    sendTestNotification(60_000L);
                    showTestHint("已发送测试通知（倒计时），请下拉通知栏查看超级岛效果");
                }));

        updateModuleStatus();
        initCustomCard();
        initHideIconSwitch();
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

        EditText etA      = findViewById(R.id.et_tpl_a);
        EditText etB      = findViewById(R.id.et_tpl_b);
        EditText etTicker = findViewById(R.id.et_tpl_ticker);
        SwitchMaterial swIconA   = findViewById(R.id.sw_icon_a);
        TextView tvHint   = findViewById(R.id.tv_save_hint);

        // 读取已保存配置，无则用默认值
        etA.setText(sp.getString("tpl_a",      "{开始}上课"));
        etB.setText(sp.getString("tpl_b",      "{教室}"));
        etTicker.setText(sp.getString("tpl_ticker", "{开始}上课 {教室}"));
        swIconA.setChecked(sp.getBoolean("icon_a",   true));

        findViewById(R.id.btn_save_custom).setOnClickListener(v -> {
            sp.edit()
                .putString("tpl_a",      etA.getText().toString().trim())
                .putString("tpl_b",      etB.getText().toString().trim())
                .putString("tpl_ticker", etTicker.getText().toString().trim())
                .putBoolean("icon_a",    swIconA.isChecked())
                .apply();
            tvHint.setText("已保存，下次通知生效");
            tvHint.setVisibility(View.VISIBLE);
        });
    }

    private static final String ALIAS = "com.xiaoai.islandnotify.MainActivityAlias";

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
