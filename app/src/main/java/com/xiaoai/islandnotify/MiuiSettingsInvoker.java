package com.xiaoai.islandnotify;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.Map;

/**
 * 动态定位并调用小爱同学（com.xiaomi.voiceassistant）内部的 SettingsUtil 工具类。
 *
 * <p>背景：小爱更新后混淆类名会变（如 utils.d6 → utils.e2），但以下特征不变：
 *   <ul>
 *     <li>静态字段中包含一个 Map，key 包含 "silent"/"Nointerferemode" 等字符串</li>
 *     <li>存在签名为 {@code boolean change(Context, String, int)} 的静态/实例方法</li>
 *     <li>类位于 {@code com.xiaomi.voiceassistant.utils.*} 包内</li>
 *   </ul>
 *
 * <p>版本缓存：找到的类名存入 island_custom SP，key = "settings_util_class_@{versionCode}"，
 * 仅在小爱版本升级时重新扫描，保证性能。
 *
 * <p>调用入口（均为 static，可在 voiceassist 进程任意位置调用）：
 * <pre>
 *   MiuiSettingsInvoker.init(appCtx, classLoader);   // Application.onCreate 时调用一次
 *   MiuiSettingsInvoker.applyMute(ctx, true);         // 静音
 *   MiuiSettingsInvoker.applyMute(ctx, false);        // 解除静音
 *   // 勿扰（预留，暂不使用）：
 *   MiuiSettingsInvoker.applyDnd(ctx, true/false);
 * </pre>
 */
public final class MiuiSettingsInvoker {

    private static final String TAG        = "IslandNotifyHook";
    private static final String PREFS_NAME = "island_custom";
    /** SP key 前缀，后接 versionCode */
    private static final String CACHE_KEY_PREFIX = "settings_util_class_@";
    /** 目标工具类所在包前缀 */
    private static final String UTILS_PKG  = "com.xiaomi.voiceassistant.utils.";
    /** 已定位的 change 方法；null 表示尚未初始化或未找到 */
    private static volatile Method sChangeMethod = null;
    /** change 方法的宿主对象（静态方法时为 null） */
    private static volatile Object sChangeTarget = null;

    // ──────────────────────────────────────────────────────────────
    // 公开 API
    // ──────────────────────────────────────────────────────────────

    /**
     * 在 Application.onCreate 中调用一次，传入目标应用的 Context 和 ClassLoader。
     * 会优先读取版本缓存，只有缓存失效才执行全量扫描。
     */
    public static void init(Context ctx, ClassLoader cl) {
        if (sChangeMethod != null) return; // 已初始化
        try {
            long versionCode = getVersionCode(ctx);
            String cacheKey  = CACHE_KEY_PREFIX + versionCode;
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String cachedName = sp.getString(cacheKey, null);

            if (cachedName != null) {
                Log.d(TAG, "[SettingsInvoker] 命中缓存 → " + cachedName);
                tryLoadClass(cl, cachedName, ctx, cacheKey, sp, false);
            }
            if (sChangeMethod == null) {
                Log.d(TAG, "[SettingsInvoker] 开始全量扫描...");
                scanAndCache(ctx, cl, cacheKey, sp);
            }
        } catch (Throwable t) {
            Log.d(TAG, "[SettingsInvoker] init 失败 → " + t.getMessage());
        }
    }

