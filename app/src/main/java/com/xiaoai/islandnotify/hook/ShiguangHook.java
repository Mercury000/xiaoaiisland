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
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.xiaoai.islandnotify.modernhook.XposedHelpers.findAndHookMethod;

public class ShiguangHook {

    static final String ACTION_SHIGUANG_COURSE_SYNC =
            "com.xiaoai.islandnotify.ACTION_SHIGUANG_COURSE_SYNC";
    static final String ACTION_REQUEST_SHIGUANG_SYNC =
            "com.xiaoai.islandnotify.ACTION_REQUEST_SHIGUANG_SYNC";

    private static final String TAG = "IslandNotifyShiguang";
    private static final String TARGET_PACKAGE = "com.xingheyuzhuan.shiguangschedule";
    private static final String TARGET_VOICEASSIST = "com.miui.voiceassist";
    private static final String DB_NAME = "main_app_database";
    private static final String DATASTORE_NAME = "app_settings.preferences_pb";
    private static final String HOOKED_KEY = "xiaoai.island.shiguang.hooked";
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private android.os.FileObserver mDbObserver;
    private android.os.FileObserver mStoreObserver;
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
                        registerSyncRequestReceiver(appCtx);
                        registerDbObserver(appCtx);
                        registerDataStoreObserver(appCtx);
                        postSync(appCtx, 350L, "startup");
                    }
                });
    }

    private void registerSyncRequestReceiver(Context ctx) {
        android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_REQUEST_SHIGUANG_SYNC);
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (!ACTION_REQUEST_SHIGUANG_SYNC.equals(action)) return;
                postSync(context, 120L, "manual_request");
            }
        };
        androidx.core.content.ContextCompat.registerReceiver(
                ctx, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
    }

    private void registerDbObserver(Context ctx) {
        if (mDbObserver != null) return;
        File dbFile = ctx.getDatabasePath(DB_NAME);
        File dbDir = dbFile == null ? null : dbFile.getParentFile();
        if (dbDir == null || !dbDir.exists()) return;
        mDbObserver = new android.os.FileObserver(
                dbDir.getAbsolutePath(),
                android.os.FileObserver.MOVED_TO
                        | android.os.FileObserver.CLOSE_WRITE
                        | android.os.FileObserver.MODIFY) {
            @Override
            public void onEvent(int event, String path) {
                if (path == null || !path.startsWith(DB_NAME)) return;
                postSync(ctx, 650L, "db_changed:" + path);
            }
        };
        mDbObserver.startWatching();
    }

    private void registerDataStoreObserver(Context ctx) {
        if (mStoreObserver != null) return;
        File store = new File(ctx.getFilesDir(), "datastore/" + DATASTORE_NAME);
        File dir = store.getParentFile();
        if (dir == null || !dir.exists()) return;
        mStoreObserver = new android.os.FileObserver(
                dir.getAbsolutePath(),
                android.os.FileObserver.MOVED_TO
                        | android.os.FileObserver.CLOSE_WRITE
                        | android.os.FileObserver.MODIFY) {
            @Override
            public void onEvent(int event, String path) {
                if (path == null || !DATASTORE_NAME.equals(path)) return;
                postSync(ctx, 220L, "datastore_changed");
            }
        };
        mStoreObserver.startWatching();
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
            String beanJson = buildWeekCourseBeanFromShiguang(ctx);
            if (beanJson == null || beanJson.isEmpty()) return;
            int hash = CourseScheduleParser.stableHash(beanJson);
            if (hash == mLastPushedHash) return;
            mLastPushedHash = hash;

            Intent sync = new Intent(ACTION_SHIGUANG_COURSE_SYNC);
            sync.setPackage(TARGET_VOICEASSIST);
            sync.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            sync.putExtra("bean_json", beanJson);
            sync.putExtra("hash", hash);
            ctx.sendBroadcast(sync);
            XposedBridge.log(TAG + ": 已推送拾光课程镜像 -> voiceassist reason=" + reason + " hash=" + hash);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": syncAndPush 失败 -> " + t.getMessage());
        }
    }

    private String buildWeekCourseBeanFromShiguang(Context ctx) throws Exception {
        File db = ctx.getDatabasePath(DB_NAME);
        if (db == null || !db.exists()) return null;

        SQLiteDatabase sqLiteDb = null;
        Cursor c = null;
        try {
            sqLiteDb = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            String currentTableId = resolveCurrentTableId(ctx, sqLiteDb);
            if (currentTableId == null || currentTableId.isEmpty()) return null;

            TableConfig config = loadTableConfig(sqLiteDb, currentTableId);
            Map<Integer, String[]> normalSlots = loadTimeSlots(sqLiteDb, currentTableId);

            Map<String, List<Integer>> weeksByCourse = new HashMap<>();
            c = sqLiteDb.rawQuery(
                    "SELECT courseId, weekNumber FROM course_weeks " +
                            "WHERE courseId IN (SELECT id FROM courses WHERE courseTableId = ?) " +
                            "ORDER BY courseId ASC, weekNumber ASC",
                    new String[]{currentTableId});
            int maxWeek = 0;
            while (c.moveToNext()) {
                String cid = safeStr(c.getString(0));
                int week = c.getInt(1);
                if (cid.isEmpty() || week <= 0) continue;
                List<Integer> weeks = weeksByCourse.get(cid);
                if (weeks == null) {
                    weeks = new ArrayList<>();
                    weeksByCourse.put(cid, weeks);
                }
                weeks.add(week);
                if (week > maxWeek) maxWeek = week;
            }
            c.close();
            c = null;

            JSONArray sectionTimes = new JSONArray();
            Set<Integer> seenSections = new HashSet<>();
            for (Map.Entry<Integer, String[]> e : normalSlots.entrySet()) {
                int sec = e.getKey();
                String start = e.getValue()[0];
                String end = e.getValue()[1];
                if (isInvalidSectionTime(start, end)) continue;
                JSONObject st = new JSONObject();
                st.put("i", sec);
                st.put("s", start);
                st.put("e", end);
                sectionTimes.put(st);
                seenSections.add(sec);
            }

            int syntheticSec = 1000;
            Map<String, Integer> customTimeToSec = new HashMap<>();
            JSONArray courses = new JSONArray();

            c = sqLiteDb.rawQuery(
                    "SELECT id, name, teacher, position, day, startSection, endSection, " +
                            "isCustomTime, customStartTime, customEndTime " +
                            "FROM courses WHERE courseTableId = ? ORDER BY day ASC, startSection ASC",
                    new String[]{currentTableId});
            while (c.moveToNext()) {
                String courseId = safeStr(c.getString(0));
                String name = safeStr(c.getString(1));
                String teacher = safeStr(c.getString(2));
                String position = safeStr(c.getString(3));
                int day = c.getInt(4);
                boolean isCustom = c.getInt(7) == 1;
                if (name.isEmpty() || day < 1 || day > 7) continue;

                List<Integer> weeks = weeksByCourse.get(courseId);
                if (weeks == null || weeks.isEmpty()) continue;
                String weeksSpec = toWeeksSpec(weeks);
                if (weeksSpec.isEmpty()) continue;

                String sectionsSpec;
                if (isCustom) {
                    String cs = safeStr(c.getString(8));
                    String ce = safeStr(c.getString(9));
                    if (isInvalidSectionTime(cs, ce)) continue;
                    String slotKey = cs + "|" + ce;
                    Integer secIdx = customTimeToSec.get(slotKey);
                    if (secIdx == null) {
                        while (seenSections.contains(syntheticSec)) syntheticSec++;
                        secIdx = syntheticSec++;
                        customTimeToSec.put(slotKey, secIdx);
                        JSONObject st = new JSONObject();
                        st.put("i", secIdx);
                        st.put("s", cs);
                        st.put("e", ce);
                        sectionTimes.put(st);
                        seenSections.add(secIdx);
                    }
                    sectionsSpec = String.valueOf(secIdx);
                } else {
                    if (c.isNull(5) || c.isNull(6)) continue;
                    int startSec = c.getInt(5);
                    int endSec = c.getInt(6);
                    if (startSec <= 0 || endSec <= 0) continue;
                    int minSec = Math.min(startSec, endSec);
                    int maxSec = Math.max(startSec, endSec);
                    if (!normalSlots.containsKey(minSec) || !normalSlots.containsKey(maxSec)) continue;
                    sectionsSpec = minSec == maxSec ? String.valueOf(minSec) : (minSec + "-" + maxSec);
                }

                JSONObject course = new JSONObject();
                course.put("day", day);
                course.put("name", name);
                course.put("teacher", teacher);
                course.put("position", position);
                course.put("sections", sectionsSpec);
                course.put("weeks", weeksSpec);
                courses.put(course);
            }
            c.close();
            c = null;

            int totalWeek = config.semesterTotalWeeks > 0 ? config.semesterTotalWeeks : (maxWeek > 0 ? maxWeek : 30);
            int presentWeek = computePresentWeek(config.semesterStartDate, totalWeek, config.sundayFirst);

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

    private String resolveCurrentTableId(Context ctx, SQLiteDatabase db) {
        String fromStore = readCurrentTableIdFromDataStore(ctx);
        if (fromStore != null && !fromStore.isEmpty()) return fromStore;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT id FROM course_tables ORDER BY createdAt DESC LIMIT 1", null);
            if (c.moveToFirst()) return safeStr(c.getString(0));
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private String readCurrentTableIdFromDataStore(Context ctx) {
        File file = new File(ctx.getFilesDir(), "datastore/" + DATASTORE_NAME);
        if (!file.exists()) return null;
        FileInputStream fis = null;
        try {
            byte[] buf = new byte[(int) Math.min(file.length(), 64 * 1024L)];
            fis = new FileInputStream(file);
            int read = fis.read(buf);
            if (read <= 0) return null;
            String raw = new String(buf, 0, read, StandardCharsets.ISO_8859_1);
            int idx = raw.indexOf("current_course_table_id");
            String scope = idx >= 0 ? raw.substring(idx, Math.min(raw.length(), idx + 200)) : raw;
            Matcher m = UUID_PATTERN.matcher(scope);
            if (m.find()) return m.group();
            m = UUID_PATTERN.matcher(raw);
            if (m.find()) return m.group();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (fis != null) fis.close();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private TableConfig loadTableConfig(SQLiteDatabase db, String tableId) {
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT semesterStartDate, semesterTotalWeeks, firstDayOfWeek " +
                            "FROM course_table_config WHERE courseTableId = ? LIMIT 1",
                    new String[]{tableId});
            if (c.moveToFirst()) {
                String startDate = normalizeStartDate(safeStr(c.getString(0)));
                int totalWeeks = c.getInt(1);
                int firstDay = c.getInt(2);
                boolean sundayFirst = (firstDay == 0 || firstDay == 7);
                return new TableConfig(startDate, totalWeeks, sundayFirst);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return new TableConfig("", 0, false);
    }

    private Map<Integer, String[]> loadTimeSlots(SQLiteDatabase db, String tableId) {
        Map<Integer, String[]> slots = new HashMap<>();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT number, startTime, endTime FROM time_slots WHERE courseTableId = ? ORDER BY number ASC",
                    new String[]{tableId});
            while (c.moveToNext()) {
                int number = c.getInt(0);
                String start = safeStr(c.getString(1));
                String end = safeStr(c.getString(2));
                slots.put(number, new String[]{start, end});
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return slots;
    }

    private static String toWeeksSpec(List<Integer> weeks) {
        if (weeks == null || weeks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < weeks.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(weeks.get(i));
        }
        return sb.toString();
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

    private static boolean isInvalidSectionTime(String start, String end) {
        if (start.isEmpty() || end.isEmpty()) return true;
        if ("00:00".equals(start) || "00:00".equals(end)) return true;
        return false;
    }

    private static String safeStr(String value) {
        return value == null ? "" : value;
    }

    private static final class TableConfig {
        final String semesterStartDate;
        final int semesterTotalWeeks;
        final boolean sundayFirst;

        TableConfig(String semesterStartDate, int semesterTotalWeeks, boolean sundayFirst) {
            this.semesterStartDate = semesterStartDate == null ? "" : semesterStartDate;
            this.semesterTotalWeeks = semesterTotalWeeks;
            this.sundayFirst = sundayFirst;
        }
    }
}
