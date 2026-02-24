package com.xiaoai.islandnotify;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.res.ColorStateList;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
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

        // 前往通知策略设置（DnD 授权）
        findViewById(R.id.btn_dnd_settings).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));

        // 测试通知：3分钟后上课（倒计时）
        findViewById(R.id.btn_send_test).setOnClickListener(v ->
                requireNotifPermAndRun(() -> {
                    sendTestNotification(3 * 60 * 1000L);
                    showTestHint("已发送测试通知（倒计时），请下拉通知栏查看超级岛效果");
                }));

        // 测试通知：模拟正在上课（正计时，startTime = 5分钟前）
        findViewById(R.id.btn_send_test_now).setOnClickListener(v ->
                requireNotifPermAndRun(() -> {
                    sendTestNotification(-5 * 60 * 1000L);
                    showTestHint("已发送测试通知（正计时），超级岛应显示已开始");
                }));

        updateModuleStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到前台时刷新权限状态（用户可能刚从设置页返回）
        updatePermissionStatus();
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
            desc.setText("请在 LSPosed 管理器中启用，并将作用域设为小爱同学");
            desc.setTextColor(onColor);
        }
    }

    /**
     * 检查并更新权限状态图标。
     */
    private void updatePermissionStatus() {
        // MODIFY_AUDIO_SETTINGS 是普通权限，安装时自动授予
        boolean audioGranted = checkSelfPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;

        // 通知访问（勿扰控制）需要用户手动授权
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        boolean dndGranted = nm != null && nm.isNotificationPolicyAccessGranted();

        setPermIcon(R.id.iv_perm_audio, audioGranted);
        setPermIcon(R.id.iv_perm_dnd, dndGranted);
    }

    private void setPermIcon(int viewId, boolean granted) {
        ImageView iv = findViewById(viewId);
        if (granted) {
            iv.setImageResource(R.drawable.ic_perm_ok);
            int color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.GREEN);
            ImageViewCompat.setImageTintList(iv, ColorStateList.valueOf(color));
        } else {
            iv.setImageResource(R.drawable.ic_perm_warn);
            int color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorError, Color.RED);
            ImageViewCompat.setImageTintList(iv, ColorStateList.valueOf(color));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 测试通知
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
        android.widget.EditText etStartTime = findViewById(R.id.et_start_time);
        android.widget.EditText etEndTime   = findViewById(R.id.et_end_time);
        String courseName  = etName.getText() != null ? etName.getText().toString().trim() : "";
        String classroom   = etClassroom.getText() != null ? etClassroom.getText().toString().trim() : "";
        String customStart = etStartTime.getText() != null ? etStartTime.getText().toString().trim() : "";
        String customEnd   = etEndTime.getText() != null ? etEndTime.getText().toString().trim() : "";
        if (courseName.isEmpty()) courseName = "高等数学";
        if (classroom.isEmpty())  classroom  = "教学楼A301";

        // 确保通知频道存在（channelId 必须包含 COURSE_SCHEDULER_REMINDER）
        final String CHANNEL_ID = "COURSE_SCHEDULER_REMINDER_sound";
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "课程提醒", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }

        // 计算上课/结束时间字符串
        // 若用户填写了 HH:mm，则以今天该时刻为准；否则用当前时间 + 偏移
        long startMs;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        if (customStart.matches("\\d{1,2}:\\d{2}")) {
            String[] parts = customStart.split(":");
            cal.set(java.util.Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
            cal.set(java.util.Calendar.MINUTE,      Integer.parseInt(parts[1]));
            cal.set(java.util.Calendar.SECOND,      0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            startMs = cal.getTimeInMillis();
        } else {
            startMs = System.currentTimeMillis() + startOffsetMs;
            cal.setTimeInMillis(startMs);
        }
        String startTime = String.format(java.util.Locale.getDefault(), "%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
        String endTime;
        if (customEnd.matches("\\d{1,2}:\\d{2}")) {
            // 用户手动填写了结束时间
            String[] ep = customEnd.split(":");
            endTime = String.format(java.util.Locale.getDefault(), "%02d:%02d",
                    Integer.parseInt(ep[0]), Integer.parseInt(ep[1]));
        } else {
            // 未填写则默认 +90 分钟
            cal.setTimeInMillis(startMs + 90 * 60 * 1000L);
            endTime = String.format(java.util.Locale.getDefault(), "%02d:%02d",
                    cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
        }

        // 先打印假的 VA_PushReceiver Log.d，供 hookPushLog 缓存 endDateTime
        // 格式与真实推送 JSON 一致：payload → courseCard → courses
        String fakeJson = "{\"channelId\":\"" + CHANNEL_ID + "\","
                + "\"payload\":[{\"courseCard\":{\"courses\":["
                + "{\"startDateTime\":\"" + startTime + "\","
                + "\"endDateTime\":\"" + endTime + "\","
                + "\"name\":\"" + courseName + "\","
                + "\"classroom\":\"" + classroom + "\"}"
                + "]}}]}";
        Log.d("VA_PushReceiver", fakeJson);

        // 构造与小爱同学真实通知格式一致的通知
        // Title: [课程名]  Body: HH:mm | 教室
        String title = "[" + courseName + "]";
        String body  = startTime + " | " + classroom;
        android.app.Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build();

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
