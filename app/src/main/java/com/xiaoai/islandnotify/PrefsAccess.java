package com.xiaoai.islandnotify;

import android.content.Context;
import android.content.SharedPreferences;

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

    static void clearIfNotEmpty(SharedPreferences prefs) {
        SharedPreferences target = resolve(prefs);
        Map<String, ?> all = target.getAll();
        if (all == null || all.isEmpty()) return;
        target.edit().clear().apply();
    }

    static void clearLocal(Context ctx, String prefsName) {
        SharedPreferences local = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        clearIfNotEmpty(local);
    }
}
