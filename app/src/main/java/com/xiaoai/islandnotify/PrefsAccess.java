package com.xiaoai.islandnotify;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

final class PrefsAccess {

    private PrefsAccess() {}

    private static final SharedPreferences EMPTY_PREFS = new SharedPreferences() {
        @Override public Map<String, ?> getAll() { return Collections.emptyMap(); }
        @Override public String getString(String key, String defValue) { return defValue; }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
        @Override public int getInt(String key, int defValue) { return defValue; }
        @Override public long getLong(String key, long defValue) { return defValue; }
        @Override public float getFloat(String key, float defValue) { return defValue; }
        @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }
        @Override public boolean contains(String key) { return false; }
        @Override
        public Editor edit() {
            return new Editor() {
                @Override public Editor putString(String key, String value) { return this; }
                @Override public Editor putStringSet(String key, Set<String> values) { return this; }
                @Override public Editor putInt(String key, int value) { return this; }
                @Override public Editor putLong(String key, long value) { return this; }
                @Override public Editor putFloat(String key, float value) { return this; }
                @Override public Editor putBoolean(String key, boolean value) { return this; }
                @Override public Editor remove(String key) { return this; }
                @Override public Editor clear() { return this; }
                @Override public boolean commit() { return true; }
                @Override public void apply() {}
            };
        }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    };

    static SharedPreferences resolve(SharedPreferences prefs) {
        return prefs != null ? prefs : EMPTY_PREFS;
    }

    static SharedPreferences.Editor edit(SharedPreferences prefs) {
        return resolve(prefs).edit();
    }

    static int readConfigInt(SharedPreferences prefs, String key, int fallback) {
        SharedPreferences target = resolve(prefs);
        return target.getInt(key, ConfigDefaults.intDefault(key, fallback));
    }

    static boolean readConfigBool(SharedPreferences prefs, String key, boolean fallback) {
        SharedPreferences target = resolve(prefs);
        return target.getBoolean(key, ConfigDefaults.boolDefault(key, fallback));
    }

    static String readConfigString(SharedPreferences prefs, String key, String fallback) {
        SharedPreferences target = resolve(prefs);
        String value = target.getString(key, ConfigDefaults.stringDefault(key, fallback));
        return value == null ? "" : value;
    }

    static void copyAll(SharedPreferences target, Map<String, ?> allValues) {
        copyAllFiltered(target, allValues, false);
    }

    static void copyAllFiltered(SharedPreferences target, Map<String, ?> allValues, boolean configOnly) {
        if (allValues == null) return;
        SharedPreferences.Editor ed = edit(target);
        for (Map.Entry<String, ?> e : allValues.entrySet()) {
            String key = e.getKey();
            if (configOnly && !ConfigDefaults.isConfigKey(key)) continue;
            putTyped(ed, key, e.getValue());
        }
        ed.apply();
    }

    static void copySingleKey(SharedPreferences target, SharedPreferences source, String key) {
        if (source == null || key == null) return;
        SharedPreferences.Editor ed = edit(target);
        if (!source.contains(key)) {
            ed.remove(key);
        } else {
            putTyped(ed, key, source.getAll().get(key));
        }
        ed.apply();
    }

    static void putTyped(SharedPreferences.Editor ed, String key, Object value) {
        if (ed == null || key == null) return;
        if (value == null) {
            ed.remove(key);
        } else if (value instanceof String) {
            ed.putString(key, (String) value);
        } else if (value instanceof Integer) {
            ed.putInt(key, (Integer) value);
        } else if (value instanceof Boolean) {
            ed.putBoolean(key, (Boolean) value);
        } else if (value instanceof Long) {
            ed.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            ed.putFloat(key, (Float) value);
        } else if (value instanceof Set) {
            @SuppressWarnings("unchecked")
            Set<String> set = (Set<String>) value;
            ed.putStringSet(key, set);
        }
    }

    static void clearIfNotEmpty(SharedPreferences prefs) {
        SharedPreferences target = resolve(prefs);
        Map<String, ?> all = target.getAll();
        if (all == null || all.isEmpty()) return;
        target.edit().clear().apply();
    }

    static void clearLocal(Context ctx, String prefsName) {
        SharedPreferences local = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        clearIfNotEmpty(local);
        deleteLocalIfEmpty(ctx, prefsName);
    }

    static void deleteLocalIfEmpty(Context ctx, String prefsName) {
        SharedPreferences local = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        Map<String, ?> all = local.getAll();
        if (all != null && !all.isEmpty()) return;
        try {
            ctx.deleteSharedPreferences(prefsName);
        } catch (Throwable ignored) {}
        try {
            File dir = new File(ctx.getApplicationInfo().dataDir, "shared_prefs");
            File xml = new File(dir, prefsName + ".xml");
            File bak = new File(dir, prefsName + ".xml.bak");
            if (xml.exists()) xml.delete();
            if (bak.exists()) bak.delete();
        } catch (Throwable ignored) {}
    }
}
