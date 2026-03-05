package com.xiaoai.islandnotify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class TotalWeekReceiver extends BroadcastReceiver {
    public static final String ACTION_UPDATE_TOTAL_WEEK = "com.xiaoai.islandnotify.ACTION_UPDATE_TOTAL_WEEK";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_UPDATE_TOTAL_WEEK.equals(intent.getAction())) {
            int tw = intent.getIntExtra("course_total_week", 0);
            if (tw > 0) {
                SharedPreferences sp = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
                sp.edit().putInt("course_total_week", tw).apply();
                Log.d("IslandNotify", "TotalWeekReceiver: updated total week to " + tw);
            }
        }
    }
}
