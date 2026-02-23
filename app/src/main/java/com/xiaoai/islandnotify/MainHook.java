package com.xiaoai.islandnotify;

import android.app.Notification;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
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

    /**
     * 用于识别"课程表提醒"通知的关键词列表。
     * 匹配通知标题、正文或 Channel ID 中任意一处包含以下词汇即触发转换。
     */
    private static final String[] SCHEDULE_KEYWORDS = {
            "课程", "课表", "上课", "选课", "schedule", "class reminder"
    };

    // ─────────────────────────────────────────────────────────────
    // 超级岛通知的参数 Key（均为小米私有扩展）
    // ─────────────────────────────────────────────────────────────
    /** 岛通知主参数 Key（JSON 字符串） */
    private static final String KEY_FOCUS_PARAM = "miui.focus.param";
    /** 图片 Bundle Key */
    private static final String KEY_FOCUS_PICS  = "miui.focus.pics";

    // ─────────────────────────────────────────────────────────────

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 只注入目标进程
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + ": 已注入目标进程 → " + TARGET_PACKAGE);
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
                injectIslandParams(notification);
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
     * 根据关键词判断是否为"课程表提醒"通知。
     * 匹配范围：通知标题、正文、Channel ID。
     */
    private boolean isScheduleNotification(Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) return false;

        String title     = extras.getString(Notification.EXTRA_TITLE, "");
        String text      = extras.getString(Notification.EXTRA_TEXT, "");
        String channelId = notification.getChannelId();
        if (channelId == null) channelId = "";

        String combined = (title + " " + text + " " + channelId).toLowerCase();

        for (String kw : SCHEDULE_KEYWORDS) {
            if (combined.contains(kw.toLowerCase())) {
                XposedBridge.log(TAG + ": 命中关键词 [" + kw + "]  title=" + title);
                return true;
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // 超级岛参数注入
    // ─────────────────────────────────────────────────────────────

    /**
     * 向通知的 extras 中注入 miui.focus.param，使其变为超级岛通知。
     */
    private void injectIslandParams(Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) {
            extras = new Bundle();
            notification.extras = extras;
        }

        try {
            CourseInfo info = extractCourseInfo(extras);
            XposedBridge.log(TAG + ": 解析结果 → 课程=" + info.courseName
                    + " 时间=" + info.startTime + " 教室=" + info.classroom);
            String islandJson = buildIslandParams(info);
            extras.putString(KEY_FOCUS_PARAM, islandJson);
            XposedBridge.log(TAG + ": 注入成功");
        } catch (JSONException e) {
            XposedBridge.log(TAG + ": 构建 JSON 失败 → " + e.getMessage());
        }
    }

    /**
     * 从通知 extras 中提取结构化课程信息。
     *
     * <p>通知内容格式（com.miui.voiceassist 课程表提醒）示例：
     * <pre>
     *   title:   "上课提醒"（或直接为课程名）
     *   text:    "[高等数学]课快到了，提前准备一下吧"
     *   lines[]: ["10:20", "教1-201"]  或合并在 text / subText 中
     * </pre>
     */
    private CourseInfo extractCourseInfo(Bundle extras) {
        // ── 1. 收集所有文本字段 ────────────────────────────────────
        String title    = safeStr(extras.getString(Notification.EXTRA_TITLE));
        String text     = safeStr(extras.getString(Notification.EXTRA_TEXT));
        String bigText  = safeStr(extras.getString(Notification.EXTRA_BIG_TEXT));
        String subText  = safeStr(extras.getString(Notification.EXTRA_SUB_TEXT));
        String infoText = safeStr(extras.getString(Notification.EXTRA_INFO_TEXT));

        StringBuilder allBuilder = new StringBuilder();
        allBuilder.append(title).append(" ")
                  .append(text).append(" ")
                  .append(bigText).append(" ")
                  .append(subText).append(" ")
                  .append(infoText);

        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) {
            for (CharSequence line : lines) {
                allBuilder.append(" ").append(line);
            }
        }
        String all = allBuilder.toString();

        // ── 2. 提取课程名 ──────────────────────────────────────────
        // 优先：[高等数学]课快到了 → 取中括号内容
        String courseName = "";
        Matcher bracketM = Pattern.compile("\\[([^\\]]+)\\]").matcher(all);
        if (bracketM.find()) {
            courseName = bracketM.group(1).trim();
        }
        // 次选：高等数学课快到了 → 取"课快到了"前的中文串
        if (courseName.isEmpty()) {
            Matcher suffixM = Pattern.compile("([\\u4e00-\\u9fa5\\w]+)课快到了").matcher(all);
            if (suffixM.find()) {
                courseName = suffixM.group(1).trim();
            }
        }
        // 兜底：title 去掉"提醒/通知/课程表/上课"等后缀
        if (courseName.isEmpty() && !title.isEmpty()) {
            courseName = title.replaceAll("(提醒|通知|课程表|上课).*", "").trim();
            if (courseName.isEmpty()) courseName = title;
        }
        if (courseName.isEmpty()) courseName = "课程提醒";

        // ── 3. 提取上课时间（H:MM 或 HH:MM）──────────────────────────
        String startTime = "";
        Matcher timeM = Pattern.compile("\\b(\\d{1,2}:\\d{2})\\b").matcher(all);
        if (timeM.find()) {
            startTime = timeM.group(1);
        }

        // ── 4. 提取教室 ────────────────────────────────────────────
        // 覆盖常见格式：教1-201 / 体育馆 / 东A101 / 实验楼302 / 图书馆 等
        String classroom = "";
        Matcher roomM = Pattern.compile(
                "(教\\d+[-_]\\d+|[东南西北][A-Za-z]?\\d{2,4}|" +
                "[\\u4e00-\\u9fa5]{2,5}(?:馆|楼|室|场|厅|中心)\\d*|" +
                "(?:实验|图书|体育|综合|教学)[\\u4e00-\\u9fa5]*\\d*)"
        ).matcher(all);
        while (roomM.find()) {
            String candidate = roomM.group(1).trim();
            if (!candidate.equals(courseName)
                    && !candidate.contains("提醒")
                    && !candidate.contains("通知")
                    && candidate.length() >= 2) {
                classroom = candidate;
                break;
            }
        }

        return new CourseInfo(courseName, startTime, classroom);
    }

    private static String safeStr(String s) {
        return s != null ? s : "";
    }

    /**
     * 构建超级岛 JSON 参数（param_v2 格式）。
     *
     * <p>对应模板：文字信息展示类 — 主要文本1 + 次要文本（前置文本 + 主要小文本）
     * <pre>
     * 大岛（展开态）：
     * ┌─────────────────────────────────┐
     * │  主要文本1:  高等数学            │  ← imageTextInfoLeft.textInfo.title
     * │  时间        10:20              │  ← subTextInfoList[0] frontTitle/title
     * │  教室        教1-201            │  ← subTextInfoList[1] frontTitle/title
     * └─────────────────────────────────┘
     *
     * 小岛（摘要态）：
     * ┌──────────────────────┐
     * │  高等数学   10:20    │  ← smallIslandArea.textInfo title/content
     * └──────────────────────┘
     * </pre>
     */
    private String buildIslandParams(CourseInfo info) throws JSONException {
        // ── 大岛 A 区：主要文本1 = 课程名 ─────────────────────────
        JSONObject mainTextInfo = new JSONObject();
        mainTextInfo.put("title", info.courseName);

        JSONObject imageTextInfoLeft = new JSONObject();
        imageTextInfoLeft.put("type", 1);
        imageTextInfoLeft.put("textInfo", mainTextInfo);

        // ── 大岛 次要文本行：[前置文本 + 主要小文本] × N ────────────
        // 每项对应模板中一组"前置文本 + 主要小文本"
        JSONArray subTextInfoList = new JSONArray();
        if (!info.startTime.isEmpty()) {
            JSONObject timeRow = new JSONObject();
            timeRow.put("frontTitle", "时间");          // 前置文本1
            timeRow.put("title",      info.startTime);  // 主要小文本1
            subTextInfoList.put(timeRow);
        }
        if (!info.classroom.isEmpty()) {
            JSONObject roomRow = new JSONObject();
            roomRow.put("frontTitle", "教室");           // 前置文本2
            roomRow.put("title",      info.classroom);   // 主要小文本2
            subTextInfoList.put(roomRow);
        }

        JSONObject bigIslandArea = new JSONObject();
        bigIslandArea.put("imageTextInfoLeft", imageTextInfoLeft);
        if (subTextInfoList.length() > 0) {
            bigIslandArea.put("subTextInfoList", subTextInfoList);
        }

        // ── 小岛：课程名（主要文本）+ 时间（次要文本）────────────────
        JSONObject smallTextInfo = new JSONObject();
        smallTextInfo.put("title", info.courseName);
        if (!info.startTime.isEmpty()) {
            smallTextInfo.put("content", info.startTime);
        }
        JSONObject smallIslandArea = new JSONObject();
        smallIslandArea.put("textInfo", smallTextInfo);

        // ── 岛属性 ────────────────────────────────────────────────
        JSONObject paramIsland = new JSONObject();
        paramIsland.put("islandProperty", 1); // 1 = 信息展示为主
        paramIsland.put("bigIslandArea",   bigIslandArea);
        paramIsland.put("smallIslandArea", smallIslandArea);

        // ── 焦点通知基础内容（悬浮展示）──────────────────────────────
        String baseContent = buildBaseContent(info);
        JSONObject baseInfo = new JSONObject();
        baseInfo.put("title",   info.courseName);
        baseInfo.put("content", baseContent);
        baseInfo.put("type", 1);

        // ── 状态栏 / 息屏文案：课程名 + 时间 ─────────────────────────
        String tickerText = info.startTime.isEmpty()
                ? info.courseName
                : info.courseName + "  " + info.startTime;

        // ── 组合 param_v2 ─────────────────────────────────────────
        JSONObject paramV2 = new JSONObject();
        paramV2.put("protocol",         1);
        paramV2.put("business",         "course_schedule");
        paramV2.put("islandFirstFloat",  true);  // 首次出现时展开大岛
        paramV2.put("enableFloat",       false); // 更新时不自动展开
        paramV2.put("updatable",         false);
        paramV2.put("ticker",            tickerText); // OS2 状态栏文案
        paramV2.put("aodTitle",          tickerText); // 息屏文案
        paramV2.put("param_island",      paramIsland);
        paramV2.put("baseInfo",          baseInfo);

        JSONObject root = new JSONObject();
        root.put("param_v2", paramV2);
        return root.toString();
    }

    /** 拼接焦点通知副文本，格式：时间 10:20  教室 教1-201 */
    private String buildBaseContent(CourseInfo info) {
        StringBuilder sb = new StringBuilder();
        if (!info.startTime.isEmpty()) {
            sb.append("时间 ").append(info.startTime);
        }
        if (!info.classroom.isEmpty()) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("教室 ").append(info.classroom);
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────
    // 数据结构
    // ─────────────────────────────────────────────────────────────

    /**
     * 从通知中提取的结构化课程信息，对应模板字段：
     * <ul>
     *   <li>courseName → 主要文本1（课程名）</li>
     *   <li>startTime  → 次要文本·主要小文本1（前置文本="时间"）</li>
     *   <li>classroom  → 次要文本·主要小文本2（前置文本="教室"）</li>
     * </ul>
     */
    private static class CourseInfo {
        final String courseName;
        final String startTime;
        final String classroom;

        CourseInfo(String courseName, String startTime, String classroom) {
            this.courseName = courseName;
            this.startTime  = startTime;
            this.classroom  = classroom;
        }
    }
}
