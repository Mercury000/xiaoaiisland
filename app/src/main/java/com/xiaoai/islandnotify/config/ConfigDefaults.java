package com.xiaoai.islandnotify;

final class ConfigDefaults {

    private ConfigDefaults() {}

    static final int STAGE_PRE = 0;
    static final int STAGE_ACTIVE = 1;
    static final int STAGE_POST = 2;
    static final int REMINDER_MINUTES = 15;
    static final int MINUTES_OFFSET = 0;
    static final int TIMEOUT_VALUE = -1;
    static final String TIMEOUT_UNIT = "m";
    static final String KEY_NOTIF_DISMISS_TRIGGER = "notif_dismiss_trigger";
    static final String NOTIF_TRIGGER = "pre";
    static final boolean SWITCH_DISABLED = false;
    static final boolean REPOST_ENABLED = true;
    static final int ISLAND_BUTTON_MODE = 0;
    static final int WAKEUP_MORNING_LAST_SEC = 4;
    static final int WAKEUP_AFTERNOON_FIRST_SEC = 5;
    static final String WAKEUP_MORNING_RULES_JSON = "[{\"sec\":1,\"hour\":7,\"minute\":0}]";
    static final String WAKEUP_AFTERNOON_RULES_JSON = "[{\"sec\":5,\"hour\":12,\"minute\":0}]";
    static final String[] TEMPLATE_BASE_KEYS = {"tpl_a", "tpl_b", "tpl_ticker"};
    static final String[] STAGE_SUFFIXES = {"_pre", "_active", "_post"};
    static final String[] STAGE_PHASES = {"pre", "active", "post"};
    static final String[] DEFAULT_TPL_A = {
            "{\u6559\u5ba4}", "{\u8bfe\u540d}", "{\u8bfe\u540d}"
    };
    static final String[] DEFAULT_TPL_B = {
            "{\u5f00\u59cb}\u4e0a\u8bfe", "{\u7ed3\u675f}\u4e0b\u8bfe", "\u5df2\u7ecf\u4e0b\u8bfe"
    };
    static final String[] DEFAULT_TPL_TICKER = {
            "{\u6559\u5ba4}\uFF5C{\u5f00\u59cb}\u4e0a\u8bfe",
            "{\u8bfe\u540d}\uFF5C{\u7ed3\u675f}\u4e0b\u8bfe",
            "{\u8bfe\u540d}\uFF5C\u5df2\u7ecf\u4e0b\u8bfe"
    };
    static final String[] EXPANDED_TPL_KEYS = {
            "tpl_base_title",
            "tpl_hint_title",
            "tpl_hint_subtitle",
            "tpl_hint_content",
            "tpl_hint_subcontent",
            "tpl_base_content",
            "tpl_base_subcontent"
    };
    static final String[] TEMPLATE_STAGE_MIGRATION_KEYS = {
            "tpl_a",
            "tpl_b",
            "tpl_ticker",
            "tpl_base_title",
            "tpl_hint_title",
            "tpl_hint_subtitle",
            "tpl_hint_content",
            "tpl_hint_subcontent",
            "tpl_base_content",
            "tpl_base_subcontent"
    };
    static final String[] EXPANDED_TPL_HINTS = {
            "主要标题",
            "主要小文本1",
            "主要小文本2",
            "前置文本1",
            "前置文本2",
            "次要文本1",
            "次要文本2"
    };
    static final String[][] DEFAULT_EXPANDED_TPLS_V2 = {
            {"{\u8bfe\u540d}", "{\u5012\u8ba1\u65f6}", "{\u6559\u5ba4}", "\u5373\u5c06\u4e0a\u8bfe", "\u5730\u70b9", "{\u5f00\u59cb} | {\u7ed3\u675f}", ""},
            {"{\u8bfe\u540d}", "{\u5012\u8ba1\u65f6}", "{\u6559\u5ba4}", "\u8ddd\u79bb\u4e0b\u8bfe", "\u5730\u70b9", "{\u5f00\u59cb} | {\u7ed3\u675f}", ""},
            {"{\u8bfe\u540d}", "{\u6b63\u8ba1\u65f6}", "{\u6559\u5ba4}", "\u5df2\u7ecf\u4e0b\u8bfe", "\u5730\u70b9", "{\u5f00\u59cb} | {\u7ed3\u675f}", ""}
    };

    static int intDefault(String key, int fallback) {
        if (key == null) return fallback;
        if (key.startsWith("to_island_val_") || key.startsWith("to_notif_val_")) {
            return TIMEOUT_VALUE;
        }
        switch (key) {
            case "reminder_minutes_before": return REMINDER_MINUTES;
            case "mute_mins_before":
            case "unmute_mins_after":
            case "dnd_mins_before":
            case "undnd_mins_after": return MINUTES_OFFSET;
            case "island_button_mode": return ISLAND_BUTTON_MODE;
            case "wakeup_morning_last_sec": return WAKEUP_MORNING_LAST_SEC;
            case "wakeup_afternoon_first_sec": return WAKEUP_AFTERNOON_FIRST_SEC;
            default: return fallback;
        }
    }

