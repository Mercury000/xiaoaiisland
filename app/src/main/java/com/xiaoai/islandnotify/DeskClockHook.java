package com.xiaoai.islandnotify;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.xiaoai.islandnotify.modernhook.XC_MethodHook;
import com.xiaoai.islandnotify.modernhook.XposedBridge;

import static com.xiaoai.islandnotify.modernhook.XposedHelpers.findAndHookMethod;

/**
 * Hook 原生时钟应用（com.android.deskclock），
 * 在其进程内静默创建/删除系统闹钟，用于「自动叫醒」功能。
 * <p>
 * 通信方式：MainHook（voiceassist 进程）发送广播
 * {@link #ACTION_SCHEDULE_CLOCK_ALARMS} 给 deskclock 进程，
 * 本类在 deskclock 进程内接收后通过反射操作 AlarmHelper / Alarm 类。
 */
public class DeskClockHook {

    private static final String TAG           = "IslandNotifyDesk";
    private static final String DESKCLOCK_PKG = "com.android.deskclock";

    /** 由 MainHook 发出、DeskClockHook 接收的叫醒闹钟调度广播 */
    static final String ACTION_SCHEDULE_CLOCK_ALARMS =
            "com.xiaoai.islandnotify.ACTION_SCHEDULE_CLOCK_ALARMS";

    /** 存储我方已创建闹钟 ID 列表（逗号分隔 long）的 SP 文件名 */
    private static final String SP_WAKEUP     = "island_wakeup";
    /** 存储创建的闹钟 ID 列表的 SP 键 */
    private static final String KEY_ALARM_IDS = "created_alarm_ids";

    // ─────────────────────────────────────────────────────────────────────────
    // Xposed 入口
    // ─────────────────────────────────────────────────────────────────────────

