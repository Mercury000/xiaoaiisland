package com.xiaoai.islandnotify;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 假期 / 调休 数据管理
 *
 * 数据来源：
 *  1. API: https://unpkg.com/holiday-calendar@1.3.0/data/CN/{year}.json
 *  2. 用户自定义
 *
 * 存储方案：
 *  - SharedPreferences 文件名：island_holiday
 *  - KEY: list_{year}  VALUE: JSON 数组字符串
 *  - App 侧写入本地，同时通过 ACTION_SYNC_PREFS 广播同步到 voiceassist 进程
 *
 * 类型：
 *  TYPE_HOLIDAY  = 0 → 节假日，当天不发任何课前提醒
 *  TYPE_WORKSWAP = 1 → 调休工作日（本应休息但上班），按指定周次/星期发提醒
 */
public class HolidayManager {

    /** SharedPreferences 文件名（两端统一） */
    public static final String PREFS_HOLIDAY = "island_holiday";
    /** 同步广播 extra key 前缀：holiday_list_{year} */
    public static final String EXTRA_LIST_PREFIX = "holiday_list_";

    /** 节假日：当天不发课前提醒 */
    public static final int TYPE_HOLIDAY  = 0;
    /** 调休工作日：按指定周次/星期发提醒 */
    public static final int TYPE_WORKSWAP = 1;

    // ── 数据模型 ─────────────────────────────────────────────────────────────

    public static class HolidayEntry {
        /** ISO 日期字符串，格式 "2026-01-01" */
        public String date;
        /** 结束日期（用于连续假期），单日则为空 */
        public String endDate;
        /** 假期名称，如 "元旦节" */
        public String name;
        /** TYPE_HOLIDAY / TYPE_WORKSWAP */
        public int type;
        /**
         * 调休专用：按第 followWeek 周的课程表上课（1-30）；-1=未配置
         */
        public int followWeek    = -1;
        /**
         * 调休专用：按星期 followWeekday（1=周一…7=周日）；-1=未配置
         */
        public int followWeekday = -1;
        /** true=用户手动添加，false=来自 API */
        public boolean isCustom;

        public HolidayEntry() {}

        public HolidayEntry(String date, String endDate, String name, int type, boolean isCustom) {
            this.date     = date;
            this.endDate  = endDate;
            this.name     = name;
            this.type     = type;
            this.isCustom = isCustom;
        }

        JSONObject toJson() {
            try {
                JSONObject j = new JSONObject();
                j.put("date", date);
                j.put("endDate", endDate != null ? endDate : "");
                j.put("name", name);
                j.put("type", type);
                j.put("fw",   followWeek);
                j.put("fwd",  followWeekday);
                j.put("c",    isCustom);
                return j;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        static HolidayEntry fromJson(JSONObject j) {
            HolidayEntry e    = new HolidayEntry();
            e.date            = j.optString("date", "");
            e.endDate         = j.optString("endDate", "");
            e.name            = j.optString("name", "");
            e.type            = j.optInt("type",   TYPE_HOLIDAY);
            e.followWeek      = j.optInt("fw",     -1);
            e.followWeekday   = j.optInt("fwd",    -1);
            e.isCustom        = j.optBoolean("c",  false);
            return e;
        }

        /** 调休描述，如"第8周 周四" */
        public String followDesc() {
            if (followWeek < 1 || followWeekday < 1) return "未配置";
            String[] wds = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
            String wd = (followWeekday >= 1 && followWeekday <= 7) ? wds[followWeekday] : "周?";
            return "第" + followWeek + "周 " + wd;
        }

        /** 判断指定日期是否落在本条目的日期范围内 */
        public boolean isMatch(String targetDate) {
            if (targetDate == null || targetDate.isEmpty()) return false;
            // 单日匹配
            if (date != null && date.equals(targetDate)) return true;
            // 范围匹配
            if (date != null && endDate != null && !endDate.isEmpty()) {
                return targetDate.compareTo(date) >= 0 && targetDate.compareTo(endDate) <= 0;
            }
            return false;
        }
    }

    // ── 持久化 ───────────────────────────────────────────────────────────────

    /** 加载指定年份的所有条目 */
    public static List<HolidayEntry> loadEntries(Context ctx, int year) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_HOLIDAY, Context.MODE_PRIVATE);
        String raw = sp.getString("list_" + year, null);
        List<HolidayEntry> list = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++)
                list.add(HolidayEntry.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        return list;
    }

    /** 将列表序列化为 JSON 字符串（用于广播传输） */
    public static String entriesToJson(List<HolidayEntry> entries) {
        JSONArray arr = new JSONArray();
        for (HolidayEntry e : entries) arr.put(e.toJson());
        return arr.toString();
    }

    /** 保存指定年份的所有条目 */
    public static void saveEntries(Context ctx, int year, List<HolidayEntry> entries) {
        ctx.getSharedPreferences(PREFS_HOLIDAY, Context.MODE_PRIVATE)
           .edit()
           .putString("list_" + year, entriesToJson(entries))
           .apply();
    }

    // ── 查询（Hook 侧调用） ──────────────────────────────────────────────────

