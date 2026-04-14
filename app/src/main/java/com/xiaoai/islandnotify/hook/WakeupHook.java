package com.xiaoai.islandnotify;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.xiaoai.islandnotify.modernhook.XC_MethodHook;
import com.xiaoai.islandnotify.modernhook.XposedBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Calendar;
import java.util.Locale;

import static com.xiaoai.islandnotify.modernhook.XposedHelpers.findAndHookMethod;

public class WakeupHook {

    static final String ACTION_WAKEUP_COURSE_SYNC =
            "com.xiaoai.islandnotify.ACTION_WAKEUP_COURSE_SYNC";

    private static final String TAG = "IslandNotifyWakeup";
    private static final String TARGET_PACKAGE = "com.suda.yzune.wakeupschedule";
    private static final String TARGET_VOICEASSIST = "com.miui.voiceassist";
    private static final String WAKEUP_DB_NAME = "wakeup";
    private static final String HOOKED_KEY = "xiaoai.island.wakeup.hooked";

    private android.os.FileObserver mDbObserver;
    private android.os.Handler mHandler;
    private final Object mSyncToken = new Object();
    private volatile int mLastPushedHash = 0;

    public void handleLoadPackage(String packageName, String processName, ClassLoader classLoader) {
        if (!TARGET_PACKAGE.equals(packageName)) return;
        if (!TARGET_PACKAGE.equals(processName)) return;
        if (System.getProperty(HOOKED_KEY) != null) return;
        System.setProperty(HOOKED_KEY, "1");
        hookApplicationOnCreate(classLoader);
        XposedBridge.log(TAG + ": 已注入目标进程 → " + TARGET_PACKAGE);
    }

