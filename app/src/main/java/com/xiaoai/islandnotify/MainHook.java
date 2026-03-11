package com.xiaoai.islandnotify;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

/**
 * LSPosed 主 Hook 类
 * 功能：拦截 com.miui.voiceassist 发送的"课程表提醒"通知，
 *       注入 miui.focus.param 参数，将其升级为小米超级岛通知。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "IslandNotifyHook";

    /** 目标应用包名（小爱同学） */
    private static final String TARGET_PACKAGE = "com.miui.voiceassist";

    /** AlarmManager 触发上课静音（发给 voiceassist 自身，不受 MIUI 电池限制） */
    private static final String ACTION_DO_MUTE   = "com.xiaoai.islandnotify.DO_MUTE";
    /** AlarmManager 触发下课解除静音（发给 voiceassist 自身，不受 MIUI 电池限制） */
    private static final String ACTION_DO_UNMUTE    = "com.xiaoai.islandnotify.DO_UNMUTE";
    /** AlarmManager 触发上课开启勿扰（DND） */
    private static final String ACTION_DO_DND_ON    = "com.xiaoai.islandnotify.DO_DND_ON";
    /** AlarmManager 触发下课关闭勿扰（DND） */
    private static final String ACTION_DO_DND_OFF   = "com.xiaoai.islandnotify.DO_DND_OFF";
    /** 超级岛按钮手动触发：立即应用所有已配置的静音/勿扰 */
    private static final String ACTION_MANUAL_MUTE   = "com.xiaoai.islandnotify.MANUAL_MUTE";
    /** 超级岛按钮手动触发：立即解除所有已配置的静音/勿扰 */
    private static final String ACTION_MANUAL_UNMUTE = "com.xiaoai.islandnotify.MANUAL_UNMUTE";
    /** 每日 00:01 跨日重调广播 Action（链式保证次日课程 alarm 不丢失） */
    private static final String ACTION_RESCHEDULE_DAILY = "com.xiaoai.islandnotify.ACTION_RESCHEDULE_DAILY";
    /** 通知定时取消广播 Action（替代 Handler.postDelayed，setAlarmClock 保证精确触发） */
    private static final String ACTION_NOTIF_CANCEL = "com.xiaoai.islandnotify.ACTION_NOTIF_CANCEL";
    /** shareData 拖拽分享图片在 miui.focus.pics Bundle 中的 key */
    private static final String PIC_KEY_SHARE = "miui.focus.pic_share";
    /** voiceassist Manifest 中已声明的 Service，用于 AlarmManager 在进程死后强制拉起 */
    private static final String UPLOAD_STATE_SERVICE = "com.xiaomi.voiceassistant.UploadStateService";

    /** 点击课程卡片整体 → 跳转课表页的 Intent URI */
    private static final String COURSE_TABLE_INTENT =
            "intent://aiweb?url=https%3A%2F%2Fi.ai.mi.com%2Fh5%2Fprecache%2Fai-schedule%2F%23%2FtodayLesson" +
            "&flag=805339136&noBack=false&statusBarColor=FFFFFF&statusBarTextBlack=true" +
            "&navigationBarColor=FFFFFF#Intent;scheme=voiceassist;package=com.miui.voiceassist;end";

    /** 岛通知主参数 Key */
    private static final String KEY_FOCUS_PARAM = "miui.focus.param";

    /** SharedPreferences 名称（与 MainActivity 保持一致） */
    private static final String PREFS_NAME = "island_custom";
    /** 模块自身包名，用于跨进程读取 SharedPreferences */
    private static final String MODULE_PKG  = "com.xiaoai.islandnotify";

    /** 同步偏好设置到目标进程的广播 Action */
    private static final String ACTION_SYNC_PREFS = "com.xiaoai.islandnotify.ACTION_SYNC_PREFS";
    /** 模块查询偏好设置的广播 Action */
    private static final String ACTION_QUERY_PREFS = "com.xiaoai.islandnotify.ACTION_QUERY_PREFS";
    /** 宿主回传偏好设置的广播 Action */
    private static final String ACTION_REPLY_PREFS = "com.xiaoai.islandnotify.ACTION_REPLY_PREFS";
    /** AlarmManager 闹钟触发岛状态更新的广播 Action */
    private static final String ACTION_ISLAND_UPDATE = "com.xiaoai.islandnotify.ACTION_ISLAND_UPDATE";
    /** 触发目标应用发送测试通知的广播 Action */
    private static final String ACTION_TEST_NOTIFY = "com.xiaoai.islandnotify.ACTION_TEST_NOTIFY";
    /** 定时触发课前提醒通知的广播 Action */
    private static final String ACTION_COURSE_REMINDER = "com.xiaoai.islandnotify.ACTION_COURSE_REMINDER";
    /** CourseData SharedPreferences 名称（voiceassist 自身） */
    private static final String PREFS_COURSE_DATA = "CourseData";
    /** 课前提醒分钟数配置键（存入 island_custom SP） */
    private static final String KEY_REMINDER_MINUTES = "reminder_minutes_before";
    /** 课前提醒默认提前分钟数 */
    private static final int DEFAULT_REMINDER_MINUTES = 15;
    /** CourseData.xml FileObserver，跨进程写入时仍能感知 */
    private android.os.FileObserver mCourseDataObserver;
    /** CourseData 变化防抖延迟（ms）：合并同一次写入触发的多个 inotify 事件 */
    private static final int RESCHEDULE_DEBOUNCE_MS = 1500;
    /** 防抖 Handler，懒加载避免 Xposed 初始化阶段 Looper 未就绪导致 NPE */
    private android.os.Handler mRescheduleHandler;
    /** 防抖 token，用于 removeCallbacksAndMessages */
    private final Object mRescheduleToken = new Object();
    /** 上次成功调度时 weekCourseBean 的 hashCode；FileObserver 触发时若内容未变则跳过重调度，避免补发重复通知 */
    private volatile int mLastCourseDataHash = 0;
    /** 测试通知自增 ID，避免多次测试因相同 ID 互相替换 */
    private static final java.util.concurrent.atomic.AtomicInteger sTestNotifId =
            new java.util.concurrent.atomic.AtomicInteger(2001);
    /** 上一条测试通知的 ID，用于发新测试前自动取消旧通知；-1 表示尚无 */
    private static volatile int sLastTestNotifId = -1;
    /** 已调度的课前提醒 alarmId 集合，关闭开关或重新调度时用于批量取消 */
    private final java.util.Set<Integer> mScheduledAlarmIds = new java.util.HashSet<>();

    // ── 自动静音相关常量 ──
    private static final String KEY_MUTE_ENABLED         = "mute_enabled";
    private static final String KEY_MUTE_MINS_BEFORE      = "mute_mins_before";   // 上课前多少分钟静音
    private static final String KEY_UNMUTE_ENABLED        = "unmute_enabled";
    private static final String KEY_UNMUTE_MINS_AFTER     = "unmute_mins_after";  // 下课后多少分钟取消静音
    private static final String KEY_DND_ENABLED           = "dnd_enabled";        // 勿扰独立开关
    private static final String KEY_DND_MINS_BEFORE       = "dnd_mins_before";    // 上课前多少分钟开启勿扰
    private static final String KEY_UNDND_ENABLED         = "undnd_enabled";      // 下课自动关闭勿扰
    private static final String KEY_UNDND_MINS_AFTER      = "undnd_mins_after";   // 下课后多少分钟关闭勿扰
    private static final int    DEFAULT_MUTE_MINS_BEFORE  = 0;
    private static final int    DEFAULT_UNMUTE_MINS_AFTER = 0;
    private static final int    DEFAULT_DND_MINS_BEFORE   = 0;
    private static final int    DEFAULT_UNDND_MINS_AFTER  = 0;
    // ── 自动叫醒（系统闹钟）相关常量 ──
    private static final String ACTION_SCHEDULE_CLOCK_ALARMS  = DeskClockHook.ACTION_SCHEDULE_CLOCK_ALARMS;
    private static final String DESKCLOCK_PKG                  = "com.android.deskclock";
    private static final String KEY_WAKEUP_MORNING_ENABLED      = "wakeup_morning_enabled";
    private static final String KEY_WAKEUP_MORNING_LAST_SEC     = "wakeup_morning_last_sec";
    private static final String KEY_WAKEUP_MORNING_RULES_JSON    = "wakeup_morning_rules_json";
    private static final String KEY_WAKEUP_AFTERNOON_ENABLED      = "wakeup_afternoon_enabled";
    private static final String KEY_WAKEUP_AFTERNOON_FIRST_SEC    = "wakeup_afternoon_first_sec";
    private static final String KEY_WAKEUP_AFTERNOON_RULES_JSON   = "wakeup_afternoon_rules_json";
    private static final int    DEFAULT_WAKEUP_MORNING_LAST_SEC       = 4;
    private static final int    DEFAULT_WAKEUP_AFTERNOON_FIRST_SEC    = 5;
    private static final String DEFAULT_WAKEUP_MORNING_RULES_JSON     = "[{\"sec\":1,\"hour\":7,\"minute\":0}]";
    private static final String DEFAULT_WAKEUP_AFTERNOON_RULES_JSON   = "[{\"sec\":5,\"hour\":12,\"minute\":0}]";
    /** 静音功能开关（volatile，跨 Xposed 回调线程读取） */
    private static volatile boolean sMuteEnabled   = false;
    /** 取消静音功能开关 */
    private static volatile boolean sUnmuteEnabled = false;
    /** 勿扰独立开关（与静音相互独立，可同时启用） */
    private static volatile boolean sDndEnabled    = false;
    private static volatile boolean sUnDndEnabled  = false;
    /** 自动叫醒功能开关 */
    private static volatile boolean sWakeupMorningEnabled   = false;
    private static volatile boolean sWakeupAfternoonEnabled = false;
    /** 超级岛按钮功能模式：0=仅静音, 1=仅勿扰, 2=两者 */
    private static volatile int sIslandButtonMode = 2;
    /** 已调度的静音/取消静音 alarm reqCode 集合，用于批量取消 */
    private final java.util.Set<Integer> mScheduledMuteIds = new java.util.HashSet<>();

    /** 辅助方法：持久化已调度的 ID 集合到 SP */
    private void saveScheduledIds(Context ctx, String key, java.util.Set<Integer> ids) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray();
            synchronized (ids) {
                for (Integer id : ids) arr.put(id);
            }
            sp.edit().putString(key, arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    /** 辅助方法：从 SP 加载已调度的 ID 集合 */
    private java.util.Set<Integer> loadScheduledIds(Context ctx, String key) {
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = sp.getString(key, null);
            if (json != null) {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) ids.add(arr.getInt(i));
            }
        } catch (Throwable ignored) {}
        return ids;
    }
    /** 有连续后续课程的通知 alarmId 集合：injectIslandParams 跳过 cancel alarm 注册，
     *  防止中间课程通知被提前清除；cancel 由 consecutive 更新路径接管后统一重建。 */
    private final java.util.Set<Integer> mConsecutiveAnchors =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** 通知 id → 当前持有该通知的课程名，防止旧课程的陈旧 STATE_FINISHED 广播在新课更新后覆写岛 */
    private final java.util.Map<Integer, String> mNotifCourseOwner =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 获取（或创建）防抖 Handler，保证在主 Looper 就绪后才初始化。 */
    private android.os.Handler getRescheduleHandler() {
        if (mRescheduleHandler == null) {
            mRescheduleHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        return mRescheduleHandler;
    }
    /** 跨 ClassLoader 的防重复注入标记 key（存于 boot classloader 的 System.properties） */
    private static final String HOOKED_KEY = "xiaoai.island.hooked";

    // ─────────────────────────────────────────────────────────────

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 注入自身进程 → hook isModuleActive()
        if ("com.xiaoai.islandnotify".equals(lpparam.packageName)) {
            hookSelfStatus(lpparam);
            return;
        }
        // 只注入目标进程
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        // 只注入主进程（processName == packageName），子进程如 :push/:remote 跳过
        // 各 OS 进程的 System.properties 相互独立，仅靠 HOOKED_KEY 无法去重跨进程重复
        if (!TARGET_PACKAGE.equals(lpparam.processName)) {
            return;
        }
        // 同一进程可能因多 ClassLoader 被调用多次，只注册一次
        // 用 System.setProperty 而非 static 字段，确保跨 ClassLoader 生效
        if (System.getProperty(HOOKED_KEY) != null) return;
        System.setProperty(HOOKED_KEY, "1");
        XposedBridge.log(TAG + ": 已注入目标进程 → " + TARGET_PACKAGE);
        hookApplicationOnCreate(lpparam);
        hookUploadStateService(lpparam);
        hookNotifyMethods(lpparam);
    }

    /**     * Hook 目标 App 的 Application.onCreate，在其进程内注册 BroadcastReceiver。
     * 收到 ACTION_SYNC_PREFS 广播时，将偷好设置写入目标进程自己的 SharedPreferences，
     * 彻底绕过 SELinux 跨 UID 文件读取限制。
     */
    private void hookApplicationOnCreate(XC_LoadPackage.LoadPackageParam lpparam) {
        findAndHookMethod("android.app.Application", lpparam.classLoader,
                "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context appCtx = (Context) param.thisObject;
                IntentFilter filter = new IntentFilter(ACTION_SYNC_PREFS);
                filter.addAction(ACTION_ISLAND_UPDATE);
                filter.addAction(ACTION_TEST_NOTIFY);
                filter.addAction(ACTION_COURSE_REMINDER);
                filter.addAction(ACTION_DO_MUTE);
                filter.addAction(ACTION_DO_UNMUTE);
                filter.addAction(ACTION_DO_DND_ON);
                filter.addAction(ACTION_DO_DND_OFF);
                filter.addAction(ACTION_MANUAL_MUTE);
                filter.addAction(ACTION_MANUAL_UNMUTE);
                filter.addAction(ACTION_RESCHEDULE_DAILY);
                filter.addAction(ACTION_NOTIF_CANCEL);
                filter.addAction(ACTION_QUERY_PREFS);
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (ACTION_SYNC_PREFS.equals(intent.getAction())) {
                            SharedPreferences.Editor ed = context
                                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                            for (String sfx : new String[]{"_pre", "_active", "_post"}) {
                                String kA = "tpl_a" + sfx;
                                if (intent.hasExtra(kA)) ed.putString(kA, intent.getStringExtra(kA));
                                String kB = "tpl_b" + sfx;
                                if (intent.hasExtra(kB)) ed.putString(kB, intent.getStringExtra(kB));
                                String kT = "tpl_ticker" + sfx;
                                if (intent.hasExtra(kT)) ed.putString(kT, intent.getStringExtra(kT));
                            }
                            if (intent.hasExtra("icon_a")) {
                                ed.putBoolean("icon_a", intent.getBooleanExtra("icon_a", true));
                            }
                            // 同步超时设置：岛消失改为三阶段独立；通知消失按阶段
                            for (String phase : new String[]{"pre", "active", "post"}) {
                                String kIsVal  = "to_island_val_"  + phase;
                                String kIsUnit = "to_island_unit_" + phase;
                                String kNoVal  = "to_notif_val_"   + phase;
                                String kNoUnit = "to_notif_unit_"  + phase;
                                if (intent.hasExtra(kIsVal))  ed.putInt   (kIsVal,  intent.getIntExtra   (kIsVal, -1));
                                if (intent.hasExtra(kIsUnit)) ed.putString(kIsUnit, safeStr(intent.getStringExtra(kIsUnit)));
                                if (intent.hasExtra(kNoVal))  ed.putInt   (kNoVal,  intent.getIntExtra   (kNoVal, -1));
                                if (intent.hasExtra(kNoUnit)) ed.putString(kNoUnit, safeStr(intent.getStringExtra(kNoUnit)));
                            }
                            if (intent.hasExtra(KEY_REMINDER_MINUTES)) {
                                ed.putInt(KEY_REMINDER_MINUTES, intent.getIntExtra(
                                        KEY_REMINDER_MINUTES, DEFAULT_REMINDER_MINUTES));
                            }
                            if (intent.hasExtra(KEY_MUTE_ENABLED)) {
                                ed.putBoolean(KEY_MUTE_ENABLED,
                                        intent.getBooleanExtra(KEY_MUTE_ENABLED, false));
                            }
                            if (intent.hasExtra(KEY_MUTE_MINS_BEFORE)) {
                                ed.putInt(KEY_MUTE_MINS_BEFORE,
                                        intent.getIntExtra(KEY_MUTE_MINS_BEFORE, DEFAULT_MUTE_MINS_BEFORE));
                            }
                            if (intent.hasExtra(KEY_UNMUTE_ENABLED)) {
                                ed.putBoolean(KEY_UNMUTE_ENABLED,
                                        intent.getBooleanExtra(KEY_UNMUTE_ENABLED, false));
                            }
                            if (intent.hasExtra(KEY_UNMUTE_MINS_AFTER)) {
                                ed.putInt(KEY_UNMUTE_MINS_AFTER,
                                        intent.getIntExtra(KEY_UNMUTE_MINS_AFTER, DEFAULT_UNMUTE_MINS_AFTER));
                            }
                            if (intent.hasExtra(KEY_DND_ENABLED)) {
                                ed.putBoolean(KEY_DND_ENABLED,
                                        intent.getBooleanExtra(KEY_DND_ENABLED, false));
                            }
                            if (intent.hasExtra(KEY_DND_MINS_BEFORE)) {
                                ed.putInt(KEY_DND_MINS_BEFORE,
                                        intent.getIntExtra(KEY_DND_MINS_BEFORE, DEFAULT_DND_MINS_BEFORE));
                            }
                            if (intent.hasExtra(KEY_UNDND_ENABLED)) {
                                ed.putBoolean(KEY_UNDND_ENABLED,
                                        intent.getBooleanExtra(KEY_UNDND_ENABLED, false));
                            }
                            if (intent.hasExtra(KEY_UNDND_MINS_AFTER)) {
                                ed.putInt(KEY_UNDND_MINS_AFTER,
                                        intent.getIntExtra(KEY_UNDND_MINS_AFTER, DEFAULT_UNDND_MINS_AFTER));
                            }
                            if (intent.hasExtra(KEY_WAKEUP_MORNING_ENABLED))
                                ed.putBoolean(KEY_WAKEUP_MORNING_ENABLED, intent.getBooleanExtra(KEY_WAKEUP_MORNING_ENABLED, false));
                            if (intent.hasExtra(KEY_WAKEUP_MORNING_LAST_SEC))
                                ed.putInt(KEY_WAKEUP_MORNING_LAST_SEC, intent.getIntExtra(KEY_WAKEUP_MORNING_LAST_SEC, DEFAULT_WAKEUP_MORNING_LAST_SEC));
                            if (intent.hasExtra(KEY_WAKEUP_MORNING_RULES_JSON))
                                ed.putString(KEY_WAKEUP_MORNING_RULES_JSON, intent.getStringExtra(KEY_WAKEUP_MORNING_RULES_JSON));
                            if (intent.hasExtra(KEY_WAKEUP_AFTERNOON_ENABLED))
                                ed.putBoolean(KEY_WAKEUP_AFTERNOON_ENABLED, intent.getBooleanExtra(KEY_WAKEUP_AFTERNOON_ENABLED, false));
                            if (intent.hasExtra(KEY_WAKEUP_AFTERNOON_FIRST_SEC))
                                ed.putInt(KEY_WAKEUP_AFTERNOON_FIRST_SEC, intent.getIntExtra(KEY_WAKEUP_AFTERNOON_FIRST_SEC, DEFAULT_WAKEUP_AFTERNOON_FIRST_SEC));
                            if (intent.hasExtra(KEY_WAKEUP_AFTERNOON_RULES_JSON))
                                ed.putString(KEY_WAKEUP_AFTERNOON_RULES_JSON, intent.getStringExtra(KEY_WAKEUP_AFTERNOON_RULES_JSON));
                            if (intent.hasExtra("island_button_mode")) {
                                ed.putInt("island_button_mode", intent.getIntExtra("island_button_mode", 2));
                            }
                            ed.apply();
                            // 自动同步假期数据（去年、今年、未来两年）到 island_holiday 文件
                            int currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
                            for (int yr = currentYear - 1; yr <= currentYear + 2; yr++) {
                                String hKey = HolidayManager.EXTRA_LIST_PREFIX + yr;
                                if (intent.hasExtra(hKey)) {
                                    String hJson = intent.getStringExtra(hKey);
                                    context.getSharedPreferences(HolidayManager.PREFS_HOLIDAY,
                                            Context.MODE_PRIVATE)
                                           .edit().putString("list_" + yr, hJson).apply();
                                    XposedBridge.log(TAG + ": 假期数据已同步 " + yr + "年");
                                }
                            }
                            // 同步内存开关
                            if (intent.hasExtra(KEY_MUTE_ENABLED))
                                sMuteEnabled   = intent.getBooleanExtra(KEY_MUTE_ENABLED, false);
                            if (intent.hasExtra(KEY_UNMUTE_ENABLED))
                                sUnmuteEnabled = intent.getBooleanExtra(KEY_UNMUTE_ENABLED, false);
                            if (intent.hasExtra(KEY_DND_ENABLED))
                                sDndEnabled    = intent.getBooleanExtra(KEY_DND_ENABLED, false);
                            if (intent.hasExtra(KEY_UNDND_ENABLED))
                                sUnDndEnabled  = intent.getBooleanExtra(KEY_UNDND_ENABLED, false);
                            if (intent.hasExtra(KEY_WAKEUP_MORNING_ENABLED))
                                sWakeupMorningEnabled   = intent.getBooleanExtra(KEY_WAKEUP_MORNING_ENABLED, false);
                            if (intent.hasExtra(KEY_WAKEUP_AFTERNOON_ENABLED))
                                sWakeupAfternoonEnabled = intent.getBooleanExtra(KEY_WAKEUP_AFTERNOON_ENABLED, false);
                            if (intent.hasExtra("island_button_mode"))
                                sIslandButtonMode = intent.getIntExtra("island_button_mode", 2);
                            // 提醒分钟数或静音参数变化时重新调度
                            boolean muteSettingChanged = intent.hasExtra(KEY_MUTE_ENABLED)
                                    || intent.hasExtra(KEY_MUTE_MINS_BEFORE)
                                    || intent.hasExtra(KEY_UNMUTE_ENABLED)
                                    || intent.hasExtra(KEY_UNMUTE_MINS_AFTER)
                                    || intent.hasExtra(KEY_DND_ENABLED)
                                    || intent.hasExtra(KEY_DND_MINS_BEFORE)
                                    || intent.hasExtra(KEY_UNDND_ENABLED)
                                    || intent.hasExtra(KEY_UNDND_MINS_AFTER);
                            boolean reminderSettingChanged = intent.hasExtra(KEY_REMINDER_MINUTES);
                            
                            if (reminderSettingChanged || muteSettingChanged) {
                                // 偏好变化仅触发本地重新调度，不触发消耗资源的主动网络更新
                                safeReschedule(context, "island_sync_prefs", false);
                            }

                            // 叫醒闹钟设置变化时重新调度
                            boolean wakeupSettingChanged = intent.hasExtra(KEY_WAKEUP_MORNING_ENABLED)
                                    || intent.hasExtra(KEY_WAKEUP_MORNING_LAST_SEC)
                                    || intent.hasExtra(KEY_WAKEUP_MORNING_RULES_JSON)
                                    || intent.hasExtra(KEY_WAKEUP_AFTERNOON_ENABLED)
                                    || intent.hasExtra(KEY_WAKEUP_AFTERNOON_FIRST_SEC)
                                    || intent.hasExtra(KEY_WAKEUP_AFTERNOON_RULES_JSON);
                            if (wakeupSettingChanged) {
                                scheduleTodayWakeupAlarms(context);
                            }
                            // 记录接收到的 extras 与写入的键值，便于排查模块进程与目标进程不一致问题
                            try {
                                StringBuilder sb = new StringBuilder();
                                sb.append("SYNC_PREFS extras:\n");
                                for (String key : intent.getExtras().keySet()) {
                                    Object v = intent.getExtras().get(key);
                                    sb.append(key).append("=").append(String.valueOf(v)).append("\n");
                                }
                                sb.append("Saved prefs:\n");
                                SharedPreferences saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                                for (String k : saved.getAll().keySet()) {
                                    Object vv = saved.getAll().get(k);
                                    sb.append(k).append("=").append(String.valueOf(vv)).append("\n");
                                }
                                XposedBridge.log(TAG + ": " + sb.toString());
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": SYNC_PREFS log failure -> " + t.getMessage());
                            }
                            XposedBridge.log(TAG + ": 偏好设置已同步到目标进程");
                        } else if (ACTION_QUERY_PREFS.equals(intent.getAction())) {
                            replyWithCurrentPrefs(context);
                        } else if (ACTION_ISLAND_UPDATE.equals(intent.getAction())) {
                            String courseName = safeStr(intent.getStringExtra("course_name"));
                            String startTime  = safeStr(intent.getStringExtra("start_time"));
                            String endTime    = safeStr(intent.getStringExtra("end_time"));
                            String classroom  = safeStr(intent.getStringExtra("classroom"));
                            CourseInfo info   = new CourseInfo(courseName, startTime, endTime, classroom);
                            int state         = intent.getIntExtra("state", STATE_ELAPSED);
                            String channelId  = safeStr(intent.getStringExtra("channel_id"));
                            String tag        = intent.getStringExtra("notif_tag");
                            int id            = intent.getIntExtra("notif_id", 0);
                            android.app.NotificationManager nm =
                                    context.getSystemService(android.app.NotificationManager.class);
                            // 找到当前活跃通知以复用其图标和 intent
                            Notification src = null;
                            for (StatusBarNotification sbn : nm.getActiveNotifications()) {
                                if (sbn.getId() == id) {
                                    src = sbn.getNotification();
                                    break;
                                }
                            }
                            if (src == null) {
                                XposedBridge.log(TAG + ": 闹钟回调时通知已消失，跳过 state=" + state);
                                return;
                            }
                            // 连续课程防竞争：若此通知已被新课接管，拒绝旧课陈旧的 STATE_FINISHED 广播
                            String staleOwner = mNotifCourseOwner.get(id);
                            if (staleOwner != null && !staleOwner.equals(courseName)) {
                                XposedBridge.log(TAG + ": [跳过陈旧state] state=" + state
                                        + " id=" + id + " (" + courseName + ") 已被「"
                                        + staleOwner + "」接管，忽略");
                                return;
                            }
                            SharedPreferences prefs = context
                                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                            sendIslandUpdate(info, state, context, channelId, src, nm, tag, id, prefs);
                        } else if (ACTION_TEST_NOTIFY.equals(intent.getAction())) {
                            // 由模块 APP 触发，在目标进程内构造并发送测试通知
                            String tCourseName = intent.getStringExtra("course_name");
                            String tStartTime  = intent.getStringExtra("start_time");
                            String tEndTime    = intent.getStringExtra("end_time");
                            String tClassroom  = intent.getStringExtra("classroom");
                            if (tCourseName == null || tCourseName.isEmpty()) tCourseName = "高等数学";
                            if (tStartTime  == null || tStartTime.isEmpty())  tStartTime  = "00:00";
                            if (tEndTime    == null || tEndTime.isEmpty())    tEndTime    = "00:00";
                            if (tClassroom  == null || tClassroom.isEmpty())  tClassroom  = "教科A-101";

                            // 独立测试通知渠道，不依赖 voiceassist 自带渠道（importance 不受控制）
                            final String TEST_CHANNEL_ID = "xiaoai_course_reminder_alert";
                            android.app.NotificationManager tnm =
                                    context.getSystemService(android.app.NotificationManager.class);
                            if (tnm == null) return;
                            if (tnm.getNotificationChannel(TEST_CHANNEL_ID) == null) {
                                android.app.NotificationChannel tch = new android.app.NotificationChannel(
                                        TEST_CHANNEL_ID, "课程提醒", android.app.NotificationManager.IMPORTANCE_HIGH);
                                tch.enableVibration(true);
                                tnm.createNotificationChannel(tch);
                            }

                            android.app.Notification tNotif = new android.app.Notification.Builder(context, TEST_CHANNEL_ID)
                                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                                    // 加 title/text 防止 MIUI 因无内容静默丢弃通知
                                    .setContentTitle("[" + tCourseName + "]快到了，提前准备一下吧")
                                    .setContentText(tStartTime + " - " + tEndTime + "  " + tClassroom)
                                    .build();
                            if (tNotif.extras == null) tNotif.extras = new android.os.Bundle();
                            // 保留 title/text：MIUI 在解析岛 JSON 前先校验通知内容，
                            // 删除 title/text 会导致 MIUI 将通知静默丢弃（「内容为空」）
                            tNotif.extras.putString("xiaoai.test.course_name", tCourseName);
                            tNotif.extras.putString("xiaoai.test.start_time",  tStartTime);
                            tNotif.extras.putString("xiaoai.test.end_time",    tEndTime);
                            tNotif.extras.putString("xiaoai.test.classroom",   tClassroom);

                            int tNotifId = sTestNotifId.getAndIncrement();
                            // 先取消上一条测试通知，避免堆积
                            if (sLastTestNotifId != -1) {
                                tnm.cancel(sLastTestNotifId);
                                XposedBridge.log(TAG + ": 已取消上一条测试通知 id=" + sLastTestNotifId);
                            }
                            sLastTestNotifId = tNotifId;
                            XposedBridge.log(TAG + ": 即将发出测试通知 → " + tCourseName + " @" + tStartTime);
                            CourseInfo tInfo = new CourseInfo(tCourseName, tStartTime, tEndTime, tClassroom);
                            applyIslandParams(context, tNotif, tInfo, tNotifId, null);
                            tnm.notify(tNotifId, tNotif);
                            XposedBridge.log(TAG + ": 已在目标进程发出测试通知 id=" + tNotifId);
                            // 测试通知按用户设定的时间逻辑调度静音/取消静音闹钟：
                            // 分钟数直接从 intent 读取（MainActivity 调用时已携带），不读 SP，消除跨进程缓存旧值问题
                            boolean tMuteEnabled   = intent.getBooleanExtra("mute_enabled",   sMuteEnabled);
                            boolean tUnmuteEnabled = intent.getBooleanExtra("unmute_enabled", sUnmuteEnabled);
                            boolean tDndEnabled    = intent.getBooleanExtra("dnd_enabled",    sDndEnabled);
                            boolean tUnDndEnabled  = intent.getBooleanExtra("undnd_enabled",  sUnDndEnabled);
                            if (tMuteEnabled || tUnmuteEnabled || tDndEnabled || tUnDndEnabled) {
                                long tNow       = System.currentTimeMillis();
                                // 使用 MainActivity 传来的精确毫秒时间戳，与真实调度逻辑完全一致
                                long classStartMs = intent.getLongExtra("start_ms", tNow + 60_000L);
                                long classEndMs   = intent.getLongExtra("end_ms",   tNow + 120_000L);
                                int  tAlarmId     = Math.abs(("test_" + tCourseName).hashCode());
                                if (tMuteEnabled || tUnmuteEnabled) {
                                    int  tMuteBefore    = intent.getIntExtra(KEY_MUTE_MINS_BEFORE,  DEFAULT_MUTE_MINS_BEFORE);
                                    int  tUnmuteAfter   = intent.getIntExtra(KEY_UNMUTE_MINS_AFTER, DEFAULT_UNMUTE_MINS_AFTER);
                                    long tMuteTrigger   = classStartMs - (long) tMuteBefore  * 60_000L;
                                    long tUnmuteTrigger = classEndMs   + (long) tUnmuteAfter * 60_000L;
                                    if (tMuteEnabled) {
                                        if (tMuteTrigger <= tNow) {
                                            applyMuteState(context, true, tCourseName);
                                        } else {
                                            scheduleMuteAlarm(context, tCourseName, tMuteTrigger, tAlarmId);
                                            XposedBridge.log(TAG + ": 测试 → 静音将在 " + (tMuteTrigger - tNow) / 1_000 + " 秒后触发");
                                        }
                                    }
                                    if (tUnmuteEnabled) {
                                        scheduleUnmuteAlarm(context, tCourseName, tUnmuteTrigger, tAlarmId);
                                        XposedBridge.log(TAG + ": 测试 → 取消静音将在 " + (tUnmuteTrigger - tNow) / 1_000 + " 秒后触发");
                                    }
                                }
                                if (tDndEnabled || tUnDndEnabled) {
                                    int  tDndBefore    = intent.getIntExtra(KEY_DND_MINS_BEFORE,  DEFAULT_DND_MINS_BEFORE);
                                    int  tUnDndAfter   = intent.getIntExtra(KEY_UNDND_MINS_AFTER, DEFAULT_UNDND_MINS_AFTER);
                                    long tDndTrigger   = classStartMs - (long) tDndBefore  * 60_000L;
                                    long tUnDndTrigger = classEndMs   + (long) tUnDndAfter * 60_000L;
                                    if (tDndEnabled) {
                                        if (tDndTrigger <= tNow) {
                                            applyDndState(context, true, tCourseName);
                                        } else {
                                            scheduleDndOnAlarm(context, tCourseName, tDndTrigger, tAlarmId);
                                            XposedBridge.log(TAG + ": 测试 → 勿扰将在 " + (tDndTrigger - tNow) / 1_000 + " 秒后触发");
                                        }
                                    }
                                    if (tUnDndEnabled) {
                                        scheduleDndOffAlarm(context, tCourseName, tUnDndTrigger, tAlarmId);
                                        XposedBridge.log(TAG + ": 测试 → 关闭勿扰将在 " + (tUnDndTrigger - tNow) / 1_000 + " 秒后触发");
                                    }
                                }
                            }
                        } else if (ACTION_COURSE_REMINDER.equals(intent.getAction())) {
                            // AlarmManager 触发课前提醒 → 在 voiceassist 进程构造通知
                            String crName  = safeStr(intent.getStringExtra("course_name"));
                            String crStart = safeStr(intent.getStringExtra("start_time"));
                            String crEnd   = safeStr(intent.getStringExtra("end_time"));
                            String crRoom  = safeStr(intent.getStringExtra("classroom"));
                            int    crId    = intent.getIntExtra("notif_id", 2001);
                            boolean crConsecutive = intent.getBooleanExtra("consecutive", false);

                            android.app.NotificationManager crnm =
                                    context.getSystemService(android.app.NotificationManager.class);
                            if (crnm == null) return;

                            // ── 连续课程：直接更新现有岛，避免双岛并存 ────────────────────
                            if (crConsecutive) {
                                android.service.notification.StatusBarNotification prevSbn = null;
                                for (android.service.notification.StatusBarNotification sbn
                                        : crnm.getActiveNotifications()) {
                                    android.app.Notification sn = sbn.getNotification();
                                    // 找到我们注入过岛参数或带有课程标记的活跃通知
                                    if (sn.extras != null
                                            && (sn.extras.containsKey(KEY_FOCUS_PARAM)
                                                || sn.extras.containsKey("xiaoai.test.course_name"))) {
                                        prevSbn = sbn;
                                        break;
                                    }
                                }
                                if (prevSbn != null) {
                                    // 用新课程信息直接更新现有岛（STATE_COUNTDOWN），无新通知声音
                                    CourseInfo newInfo = new CourseInfo(crName, crStart, crEnd, crRoom);
                                    SharedPreferences crPrefs = context.getSharedPreferences(
                                            PREFS_NAME, Context.MODE_PRIVATE);
                                    int    prevId  = prevSbn.getId();
                                    String prevTag = prevSbn.getTag();
                                    // ① 更新所有权：阻止旧课陈旧的 STATE_FINISHED 广播在新课更新后覆写岛
                                    mNotifCourseOwner.put(prevId, crName);
                                    // ② 主动取消旧课的 STATE_ELAPSED/FINISHED 闹钟，彻底消除竞争
                                    AlarmManager staleAm = context.getSystemService(AlarmManager.class);
                                    for (int ss = 1; ss <= 2; ss++) {
                                        PendingIntent stalePi = PendingIntent.getService(context,
                                                prevId * 10 + ss,
                                                createServiceIntent(ACTION_ISLAND_UPDATE),
                                                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
                                        if (stalePi != null) { staleAm.cancel(stalePi); stalePi.cancel(); }
                                    }
                                    // 为确保连续课程能触发岛的弹出动画，不能只用低权重的 sendIslandUpdate
                                    // 而是重建高权重的 CR_CH 通知，模拟新课提醒
                                    final String CR_CH = "xiaoai_course_reminder_alert";
                                    if (crnm.getNotificationChannel(CR_CH) == null) {
                                        android.app.NotificationChannel crch = new android.app.NotificationChannel(
                                                CR_CH, "课程提醒", android.app.NotificationManager.IMPORTANCE_HIGH);
                                        crch.enableVibration(true);
                                        crnm.createNotificationChannel(crch);
                                    }
                                    android.app.Notification jumpNotif = new android.app.Notification.Builder(context, CR_CH)
                                            .setSmallIcon(prevSbn.getNotification().getSmallIcon())
                                            .setContentTitle("[" + crName + "]快到了，提前准备一下吧")
                                            .setContentText(crStart + " - " + crEnd + "  " + crRoom)
                                            .build();
                                    if (jumpNotif.extras == null) jumpNotif.extras = new android.os.Bundle();
                                    jumpNotif.extras.putString("xiaoai.test.course_name", crName);
                                    jumpNotif.extras.putString("xiaoai.test.start_time",  crStart);
                                    jumpNotif.extras.putString("xiaoai.test.end_time",    crEnd);
                                    jumpNotif.extras.putString("xiaoai.test.classroom",   crRoom);
                                    applyIslandParams(context, jumpNotif, newInfo, prevId, prevTag);
                                    if (prevTag != null) crnm.notify(prevTag, prevId, jumpNotif);
                                    else                 crnm.notify(prevId, jumpNotif);
                                    // 为新课程调度 STATE_ELAPSED / STATE_FINISHED
                                    // reqCode 复用 prevId，FLAG_UPDATE_CURRENT 会自动覆盖旧课程的同类 alarm
                                    long crStartMs = computeClassStartMs(crStart);
                                    long crEndMs   = computeClassStartMs(crEnd);
                                    long nowCr     = System.currentTimeMillis();
                                    if (crStartMs > nowCr) {
                                        // 还没上课，调度 alarm
                                        MainHook.this.scheduleIslandAlarm(context, newInfo,
                                                STATE_ELAPSED, ISLAND_UPDATE_CHANNEL, prevTag, prevId, crStartMs);
                                    } else {
                                        // 0 间隔连续课程：trigger 触发时已到上课时间，立即刷为"上课中"
                                        XposedBridge.log(TAG + ": [连续课程] crStartMs 已过，立即刷 STATE_ELAPSED");
                                        sendIslandUpdate(newInfo, STATE_ELAPSED, context,
                                                prevSbn.getNotification().getChannelId(),
                                                prevSbn.getNotification(), crnm,
                                                prevTag, prevId, crPrefs);
                                    }
                                    if (crEndMs > nowCr) {
                                        MainHook.this.scheduleIslandAlarm(context, newInfo,
                                                STATE_FINISHED, ISLAND_UPDATE_CHANNEL, prevTag, prevId, crEndMs);
                                    } else {
                                        // 下课时间也已过（极端情况，补发 STATE_FINISHED）
                                        XposedBridge.log(TAG + ": [连续课程] crEndMs 已过，立即刷 STATE_FINISHED");
                                        sendIslandUpdate(newInfo, STATE_FINISHED, context,
                                                prevSbn.getNotification().getChannelId(),
                                                prevSbn.getNotification(), crnm,
                                                prevTag, prevId, crPrefs);
                                    }
                                    scheduleNotifCancelAlarms(context, crPrefs, prevTag, prevId,
                                            nowCr, crStartMs, crEndMs);
                                    XposedBridge.log(TAG + ": [连续课程] 岛已更新 → " + crName
                                            + " id=" + prevId);
                                    return; // 不再发新通知
                                }
                                // 未找到现有岛，降级到正常发送路径
                                XposedBridge.log(TAG + ": [连续课程] 未找到现有岛，降级为新通知");
                            }

                            // ── 正常路径：发新通知（首节课或降级）────────────────────────
                            final String CR_CH = "xiaoai_course_reminder_alert";
                            if (crnm.getNotificationChannel(CR_CH) == null) {
                                android.app.NotificationChannel crch = new android.app.NotificationChannel(
                                        CR_CH, "课程提醒", android.app.NotificationManager.IMPORTANCE_HIGH);
                                crch.enableVibration(true);
                                crnm.createNotificationChannel(crch);
                            }
                            android.app.Notification crNotif = new android.app.Notification.Builder(context, CR_CH)
                                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                                    .setContentTitle("[" + crName + "]快到了，提前准备一下吧")
                                    .setContentText(crStart + " - " + crEnd + "  " + crRoom)
                                    .build();
                            if (crNotif.extras == null) crNotif.extras = new android.os.Bundle();
                            // 保留 title/text，防止 MIUI 静默丢弃内容为空的通知
                            crNotif.extras.putString("xiaoai.test.course_name", crName);
                            crNotif.extras.putString("xiaoai.test.start_time",  crStart);
                            crNotif.extras.putString("xiaoai.test.end_time",    crEnd);
                            crNotif.extras.putString("xiaoai.test.classroom",   crRoom);
                            CourseInfo crInfo = new CourseInfo(crName, crStart, crEnd, crRoom);
                            applyIslandParams(context, crNotif, crInfo, crId, null);
                            crnm.notify(crId, crNotif);
                            XposedBridge.log(TAG + ": 课前提醒通知已发送 → " + crName + " @" + crStart);
                        } else if (ACTION_DO_MUTE.equals(intent.getAction())) {
                            applyMuteState(context, true, intent.getStringExtra("course_name"));
                        } else if (ACTION_DO_UNMUTE.equals(intent.getAction())) {
                            applyMuteState(context, false, intent.getStringExtra("course_name"));
                        } else if (ACTION_DO_DND_ON.equals(intent.getAction())) {
                            applyDndState(context, true, intent.getStringExtra("course_name"));
                        } else if (ACTION_DO_DND_OFF.equals(intent.getAction())) {
                            applyDndState(context, false, intent.getStringExtra("course_name"));
                        } else if (ACTION_MANUAL_MUTE.equals(intent.getAction())) {
                            String mn = intent.getStringExtra("course_name");
                            // 按钮模式：0=仅静音, 1=仅勿扰, 2=两者
                            if (sIslandButtonMode == 0 || sIslandButtonMode == 2) applyMuteState(context, true, mn);
                            if (sIslandButtonMode == 1 || sIslandButtonMode == 2) applyDndState(context, true, mn);
                        } else if (ACTION_MANUAL_UNMUTE.equals(intent.getAction())) {
                            String mn = intent.getStringExtra("course_name");
                            if (sIslandButtonMode == 0 || sIslandButtonMode == 2) applyMuteState(context, false, mn);
                            if (sIslandButtonMode == 1 || sIslandButtonMode == 2) applyDndState(context, false, mn);
                        } else if (ACTION_RESCHEDULE_DAILY.equals(intent.getAction())) {
                            // 每日 00:01 跨日重调：触发主动更新，重新同步开关状态，重新调度当日 alarm
                            XposedBridge.log(TAG + ": [跨日重调] 触发，同步配置并执行主动重调度");
                            SharedPreferences dp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                            sMuteEnabled   = dp.getBoolean(KEY_MUTE_ENABLED,   false);
                            sUnmuteEnabled = dp.getBoolean(KEY_UNMUTE_ENABLED, false);
                            sDndEnabled    = dp.getBoolean(KEY_DND_ENABLED,    false);
                            sUnDndEnabled  = dp.getBoolean(KEY_UNDND_ENABLED,  false);
                            sWakeupMorningEnabled   = dp.getBoolean(KEY_WAKEUP_MORNING_ENABLED,   false);
                            sWakeupAfternoonEnabled = dp.getBoolean(KEY_WAKEUP_AFTERNOON_ENABLED, false);
                            sIslandButtonMode = dp.getInt("island_button_mode", 0);
                            
                            safeReschedule(context, "island_reschedule_daily", true);
                        } else if (ACTION_NOTIF_CANCEL.equals(intent.getAction())) {
                            // AlarmClock 触发通知定时取消
                            int    cancelId  = intent.getIntExtra("notif_id", -1);
                            String cancelTag = intent.getStringExtra("notif_tag");
                            String phase     = safeStr(intent.getStringExtra("phase"));
                            if (cancelId == -1) return;
                            android.app.NotificationManager cnm =
                                    context.getSystemService(android.app.NotificationManager.class);
                            if (cancelTag != null) cnm.cancel(cancelTag, cancelId);
                            else                   cnm.cancel(cancelId);
                            mNotifCourseOwner.remove(cancelId); // 清除所有权，允许后续重建同 id 通知
                            XposedBridge.log(TAG + ": 通知定时取消 [" + phase + "] id=" + cancelId);
                        }
                    }
                };
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    appCtx.registerReceiver(receiver, filter);
                }
                XposedBridge.log(TAG + ": [Application] 广播接收器已注册");
                // 动态定位小米内部 SettingsUtil（change 方法），用于静音/勿扰模式切换，结果按版本号缓存
                MiuiSettingsInvoker.init(appCtx, appCtx.getClassLoader());
                // 从 SP 读取开关状态
                SharedPreferences initPrefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                sMuteEnabled            = initPrefs.getBoolean(KEY_MUTE_ENABLED,              false);
                sUnmuteEnabled          = initPrefs.getBoolean(KEY_UNMUTE_ENABLED,            false);
                sDndEnabled             = initPrefs.getBoolean(KEY_DND_ENABLED,               false);
                sUnDndEnabled           = initPrefs.getBoolean(KEY_UNDND_ENABLED,             false);
                sWakeupMorningEnabled   = initPrefs.getBoolean(KEY_WAKEUP_MORNING_ENABLED,    false);
                sWakeupAfternoonEnabled = initPrefs.getBoolean(KEY_WAKEUP_AFTERNOON_ENABLED,  false);
                sIslandButtonMode       = initPrefs.getInt    ("island_button_mode",           0);
                // 加载持久化的闹钟 ID
                mScheduledAlarmIds.addAll(loadScheduledIds(appCtx, "scheduled_alarm_ids"));
                mScheduledMuteIds.addAll(loadScheduledIds(appCtx, "scheduled_mute_ids"));
                scheduleTodayWakeupAlarms(appCtx);
                // 启动 CourseData 监听
                registerCourseDataListener(appCtx);
                // 启动时执行主动更新与重调度，确保进程重启后数据也是最新的
                safeReschedule(appCtx, "island_startup", true);
                // 初始化课表内容哈希，确保 FileObserver 首次触发时能正确跳过未实质变动的写入。
                try {
                    @SuppressWarnings("deprecation")
                    SharedPreferences initCp = appCtx.getSharedPreferences(
                            PREFS_COURSE_DATA, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                    String initBj = initCp.getString("weekCourseBean", null);
                    if (initBj != null && !initBj.isEmpty()) {
                        mLastCourseDataHash = stableCourseHash(initBj);
                    }
                } catch (Throwable ignored) {}
                // 跨日自动重调：每天 00:01 重新调度当日闹钟，链式保证次日不丢失
                scheduleMidnightReschedule(appCtx);
                XposedBridge.log(TAG + ": 偷好同步接收器已注册，课前提醒已开启");
            }
        });
    }

    /**
     * 创建指向 UploadStateService 的显式 Intent（供 AlarmManager 使用）。
     * PendingIntent.getService 在进程死亡后能强制拉起进程，
     * 解决 getBroadcast + 动态注册接收器在进程死后无人接收的问题。
     */
    private Intent createServiceIntent(String action) {
        Intent intent = new Intent(action);
        intent.setClassName(TARGET_PACKAGE, UPLOAD_STATE_SERVICE);
        return intent;
    }

    /** 判断给定 action 是否为闹钟触发的 action（需通过 Service 拉起进程） */
    private static boolean isAlarmAction(String action) {
        return ACTION_COURSE_REMINDER.equals(action)
                || ACTION_ISLAND_UPDATE.equals(action)
                || ACTION_DO_MUTE.equals(action)
                || ACTION_DO_UNMUTE.equals(action)
                || ACTION_DO_DND_ON.equals(action)
                || ACTION_DO_DND_OFF.equals(action)
                || ACTION_RESCHEDULE_DAILY.equals(action)
                || ACTION_NOTIF_CANCEL.equals(action);
    }

    /**
     * Hook voiceassist 的 UploadStateService.onStartCommand，拦截闹钟触发的 Intent。
     * 当进程被杀后，AlarmManager 通过 PendingIntent.getService 强制拉起进程：
     *   系统启动进程 → Application.onCreate（Xposed 注入 + 动态 BR 注册）
     *   → Service.onStartCommand（本 hook 拦截）→ 转发为包内广播 → 动态 BR 处理。
     */
    private void hookUploadStateService(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // UploadStateService 未覆写 onStartCommand，需用 getMethod 搜索继承链
            Class<?> svcClass = lpparam.classLoader.loadClass(UPLOAD_STATE_SERVICE);
            java.lang.reflect.Method onStartCmd = svcClass.getMethod(
                    "onStartCommand", Intent.class, int.class, int.class);
            XposedBridge.hookMethod(onStartCmd, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // 仅拦截 UploadStateService 实例，放行其他 Service
                    if (!UPLOAD_STATE_SERVICE.equals(param.thisObject.getClass().getName()))
                        return;
                    Intent intent = (Intent) param.args[0];
                    if (intent == null || intent.getAction() == null) return;
                    String action = intent.getAction();
                    if (!isAlarmAction(action)) return;

                    Context ctx = (Context) param.thisObject;
                    // 清除 component（Service 目标），转为包内隐式广播
                    Intent fwd = new Intent(action);
                    if (intent.getExtras() != null) fwd.putExtras(intent.getExtras());
                    fwd.setPackage(TARGET_PACKAGE);
                    ctx.sendBroadcast(fwd);
                    param.setResult(android.app.Service.START_NOT_STICKY);
                    XposedBridge.log(TAG + ": [Service→BR] 转发 " + action);
                }
            });
            XposedBridge.log(TAG + ": hookUploadStateService 已注入");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookUploadStateService 失败: " + t.getMessage());
        }
    }

    /**
     * 统一重调度逻辑：
     * 1. 触发 proactive 主动更新（可选）。
     * 2. 强制清除哈希（mLastCourseDataHash=0），确保后续 FileObserver 或延时任务必执行。
     * 3. 立即执行一次调度（即刻响应）。
     * 4. 5秒后再次执行调度（等待网络更新完成）。
     */
    private void safeReschedule(Context context, String from, boolean proactive) {
        if (proactive) {
            TimeTableHelperInvoker.init(context, context.getClassLoader());
            TimeTableHelperInvoker.triggerUpdate(context, from, false);
        }
        mLastCourseDataHash = 0;
        
        // 立即执行一次（使用现有数据，即刻反馈）
        scheduleTodayCourseReminders(context, null, false);
        scheduleTodayMuteAlarms(context, false);
        scheduleTodayWakeupAlarms(context);

        // 5秒后再次执行（等待主动更新写盘成功）
        // skipRepost=true：仅重新调度未来闹钟，跳过补发通知和即时静音/勿扰，
        // 避免与第一次执行重复导致岛重新弹出和多余状态切换。
        getRescheduleHandler().postDelayed(() -> {
            scheduleTodayCourseReminders(context, null, true);
            scheduleTodayMuteAlarms(context, true);
            scheduleTodayWakeupAlarms(context);
            // 若是跨日重调，链式调度下一个午夜
            if ("island_reschedule_daily".equals(from)) {
                scheduleMidnightReschedule(context);
            }
        }, 5000);
    }

    /**
     * 读取 voiceassist 自身的 CourseData SharedPreferences，解析今日课程，
     * 为每节课在开始前 N 分钟设置 AlarmManager 精确闹钟。
     */
    private void scheduleTodayCourseReminders(Context ctx, String cachedBeanJson) {
        scheduleTodayCourseReminders(ctx, cachedBeanJson, false);
    }

    /**
     * @param skipRepost 为 true 时跳过“补发”逻辑（进程重启后第二次延迟调度时使用），
     *                   仅重新调度未来闹钟，避免重复触发岛动画。
     */
    private void scheduleTodayCourseReminders(Context ctx, String cachedBeanJson, boolean skipRepost) {
        try {
            // ── 1. 先读数据，内容哈希去重（必须在 cancel 前），避免相同课表重复写盘触发补发 ──
            final String beanJson;
            if (cachedBeanJson != null) {
                // 来自 FileObserver 回调：直接使用已读好的 bean，不再二次读盘，
                // 且 mLastCourseDataHash 已由 callback 写入，此处不覆盖，防止双重读导致 hash 不一致。
                beanJson = cachedBeanJson;
            } else {
                // 来自强制重调路径（跨日/提醒分钟变化等）：从磁盘读取最新值并更新 hash。
                @SuppressWarnings("deprecation")
                SharedPreferences coursePrefs = ctx.getSharedPreferences(
                        PREFS_COURSE_DATA, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                String raw = coursePrefs.getString("weekCourseBean", null);
                if (raw == null || raw.isEmpty()) {
                    XposedBridge.log(TAG + ": CourseData 为空，跳过课前提醒调度");
                    return;
                }
                beanJson = raw;
                mLastCourseDataHash = stableCourseHash(beanJson);
            }
            if (beanJson.isEmpty()) {
                XposedBridge.log(TAG + ": CourseData 为空，跳过课前提醒调度");
                return;
            }

            // ── 2. 取消所有旧闹钟，且计算新课表的所有 valid ID 用于后续精确清理 ──
            // MODE_MULTI_PROCESS 保证从磁盘读取最新值；cancel 在 hash 检查之后，避免提前撤销正在运行的 alarm
            cancelAllScheduledAlarms(ctx);
            mScheduledAlarmIds.clear();
            java.util.Set<Integer> validAlarmIds = new java.util.HashSet<>();


            // ── 2b. 节假日 / 调休 检查 ─────────────────────────────────────────
            java.text.SimpleDateFormat holidayFmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            String todayDateStr = holidayFmt.format(new java.util.Date());
            // 节假日：取消旧闹钟后直接返回，不发任何提醒
            if (HolidayManager.isHoliday(ctx, todayDateStr)) {
                XposedBridge.log(TAG + ": 今日 " + todayDateStr + " 为节假日，跳过课前提醒调度");
                return;
            }
            // 调休工作日：记录替换信息，后续覆盖 todayDay / currentWeek
            HolidayManager.HolidayEntry workSwapDay = HolidayManager.getWorkSwap(ctx, todayDateStr);

            // 今天是星期几（课程数据: 1=周一, 7=周日）
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int calDay   = cal.get(java.util.Calendar.DAY_OF_WEEK);
            int todayDay = (calDay == java.util.Calendar.SUNDAY) ? 7 : (calDay - 1);

            JSONObject root    = new JSONObject(beanJson);
            JSONObject data    = root.getJSONObject("data");
            JSONObject setting = data.getJSONObject("setting");
            // 直接使用服务端 presentWeek（已处理开学延期、调课周等特殊情况）
            int currentWeek = setting.optInt("presentWeek", 0);
            // 调休工作日：将当天星期和周次替换为指定的调休酬条件
            if (workSwapDay != null && workSwapDay.followWeek > 0 && workSwapDay.followWeekday > 0) {
                XposedBridge.log(TAG + ": 今日 " + todayDateStr + " 为调休工作日，"
                        + "按\u7b2c" + workSwapDay.followWeek + "周 " + workSwapDay.followDesc() + " 调度");
                todayDay    = workSwapDay.followWeekday;
                currentWeek = workSwapDay.followWeek;
            }
            // 超出学期总周数则本学期已结束，无需调度任何课程
            int totalWeek = setting.optInt("totalWeek", 0);
            if (totalWeek > 0 && currentWeek > totalWeek) {
                XposedBridge.log(TAG + ": 当前第 " + currentWeek + " 周，已超过学期总周数 "
                        + totalWeek + "，跳过调度");
                return;
            }
            JSONArray sectionTimes = new JSONArray(setting.getString("sectionTimes"));
            JSONArray courses      = data.getJSONArray("courses");

            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            // 将学期总周数写入 voiceassist 自己的 island_custom（作为备份），并广播给模块 UI
            if (totalWeek > 0) {
                prefs.edit().putInt("course_total_week", totalWeek).apply();
                Intent twIntent = new Intent(TotalWeekReceiver.ACTION_UPDATE_TOTAL_WEEK);
                twIntent.setPackage(MODULE_PKG);
                twIntent.putExtra("course_total_week", totalWeek);
                ctx.sendBroadcast(twIntent);
            }
            int reminderMinutes     = prefs.getInt(KEY_REMINDER_MINUTES, DEFAULT_REMINDER_MINUTES);
            long nowMs              = System.currentTimeMillis();
            long reminderMs         = (long) reminderMinutes * 60_000L;

            // ── 第一遍：收集今日有效课程 [startMs, endMs, originalIndex] ──
            java.util.List<long[]> todaySlots = new java.util.ArrayList<>();
            for (int i = 0; i < courses.length(); i++) {
                JSONObject course = courses.getJSONObject(i);
                if (course.getInt("day") != todayDay) continue;

                boolean inWeek = false;
                for (String w : course.getString("weeks").split(",")) {
                    try { if (Integer.parseInt(w.trim()) == currentWeek) { inWeek = true; break; } }
                    catch (NumberFormatException ignored) {}
                }
                if (!inWeek) continue;

                String[] secs = course.getString("sections").split(",");
                if (secs.length == 0) continue;
                int firstSec = Integer.parseInt(secs[0].trim());
                int lastSec  = Integer.parseInt(secs[secs.length - 1].trim());

                String startTime = getSectionTime(sectionTimes, firstSec, true);
                String endTime   = getSectionTime(sectionTimes, lastSec,  false);
                if (startTime.isEmpty() || endTime.isEmpty()) continue;

                long startMs = computeClassStartMs(startTime);
                long endMs   = computeClassStartMs(endTime);
                if (startMs < 0 || endMs < 0) continue;

                todaySlots.add(new long[]{startMs, endMs, i});
            }
            // 按开始时间升序，确保连续课程检测的方向正确
            todaySlots.sort((a, b) -> Long.compare(a[0], b[0]));

            // ── 第二遍：逐课计算触发时间，检测连续课程 ──
            mConsecutiveAnchors.clear(); // 每次重新调度前重置锚点集合
            int scheduledCount = 0;
            int prevLoopAlarmId = -1;     // 上一课的 alarmId，用于连续检测时标记锚点
            for (int si = 0; si < todaySlots.size(); si++) {
                long[] slot   = todaySlots.get(si);
                long startMs  = slot[0];
                long endMs    = slot[1];
                int  idx      = (int) slot[2];

                JSONObject course = courses.getJSONObject(idx);
                String[] secs     = course.getString("sections").split(",");
                int firstSec      = Integer.parseInt(secs[0].trim());
                int lastSec       = Integer.parseInt(secs[secs.length - 1].trim());
                String startTime  = getSectionTime(sectionTimes, firstSec, true);
                String endTime    = getSectionTime(sectionTimes, lastSec,  false);
                String courseName = course.getString("name");
                String classroom  = course.optString("position", "");
                CourseInfo info   = new CourseInfo(courseName, startTime, endTime, classroom);
                // 将所有关键参数纳入 hash 计算，确保教室、结束时间等发生变化时，能生成新的 alarmId 
                // 从而让旧的 alarm（未包含在新 validIds 中）被精确清理，并重新排布新闹钟触发岛更新
                int alarmId       = Math.abs((courseName + startTime + endTime + classroom).hashCode());
                validAlarmIds.add(alarmId);

                // 默认触发时间：课程开始前 N 分钟
                long triggerMs    = startMs - reminderMs;
                boolean isConsecutive = false;

                // 若上一门课与本节的课间 <= 提醒分钟数，则视为连续课程
                if (si > 0) {
                    long prevEndMs = todaySlots.get(si - 1)[1];
                    long breakMs   = startMs - prevEndMs;
                    if (breakMs >= 0 && breakMs <= reminderMs) {
                        // 连续：在上节课下课时触发本节提醒
                        triggerMs     = prevEndMs;
                        isConsecutive = true;
                        // 上一课有连续后续，标记为锚点：injectIslandParams 跳过其 cancel alarm
                        if (prevLoopAlarmId != -1) mConsecutiveAnchors.add(prevLoopAlarmId);
                        XposedBridge.log(TAG + ": [连续课程] " + courseName
                                + " 课间=" + (breakMs / 60_000) + "min <= 提醒"
                                + reminderMinutes + "min，将在上节下课时触发");
                    }
                }
                prevLoopAlarmId = alarmId; // 始终更新，供下次迭代判断

                if (triggerMs <= nowMs) {
                    // 在提醒窗口内或课程进行中（含进程重启场景）→ 立即补发通知
                    if (nowMs < endMs && !skipRepost) {
                        // 检查通知栏是否已有同 ID 通知，避免重复补发导致岛重新弹出
                        android.app.NotificationManager repostNm =
                                ctx.getSystemService(android.app.NotificationManager.class);
                        boolean alreadyPosted = false;
                        if (repostNm != null) {
                            for (android.service.notification.StatusBarNotification sbn
                                    : repostNm.getActiveNotifications()) {
                                if (sbn.getId() == alarmId) { alreadyPosted = true; break; }
                            }
                        }
                        if (!alreadyPosted) {
                            sendCourseReminderNow(ctx, info, alarmId);
                            String label = (nowMs < startMs) ? "[窗口内补发]" : "[上课中补发]";
                            XposedBridge.log(TAG + ": " + label + " " + courseName + " @" + startTime);
                        } else {
                            XposedBridge.log(TAG + ": [跳过补发] 通知已存在 " + courseName + " id=" + alarmId);
                        }
                        scheduledCount++;
                    }
                    continue;
                }

                scheduleCourseReminderAlarm(ctx, info, triggerMs, alarmId, isConsecutive);
                scheduledCount++;
            }
            XposedBridge.log(TAG + ": 今日课前提醒已调度 " + scheduledCount
                    + " 条（第 " + currentWeek + " 周，提前 " + reminderMinutes + " 分钟）");

            // ── 3. 精确清理：取消那些属于我方但不在新课表 valid 集合中的活跃通知 ──
            cancelStaleNotifications(ctx, validAlarmIds);
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": scheduleTodayCourseReminders 失败 → " + e.getMessage());
        }
    }

    /**
     * 提取 weekCourseBean JSON 中真正影响调度的字段（courses / sectionTimes / presentWeek /
     * totalWeek / weekStart）并计算其 hashCode，过滤掉 updateTime、level、rtPresentWeek、
     * startSemester 等无关字段，避免误判「内容已变化」。
     */
    private int stableCourseHash(String beanJson) {
        if (beanJson == null || beanJson.isEmpty()) return 0;
        try {
            org.json.JSONObject root    = new org.json.JSONObject(beanJson);
            org.json.JSONObject data    = root.getJSONObject("data");
            org.json.JSONObject setting = data.getJSONObject("setting");
            // presentWeek 每周变化一次（周次推进），必须纳入哈希否则新周课表不重调度
            // updateTime / level / rtPresentWeek / startSemester 每次写盘都变，排除
            String stable = data.optJSONArray("courses").toString()
                    + setting.optString("sectionTimes")
                    + setting.optString("totalWeek")
                    + setting.optString("weekStart")
                    + setting.optInt("presentWeek", 0);
            return stable.hashCode();
        } catch (Throwable e) {
            return beanJson.hashCode();
        }
    }

    /** 从 sectionTimes JSON 数组中查找某节课的开始或结束时间（HH:mm）。 */
    private String getSectionTime(JSONArray sectionTimes, int sectionIndex, boolean isStart) {
        try {
            for (int i = 0; i < sectionTimes.length(); i++) {
                JSONObject st = sectionTimes.getJSONObject(i);
                if (st.getInt("i") == sectionIndex) {
                    return isStart ? st.getString("s") : st.getString("e");
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * 为单节课程注册一个 AlarmManager 精确唤醒闹钟（在 voiceassist 进程内）。
     * @param isConsecutive 是否为连续课程（课间 < 提醒分钟数，触发时间为上节下课时刻）
     */
    private void scheduleCourseReminderAlarm(Context ctx, CourseInfo info,
                                              long triggerMs, int alarmId,
                                              boolean isConsecutive) {
        try {
            Intent intent = createServiceIntent(ACTION_COURSE_REMINDER);
            intent.putExtra("course_name",  info.courseName);
            intent.putExtra("start_time",   info.startTime);
            intent.putExtra("end_time",     info.endTime);
            intent.putExtra("classroom",    info.classroom);
            intent.putExtra("notif_id",     alarmId);
            intent.putExtra("consecutive",  isConsecutive);
            PendingIntent pi = PendingIntent.getService(ctx, alarmId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent showPi = PendingIntent.getBroadcast(ctx, alarmId | 0x50000000,
                    new Intent(ACTION_COURSE_REMINDER).setPackage(TARGET_PACKAGE),
                    PendingIntent.FLAG_IMMUTABLE);
            ctx.getSystemService(AlarmManager.class)
               .setAlarmClock(new AlarmManager.AlarmClockInfo(triggerMs, showPi), pi);
            synchronized (mScheduledAlarmIds) {
                mScheduledAlarmIds.add(alarmId);
                saveScheduledIds(ctx, "scheduled_alarm_ids", mScheduledAlarmIds);
            }
            long minsLeft = (triggerMs - System.currentTimeMillis()) / 60_000;
            XposedBridge.log(TAG + ": 闹钟已设(AlarmClock) " + info.courseName + " @" + info.startTime
                    + (isConsecutive ? "（连续课程，上节下课触发）" : "")
                    + " 约 " + minsLeft + " 分钟后触发");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": scheduleCourseReminderAlarm 失败 → " + e.getMessage());
        }
    }

    /**
     * 取消 mScheduledAlarmIds 中所有已调度的课前提醒 AlarmManager 闹钟。
     * 不影响静音闹钟（静音功能独立于自定义提醒开关）。
     */
    private void cancelAllScheduledAlarms(Context ctx) {
        java.util.Set<Integer> idsToCancel = loadScheduledIds(ctx, "scheduled_alarm_ids");
        if (idsToCancel.isEmpty()) return;
        try {
            AlarmManager am = ctx.getSystemService(AlarmManager.class);
            for (int id : idsToCancel) {
                Intent dummy = createServiceIntent(ACTION_COURSE_REMINDER);
                PendingIntent pi = PendingIntent.getService(ctx, id, dummy,
                        PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            }
            XposedBridge.log(TAG + ": 已取消 " + idsToCancel.size() + " 个课前提醒闹钟");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": cancelAllScheduledAlarms 失败 → " + e.getMessage());
        }
        synchronized (mScheduledAlarmIds) {
            mScheduledAlarmIds.clear();
            saveScheduledIds(ctx, "scheduled_alarm_ids", mScheduledAlarmIds);
        }
    }

    /**
     * 精确清理：遍历通知栏，如果发现某通知是我方发出的，但 ID 不在有效集合内，则取消它。
     * 存在于有效集合内的通知不主动取消，由后续 notify() 平滑更新，避免闪烁。
     */
    private void cancelStaleNotifications(Context ctx, java.util.Set<Integer> validIds) {
        try {
            android.app.NotificationManager nm = ctx.getSystemService(android.app.NotificationManager.class);
            if (nm == null) return;
            int count = 0;
            for (android.service.notification.StatusBarNotification sbn : nm.getActiveNotifications()) {
                android.app.Notification n = sbn.getNotification();
                String ch = safeStr(n.getChannelId());
                boolean isOurs = (n.extras != null && n.extras.containsKey(KEY_FOCUS_PARAM))
                        || (n.extras != null && n.extras.containsKey("xiaoai.test.course_name"))
                        || "xiaoai_course_reminder_alert".equals(ch)
                        || ISLAND_UPDATE_CHANNEL.equals(ch)
                        || ch.contains("COURSE_SCHEDULER_REMINDER");
                        
                if (isOurs) {
                    int id = sbn.getId();
                    if (!validIds.contains(id)) {
                        nm.cancel(sbn.getTag(), id);
                        mNotifCourseOwner.remove(id);
                        count++;
                    }
                }
            }
            if (count > 0) XposedBridge.log(TAG + ": 已精确清理 " + count + " 个旧课表残留通知");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cancelStaleNotifications 失败 -> " + t.getMessage());
        }
    }

    /** 取消所有静音 / 取消静音闹钟。 */
    private void cancelAllMuteAlarms(Context ctx) {
        java.util.Set<Integer> idsToCancel = loadScheduledIds(ctx, "scheduled_mute_ids");
        if (idsToCancel.isEmpty()) return;
        try {
            AlarmManager am = ctx.getSystemService(AlarmManager.class);
            for (int id : idsToCancel) {
                for (String action : new String[]{ACTION_DO_MUTE, ACTION_DO_UNMUTE, ACTION_DO_DND_ON, ACTION_DO_DND_OFF}) {
                    int aid = id & 0x00FFFFFF;
                    int reqCode;
                    if      (action.equals(ACTION_DO_MUTE))    reqCode = aid | 0x01000000;
                    else if (action.equals(ACTION_DO_UNMUTE))  reqCode = aid | 0x02000000;
                    else if (action.equals(ACTION_DO_DND_ON))  reqCode = aid | 0x03000000;
                    else                                        reqCode = aid | 0x04000000;
                    Intent dummy = createServiceIntent(action);
                    PendingIntent pi = PendingIntent.getService(ctx, reqCode, dummy,
                            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
                }
            }
            XposedBridge.log(TAG + ": 已取消 " + idsToCancel.size() + " 个静音闹钟");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": cancelAllMuteAlarms 失败 → " + e.getMessage());
        }
        synchronized (mScheduledMuteIds) {
            mScheduledMuteIds.clear();
            saveScheduledIds(ctx, "scheduled_mute_ids", mScheduledMuteIds);
        }
    }

    /** 设置上课静音闹钟，发给 voiceassist 自身（系统应用，不受 MIUI 电池优化限制）。
     * reqCode = (alarmId & 0x00FFFFFF) | 0x01000000，与课前提醒不重叠。 */
    private void scheduleMuteAlarm(Context ctx, String courseName, long triggerMs, int alarmId) {
        try {
            int reqCode = (alarmId & 0x00FFFFFF) | 0x01000000;
            Intent intent = createServiceIntent(ACTION_DO_MUTE);
            intent.putExtra("course_name", courseName);
            PendingIntent pi = PendingIntent.getService(ctx, reqCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            // 用一个不起眼的 show-intent：点击状态栏闹钟图标时什么都不做
            PendingIntent showPi = PendingIntent.getBroadcast(ctx, reqCode | 0x40000000,
                    new Intent(ACTION_DO_MUTE).setPackage(TARGET_PACKAGE),
                    PendingIntent.FLAG_IMMUTABLE);
            AlarmManager.AlarmClockInfo clockInfo =
                    new AlarmManager.AlarmClockInfo(triggerMs, showPi);
            ctx.getSystemService(AlarmManager.class).setAlarmClock(clockInfo, pi);
            synchronized (mScheduledMuteIds) {
                mScheduledMuteIds.add(alarmId);
                saveScheduledIds(ctx, "scheduled_mute_ids", mScheduledMuteIds);
            }
            long secsLeft = (triggerMs - System.currentTimeMillis()) / 1_000;
            XposedBridge.log(TAG + ": 静音闹钟已设(AlarmClock) " + courseName + " 约 " + secsLeft + " 秒后触发");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": scheduleMuteAlarm 失败 → " + e.getMessage());
        }
    }

    /**
     * 设置次日 00:01 的跨日重调闹钟。
     * 链式调用：每次触发后在 ACTION_RESCHEDULE_DAILY handler 内再次调用本方法，
     * 确保功能永久生效，无需重启 voiceassist。
     * reqCode 固定为 0x99000001，不与课程/静音 alarm 冲突。
     */
    private void scheduleMidnightReschedule(Context ctx) {
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 1);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            long triggerMs = cal.getTimeInMillis();
            Intent intent = createServiceIntent(ACTION_RESCHEDULE_DAILY);
            PendingIntent pi = PendingIntent.getService(ctx, 0x99000001, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent showPi = PendingIntent.getBroadcast(ctx, 0x99000002,
                    new Intent(ACTION_RESCHEDULE_DAILY).setPackage(TARGET_PACKAGE),
                    PendingIntent.FLAG_IMMUTABLE);
            ctx.getSystemService(AlarmManager.class)
               .setAlarmClock(new AlarmManager.AlarmClockInfo(triggerMs, showPi), pi);
            String fmt = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(new java.util.Date(triggerMs));
            XposedBridge.log(TAG + ": 跨日重调闹钟已设(AlarmClock) → " + fmt);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": scheduleMidnightReschedule 失败 → " + e.getMessage());
        }
    }

    /** 设置下课取消静音闹钟。reqCode = (alarmId & 0x00FFFFFF) | 0x02000000。 */
    private void scheduleUnmuteAlarm(Context ctx, String courseName, long triggerMs, int alarmId) {
        try {
            int reqCode = (alarmId & 0x00FFFFFF) | 0x02000000;
            Intent intent = createServiceIntent(ACTION_DO_UNMUTE);
            intent.putExtra("course_name", courseName);
            PendingIntent pi = PendingIntent.getService(ctx, reqCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent showPi = PendingIntent.getBroadcast(ctx, reqCode | 0x40000000,
                    new Intent(ACTION_DO_UNMUTE).setPackage(TARGET_PACKAGE),
                    PendingIntent.FLAG_IMMUTABLE);
            AlarmManager.AlarmClockInfo clockInfo =
                    new AlarmManager.AlarmClockInfo(triggerMs, showPi);
            ctx.getSystemService(AlarmManager.class).setAlarmClock(clockInfo, pi);
            synchronized (mScheduledMuteIds) {
                mScheduledMuteIds.add(alarmId);
                saveScheduledIds(ctx, "scheduled_mute_ids", mScheduledMuteIds);
            }
            long secsLeft = (triggerMs - System.currentTimeMillis()) / 1_000;
            XposedBridge.log(TAG + ": 取消静音闹钟已设(AlarmClock) " + courseName + " 约 " + secsLeft + " 秒后触发");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": scheduleUnmuteAlarm 失败 → " + e.getMessage());
        }
    }

    /** 设置上课开启勿扰闹钟。reqCode = (alarmId & 0x00FFFFFF) | 0x03000000。 */
    private void scheduleDndOnAlarm(Context ctx, String courseName, long triggerMs, int alarmId) {
        try {
            int reqCode = (alarmId & 0x00FFFFFF) | 0x03000000;
            Intent intent = createServiceIntent(ACTION_DO_DND_ON);
            intent.putExtra("course_name", courseName);
            PendingIntent pi = PendingIntent.getService(ctx, reqCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent showPi = PendingIntent.getBroadcast(ctx, reqCode | 0x40000000,
                    new Intent(ACTION_DO_DND_ON).setPackage(TARGET_PACKAGE),
                    PendingIntent.FLAG_IMMUTABLE);
            ctx.getSystemService(AlarmManager.class)
               .setAlarmClock(new AlarmManager.AlarmClockInfo(triggerMs, showPi), pi);
            synchronized (mScheduledMuteIds) {
                mScheduledMuteIds.add(alarmId);
                saveScheduledIds(ctx, "scheduled_mute_ids", mScheduledMuteIds);
            }
            long secsLeft = (triggerMs - System.currentTimeMillis()) / 1_000;
            XposedBridge.log(TAG + ": 开启勿扰闹钟已设(AlarmClock) " + courseName + " 约 " + secsLeft + " 秒后触发");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": scheduleDndOnAlarm 失败 → " + e.getMessage());
        }
    }

    /** 设置下课关闭勿扰闹钟。reqCode = (alarmId & 0x00FFFFFF) | 0x04000000。 */
    private void scheduleDndOffAlarm(Context ctx, String courseName, long triggerMs, int alarmId) {
        try {
            int reqCode = (alarmId & 0x00FFFFFF) | 0x04000000;
            Intent intent = createServiceIntent(ACTION_DO_DND_OFF);
            intent.putExtra("course_name", courseName);
            PendingIntent pi = PendingIntent.getService(ctx, reqCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent showPi = PendingIntent.getBroadcast(ctx, reqCode | 0x40000000,
                    new Intent(ACTION_DO_DND_OFF).setPackage(TARGET_PACKAGE),
                    PendingIntent.FLAG_IMMUTABLE);
            ctx.getSystemService(AlarmManager.class)
               .setAlarmClock(new AlarmManager.AlarmClockInfo(triggerMs, showPi), pi);
            synchronized (mScheduledMuteIds) {
                mScheduledMuteIds.add(alarmId);
                saveScheduledIds(ctx, "scheduled_mute_ids", mScheduledMuteIds);
            }
            long secsLeft = (triggerMs - System.currentTimeMillis()) / 1_000;
            XposedBridge.log(TAG + ": 关闭勿扰闹钟已设(AlarmClock) " + courseName + " 约 " + secsLeft + " 秒后触发");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": scheduleDndOffAlarm 失败 → " + e.getMessage());
        }
    }

    /** 在 voiceassist 进程内执行静音/恢复铃声。 */
    private void applyMuteState(Context ctx, boolean mute, String courseName) {
        String modeTip = mute ? "静音" : "恢复铃声";
        boolean ok = MiuiSettingsInvoker.applyMute(ctx, mute);
        XposedBridge.log(TAG + ": [" + modeTip + "] MiuiSettingsInvoker " + (ok ? "成功" : "失败 ← " + courseName));
    }

    /** 在 voiceassist 进程内开启/关闭勿扰（DND）模式。 */
    private void applyDndState(Context ctx, boolean enable, String courseName) {
        String modeTip = enable ? "开启勿扰" : "关闭勿扰";
        boolean ok = MiuiSettingsInvoker.applyDnd(ctx, enable);
        XposedBridge.log(TAG + ": [" + modeTip + "] MiuiSettingsInvoker " + (ok ? "成功" : "失败 ← " + courseName));
    }

    /** 将宿主进程当前的偏好设置全量回传给模块 App，解决重装后 UI 不一致问题。 */
    private void replyWithCurrentPrefs(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Intent reply = new Intent(ACTION_REPLY_PREFS);
            reply.setPackage(MODULE_PKG);
            java.util.Map<String, ?> all = sp.getAll();
            if (all != null) {
                for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                    Object v = entry.getValue();
                    if (v instanceof String)  reply.putExtra(entry.getKey(), (String)  v);
                    else if (v instanceof Integer) reply.putExtra(entry.getKey(), (Integer) v);
                    else if (v instanceof Boolean) reply.putExtra(entry.getKey(), (Boolean) v);
                    else if (v instanceof Long)    reply.putExtra(entry.getKey(), (Long)    v);
                    else if (v instanceof Float)   reply.putExtra(entry.getKey(), (Float)   v);
                }
            }
            // 补充同步假期数据 (去年、今年、未来两年)
            int currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            SharedPreferences hsp = ctx.getSharedPreferences(HolidayManager.PREFS_HOLIDAY, Context.MODE_PRIVATE);
            for (int yr = currentYear - 1; yr <= currentYear + 2; yr++) {
                String val = hsp.getString("list_" + yr, null);
                if (val != null) {
                    reply.putExtra(HolidayManager.EXTRA_LIST_PREFIX + yr, val);
                }
            }
            ctx.sendBroadcast(reply);
            XposedBridge.log(TAG + ": 已回传全量偏好设置到模块 App");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": replyWithCurrentPrefs 失败 -> " + t.getMessage());
        }
    }

    /**
     * 独立读取 CourseData 并调度今日静音/取消静音闹钟。
     * 完全独立于自定义提醒开关，两者可单独启用。
     */
    private void scheduleTodayMuteAlarms(Context ctx) {
        scheduleTodayMuteAlarms(ctx, false);
    }

    /**
     * @param skipImmediate 为 true 时跳过“课中立即静音/勿扰”逻辑，
     *                      仅重新调度未来闹钟。
     */
    private void scheduleTodayMuteAlarms(Context ctx, boolean skipImmediate) {
        cancelAllMuteAlarms(ctx);           // 先无条件取消旧闹钟，防止关闭静音后残留
        if (!sMuteEnabled && !sUnmuteEnabled && !sDndEnabled && !sUnDndEnabled) return;
        try {
            // ── 节假日 / 调休检查 ──
            java.text.SimpleDateFormat dateFmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            String todayDateStr = dateFmt.format(new java.util.Date());
            if (HolidayManager.isHoliday(ctx, todayDateStr)) {
                XposedBridge.log(TAG + ": 静音/勿扰：今日 " + todayDateStr + " 为节假日，立即还原并跳过调度");
                if (sMuteEnabled || sUnmuteEnabled) applyMuteState(ctx, false, "节假日");
                if (sDndEnabled  || sUnDndEnabled)  applyDndState(ctx, false, "节假日");
                return;
            }
            HolidayManager.HolidayEntry workSwap = HolidayManager.getWorkSwap(ctx, todayDateStr);

            @SuppressWarnings("deprecation")
            SharedPreferences coursePrefs = ctx.getSharedPreferences(
                    PREFS_COURSE_DATA, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
            String beanJson = coursePrefs.getString("weekCourseBean", null);
            if (beanJson == null || beanJson.isEmpty()) return;

            java.util.Calendar cal = java.util.Calendar.getInstance();
            int calDay      = cal.get(java.util.Calendar.DAY_OF_WEEK);
            int calTodayDay = (calDay == java.util.Calendar.SUNDAY) ? 7 : (calDay - 1);
            int todayDay    = (workSwap != null && workSwap.followWeekday > 0) ? workSwap.followWeekday : calTodayDay;

            JSONObject root    = new JSONObject(beanJson);
            JSONObject data    = root.getJSONObject("data");
            JSONObject setting = data.getJSONObject("setting");
            // 直接使用服务端 presentWeek（已处理开学延期、调课周等特殊情况）
            int baseWeek = setting.optInt("presentWeek", 0);
            final int currentWeek = (workSwap != null && workSwap.followWeek > 0) ? workSwap.followWeek : baseWeek;
            JSONArray sectionTimes = new JSONArray(setting.getString("sectionTimes"));
            JSONArray courses      = data.getJSONArray("courses");

            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            int muteMinsBefore  = prefs.getInt(KEY_MUTE_MINS_BEFORE,  DEFAULT_MUTE_MINS_BEFORE);
            int unmuteMinsAfter = prefs.getInt(KEY_UNMUTE_MINS_AFTER, DEFAULT_UNMUTE_MINS_AFTER);
            int dndMinsBefore   = prefs.getInt(KEY_DND_MINS_BEFORE,   DEFAULT_DND_MINS_BEFORE);
            int unDndMinsAfter  = prefs.getInt(KEY_UNDND_MINS_AFTER,  DEFAULT_UNDND_MINS_AFTER);
            long nowMs = System.currentTimeMillis();
            int count  = 0;

            // ── 预扫描：检测当前是否有任何课程正处于静音/勿扰窗口内 ──
            // 若有任何课程正在课中，则不应因已结束的课程而取消静音/勿扰
            boolean anyActiveMute = false;   // 是否有课程正处于静音窗口（muteTrigger <= now < endMs）
            boolean anyActiveDnd  = false;   // 是否有课程正处于勿扰窗口（dndTrigger  <= now < endMs）
            for (int p = 0; p < courses.length(); p++) {
                JSONObject pc = courses.getJSONObject(p);
                if (pc.getInt("day") != todayDay) continue;
                boolean pInWeek = false;
                for (String w : pc.getString("weeks").split(",")) {
                    try { if (Integer.parseInt(w.trim()) == currentWeek) { pInWeek = true; break; } }
                    catch (NumberFormatException ignored) {}
                }
                if (!pInWeek) continue;
                String[] pSecs = pc.getString("sections").split(",");
                if (pSecs.length == 0) continue;
                String pStart = getSectionTime(sectionTimes, Integer.parseInt(pSecs[0].trim()), true);
                String pEnd   = getSectionTime(sectionTimes, Integer.parseInt(pSecs[pSecs.length-1].trim()), false);
                if (pStart.isEmpty() || pEnd.isEmpty()) continue;
                long pStartMs = computeClassStartMs(pStart);
                long pEndMs   = computeClassStartMs(pEnd);
                if (pStartMs < 0 || pEndMs < 0) continue;
                if (sMuteEnabled && !anyActiveMute) {
                    long mt = pStartMs - (long) muteMinsBefore * 60_000L;
                    if (mt <= nowMs && nowMs < pEndMs) anyActiveMute = true;
                }
                if (sDndEnabled && !anyActiveDnd) {
                    long dt = pStartMs - (long) dndMinsBefore * 60_000L;
                    if (dt <= nowMs && nowMs < pEndMs) anyActiveDnd = true;
                }
                if (anyActiveMute && anyActiveDnd) break; // 已确认，提前退出
            }

            for (int i = 0; i < courses.length(); i++) {
                JSONObject course = courses.getJSONObject(i);
                if (course.getInt("day") != todayDay) continue;
                boolean inWeek = false;
                for (String w : course.getString("weeks").split(",")) {
                    try { if (Integer.parseInt(w.trim()) == currentWeek) { inWeek = true; break; } }
                    catch (NumberFormatException ignored) {}
                }
                if (!inWeek) continue;
                String[] secs = course.getString("sections").split(",");
                if (secs.length == 0) continue;
                String startTime = getSectionTime(sectionTimes, Integer.parseInt(secs[0].trim()), true);
                String endTime   = getSectionTime(sectionTimes, Integer.parseInt(secs[secs.length-1].trim()), false);
                if (startTime.isEmpty() || endTime.isEmpty()) continue;
                long startMs = computeClassStartMs(startTime);
                long endMs   = computeClassStartMs(endTime);
                if (startMs < 0 || endMs < 0) continue;
                String courseName = course.getString("name");
                int alarmId = Math.abs((courseName + startTime).hashCode());

                if (sMuteEnabled) {
                    long muteTriggerMs = startMs - (long) muteMinsBefore * 60_000L;
                    if (muteTriggerMs <= nowMs && nowMs < endMs && !skipImmediate) {
                        // 已在课中且静音时刻已过 → 仅在未静音时立即静音
                        AudioManager audioMgr = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                        if (audioMgr != null && audioMgr.getRingerMode() != AudioManager.RINGER_MODE_SILENT) {
                            applyMuteState(ctx, true, courseName);
                        } else {
                            XposedBridge.log(TAG + ": [跳过静音] 已处于静音状态 " + courseName);
                        }
                    } else if (muteTriggerMs > nowMs) {
                        scheduleMuteAlarm(ctx, courseName, muteTriggerMs, alarmId);
                        count++;
                    }
                }
                if (sUnmuteEnabled) {
                    long unmuteTriggerMs = endMs + (long) unmuteMinsAfter * 60_000L;
                    if (unmuteTriggerMs > nowMs) {
                        scheduleUnmuteAlarm(ctx, courseName, unmuteTriggerMs, alarmId);
                        count++;
                    } else if (nowMs >= endMs && !skipImmediate && !anyActiveMute) {
                        // 下课时间已过且解除静音时间也已过（如进程重启场景）→ 立即恢复铃声
                        // 但若有其他课程正在课中需要静音，则跳过
                        applyMuteState(ctx, false, courseName);
                    }
                }
                if (sDndEnabled) {
                    long dndTriggerMs = startMs - (long) dndMinsBefore * 60_000L;
                    if (dndTriggerMs <= nowMs && nowMs < endMs && !skipImmediate) {
                        // 已在课中 → 仅在勿扰未开启时才设置
                        android.app.NotificationManager dndNm =
                                ctx.getSystemService(android.app.NotificationManager.class);
                        if (dndNm != null && dndNm.getCurrentInterruptionFilter()
                                != android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                            applyDndState(ctx, true, courseName);
                        } else {
                            XposedBridge.log(TAG + ": [跳过勿扰] 已处于勿扰状态 " + courseName);
                        }
                    } else if (dndTriggerMs > nowMs) {
                        scheduleDndOnAlarm(ctx, courseName, dndTriggerMs, alarmId);
                        count++;
                    }
                }
                if (sUnDndEnabled) {
                    long unDndTriggerMs = endMs + (long) unDndMinsAfter * 60_000L;
                    if (unDndTriggerMs > nowMs) {
                        scheduleDndOffAlarm(ctx, courseName, unDndTriggerMs, alarmId);
                        count++;
                    } else if (nowMs >= endMs && !skipImmediate && !anyActiveDnd) {
                        // 下课时间已过且关闭勿扰时间也已过（如进程重启场景）→ 立即关闭勿扰
                        // 但若有其他课程正在课中需要勿扰，则跳过
                        applyDndState(ctx, false, courseName);
                    }
                }
            }
            XposedBridge.log(TAG + ": 静音/勿扰闹钟已调度 " + count + " 个");
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": scheduleTodayMuteAlarms 失败 → " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 自动叫醒：向 deskclock 进程发送广播，携带今日首节课时间
    // ═══════════════════════════════════════════════════════════════

    /**
     * 根据今日课表计算上午/下午首节课时间，广播给 DeskClockHook。
     * <ul>
     *   <li>若上午/下午叫醒均关闭，则发 clear_only 清除旧闹钟。</li>
     *   <li>目标时间已过则忽略（不设）。</li>
     *   <li>每次调用均先清除之前创建的闹钟再重建（在 DeskClockHook 侧）。</li>
     * </ul>
     */
    /**
     * 将今日课程原始数据 + 用户叫醒配置打包发给 deskclock 进程。
     * 所有课程解析、判断是否有课、决定时间的逻辑全部由 DeskClockHook 在 deskclock 进程内完成。
     */
    private void scheduleTodayWakeupAlarms(Context ctx) {
        if (!sWakeupMorningEnabled && !sWakeupAfternoonEnabled) {
            sendClearClockAlarms(ctx);
            return;
        }
        try {
            // ── 节假日 / 调休检查（与课前提醒逻辑一致）──
            java.text.SimpleDateFormat dateFmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            String todayDateStr = dateFmt.format(new java.util.Date());
            if (HolidayManager.isHoliday(ctx, todayDateStr)) {
                XposedBridge.log(TAG + ": 叫醒：今日 " + todayDateStr + " 为节假日，清除叫醒闹钟");
                sendClearClockAlarms(ctx);
                return;
            }
            HolidayManager.HolidayEntry workSwap = HolidayManager.getWorkSwap(ctx, todayDateStr);

            @SuppressWarnings("deprecation")
            SharedPreferences coursePrefs = ctx.getSharedPreferences(
                    PREFS_COURSE_DATA, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
            String beanJson = coursePrefs.getString("weekCourseBean", null);
            if (beanJson == null || beanJson.isEmpty()) {
                sendClearClockAlarms(ctx);
                return;
            }

            // ── 解决进程未启动问题 ──
            // 1. Tickle：通过查询 ContentProvider 迫使 deskclock 进程启动
            tickleDeskClock(ctx);

            // 2. 延时 1s 发送：确保 deskclock 进程完成初始化并进入 Looper 循环
            getRescheduleHandler().postDelayed(() -> {
                try {
                    SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    Intent schedIntent = new Intent(ACTION_SCHEDULE_CLOCK_ALARMS);
                    schedIntent.setPackage(DESKCLOCK_PKG);
                    // 关键标志位：允许触发已停止的应用，且提高接收优先级
                    schedIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | 0x10000000); // 0x10000000 = FLAG_RECEIVER_FOREGROUND
                    
                    // 原始课程数据（deskclock 侧自行解析）
                    schedIntent.putExtra("bean_json", beanJson);
                    // 调休工作日：传入覆盖的 todayDay / currentWeek，供 DeskClockHook 使用
                    if (workSwap != null && workSwap.followWeek > 0 && workSwap.followWeekday > 0) {
                        schedIntent.putExtra("today_day_override",    workSwap.followWeekday);
                        schedIntent.putExtra("current_week_override", workSwap.followWeek);
                    }
                    // 上午配置
                    if (sWakeupMorningEnabled) {
                        schedIntent.putExtra("morning_enabled",      true);
                        schedIntent.putExtra("morning_last_sec",     prefs.getInt(KEY_WAKEUP_MORNING_LAST_SEC, DEFAULT_WAKEUP_MORNING_LAST_SEC));
                        schedIntent.putExtra("morning_rules_json",   prefs.getString(KEY_WAKEUP_MORNING_RULES_JSON, DEFAULT_WAKEUP_MORNING_RULES_JSON));
                    }
                    // 下午配置
                    if (sWakeupAfternoonEnabled) {
                        schedIntent.putExtra("afternoon_enabled",    true);
                        schedIntent.putExtra("afternoon_first_sec",  prefs.getInt(KEY_WAKEUP_AFTERNOON_FIRST_SEC, DEFAULT_WAKEUP_AFTERNOON_FIRST_SEC));
                        schedIntent.putExtra("afternoon_rules_json", prefs.getString(KEY_WAKEUP_AFTERNOON_RULES_JSON, DEFAULT_WAKEUP_AFTERNOON_RULES_JSON));
                    }
                    ctx.sendBroadcast(schedIntent);
                    XposedBridge.log(TAG + ": 叫醒配置已转发给 deskclock (已延迟 1s)");
                } catch (Throwable e) {
                    XposedBridge.log(TAG + ": postDelayed scheduleWakeup 失败 → " + e.getMessage());
                }
            }, 1000);

        } catch (Throwable e) {
            XposedBridge.log(TAG + ": scheduleTodayWakeupAlarms 失败 → " + e.getMessage());
        }
    }

    /** 通过查询 ContentProvider 迫使 deskclock 进程拉起 */
    private void tickleDeskClock(Context ctx) {
        try {
            // 注意：URI 必须与 DeskClockHook 中的 getAlarmContentUri 匹配
            Uri uri = Uri.parse("content://com.android.deskclock/alarm");
            android.database.Cursor c = ctx.getContentResolver().query(uri, 
                    new String[]{"_id"}, null, null, "_id LIMIT 1");
            if (c != null) {
                c.close();
                XposedBridge.log(TAG + ": [Tickle] DeskClock 进程已试探触发");
            }
        } catch (Throwable e) {
            // 即使失败（如无权限）也可能是系统限制，通常足以拉起进程
            XposedBridge.log(TAG + ": [Tickle] DeskClock 试探失败（正常现象）→ " + e.getMessage());
        }
    }

    /** 向 deskclock 进程发广播，仅清除之前创建的叫醒闹钟 */
    private void sendClearClockAlarms(Context ctx) {
        tickleDeskClock(ctx);
        getRescheduleHandler().postDelayed(() -> {
            try {
                Intent i = new Intent(ACTION_SCHEDULE_CLOCK_ALARMS);
                i.setPackage(DESKCLOCK_PKG);
                i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | 0x10000000);
                i.putExtra("clear_only", true);
                ctx.sendBroadcast(i);
            } catch (Throwable ignored) {}
        }, 500); // 清理逻辑延迟稍短即可
    }

    /**
     * 同时扫描并 cancel 小爱自身已发出的同频道通知（非我方注入的），
     * 防止通知栏出现旧旧的重复条目。
     */
    private void sendCourseReminderNow(Context ctx, CourseInfo info, int notifId) {
        try {
            // 使用独立渠道（不依赖 voiceassist 已创建的 COURSE_SCHEDULER_REMINDER_sound，
            // 因为该渠道由 voiceassist 自身首次创建，importance 不受我们控制）
            final String CR_CH = "xiaoai_course_reminder_alert";
            android.app.NotificationManager nm =
                    ctx.getSystemService(android.app.NotificationManager.class);
            if (nm == null) return;
            // 取消小爱自己已发出的旧提醒通知（所有我方注入前的原生通知）
            for (android.service.notification.StatusBarNotification sbn : nm.getActiveNotifications()) {
                android.app.Notification n = sbn.getNotification();
                String ch = safeStr(n.getChannelId());
                if ((ch.equals("COURSE_SCHEDULER_REMINDER_sound") || ch.equals(CR_CH))
                        && (n.extras == null || !n.extras.containsKey("xiaoai.test.course_name"))) {
                    nm.cancel(sbn.getId());
                    XposedBridge.log(TAG + ": 已 cancel 小爱旧提醒通知 id=" + sbn.getId());
                }
            }
            if (nm.getNotificationChannel(CR_CH) == null) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        CR_CH, "课程提醒", android.app.NotificationManager.IMPORTANCE_HIGH);
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }
            android.app.Notification notif = new android.app.Notification.Builder(ctx, CR_CH)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("[" + info.courseName + "]快到了，提前准备一下吧")
                    .setContentText(info.startTime + " - " + info.endTime + "  " + info.classroom)
                    .build();
            if (notif.extras == null) notif.extras = new android.os.Bundle();
            // 保留 title/text，防止 MIUI 静默丢弃内容为空的通知
            notif.extras.putString("xiaoai.test.course_name", info.courseName);
            notif.extras.putString("xiaoai.test.start_time",  info.startTime);
            notif.extras.putString("xiaoai.test.end_time",    info.endTime);
            notif.extras.putString("xiaoai.test.classroom",   info.classroom);
            applyIslandParams(ctx, notif, info, notifId, null);
            nm.notify(notifId, notif);
            XposedBridge.log(TAG + ": [立即] 课前提醒通知已发送 " + info.courseName
                    + " @" + info.startTime + " id=" + notifId);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": sendCourseReminderNow 失败 → " + e.getMessage());
        }
    }

    /**
     * 用 FileObserver 监听 CourseData.xml 的写入（包括跨进程写入）。
     * Android SP 采用原子 rename（.bak → 目标文件），故监听 shared_prefs/ 目录的
     * MOVED_TO 事件（同时保留 CLOSE_WRITE 兜底），过滤文件名 CourseData.xml。
     */
    private void registerCourseDataListener(Context ctx) {
        if (mCourseDataObserver != null) return; // 已注册，防重复

        // voiceassist 自有数据目录下的 shared_prefs/
        String dirPath = ctx.getFilesDir().getParent() + "/shared_prefs";

        mCourseDataObserver = new android.os.FileObserver(
                dirPath,
                android.os.FileObserver.MOVED_TO | android.os.FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, String path) {
                if (path == null || !path.equals("CourseData.xml")) return;
                // 防抖：移除上次未执行的任务，延迟 1500ms 执行
                getRescheduleHandler().removeCallbacksAndMessages(mRescheduleToken);
                getRescheduleHandler().postDelayed(() -> {
                    // ── 回调层统一哈希去重：课前提醒 + 静音两条路径共享同一份检查 ──
                    // 只在这里【读取并比较】哈希；mLastCourseDataHash 的【更新】由 scheduleTodayCourseReminders
                    // 负责（RESCHEDULE_DAILY / SYNC_PREFS 强制重调时也会先重置为 0）。
                    // 若只开静音（sCustomReminderEnabled=false），mLastCourseDataHash 由 init block 初始化。
                    String bj = null;
                    try {
                        @SuppressWarnings("deprecation")
                        SharedPreferences cp = ctx.getSharedPreferences(
                                PREFS_COURSE_DATA, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                        bj = cp.getString("weekCourseBean", null);
                        if (bj != null && !bj.isEmpty()) {
                            int h = stableCourseHash(bj);
                            if (h == mLastCourseDataHash) {
                                XposedBridge.log(TAG + ": [FileObserver] 内容未变化，跳过重调度（hash=" + h + "）");
                                return;
                            }
                            // 内容已变化：更新哈希，后续两个调度函数均需执行
                            mLastCourseDataHash = h;
                        }
                    } catch (Throwable ignored) {}
                    XposedBridge.log(TAG + ": CourseData.xml 已变化，重新调度课前提醒");
                    scheduleTodayCourseReminders(ctx, bj);
                    scheduleTodayMuteAlarms(ctx);
                }, mRescheduleToken, RESCHEDULE_DEBOUNCE_MS);
            }
        };
        mCourseDataObserver.startWatching();
        XposedBridge.log(TAG + ": CourseData FileObserver 已启动，监控目录: " + dirPath);
    }

    /**     * Hook 自身进程的 MainActivity.isModuleActive()，将返回值替换为 true，
     * 使主界面能正确检测到模块已激活。
     */
    private void hookSelfStatus(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            findAndHookMethod(
                    "com.xiaoai.islandnotify.MainActivity",
                    lpparam.classLoader,
                    "isModuleActive",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookSelfStatus 失败: " + t.getMessage());
        }
    }

    /**
     * Hook NotificationManager 的两个 notify 重载，在通知发出前注入岛参数。
     */
    private void hookNotifyMethods(XC_LoadPackage.LoadPackageParam lpparam) {

        // ① notify(int id, Notification notification)
        try {
            findAndHookMethod(
                    "android.app.NotificationManager",
                    lpparam.classLoader,
                    "notify",
                    int.class,
                    Notification.class,
                    new NotifyHook(1) // notification 在 args[1]
            );
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": Hook notify(int,Notification) 失败 → " + e.getMessage());
        }

        // ② notify(String tag, int id, Notification notification)
        try {
            findAndHookMethod(
                    "android.app.NotificationManager",
                    lpparam.classLoader,
                    "notify",
                    String.class,
                    int.class,
                    Notification.class,
                    new NotifyHook(2) // notification 在 args[2]
            );
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": Hook notify(String,int,Notification) 失败 → " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部 Hook 实现
    // ═══════════════════════════════════════════════════════════════

    /**
     * @param notifArgIndex Notification 对象在 args 数组中的下标
     */
    private class NotifyHook extends XC_MethodHook {

        private final int notifArgIndex;

        NotifyHook(int notifArgIndex) {
            this.notifArgIndex = notifArgIndex;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            Notification notification = (Notification) param.args[notifArgIndex];
            if (notification == null) return;

            // 我方通知（已含岛参数）直接放行
            if (isAlreadyIsland(notification)) return;

            // 我方课程通知（携带课程标记）直接放行
            if (notification.extras != null
                    && notification.extras.containsKey("xiaoai.test.course_name")) return;

            // 小爱原生课程提醒 → 屏蔽，由我方通知替代
            if (isScheduleNotification(notification)) {
                param.setResult(null);
                XposedBridge.log(TAG + ": 已屏蔽小爱原生课程提醒通知");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 通知识别逻辑
    // ─────────────────────────────────────────────────────────────

    /**
     * 判断该通知是否已经携带超级岛参数（避免重复注入）。
     */
    private boolean isAlreadyIsland(Notification notification) {
        if (notification.extras == null) return false;
        return notification.extras.containsKey(KEY_FOCUS_PARAM);
    }

    /**
     * 判断是否为课程表提醒通知。
     * 实测 logcat 确认 channelId = {@code "COURSE_SCHEDULER_REMINDER_sound"}。
     */
    private boolean isScheduleNotification(Notification notification) {
        String channelId = safeStr(notification.getChannelId());
        // 匹配 voiceassist 自身的课程提醒渠道，以及我们自己的独立提醒渠道
        boolean hit = channelId.contains("COURSE_SCHEDULER_REMINDER")
                || channelId.equals("xiaoai_course_reminder_alert");
        if (hit) XposedBridge.log(TAG + ": 命中 channelId=" + channelId);
        return hit;
    }

    // ─────────────────────────────────────────────────────────────
    // 超级岛参数注入
    // ─────────────────────────────────────────────────────────────

    /**
     * 向通知注入超级岛参数（miui.focus.param），并调度岛状态更新/取消闹钟。
     * 在 nm.notify() 之前调用，使通知直接携带岛参数发出。
     */
    private void applyIslandParams(Context ctx, Notification notif,
            CourseInfo info, int notifId, String notifTag) {
        try {
            if (notif.extras == null) notif.extras = new Bundle();
            SharedPreferences prefs = loadPrefs(ctx);
            
            long startMs = computeClassStartMs(info.startTime);
            long endMs   = computeClassStartMs(info.endTime);
            long now     = System.currentTimeMillis();

            // 动态计算通知状态：倒计时(0)、上课中(1)、已下课(2)
            int state = STATE_COUNTDOWN;
            if (now >= endMs) {
                state = STATE_FINISHED;
            } else if (now >= startMs) {
                state = STATE_ELAPSED;
            }

            notif.extras.putString(KEY_FOCUS_PARAM, buildIslandJson(info, state, prefs));
            mNotifCourseOwner.put(notifId, info.courseName);
            try {
                Intent tableIntent = Intent.parseUri(COURSE_TABLE_INTENT, Intent.URI_INTENT_SCHEME);
                notif.contentIntent = PendingIntent.getActivity(ctx, 1, tableIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            } catch (Exception e) {
                XposedBridge.log(TAG + ": 课表 intent 解析失败 → " + e.getMessage());
            }

            String chId  = safeStr(notif.getChannelId());
            if (startMs > now && (startMs - now) <= 6 * 3600 * 1000L)
                scheduleIslandAlarm(ctx, info, STATE_ELAPSED,  chId, notifTag, notifId, startMs);
            if (!info.endTime.isEmpty() && endMs > now && (endMs - now) <= 6 * 3600 * 1000L) {
                // 锚点课程（有连续后续课程）：STATE_FINISHED 延迟 1 秒，确保连续触发 alarm 能先 cancel 它，
                // 避免"已下课"与"下节倒计时"在相同毫秒 competition 导致短暂闪烁。
                long finishedTrigger = mConsecutiveAnchors.contains(notifId) ? endMs + 1000 : endMs;
                scheduleIslandAlarm(ctx, info, STATE_FINISHED, chId, notifTag, notifId, finishedTrigger);
            }
            if (!mConsecutiveAnchors.contains(notifId))
                scheduleNotifCancelAlarms(ctx, prefs, notifTag, notifId, now, startMs, endMs);
            else
                XposedBridge.log(TAG + ": [锚点课程] id=" + notifId + " 跳过 cancel alarm");
            XposedBridge.log(TAG + ": applyIslandParams 完成(state=" + state + ") → " + info.courseName + " id=" + notifId);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": applyIslandParams 失败 → " + e.getMessage());
        }
    }

    private static String safeStr(String s) {
        return s != null ? s : "";
    }
    // 岛状态常量
    private static final int STATE_COUNTDOWN = 0; // 倒计时（上课前）
    private static final int STATE_ELAPSED   = 1; // 正计时（上课中）
    private static final int STATE_FINISHED  = 2; // 正计时（已下课）

    /**
     * 利用 AlarmManager.setExactAndAllowWhileIdle 在指定时刻发送岛状态更新广播。
     * 运行在 voiceassist 进程内，借用其 SCHEDULE_EXACT_ALARM 权限，精确唤醒 Doze。
     */
    private void scheduleIslandAlarm(Context ctx, CourseInfo info, int state,
            String channelId, String tag, int id, long triggerMs) {
        long delayMs = triggerMs - System.currentTimeMillis();
        if (delayMs <= 0) return;

        if (TARGET_PACKAGE.equals(ctx.getPackageName())) {
            // voiceassist 进程：用精确闹钟，可唤醒 Doze
            try {
                Intent intent = createServiceIntent(ACTION_ISLAND_UPDATE);
                intent.putExtra("course_name", info.courseName);
                intent.putExtra("start_time",  info.startTime);
                intent.putExtra("end_time",    info.endTime);
                intent.putExtra("classroom",   info.classroom);
                intent.putExtra("state",       state);
                intent.putExtra("channel_id",  channelId);
                intent.putExtra("notif_tag",   tag);
                intent.putExtra("notif_id",    id);
                int reqCode = id * 10 + state;
                PendingIntent pi = PendingIntent.getService(ctx, reqCode, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                PendingIntent showPi = PendingIntent.getBroadcast(ctx, reqCode | 0x60000000,
                        new Intent(ACTION_ISLAND_UPDATE).setPackage(TARGET_PACKAGE),
                        PendingIntent.FLAG_IMMUTABLE);
                AlarmManager am = ctx.getSystemService(AlarmManager.class);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerMs, showPi), pi);
                XposedBridge.log(TAG + ": AlarmManager(AlarmClock) 已设定 state=" + state
                        + " in " + (delayMs / 1000) + "s");
            } catch (Exception e) {
                XposedBridge.log(TAG + ": scheduleIslandAlarm 失败 → " + e.getMessage());
            }
        } else {
            // 模块自身进程（测试通知）：前台运行，Handler 足够
            final CourseInfo fi = info;
            final Context fc = ctx;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                android.app.NotificationManager nm =
                        fc.getSystemService(android.app.NotificationManager.class);
                android.service.notification.StatusBarNotification src = null;
                for (android.service.notification.StatusBarNotification sbn
                        : nm.getActiveNotifications()) {
                    if (sbn.getId() == id) { src = sbn.getNotification() != null ? sbn : null; break; }
                }
                if (src == null) return;
                SharedPreferences prefs =
                        fc.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                sendIslandUpdate(fi, state, fc, channelId, src.getNotification(), nm, tag, id, prefs);
            }, delayMs);
            XposedBridge.log(TAG + ": Handler 已设定 state=" + state + " in " + (delayMs / 1000) + "s");
        }
    }

    /**
     * 在三个时间点各调度一个 ACTION_NOTIF_CANCEL 闹钟，先触发者取消通知（其余成为 no-op）。
     * 时间点：pre→通知发出后，active→上课后，post→下课后。val=-1 表示跳过该阶段。
     * 使用 setAlarmClock 保证精确触发，替代原 Handler.postDelayed。
     */
    private void scheduleNotifCancelAlarms(Context ctx,
            android.content.SharedPreferences prefs,
            String tag, int id,
            long notifPostedMs, long startMs, long endMs) {
        if (ctx == null) return;
        final String[] phases = {"pre", "active", "post"};
        final long[]   baseMs = {notifPostedMs, startMs, endMs};
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        for (int i = 0; i < 3; i++) {
            int    val  = (prefs != null) ? prefs.getInt   ("to_notif_val_"  + phases[i], -1) : -1;
            String unit = (prefs != null) ? safeStr(prefs.getString("to_notif_unit_" + phases[i], "m")) : "m";
            if (val <= 0 || baseMs[i] <= 0) continue;
            long delayMs   = "s".equals(unit) ? (long) val * 1000L : (long) val * 60_000L;
            long triggerMs = baseMs[i] + delayMs;
            if (triggerMs <= System.currentTimeMillis()) continue;
            // reqCode: id * 10 + state 已用 0-2，+3+i 用于 cancel（3/4/5），不冲突
            int reqCode = id * 10 + 3 + i;
            Intent ci = createServiceIntent(ACTION_NOTIF_CANCEL);
            ci.putExtra("notif_id",  id);
            ci.putExtra("notif_tag", tag);
            ci.putExtra("phase",     phases[i]);
            PendingIntent pi = PendingIntent.getService(ctx, reqCode, ci,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent showPi = PendingIntent.getBroadcast(ctx, reqCode | 0x60000000,
                    new Intent(ACTION_NOTIF_CANCEL).setPackage(TARGET_PACKAGE),
                    PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerMs, showPi), pi);
            XposedBridge.log(TAG + ": 通知取消 AlarmClock [" + phases[i] + "] in "
                    + (triggerMs - System.currentTimeMillis()) / 1000 + "s");
        }
    }

    /**
     * 构建并发送更新后的岛通知。
     */
    /**
     * 岛状态更新专用渠道 ID（IMPORTANCE_LOW：无声无震，渠道级保证，不依赖 FLAG_ONLY_ALERT_ONCE）。
     * 与 voiceassist 自带的 COURSE_SCHEDULER_REMINDER_sound 完全独立，不会被其 importance 覆盖。
     */
    private static final String ISLAND_UPDATE_CHANNEL = "xiaoai_island_update_silent";

    private void sendIslandUpdate(CourseInfo info, int state,
            Context ctx, String channelId, Notification src,
            android.app.NotificationManager nm, String tag, int id,
            android.content.SharedPreferences prefs) {
        try {
            // 确保静音更新渠道存在（IMPORTANCE_LOW = 无声无震，不受 voiceassist 原渠道影响）
            if (nm.getNotificationChannel(ISLAND_UPDATE_CHANNEL) == null) {
                android.app.NotificationChannel uch = new android.app.NotificationChannel(
                        ISLAND_UPDATE_CHANNEL, "岛状态更新",
                        android.app.NotificationManager.IMPORTANCE_LOW);
                uch.setSound(null, null);   // 渠道无声
                uch.enableVibration(false); // 渠道无震
                nm.createNotificationChannel(uch);
            }
            String json = buildIslandJson(info, state, prefs);
            Notification n = new Notification.Builder(ctx, ISLAND_UPDATE_CHANNEL)
                    .setSmallIcon(src.getSmallIcon())
                    .setContentTitle(info.courseName)
                    .setContentText(info.startTime
                            + (info.endTime.isEmpty() ? "" : " | " + info.endTime)
                            + (info.classroom.isEmpty() ? "" : " " + info.classroom))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)   // 双重保险
                    .build();
            n.extras.putString(KEY_FOCUS_PARAM, json);
            n.contentIntent = src.contentIntent;
            if (tag != null) nm.notify(tag, id, n);
            else             nm.notify(id, n);
            XposedBridge.log(TAG + ": 岛状态更新已发送 state=" + state + " id=" + id);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": 岛状态更新失败 state=" + state + " → " + e.getMessage());
        }
    }

    /**
     * 统一构建超级岛 JSON。三种状态的差异通过 state 参数区分：
     *   STATE_COUNTDOWN：上课前倒计时，上课静音按钮
     *   STATE_ELAPSED  ：上课中正计时，上课静音按钮
     *   STATE_FINISHED ：下课后正计时，解除静音按钮
     */
    private String buildIslandJson(CourseInfo info, int state,
            android.content.SharedPreferences prefs) throws JSONException {
        long startMs = computeClassStartMs(info.startTime);
        long endMs   = computeClassStartMs(info.endTime);
        long now     = System.currentTimeMillis();
        boolean isFinished = state == STATE_FINISHED;
        boolean isActive   = state != STATE_COUNTDOWN; // STATE_ELAPSED 或 STATE_FINISHED

        // ── baseInfo ──────────────────────────────────────────────
        JSONObject baseInfo = new JSONObject();
        baseInfo.put("type",  2);
        baseInfo.put("title", info.courseName);
        if (isActive) baseInfo.put("showDivider", true);
        if (!info.startTime.isEmpty()) baseInfo.put("content",    info.startTime);
        if (!info.endTime.isEmpty())   baseInfo.put("subContent", "| " + info.endTime);

        // ── picInfo（展开态识别图形组件，始终存在）────────────────────────
        JSONObject notifPicInfo = new JSONObject();
        notifPicInfo.put("type", 1); // 1 = appIcon

        // ── actionInfo ────────────────────────────────────────────
        // 发给 voiceassist 自身进程内的广播接收器（同进程，无跨包权限问题）
        // 按钮模式：0=仅静音, 1=仅勿扰, 2=两者
        boolean showMute = sIslandButtonMode == 0 || sIslandButtonMode == 2;
        boolean showDnd  = sIslandButtonMode == 1 || sIslandButtonMode == 2;
        
        String muteAction = isFinished ? ACTION_MANUAL_UNMUTE : ACTION_MANUAL_MUTE;
        String actionTitle;
        if (showMute && showDnd) {
            actionTitle = isFinished ? "解除静默" : "上课静默";
        } else if (showDnd) {
            actionTitle = isFinished ? "解除勿扰" : "上课勿扰";
        } else {
            actionTitle = isFinished ? "解除静音" : "上课静音";
        }
        JSONObject actionInfo = new JSONObject();
        actionInfo.put("actionIntentType", 2);
        actionInfo.put("actionIntent",
                "intent:#Intent;action=" + muteAction
                + ";package=" + TARGET_PACKAGE
                + ";launchFlags=0x10000000;end");
        actionInfo.put("actionTitle", actionTitle);

        // ── hintInfo ──────────────────────────────────────────────
        long   timerMs;
        int    timerType;
        String hintContent;
        if (isFinished) {
            timerMs     = endMs;
            timerType   = 1;
            hintContent = "已经下课";
        } else if (isActive) {
            timerMs     = startMs;
            timerType   = 1;
            hintContent = "已经上课";
        } else {
            // STATE_COUNTDOWN：倒计时或已过时
            timerMs     = startMs;
            timerType   = (startMs > now) ? -1 : 1;
            hintContent = (startMs > now) ? "即将上课" : "已经上课";
        }
        JSONObject hintInfo = new JSONObject();
        hintInfo.put("type",       2);
        hintInfo.put("content",    hintContent);
        hintInfo.put("subContent", "地点");
        hintInfo.put("subTitle",   info.classroom.isEmpty() ? "—" : info.classroom);
        hintInfo.put("actionInfo", actionInfo);
        if (timerMs > 0) {
            JSONObject timerInfo = new JSONObject();
            timerInfo.put("timerType",          timerType);
            timerInfo.put("timerWhen",          timerMs);
            timerInfo.put("timerSystemCurrent", now);
            hintInfo.put("timerInfo", timerInfo);
        } else {
            hintInfo.put("title", hintContent);
        }

        // ── bigIslandArea ─────────────────────────────────────────
        // 按上课状态选择对应阶段模板后缀
        String stageSuffix = (state == STATE_COUNTDOWN) ? "_pre"
                           : (state == STATE_ELAPSED)   ? "_active" : "_post";
        final boolean showIconA = (prefs == null || prefs.getBoolean("icon_a", true));
        JSONObject aTextInfo = new JSONObject();
        String aFallback = resolveTemplate(DEFAULT_TPLS[
                (state == STATE_COUNTDOWN) ? 0 : (state == STATE_ELAPSED) ? 1 : 2][0], info, info.courseName);
        aTextInfo.put("title", resolveTemplate(
                getStagedPref(prefs, "tpl_a", stageSuffix), info, aFallback));
        JSONObject imageTextInfoLeft = new JSONObject();
        imageTextInfoLeft.put("type", 1);
        if (showIconA) {
            // 显示图标：picInfo.type=1 = appIcon（取应用桌面图标）
            JSONObject aPicInfo = new JSONObject();
            aPicInfo.put("type", 1);
            imageTextInfoLeft.put("picInfo", aPicInfo);
        }
        // 隐藏图标时不传 picInfo，文档："图标或正文大字二选一"，有 textInfo.title 即合规
        imageTextInfoLeft.put("textInfo", aTextInfo);
        JSONObject bTextInfo = new JSONObject();
        String bFallback = resolveTemplate(DEFAULT_TPLS[
                (state == STATE_COUNTDOWN) ? 0 : (state == STATE_ELAPSED) ? 1 : 2][1], info, info.classroom.isEmpty() ? "\u2014" : info.classroom);
        bTextInfo.put("title", resolveTemplate(
                getStagedPref(prefs, "tpl_b", stageSuffix), info, bFallback));
        JSONObject bigIslandArea = new JSONObject();
        bigIslandArea.put("imageTextInfoLeft", imageTextInfoLeft);
        bigIslandArea.put("textInfo",          bTextInfo);

        // ── smallIslandArea（小岛/息屏图标）────────────────────────────────────────
        // 文档说明：小岛图标有三级 fallback（开发者上传 → A区左侧图标 → 应用图标），
        // 系统始终显示图标，无法通过不传字段来隐藏。
        // 因此不传 smallIslandArea.picInfo，让系统自动 fallback 到 A区图标或应用图标。
        // icon_a 关闭时 A区无 picInfo，小岛最终 fallback 到应用图标（始终有图标）。
        JSONObject smallIslandArea = new JSONObject();

        // ── shareData ─────────────────────────────────────────────
        String timeRange = info.startTime + (info.endTime.isEmpty() ? "" : "-" + info.endTime);
        JSONObject shareData = new JSONObject();
        shareData.put("pic",          PIC_KEY_SHARE);
        shareData.put("title",        info.courseName);
        shareData.put("content",      info.classroom.isEmpty() ? "" : info.classroom);
        shareData.put("shareContent", info.courseName
                + (info.classroom.isEmpty() ? "" : " " + info.classroom)
                + (timeRange.isEmpty() ? "" : " " + timeRange));

        // ── 岛消失超时（按阶段读取对应值）──────────
        // 根据当前 state 选择阶段后缀："pre" / "active" / "post"
        String phase = (state == STATE_COUNTDOWN) ? "pre"
                 : (state == STATE_ELAPSED)   ? "active" : "post";
        int islandToVal  = (prefs != null) ? prefs.getInt   ("to_island_val_"  + phase, -1) : -1;
        String islandToUnit = (prefs != null) ? safeStr(prefs.getString("to_island_unit_" + phase, "m")) : "m";

        JSONObject paramIsland = new JSONObject();
        paramIsland.put("islandProperty",  1);
        paramIsland.put("bigIslandArea",   bigIslandArea);
        paramIsland.put("smallIslandArea", smallIslandArea);
        paramIsland.put("shareData",       shareData);
        if (islandToVal > 0) {
            long islandToSecs = "m".equals(islandToUnit) ? (long) islandToVal * 60 : islandToVal;
            paramIsland.put("islandTimeout", islandToSecs);
            XposedBridge.log(TAG + ": islandTimeout(" + phase + ")=" + islandToSecs + "s");
        }

        // ── paramV2 ───────────────────────────────────────────────
        String tickerText = resolveTemplate(
                getStagedPref(prefs, "tpl_ticker", stageSuffix),
                info, buildTickerText(info));
        JSONObject paramV2 = new JSONObject();
        paramV2.put("protocol",    1);
        paramV2.put("business",    "course_schedule");
        paramV2.put("updatable",   true);
        paramV2.put("colorScheme", 0); // 0 = 自动适配，状态栏自动反色
        paramV2.put("ticker",      tickerText);
        paramV2.put("aodTitle",    tickerText);
        if (isActive) {
            paramV2.put("islandFirstFloat", true);
            paramV2.put("enableFloat",      true);
        } else {
            paramV2.put("enableFloat", false);
        }
        paramV2.put("baseInfo",     baseInfo);
        paramV2.put("picInfo",      notifPicInfo);
        paramV2.put("hintInfo",     hintInfo);
        paramV2.put("param_island", paramIsland);
        // 注意：param_v2.timeout 字段在实测中无效，通知消失改由 scheduleNotifCancelAlarms
        // 在 injectIslandParams 时通过 AlarmManager + nm.cancel() 实现。

        JSONObject root = new JSONObject();
        root.put("param_v2", paramV2);
        return root.toString();
    }

    /** 状态栏 / 息屏文案兜底（getStagedPref 已优先返回 DEFAULT_TPLS，此方法为最后保底） */
    private String buildTickerText(CourseInfo info) {
        return (info.startTime.isEmpty() ? info.courseName : info.startTime + "上课");
    }

    // 各阶段模板的最终兑底默认值（即使未开启过模块主界面也生效）
    private static final String[][] DEFAULT_TPLS = {
        // { tpl_a,    tpl_b,       tpl_ticker          }
        { "{\u6559\u5ba4}",   "{\u5f00\u59cb}\u4e0a\u8bfe",  "{\u6559\u5ba4}\uff5c{\u5f00\u59cb}\u4e0a\u8bfe"  }, // _pre
        { "{\u8bfe\u540d}",   "{\u7ed3\u675f}\u4e0b\u8bfe",  "{\u8bfe\u540d}\uff5c{\u7ed3\u675f}\u4e0b\u8bfe"  }, // _active
        { "{\u8bfe\u540d}",   "\u5df2\u7ecf\u4e0b\u8bfe",    "{\u8bfe\u540d}\uff5c\u5df2\u7ecf\u4e0b\u8bfe"    }, // _post
    };
    private static final String[] STAGE_SUFFIXES = {"_pre", "_active", "_post"};
    private static final String[] TPL_KEYS       = {"tpl_a", "tpl_b", "tpl_ticker"};

    /**
     * 读取分阶段模板（suffix = "_pre" / "_active" / "_post"）。
     * SP 为空时依次 fallback：无后缀旧 key → 代码内置默认值。
     */
    private static String getStagedPref(SharedPreferences prefs, String key, String suffix) {
        if (prefs != null) {
            String v = prefs.getString(key + suffix, "");
            if (v != null && !v.isEmpty()) return v;
            // 兼容旧版无分阶段配置
            String old = prefs.getString(key, "");
            if (old != null && !old.isEmpty()) return old;
        }
        // SP 完全为空（未开启过主界面），返回代码内置默认模板
        int si = java.util.Arrays.asList(STAGE_SUFFIXES).indexOf(suffix);
        int ki = java.util.Arrays.asList(TPL_KEYS).indexOf(key);
        if (si >= 0 && ki >= 0) return DEFAULT_TPLS[si][ki];
        return "";
    }

    /**
     * 将模板字符串中的变量替换为实际课程信息。
     * 支持：{课名} {开始} {结束} {教室}
     * 若模板为空则返回 fallback。
     */
    private static String resolveTemplate(String tpl, CourseInfo info, String fallback) {
        if (tpl == null || tpl.isEmpty()) return fallback;
        return tpl
                .replace("{课名}", info.courseName)
                .replace("{开始}", info.startTime)
                .replace("{结束}", info.endTime)
                .replace("{教室}", info.classroom);
    }

    /**
     * 跨进程读取模块自身的 SharedPreferences。
     * 使用 XSharedPreferences 绕过 Android 9+ 的沙箱文件权限限制。
     * createPackageContext+MODE_PRIVATE 在 Android 9+ 会被 SELinux 拦截，无法使用。
     */
    private SharedPreferences loadPrefs(Context ctx) {
        // 优先读取目标进程自己的 SP（由广播同步写入，无 SELinux 问题）
        SharedPreferences local = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (local.getAll().size() > 0) {
            XposedBridge.log(TAG + ": 读取目标进程偷好设置，条目数=" + local.getAll().size());
            return local;
        }
        // 回退：XSharedPreferences（首次吏未同步时）
        try {
            de.robv.android.xposed.XSharedPreferences prefs =
                    new de.robv.android.xposed.XSharedPreferences(MODULE_PKG, PREFS_NAME);
            prefs.reload();
            XposedBridge.log(TAG + ": XSharedPreferences 加载，条目数=" + prefs.getAll().size());
            return prefs;
        } catch (Exception e) {
            XposedBridge.log(TAG + ": loadPrefs 失败 → " + e.getMessage());
            return null;
        }
    }

    /**
     * 计算今天上课开始时间的毫秒时间戳。
     * 用于 hintInfo.timerInfo（倒计时组件）。
     */
    private static long computeClassStartMs(String startTime) {
        if (startTime == null || startTime.isEmpty()) return -1;
        try {
            String[] parts = startTime.split(":");
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, h);
            cal.set(java.util.Calendar.MINUTE, m);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        } catch (Exception e) {
            return -1;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 数据结构
    // ─────────────────────────────────────────────────────────────

    private static class CourseInfo {
        final String courseName;
        final String startTime;
        final String endTime;
        final String classroom;

        CourseInfo(String courseName, String startTime, String endTime, String classroom) {
            this.courseName = courseName;
            this.startTime  = startTime;
            this.endTime    = endTime;
            this.classroom  = classroom;
        }
    }
}
