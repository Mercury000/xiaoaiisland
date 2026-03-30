package com.xiaoai.islandnotify;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.xiaoai.islandnotify.modernhook.XposedBridge;

import io.github.libxposed.api.XposedModule;

public class ModuleEntry extends XposedModule {

    private static final String TAG = "IslandNotifyModule";

    private final MainHook mainHook = new MainHook();
    private final DeskClockHook deskClockHook = new DeskClockHook();
    private final SystemUiHook systemUiHook = new SystemUiHook();
    private volatile String processName = "";

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        processName = param.getProcessName();
        XposedBridge.init(this);
        XposedBridge.log(TAG + ": onModuleLoaded process=" + processName);
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.Q)
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        XposedBridge.init(this);

        String packageName = param.getPackageName();
        String resolvedProcessName = processName == null || processName.isEmpty()
                ? param.getPackageName()
                : processName;
        ClassLoader classLoader = param.getDefaultClassLoader();

        try {
            mainHook.handleLoadPackage(packageName, resolvedProcessName, classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": MainHook failed -> " + t.getMessage());
            XposedBridge.log(t);
        }

        try {
            deskClockHook.handleLoadPackage(packageName, classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": DeskClockHook failed -> " + t.getMessage());
            XposedBridge.log(t);
        }

        try {
            systemUiHook.handleLoadPackage(packageName, classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": SystemUiHook failed -> " + t.getMessage());
            XposedBridge.log(t);
        }
    }
}

