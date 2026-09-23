package com.xiaoai.islandnotify;

/**
 * 跨进程、跨 hook 共用的字面量。
 *
 * <p>存在理由：这些值原本在各个 hook 类里各写一份私有常量（有的连名字都不一样），
 * 数值一旦不一致，失败方式是<em>静默</em>的——比如 {@code UploadStateService} 的类名
 * 写错，startService 照样返回非 null，但 MainHook 侧的 {@code equals} 判断永远不成立，
 * 课程镜像就再也送不到宿主进程，而两边都只记一行无异常日志。
 * 本模块已经吃过一次「两边字面量不一致、谁都没报错」的亏（叫醒闹钟标签前缀
 * 「课表提醒」vs「课程提醒」），所以把这些值收到一处。
 *
 * <p>各 hook 类仍保留自己原有的字段名（值改为引用这里），这样调用点一行都不用动，
 * 而这些值的唯一定义只存在于本文件。
 *
 * <p>注意：本模块的类可能被 LSPosed 注入到 voiceassist / 拾光 / 时钟 / SystemUI
 * 等多个宿主进程里，本类不能持有任何可变状态，也不要碰 Context。
 */
final class ModuleConstants {

    private ModuleConstants() {
    }

    /** 承载超级岛与课程镜像的宿主进程。 */
    static final String VOICEASSIST_PKG = "com.miui.voiceassist";

    /**
     * voiceassist 侧被 {@code MainHook} hook 了 {@code onStartCommand} 的 Service。
     *
     * <p>第三方课表用它把可能已被杀的 voiceassist 进程拉起来，并把 extras 转成包内广播；
     * {@code MainHook.hookUploadStateService} 里的类名匹配用的是<em>全限定名</em>字符串，
     * 与这里必须逐字节一致。
     */
    static final String VOICEASSIST_UPLOAD_SERVICE =
            "com.xiaomi.voiceassistant.UploadStateService";

    /** 拾光课程表宿主包名。 */
    static final String SHIGUANG_PKG = "com.xingheyuzhuan.shiguangschedule";

    /** 跨天重调用的包内广播（MainHook 注册、MainActivity 触发）。 */
    static final String ACTION_RESCHEDULE_DAILY = "com.xiaoai.islandnotify.ACTION_RESCHEDULE_DAILY";

    /** 手动测试通知（MainHook 注册、MainActivity 触发）。 */
    static final String ACTION_TEST_NOTIFY = "com.xiaoai.islandnotify.ACTION_TEST_NOTIFY";
}
