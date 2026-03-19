package com.xiaoai.islandnotify;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Enumeration;
import com.xiaoai.islandnotify.modernhook.XposedBridge;

/**
 * 动态定位并调用小爱同学内部的 TimeTableHelper.downCourseInfoData。
 */
public final class TimeTableHelperInvoker {

    private static final String TAG = "IslandNotifyHook";
    private static final String PREFS_NAME = "island_custom";
    private static final String CACHE_KEY_PREFIX = "timetable_helper_class_@";
    
    private static volatile Method sDownMethod = null;
    private static volatile Object sInstance = null;

    public static void init(Context ctx, ClassLoader cl) {
        if (sDownMethod != null) return;
        try {
            long versionCode = getVersionCode(ctx);
            String cacheKey = CACHE_KEY_PREFIX + versionCode;
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String cachedName = sp.getString(cacheKey, null);

            if (cachedName != null) {
                XposedBridge.log(TAG + ": [TimeTableInvoker] 命中缓存 → " + cachedName);
                if (tryLoadClass(cl, cachedName, cacheKey, sp, false)) return;
            }

            XposedBridge.log(TAG + ": [TimeTableInvoker] 开始全量扫描...");
            scanAndCache(ctx, cl, cacheKey, sp);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [TimeTableInvoker] init 失败 → " + t.getMessage());
        }
    }

    public static boolean triggerUpdate(Context ctx, String from, boolean fromH5) {
        Method m = sDownMethod;
        Object target = sInstance;
        if (m == null || target == null) {
            XposedBridge.log(TAG + ": [TimeTableInvoker] triggerUpdate 未就绪");
            return false;
        }
        try {
            m.invoke(target, ctx, from, fromH5);
            XposedBridge.log(TAG + ": [TimeTableInvoker] 已触发主动更新 from=" + from);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [TimeTableInvoker] triggerUpdate 失败 → " + t.getMessage());
            return false;
        }
    }

    private static void scanAndCache(Context ctx, ClassLoader cl, String cacheKey, SharedPreferences sp) {
        try {
            for (String name : enumerateClassNames(cl)) {
                // 移除包名前缀限制，应对各种混淆情况
                if (tryLoadClass(cl, name, cacheKey, sp, true)) {
                    XposedBridge.log(TAG + ": [TimeTableInvoker] 扫描命中 → " + name);
                    return;
                }
            }
            XposedBridge.log(TAG + ": [TimeTableInvoker] 扫描结束，未找到 TimeTableHelper");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [TimeTableInvoker] scanAndCache 失败 → " + t.getMessage());
        }
    }

    private static boolean tryLoadClass(ClassLoader cl, String name, String cacheKey, SharedPreferences sp, boolean saveCache) {
        try {
            Class<?> cls = Class.forName(name, false, cl);
            // 特征1：包含特征字符串（weekCourseBean 是 TimeTableHelper 的核心数据 Key）
            boolean hasFeatureStr = false;
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if ("weekCourseBean".equals(val) || "todayCourseIsEnd".equals(val)) {
                        hasFeatureStr = true;
                        break;
                    }
                }
            }
            if (!hasFeatureStr) return false;

            // 特征2：包含 downCourseInfoData(Context, String, boolean)
            // 该方法签名为 (Context, String, boolean)V
            Method m = null;
            for (Method method : cls.getDeclaredMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (p.length == 3 && p[0] == Context.class && p[1] == String.class && p[2] == boolean.class
                        && method.getReturnType() == void.class) {
                    m = method;
                    break;
                }
            }
            if (m == null) return false;

            // 特征3：Kotlin Singleton 实例字段 'a' (Lj80/n;->a:Lj80/n;)
            Field instanceField = null;
            try { instanceField = cls.getDeclaredField("a"); } catch (Throwable ignored) {}
            if (instanceField == null) return false;
            instanceField.setAccessible(true);
            Object instance = instanceField.get(null);
            if (instance == null || !cls.isInstance(instance)) return false;

            sDownMethod = m;
            sInstance = instance;
            m.setAccessible(true);

            if (saveCache) {
                sp.edit().putString(cacheKey, name).apply();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> enumerateClassNames(ClassLoader cl) {
        java.util.List<String> result = new java.util.ArrayList<>();
        try {
            Field pathListField = findField(cl.getClass(), "pathList");
            if (pathListField == null) pathListField = findField(cl.getClass().getSuperclass(), "pathList");
            if (pathListField == null) return result;
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(cl);

            Field elementsField = findField(pathList.getClass(), "dexElements");
            if (elementsField == null) return result;
            elementsField.setAccessible(true);
            Object[] elements = (Object[]) elementsField.get(pathList);

            for (Object element : elements) {
                Field dexFileField = findField(element.getClass(), "dexFile");
                if (dexFileField == null) continue;
                dexFileField.setAccessible(true);
                Object dexFile = dexFileField.get(element);
                if (dexFile == null) continue;

                Method entriesMethod = dexFile.getClass().getMethod("entries");
                Enumeration<String> entries = (Enumeration<String>) entriesMethod.invoke(dexFile);
                while (entries.hasMoreElements()) {
                    result.add(entries.nextElement());
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [TimeTableInvoker] enumerateClassNames 失败 → " + t.getMessage());
        }
        return result;
    }

    private static Field findField(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            try { return cls.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static long getVersionCode(Context ctx) {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                return pi.getLongVersionCode();
            } else {
                return pi.versionCode;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private TimeTableHelperInvoker() {}
}
