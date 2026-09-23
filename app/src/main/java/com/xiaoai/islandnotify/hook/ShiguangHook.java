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
    /** voiceassist 侧被 MainHook hook 了 onStartCommand 的 Service，用于把已被杀的进程拉起来 */
    private static final String VOICEASSIST_UPLOAD_SERVICE =
            "com.xiaomi.voiceassistant.UploadStateService";
    private static final String DB_NAME = "main_app_database";
    private static final String DATASTORE_NAME = "app_settings.preferences_pb";
    private static final String HOOKED_KEY = "xiaoai.island.shiguang.hooked";
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    // time_slots 分组列的三种形态（探测见 detectSchemaForm）
    private static final int SCHEMA_UNKNOWN = 0;
    /** v5 及以前：time_slots.courseTableId 直接就是课表 ID */
    private static final int SCHEMA_LEGACY = 1;
    /** v6 起：time_slots.timeTableId + course_time_bindings 间接层 */
    private static final int SCHEMA_BINDING = 2;

    private static final String BINDING_TYPE_COMBO = "COMBO";

    /** 目录监听重试上限：目录一直不存在说明用户还没打开过拾光，再等也没用 */
    private static final int MAX_OBSERVER_RETRY = 15;

    private android.os.FileObserver mDbObserver;
    private android.os.FileObserver mStoreObserver;
    private android.os.Handler mHandler;
    private int mObserverRetryCount = 0;
    /** 最近一次观察到的日期（yyyy-MM-dd）：跨天检测的基准，跨天事件与每分钟兜底共用 */
    private volatile String mLastSeenDate = "";
    private final Object mSyncToken = new Object();
    private volatile int mLastPushedHash = 0;
    /** 上一次打印过的 time_slots 形态，避免每次推送都重复刷同一条日志 */
    private volatile int mLastLoggedSchemaForm = Integer.MIN_VALUE;
    /**
     * 需要告诉用户的「库结构不认识」提示；null 表示当前形态可解析。
     *
     * <p>这是本模块与拾光之间唯一的一处「契约」检查：拾光再改一次 time_slots 的分组列
     * （或把课表与作息之间再插一层），这里就会落进 SCHEMA_UNKNOWN。原实现的后果是
     * 静默返回空节次表 → 普通课全部被丢弃 → 岛上只剩自定义时间的课，用户和 2026-09-08
     * 那次 v6 变更一样只能看到「同步成功但课程没了」，而模块自己什么也不说。
     * 现在把这个事实带到用户面前（经 voiceassist 进程弹提示）。
     */
    private volatile String mSchemaWarning = null;
    /** 自身读库产生的文件事件在此时间前一律忽略，避免「读 → 改 -shm/-wal → 再读」自激循环 */
    private volatile long mSelfReadUntilMs = 0L;

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
                        registerDateChangeReceiver(appCtx);
                        tryRegisterObservers(appCtx);
                        postSync(appCtx, 350L, "startup");
                    }
                });
    }

    /**
     * 注册数据库与 DataStore 的文件监听，失败则退避重试。
     *
     * <p>原实现是「目录不存在就 return」，而 hook 注入发生在拾光进程刚创建时，
     * 数据库目录往往还没建（用户还没打开过拾光、或首次安装）。这种一次性尝试一旦
     * 落在目录创建之前，本次进程生命周期内就再也没有任何变更通知，
     * 只能等下一条无关的 startup 事件——表现为「改了课表但岛不动」，且全程无日志。
     */
    private void tryRegisterObservers(Context ctx) {
        registerDbObserver(ctx);
        registerDataStoreObserver(ctx);
        if (mDbObserver != null && mStoreObserver != null) {
            XposedBridge.log(TAG + ": 数据库与 DataStore 监听已建立"
                    + (mObserverRetryCount > 0 ? "（重试 " + mObserverRetryCount + " 次后成功）" : ""));
            // 重试期间可能正好完成了建库/换课表，而那时的变更事件没人监听，补一次同步。
            if (mObserverRetryCount > 0) postSync(ctx, 650L, "observers_ready");
            return;
        }
        if (mObserverRetryCount >= MAX_OBSERVER_RETRY) {
            XposedBridge.log(TAG + ": 文件监听注册失败且已达重试上限 " + MAX_OBSERVER_RETRY
                    + " 次（db=" + (mDbObserver != null) + " datastore=" + (mStoreObserver != null)
                    + "），本次进程内将依赖手动/启动同步，不再重试");
            return;
        }
        mObserverRetryCount++;
        // 首次 2s，之后线性放大：文件监听只是「及时性」优化，读库路径本身不依赖它。
        long delayMs = 2000L * mObserverRetryCount;
        XposedBridge.log(TAG + ": 文件监听未就绪（db=" + (mDbObserver != null)
                + " datastore=" + (mStoreObserver != null) + "），第 " + mObserverRetryCount
                + " 次重试将在 " + delayMs + "ms 后");
        getHandler().postDelayed(() -> tryRegisterObservers(ctx), delayMs);
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

    /**
     * 跨天重算通道。
     *
     * <p>镜像 bean 是「一次性快照」：presentWeek 在推送那一刻算好（computePresentWeek），
     * 生效节次也按推送当日解析（COMBO 组合方案按日期命中不同作息）。原实现只有
     * startup / db_changed / datastore_changed / manual_request 四个同步触发点，
     * 没有任何跨天信号——用户在拾光里不改任何东西，第二天岛上的周次和节次就都是昨天的，
     * 且 hash 不变、推送被去重，连「重新推一次」都不会发生。
     *
     * <p>这里补两类信号，任一先到都会触发重算：
     * <ul>
     *   <li>系统跨天/改时间广播：DATE_CHANGED、TIME_SET、TIMEZONE_CHANGED；</li>
     *   <li>ACTION_TIME_TICK（每分钟一次）作为兜底，覆盖广播被 ROM 拦截的情况。</li>
     * </ul>
     * 两者都只在「日期字符串真的变了」时才推送，所以一天最多触发一次。
     */
    private void registerDateChangeReceiver(Context ctx) {
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_TIME_TICK);
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                checkDateRollover(context, action == null ? "time_signal" : action);
            }
        };
        // 只收系统广播，不对外开放；导出标志在 API 33+ 必填。
        androidx.core.content.ContextCompat.registerReceiver(
                ctx, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        mLastSeenDate = todayDateString();
        XposedBridge.log(TAG + ": 已注册跨天重算监听（date/time/timezone/tick），当前日期 " + mLastSeenDate);
    }

    /**
     * 日期变了就强制重算一次；同样的日期不做任何事。
     * 只有「已知旧日期」才算跨天，否则进程刚起来时会把首次校准误判成跨天。
     */
    private void checkDateRollover(Context ctx, String reason) {
        String today = todayDateString();
        String last = mLastSeenDate;
        if (today.equals(last)) return;
        mLastSeenDate = today;
        if (last == null || last.isEmpty()) return;
        XposedBridge.log(TAG + ": 检测到跨天 " + last + " -> " + today + "（信号 " + reason
                + "），强制重算周次与生效作息");
        postSync(ctx, 500L, "date_rollover:" + today);
    }

    private void registerDbObserver(Context ctx) {
        if (mDbObserver != null) return;
        File dbFile = ctx.getDatabasePath(DB_NAME);
        File dbDir = dbFile == null ? null : dbFile.getParentFile();
        // 目录不存在时不放弃：进程启动早于拾光建库是常态，由 tryRegisterObservers 后台重试。
        if (dbDir == null || !dbDir.exists()) return;
        mDbObserver = new android.os.FileObserver(
                dbDir.getAbsolutePath(),
                android.os.FileObserver.MOVED_TO
                        | android.os.FileObserver.CLOSE_WRITE
                        | android.os.FileObserver.MODIFY) {
            @Override
            public void onEvent(int event, String path) {
                if (path == null || !path.startsWith(DB_NAME)) return;
                // -shm 只是 WAL 的读端索引：只读打开数据库也会写它，
                // 据此触发同步会形成「读 → 改 -shm → 再读」的自激循环。
                if (path.endsWith("-shm")) return;
                if (System.currentTimeMillis() < mSelfReadUntilMs) return;
                postSync(ctx, 650L, "db_changed:" + path);
            }
        };
        mDbObserver.startWatching();
    }

    private void registerDataStoreObserver(Context ctx) {
        if (mStoreObserver != null) return;
        File store = new File(ctx.getFilesDir(), "datastore/" + DATASTORE_NAME);
        File dir = store.getParentFile();
        // 同上：目录不存在交由 tryRegisterObservers 重试，这里不再早退即永久放弃。
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
            mSchemaWarning = null;
            String beanJson = buildWeekCourseBeanFromShiguang(ctx);
            if (beanJson == null || beanJson.isEmpty()) return;
            // 内省：bean 非空却一门课都没有，说明丢弃发生在读库阶段（下面每次丢弃都有日志）。
            // ★ 只记日志、绝不提前 return —— 空课表用户正需要这次推送把岛上的残影清空。
            int courseCount = countCoursesInBean(beanJson);
            if (courseCount == 0) {
                XposedBridge.log(TAG + ": 构建出的镜像不含任何课程，请查上面的课程丢弃日志 reason=" + reason);
            }
            int hash = CourseScheduleParser.stableHash(beanJson);
            // 正常情况下 hash 没变就不重复推送。但这条例外必须放行：库结构不认识时，
            // 如果该课表只含自定义时间的课程（这类课不走生效节次表、画面完全没变），
            // hash 会和上一次推送逐字节相同 —— 若跟着早退，用户就永远收不到提示，
            // 又回到「模块什么也不说」的老样子。多推一次的代价极小（同一份 bean，
            // 下游还会按文案去重），换的是这条提示一定能送达。
            if (hash == mLastPushedHash && mSchemaWarning == null) return;

            // 先用 startService 把可能已被杀的 voiceassist 拉起来（MainHook 的 Service hook
            // 会把它转成包内广播）；广播只能进到运行中的动态接收器，进程不在时会静默丢失。
            boolean started = startVoiceassistService(ctx, beanJson, hash);

            Intent sync = new Intent(ACTION_SHIGUANG_COURSE_SYNC);
            sync.setPackage(TARGET_VOICEASSIST);
            sync.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            sync.putExtra("bean_json", beanJson);
            sync.putExtra("hash", hash);
            // 库结构不认识时顺带把原因交给 voiceassist 进程：hook 侧是只读的
            // （XSharedPreferences.edit() 直接抛异常），也没有 Toast 通道，
            // 只能在这次必然会发生的推送里捎带一条提示。
            if (mSchemaWarning != null) sync.putExtra("schema_warning", mSchemaWarning);
            ctx.sendBroadcast(sync);

            // 只有 startService 成功才算确定送达；否则不记账，留给下次事件重试，
            // 避免推送丢失后镜像永久停留在旧数据上。
            if (started) mLastPushedHash = hash;
            XposedBridge.log(TAG + ": 已推送拾光课程镜像 -> voiceassist reason=" + reason
                    + " hash=" + hash + " service=" + started + " courses=" + courseCount);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": syncAndPush 失败 -> " + t);
        }
    }

    /** 统计镜像里的课程数，仅用于诊断日志；解析失败返回 -1（不干扰推送决策）。 */
    private static int countCoursesInBean(String beanJson) {
        try {
            JSONArray courses = new JSONObject(beanJson).optJSONObject("data").optJSONArray("courses");
            return courses == null ? 0 : courses.length();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 通过 startService 投递并顺带拉起 voiceassist 进程，成功返回 true。 */
    private boolean startVoiceassistService(Context ctx, String beanJson, int hash) {
        try {
            Intent svc = new Intent(ACTION_SHIGUANG_COURSE_SYNC);
            svc.setClassName(TARGET_VOICEASSIST, VOICEASSIST_UPLOAD_SERVICE);
            svc.putExtra("bean_json", beanJson);
            svc.putExtra("hash", hash);
            // 这条才是「进程被杀也能送达」的可靠通路（MainHook 的 Service hook 会把全部
            // extras 原样转成包内广播），提示同样要跟着走。
            if (mSchemaWarning != null) svc.putExtra("schema_warning", mSchemaWarning);
            return ctx.startService(svc) != null;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": startService 拉起 voiceassist 失败 -> " + t.getMessage());
            return false;
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
            int droppedSlots = 0;
            for (Map.Entry<Integer, String[]> e : normalSlots.entrySet()) {
                int sec = e.getKey();
                String start = e.getValue()[0];
                String end = e.getValue()[1];
                if (isInvalidSectionTime(start, end)) {
                    droppedSlots++;
                    continue;
                }
                JSONObject st = new JSONObject();
                st.put("i", sec);
                st.put("s", start);
                st.put("e", end);
                sectionTimes.put(st);
                seenSections.add(sec);
            }
            // 节次表是整个节次解析的地基：为空就等于所有普通课都会被丢弃，所以无条件记录。
            XposedBridge.log(TAG + ": 生效节次 读取 " + normalSlots.size() + " 条，可用 "
                    + seenSections.size() + " 条，时间非法丢弃 " + droppedSlots + " 条（课表 "
                    + currentTableId + "）");
            if (normalSlots.isEmpty()) {
                XposedBridge.log(TAG + ": 生效节次为空，所有普通课程都会被跳过；"
                        + "若拾光里作息正常，请检查上面的 [schema] 日志");
            }

            int syntheticSec = 1000;
            int droppedInvalid = 0;
            int droppedNoWeek = 0;
            int droppedNoSlot = 0;
            int keptCourses = 0;
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
                if (name.isEmpty() || day < 1 || day > 7) {
                    droppedInvalid++;
                    XposedBridge.log(TAG + ": 课程记录字段非法（name=\"" + name + "\" day=" + day
                            + "），丢弃该课");
                    continue;
                }

                List<Integer> weeks = weeksByCourse.get(courseId);
                if (weeks == null || weeks.isEmpty()) {
                    droppedNoWeek++;
                    XposedBridge.log(TAG + ": 课程 " + name + " 没有任何周次记录，丢弃该课");
                    continue;
                }
                String weeksSpec = toWeeksSpec(weeks);
                if (weeksSpec.isEmpty()) {
                    droppedNoWeek++;
                    XposedBridge.log(TAG + ": 课程 " + name + " 的周次文本为空，丢弃该课");
                    continue;
                }

                String sectionsSpec;
                String customStart = "";
                String customEnd = "";
                if (isCustom) {
                    customStart = safeStr(c.getString(8));
                    customEnd = safeStr(c.getString(9));
                    if (isInvalidSectionTime(customStart, customEnd)) {
                        droppedNoSlot++;
                        XposedBridge.log(TAG + ": 课程 " + name + " 的自定义时间 \""
                                + customStart + "-" + customEnd + "\" 非法，丢弃该课");
                        continue;
                    }
                    // 优先落到时间上覆盖它的真实节次：自动叫醒的规则是按真实节次配置的，
                    // 合成节次号查不到任何规则，这类课就永远参与不了叫醒。
                    int matchedSec = matchSectionByTime(normalSlots, customStart);
                    if (matchedSec > 0) {
                        sectionsSpec = String.valueOf(matchedSec);
                    } else {
                        String slotKey = customStart + "|" + customEnd;
                        Integer secIdx = customTimeToSec.get(slotKey);
                        if (secIdx == null) {
                            while (seenSections.contains(syntheticSec)) syntheticSec++;
                            secIdx = syntheticSec++;
                            customTimeToSec.put(slotKey, secIdx);
                            JSONObject st = new JSONObject();
                            st.put("i", secIdx);
                            st.put("s", customStart);
                            st.put("e", customEnd);
                            sectionTimes.put(st);
                            seenSections.add(secIdx);
                        }
                        sectionsSpec = String.valueOf(secIdx);
                        XposedBridge.log(TAG + ": 自定义时间 " + customStart + "-" + customEnd
                                + " 不落在任何节次区间内，使用合成节次 " + secIdx
                                + "（该课不参与自动叫醒）course=" + name);
                    }
                } else {
                    if (c.isNull(5) || c.isNull(6) || c.getInt(5) <= 0 || c.getInt(6) <= 0) {
                        droppedNoSlot++;
                        XposedBridge.log(TAG + ": 课程 " + name + " 没有有效节次号，丢弃该课"
                                + "（startSection/endSection 为空或非正）");
                        continue;
                    }
                    int startSec = c.getInt(5);
                    int endSec = c.getInt(6);
                    int minSec = Math.min(startSec, endSec);
                    int maxSec = Math.max(startSec, endSec);
                    if (!normalSlots.containsKey(minSec) || !normalSlots.containsKey(maxSec)) {
                        droppedNoSlot++;
                        // 本次故障的放大器：节次表拿不到时，普通课会在这里被整批静默丢弃。
                        XposedBridge.log(TAG + ": 课程 " + name + " 的节次 " + minSec + "-" + maxSec
                                + " 不在生效节次表（" + normalSlots.size() + " 条）中，丢弃该课");
                        continue;
                    }
                    sectionsSpec = minSec == maxSec ? String.valueOf(minSec) : (minSec + "-" + maxSec);
                }

                JSONObject course = new JSONObject();
                course.put("day", day);
                course.put("name", name);
                course.put("teacher", teacher);
                course.put("position", position);
                course.put("sections", sectionsSpec);
                course.put("weeks", weeksSpec);
                // 自定义时间显式写入：解析器优先采用它，映射到真实节次后也不会被该节次的默认时间覆盖。
                if (isCustom) {
                    course.put("startTime", customStart);
                    course.put("endTime", customEnd);
                }
                courses.put(course);
                keptCourses++;
            }
            c.close();
            c = null;

            XposedBridge.log(TAG + ": 课程 读取 " + (keptCourses + droppedInvalid + droppedNoWeek + droppedNoSlot)
                    + " 门，保留 " + keptCourses + " 门，丢弃 " + (droppedInvalid + droppedNoWeek + droppedNoSlot)
                    + " 门（字段非法 " + droppedInvalid + " / 无周次 " + droppedNoWeek
                    + " / 节次缺失 " + droppedNoSlot + "）");

            int totalWeek = config.semesterTotalWeeks > 0 ? config.semesterTotalWeeks : (maxWeek > 0 ? maxWeek : 30);
            int presentWeek = computePresentWeek(config.semesterStartDate, config.sundayFirst);

            JSONObject setting = new JSONObject();
            setting.put("presentWeek", presentWeek);
            setting.put("totalWeek", totalWeek);
            setting.put("weekStart", 1);
            setting.put("sectionTimes", sectionTimes);
            setting.put("startDate", config.semesterStartDate);
            setting.put("sundayFirst", config.sundayFirst);

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

    private String resolveCurrentTableId(Context ctx, SQLiteDatabase db) {
        String fromStore = readCurrentTableIdFromDataStore(ctx);
        if (fromStore != null && !fromStore.isEmpty()) return fromStore;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT id FROM course_tables ORDER BY createdAt DESC LIMIT 1", null);
            if (c.moveToFirst()) {
                String fallback = safeStr(c.getString(0));
                XposedBridge.log(TAG + ": DataStore 未取到当前课表，退回「最近创建」的课表 " + fallback
                        + "（可能与用户在拾光里选中的课表不一致）");
                return fallback;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 读取当前课表失败（DataStore 与 course_tables 都不可用）-> " + t);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private String readCurrentTableIdFromDataStore(Context ctx) {
        File file = new File(ctx.getFilesDir(), "datastore/" + DATASTORE_NAME);
        if (!file.exists()) {
            XposedBridge.log(TAG + ": DataStore 文件不存在 " + file.getAbsolutePath()
                    + "，将退回最近创建的课表");
            return null;
        }
        FileInputStream fis = null;
        try {
            byte[] buf = new byte[(int) Math.min(file.length(), 64 * 1024L)];
            fis = new FileInputStream(file);
            int read = fis.read(buf);
            if (read <= 0) {
                XposedBridge.log(TAG + ": DataStore 文件为空，将退回最近创建的课表");
                return null;
            }
            String raw = new String(buf, 0, read, StandardCharsets.ISO_8859_1);
            int idx = raw.indexOf("current_course_table_id");
            String scope = idx >= 0 ? raw.substring(idx, Math.min(raw.length(), idx + 200)) : raw;
            Matcher m = UUID_PATTERN.matcher(scope);
            if (m.find()) return m.group();
            m = UUID_PATTERN.matcher(raw);
            if (m.find()) {
                // 明文启发式：窗口内没找到 key，退而取全文第一个 UUID，可能不是用户选中的课表。
                XposedBridge.log(TAG + ": DataStore 里未定位到 current_course_table_id，"
                        + "退取全文首个 UUID " + m.group() + "（可能选错课表）");
                return m.group();
            }
            XposedBridge.log(TAG + ": DataStore 里没有任何 UUID 可用，将退回最近创建的课表");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 解析 DataStore 失败，将退回最近创建的课表 -> " + t);
        } finally {
            try {
                if (fis != null) fis.close();
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": 关闭 DataStore 流失败 -> " + t);
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
                if (startDate.isEmpty()) {
                    XposedBridge.log(TAG + ": 课表 " + tableId + " 没有学期开始日期，"
                            + "当前周将按第 1 周处理，周次判断可能不准");
                }
                return new TableConfig(startDate, totalWeeks, sundayFirst);
            }
            XposedBridge.log(TAG + ": course_table_config 里没有课表 " + tableId
                    + " 的配置行，总周数将退回课程最大周，周次判断可能不准");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 读取课表配置失败 tableId=" + tableId + " -> " + t);
        } finally {
            if (c != null) c.close();
        }
        return new TableConfig("", 0, false);
    }

    /**
     * 读取「该课表当前生效」的作息节次。
     *
     * <p>v5 及以前：time_slots.courseTableId 就是课表 ID，直连即可。
     * v6 起（拾光 2.0.1 / versionCode 35）：time_slots 改按 timeTableId 分组，课表与作息之间
     * 多了 course_time_bindings 间接层（专属作息 / 公共作息 SINGLE / 组合方案 COMBO），
     * 必须按绑定解析才能拿到生效值 —— 直接把 courseTableId 当 timeTableId 用会读到残留的专属作息，
     * 得到「不为空、不报错、但时间是错的」结果。
     *
     * <p>未知形态不抛异常也不静默返回残缺数据，而是返回空表并留下日志。
     */
    private Map<Integer, String[]> loadTimeSlots(SQLiteDatabase db, String courseTableId) {
        int form = detectSchemaForm(db);
        if (form != mLastLoggedSchemaForm) {
            mLastLoggedSchemaForm = form;
            XposedBridge.log(TAG + ": [schema] time_slots 形态=" + schemaFormName(form)
                    + " user_version=" + readUserVersion(db));
        }
        if (form == SCHEMA_LEGACY) {
            mSchemaWarning = null;
            return querySlotsByLegacyColumn(db, courseTableId);
        }
        if (form == SCHEMA_BINDING) {
            mSchemaWarning = null;
            return loadBoundTimeSlots(db, courseTableId);
        }
        XposedBridge.log(TAG + ": [schema] time_slots 既没有 courseTableId 也没有 timeTableId 列，"
                + "无法解析生效作息：本次推送不含任何普通节次，相关课程会被跳过");
        // 唯一「模块读不懂拾光的库」的情况：原实现到此为止只有一行日志，用户在岛上
        // 看到的是课程凭空消失。把原因带出去，由 voiceassist 进程提示用户。
        mSchemaWarning = "拾光课程表的数据库结构已变更，本模块暂时无法读取作息时间"
                + "（会缺少普通课程）。请等待模块更新。";
        return new HashMap<>();
    }

    private static String schemaFormName(int form) {
        if (form == SCHEMA_LEGACY) return "v5-and-earlier(courseTableId)";
        if (form == SCHEMA_BINDING) return "v6+(timeTableId+course_time_bindings)";
        return "unknown";
    }

    /**
     * 探测 time_slots 的分组列形态。用 PRAGMA table_info（各版本 SQLite 都支持）而不是
     * pragma_table_info 表值函数（需 3.16+），也不用 room_master_table.identity_hash
     * —— 迁移库的 identity_hash 仍是 v5 的值，只有 user_version 会更新到 6。
     */
    private int detectSchemaForm(SQLiteDatabase db) {
        boolean legacyColumn = hasColumn(db, "time_slots", "courseTableId");
        boolean bindingColumn = hasColumn(db, "time_slots", "timeTableId");
        if (bindingColumn && !legacyColumn) return SCHEMA_BINDING;
        if (legacyColumn) return SCHEMA_LEGACY;
        return SCHEMA_UNKNOWN;
    }

    private boolean hasColumn(SQLiteDatabase db, String table, String column) {
        Cursor c = null;
        try {
            c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            int nameIdx = c.getColumnIndex("name");
            if (nameIdx < 0) return false;
            while (c.moveToNext()) {
                if (column.equalsIgnoreCase(safeStr(c.getString(nameIdx)))) return true;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [schema] 探测 " + table + "." + column + " 失败 -> " + t);
        } finally {
            if (c != null) c.close();
        }
        return false;
    }

    private int readUserVersion(SQLiteDatabase db) {
        Cursor c = null;
        try {
            c = db.rawQuery("PRAGMA user_version", null);
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [schema] 读取 user_version 失败 -> " + t);
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    private Map<Integer, String[]> querySlotsByLegacyColumn(SQLiteDatabase db, String courseTableId) {
        return querySlots(db,
                "SELECT number, startTime, endTime FROM time_slots WHERE courseTableId = ? ORDER BY number ASC",
                new String[]{courseTableId});
    }

    private Map<Integer, String[]> querySlotsByTimeTableId(SQLiteDatabase db, String timeTableId) {
        return querySlots(db,
                "SELECT number, startTime, endTime FROM time_slots WHERE timeTableId = ? ORDER BY number ASC",
                new String[]{timeTableId});
    }

    private Map<Integer, String[]> querySlots(SQLiteDatabase db, String sql, String[] args) {
        Map<Integer, String[]> slots = new HashMap<>();
        Cursor c = null;
        try {
            c = db.rawQuery(sql, args);
            while (c.moveToNext()) {
                int number = c.getInt(0);
                String start = safeStr(c.getString(1));
                String end = safeStr(c.getString(2));
                slots.put(number, new String[]{start, end});
            }
        } catch (Throwable t) {
            // 这里曾经是 catch (Throwable ignored)：v6 改名后查询必然失败，异常被吞掉，
            // 上层只看到「节次表为空」，于是把所有普通课程静默丢弃且日志一片空白。
            XposedBridge.log(TAG + ": [schema] 查询作息节次失败，本次将缺少普通节次 -> " + t);
        } finally {
            if (c != null) c.close();
        }
        return slots;
    }

    /** v6：先看课表绑定到哪个作息，再读那个作息的节次。 */
    private Map<Integer, String[]> loadBoundTimeSlots(SQLiteDatabase db, String courseTableId) {
        Binding binding = loadBinding(db, courseTableId);
        if (binding == null) {
            // 无绑定是正常态：新建课表与迁移时都会为课表写入专属作息，其 ID 就等于课表 ID。
            return querySlotsByTimeTableId(db, courseTableId);
        }
        if (BINDING_TYPE_COMBO.equalsIgnoreCase(binding.targetType)) {
            return loadComboEffectiveSlots(db, courseTableId, binding.targetId, todayDateString());
        }
        String targetId = binding.targetId.isEmpty() ? courseTableId : binding.targetId;
        Map<Integer, String[]> slots = querySlotsByTimeTableId(db, targetId);
        XposedBridge.log(TAG + ": [schema] 课表 " + courseTableId + " 绑定 "
                + binding.targetType + " 作息 " + targetId + "，生效节次 " + slots.size() + " 条");
        return slots;
    }

    /**
     * 组合方案生效节次：以基准作息为骨架，按「今天」命中的规则把目标作息的时间对齐过来。
     * 与拾光 TimeScheduleRepository.getEffectiveTimeSlotsOnce 的 COMBO 分支逐条对应。
     */
    private Map<Integer, String[]> loadComboEffectiveSlots(SQLiteDatabase db, String courseTableId,
                                                           String comboId, String dateStr) {
        String baseTableId = loadComboBaseTableId(db, comboId);
        // baseTimeTableId 为空 = 拾光文档里的「动态专属作息」，骨架就是该课表自己的专属作息
        if (baseTableId == null || baseTableId.isEmpty()) baseTableId = courseTableId;
        Map<Integer, String[]> baseSlots = querySlotsByTimeTableId(db, baseTableId);

        String matchedTargetId = matchComboTargetTableId(db, comboId, dateStr);
        if (matchedTargetId == null || matchedTargetId.isEmpty() || matchedTargetId.equals(baseTableId)) {
            XposedBridge.log(TAG + ": [schema] 组合作息 " + comboId + " 在 " + dateStr
                    + " 未命中规则，取基准作息 " + baseTableId + " 的 " + baseSlots.size() + " 条节次");
            return baseSlots;
        }
        Map<Integer, String[]> targetSlots = querySlotsByTimeTableId(db, matchedTargetId);
        Map<Integer, String[]> aligned = alignTimeSlots(baseSlots, targetSlots);
        XposedBridge.log(TAG + ": [schema] 组合作息 " + comboId + " 在 " + dateStr + " 命中 "
                + matchedTargetId + "：基准 " + baseSlots.size() + " 条，生效 " + aligned.size() + " 条");
        return aligned;
    }

    /**
     * 匹配日期对应的组合作息规则，无命中返回 null（注意：无命中不等于空作息，调用方会回退基准骨架）。
     * 日期是 "yyyy-MM-dd" 定长串，逐字符比较即日期比较且端点包含，与拾光的
     * `currentDateStr in it.startDate..it.endDate` 等价；rowid 序 = 插入序 = UI 展示序，
     * 与拾光 DAO 无 ORDER BY 时的返回序一致。
     */
    private String matchComboTargetTableId(SQLiteDatabase db, String comboId, String dateStr) {
        if (comboId == null || comboId.isEmpty()) return null;
        if (dateStr == null || dateStr.isEmpty()) return null;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT targetTimeTableId, startDate, endDate FROM time_table_combo_rules "
                    + "WHERE comboId = ? ORDER BY rowid ASC", new String[]{comboId});
            while (c.moveToNext()) {
                String targetId = safeStr(c.getString(0));
                String start = normalizeStartDate(safeStr(c.getString(1)));
                String end = normalizeStartDate(safeStr(c.getString(2)));
                if (targetId.isEmpty()) continue;
                // 反向区间（start > end）在拾光里永远是死规则，这里同样匹配不到，保持行为一致
                if (start.compareTo(dateStr) <= 0 && dateStr.compareTo(end) <= 0) return targetId;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [schema] 读取组合作息规则失败 combo=" + comboId
                    + "，本次退回基准作息 -> " + t);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /**
     * 节次对齐：以 baseSlots 的结构为骨架。两边都有的节次取目标作息的时间；
     * 只在基准里的节次保留基准原时间（不补号、也不伪造时间）；只在目标里的节次被丢弃。
     * 与拾光 alignTimeSlots 语义一致 —— 基准里的空洞是 v6 的合法状态，不能在这里填平。
     */
    private static Map<Integer, String[]> alignTimeSlots(Map<Integer, String[]> baseSlots,
                                                         Map<Integer, String[]> targetSlots) {
        if (baseSlots.isEmpty()) return targetSlots;
        if (targetSlots.isEmpty()) return baseSlots;
        Map<Integer, String[]> aligned = new HashMap<>();
        for (Map.Entry<Integer, String[]> e : baseSlots.entrySet()) {
            String[] target = targetSlots.get(e.getKey());
            aligned.put(e.getKey(), target != null ? target : e.getValue());
        }
        return aligned;
    }

    /** 生效作息的解析口径是「今天」（与拾光 UI 一致），COMBO 规则按这个日期匹配。 */
    private static String todayDateString() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
    }

    private String loadComboBaseTableId(SQLiteDatabase db, String comboId) {
        if (comboId == null || comboId.isEmpty()) return null;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT baseTimeTableId FROM time_table_combos WHERE id = ? LIMIT 1",
                    new String[]{comboId});
            if (c.moveToFirst() && !c.isNull(0)) return safeStr(c.getString(0));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [schema] 读取组合作息基准作息失败 combo=" + comboId + " -> " + t);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private Binding loadBinding(SQLiteDatabase db, String courseTableId) {
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT targetType, targetId FROM course_time_bindings WHERE courseTableId = ? LIMIT 1",
                    new String[]{courseTableId});
            if (c.moveToFirst()) {
                return new Binding(safeStr(c.getString(0)), safeStr(c.getString(1)));
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [schema] 读取课表作息绑定失败 courseTableId=" + courseTableId
                    + "，本次退回专属作息 -> " + t);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /**
     * 找出时间上覆盖 startTime 的真实节次编号（节次开始 ≤ startTime &lt; 节次结束），找不到返回 -1。
     * 多个节次同时覆盖时取编号最小的。
     */
    private static int matchSectionByTime(Map<Integer, String[]> slots, String startTime) {
        int target = toMinutes(startTime);
        if (target < 0 || slots == null) return -1;
        int best = -1;
        for (Map.Entry<Integer, String[]> e : slots.entrySet()) {
            int slotStart = toMinutes(e.getValue()[0]);
            int slotEnd = toMinutes(e.getValue()[1]);
            if (slotStart < 0 || slotEnd <= slotStart) continue;
            if (target < slotStart || target >= slotEnd) continue;
            int sec = e.getKey();
            if (best < 0 || sec < best) best = sec;
        }
        return best;
    }

    /** "HH:mm" → 当日分钟数，格式非法返回 -1。 */
    private static int toMinutes(String hhmm) {
        if (hhmm == null || hhmm.length() != 5 || hhmm.charAt(2) != ':') return -1;
        try {
            int h = Integer.parseInt(hhmm.substring(0, 2));
            int m = Integer.parseInt(hhmm.substring(3, 5));
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Throwable ignored) {
            return -1;
        }
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

    /**
     * 按学期开始日期推算当前周序号，不做夹取：学期未开始时 ≤ 0，学期结束后大于总周数，
     * 由消费侧（CourseScheduleParser / MainHook）据此判断学期状态。
     * startDate 缺失时无从推算，退回第 1 周以免误判成学期已结束而停掉所有提醒。
     */
    private int computePresentWeek(String startDate, boolean sundayFirst) {
        if (startDate == null || startDate.isEmpty()) return 1;
        int[] ymd = parseYmd(startDate);
        if (ymd == null) {
            XposedBridge.log(TAG + ": 学期开始日期 \"" + startDate
                    + "\" 无法解析，当前周退回第 1 周；提醒可能按错误的周次调度");
            return 1;
        }

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

    private static boolean isInvalidSectionTime(String start, String end) {
        if (start.isEmpty() || end.isEmpty()) return true;
        if ("00:00".equals(start) || "00:00".equals(end)) return true;
        return false;
    }

    private static String safeStr(String value) {
        return value == null ? "" : value;
    }

    private static final class Binding {
        final String targetType;
        final String targetId;

        Binding(String targetType, String targetId) {
            this.targetType = targetType == null ? "" : targetType;
            this.targetId = targetId == null ? "" : targetId;
        }
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
