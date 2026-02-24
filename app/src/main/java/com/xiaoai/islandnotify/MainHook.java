package com.xiaoai.islandnotify;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
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

    /** 防止同一进程内多个 ClassLoader 重复注册 Hook */
    private static volatile boolean hooked = false;

    /** 最近一次通知对象，供 extractFromRemoteViews 读取 bigContentView */
    private Notification lastNotificationRef = null;

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
        if (hooked) return;
        hooked = true;
        XposedBridge.log(TAG + ": 已注入目标进程 → " + TARGET_PACKAGE);
        hookNotifyMethods(lpparam);
    }

    /**
     * Hook 自身进程的 MainActivity.isModuleActive()，将返回值替换为 true，
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

            // 正计时更新通知直接放行，不再重入注入逻辑
            if (notification.extras != null
                    && notification.extras.getBoolean("islandElapsedUpdate", false)) return;

            // 防止重复处理
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
        if (channelId.contains("COURSE_SCHEDULER_REMINDER")) {
            XposedBridge.log(TAG + ": 命中 channelId=" + channelId);
            return true;
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
    private void injectIslandParams(Notification notification, Object nmInstance,
                                     int notifId, String notifTag) {
        Bundle extras = notification.extras;
        if (extras == null) {
            extras = new Bundle();
            notification.extras = extras;
        }

        try {
            this.lastNotificationRef = notification;
            CourseInfo info = extractCourseInfo(extras);
            XposedBridge.log(TAG + ": 解析结果 → 课程=" + info.courseName
                    + " 时间=" + info.startTime + " 结束=" + info.endTime
                    + " 教室=" + info.classroom);

            // ── 1. 构建超级岛 JSON ─────────────────────────────────────
            long startMs = computeClassStartMs(info.startTime);
            String islandJson = buildIslandParams(info);
            extras.putString(KEY_FOCUS_PARAM, islandJson);
            XposedBridge.log(TAG + ": JSON 长度=" + islandJson.length()
                    + " startMs=" + startMs);

            // ── 2. 获取 ctx ──────────────────────────────────────────
            Context ctx = null;
            try {
                ctx = (Context) XposedHelpers.getObjectField(nmInstance, "mContext");
            } catch (Throwable ignored) {}
            XposedBridge.log(TAG + ": ctx=" + (ctx != null ? "ok" : "null"));

            if (ctx != null) {
                // ── 3. 注入 miui.focus.pics ──────────────────────────
                try {
                    Drawable drawable = ctx.getPackageManager().getApplicationIcon(TARGET_PACKAGE);
                    if (drawable instanceof BitmapDrawable) {
                        Bitmap bmp = ((BitmapDrawable) drawable).getBitmap();
                        Bundle picsBundle = new Bundle();
                        picsBundle.putParcelable(PIC_KEY_SHARE, bmp);
                        extras.putBundle("miui.focus.pics", picsBundle);
                        XposedBridge.log(TAG + ": miui.focus.pics 注入成功 " + bmp.getWidth() + "x" + bmp.getHeight());
                    }
                } catch (Exception ignored) {}
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

                // ── 4. 延迟到开课时刻更新为正计时 ─────────────────────────
                long delay = startMs - System.currentTimeMillis();
                if (delay > 0 && delay <= 6 * 3600 * 1000L) {
                    final Context finalCtx    = ctx;
                    final CourseInfo savedInfo = info;
                    final String channelId    = safeStr(notification.getChannelId());
                    final Bundle savedPics    = extras.getBundle("miui.focus.pics");
                    final android.app.NotificationManager nm =
                            finalCtx.getSystemService(android.app.NotificationManager.class);
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            String elapsedJson = buildIslandParamsElapsed(savedInfo);
                            Notification.Builder b = new Notification.Builder(finalCtx, channelId)
                                    .setSmallIcon(notification.getSmallIcon())
                                    .setContentTitle(savedInfo.courseName)
                                    .setContentText(savedInfo.startTime
                                            + (savedInfo.endTime.isEmpty() ? "" : " | " + savedInfo.endTime)
                                            + (savedInfo.classroom.isEmpty() ? "" : " " + savedInfo.classroom))
                                    .setAutoCancel(true);
                            Notification updated = b.build();
                            updated.extras.putString(KEY_FOCUS_PARAM, elapsedJson);
                            updated.extras.putBoolean("islandElapsedUpdate", true);
                            // 同样注入 App 图标供分享
                            if (savedPics != null) {
                                updated.extras.putBundle("miui.focus.pics", savedPics);
                            }
                            updated.contentIntent = notification.contentIntent;
                            if (notifTag != null) nm.notify(notifTag, notifId, updated);
                            else                  nm.notify(notifId, updated);
                            XposedBridge.log(TAG + ": 正计时更新通知已重发 id=" + notifId);

                            // 安排下课状态更新
                            long endMs = computeClassStartMs(savedInfo.endTime);
                            long delayEnd = endMs - System.currentTimeMillis();
                            if (!savedInfo.endTime.isEmpty() && endMs > 0 && delayEnd > 0 && delayEnd <= 6 * 3600 * 1000L) {
                                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                    try {
                                        String finishedJson = buildIslandParamsFinished(savedInfo);
                                        Notification.Builder bf = new Notification.Builder(finalCtx, channelId)
                                                .setSmallIcon(notification.getSmallIcon())
                                                .setContentTitle(savedInfo.courseName)
                                                .setContentText(savedInfo.startTime
                                                        + (savedInfo.endTime.isEmpty() ? "" : " | " + savedInfo.endTime)
                                                        + (savedInfo.classroom.isEmpty() ? "" : " " + savedInfo.classroom))
                                                .setAutoCancel(true);
                                        Notification fin = bf.build();
                                        fin.extras.putString(KEY_FOCUS_PARAM, finishedJson);
                                        fin.extras.putBoolean("islandElapsedUpdate", true);
                                        if (savedPics != null) fin.extras.putBundle("miui.focus.pics", savedPics);
                                        fin.contentIntent = notification.contentIntent;
                                        if (notifTag != null) nm.notify(notifTag, notifId, fin);
                                        else                  nm.notify(notifId, fin);
                                        XposedBridge.log(TAG + ": 下课更新通知已重发 id=" + notifId);
                                    } catch (Exception e) {
                                        XposedBridge.log(TAG + ": 下课更新失败 → " + e.getMessage());
                                    }
                                }, delayEnd);
                                XposedBridge.log(TAG + ": 已安排下课更新，延迟 " + (delayEnd / 1000) + "秒");
                            }
                        } catch (Exception e) {
                            XposedBridge.log(TAG + ": 延迟更新失败 → " + e.getMessage());
                        }
                    }, delay);
                    XposedBridge.log(TAG + ": 已安排正计时更新，延迟 " + (delay / 1000) + "秒");
                }
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
    private CourseInfo extractCourseInfo(Bundle extras) {
        CourseInfo fromView = extractFromRemoteViews();
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
    private CourseInfo extractFromRemoteViews() {
        Notification notif = this.lastNotificationRef;
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
    private static final int STATE_COUNTDOWN = 0; // 倒计时（课前）
    private static final int STATE_ELAPSED   = 1; // 正计时（上课中）
    private static final int STATE_FINISHED  = 2; // 正计时（已下课）

    private String buildIslandParams(CourseInfo info)         throws JSONException { return buildIslandJson(info, STATE_COUNTDOWN); }
    private String buildIslandParamsElapsed(CourseInfo info)  throws JSONException { return buildIslandJson(info, STATE_ELAPSED);   }
    private String buildIslandParamsFinished(CourseInfo info) throws JSONException { return buildIslandJson(info, STATE_FINISHED);  }

    /**
     * 统一构建超级岛 JSON。三种状态的差异通过 state 参数区分：
     *   STATE_COUNTDOWN：课前倒计时，上课静音按钮
     *   STATE_ELAPSED  ：上课中正计时，上课静音按钮
     *   STATE_FINISHED ：下课后正计时，解除静音按钮
     */
    private String buildIslandJson(CourseInfo info, int state) throws JSONException {
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

        // ── picInfo ───────────────────────────────────────────────
        JSONObject notifPicInfo = new JSONObject();
        notifPicInfo.put("type", 1);

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
        String aContent;
        if (isFinished) {
            aContent = "已下课";
        } else if (isActive) {
            aContent = "已开始" + computeElapsed(info.startTime);
        } else if (startMs > 0 && startMs > now) {
            aContent = Math.max(1L, (startMs - now) / 60000L) + "分钟后开始";
        } else {
            aContent = "已开始" + computeElapsed(info.startTime);
        }
        JSONObject aPicInfo  = new JSONObject(); aPicInfo.put("type", 1);
        JSONObject aTextInfo = new JSONObject();
        aTextInfo.put("title",   info.courseName);
        aTextInfo.put("content", aContent);
        JSONObject imageTextInfoLeft = new JSONObject();
        imageTextInfoLeft.put("type",     1);
        imageTextInfoLeft.put("picInfo",  aPicInfo);
        imageTextInfoLeft.put("textInfo", aTextInfo);
        JSONObject bTextInfo = new JSONObject();
        bTextInfo.put("title", info.classroom.isEmpty() ? "—" : info.classroom);
        JSONObject bigIslandArea = new JSONObject();
        bigIslandArea.put("imageTextInfoLeft", imageTextInfoLeft);
        bigIslandArea.put("textInfo",          bTextInfo);

        // ── smallIslandArea ───────────────────────────────────────
        JSONObject smallPicInfo = new JSONObject(); smallPicInfo.put("type", 1);
        JSONObject smallIslandArea = new JSONObject(); smallIslandArea.put("picInfo", smallPicInfo);

        // ── shareData ─────────────────────────────────────────────
        String timeRange = info.startTime + (info.endTime.isEmpty() ? "" : "-" + info.endTime);
        JSONObject shareData = new JSONObject();
        shareData.put("pic",          PIC_KEY_SHARE);
        shareData.put("title",        info.courseName);
        shareData.put("content",      info.classroom.isEmpty() ? "" : info.classroom);
        shareData.put("shareContent", info.courseName
                + (info.classroom.isEmpty() ? "" : " " + info.classroom)
                + (timeRange.isEmpty() ? "" : " " + timeRange));

        JSONObject paramIsland = new JSONObject();
        paramIsland.put("islandProperty",  1);
        paramIsland.put("bigIslandArea",   bigIslandArea);
        paramIsland.put("smallIslandArea", smallIslandArea);
        paramIsland.put("shareData",       shareData);

        // ── paramV2 ───────────────────────────────────────────────
        String tickerText = buildTickerText(info);
        JSONObject paramV2 = new JSONObject();
        paramV2.put("protocol",  1);
        paramV2.put("business",  "course_schedule");
        paramV2.put("updatable", true);
        paramV2.put("ticker",    tickerText);
        paramV2.put("aodTitle",  tickerText);
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

    /** 计算距上课开始已过多少分钟，格式："5分钟" 或 "0分钟" */
    private static String computeElapsed(String startTime) {
        if (startTime == null || startTime.isEmpty()) return "";
        try {
            String[] parts = startTime.split(":");
            int startH = Integer.parseInt(parts[0].trim());
            int startM = Integer.parseInt(parts[1].trim());
            java.util.Calendar now = java.util.Calendar.getInstance();
            int diff = (now.get(java.util.Calendar.HOUR_OF_DAY) * 60
                    +  now.get(java.util.Calendar.MINUTE))
                    - (startH * 60 + startM);
            if (diff < 0) diff = 0;
            return diff + "分钟";
        } catch (Exception e) {
            return "";
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
