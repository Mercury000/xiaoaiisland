package com.xiaoai.islandnotify;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class HolidayManager {

    public static final String PREFS_HOLIDAY = "island_holiday";
    public static final String EXTRA_LIST_PREFIX = "holiday_list_";
    private static volatile SharedPreferences sRemotePrefs;

    public static final int TYPE_HOLIDAY = 0;
    public static final int TYPE_WORKSWAP = 1;

    public static void setRemotePrefs(SharedPreferences remotePrefs) {
        sRemotePrefs = remotePrefs;
    }

    public static void clearRemotePrefs() {
        sRemotePrefs = null;
    }

    private static SharedPreferences resolvePrefs() {
        return PrefsAccess.resolve(sRemotePrefs);
    }

    public static class HolidayEntry {
        public String date;
        public String endDate;
        public String name;
        public int type;
        public int followWeek = -1;
        public int followWeekday = -1;
        public boolean isCustom;

        public HolidayEntry() {}

        public HolidayEntry(String date, String endDate, String name, int type, boolean isCustom) {
            this.date = date;
            this.endDate = endDate;
            this.name = name;
            this.type = type;
            this.isCustom = isCustom;
        }

        JSONObject toJson() {
            try {
                JSONObject j = new JSONObject();
                j.put("date", date);
                j.put("endDate", endDate != null ? endDate : "");
                j.put("name", name);
                j.put("type", type);
                j.put("fw", followWeek);
                j.put("fwd", followWeekday);
                j.put("c", isCustom);
                return j;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        static HolidayEntry fromJson(JSONObject j) {
            HolidayEntry e = new HolidayEntry();
            e.date = j.optString("date", "");
            e.endDate = j.optString("endDate", "");
            e.name = j.optString("name", "");
            e.type = j.optInt("type", TYPE_HOLIDAY);
            e.followWeek = j.optInt("fw", -1);
            e.followWeekday = j.optInt("fwd", -1);
            e.isCustom = j.optBoolean("c", false);
            return e;
        }

        public String followDesc() {
            if (followWeek < 1 || followWeekday < 1) return "未配置";
            String[] wds = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
            String wd = (followWeekday >= 1 && followWeekday <= 7) ? wds[followWeekday] : "周?";
            return "第" + followWeek + "周 " + wd;
        }

        public boolean isMatch(String targetDate) {
            if (targetDate == null || targetDate.isEmpty()) return false;
            if (date != null && date.equals(targetDate)) return true;
            if (date != null && endDate != null && !endDate.isEmpty()) {
                return targetDate.compareTo(date) >= 0 && targetDate.compareTo(endDate) <= 0;
            }
            return false;
        }
    }

    public static List<HolidayEntry> loadEntries(Context ctx, int year) {
        SharedPreferences sp = resolvePrefs();
        String raw = sp.getString("list_" + year, null);
        List<HolidayEntry> list = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                list.add(HolidayEntry.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static String entriesToJson(List<HolidayEntry> entries) {
        JSONArray arr = new JSONArray();
        for (HolidayEntry e : entries) arr.put(e.toJson());
        return arr.toString();
    }

    public static void saveEntries(Context ctx, int year, List<HolidayEntry> entries) {
        resolvePrefs()
                .edit()
                .putString("list_" + year, entriesToJson(entries))
                .apply();
    }

    public static void mergeAndSave(Context ctx, int year, List<HolidayEntry> apiEntries) {
        List<HolidayEntry> existing = loadEntries(ctx, year);
        List<HolidayEntry> merged = new ArrayList<>();
        for (HolidayEntry e : existing) {
            if (e.isCustom) merged.add(e);
        }
        for (HolidayEntry ae : apiEntries) {
            boolean conflict = false;
            for (HolidayEntry ce : merged) {
                if (ce.date.equals(ae.date)) {
                    conflict = true;
                    break;
                }
            }
            if (!conflict) merged.add(ae);
        }
        merged.sort((a, b) -> a.date.compareTo(b.date));
        saveEntries(ctx, year, merged);
    }

    public static void clearAll(Context ctx) {
        SharedPreferences sp = resolvePrefs();
        SharedPreferences.Editor editor = sp.edit();
        for (String key : sp.getAll().keySet()) {
            if (key.startsWith("list_")) editor.remove(key);
        }
        editor.apply();
    }

    public static boolean isHoliday(Context ctx, String date) {
        try {
            int year = Integer.parseInt(date.substring(0, 4));
            for (HolidayEntry e : loadEntries(ctx, year)) {
                if (e.type == TYPE_HOLIDAY && e.isMatch(date)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static HolidayEntry getWorkSwap(Context ctx, String date) {
        try {
            int year = Integer.parseInt(date.substring(0, 4));
            for (HolidayEntry e : loadEntries(ctx, year)) {
                if (e.type == TYPE_WORKSWAP && e.isMatch(date)) return e;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static List<HolidayEntry> parseApiResponse(String json) {
        List<HolidayEntry> result = new ArrayList<>();
        if (json == null) return result;
        String raw = json.trim();
        if (raw.isEmpty()) return result;

        try {
            JSONObject obj = new JSONObject(raw);
            JSONArray arr = obj.getJSONArray("dates");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject d = arr.getJSONObject(i);
                String date = d.optString("date", "");
                String name = d.optString("name_cn", "");
                if (name.isEmpty()) name = d.optString("name", "");
                String type = d.optString("type", "");
                if ("public_holiday".equals(type)) {
                    addEntry(result, date, name, true);
                } else if ("transfer_workday".equals(type)) {
                    addEntry(result, date, name, false);
                }
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return mergeConsecutiveEntries(result);
    }

    private static void addEntry(List<HolidayEntry> list, String date, String name, boolean isHoliday) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) return;
        if (name == null || name.isEmpty()) name = date;
        list.add(new HolidayEntry(date, "", name, isHoliday ? TYPE_HOLIDAY : TYPE_WORKSWAP, false));
    }

    private static List<HolidayEntry> mergeConsecutiveEntries(List<HolidayEntry> list) {
        if (list.isEmpty()) return list;
        list.sort((a, b) -> a.date.compareTo(b.date));
        List<HolidayEntry> merged = new ArrayList<>();
        HolidayEntry current = null;
        for (HolidayEntry e : list) {
            if (current == null) {
                current = e;
                merged.add(current);
            } else if (current.type == e.type
                    && current.name.equals(e.name)
                    && current.isCustom == e.isCustom
                    && current.followWeek == e.followWeek
                    && current.followWeekday == e.followWeekday) {
                String lastDate = (current.endDate != null && !current.endDate.isEmpty())
                        ? current.endDate : current.date;
                if (isAdjacentDay(lastDate, e.date)) {
                    current.endDate = e.date;
                    continue;
                }
                current = e;
                merged.add(current);
            } else {
                current = e;
                merged.add(current);
            }
        }
        return merged;
    }

    private static boolean isAdjacentDay(String d1, String d2) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            long t1 = sdf.parse(d1).getTime();
            long t2 = sdf.parse(d2).getTime();
            return t2 - t1 == 86400000L;
        } catch (Exception e) {
            return false;
        }
    }
}
