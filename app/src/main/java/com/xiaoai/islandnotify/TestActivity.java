package com.xiaoai.islandnotify;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 测试 Activity：无需 com.miui.voiceassist，直接向系统发送携带
 * miui.focus.param 的通知，用于在设备上验证超级岛渲染效果。
 */
public class TestActivity extends Activity {

    private static final String TEST_CHANNEL_ID = "island_notify_test";
    private static final int    NOTIF_ID        = 99901;

    private static final String COURSE_ICON_URL =
            "https://cdn.cnbj1.fds.api.mi-img.com/xiaoailite-ios/XiaoAiSuggestion/MsgSettingIconCourse.png";
    private static final String MUTE_ACTION =
            "com.xiaoai.islandnotify.ACTION_MUTE";
    private static final String COURSE_TABLE_INTENT =
            "intent://aiweb?url=https%3A%2F%2Fi.ai.mi.com%2Fh5%2Fprecache%2Fai-schedule%2F%23%2FtodayLesson" +
            "&flag=805339136&noBack=false&statusBarColor=FFFFFF&statusBarTextBlack=true" +
            "&navigationBarColor=FFFFFF#Intent;scheme=voiceassist;package=com.miui.voiceassist;end";

    /** 图标 Bitmap 缓存（后台线程下载） */
    private static volatile Bitmap sCourseBitmap = null;

    private EditText etCourse;
    private EditText etTime;
    private EditText etEndTime;
    private EditText etRoom;
    private TextView tvJson;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── 根布局 ────────────────────────────────────────────────
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.setBackgroundColor(Color.parseColor("#F5F5F5"));

        // ── 标题 ──────────────────────────────────────────────────
        TextView title = new TextView(this);
        title.setText("超级岛通知测试");
        title.setTextSize(20);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        // ── 输入区 ────────────────────────────────────────────────
        etCourse  = makeInput("课程名称", "高等数学");
        etTime    = makeInput("开始时间", "10:20");
        etEndTime = makeInput("结束时间", "12:05");
        etRoom    = makeInput("教室",     "教1-201");
        root.addView(labelWrap("课程名称", etCourse));
        root.addView(labelWrap("开始时间", etTime));
        root.addView(labelWrap("结束时间", etEndTime));
        root.addView(labelWrap("教室",     etRoom));

        // ── 按钮组 ────────────────────────────────────────────────
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        Button btnSend = makeButton("发送超级岛通知", Color.parseColor("#FF6200EE"));
        btnSend.setOnClickListener(v -> sendIslandNotification());

        Button btnRaw = makeButton("发送原始通知(测试Hook)", Color.parseColor("#FF03DAC5"));
        btnRaw.setOnClickListener(v -> sendRawNotification());

        btnRow.addView(btnSend);
        btnRow.addView(space(dp(12)));
        btnRow.addView(btnRaw);
        root.addView(btnRow);
        root.addView(space(dp(12)));

        // ── JSON 预览 ─────────────────────────────────────────────
        TextView jsonLabel = new TextView(this);
        jsonLabel.setText("注入的 JSON 预览：");
        jsonLabel.setTextColor(Color.DKGRAY);
        root.addView(jsonLabel);

        tvJson = new TextView(this);
        tvJson.setTextSize(11);
        tvJson.setTextColor(Color.parseColor("#212121"));
        tvJson.setBackgroundColor(Color.WHITE);
        tvJson.setPadding(dp(8), dp(8), dp(8), dp(8));
        tvJson.setText("点击按钮后此处显示 JSON");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(tvJson);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        lp.topMargin = dp(4);
        scroll.setLayoutParams(lp);
        root.addView(scroll);

        setContentView(root);