    static boolean boolDefault(String key, boolean fallback) {
        if (key == null) return fallback;
        switch (key) {
            case "repost_enabled": return REPOST_ENABLED;
            case "mute_enabled":
            case "unmute_enabled":
            case "dnd_enabled":
            case "undnd_enabled":
            case "wakeup_morning_enabled":
            case "wakeup_afternoon_enabled":
            case "active_countdown_to_end": return SWITCH_DISABLED;
            default: return fallback;
        }
    }

    static String stringDefault(String key, String fallback) {
        if (key == null) return fallback;
        if (key.startsWith("to_island_unit_") || key.startsWith("to_notif_unit_")) {
            return TIMEOUT_UNIT;
        }
        switch (key) {
            case "notif_dismiss_trigger": return NOTIF_TRIGGER;
            case "wakeup_morning_rules_json": return WAKEUP_MORNING_RULES_JSON;
            case "wakeup_afternoon_rules_json": return WAKEUP_AFTERNOON_RULES_JSON;
            default: return fallback;
        }
    }

    static String stagedTemplateDefault(String key, String suffix, String fallback) {
        int keyIndex = -1;
        for (int i = 0; i < TEMPLATE_BASE_KEYS.length; i++) {
            if (TEMPLATE_BASE_KEYS[i].equals(key)) {
                keyIndex = i;
                break;
            }
        }
        int stageIndex = -1;
        for (int i = 0; i < STAGE_SUFFIXES.length; i++) {
            if (STAGE_SUFFIXES[i].equals(suffix)) {
                stageIndex = i;
                break;
            }
        }
        if (stageIndex < 0 || keyIndex < 0) return fallback;
        if (keyIndex == 0) return DEFAULT_TPL_A[stageIndex];
        if (keyIndex == 1) return DEFAULT_TPL_B[stageIndex];
        return DEFAULT_TPL_TICKER[stageIndex];
    }

    static int stageIndexBySuffix(String suffix) {
        if (suffix == null) return STAGE_PRE;
        for (int i = 0; i < STAGE_SUFFIXES.length; i++) {
            if (STAGE_SUFFIXES[i].equals(suffix)) return i;
        }
        return STAGE_PRE;
    }

    static int stageIndexByPhase(String phase) {
        if (phase == null) return STAGE_PRE;
        for (int i = 0; i < STAGE_PHASES.length; i++) {
            if (STAGE_PHASES[i].equals(phase)) return i;
        }
        return STAGE_PRE;
    }

    static String stageSuffix(int stageIndex) {
        int idx = normalizeStageIndex(stageIndex);
        return STAGE_SUFFIXES[idx];
    }

    static String stagePhase(int stageIndex) {
        int idx = normalizeStageIndex(stageIndex);
        return STAGE_PHASES[idx];
    }

    static int normalizeStageIndex(int stageIndex) {
        if (stageIndex < STAGE_PRE || stageIndex >= STAGE_PHASES.length) return STAGE_PRE;
        return stageIndex;
    }

    static boolean isConfigKey(String key) {
        if (key == null || key.isEmpty()) return false;
        if (key.startsWith("tpl_") || key.startsWith("to_island_") || key.startsWith("to_notif_")) return true;
        if ("migration_config_v1_done".equals(key)
                || "migration_config_v2_done".equals(key)
                || KEY_NOTIF_DISMISS_TRIGGER.equals(key)) return true;
        return "reminder_minutes_before".equals(key)
                || "mute_enabled".equals(key)
                || "mute_mins_before".equals(key)
                || "unmute_enabled".equals(key)
                || "unmute_mins_after".equals(key)
                || "dnd_enabled".equals(key)
                || "dnd_mins_before".equals(key)
                || "undnd_enabled".equals(key)
                || "undnd_mins_after".equals(key)
                || "repost_enabled".equals(key)
                || "active_countdown_to_end".equals(key)
                || "island_button_mode".equals(key)
                || "icon_a".equals(key)
                || "wakeup_morning_enabled".equals(key)
                || "wakeup_morning_last_sec".equals(key)
                || "wakeup_morning_rules_json".equals(key)
                || "wakeup_afternoon_enabled".equals(key)
                || "wakeup_afternoon_first_sec".equals(key)
                || "wakeup_afternoon_rules_json".equals(key);
    }

    static String expandedTemplateDefault(int stageIndex, int keyIndex, String fallback) {
        if (stageIndex < 0 || stageIndex >= DEFAULT_EXPANDED_TPLS_V2.length) return fallback;
        if (keyIndex < 0 || keyIndex >= DEFAULT_EXPANDED_TPLS_V2[stageIndex].length) return fallback;
        return DEFAULT_EXPANDED_TPLS_V2[stageIndex][keyIndex];
    }
}
