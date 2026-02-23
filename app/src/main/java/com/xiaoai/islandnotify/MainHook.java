package com.xiaoai.islandnotify;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

/**
 * LSPosed 主 Hook 类
 * 功能：拦截 com.miui.voiceassist 发送的"课程表提醒"通知，
 *       注入 miui.focus.param 参数，将其升级为小米超级岛通知。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "IslandNotifyHook";

    /** 目标应用包名（小爱同学） */
    private static final String TARGET_PACKAGE = "com.miui.voiceassist";

    /** 课程图标 URL（来自原始通知 payload.icon 字段） */
    private static final String COURSE_ICON_URL =
            "https://cdn.cnbj1.fds.api.mi-img.com/xiaoailite-ios/XiaoAiSuggestion/MsgSettingIconCourse.png";

    /** 课程图标在 miui.focus.pics Bundle 中的 key */
    private static final String PIC_KEY_COURSE   = "miui.focus.pic_course";
    /** 上课静音 Action 在 miui.focus.actions Bundle 中的 key */
    private static final String ACTION_KEY_MUTE  = "miui.focus.action_mute";
    /** 触发上课静音的广播 Action */
    private static final String MUTE_ACTION      = "com.xiaoai.islandnotify.ACTION_MUTE";
    /** 上课静音按钮 inline Intent URI（actionIntentType=2，系统直接发广播，无需 PendingIntent Bundle） */
    private static final String MUTE_ACTION_URI  =
            "intent:#Intent;action=com.xiaoai.islandnotify.ACTION_MUTE;package=com.xiaoai.islandnotify;end";

    /** 点击课程卡片整体 → 跳转课表页的 Intent URI */
    private static final String COURSE_TABLE_INTENT =
            "intent://aiweb?url=https%3A%2F%2Fi.ai.mi.com%2Fh5%2Fprecache%2Fai-schedule%2F%23%2FtodayLesson" +
            "&flag=805339136&noBack=false&statusBarColor=FFFFFF&statusBarTextBlack=true" +
            "&navigationBarColor=FFFFFF#Intent;scheme=voiceassist;package=com.miui.voiceassist;end";

    /** 关键词兜底识别列表 */
    private static final String[] SCHEDULE_KEYWORDS = {
            "课程", "课表", "上课", "选课", "schedule", "class reminder"
    };

    /** 岛通知主参数 Key */
    private static final String KEY_FOCUS_PARAM = "miui.focus.param";

    /** 课程图标 Bitmap 缓存（后台线程异步下载后填充） */
    private static volatile Bitmap cachedCourseBitmap = null;
    private static volatile boolean iconFetchAttempted = false;

    // ─────────────────────────────────────────────────────────────

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 只注入目标进程
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + ": 已注入目标进程 → " + TARGET_PACKAGE);
        ensureIconDownloaded(); // 提前异步下载图标，避免通知触发时还未准备好
        hookNotifyMethods(lpparam);
    }

    /**
     * Hook NotificationManager 的两个 notify 重载，在通知发出前注入岛参数。
     */
    private void hookNotifyMethods(XC_LoadPackage.LoadPackageParam lpparam) {

        // ① notify(int id, Notification notification)
        try {
            findAndHookMethod(
                    "android.app.NotificationManager",
                    lpparam.classLoader,
                    "notify",
                    int.class,
                    Notification.class,
                    new NotifyHook(1) // notification 在 args[1]
            );
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": Hook notify(int,Notification) 失败 → " + e.getMessage());
        }

        // ② notify(String tag, int id, Notification notification)
        try {
            findAndHookMethod(
                    "android.app.NotificationManager",
                    lpparam.classLoader,
                    "notify",
                    String.class,
                    int.class,
                    Notification.class,
                    new NotifyHook(2) // notification 在 args[2]
            );
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": Hook notify(String,int,Notification) 失败 → " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部 Hook 实现
    // ═══════════════════════════════════════════════════════════════

    /**
     * @param notifArgIndex Notification 对象在 args 数组中的下标
     */
    private class NotifyHook extends XC_MethodHook {

        private final int notifArgIndex;

        NotifyHook(int notifArgIndex) {
            this.notifArgIndex = notifArgIndex;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            Notification notification = (Notification) param.args[notifArgIndex];
            if (notification == null) return;

            // 防止重复处理（同一通知被两个 Hook 各触发一次时的保护）
            if (isAlreadyIsland(notification)) return;

            if (isScheduleNotification(notification)) {
                XposedBridge.log(TAG + ": 检测到课程表提醒，开始注入超级岛参数");
                injectIslandParams(notification, param.thisObject);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 通知识别逻辑
    // ─────────────────────────────────────────────────────────────

    /**
     * 判断该通知是否已经携带超级岛参数（避免重复注入）。
     */
    private boolean isAlreadyIsland(Notification notification) {
        if (notification.extras == null) return false;
        return notification.extras.containsKey(KEY_FOCUS_PARAM);
    }

    /**
     * 判断是否为课程表提醒通知。
     *
     * <p>实测 logcat 确认：
     * <ul>
     *   <li>channelId = {@code "COURSE_SCHEDULER_REMINDER_sound"}</li>
     *   <li>msgType   = {@code "COURSE_SCHEDULER_REMINDER"}</li>
     * </ul>
     * 优先通过 Channel ID 精确匹配；Channel ID 变更时自动降级为关键词匹配兜底。
     */
    private boolean isScheduleNotification(Notification notification) {
        // ① 精确匹配 Channel ID（首选，来自实测 logcat）
        String channelId = safeStr(notification.getChannelId());
        if (channelId.contains("COURSE_SCHEDULER_REMINDER")) {
            XposedBridge.log(TAG + ": 命中 channelId=" + channelId);
            return true;
        }

        // ② 关键词兜底（防止 channelId 未来变更）
        Bundle extras = notification.extras;
        if (extras == null) return false;
        CharSequence titleCs2 = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCs2  = extras.getCharSequence(Notification.EXTRA_TEXT);
        String title = titleCs2 != null ? titleCs2.toString() : "";
        String text  = textCs2  != null ? textCs2.toString()  : "";
        String combined = (title + " " + text).toLowerCase();
        for (String kw : SCHEDULE_KEYWORDS) {
            if (combined.contains(kw.toLowerCase())) {
                XposedBridge.log(TAG + ": 命中关键词 [" + kw + "] title=" + title);
                return true;
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // 超级岛参数注入
    // ─────────────────────────────────────────────────────────────

    /**
     * 向通知的 extras 中注入 miui.focus.param（及图标/Action Bundle），
     * 使其变为超级岛通知，并设置整体点击事件跳转到课表页。
     */
    private void injectIslandParams(Notification notification, Object nmInstance) {
        Bundle extras = notification.extras;
        if (extras == null) {
            extras = new Bundle();
            notification.extras = extras;
        }

        try {
            CourseInfo info = extractCourseInfo(extras);
            XposedBridge.log(TAG + ": 解析结果 → 课程=" + info.courseName
                    + " 时间=" + info.startTime + " 结束=" + info.endTime
                    + " 教室=" + info.classroom);

            // ── 1. 构建超级岛 JSON ─────────────────────────────────────
            String islandJson = buildIslandParams(info);
            extras.putString(KEY_FOCUS_PARAM, islandJson);

            // ── 2. 注入课程图标（miui.focus.pics 存 Bitmap Parcelable）────
            ensureIconDownloaded();
            if (cachedCourseBitmap != null) {
                Bundle picsBundle = new Bundle();
                picsBundle.putParcelable(PIC_KEY_COURSE, cachedCourseBitmap);
                extras.putBundle("miui.focus.pics", picsBundle);
            }

            // ── 3. 注入 Action 及点击 Intent ──────────────────────────
            Context ctx = null;
            try {
                ctx = (Context) XposedHelpers.getObjectField(nmInstance, "mContext");
            } catch (Throwable ignored) {}
            if (ctx != null) {
                // textButton 已改用 inline actionIntentType=2，无需 miui.focus.actions Bundle

                // 整体点击 → 课表页
                try {
                    Intent tableIntent = Intent.parseUri(
                            COURSE_TABLE_INTENT, Intent.URI_INTENT_SCHEME);
                    PendingIntent tablePi = PendingIntent.getActivity(
                            ctx, 1, tableIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    );
                    notification.contentIntent = tablePi;
                } catch (Exception e) {
                    XposedBridge.log(TAG + ": 课表 intent 解析失败 → " + e.getMessage());
                }
            }

            XposedBridge.log(TAG + ": 注入成功 hasPic=" + (cachedCourseBitmap != null));
        } catch (JSONException e) {
            XposedBridge.log(TAG + ": 构建 JSON 失败 → " + e.getMessage());
        }
    }

    /**
     * 从通知 extras 中精确提取课程信息。
     *
     * <p>实测 logcat 确认的固定格式（com.miui.voiceassist 课程表提醒）：
     * <pre>
     *   EXTRA_TITLE = "[高等数学]快到了，提前准备一下吧"
     *   EXTRA_TEXT  = "19:50 | 教1-201"   ← 固定格式：startTime + " | " + 教室
     *   EXTRA_SUB_TEXT / 自定义字段 可能含 endTime（如 "20:35"）
     * </pre>
     */
    private CourseInfo extractCourseInfo(Bundle extras) {
        // 通知 title/text 存的是 CharSequence（Spanned），getString() 对此类型返回 null
        CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence bodyCs  = extras.getCharSequence(Notification.EXTRA_TEXT);
        String title = titleCs != null ? titleCs.toString() : "";
        String body  = bodyCs  != null ? bodyCs.toString()  : "";

        // ── 课程名：提取 "[高等数学]" 中的内容 ───────────────────────
        String courseName = "";
        Matcher m = Pattern.compile("\\[([^\\]]+)\\]").matcher(title);
        if (m.find()) {
            courseName = m.group(1).trim();
        }
        if (courseName.isEmpty()) {
            courseName = title.replaceAll("(快到了|提醒|通知|课程表).*", "").trim();
        }
        if (courseName.isEmpty()) courseName = "课程提醒";

        // ── 时间 + 教室：body 固定格式 "19:50 | 教1-201" ─────────────
        String startTime = "";
        String classroom = "";
        String[] parts = body.split("\\s*\\|\\s*", 2);
        if (parts.length >= 1) startTime = parts[0].trim();
        if (parts.length >= 2) classroom  = parts[1].trim();

        // ── 结束时间：从 EXTRA_SUB_TEXT 或其他自定义 extra 中尝试读取 ──
        // endDateTime 字段只存在于原始 payload JSON 中，
        // 这里扫描所有字符串型 extra，找符合 HH:mm 格式的第二个时间值
        String endTime = "";
        for (String key : extras.keySet()) {
            if (key.equals(Notification.EXTRA_TITLE) || key.equals(Notification.EXTRA_TEXT)) continue;
            Object val = extras.get(key);
            if (val instanceof String || val instanceof CharSequence) {
                String sv = val.toString().trim();
                if (sv.matches("\\d{1,2}:\\d{2}") && !sv.equals(startTime)) {
                    endTime = sv;
                    XposedBridge.log(TAG + ": 从 extra[" + key + "] 找到结束时间=" + endTime);
                    break;
                }
            }
        }

        XposedBridge.log(TAG + ": 解析 title=[" + title + "] body=[" + body + "]"
                + " → 课程=" + courseName + " 开始=" + startTime
                + " 结束=" + endTime + " 教室=" + classroom);
        return new CourseInfo(courseName, startTime, endTime, classroom);
    }

    private static String safeStr(String s) {
        return s != null ? s : "";
    }

    /**
     * 构建超级岛 JSON 参数（param_v2 格式）。
     *
     * <p>大岛展开态结构：
     * <pre>
     * ┌──────────────────────────────────────────────┐
     * │ [课程图标]  高等数学          教1-201（B区）  │
     * │             19:50-20:35                       │
     * │                         [上课静音]            │
     * └──────────────────────────────────────────────┘
     * </pre>
     *
     * <ul>
     *   <li>A区 imageTextInfoLeft：picInfo(图标) + textInfo(课程名/时间)</li>
     *   <li>B区 textInfo：教室名</li>
     *   <li>textButton：上课静音按钮，action → miui.focus.actions Bundle</li>
     *   <li>notification.contentIntent：整体点击 → 课表页</li>
     * </ul>
     */
    private String buildIslandParams(CourseInfo info) throws JSONException {
        // 时间显示：有结束时间 → "19:50-20:35"，否则仅 "19:50"
        String timeDisplay = info.startTime;
        if (!info.endTime.isEmpty()) {
            timeDisplay = info.startTime + "-" + info.endTime;
        }

        // ── A 区：imageTextInfoLeft（type=1）──────────────────────────
        // picInfo.pic 引用 miui.focus.pics Bundle 中对应 key 的 Icon 对象
        JSONObject picInfo = new JSONObject();
        picInfo.put("type", 1);
        picInfo.put("pic", PIC_KEY_COURSE);

        JSONObject aTextInfo = new JSONObject();
        aTextInfo.put("title", info.courseName);
        if (!timeDisplay.isEmpty()) aTextInfo.put("content", timeDisplay);

        JSONObject imageTextInfoLeft = new JSONObject();
        imageTextInfoLeft.put("type", 1);
        imageTextInfoLeft.put("picInfo", picInfo);
        imageTextInfoLeft.put("textInfo", aTextInfo);

        // ── B 区：textInfo（教室，直接显示值，不加标签前缀）──────────
        JSONObject bTextInfo = new JSONObject();
        bTextInfo.put("title", info.classroom.isEmpty() ? "—" : info.classroom);

        // ── 按钮：上课静音（Method 2 inline，actionIntentType=2 → 广播）────
        JSONObject muteBtn = new JSONObject();
        muteBtn.put("actionTitle",    "上课静音");
        muteBtn.put("actionIntentType", 2);          // 2 = broadcast
        muteBtn.put("actionIntent",   MUTE_ACTION_URI);
        org.json.JSONArray textButton = new org.json.JSONArray();
        textButton.put(muteBtn);

        JSONObject bigIslandArea = new JSONObject();
        bigIslandArea.put("imageTextInfoLeft", imageTextInfoLeft);
        bigIslandArea.put("textInfo",          bTextInfo);
        bigIslandArea.put("textButton",        textButton);

        // ── 小岛摘要态：显式指定图标（防止兜底到 App 图标）────────────
        JSONObject smallPicInfo = new JSONObject();
        smallPicInfo.put("type", 1);
        smallPicInfo.put("pic",  PIC_KEY_COURSE);
        JSONObject smallIslandArea = new JSONObject();
        smallIslandArea.put("picInfo", smallPicInfo);

        JSONObject paramIsland = new JSONObject();
        paramIsland.put("islandProperty", 1);
        paramIsland.put("bigIslandArea", bigIslandArea);
        paramIsland.put("smallIslandArea", smallIslandArea);

        // ── 焦点通知（baseInfo）─────────────────────────────────────
        String bodyContent = timeDisplay
                + (info.classroom.isEmpty() ? "" : " | " + info.classroom);
        JSONObject baseInfo = new JSONObject();
        baseInfo.put("title",   info.courseName);
        baseInfo.put("content", bodyContent.isEmpty() ? info.courseName : bodyContent);
        baseInfo.put("type", 1);

        // ── 状态栏 / 息屏文案 ────────────────────────────────────────
        String tickerText = buildTickerText(info);

        // ── 组合 param_v2 ─────────────────────────────────────────
        JSONObject paramV2 = new JSONObject();
        paramV2.put("protocol",        1);
        paramV2.put("business",        "course_schedule");
        paramV2.put("islandFirstFloat", true);
        paramV2.put("enableFloat",      false);
        paramV2.put("updatable",        false);
        paramV2.put("ticker",           tickerText);
        paramV2.put("aodTitle",         tickerText);
        paramV2.put("param_island",     paramIsland);
        paramV2.put("baseInfo",         baseInfo);

        JSONObject root = new JSONObject();
        root.put("param_v2", paramV2);
        return root.toString();
    }

    /** 状态栏 / 息屏文案：格式 "高等数学  19:50" */
    private String buildTickerText(CourseInfo info) {
        if (info.startTime.isEmpty()) return info.courseName;
        return info.courseName + "  " + info.startTime;
    }

    /**
     * 异步下载课程图标并缓存到 {@link #cachedCourseBitmap}。
     * 若已尝试过（无论成功与否）则直接返回，避免重复网络请求。
     * 下载在后台线程进行，不阻塞通知注入。
     */
    private static void ensureIconDownloaded() {
        if (iconFetchAttempted) return;
        iconFetchAttempted = true;

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(COURSE_ICON_URL).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.connect();
                if (conn.getResponseCode() == 200) {
                    Bitmap bmp = BitmapFactory.decodeStream(conn.getInputStream());
                    if (bmp != null) {
                        cachedCourseBitmap = bmp;
                        XposedBridge.log(TAG + ": 课程图标下载成功 "
                                + bmp.getWidth() + "x" + bmp.getHeight());
                    }
                } else {
                    XposedBridge.log(TAG + ": 课程图标下载失败 HTTP " + conn.getResponseCode());
                }
            } catch (Exception e) {
                XposedBridge.log(TAG + ": 课程图标下载异常 → " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "IslandIconFetch").start();
    }

    // ─────────────────────────────────────────────────────────────
    // 数据结构
    // ─────────────────────────────────────────────────────────────

    private static class CourseInfo {
        final String courseName;
        final String startTime;
        final String endTime;
        final String classroom;

        CourseInfo(String courseName, String startTime, String endTime, String classroom) {
            this.courseName = courseName;
            this.startTime  = startTime;
            this.endTime    = endTime;
            this.classroom  = classroom;
        }
    }
}