    /**
     * 调用 SettingsUtil.change(ctx, type, value)。
     * type 参考 SettingsUtil 内部 Map 的 key；value 通常 1=开启, 0=关闭。
     *
     * @return true=调用成功, false=invoker 未就绪（将由调用方回退到 AudioManager）
     */
    public static boolean invokeSwitch(Context ctx, String type, int value) {
        Method m = sChangeMethod;
        if (m == null) {
            Log.d(TAG, "[SettingsInvoker] invokeSwitch 未就绪 type=" + type);
            return false;
        }
        try {
            Object result = m.invoke(sChangeTarget, ctx, type, value);
            Log.d(TAG, "[SettingsInvoker] invokeSwitch(" + type + ", " + value
                    + ") → " + result);
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "[SettingsInvoker] invokeSwitch 失败 → " + t.getMessage());
            return false;
        }
    }

    // ── 快捷接口 ──────────────────────────────────────────────────

    /** 静音 / 解除静音。对应 SettingsUtil 的 "silent" key，value=1静音/0恢复。 */
    public static boolean applyMute(Context ctx, boolean mute) {
        return invokeSwitch(ctx, "silent", mute ? 1 : 0);
    }

    /**
     * 勿扰模式（预留，暂不使用）。
     * 对应 SettingsUtil 的 "Nointerferemode" key，value=1开启/0关闭。
     */
    public static boolean applyDnd(Context ctx, boolean enable) {
        return invokeSwitch(ctx, "Nointerferemode", enable ? 1 : 0);
    }

    // ──────────────────────────────────────────────────────────────
    // 内部实现
    // ──────────────────────────────────────────────────────────────

    /** 全量扫描 utils.* 下所有类，找到后写缓存。 */
    private static void scanAndCache(Context ctx, ClassLoader cl,
                                     String cacheKey, SharedPreferences sp) {
        try {
            for (String name : enumerateClassNames(cl)) {
                if (!name.startsWith(UTILS_PKG)) continue;
                if (tryLoadClass(cl, name, ctx, cacheKey, sp, true)) {
                    Log.d(TAG, "[SettingsInvoker] 扫描命中 → " + name);
                    return;
                }
            }
            Log.d(TAG, "[SettingsInvoker] 扫描结束，未找到 SettingsUtil");
        } catch (Throwable t) {
            Log.d(TAG, "[SettingsInvoker] scanAndCache 失败 → " + t.getMessage());
        }
    }

    /**
     * 尝试加载指定类并验证特征：
     *   1. 存在签名 change(Context, String, int) 的方法
     *   2. 存在至少一个 Map 类型的静态字段（dispatch table）
     * 验证通过后缓存 Method，返回 true。
     */
    private static boolean tryLoadClass(ClassLoader cl, String name,
                                        Context ctx, String cacheKey,
                                        SharedPreferences sp, boolean saveCache) {
        try {
            Class<?> cls = Class.forName(name, false, cl);
            Method changeMethod = findChangeMethod(cls);
            if (changeMethod == null) return false;
            if (!hasMapField(cls))    return false;

            // 验证：尝试用已知无害的 key 做一次空调用（静音 0）
            // 若抛异常说明不是目标方法，跳过
            changeMethod.setAccessible(true);
            Object target = null; // 先假设静态；若需要实例则另行处理
            try {
                changeMethod.invoke(target, ctx, "__probe__", 0);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                // 内部异常可接受（找到了正确的方法，只是 key 不存在）
            } catch (IllegalArgumentException iae) {
                // 参数不匹配，不是目标方法
                return false;
            }

            sChangeMethod = changeMethod;
            sChangeTarget = target;
            Log.d(TAG, "[SettingsInvoker] change 方法已绑定 → "
                    + cls.getName() + "#" + changeMethod.getName());

            if (saveCache) {
                sp.edit().putString(cacheKey, name).apply();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 在类中寻找 change(Context, String, int) 签名的方法（静态或实例均可）。 */
    private static Method findChangeMethod(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3
                    && p[0] == Context.class
                    && p[1] == String.class
                    && p[2] == int.class) {
                return m;
            }
        }
        return null;
    }

    /** 判断类中是否有 Map 类型的静态字段（用于区分 SettingsUtil 的 dispatch table）。 */
    private static boolean hasMapField(Class<?> cls) {
        for (Field f : cls.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(f.getType())) return true;
        }
        return false;
    }

    /**
     * 通过反射遍历 BaseDexClassLoader 内部的 DexFile，枚举所有类名。
     * 兼容 API 21 – 34（DexFile 已废弃但仍可反射访问）。
     */
    @SuppressWarnings("unchecked")
    private static java.util.List<String> enumerateClassNames(ClassLoader cl) {
        java.util.List<String> result = new java.util.ArrayList<>();
        try {
            // BaseDexClassLoader.pathList -> DexPathList.dexElements[] -> element.dexFile
            Field pathListField = findField(cl.getClass(), "pathList");
            if (pathListField == null) {
                // 向上查找父类
                pathListField = findField(cl.getClass().getSuperclass(), "pathList");
            }
            if (pathListField == null) return result;
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(cl);

            Field elementsField = findField(pathList.getClass(), "dexElements");
            if (elementsField == null) return result;
            elementsField.setAccessible(true);
            Object[] elements = (Object[]) elementsField.get(pathList);

            for (Object element : elements) {
                // element.dexFile (dalvik.system.DexFile)
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
            Log.d(TAG, "[SettingsInvoker] enumerateClassNames 失败 → " + t.getMessage());
        }
        return result;
    }

    /** 向上遍历类层次查找字段。 */
    private static Field findField(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            try { return cls.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static long getVersionCode(Context ctx) {
        try {
            android.content.pm.PackageInfo pi =
                    ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                return pi.getLongVersionCode();
            } else {
                //noinspection deprecation
                return pi.versionCode;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private MiuiSettingsInvoker() {}
}
