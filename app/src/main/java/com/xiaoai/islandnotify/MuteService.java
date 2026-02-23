package com.xiaoai.islandnotify;

import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.os.IBinder;

/**
 * 上课静音 Service。
 *
 * <p>Super Island 按钮使用 {@code actionIntentType=3}（startService）调用。
 * Service 比广播在 MIUI 上更可靠，且不受 Android 13+ 隐式广播限制。
 * 静音完成后立即 stopSelf()。
 */
public class MuteService extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            try {
                am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            } catch (SecurityException e) {
                try { am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE); } catch (Exception ignored) {}
            }
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