    /**
     * 判断指定日期是否为节假日（节假日当天不发课前提醒）。
     * @param date "yyyy-MM-dd"
     */
    public static boolean isHoliday(Context ctx, String date) {
        try {
            int year = Integer.parseInt(date.substring(0, 4));
            for (HolidayEntry e : loadEntries(ctx, year))
                if (e.type == TYPE_HOLIDAY && e.isMatch(date)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 获取指定日期对应的调休工作日配置（返回 null 表示不是调休工作日）。
     * @param date "yyyy-MM-dd"
     */
    public static HolidayEntry getWorkSwap(Context ctx, String date) {
        try {
            int year = Integer.parseInt(date.substring(0, 4));
            for (HolidayEntry e : loadEntries(ctx, year))
                if (e.type == TYPE_WORKSWAP && e.isMatch(date)) return e;
        } catch (Exception ignored) {}
        return null;
    }

    // ── API 解析 ─────────────────────────────────────────────────────────────

    /**
     * 解析 holiday-calendar@1.3.0 CN JSON。
     *
     * 实际格式（通过抓包确认）：
     * {
     *   "year": 2026,
     *   "region": "CN",
     *   "dates": [
     *     {"date":"2026-01-01","name":"元旦","name_cn":"元旦","name_en":"...","type":"public_holiday"},
     *     {"date":"2026-01-04","name":"元旦补班","name_cn":"...","type":"transfer_workday"},
     *     ...
     *   ]
     * }
     *
     * type="public_holiday"   → TYPE_HOLIDAY  （不发提醒）
     * type="transfer_workday" → TYPE_WORKSWAP （调休工作日，需用户配置周次）
     *
     * 兼容降级格式（旧版 / 其他 CN 假日源）：
     * 格式 B: {"days":[...]}  各 item 含 isOffDay boolean
     * 格式 C: 直接 Array，含 isOffDay 或 type 字段
     * 格式 D: 对象 key=日期，value 含 isOffDay
     */
    public static List<HolidayEntry> parseApiResponse(String json) {
        List<HolidayEntry> result = new ArrayList<>();
        if (json == null) return result;
        json = json.trim();
        if (json.isEmpty()) return result;

        if (json.startsWith("{")) {
            try {
                JSONObject obj = new JSONObject(json);

                // ── 格式 主 / dates 数组（holiday-calendar@1.3.0 实际格式） ──
                if (obj.has("dates")) {
                    JSONArray arr = obj.getJSONArray("dates");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject d = arr.getJSONObject(i);
                        String date = d.optString("date", "");
                        // 优先 name_cn，回退 name
                        String name = d.optString("name_cn", "");
                        if (name.isEmpty()) name = d.optString("name", "");
                        String type = d.optString("type", "");
                        boolean isHoliday = "public_holiday".equals(type)
                                || d.optBoolean("isOffDay", false);
                        boolean isWorkSwap = "transfer_workday".equals(type)
                                || (d.has("isOffDay") && !d.optBoolean("isOffDay", true));
                        if (isHoliday)   addEntry(result, date, name, true);
                        else if (isWorkSwap) addEntry(result, date, name, false);
                    }
                    return result;
                }

                // ── 格式 B: days 数组 ──
                if (obj.has("days")) {
                    JSONArray arr = obj.getJSONArray("days");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject d = arr.getJSONObject(i);
                        String type = d.optString("type", "");
                        boolean isHoliday = "public_holiday".equals(type)
                                || d.optBoolean("isOffDay", true);
                        addEntry(result, d.optString("date"), d.optString("name"), isHoliday);
                    }
                    return result;
                }

                // ── 格式 D: 对象 key=日期 ──
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!key.matches("\\d{4}-\\d{2}-\\d{2}")) continue;
                    JSONObject val = obj.optJSONObject(key);
                    if (val != null) {
                        String name = val.optString("name_cn", "");
                        if (name.isEmpty()) name = val.optString("name", key);
                        String type = val.optString("type", "");
                        boolean isHoliday = "public_holiday".equals(type)
                                || val.optBoolean("isOffDay", true);
                        addEntry(result, key, name, isHoliday);
                    }
                }
            } catch (Exception ignored) {}

        } else if (json.startsWith("[")) {
            // ── 格式 C: 直接数组 ──
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject d = arr.getJSONObject(i);
                    String name = d.optString("name_cn", "");
                    if (name.isEmpty()) name = d.optString("name", "");
                    String type = d.optString("type", "");
                    boolean isHoliday = "public_holiday".equals(type)
                            || d.optBoolean("isOffDay", true);
                    addEntry(result, d.optString("date"), name, isHoliday);
                }
            } catch (Exception ignored) {}
        }
        return mergeConsecutiveEntries(result);
    }

    private static void addEntry(List<HolidayEntry> list, String date, String name, boolean isHoliday) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) return;
        if (name == null || name.isEmpty()) name = date;
        list.add(new HolidayEntry(date, "", name, isHoliday ? TYPE_HOLIDAY : TYPE_WORKSWAP, false));
    }

    /** 合并相邻且相同属性的假期为连休 */
    private static List<HolidayEntry> mergeConsecutiveEntries(List<HolidayEntry> list) {
        if (list.isEmpty()) return list;
        java.util.Collections.sort(list, (a, b) -> a.date.compareTo(b.date));
        List<HolidayEntry> merged = new ArrayList<>();
        HolidayEntry current = null;
        for (HolidayEntry e : list) {
            if (current == null) {
                current = e;
                merged.add(current);
            } else {
                if (current.type == e.type && current.name.equals(e.name) && current.isCustom == e.isCustom
                        && current.followWeek == e.followWeek && current.followWeekday == e.followWeekday) {
                    String lastDate = (current.endDate != null && !current.endDate.isEmpty()) ? current.endDate : current.date;
                    if (isAdjacentDay(lastDate, e.date)) {
                        current.endDate = e.date;
                        continue;
                    }
                }
                current = e;
                merged.add(current);
            }
        }
        return merged;
    }

    private static boolean isAdjacentDay(String d1, String d2) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            long t1 = sdf.parse(d1).getTime();
            long t2 = sdf.parse(d2).getTime();
            // 考虑时差及夏令时，20-28 小时视为相邻
            return Math.abs(t2 - t1) <= 100000000L && Math.abs(t2 - t1) >= 70000000L;
        } catch (Exception e) {
            return false;
        }
    }
}
