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

    private android.os.FileObserver mDbObserver;
    private android.os.FileObserver mStoreObserver;
    private android.os.Handler mHandler;
    private final Object mSyncToken = new Object();
    private volatile int mLastPushedHash = 0;
    /** 上一次打印过的 time_slots 形态，避免每次推送都重复刷同一条日志 */
    private volatile int mLastLoggedSchemaForm = Integer.MIN_VALUE;
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

            // 先用 startService 把可能已被杀的 voiceassist 拉起来（MainHook 的 Service hook
            // 会把它转成包内广播）；广播只能进到运行中的动态接收器，进程不在时会静默丢失。
            boolean started = startVoiceassistService(ctx, beanJson, hash);

            Intent sync = new Intent(ACTION_SHIGUANG_COURSE_SYNC);
            sync.setPackage(TARGET_VOICEASSIST);
            sync.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            sync.putExtra("bean_json", beanJson);
            sync.putExtra("hash", hash);
            ctx.sendBroadcast(sync);

            // 只有 startService 成功才算确定送达；否则不记账，留给下次事件重试，
            // 避免推送丢失后镜像永久停留在旧数据上。
            if (started) mLastPushedHash = hash;
            XposedBridge.log(TAG + ": 已推送拾光课程镜像 -> voiceassist reason=" + reason
                    + " hash=" + hash + " service=" + started);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": syncAndPush 失败 -> " + t.getMessage());
        }
    }

    /** 通过 startService 投递并顺带拉起 voiceassist 进程，成功返回 true。 */
    private boolean startVoiceassistService(Context ctx, String beanJson, int hash) {
        try {
            Intent svc = new Intent(ACTION_SHIGUANG_COURSE_SYNC);
            svc.setClassName(TARGET_VOICEASSIST, VOICEASSIST_UPLOAD_SERVICE);
            svc.putExtra("bean_json", beanJson);
            svc.putExtra("hash", hash);
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
                String customStart = "";
                String customEnd = "";
                if (isCustom) {
                    customStart = safeStr(c.getString(8));
                    customEnd = safeStr(c.getString(9));
                    if (isInvalidSectionTime(customStart, customEnd)) continue;
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
                // 自定义时间显式写入：解析器优先采用它，映射到真实节次后也不会被该节次的默认时间覆盖。
                if (isCustom) {
                    course.put("startTime", customStart);
                    course.put("endTime", customEnd);
                }
                courses.put(course);
            }
            c.close();
            c = null;

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
        if (form == SCHEMA_LEGACY) return querySlotsByLegacyColumn(db, courseTableId);
        if (form == SCHEMA_BINDING) return loadBoundTimeSlots(db, courseTableId);
        XposedBridge.log(TAG + ": [schema] time_slots 既没有 courseTableId 也没有 timeTableId 列，"
                + "无法解析生效作息：本次推送不含任何普通节次，相关课程会被跳过");
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
        } catch (Throwable ignored) {
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