    private void hookApplicationOnCreate(ClassLoader classLoader) {
        findAndHookMethod("android.app.Application", classLoader,
                "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context appCtx = (Application) param.thisObject;
                        registerDbObserver(appCtx);
                        postSync(appCtx, 300L, "startup");
                    }
                });
    }

    private void registerDbObserver(Context ctx) {
        if (mDbObserver != null) return;
        File dbFile = ctx.getDatabasePath(WAKEUP_DB_NAME);
        File dbDir = dbFile == null ? null : dbFile.getParentFile();
        if (dbDir == null || !dbDir.exists()) {
            XposedBridge.log(TAG + ": 数据库目录不存在，跳过监听");
            return;
        }

        mDbObserver = new android.os.FileObserver(
                dbDir.getAbsolutePath(),
                android.os.FileObserver.MOVED_TO
                        | android.os.FileObserver.CLOSE_WRITE
                        | android.os.FileObserver.MODIFY) {
            @Override
            public void onEvent(int event, String path) {
                if (path == null || !path.startsWith(WAKEUP_DB_NAME)) return;
                postSync(ctx, 600L, "db_changed:" + path);
            }
        };
        mDbObserver.startWatching();
        XposedBridge.log(TAG + ": Wakeup DB 监听已启动 -> " + dbDir.getAbsolutePath());
    }

    private void postSync(Context ctx, long delayMs, String reason) {
        android.os.Handler handler = getHandler();
        handler.removeCallbacksAndMessages(mSyncToken);
        handler.postDelayed(() -> syncAndPush(ctx, reason), mSyncToken, delayMs);
    }

    private android.os.Handler getHandler() {
        if (mHandler == null) {
            mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        return mHandler;
    }

    private void syncAndPush(Context ctx, String reason) {
        try {
            String beanJson = buildWeekCourseBeanFromWakeup(ctx);
            if (beanJson == null || beanJson.isEmpty()) return;
            int hash = CourseScheduleParser.stableHash(beanJson);
            if (hash == mLastPushedHash) return;
            mLastPushedHash = hash;

            Intent sync = new Intent(ACTION_WAKEUP_COURSE_SYNC);
            sync.setPackage(TARGET_VOICEASSIST);
            sync.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            sync.putExtra("bean_json", beanJson);
            sync.putExtra("hash", hash);
            ctx.sendBroadcast(sync);
            XposedBridge.log(TAG + ": 已推送 wakeup 课程镜像 -> voiceassist reason=" + reason + " hash=" + hash);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": syncAndPush 失败 -> " + t.getMessage());
        }
    }

    private String buildWeekCourseBeanFromWakeup(Context ctx) throws Exception {
        File db = ctx.getDatabasePath(WAKEUP_DB_NAME);
        if (db == null || !db.exists()) return null;
        SQLiteDatabase sqLiteDb = null;
        Cursor c = null;
        try {
            sqLiteDb = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);

            long tableId = -1L;
            long timeTableId = -1L;
            c = sqLiteDb.rawQuery(
                    "SELECT id, timeTable FROM TableBean ORDER BY CAST(updateTime AS INTEGER) DESC, id DESC LIMIT 1",
                    null);
            if (c.moveToFirst()) {
                tableId = c.getLong(0);
                timeTableId = c.getLong(1);
            }
            c.close();
            c = null;

            if (tableId <= 0L) {
                c = sqLiteDb.rawQuery("SELECT MAX(tableId) FROM CourseDetailBean", null);
                if (c.moveToFirst()) tableId = c.getLong(0);
                c.close();
                c = null;
            }
            if (tableId <= 0L) return null;
            if (timeTableId <= 0L) {
                c = sqLiteDb.rawQuery("SELECT timeTable FROM TableBean WHERE id = ?", new String[]{String.valueOf(tableId)});
                if (c.moveToFirst()) timeTableId = c.getLong(0);
                c.close();
                c = null;
            }

            JSONArray sectionTimes = new JSONArray();
            if (timeTableId > 0L) {
                c = sqLiteDb.rawQuery(
                        "SELECT node, startTime, endTime FROM TimeDetailBean WHERE timeTable = ? ORDER BY node ASC",
                        new String[]{String.valueOf(timeTableId)});
                while (c.moveToNext()) {
                    String start = safeStr(c.getString(1));
                    String end = safeStr(c.getString(2));
                    if (isInvalidSectionTime(start, end)) continue;
                    JSONObject st = new JSONObject();
                    st.put("i", c.getInt(0));
                    st.put("s", start);
                    st.put("e", end);
                    sectionTimes.put(st);
                }
                c.close();
                c = null;
            }

            JSONArray courses = new JSONArray();
            int maxEndWeek = 0;
            c = sqLiteDb.rawQuery(
                    "SELECT d.day, d.room, d.teacher, d.startNode, d.step, d.startWeek, d.endWeek, d.type, " +
                            "d.ownTime, d.startTime, d.endTime, b.courseName " +
                            "FROM CourseDetailBean d " +
                            "JOIN CourseBaseBean b ON b.id = d.id AND b.tableId = d.tableId " +
                            "WHERE d.tableId = ? " +
                            "ORDER BY d.day ASC, d.startNode ASC",
                    new String[]{String.valueOf(tableId)});
            while (c.moveToNext()) {
                int day = c.getInt(0);
                int startNode = c.getInt(3);
                int step = Math.max(1, c.getInt(4));
                int startWeek = Math.max(1, c.getInt(5));
                int endWeek = Math.max(startWeek, c.getInt(6));
                int type = c.getInt(7);
                int ownTime = c.getInt(8);
                String customStartTime = safeStr(c.getString(9));
                String customEndTime = safeStr(c.getString(10));
                String courseName = safeStr(c.getString(11));
                if (day < 1 || day > 7 || startNode <= 0 || courseName.isEmpty()) continue;

                String weeks = buildWeeks(startWeek, endWeek, type);
                if (weeks.isEmpty()) continue;
                int endNode = startNode + step - 1;

                JSONObject course = new JSONObject();
                course.put("day", day);
                course.put("name", courseName);
                course.put("position", safeStr(c.getString(1)));
                course.put("teacher", safeStr(c.getString(2)));
                course.put("sections", startNode == endNode
                        ? String.valueOf(startNode)
                        : (startNode + "-" + endNode));
                course.put("weeks", weeks);
                // WakeUp 自定义时间存于 CourseDetailBean.startTime/endTime（ownTime=1）。
                // 写入镜像后由解析器优先采用，避免被节次默认时间覆盖。
                if (ownTime == 1 && !isInvalidSectionTime(customStartTime, customEndTime)) {
                    course.put("startTime", customStartTime);
                    course.put("endTime", customEndTime);
                }
                courses.put(course);
                if (endWeek > maxEndWeek) maxEndWeek = endWeek;
            }
            c.close();
            c = null;

            TermConfig termConfig = loadTermConfig(ctx, tableId);
            int totalWeek = termConfig.maxWeek > 0 ? termConfig.maxWeek
                    : (maxEndWeek > 0 ? maxEndWeek : 30);
            int presentWeek = computePresentWeek(termConfig.startDate, totalWeek, termConfig.sundayFirst);

            JSONObject setting = new JSONObject();
            setting.put("presentWeek", presentWeek);
            setting.put("totalWeek", totalWeek);
            setting.put("weekStart", 1);
            setting.put("sectionTimes", sectionTimes);

            JSONObject data = new JSONObject();
            data.put("setting", setting);
            data.put("courses", courses);

            JSONObject root = new JSONObject();
            root.put("data", data);
            return root.toString();
        } finally {
            if (c != null) c.close();
            if (sqLiteDb != null) sqLiteDb.close();
        }
    }

    private static String buildWeeks(int startWeek, int endWeek, int type) {
        StringBuilder sb = new StringBuilder();
        for (int w = startWeek; w <= endWeek; w++) {
            if (type == 1 && (w % 2 == 0)) continue;
            if (type == 2 && (w % 2 != 0)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(w);
        }
        return sb.toString();
    }

    private static String safeStr(String value) {
        return value == null ? "" : value;
    }

    private static boolean isInvalidSectionTime(String start, String end) {
        if (start.isEmpty() || end.isEmpty()) return true;
        if ("00:00".equals(start) || "00:00".equals(end)) return true;
        return false;
    }

    private TermConfig loadTermConfig(Context ctx, long tableId) {
        String prefName = "table" + tableId + "_config";
        android.content.SharedPreferences sp =
                ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE);
        String startDate = normalizeStartDate(sp.getString("startDate", ""));
        int maxWeek = sp.getInt("maxWeek", 0);
        boolean sundayFirst = sp.getBoolean("sundayFirst", false);
        if (startDate.isEmpty()) {
            XposedBridge.log(TAG + ": 未找到有效 startDate，pref=" + prefName);
        }
        return new TermConfig(startDate, maxWeek, sundayFirst);
    }

    private int computePresentWeek(String startDate, int totalWeek, boolean sundayFirst) {
        if (startDate == null || startDate.isEmpty()) return 1;
        int[] ymd = parseYmd(startDate);
        if (ymd == null) return 1;

        Calendar start = Calendar.getInstance(Locale.US);
        start.set(Calendar.YEAR, ymd[0]);
        start.set(Calendar.MONTH, Math.max(0, ymd[1] - 1));
        start.set(Calendar.DAY_OF_MONTH, Math.max(1, ymd[2]));
        clearClock(start);

        Calendar today = Calendar.getInstance(Locale.US);
        clearClock(today);

        int weekStartDay = sundayFirst ? Calendar.SUNDAY : Calendar.MONDAY;
        alignToWeekStart(start, weekStartDay);
        alignToWeekStart(today, weekStartDay);

        long diffDays = (today.getTimeInMillis() - start.getTimeInMillis()) / 86_400_000L;
        int week = (int) Math.floor(diffDays / 7.0d) + 1;
        if (week < 1) week = 1;
        if (totalWeek > 0 && week > totalWeek) week = totalWeek;
        return week;
    }

    private static void clearClock(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private static void alignToWeekStart(Calendar c, int weekStartDay) {
        int cur = c.get(Calendar.DAY_OF_WEEK);
        int delta = cur - weekStartDay;
        if (delta < 0) delta += 7;
        if (delta != 0) c.add(Calendar.DAY_OF_MONTH, -delta);
    }

    private static String normalizeStartDate(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        return s.replace('/', '-').replace('.', '-');
    }

    private static int[] parseYmd(String raw) {
        try {
            String[] parts = raw.split("-");
            if (parts.length < 3) return null;
            int y = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            int d = Integer.parseInt(parts[2].trim());
            if (y <= 0 || m <= 0 || d <= 0) return null;
            return new int[]{y, m, d};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class TermConfig {
        final String startDate;
        final int maxWeek;
        final boolean sundayFirst;

        TermConfig(String startDate, int maxWeek, boolean sundayFirst) {
            this.startDate = startDate == null ? "" : startDate;
            this.maxWeek = maxWeek;
            this.sundayFirst = sundayFirst;
        }
    }
}
