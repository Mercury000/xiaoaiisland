package com.xiaoai.islandnotify;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class CourseScheduleParser {

    private CourseScheduleParser() {}

    static final class ParsedSchedule {
        /**
         * 学期真实周序号，不做上下夹取：学期未开始时 ≤ 0，学期结束后 &gt; totalWeek。
         * 课程匹配可直接使用，越界周在 {@link CourseSlot#isInWeek(int)} 中天然不匹配任何课程。
         */
        final int presentWeek;
        final int totalWeek;
        final List<CourseSlot> courses;

        ParsedSchedule(int presentWeek, int totalWeek, List<CourseSlot> courses) {
            this.presentWeek = presentWeek;
            this.totalWeek = totalWeek;
            this.courses = courses == null ? Collections.emptyList() : courses;
        }

        boolean isTermNotStarted() {
            return presentWeek < 1;
        }

        boolean isTermEnded() {
            return totalWeek > 0 && presentWeek > totalWeek;
        }
    }

    static final class CourseSlot {
        final int day;
        final String courseName;
        final String startTime;
        final String endTime;
        final String classroom;
        final String sectionRange;
        final String teacher;
        /** 起始节次，无法解析出节次时为 -1 */
        final int firstSection;
        private final WeekMatcher weekMatcher;

        CourseSlot(int day, String courseName, String startTime, String endTime,
                   String classroom, String sectionRange, String teacher,
                   int firstSection, WeekMatcher weekMatcher) {
            this.day = day;
            this.courseName = safeStr(courseName);
            this.startTime = safeStr(startTime);
            this.endTime = safeStr(endTime);
            this.classroom = safeStr(classroom);
            this.sectionRange = safeStr(sectionRange);
            this.teacher = safeStr(teacher);
            this.firstSection = firstSection;
            this.weekMatcher = weekMatcher == null ? WeekMatcher.empty() : weekMatcher;
        }

        boolean isInWeek(int week) {
            return weekMatcher.contains(week);
        }
    }

    static ParsedSchedule parse(String beanJson) throws Exception {
        if (beanJson == null || beanJson.isEmpty()) {
            throw new IllegalArgumentException("weekCourseBean is empty");
        }
        JSONObject root = new JSONObject(beanJson);
        JSONObject data = root.getJSONObject("data");
        JSONObject setting = data.getJSONObject("setting");
        int totalWeek = setting.optInt("totalWeek", 0);
        
        int presentWeek = setting.optInt("presentWeek", 0);
        String startDate = setting.optString("startDate", "");
        boolean sundayFirst = setting.optBoolean("sundayFirst", false);
        if (!startDate.isEmpty()) {
            presentWeek = computePresentWeek(startDate, presentWeek, sundayFirst);
        }

        JSONArray sectionTimesArray = parseSectionTimesArray(setting);
        java.util.Map<Integer, SectionTime> sectionTimes = buildSectionTimeMap(sectionTimesArray);

        JSONArray coursesArray = data.optJSONArray("courses");
        List<CourseSlot> courses = new ArrayList<>();
        if (coursesArray != null) {
            for (int i = 0; i < coursesArray.length(); i++) {
                JSONObject course = coursesArray.optJSONObject(i);
                CourseSlot slot = parseCourseSlot(course, sectionTimes);
                if (slot != null) courses.add(slot);
            }
        }
        return new ParsedSchedule(presentWeek, totalWeek, courses);
    }

    static int stableHash(String beanJson) {
        if (beanJson == null || beanJson.isEmpty()) return 0;
        try {
            JSONObject root = new JSONObject(beanJson);
            JSONObject data = root.getJSONObject("data");
            JSONObject setting = data.getJSONObject("setting");
            
            int presentWeek = setting.optInt("presentWeek", 0);
            String startDate = setting.optString("startDate", "");
            boolean sundayFirst = setting.optBoolean("sundayFirst", false);
            if (!startDate.isEmpty()) {
                presentWeek = computePresentWeek(startDate, presentWeek, sundayFirst);
            }

            String stable = String.valueOf(data.optJSONArray("courses"))
                    + sectionTimesStableRaw(setting)
                    + setting.optString("totalWeek")
                    + setting.optString("weekStart")
                    + presentWeek
                    + startDate
                    + sundayFirst;
            return stable.hashCode();
        } catch (Throwable ignored) {
            return beanJson.hashCode();
        }
    }

    private static String sectionTimesStableRaw(JSONObject setting) {
        JSONArray sectionTimesArray = setting.optJSONArray("sectionTimes");
        if (sectionTimesArray != null) return sectionTimesArray.toString();
        return setting.optString("sectionTimes", "");
    }

    private static JSONArray parseSectionTimesArray(JSONObject setting) throws Exception {
        JSONArray direct = setting.optJSONArray("sectionTimes");
        if (direct != null) return direct;
        String raw = setting.optString("sectionTimes", "[]");
        if (raw == null || raw.isEmpty()) raw = "[]";
        return new JSONArray(raw);
    }

    private static java.util.Map<Integer, SectionTime> buildSectionTimeMap(JSONArray sectionTimesArray) {
        java.util.Map<Integer, SectionTime> sectionTimes = new java.util.HashMap<>();
        if (sectionTimesArray == null) return sectionTimes;
        for (int i = 0; i < sectionTimesArray.length(); i++) {
            JSONObject item = sectionTimesArray.optJSONObject(i);
            if (item == null) continue;
            int sectionIndex = item.optInt("i", -1);
            if (sectionIndex < 0) continue;
            String startTime = safeStr(item.optString("s", ""));
            String endTime = safeStr(item.optString("e", ""));
            if (startTime.isEmpty() || endTime.isEmpty()) continue;
            sectionTimes.put(sectionIndex, new SectionTime(startTime, endTime));
        }
        return sectionTimes;
    }

    private static CourseSlot parseCourseSlot(JSONObject course, java.util.Map<Integer, SectionTime> sectionTimes) {
        if (course == null) return null;
        int day = course.optInt("day", -1);
        if (day < 1 || day > 7) return null;

        WeekMatcher weekMatcher = WeekMatcher.parse(course.optString("weeks", ""));
        if (weekMatcher.isEmpty()) return null;

        int[] sectionBounds = parseSectionBounds(course.optString("sections", ""));
        int firstSection = sectionBounds == null ? -1 : sectionBounds[0];
        int lastSection = sectionBounds == null ? -1 : sectionBounds[1];

        String directStartTime = firstNonEmpty(
                course.optString("startTime", ""),
                course.optString("start_time", ""),
                course.optString("customStartTime", ""),
                course.optString("custom_start_time", ""));
        String directEndTime = firstNonEmpty(
                course.optString("endTime", ""),
                course.optString("end_time", ""),
                course.optString("customEndTime", ""),
                course.optString("custom_end_time", ""));
        boolean hasDirectTime = isValidTimeRange(directStartTime, directEndTime);

        String resolvedStartTime;
        String resolvedEndTime;
        if (hasDirectTime) {
            resolvedStartTime = directStartTime;
            resolvedEndTime = directEndTime;
        } else {
            if (sectionBounds == null) return null;
            SectionTime startSection = sectionTimes.get(firstSection);
            SectionTime endSection = sectionTimes.get(lastSection);
            if (startSection == null || endSection == null) return null;
            resolvedStartTime = startSection.startTime;
            resolvedEndTime = endSection.endTime;
        }

        String courseName = firstNonEmpty(
                course.optString("name", ""),
                course.optString("courseName", ""));
        if (courseName.isEmpty()) return null;
        String classroom = firstNonEmpty(
                course.optString("position", ""),
                course.optString("classroom", ""));
        String sectionRange = sectionBounds == null
                ? safeStr(course.optString("sections", ""))
                : (firstSection + "-" + lastSection);
        String teacher = extractTeacher(course);
        return new CourseSlot(day, courseName, resolvedStartTime, resolvedEndTime,
                classroom, sectionRange, teacher, firstSection, weekMatcher);
    }

    private static boolean isValidTimeRange(String start, String end) {
        if (!isLikelyTime(start) || !isLikelyTime(end)) return false;
        // WakeUp 常见占位值：00:00；这类值不能作为课程时间。
        if ("00:00".equals(start) || "00:00".equals(end)) return false;
        return true;
    }

    private static boolean isLikelyTime(String value) {
        if (value == null || value.isEmpty()) return false;
        if (value.length() != 5 || value.charAt(2) != ':') return false;
        char h1 = value.charAt(0), h2 = value.charAt(1), m1 = value.charAt(3), m2 = value.charAt(4);
        if (h1 < '0' || h1 > '2' || h2 < '0' || h2 > '9') return false;
        if (m1 < '0' || m1 > '5' || m2 < '0' || m2 > '9') return false;
        int hour = (h1 - '0') * 10 + (h2 - '0');
        return hour >= 0 && hour <= 23;
    }

    private static int[] parseSectionBounds(String sectionsSpec) {
        if (sectionsSpec == null || sectionsSpec.isEmpty()) return null;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        String normalized = normalizeSpec(sectionsSpec);
        String[] tokens = normalized.split(",");
        for (String token : tokens) {
            if (token == null) continue;
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            int[] range = parseRange(trimmed);
            if (range == null) continue;
            min = Math.min(min, range[0]);
            max = Math.max(max, range[1]);
        }
        if (min == Integer.MAX_VALUE || max == Integer.MIN_VALUE) return null;
        return new int[]{min, max};
    }

    private static String extractTeacher(JSONObject course) {
        String teacher = firstNonEmpty(
                course.optString("teacher", ""),
                course.optString("teacherName", ""),
                course.optString("teacher_name", ""),
                course.optString("teachers", ""));
        return safeStr(teacher);
    }

    private static int[] parseRange(String token) {
        String normalized = token.replace("~", "-")
                .replace("—", "-")
                .replace("－", "-")
                .replace("至", "-");
        int dashIndex = normalized.indexOf('-');
        if (dashIndex > 0 && dashIndex < normalized.length() - 1) {
            Integer start = parsePositiveInt(normalized.substring(0, dashIndex));
            Integer end = parsePositiveInt(normalized.substring(dashIndex + 1));
            if (start == null || end == null) return null;
            int a = Math.min(start, end);
            int b = Math.max(start, end);
            return new int[]{a, b};
        }
        Integer single = parsePositiveInt(normalized);
        if (single == null) return null;
        return new int[]{single, single};
    }

    private static Integer parsePositiveInt(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        int value = 0;
        boolean hasDigit = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '0' && ch <= '9') {
                hasDigit = true;
                value = value * 10 + (ch - '0');
            } else if (hasDigit) {
                break;
            }
        }
        if (!hasDigit || value <= 0) return null;
        return value;
    }

    private static String normalizeSpec(String spec) {
        return safeStr(spec)
                .replace("，", ",")
                .replace("；", ",")
                .replace(";", ",");
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    private static String safeStr(String value) {
        return value == null ? "" : value;
    }

    private static final class SectionTime {
        final String startTime;
        final String endTime;

        SectionTime(String startTime, String endTime) {
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    private static final class WeekMatcher {
        private final List<int[]> ranges;

        private WeekMatcher(List<int[]> ranges) {
            this.ranges = ranges == null ? Collections.emptyList() : ranges;
        }

        static WeekMatcher empty() {
            return new WeekMatcher(Collections.emptyList());
        }

        static WeekMatcher parse(String weeksSpec) {
            if (weeksSpec == null || weeksSpec.isEmpty()) return empty();
            String normalized = normalizeSpec(weeksSpec).replace(" ", "");
            String[] tokens = normalized.split(",");
            List<int[]> ranges = new ArrayList<>();
            for (String token : tokens) {
                if (token == null || token.isEmpty()) continue;
                int[] range = parseRange(token);
                if (range != null) ranges.add(range);
            }
            return ranges.isEmpty() ? empty() : new WeekMatcher(ranges);
        }

        boolean isEmpty() {
            return ranges.isEmpty();
        }

        boolean contains(int week) {
            if (week <= 0) return false;
            for (int[] range : ranges) {
                if (week >= range[0] && week <= range[1]) return true;
            }
            return false;
        }
    }

    /**
     * 按学期开始日期推算当前周序号。返回值不做夹取：今天早于 startDate 时 ≤ 0，
     * 超出学期总周数时大于总周数，供调用方据此判断学期未开始 / 已结束。
     * startDate 缺失或非法时无法推算，原样返回 currentWeek。
     */
    private static int computePresentWeek(String startDate, int currentWeek, boolean sundayFirst) {
        if (startDate == null || startDate.isEmpty()) return currentWeek;
        int[] ymd = parseYmd(startDate);
        if (ymd == null) return currentWeek;

        java.util.Calendar start = java.util.Calendar.getInstance(java.util.Locale.US);
        start.set(java.util.Calendar.YEAR, ymd[0]);
        start.set(java.util.Calendar.MONTH, Math.max(0, ymd[1] - 1));
        start.set(java.util.Calendar.DAY_OF_MONTH, Math.max(1, ymd[2]));
        clearClock(start);

        java.util.Calendar today = java.util.Calendar.getInstance(java.util.Locale.US);
        clearClock(today);

        int weekStartDay = sundayFirst ? java.util.Calendar.SUNDAY : java.util.Calendar.MONDAY;
        alignToWeekStart(start, weekStartDay);
        alignToWeekStart(today, weekStartDay);

        long diffDays = (today.getTimeInMillis() - start.getTimeInMillis()) / 86_400_000L;
        return (int) Math.floor(diffDays / 7.0d) + 1;
    }

    private static void clearClock(java.util.Calendar c) {
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
    }

    private static void alignToWeekStart(java.util.Calendar c, int weekStartDay) {
        int cur = c.get(java.util.Calendar.DAY_OF_WEEK);
        int delta = cur - weekStartDay;
        if (delta < 0) delta += 7;
        if (delta != 0) c.add(java.util.Calendar.DAY_OF_MONTH, -delta);
    }

    private static int[] parseYmd(String raw) {
        try {
            String[] parts = raw.split("-");
            if (parts.length >= 3) {
                return new int[]{
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2])
                };
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