    public void handleLoadPackage(String packageName, ClassLoader classLoader) throws Throwable {
        if (!DESKCLOCK_PKG.equals(packageName)) return;

        // 在 deskclock Application 启动时注册广播接收器
        findAndHookMethod("android.app.Application", classLoader,
                "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context ctx = (Application) param.thisObject;
                        registerScheduleReceiver(ctx, classLoader);
                        XposedBridge.log(TAG + ": 叫醒闹钟接收器已注册");
                    }
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 广播注册
    // ─────────────────────────────────────────────────────────────────────────

    private void registerScheduleReceiver(Context ctx, ClassLoader cl) {
        IntentFilter filter = new IntentFilter(ACTION_SCHEDULE_CLOCK_ALARMS);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (!ACTION_SCHEDULE_CLOCK_ALARMS.equals(action)) return;
                handleSchedule(context, intent, cl);
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 调度主流程
    // ─────────────────────────────────────────────────────────────────────────

    private void handleSchedule(Context ctx, Intent intent, ClassLoader cl) {
        // 1. 无论任何情况先删除旧闹钟
        deletePreviousAlarms(ctx, cl);

        if (intent.getBooleanExtra("clear_only", false)) {
            XposedBridge.log(TAG + ": 叫醒闹钟已全部清除（clear_only）");
            return;
        }

        String beanJson = intent.getStringExtra("bean_json");
        if (beanJson == null || beanJson.isEmpty()) {
            XposedBridge.log(TAG + ": 无课程数据，跳过叫醒调度");
            return;
        }

        List<Long> createdIds = new ArrayList<>();

        try {
            org.json.JSONObject root    = new org.json.JSONObject(beanJson);
            org.json.JSONObject data    = root.getJSONObject("data");
            org.json.JSONObject setting = data.getJSONObject("setting");
            int currentWeek = setting.optInt("presentWeek", 0);
            org.json.JSONArray courses  = data.getJSONArray("courses");

            // 今天是星期几（1=周一…7=周日，与课表字段 day 一致）
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int calDay      = cal.get(java.util.Calendar.DAY_OF_WEEK);
            int calTodayDay = (calDay == java.util.Calendar.SUNDAY) ? 7 : (calDay - 1);
            // 若 MainHook 已处理节假日/调休，使用传入的覆盖值
            int todayDay    = intent.getIntExtra("today_day_override",    calTodayDay);
            int weekOvr     = intent.getIntExtra("current_week_override", -1);
            if (weekOvr > 0) currentWeek = weekOvr;

            boolean morningEnabled   = intent.getBooleanExtra("morning_enabled",   false);
            boolean afternoonEnabled = intent.getBooleanExtra("afternoon_enabled", false);
            int morningLastSec    = intent.getIntExtra("morning_last_sec",    4);
            int afternoonFirstSec = intent.getIntExtra("afternoon_first_sec", 5);

            // 找今日上午/下午第一节课（sections[0] 最小值）
            int    firstMorningSec   = Integer.MAX_VALUE; String morningName   = "";
            int    firstAfternoonSec = Integer.MAX_VALUE; String afternoonName = "";

            for (int i = 0; i < courses.length(); i++) {
                org.json.JSONObject course = courses.getJSONObject(i);
                if (course.getInt("day") != todayDay) continue;
                boolean inWeek = false;
                for (String w : course.optString("weeks", "").split(",")) {
                    try { if (Integer.parseInt(w.trim()) == currentWeek) { inWeek = true; break; } }
                    catch (NumberFormatException ignored) {}
                }
                if (!inWeek) continue;
                String[] secs = course.optString("sections", "").split(",");
                if (secs.length == 0 || secs[0].trim().isEmpty()) continue;
                int firstSec;
                try { firstSec = Integer.parseInt(secs[0].trim()); }
                catch (NumberFormatException e) { continue; }
                String courseName = course.optString("name", "");
                // 上午：找所有 sections[0] ≤ morningLastSec 中最小的
                if (morningEnabled && firstSec <= morningLastSec && firstSec < firstMorningSec) {
                    firstMorningSec = firstSec;
                    morningName     = courseName;
                }
                // 下午：找所有 sections[0] ≥ afternoonFirstSec 中最小的
                if (afternoonEnabled && firstSec >= afternoonFirstSec && firstSec < firstAfternoonSec) {
                    firstAfternoonSec = firstSec;
                    afternoonName     = courseName;
                }
            }

            long nowMs = System.currentTimeMillis();

            // 2. 上午：用 firstMorningSec 在规则列表里查时间
            if (morningEnabled && firstMorningSec != Integer.MAX_VALUE) {
                String rulesJson = intent.getStringExtra("morning_rules_json");
                if (rulesJson != null && !rulesJson.isEmpty()) {
                    try {
                        org.json.JSONArray rules = new org.json.JSONArray(rulesJson);
                        for (int r = 0; r < rules.length(); r++) {
                            org.json.JSONObject rule = rules.getJSONObject(r);
                            if (rule.getInt("sec") == firstMorningSec) {
                                int hour = rule.getInt("hour"); int minute = rule.getInt("minute");
                                java.util.Calendar ac = java.util.Calendar.getInstance();
                                ac.set(java.util.Calendar.HOUR_OF_DAY, hour);
                                ac.set(java.util.Calendar.MINUTE,      minute);
                                ac.set(java.util.Calendar.SECOND,      0);
                                ac.set(java.util.Calendar.MILLISECOND, 0);
                                if (ac.getTimeInMillis() > nowMs) {
                                    long id = createAlarm(ctx, cl, hour, minute, "课程提醒：" + morningName);
                                    if (id > 0) createdIds.add(id);
                                } else {
                                    XposedBridge.log(TAG + ": 上午叫醒时间已过 " + hour + ":" +
                                            String.format(java.util.Locale.getDefault(), "%02d", minute) + "，跳过");
                                }
                                break;
                            }
                        }
                    } catch (org.json.JSONException e) {
                        XposedBridge.log(TAG + ": morning_rules_json 解析失败 → " + e);
                    }
                }
            }

            // 3. 下午：用 firstAfternoonSec 在规则列表里查时间
            if (afternoonEnabled && firstAfternoonSec != Integer.MAX_VALUE) {
                String rulesJson = intent.getStringExtra("afternoon_rules_json");
                if (rulesJson != null && !rulesJson.isEmpty()) {
                    try {
                        org.json.JSONArray rules = new org.json.JSONArray(rulesJson);
                        for (int r = 0; r < rules.length(); r++) {
                            org.json.JSONObject rule = rules.getJSONObject(r);
                            if (rule.getInt("sec") == firstAfternoonSec) {
                                int hour = rule.getInt("hour"); int minute = rule.getInt("minute");
                                java.util.Calendar ac = java.util.Calendar.getInstance();
                                ac.set(java.util.Calendar.HOUR_OF_DAY, hour);
                                ac.set(java.util.Calendar.MINUTE,      minute);
                                ac.set(java.util.Calendar.SECOND,      0);
                                ac.set(java.util.Calendar.MILLISECOND, 0);
                                if (ac.getTimeInMillis() > nowMs) {
                                    long id = createAlarm(ctx, cl, hour, minute, "课程提醒：" + afternoonName);
                                    if (id > 0) createdIds.add(id);
                                } else {
                                    XposedBridge.log(TAG + ": 下午叫醒时间已过 " + hour + ":" +
                                            String.format(java.util.Locale.getDefault(), "%02d", minute) + "，跳过");
                                }
                                break;
                            }
                        }
                    } catch (org.json.JSONException e) {
                        XposedBridge.log(TAG + ": afternoon_rules_json 解析失败 → " + e);
                    }
                }
            }

        } catch (Throwable e) {
            XposedBridge.log(TAG + ": handleSchedule 课程解析失败 → " + e);
        }

        storeAlarmIds(ctx, createdIds);
        XposedBridge.log(TAG + ": 叫醒闹钟调度完成，共 " + createdIds.size() + " 个");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 创建单个闹钟（反射 Alarm + AlarmHelper）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 通过反射创建一个系统闹钟并写入数据库。
     * @return 新闹钟的 id（>0），失败时返回 -1
     */
    private long createAlarm(Context ctx, ClassLoader cl,
                             int hour, int minute, String label) {
        try {
            Class<?> alarmCls = Class.forName("com.android.deskclock.Alarm", false, cl);
            Object   alarm    = alarmCls.newInstance();

            setField(alarm, "hour",          hour);
            setField(alarm, "minutes",       minute);
            setField(alarm, "vibrate",       true);
            setField(alarm, "enabled",       true);
            setField(alarm, "deleteAfterUse", true); // 用后即焚

            // 标签（不同版本字段名不同）
            String lbl = label != null ? label : "";
            if (!setField(alarm, "label", lbl)) setField(alarm, "message", lbl);

            // 默认闹钟铃声
            Uri alertUri = android.media.RingtoneManager
                    .getDefaultUri(android.media.RingtoneManager.TYPE_ALARM);
            setField(alarm, "alert", alertUri);

            // DaysOfWeek：coded = 0 → 不重复（仅一次）
            Object daysOfWeek = getField(alarm, "daysOfWeek");
            if (daysOfWeek != null) {
                // 不同版本字段名为 "coded" 或 "mDays"
                if (!setField(daysOfWeek, "coded", 0)) setField(daysOfWeek, "mDays", 0);
            }

            // 调用 AlarmHelper.addAlarm(Context, Alarm)
            Method addMethod = resolveAddAlarmMethod(cl, alarmCls);
            if (addMethod == null) {
                XposedBridge.log(TAG + ": 未找到 AlarmHelper.addAlarm，无法创建闹钟");
                return -1;
            }
            addMethod.setAccessible(true);
            addMethod.invoke(null, ctx, alarm);

            // 不依赖 addAlarm 返回值（MIUI 各版本返回触发时间戳/Uri/Alarm 均有不同）
            // 直接反查 ContentProvider，取 hour+minutes 匹配且 _id 最大的行（最新插入）
            long id = findAlarmIdByTime(ctx, cl, hour, minute);
            String timeStr = hour + ":" + String.format(java.util.Locale.getDefault(), "%02d", minute);
            XposedBridge.log(TAG + ": 闹钟已创建 " + timeStr + " id=" + id + " [" + lbl + "]");
            return id;

        } catch (Throwable e) {
            XposedBridge.log(TAG + ": createAlarm 失败 → " + e);
            return -1;
        }
    }

    /**
     * 在多个已知路径中查找 AlarmHelper.addAlarm(Context, Alarm) 静态方法。
     * MIUI 各版本中 AlarmHelper 的包路径会有差异。
     */
    private Method resolveAddAlarmMethod(ClassLoader cl, Class<?> alarmCls) {
        String[] helperPaths = {
                "com.android.deskclock.util.AlarmHelper",
                "com.android.deskclock.AlarmHelper",
                "com.android.deskclock.provider.AlarmHelper",
        };
        for (String path : helperPaths) {
            try {
                Class<?> helperCls = Class.forName(path, false, cl);
                Method m = helperCls.getDeclaredMethod("addAlarm", Context.class, alarmCls);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 从 addAlarm 返回值或 alarm 对象本身读取数据库行 id */
    private long extractId(Object returnVal, Object alarm) {
        // MIUI 部分版本直接返回 Uri（content://…/alarm/7）
        if (returnVal instanceof Uri) {
            try { return Long.parseLong(((Uri) returnVal).getLastPathSegment()); }
            catch (Throwable ignored) {}
        }
        // 部分版本返回 Long，需区分真实行 ID（小整数）与时间戳（>10^10）
        if (returnVal instanceof Long)    { long v = (Long) returnVal;    if (v > 0 && v < 10_000_000_000L) return v; }
        if (returnVal instanceof Integer) { return (long) (Integer) returnVal; }
        // 尝试从返回的 Alarm 对象读取 id 字段（需排除时间戳）
        if (returnVal != null && !(returnVal instanceof Uri)) {
            Object rv = getField(returnVal, "id");
            if (rv instanceof Long)    { long v = (Long) rv;    if (v > 0 && v < 10_000_000_000L) return v; }
            if (rv instanceof Integer) { return (long) (Integer) rv; }
        }
        // 最后尝试原始 alarm 对象的 id 字段
        Object idVal = getField(alarm, "id");
        if (idVal instanceof Long)    { long v = (Long) idVal;    if (v > 0 && v < 10_000_000_000L) return v; }
        if (idVal instanceof Integer) { return (long) (Integer) idVal; }
        return -1;
    }

    /**
     * 通过 ContentProvider 反查刚创建的闹钟真实行 ID。
     * 查找所有 hour == h && minutes == m 的行，返回 _id 最大（最新插入）的那条。
     */
    private long findAlarmIdByTime(Context ctx, ClassLoader cl, int hour, int minute) {
        try {
            Uri base = getAlarmContentUri(cl);
            android.database.Cursor c = ctx.getContentResolver().query(
                    base, null, null, null, null);
            if (c == null) return -1;
            long maxId = -1;
            StringBuilder dbgCols = null;
            try {
                if (c.getColumnCount() > 0 && !c.moveToFirst()) return -1;
                // 记录一次列名用于诊断
                dbgCols = new StringBuilder();
                for (int ci = 0; ci < c.getColumnCount(); ci++) {
                    if (ci > 0) dbgCols.append(",");
                    dbgCols.append(c.getColumnName(ci));
                }
                c.moveToFirst();
                do {
                    int idxId = c.getColumnIndex("_id");
                    int idxH  = c.getColumnIndex("hour");
                    int idxM  = c.getColumnIndex("minutes");
                    if (idxId < 0 || idxH < 0 || idxM < 0) break; // 列不存在则停止
                    long rowId = c.getLong(idxId);
                    int  h     = c.getInt(idxH);
                    int  m     = c.getInt(idxM);
                    if (h == hour && m == minute && rowId > maxId) maxId = rowId;
                } while (c.moveToNext());
            } finally { c.close(); }
            if (dbgCols != null) XposedBridge.log(TAG + ": CP columns=[" + dbgCols + "] found _id=" + maxId);
            return maxId;
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": findAlarmIdByTime 失败 → " + e);
            return -1;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 删除已创建的闹钟
    // ─────────────────────────────────────────────────────────────────────────

    private void deletePreviousAlarms(Context ctx, ClassLoader cl) {
        // 主路径：按标签查询，不依赖 ID 存储是否准确
        int labelCount = deleteAlarmsByLabel(ctx, cl);
        // 兜底：SP 记录的 ID（应对标签查询失败的情况）
        List<Long> ids = loadAlarmIds(ctx);
        for (long id : ids) deleteAlarmById(ctx, cl, id);
        storeAlarmIds(ctx, new ArrayList<>());
        XposedBridge.log(TAG + ": 旧叫醒闹钟已清除（标签=" + labelCount + " ID=" + ids.size() + "）");
    }

    /** 查询所有闹钟、在 Java 侧过滤 label/message 列，删除由本模块创建的闹钟 */
    private int deleteAlarmsByLabel(Context ctx, ClassLoader cl) {
        try {
            Uri base = getAlarmContentUri(cl);
            // 查询全部列，避免 LIKE 在部分 MIUI 版本上不被 ContentProvider 支持
            android.database.Cursor c = ctx.getContentResolver().query(
                    base, null, null, null, null);
            if (c == null) return 0;
            List<Long> toDelete = new ArrayList<>();
            try {
                while (c.moveToNext()) {
                    int idxId  = c.getColumnIndex("_id");
                    if (idxId < 0) continue;
                    long rowId = c.getLong(idxId);
                    // 尝试 label 列；MIUI 某些版本用 message
                    String lbl = "";
                    int idxLabel = c.getColumnIndex("label");
                    if (idxLabel >= 0 && !c.isNull(idxLabel)) lbl = c.getString(idxLabel);
                    if (lbl.isEmpty()) {
                        int idxMsg = c.getColumnIndex("message");
                        if (idxMsg >= 0 && !c.isNull(idxMsg)) lbl = c.getString(idxMsg);
                    }
                    if (lbl.startsWith("课表提醒：")) toDelete.add(rowId);
                }
            } finally { c.close(); }
            for (long id : toDelete) deleteAlarmById(ctx, cl, id);
            if (!toDelete.isEmpty())
                XposedBridge.log(TAG + ": 通过标签删除 " + toDelete.size() + " 个旧闹钟");
            return toDelete.size();
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": deleteAlarmsByLabel 失败 → " + e);
            return 0;
        }
    }

    /** 反射获取 Alarm.CONTENT_URI，失败则返回硬编码 URI */
    private Uri getAlarmContentUri(ClassLoader cl) {
        try {
            Class<?> alarmCls = Class.forName("com.android.deskclock.Alarm", false, cl);
            java.lang.reflect.Field f = alarmCls.getDeclaredField("CONTENT_URI");
            f.setAccessible(true);
            Object val = f.get(null);
            if (val instanceof Uri) return (Uri) val;
        } catch (Throwable ignored) {}
        return Uri.parse("content://com.android.deskclock/alarm");
    }

    private void deleteAlarmById(Context ctx, ClassLoader cl, long id) {
        // 直接操作 ContentProvider（反射 removeAlarm 在此设备上无效，CP 可靠）
        try {
            Uri base = getAlarmContentUri(cl);
            // 优先 path-based URI（更可靠）
            int rows = ctx.getContentResolver().delete(
                    android.content.ContentUris.withAppendedId(base, id), null, null);
            if (rows > 0) return;
            // 再尝试 WHERE 子句
            ctx.getContentResolver().delete(base, "_id = ?", new String[]{String.valueOf(id)});
        } catch (Throwable e2) {
            XposedBridge.log(TAG + ": ContentProvider 删除失败 id=" + id + " → " + e2.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 持久化已创建闹钟 ID 列表
    // ─────────────────────────────────────────────────────────────────────────

    private void storeAlarmIds(Context ctx, List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(ids.get(i));
        }
        ctx.getSharedPreferences(SP_WAKEUP, Context.MODE_PRIVATE)
           .edit().putString(KEY_ALARM_IDS, sb.toString()).apply();
    }

    private List<Long> loadAlarmIds(Context ctx) {
        String stored = ctx.getSharedPreferences(SP_WAKEUP, Context.MODE_PRIVATE)
                           .getString(KEY_ALARM_IDS, "");
        List<Long> ids = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return ids;
        for (String part : stored.split(",")) {
            try { ids.add(Long.parseLong(part.trim())); }
            catch (NumberFormatException ignored) {}
        }
        return ids;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 反射工具方法
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 设置对象字段（向父类递归查找），返回是否成功。
     */
    private boolean setField(Object obj, String name, Object value) {
        Field f = findField(obj.getClass(), name);
        if (f == null) return false;
        try {
            f.setAccessible(true);
            f.set(obj, value);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 获取对象字段值（向父类递归查找），失败返回 null。 */
    private Object getField(Object obj, String name) {
        Field f = findField(obj.getClass(), name);
        if (f == null) return null;
        try {
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable e) {
            return null;
        }
    }

    private Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }
}
