package com.xiaoai.islandnotify.modernhook;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

public final class XposedBridge {

    private static volatile XposedInterface sXposed;

    private XposedBridge() {
    }

    public static void init(XposedInterface xposedInterface) {
        sXposed = xposedInterface;
    }

    public static void log(String text) {
        XposedInterface api = sXposed;
        if (api != null) {
            api.log(Log.INFO, "IslandNotify", text == null ? "null" : text);
        } else {
            Log.i("IslandNotify", text == null ? "null" : text);
        }
    }

    public static void log(Throwable t) {
        XposedInterface api = sXposed;
        if (api != null) {
            api.log(Log.ERROR, "IslandNotify", "Hook exception", t);
        } else {
            Log.e("IslandNotify", "Hook exception", t);
        }
    }

    public static SharedPreferences getRemotePreferences(String group) {
        XposedInterface api = sXposed;
        if (api == null) {
            throw new IllegalStateException("Xposed API context is not initialized");
        }
        return api.getRemotePreferences(group);
    }

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        XposedInterface api = sXposed;
        if (api == null) {
            throw new IllegalStateException("Xposed API context is not initialized");
        }
        if (!(method instanceof Executable)) {
            throw new IllegalArgumentException("Only Method/Constructor can be hooked");
        }
        Executable executable = (Executable) method;
        Object handle = api.hook(executable).intercept(chain -> {
            XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
            param.method = method;
            param.thisObject = chain.getThisObject();
            List<Object> chainArgs = chain.getArgs();
            param.args = chainArgs.toArray(new Object[0]);

            callback.beforeHookedMethod(param);

            if (!param.isReturnEarly()) {
                try {
                    param.setResultNoEarly(chain.proceed(param.args));
                } catch (Throwable t) {
                    param.setThrowable(t);
                }
            }

            callback.afterHookedMethod(param);

            if (param.hasThrowable()) {
                throw param.getThrowable();
            }
            return param.getResult();
        });
        return callback.new Unhook(method, handle);
    }
}
