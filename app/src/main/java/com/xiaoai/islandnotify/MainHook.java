package com.xiaoai.islandnotify;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.widget.RemoteViews;

import org.json.JSONException;
import org.json.JSONObject;

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

    /** 触发上课静音的广播 Action */
    private static final String MUTE_ACTION   = "com.xiaoai.islandnotify.ACTION_MUTE";
    /** 触发解除静音的广播 Action */
    private static final String UNMUTE_ACTION = "com.xiaoai.islandnotify.ACTION_UNMUTE";
    /** shareData 拖拽分享图片在 miui.focus.pics Bundle 中的 key */
    private static final String PIC_KEY_SHARE = "miui.focus.pic_share";

    /** 点击课程卡片整体 → 跳转课表页的 Intent URI */
    private static final String COURSE_TABLE_INTENT =
            "intent://aiweb?url=https%3A%2F%2Fi.ai.mi.com%2Fh5%2Fprecache%2Fai-schedule%2F%23%2FtodayLesson" +
            "&flag=805339136&noBack=false&statusBarColor=FFFFFF&statusBarTextBlack=true" +
            "&navigationBarColor=FFFFFF#Intent;scheme=voiceassist;package=com.miui.voiceassist;end";

    /** 岛通知主参数 Key */
    private static final String KEY_FOCUS_PARAM = "miui.focus.param";

    /** SharedPreferences 名称（与 MainActivity 保持一致） */
    private static final String PREFS_NAME = "island_custom";
    /** 模块自身包名，用于跨进程读取 SharedPreferences */
    private static final String MODULE_PKG  = "com.xiaoai.islandnotify";

    /** 同步偏好设置到目标进程的广播 Action */
    private static final String ACTION_SYNC_PREFS = "com.xiaoai.islandnotify.ACTION_SYNC_PREFS";
    /** AlarmManager 闹钟触发岛状态更新的广播 Action */
    private static final String ACTION_ISLAND_UPDATE = "com.xiaoai.islandnotify.ACTION_ISLAND_UPDATE";
    /** 触发目标应用发送测试通知的广播 Action */
    private static final String ACTION_TEST_NOTIFY = "com.xiaoai.islandnotify.ACTION_TEST_NOTIFY";
    /** 跨 ClassLoader 的防重复注入标记 key（存于 boot classloader 的 System.properties） */
    private static final String HOOKED_KEY = "xiaoai.island.hooked";

    // ─────────────────────────────────────────────────────────────

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 注入自身进程 → hook isModuleActive()；同时 hook notify，支持测试通知
        if ("com.xiaoai.islandnotify".equals(lpparam.packageName)) {
            hookSelfStatus(lpparam);
            hookNotifyMethods(lpparam);
            return;
        }
        // 只注入目标进程
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        // 同一进程可能因多 ClassLoader 被调用多次，只注册一次
        // 用 System.setProperty 而非 static 字段，确保跨 ClassLoader 生效
        if (System.getProperty(HOOKED_KEY) != null) return;
        System.setProperty(HOOKED_KEY, "1");
        XposedBridge.log(TAG + ": 已注入目标进程 → " + TARGET_PACKAGE);
        hookApplicationOnCreate(lpparam);
        hookNotifyMethods(lpparam);
    }

    /**     * Hook 目标 App 的 Application.onCreate，在其进程内注册 BroadcastReceiver。
     * 收到 ACTION_SYNC_PREFS 广播时，将偷好设置写入目标进程自己的 SharedPreferences，
     * 彻底绕过 SELinux 跨 UID 文件读取限制。
     */
    private void hookApplicationOnCreate(XC_LoadPackage.LoadPackageParam lpparam) {
        findAndHookMethod("android.app.Application", lpparam.classLoader,
                "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context appCtx = (Context) param.thisObject;
                IntentFilter filter = new IntentFilter(ACTION_SYNC_PREFS);
                filter.addAction(ACTION_ISLAND_UPDATE);
                filter.addAction(ACTION_TEST_NOTIFY);
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (ACTION_SYNC_PREFS.equals(intent.getAction())) {
                            SharedPreferences.Editor ed = context
                                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                            for (String sfx : new String[]{"_pre", "_active", "_post"}) {
                                String kA = "tpl_a" + sfx;
                                if (intent.hasExtra(kA)) ed.putString(kA, intent.getStringExtra(kA));
                                String kB = "tpl_b" + sfx;
                                if (intent.hasExtra(kB)) ed.putString(kB, intent.getStringExtra(kB));
                                String kT = "tpl_ticker" + sfx;
                                if (intent.hasExtra(kT)) ed.putString(kT, intent.getStringExtra(kT));
                            }
                            if (intent.hasExtra("icon_a")) {
                                ed.putBoolean("icon_a", intent.getBooleanExtra("icon_a", true));
                            }
                            // 同步超时设置：岛消失改为三阶段独立；通知消失按阶段
                            for (String phase : new String[]{"pre", "active", "post"}) {
                                String kIsVal  = "to_island_val_"  + phase;
                                String kIsUnit = "to_island_unit_" + phase;
                                String kNoVal  = "to_notif_val_"   + phase;
                                String kNoUnit = "to_notif_unit_"  + phase;
                                if (intent.hasExtra(kIsVal))  ed.putInt   (kIsVal,  intent.getIntExtra   (kIsVal, -1));
                                if (intent.hasExtra(kIsUnit)) ed.putString(kIsUnit, safeStr(intent.getStringExtra(kIsUnit)));
                                if (intent.hasExtra(kNoVal))  ed.putInt   (kNoVal,  intent.getIntExtra   (kNoVal, -1));
                                if (intent.hasExtra(kNoUnit)) ed.putString(kNoUnit, safeStr(intent.getStringExtra(kNoUnit)));
                            }
                            ed.apply();
                            // 记录接收到的 extras 与写入的键值，便于排查模块进程与目标进程不一致问题
                            try {
                                StringBuilder sb = new StringBuilder();
                                sb.append("SYNC_PREFS extras:\n");
                                for (String key : intent.getExtras().keySet()) {
                                    Object v = intent.getExtras().get(key);
                                    sb.append(key).append("=").append(String.valueOf(v)).append("\n");
                                }
                                sb.append("Saved prefs:\n");
                                SharedPreferences saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                                for (String k : saved.getAll().keySet()) {
                                    Object vv = saved.getAll().get(k);
                                    sb.append(k).append("=").append(String.valueOf(vv)).append("\n");
                                }
                                XposedBridge.log(TAG + ": " + sb.toString());
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": SYNC_PREFS log failure -> " + t.getMessage());
                            }
                            XposedBridge.log(TAG + ": 偏好设置已同步到目标进程");
                        } else if (ACTION_ISLAND_UPDATE.equals(intent.getAction())) {
                            String courseName = safeStr(intent.getStringExtra("course_name"));
                            String startTime  = safeStr(intent.getStringExtra("start_time"));
                            String endTime    = safeStr(intent.getStringExtra("end_time"));
                            String classroom  = safeStr(intent.getStringExtra("classroom"));
                            CourseInfo info   = new CourseInfo(courseName, startTime, endTime, classroom);
                            int state         = intent.getIntExtra("state", STATE_ELAPSED);
                            String channelId  = safeStr(intent.getStringExtra("channel_id"));
                            String tag        = intent.getStringExtra("notif_tag");
                            int id            = intent.getIntExtra("notif_id", 0);
                            android.app.NotificationManager nm =
                                    context.getSystemService(android.app.NotificationManager.class);
                            // 找到当前活跃通知以复用其图标和 intent
                            Notification src = null;
                            for (StatusBarNotification sbn : nm.getActiveNotifications()) {
                                if (sbn.getId() == id) {
                                    src = sbn.getNotification();
                                    break;
                                }
                            }
                            if (src == null) {
                                XposedBridge.log(TAG + ": 闹钟回调时通知已消失，跳过 state=" + state);
                                return;
                            }
                            SharedPreferences prefs = context
                                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                            sendIslandUpdate(info, state, context, channelId, src, nm, tag, id, prefs);
                        } else if (ACTION_TEST_NOTIFY.equals(intent.getAction())) {
                            // 由模块 APP 触发，在目标进程内构造并发送测试通知
                            String tCourseName = intent.getStringExtra("course_name");
                            String tStartTime  = intent.getStringExtra("start_time");
                            String tEndTime    = intent.getStringExtra("end_time");
                            String tClassroom  = intent.getStringExtra("classroom");
                            if (tCourseName == null || tCourseName.isEmpty()) tCourseName = "高等数学";
                            if (tStartTime  == null || tStartTime.isEmpty())  tStartTime  = "00:00";
                            if (tEndTime    == null || tEndTime.isEmpty())    tEndTime    = "00:00";
                            if (tClassroom  == null || tClassroom.isEmpty())  tClassroom  = "教科A-101";

                            final String TEST_CHANNEL_ID = "COURSE_SCHEDULER_REMINDER_sound";
                            android.app.NotificationManager tnm =
                                    context.getSystemService(android.app.NotificationManager.class);
                            if (tnm == null) return;
                            if (tnm.getNotificationChannel(TEST_CHANNEL_ID) == null) {
                                android.app.NotificationChannel tch = new android.app.NotificationChannel(
                                        TEST_CHANNEL_ID, "课程提醒", android.app.NotificationManager.IMPORTANCE_HIGH);
                                tnm.createNotificationChannel(tch);
                            }

                            android.app.Notification tNotif = new android.app.Notification.Builder(context, TEST_CHANNEL_ID)
                                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                                    .build();
                            if (tNotif.extras == null) tNotif.extras = new android.os.Bundle();
                            tNotif.extras.putString("xiaoai.test.course_name", tCourseName);
                            tNotif.extras.putString("xiaoai.test.start_time",  tStartTime);
                            tNotif.extras.putString("xiaoai.test.end_time",    tEndTime);
                            tNotif.extras.putString("xiaoai.test.classroom",   tClassroom);

                            tnm.notify(1001, tNotif);
                            XposedBridge.log(TAG + ": 已在目标进程发出测试通知");
                        }
                    }
                };
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    appCtx.registerReceiver(receiver, filter);
                }
                XposedBridge.log(TAG + ": 偷好同步接收器已注册");
            }
        });
    }

    /**     * Hook 自身进程的 MainActivity.isModuleActive()，将返回值替换为 true，
     * 使主界面能正确检测到模块已激活。
     */
    private void hookSelfStatus(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            findAndHookMethod(
                    "com.xiaoai.islandnotify.MainActivity",
                    lpparam.classLoader,
                    "isModuleActive",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookSelfStatus 失败: " + t.getMessage());
        }
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

            // 防止重复处理（已注入岛参数的通知直接放行）
            if (isAlreadyIsland(notification)) return;

            if (isScheduleNotification(notification)) {
                XposedBridge.log(TAG + ": 检测到课程表提醒，开始注入超级岛参数");
                int notifId;
                String notifTag;
                if (notifArgIndex == 1) {
                    notifId  = (int) param.args[0];
                    notifTag = null;
                } else {
                    notifTag = (String) param.args[0];
                    notifId  = (int)   param.args[1];
                }
                injectIslandParams(notification, param.thisObject, notifId, notifTag);
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
     * 实测 logcat 确认 channelId = {@code "COURSE_SCHEDULER_REMINDER_sound"}。
     */
    private boolean isScheduleNotification(Notification notification) {
        String channelId = safeStr(notification.getChannelId());
        boolean hit = channelId.contains("COURSE_SCHEDULER_REMINDER");
        if (hit) XposedBridge.log(TAG + ": 命中 channelId=" + channelId);
        return hit;
    }

    // ─────────────────────────────────────────────────────────────
    // 超级岛参数注入
    // ─────────────────────────────────────────────────────────────

    /**
     * 向通知的 extras 中注入 miui.focus.param（及图标/Action Bundle），
     * 使其变为超级岛通知，并设置整体点击事件跳转到课表页。
     */
    private void injectIslandParams(Notification notification, Object nmInstance,
                                     int notifId, String notifTag) {
        Bundle extras = notification.extras;
        if (extras == null) {
            extras = new Bundle();
            notification.extras = extras;
        }

        try {
            CourseInfo info = extractCourseInfo(notification);
            XposedBridge.log(TAG + ": 解析结果 → 课程=" + info.courseName
                    + " 时间=" + info.startTime + " 结束=" + info.endTime
                    + " 教室=" + info.classroom);

            // ── 1. 计算开始时间戳 ─────────────────────────────────────
            long startMs = computeClassStartMs(info.startTime);

            // ── 2. 获取 ctx ──────────────────────────────────────────
            Context ctx = null;
            try {
                ctx = (Context) XposedHelpers.getObjectField(nmInstance, "mContext");
            } catch (Throwable ignored) {}
            XposedBridge.log(TAG + ": ctx=" + (ctx != null ? "ok" : "null"));

            // ── 3. 读取用户偏好设置 ───────────────────────────────────
            final android.content.SharedPreferences prefs =
                    (ctx != null) ? loadPrefs(ctx) : null;

            // ── 4. 构建超级岛 JSON ────────────────────────────────────
            String islandJson = buildIslandJson(info, STATE_COUNTDOWN, prefs);
            extras.putString(KEY_FOCUS_PARAM, islandJson);
            XposedBridge.log(TAG + ": JSON 长度=" + islandJson.length()
                    + " startMs=" + startMs);

            if (ctx != null) {
                try {
                    Intent tableIntent = Intent.parseUri(
                            COURSE_TABLE_INTENT, Intent.URI_INTENT_SCHEME);
                    PendingIntent tablePi = PendingIntent.getActivity(
                            ctx, 1, tableIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    notification.contentIntent = tablePi;
                } catch (Exception e) {
                    XposedBridge.log(TAG + ": 课表 intent 解析失败 → " + e.getMessage());
                }

                // ── 6. 用 AlarmManager 闹钟调度岛状态更新（可唤醒 Doze）────────
                // 替换原 Handler.postDelayed，避免进程被杀后延迟/丢失
                final String channelId = safeStr(notification.getChannelId());

                // 6a. 开课时刻 → 正计时（STATE_ELAPSED）
                long delay = startMs - System.currentTimeMillis();
                if (delay > 0 && delay <= 6 * 3600 * 1000L) {
                    scheduleIslandAlarm(ctx, info, STATE_ELAPSED, channelId,
                            notifTag, notifId, startMs);
                }

                // 6b. 下课时刻 → 下课状态（STATE_FINISHED）独立调度
                long endMs2 = computeClassStartMs(info.endTime);
                long delayEnd = endMs2 - System.currentTimeMillis();
                if (!info.endTime.isEmpty() && endMs2 > 0 && delayEnd > 0 && delayEnd <= 6 * 3600 * 1000L) {
                    scheduleIslandAlarm(ctx, info, STATE_FINISHED, channelId,
                            notifTag, notifId, endMs2);
                }

                // 6c. 通知取消闹钟（三个阶段各自独立调度，先触发者生效）
                // 使用 Android 原生 nm.cancel，不依赖 JSON timeout 字段
                scheduleNotifCancelAlarms(ctx, prefs, notifTag, notifId,
                        System.currentTimeMillis(), startMs, endMs2);
            }

            XposedBridge.log(TAG + ": 注入成功");
        } catch (JSONException e) {
            XposedBridge.log(TAG + ": 构建 JSON 失败 → " + e.getMessage());
        }
    }

    /**
     * 从 Notification 的 RemoteViews 中直接提取课程信息。
     *
     * 实测（02-24 11:30 logcat）：voiceassist 使用完全自定义 View，
     * extras 中 android.title / android.text 均为 null。
     * 数据存在 bigContentView 的 ReflectionAction.mValue 字段序列中：
     *   [0] "[课程]快到了，提前准备一下吧"  ← 课程名在 [] 内
     *   [1] "11:45"                         ← 开始时间（HH:mm）
     *   [2] "12:30"                         ← 结束时间（HH:mm）
     *   [3] "课程"                           ← 课程名（纯文字，无括号）
     *   [4] "教室"                           ← 教室
     * contentView 中有 "11:45 | 教室" 格式，可作补充来源。
     */
    private CourseInfo extractCourseInfo(Notification notification) {
        // 优先读取测试广播注入的课程信息（来自目标进程测试通知）
        if (notification.extras != null
                && notification.extras.containsKey("xiaoai.test.course_name")) {
            String name  = safeStr(notification.extras.getString("xiaoai.test.course_name"));
            String start = safeStr(notification.extras.getString("xiaoai.test.start_time"));
            String end   = safeStr(notification.extras.getString("xiaoai.test.end_time"));
            String room  = safeStr(notification.extras.getString("xiaoai.test.classroom"));
            XposedBridge.log(TAG + ": [测试通知] 直接读取 extras → 课程=" + name
                    + " 开始=" + start + " 结束=" + end + " 教室=" + room);
            return new CourseInfo(name, start, end, room);
        }
        CourseInfo fromView = extractFromRemoteViews(notification);
        if (fromView != null) {
            XposedBridge.log(TAG + ": [RemoteViews] 精确提取 → 课程=" + fromView.courseName
                    + " 开始=" + fromView.startTime + " 结束=" + fromView.endTime
                    + " 教室=" + fromView.classroom);
            return fromView;
        }

        XposedBridge.log(TAG + ": [兜底] RemoteViews 解析失败，返回空课程信息");
        return new CourseInfo("课程提醒", "", "", "");
    }

    /**
     * 用反射从 RemoteViews.mActions 中收集所有 CharSequence 值，
     * 然后按规则匹配课程名、开始/结束时间、教室。
     *
     * 匹配规则（基于实测 bigContentView 顺序）：
     *   - 含 "[...]" 的字符串 → 提取括号内作课程名
     *   - 纯 "HH:mm" 格式 → 按出现顺序：第1个=开始时间，第2个=结束时间
     *   - "HH:mm | 教室" 格式 → 拆分开始时间和教室
     *   - 2~20字且非时间非按钮文字 → 优先作教室，其次作课程名
     */
    private CourseInfo extractFromRemoteViews(Notification notif) {
        if (notif == null) return null;
        RemoteViews big     = notif.bigContentView;
        RemoteViews content = notif.contentView;
        if (big == null && content == null) return null;

        java.util.List<String> texts = new java.util.ArrayList<>();
        for (RemoteViews rv : new RemoteViews[]{big, content}) {
            if (rv == null) continue;
            try {
                java.lang.reflect.Field fActions = RemoteViews.class.getDeclaredField("mActions");
                fActions.setAccessible(true);
                java.util.ArrayList<?> actions = (java.util.ArrayList<?>) fActions.get(rv);
                if (actions == null) continue;
                for (Object act : actions) {
                    if (!act.getClass().getSimpleName().equals("ReflectionAction")) continue;
                    try {
                        java.lang.reflect.Field fValue = act.getClass().getDeclaredField("mValue");
                        fValue.setAccessible(true);
                        Object val = fValue.get(act);
                        if (!(val instanceof CharSequence)) continue;
                        String sv = val.toString().trim();
                        if (!sv.isEmpty()) texts.add(sv);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            if (!texts.isEmpty()) break;
        }
        if (texts.isEmpty()) return null;

        String courseName = "", startTime = "", endTime = "", classroom = "";
        java.util.regex.Pattern timePattern = java.util.regex.Pattern.compile("^\\d{1,2}:\\d{2}$");
        java.util.regex.Pattern subPattern  = java.util.regex.Pattern.compile("(\\d{1,2}:\\d{2})\\s*[|｜]\\s*(.+)");
        // 跳过按钮文字
        java.util.Set<String> buttonTexts = new java.util.HashSet<>(
                java.util.Arrays.asList("上课静音", "完整课表", "静音", "课表"));

        for (String t : texts) {
            if (buttonTexts.contains(t)) continue;

            // "[课程]快到了..." → 课程名
            java.util.regex.Matcher mb = java.util.regex.Pattern.compile("\\[([^\\]]+)\\]").matcher(t);
            if (mb.find() && courseName.isEmpty()) {
                courseName = mb.group(1).trim();
                continue;
            }
            // "11:45 | 教室" → 开始时间 + 教室
            java.util.regex.Matcher ms = subPattern.matcher(t);
            if (ms.find()) {
                if (startTime.isEmpty()) startTime = ms.group(1).trim();
                if (classroom.isEmpty())  classroom  = ms.group(2).trim();
                continue;
            }
            // 纯时间 "HH:mm"
            if (timePattern.matcher(t).matches()) {
                if (startTime.isEmpty())     startTime = t;
                else if (endTime.isEmpty())  endTime   = t;
                continue;
            }
            // 短文本（2-20字）
            if (t.length() >= 2 && t.length() <= 20) {
                if (t.equals(courseName)) continue;           // 课程名重复出现，跳过
                if (classroom.isEmpty())  classroom  = t;    // 第一个未知短文本就是教室
                else if (courseName.isEmpty()) courseName = t;
            }
        }

        if (courseName.isEmpty()) courseName = "课程提醒";

        if (startTime.isEmpty()) return null; // 连时间都没有，本方法无效
        return new CourseInfo(courseName, startTime, endTime, classroom);
    }

    private static String safeStr(String s) {
        return s != null ? s : "";
    }

    // 岛状态常量
    private static final int STATE_COUNTDOWN = 0; // 倒计时（上课前）
    private static final int STATE_ELAPSED   = 1; // 正计时（上课中）
    private static final int STATE_FINISHED  = 2; // 正计时（已下课）

    /**
     * 利用 AlarmManager.setExactAndAllowWhileIdle 在指定时刻发送岛状态更新广播。
     * 运行在 voiceassist 进程内，借用其 SCHEDULE_EXACT_ALARM 权限，精确唤醒 Doze。
     */
    private void scheduleIslandAlarm(Context ctx, CourseInfo info, int state,
            String channelId, String tag, int id, long triggerMs) {
        long delayMs = triggerMs - System.currentTimeMillis();
        if (delayMs <= 0) return;

        if (TARGET_PACKAGE.equals(ctx.getPackageName())) {
            // voiceassist 进程：用精确闹钟，可唤醒 Doze
            try {
                Intent intent = new Intent(ACTION_ISLAND_UPDATE);
                intent.setPackage(TARGET_PACKAGE);
                intent.putExtra("course_name", info.courseName);
                intent.putExtra("start_time",  info.startTime);
                intent.putExtra("end_time",    info.endTime);
                intent.putExtra("classroom",   info.classroom);
                intent.putExtra("state",       state);
                intent.putExtra("channel_id",  channelId);
                intent.putExtra("notif_tag",   tag);
                intent.putExtra("notif_id",    id);
                int reqCode = id * 10 + state;
                PendingIntent pi = PendingIntent.getBroadcast(ctx, reqCode, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                AlarmManager am = ctx.getSystemService(AlarmManager.class);
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi);
                XposedBridge.log(TAG + ": AlarmManager 已设定 state=" + state
                        + " in " + (delayMs / 1000) + "s");
            } catch (Exception e) {
                XposedBridge.log(TAG + ": scheduleIslandAlarm 失败 → " + e.getMessage());
            }
        } else {
            // 模块自身进程（测试通知）：前台运行，Handler 足够
            final CourseInfo fi = info;
            final Context fc = ctx;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                android.app.NotificationManager nm =
                        fc.getSystemService(android.app.NotificationManager.class);
                android.service.notification.StatusBarNotification src = null;
                for (android.service.notification.StatusBarNotification sbn
                        : nm.getActiveNotifications()) {
                    if (sbn.getId() == id) { src = sbn.getNotification() != null ? sbn : null; break; }
                }
                if (src == null) return;
                SharedPreferences prefs =
                        fc.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                sendIslandUpdate(fi, state, fc, channelId, src.getNotification(), nm, tag, id, prefs);
            }, delayMs);
            XposedBridge.log(TAG + ": Handler 已设定 state=" + state + " in " + (delayMs / 1000) + "s");
        }
    }

    /**
     * 在三个时间点各调度一个 ACTION_NOTIF_CANCEL 闹钟，先触发者取消通知并自动使其余成为 no-op。
     *
     * 时间点：
     *   pre    → notifPostedMs + delay_pre
     *   active → startMs       + delay_active
     *   post   → endMs         + delay_post
     *
     * 任何阶段若 val=-1（默认）则跳过调度。
     */
    /**
     * 在 voiceassist 主线程通过 Handler.postDelayed 延迟取消通知。
     * 比 AlarmManager+Broadcast 更简单可靠：voiceassist 是常驻进程，
     * 且若被杀通知也会自动消失，无需持久化 alarm。
     */
    private void scheduleNotifCancelAlarms(Context ctx,
            android.content.SharedPreferences prefs,
            String tag, int id,
            long notifPostedMs, long startMs, long endMs) {
        if (ctx == null) return;
        // 不限制进程：模块进程测试通知 / voiceassist 真实通知均适用
        final String[] phases = {"pre", "active", "post"};
        final long[]   baseMs = {notifPostedMs, startMs, endMs};
        android.app.NotificationManager nm =
                ctx.getSystemService(android.app.NotificationManager.class);
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        for (int i = 0; i < 3; i++) {
            int    val  = (prefs != null) ? prefs.getInt   ("to_notif_val_"  + phases[i], -1) : -1;
            String unit = (prefs != null) ? safeStr(prefs.getString("to_notif_unit_" + phases[i], "m")) : "m";
            if (val <= 0 || baseMs[i] <= 0) continue;
            long delayMs  = "s".equals(unit) ? (long) val * 1000 : (long) val * 60 * 1000;
            long remainMs = baseMs[i] + delayMs - System.currentTimeMillis();
            if (remainMs <= 0) continue;
            final String finalTag   = tag;
            final int    finalId    = id;
            final String phaseName  = phases[i];
            handler.postDelayed(() -> {
                if (finalTag != null) nm.cancel(finalTag, finalId);
                else                 nm.cancel(finalId);
                XposedBridge.log(TAG + ": 通知已取消 [" + phaseName + "] id=" + finalId);
            }, remainMs);
            XposedBridge.log(TAG + ": 通知取消 Handler [" + phases[i] + "] in " + remainMs / 1000 + "s");
        }
    }

    /**
     * 构建并发送更新后的岛通知。
     */
    private void sendIslandUpdate(CourseInfo info, int state,
            Context ctx, String channelId, Notification src,
            android.app.NotificationManager nm, String tag, int id,
            android.content.SharedPreferences prefs) {
        try {
            String json = buildIslandJson(info, state, prefs);
            Notification n = new Notification.Builder(ctx, channelId)
                    .setSmallIcon(src.getSmallIcon())
                    .setContentTitle(info.courseName)
                    .setContentText(info.startTime
                            + (info.endTime.isEmpty() ? "" : " | " + info.endTime)
                            + (info.classroom.isEmpty() ? "" : " " + info.classroom))
                    .setAutoCancel(true)
                    .build();
            n.extras.putString(KEY_FOCUS_PARAM, json);
            n.contentIntent = src.contentIntent;
            if (tag != null) nm.notify(tag, id, n);
            else             nm.notify(id, n);
            XposedBridge.log(TAG + ": 岛状态更新已发送 state=" + state + " id=" + id);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": 岛状态更新失败 state=" + state + " → " + e.getMessage());
        }
    }

    /**
     * 统一构建超级岛 JSON。三种状态的差异通过 state 参数区分：
     *   STATE_COUNTDOWN：上课前倒计时，上课静音按钮
     *   STATE_ELAPSED  ：上课中正计时，上课静音按钮
     *   STATE_FINISHED ：下课后正计时，解除静音按钮
     */
    private String buildIslandJson(CourseInfo info, int state,
            android.content.SharedPreferences prefs) throws JSONException {
        long startMs = computeClassStartMs(info.startTime);
        long endMs   = computeClassStartMs(info.endTime);
        long now     = System.currentTimeMillis();
        boolean isFinished = state == STATE_FINISHED;
        boolean isActive   = state != STATE_COUNTDOWN; // STATE_ELAPSED 或 STATE_FINISHED

        // ── baseInfo ──────────────────────────────────────────────
        JSONObject baseInfo = new JSONObject();
        baseInfo.put("type",  2);
        baseInfo.put("title", info.courseName);
        if (isActive) baseInfo.put("showDivider", true);
        if (!info.startTime.isEmpty()) baseInfo.put("content",    info.startTime);
        if (!info.endTime.isEmpty())   baseInfo.put("subContent", "| " + info.endTime);

        // ── picInfo（展开态识别图形组件，始终存在）────────────────────────
        JSONObject notifPicInfo = new JSONObject();
        notifPicInfo.put("type", 1); // 1 = appIcon

        // ── actionInfo ────────────────────────────────────────────
        String actionAction = isFinished ? UNMUTE_ACTION : MUTE_ACTION;
        String actionTitle  = isFinished ? "解除静音" : "上课静音";
        JSONObject actionInfo = new JSONObject();
        actionInfo.put("actionIntentType", 2);
        actionInfo.put("actionIntent",
                "intent:#Intent;action=" + actionAction
                + ";component=com.xiaoai.islandnotify/.MuteReceiver"
                + ";launchFlags=0x10000000;end");
        actionInfo.put("actionTitle", actionTitle);

        // ── hintInfo ──────────────────────────────────────────────
        long   timerMs;
        int    timerType;
        String hintContent;
        if (isFinished) {
            timerMs     = endMs;
            timerType   = 1;
            hintContent = "已经下课";
        } else if (isActive) {
            timerMs     = startMs;
            timerType   = 1;
            hintContent = "已经上课";
        } else {
            // STATE_COUNTDOWN：倒计时或已过时
            timerMs     = startMs;
            timerType   = (startMs > now) ? -1 : 1;
            hintContent = (startMs > now) ? "即将上课" : "已经上课";
        }
        JSONObject hintInfo = new JSONObject();
        hintInfo.put("type",       2);
        hintInfo.put("content",    hintContent);
        hintInfo.put("subContent", "地点");
        hintInfo.put("subTitle",   info.classroom.isEmpty() ? "—" : info.classroom);
        hintInfo.put("actionInfo", actionInfo);
        if (timerMs > 0) {
            JSONObject timerInfo = new JSONObject();
            timerInfo.put("timerType",          timerType);
            timerInfo.put("timerWhen",          timerMs);
            timerInfo.put("timerSystemCurrent", now);
            hintInfo.put("timerInfo", timerInfo);
        } else {
            hintInfo.put("title", hintContent);
        }

        // ── bigIslandArea ─────────────────────────────────────────
        // 按上课状态选择对应阶段模板后缀
        String stageSuffix = (state == STATE_COUNTDOWN) ? "_pre"
                           : (state == STATE_ELAPSED)   ? "_active" : "_post";
        final boolean showIconA = (prefs == null || prefs.getBoolean("icon_a", true));
        JSONObject aTextInfo = new JSONObject();
        String aFallback = resolveTemplate(DEFAULT_TPLS[
                (state == STATE_COUNTDOWN) ? 0 : (state == STATE_ELAPSED) ? 1 : 2][0], info, info.courseName);
        aTextInfo.put("title", resolveTemplate(
                getStagedPref(prefs, "tpl_a", stageSuffix), info, aFallback));
        JSONObject imageTextInfoLeft = new JSONObject();
        imageTextInfoLeft.put("type", 1);
        if (showIconA) {
            // 显示图标：picInfo.type=1 = appIcon（取应用桌面图标）
            JSONObject aPicInfo = new JSONObject();
            aPicInfo.put("type", 1);
            imageTextInfoLeft.put("picInfo", aPicInfo);
        }
        // 隐藏图标时不传 picInfo，文档："图标或正文大字二选一"，有 textInfo.title 即合规
        imageTextInfoLeft.put("textInfo", aTextInfo);
        JSONObject bTextInfo = new JSONObject();
        String bFallback = resolveTemplate(DEFAULT_TPLS[
                (state == STATE_COUNTDOWN) ? 0 : (state == STATE_ELAPSED) ? 1 : 2][1], info, info.classroom.isEmpty() ? "\u2014" : info.classroom);
        bTextInfo.put("title", resolveTemplate(
                getStagedPref(prefs, "tpl_b", stageSuffix), info, bFallback));
        JSONObject bigIslandArea = new JSONObject();
        bigIslandArea.put("imageTextInfoLeft", imageTextInfoLeft);
        bigIslandArea.put("textInfo",          bTextInfo);

        // ── smallIslandArea（小岛/息屏图标）────────────────────────────────────────
        // 文档说明：小岛图标有三级 fallback（开发者上传 → A区左侧图标 → 应用图标），
        // 系统始终显示图标，无法通过不传字段来隐藏。
        // 因此不传 smallIslandArea.picInfo，让系统自动 fallback 到 A区图标或应用图标。
        // icon_a 关闭时 A区无 picInfo，小岛最终 fallback 到应用图标（始终有图标）。
        JSONObject smallIslandArea = new JSONObject();

        // ── shareData ─────────────────────────────────────────────
        String timeRange = info.startTime + (info.endTime.isEmpty() ? "" : "-" + info.endTime);
        JSONObject shareData = new JSONObject();
        shareData.put("pic",          PIC_KEY_SHARE);
        shareData.put("title",        info.courseName);
        shareData.put("content",      info.classroom.isEmpty() ? "" : info.classroom);
        shareData.put("shareContent", info.courseName
                + (info.classroom.isEmpty() ? "" : " " + info.classroom)
                + (timeRange.isEmpty() ? "" : " " + timeRange));

        // ── 岛消失超时（按阶段读取对应值）──────────
        // 根据当前 state 选择阶段后缀："pre" / "active" / "post"
        String phase = (state == STATE_COUNTDOWN) ? "pre"
                 : (state == STATE_ELAPSED)   ? "active" : "post";
        int islandToVal  = (prefs != null) ? prefs.getInt   ("to_island_val_"  + phase, -1) : -1;
        String islandToUnit = (prefs != null) ? safeStr(prefs.getString("to_island_unit_" + phase, "m")) : "m";

        JSONObject paramIsland = new JSONObject();
        paramIsland.put("islandProperty",  1);
        paramIsland.put("bigIslandArea",   bigIslandArea);
        paramIsland.put("smallIslandArea", smallIslandArea);
        paramIsland.put("shareData",       shareData);
        if (islandToVal > 0) {
            long islandToSecs = "m".equals(islandToUnit) ? (long) islandToVal * 60 : islandToVal;
            paramIsland.put("islandTimeout", islandToSecs);
            XposedBridge.log(TAG + ": islandTimeout(" + phase + ")=" + islandToSecs + "s");
        }

        // ── paramV2 ───────────────────────────────────────────────
        String tickerText = resolveTemplate(
                getStagedPref(prefs, "tpl_ticker", stageSuffix),
                info, buildTickerText(info));
        JSONObject paramV2 = new JSONObject();
        paramV2.put("protocol",    1);
        paramV2.put("business",    "course_schedule");
        paramV2.put("updatable",   true);
        paramV2.put("colorScheme", 0); // 0 = 自动适配，状态栏自动反色
        paramV2.put("ticker",      tickerText);
        paramV2.put("aodTitle",    tickerText);
        if (isActive) {
            paramV2.put("islandFirstFloat", true);
            paramV2.put("enableFloat",      true);
        } else {
            paramV2.put("enableFloat", false);
        }
        paramV2.put("baseInfo",     baseInfo);
        paramV2.put("picInfo",      notifPicInfo);
        paramV2.put("hintInfo",     hintInfo);
        paramV2.put("param_island", paramIsland);
        // 注意：param_v2.timeout 字段在实测中无效，通知消失改由 scheduleNotifCancelAlarms
        // 在 injectIslandParams 时通过 AlarmManager + nm.cancel() 实现。

        JSONObject root = new JSONObject();
        root.put("param_v2", paramV2);
        return root.toString();
    }

    /** 状态栏 / 息屏文案兜底（getStagedPref 已优先返回 DEFAULT_TPLS，此方法为最后保底） */
    private String buildTickerText(CourseInfo info) {
        return (info.startTime.isEmpty() ? info.courseName : info.startTime + "上课");
    }

    // 各阶段模板的最终兑底默认值（即使未开启过模块主界面也生效）
    private static final String[][] DEFAULT_TPLS = {
        // { tpl_a,    tpl_b,       tpl_ticker          }
        { "{\u6559\u5ba4}",   "{\u5f00\u59cb}\u4e0a\u8bfe",  "{\u6559\u5ba4}\uff5c{\u5f00\u59cb}\u4e0a\u8bfe"  }, // _pre
        { "{\u8bfe\u540d}",   "{\u7ed3\u675f}\u4e0b\u8bfe",  "{\u8bfe\u540d}\uff5c{\u7ed3\u675f}\u4e0b\u8bfe"  }, // _active
        { "{\u8bfe\u540d}",   "\u5df2\u7ecf\u4e0b\u8bfe",    "{\u8bfe\u540d}\uff5c\u5df2\u7ecf\u4e0b\u8bfe"    }, // _post
    };
    private static final String[] STAGE_SUFFIXES = {"_pre", "_active", "_post"};
    private static final String[] TPL_KEYS       = {"tpl_a", "tpl_b", "tpl_ticker"};

    /**
     * 读取分阶段模板（suffix = "_pre" / "_active" / "_post"）。
     * SP 为空时依次 fallback：无后缀旧 key → 代码内置默认值。
     */
    private static String getStagedPref(SharedPreferences prefs, String key, String suffix) {
        if (prefs != null) {
            String v = prefs.getString(key + suffix, "");
            if (v != null && !v.isEmpty()) return v;
            // 兼容旧版无分阶段配置
            String old = prefs.getString(key, "");
            if (old != null && !old.isEmpty()) return old;
        }
        // SP 完全为空（未开启过主界面），返回代码内置默认模板
        int si = java.util.Arrays.asList(STAGE_SUFFIXES).indexOf(suffix);
        int ki = java.util.Arrays.asList(TPL_KEYS).indexOf(key);
        if (si >= 0 && ki >= 0) return DEFAULT_TPLS[si][ki];
        return "";
    }

    /**
     * 将模板字符串中的变量替换为实际课程信息。
     * 支持：{课名} {开始} {结束} {教室}
     * 若模板为空则返回 fallback。
     */
    private static String resolveTemplate(String tpl, CourseInfo info, String fallback) {
        if (tpl == null || tpl.isEmpty()) return fallback;
        return tpl
                .replace("{课名}", info.courseName)
                .replace("{开始}", info.startTime)
                .replace("{结束}", info.endTime)
                .replace("{教室}", info.classroom);
    }

    /**
     * 跨进程读取模块自身的 SharedPreferences。
     * 使用 XSharedPreferences 绕过 Android 9+ 的沙箱文件权限限制。
     * createPackageContext+MODE_PRIVATE 在 Android 9+ 会被 SELinux 拦截，无法使用。
     */
    private SharedPreferences loadPrefs(Context ctx) {
        // 优先读取目标进程自己的 SP（由广播同步写入，无 SELinux 问题）
        SharedPreferences local = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (local.getAll().size() > 0) {
            XposedBridge.log(TAG + ": 读取目标进程偷好设置，条目数=" + local.getAll().size());
            return local;
        }
        // 回退：XSharedPreferences（首次吏未同步时）
        try {
            de.robv.android.xposed.XSharedPreferences prefs =
                    new de.robv.android.xposed.XSharedPreferences(MODULE_PKG, PREFS_NAME);
            prefs.reload();
            XposedBridge.log(TAG + ": XSharedPreferences 加载，条目数=" + prefs.getAll().size());
            return prefs;
        } catch (Exception e) {
            XposedBridge.log(TAG + ": loadPrefs 失败 → " + e.getMessage());
            return null;
        }
    }

    /**
     * 计算今天上课开始时间的毫秒时间戳。
     * 用于 hintInfo.timerInfo（倒计时组件）。
     */
    private static long computeClassStartMs(String startTime) {
        if (startTime == null || startTime.isEmpty()) return -1;
        try {
            String[] parts = startTime.split(":");
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, h);
            cal.set(java.util.Calendar.MINUTE, m);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        } catch (Exception e) {
            return -1;
        }
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