        // ── 申请通知权限（Android 13+）────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
        createChannel();
        downloadIconAsync();
    }

    /** 异步下载课程图标并缓存 */
    private void downloadIconAsync() {
        if (sCourseBitmap != null) return;
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(COURSE_ICON_URL).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.connect();
                if (conn.getResponseCode() == 200) {
                    Bitmap bmp = BitmapFactory.decodeStream(conn.getInputStream());
                    if (bmp != null) sCourseBitmap = bmp;
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "TestIconFetch").start();
    }

    // ─────────────────────────────────────────────────────────────
    // 发送超级岛通知（已注入 miui.focus.param）
    // ─────────────────────────────────────────────────────────────

    private void sendIslandNotification() {
        String course  = etCourse.getText().toString().trim();
        String time    = etTime.getText().toString().trim();
        String endTime = etEndTime.getText().toString().trim();
        String room    = etRoom.getText().toString().trim();
        if (course.isEmpty()) { toast("请输入课程名称"); return; }

        try {
            String json = buildIslandJson(course, time, endTime, room);
            tvJson.setText(prettyJson(json));

            Notification.Builder builder = new Notification.Builder(this, TEST_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(course)
                    .setContentText((endTime.isEmpty() ? time : time + "-" + endTime)
                            + (room.isEmpty() ? "" : " | " + room))
                    .setAutoCancel(true);

            Notification notif = builder.build();
            notif.extras.putString("miui.focus.param", json);

            // ── 图标 Bundle（miui.focus.pics，直接存 Bitmap）─────────────────
            if (sCourseBitmap != null) {
                android.os.Bundle picsBundle = new android.os.Bundle();
                picsBundle.putParcelable("miui.focus.pic_course", sCourseBitmap);
                notif.extras.putBundle("miui.focus.pics", picsBundle);
            }

            // ── Action Bundle（miui.focus.actions）────────────────────
            Intent muteIntent = new Intent(MUTE_ACTION);
            muteIntent.setPackage(getPackageName());
            PendingIntent mutePi = PendingIntent.getBroadcast(
                    this, 0, muteIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            android.os.Bundle actionsBundle = new android.os.Bundle();
            actionsBundle.putParcelable("miui.focus.action_mute", mutePi);
            notif.extras.putBundle("miui.focus.actions", actionsBundle);

            // ── 整体点击 → 课表页 contentIntent─────────────────
            try {
                Intent tableIntent = Intent.parseUri(
                        COURSE_TABLE_INTENT, Intent.URI_INTENT_SCHEME);
                notif.contentIntent = PendingIntent.getActivity(
                        this, 1, tableIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            } catch (Exception ignored) {}

            getSystemService(NotificationManager.class).notify(NOTIF_ID, notif);
            toast("已发送超级岛通知 " + (sCourseBitmap != null ? "(含图标)" : "(图标加载中)") + " ✓");
        } catch (JSONException e) {
            toast("JSON 构建失败：" + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 发送原始通知（模拟 com.miui.voiceassist 的格式，测试 Hook 识别）
    // ─────────────────────────────────────────────────────────────

    private void sendRawNotification() {
        String course = etCourse.getText().toString().trim();
        String time   = etTime.getText().toString().trim();
        String room   = etRoom.getText().toString().trim();
        if (course.isEmpty()) { toast("请输入课程名称"); return; }

        // 完全模拟 com.miui.voiceassist 的通知格式（来自 logcat）
        String fakeTitle = "[" + course + "]快到了，提前准备一下吧";
        String fakeBody  = time + (room.isEmpty() ? "" : " | " + room);
        tvJson.setText("原始通知格式（Hook 将自动转换）\n\ntitle: " + fakeTitle
                + "\ntext:  " + fakeBody
                + "\n\n注意：Hook 仅在 com.miui.voiceassist\n进程内生效，"
                + "此按钮从本模块进程发出，\n仅用于验证通知格式是否正确。");

        Notification notif = new Notification.Builder(this, TEST_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(fakeTitle)
                .setContentText(fakeBody)
                .setAutoCancel(true)
                .build();

        getSystemService(NotificationManager.class).notify(NOTIF_ID + 1, notif);
        toast("已发送原始格式通知 ✓");
    }

    // ─────────────────────────────────────────────────────────────
    // 构建超级岛 JSON（与 MainHook.buildIslandParams 保持一致）
    // ─────────────────────────────────────────────────────────────

    private String buildIslandJson(String course, String time, String endTime, String room)
            throws JSONException {

        // 时间显示：有结束时间 → "19:50-20:35"，否则仅 "19:50"
        String timeDisplay = (endTime != null && !endTime.isEmpty())
                ? time + "-" + endTime : time;

        // 大岛 A区：imageTextInfoLeft（图文组件1 type=1）+ picInfo（课程图标）
        JSONObject picInfo = new JSONObject();
        picInfo.put("type", 1);
        picInfo.put("pic", "miui.focus.pic_course");

        JSONObject aTextInfo = new JSONObject();
        aTextInfo.put("title", course);
        if (!timeDisplay.isEmpty()) aTextInfo.put("content", timeDisplay);
        JSONObject imageTextInfoLeft = new JSONObject();
        imageTextInfoLeft.put("type", 1);
        imageTextInfoLeft.put("picInfo", picInfo);
        imageTextInfoLeft.put("textInfo", aTextInfo);

        // 大岛 B区：textInfo（只放教室值，不加"地点"标签前缀）
        JSONObject bTextInfo = new JSONObject();
        bTextInfo.put("title", room.isEmpty() ? "—" : room);

        // 上课静音按钮
        JSONObject muteBtn = new JSONObject();
        muteBtn.put("actionTitle", "上课静音");
        muteBtn.put("action", "miui.focus.action_mute");
        org.json.JSONArray textButton = new org.json.JSONArray();
        textButton.put(muteBtn);

        JSONObject bigIslandArea = new JSONObject();
        bigIslandArea.put("imageTextInfoLeft", imageTextInfoLeft);
        bigIslandArea.put("textInfo", bTextInfo);
        bigIslandArea.put("textButton", textButton);

        // 小岛：空对象 → 系统自动兜底 App 图标
        JSONObject smallIslandArea = new JSONObject();

        JSONObject paramIsland = new JSONObject();
        paramIsland.put("islandProperty", 1);
        paramIsland.put("bigIslandArea",   bigIslandArea);
        paramIsland.put("smallIslandArea", smallIslandArea);

        // baseInfo
        String bodyContent = timeDisplay + (room.isEmpty() ? "" : " | " + room);
        String ticker = timeDisplay.isEmpty() ? course : course + "  " + time;
        JSONObject baseInfo = new JSONObject();
        baseInfo.put("title",   course);
        baseInfo.put("content", bodyContent.isEmpty() ? course : bodyContent);
        baseInfo.put("type", 1);

        // param_v2
        JSONObject paramV2 = new JSONObject();
        paramV2.put("protocol",        1);
        paramV2.put("business",        "course_schedule");
        paramV2.put("islandFirstFloat", true);
        paramV2.put("enableFloat",      false);
        paramV2.put("updatable",        false);
        paramV2.put("ticker",           ticker);
        paramV2.put("aodTitle",         ticker);
        paramV2.put("param_island",     paramIsland);
        paramV2.put("baseInfo",         baseInfo);

        JSONObject root = new JSONObject();
        root.put("param_v2", paramV2);
        return root.toString();
    }

    // ─────────────────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────────────────

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
                TEST_CHANNEL_ID, "超级岛测试", NotificationManager.IMPORTANCE_HIGH);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private String prettyJson(String json) {
        try {
            return new JSONObject(json).toString(2);
        } catch (JSONException e) {
            return json;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private EditText makeInput(String hint, String def) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(def);
        et.setBackgroundColor(Color.WHITE);
        et.setPadding(dp(8), dp(6), dp(8), dp(6));
        et.setSingleLine();
        return et;
    }

    private LinearLayout labelWrap(String label, EditText et) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.DKGRAY);
        tv.setMinWidth(dp(70));
        row.addView(tv);

        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        et.setLayoutParams(etLp);
        row.addView(et);
        return row;
    }

    private Button makeButton(String text, int color) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(color);
        btn.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btn.setLayoutParams(lp);
        return btn;
    }

    private View space(int size) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return v;
    }
}
