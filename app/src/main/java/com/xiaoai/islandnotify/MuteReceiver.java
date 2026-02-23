package com.xiaoai.islandnotify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;

/**
 * 上课静音接收器：收到广播后将铃声切为静音。
 * 由超级岛通知的"上课静音"按钮触发（miui.focus.actions → ACTION_KEY_MUTE）。
 */
public class MuteReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        } catch (SecurityException e) {
            try { am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE); } catch (Exception ignored) {}
        }
    }
}
