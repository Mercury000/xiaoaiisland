package com.xiaoai.islandnotify;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

final class AlarmScheduler {

    private AlarmScheduler() {}

    private static final int SERVICE_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

    static Intent buildServiceIntent(String targetPackage, String serviceClassName, String action) {
        Intent intent = new Intent(action);
        intent.setClassName(targetPackage, serviceClassName);
        return intent;
    }

    static boolean scheduleAlarmClock(Context ctx,
                                      Intent serviceIntent,
                                      int requestCode,
                                      String showAction,
                                      String showPackage,
                                      int showRequestCode,
                                      boolean showUpdateCurrent,
                                      long triggerAtMillis) {
        if (ctx == null || serviceIntent == null || showAction == null || showPackage == null) return false;
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return false;
        PendingIntent servicePi = PendingIntent.getService(ctx, requestCode, serviceIntent, SERVICE_FLAGS);
        PendingIntent showPi = PendingIntent.getBroadcast(
                ctx,
                showRequestCode,
                new Intent(showAction).setPackage(showPackage),
                showFlags(showUpdateCurrent));
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAtMillis, showPi), servicePi);
        return true;
    }

    static void cancelAlarmClock(Context ctx,
                                 Intent serviceIntent,
                                 int requestCode,
                                 String showAction,
                                 String showPackage,
                                 int showRequestCode,
                                 boolean showUpdateCurrent) {
        if (ctx == null || serviceIntent == null || showAction == null || showPackage == null) return;
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;

        PendingIntent servicePi = PendingIntent.getService(ctx, requestCode, serviceIntent, SERVICE_FLAGS);
        if (servicePi != null) {
            am.cancel(servicePi);
            servicePi.cancel();
        }
        PendingIntent showPi = PendingIntent.getBroadcast(
                ctx,
                showRequestCode,
                new Intent(showAction).setPackage(showPackage),
                showFlags(showUpdateCurrent));
        if (showPi != null) {
            am.cancel(showPi);
            showPi.cancel();
        }
    }

    static int reqCodeForMuteAction(int alarmId, String action) {
        int aid = alarmId & 0x00FFFFFF;
        if ("com.xiaoai.islandnotify.DO_MUTE".equals(action)) return aid | 0x01000000;
        if ("com.xiaoai.islandnotify.DO_UNMUTE".equals(action)) return aid | 0x02000000;
        if ("com.xiaoai.islandnotify.DO_DND_ON".equals(action)) return aid | 0x03000000;
        return aid | 0x04000000;
    }

    private static int showFlags(boolean updateCurrent) {
        int flags = PendingIntent.FLAG_IMMUTABLE;
        if (updateCurrent) flags |= PendingIntent.FLAG_UPDATE_CURRENT;
        return flags;
    }
}

