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
    /** voiceassist 侧被 MainHook hook 了 onStartCommand 的 Service，用于把已被杀的进程拉起来 */
    private static final String VOICEASSIST_UPLOAD_SERVICE =
            "com.xiaomi.voiceassistant.UploadStateService";
    private static final String WAKEUP_DB_NAME = "wakeup";
    /** WakeUp 全局配置 SP：当前显示的课表 ID 存于其中的 show_table_id */
    private static final String WAKEUP_MAIN_PREFS = "config";
    /** 旧存储结构分界：versionCode ≤ 263（即 6.0.23，Room schema v11），TableBean 无 updateTime 列 */
    private static final long LEGACY_VERSION_CODE_MAX = 263L;
    private static final String HOOKED_KEY = "xiaoai.island.wakeup.hooked";

    private android.os.FileObserver mDbObserver;
    private android.os.FileObserver mPrefsObserver;
    private android.os.Handler mHandler;
    private final Object mSyncToken = new Object();
    private volatile int mLastPushedHash = 0;
    private volatile Long mVersionCode;
    private volatile Boolean mLegacyMode;
    /** 自身读库产生的文件事件在此时间前一律忽略，避免「读 → 改 -shm/-wal → 再读」自激循环 */
    private volatile long mSelfReadUntilMs = 0L;
    /** 上次输出过的选表日志，内容不变时不重复打印 */
    private volatile String mLastPickLog = "";

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
                        registerPrefsObserver(appCtx);
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
                // -shm 只是 WAL 的读端索引：只读打开数据库也会写它，
                // 据此触发同步会形成「读 → 改 -shm → 再读」的自激循环。
                if (path.endsWith("-shm")) return;
                if (System.currentTimeMillis() < mSelfReadUntilMs) return;
                postSync(ctx, 600L, "db_changed:" + path);
            }
        };
        mDbObserver.startWatching();
        XposedBridge.log(TAG + ": Wakeup DB 监听已启动 -> " + dbDir.getAbsolutePath());
    }

    private void registerPrefsObserver(Context ctx) {
        if (mPrefsObserver != null) return;
        // startDate / maxWeek / sundayFirst 存储在 /shared_prefs/table<id>_config.xml
        File prefsDir = new File(ctx.getApplicationInfo().dataDir, "shared_prefs");
        if (!prefsDir.exists()) {
            XposedBridge.log(TAG + ": shared_prefs 目录不存在，跳过监听");
            return;
        }
        mPrefsObserver = new android.os.FileObserver(
                prefsDir.getAbsolutePath(),
                android.os.FileObserver.MOVED_TO
                        | android.os.FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, String path) {
                if (path == null) return;
                if (path.startsWith("table") && path.endsWith("_config.xml")) {
                    postSync(ctx, 400L, "prefs_changed:" + path);
                    return;
                }
                // 老版本（≤6.0.23）切换课表只改写 config.xml 的 show_table_id
                if ((WAKEUP_MAIN_PREFS + ".xml").equals(path)) {
                    postSync(ctx, 400L, "prefs_changed:" + path);
                }
            }
        };
        mPrefsObserver.startWatching();
        XposedBridge.log(TAG + ": Wakeup SharedPrefs 监听已启动 -> " + prefsDir.getAbsolutePath());
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

            // 先用 startService 把可能已被杀的 voiceassist 拉起来（MainHook 的 Service hook
            // 会把它转成包内广播）；广播只能进到运行中的动态接收器，进程不在时会静默丢失。
            boolean started = startVoiceassistService(ctx, beanJson, hash);

            Intent sync = new Intent(ACTION_WAKEUP_COURSE_SYNC);
            sync.setPackage(TARGET_VOICEASSIST);
            sync.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            sync.putExtra("bean_json", beanJson);
            sync.putExtra("hash", hash);
            ctx.sendBroadcast(sync);

            // 只有 startService 成功才算确定送达；否则不记账，留给下次事件重试，
            // 避免推送丢失后镜像永久停留在旧数据上。
            if (started) mLastPushedHash = hash;
            XposedBridge.log(TAG + ": 已推送 wakeup 课程镜像 -> voiceassist reason=" + reason
                    + " hash=" + hash + " service=" + started);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": syncAndPush 失败 -> " + t.getMessage());
        }
    }

    /** 通过 startService 投递并顺带拉起 voiceassist 进程，成功返回 true。 */
    private boolean startVoiceassistService(Context ctx, String beanJson, int hash) {
        try {
            Intent svc = new Intent(ACTION_WAKEUP_COURSE_SYNC);
            svc.setClassName(TARGET_VOICEASSIST, VOICEASSIST_UPLOAD_SERVICE);
            svc.putExtra("bean_json", beanJson);
            svc.putExtra("hash", hash);
            return ctx.startService(svc) != null;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": startService 拉起 voiceassist 失败 -> " + t.getMessage());
            return false;
        }
    }

    /** 读取宿主（WakeUp）versionCode；读取失败返回 Long.MAX_VALUE（按新版处理，由 SQL 降级兜底） */
    private long getWakeupVersionCode(Context ctx) {
        Long cached = mVersionCode;
        if (cached != null) return cached;
        long code = Long.MAX_VALUE;
        try {
            code = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 读取宿主版本失败 -> " + t.getMessage());
        }
        mVersionCode = code;
        return code;
    }

    /**
     * 是否为旧存储结构（versionCode ≤ 263，即 6.0.23，Room schema v11）：
     * TableBean 无 updateTime 列，选表需用 config.xml 的 show_table_id。
     */
    private boolean isLegacyVersion(Context ctx) {
        Boolean cached = mLegacyMode;
        if (cached != null) return cached;
        boolean legacy = getWakeupVersionCode(ctx) <= LEGACY_VERSION_CODE_MAX;
        mLegacyMode = legacy;
        return legacy;
    }

    private String buildWeekCourseBeanFromWakeup(Context ctx) throws Exception {
        File db = ctx.getDatabasePath(WAKEUP_DB_NAME);
        if (db == null || !db.exists()) return null;
        SQLiteDatabase sqLiteDb = null;
        Cursor c = null;
        try {
            sqLiteDb = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);

            boolean legacy = isLegacyVersion(ctx);
            boolean downgraded = false;
            long tableId = -1L;
            long timeTableId = -1L;
            String pickedBy = "none";

            // 优先 config.xml 的 show_table_id —— 这才是 WakeUp 当前显示的那张课表。
            // 不能只按 updateTime 取最新：多课表用户编辑过归档课表后会选错表，
            // startDate / maxWeek 跟着错，算出来的周数与 App 里显示的对不上。
            long shownId = readShownTableId(ctx);
            if (shownId > 0L) {
                long[] found = queryTableById(sqLiteDb, shownId);
                if (found != null) {
                    tableId = found[0];
                    timeTableId = found[1];
                    pickedBy = "show_table_id";
                } else {
                    XposedBridge.log(TAG + ": show_table_id=" + shownId
                            + " 在 TableBean 中不存在，改用其他方式选表");
                }
            }

            if (tableId <= 0L && !legacy) {
                // 新版（>6.0.23）：TableBean 含 updateTime，退回取最近更新的课表
                try {
                    c = sqLiteDb.rawQuery(
                            "SELECT id, timeTable FROM TableBean ORDER BY CAST(updateTime AS INTEGER) DESC, id DESC LIMIT 1",
                            null);
                    if (c.moveToFirst()) {
                        tableId = c.getLong(0);
                        timeTableId = c.getLong(1);
                        pickedBy = "updateTime";
                    }
                    c.close();
                    c = null;
                } catch (Throwable t) {
                    // 旧库无 updateTime 列
                    downgraded = true;
                    XposedBridge.log(TAG + ": 新版选表失败 -> " + t.getMessage());
                }
            }
            if (tableId <= 0L) {
                c = sqLiteDb.rawQuery("SELECT MAX(tableId) FROM CourseDetailBean", null);
                if (c.moveToFirst()) {
                    tableId = c.getLong(0);
                    if (tableId > 0L) pickedBy = "max_tableId";
                }
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
            String pickLog = "选表 tableId=" + tableId + " timeTable=" + timeTableId
                    + " by=" + pickedBy
                    + " versionCode=" + getWakeupVersionCode(ctx)
                    + " mode=" + (legacy ? "legacy" : "modern") + (downgraded ? "(降级)" : "");
            if (!pickLog.equals(mLastPickLog)) {
                mLastPickLog = pickLog;
                XposedBridge.log(TAG + ": " + pickLog);
            }

            JSONArray sectionTimes = new JSONArray();
            java.util.Set<Integer> sectionNodes = new java.util.HashSet<>();
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
                    sectionNodes.add(c.getInt(0));
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
                boolean hasOwnTime = ownTime == 1 && !isInvalidSectionTime(customStartTime, customEndTime);
                if (hasOwnTime) {
                    course.put("startTime", customStartTime);
                    course.put("endTime", customEndTime);
                } else if (!sectionNodes.contains(startNode) || !sectionNodes.contains(endNode)) {
                    // 既无自定义时间、节次又不在时间表内 → 解析器解析不出上下课时间会丢弃这门课
                    XposedBridge.log(TAG + ": 课程时间无法解析，将被丢弃 course=" + courseName
                            + " day=" + day + " 节次=" + startNode + "-" + endNode
                            + " 时间表节次=" + sectionNodes.size() + " 个 timeTable=" + timeTableId);
                }
                courses.put(course);
                if (endWeek > maxEndWeek) maxEndWeek = endWeek;
            }
            c.close();
            c = null;

            TermConfig termConfig = loadTermConfig(ctx, tableId);
            int totalWeek = termConfig.maxWeek > 0 ? termConfig.maxWeek
                    : (maxEndWeek > 0 ? maxEndWeek : 30);
            int presentWeek = computePresentWeek(termConfig.startDate, termConfig.sundayFirst);

            JSONObject setting = new JSONObject();
            setting.put("presentWeek", presentWeek);
            setting.put("totalWeek", totalWeek);
            setting.put("weekStart", 1);
            setting.put("sectionTimes", sectionTimes);
            setting.put("startDate", termConfig.startDate);
            setting.put("sundayFirst", termConfig.sundayFirst);

            JSONObject data = new JSONObject();
            data.put("setting", setting);
            data.put("courses", courses);

            JSONObject root = new JSONObject();
            root.put("data", data);
            return root.toString();
        } finally {
            if (c != null) c.close();
            if (sqLiteDb != null) sqLiteDb.close();
            // 关库可能触发 WAL checkpoint，写回主库与 -wal 会再次唤起 FileObserver
            mSelfReadUntilMs = System.currentTimeMillis() + 800L;
        }
    }

    /** 读取 WakeUp 当前显示的课表 ID（config.xml / show_table_id），缺失或异常返回 -1。 */
    private long readShownTableId(Context ctx) {
        try {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences(WAKEUP_MAIN_PREFS, Context.MODE_PRIVATE);
            try {
                return sp.getInt("show_table_id", -1);
            } catch (ClassCastException e) {
                return sp.getLong("show_table_id", -1L);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 读取 show_table_id 失败 -> " + t.getMessage());
            return -1L;
        }
    }

    /**
     * 校验课表是否真实存在并取出它的时间表 ID。
     * show_table_id 可能指向已被删除的课表，直接采用会镜像出一份空课表。
     *
     * @return {tableId, timeTableId}，不存在时返回 null
     */
    private long[] queryTableById(SQLiteDatabase db, long tableId) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT id, timeTable FROM TableBean WHERE id = ?",
                    new String[]{String.valueOf(tableId)});
            if (cursor.moveToFirst()) {
                return new long[]{cursor.getLong(0), cursor.getLong(1)};
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 校验 tableId=" + tableId + " 失败 -> " + t.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
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

    /**
     * 按学期开始日期推算当前周序号，不做夹取：学期未开始时 ≤ 0，学期结束后大于总周数，
     * 由消费侧（CourseScheduleParser / MainHook）据此判断学期状态。
     * startDate 缺失时无从推算，退回第 1 周以免误判成学期已结束而停掉所有提醒。
     */
    private int computePresentWeek(String startDate, boolean sundayFirst) {
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
        return (int) Math.floor(diffDays / 7.0d) + 1;
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
