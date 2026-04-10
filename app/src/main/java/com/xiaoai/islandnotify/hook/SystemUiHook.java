package com.xiaoai.islandnotify;

import android.os.Bundle;
import android.app.Notification;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Chronometer;
import android.widget.TextView;

import com.xiaoai.islandnotify.modernhook.XC_MethodHook;
import com.xiaoai.islandnotify.modernhook.XposedBridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.json.JSONObject;

import static com.xiaoai.islandnotify.modernhook.XposedHelpers.findAndHookMethod;

public class SystemUiHook {

    private static final String TAG = "IslandNotifySysUI";
    private static final Set<String> TARGET_PACKAGES = ConcurrentHashMap.newKeySet();

    private static final String DYNAMIC_ISLAND_CONTENT_IFACE =
            "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandContent";
    private static final String DYNAMIC_ISLAND_DATA_CLASS =
            "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData";
    private static final String EXTRA_BIG_ISLAND_EFFECT = "miui.bigIsland.effect.src";
    private static final String FOCUS_CONTENT_CLASS =
            "com.android.systemui.plugins.miui.notification.FocusNotificationContent";
    private static final String FOCUS_NOTIFICATION_CONTROLLER_CLASS =
            "miui.systemui.notification.focus.FocusNotificationController";
    private static final String DEVICE_LISTENER_CLASS =
            "com.android.systemui.devicenotification.listener.DeviceNotificationListenerImpl";
    private static final String DEVICE_MODEL_CLASS =
            "com.android.systemui.devicenotification.bean.DeviceNotificationModel";
    private static final String DYNAMIC_ISLAND_WINDOW_VIEW_CONTROLLER_CLASS =
            "miui.systemui.dynamicisland.window.DynamicIslandWindowViewController";
    private static final String DYNAMIC_ISLAND_ANIMATION_CONTROLLER_CLASS =
            "miui.systemui.dynamicisland.anim.DynamicIslandAnimationController";
    private static final String DYNAMIC_ISLAND_BASE_CONTENT_VIEW_CLASS =
            "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView";
    private static final String TEMPLATE_FACTORY_V3_CLASS =
            "miui.systemui.notification.focus.templateV3.TemplateFactoryV3";
    private static final String DYNAMIC_FEATURE_CONFIG_CLASS =
            "miui.systemui.dynamicisland.DynamicFeatureConfig";
    private static final String MODULE_TEXT_VIEW_HOLDER_CLASS =
            "miui.systemui.notification.focus.moduleV3.ModuleTextViewHolder";
    private static final String MODULE_TINY_TEXT_VIEW_HOLDER_CLASS =
            "miui.systemui.notification.focus.moduleV3.ModuleTinyTextViewHolder";
    private static final String MODULE_DECO_PORT_TEXT_BUTTON_VIEW_HOLDER_CLASS =
            "miui.systemui.notification.focus.moduleV3.ModuleDecoPortTextButtonViewHolder";
    private static final String MODULE_DECO_PORT_TEXT_VIEW_HOLDER_CLASS =
            "miui.systemui.notification.focus.moduleV3.ModuleDecoPortTextViewHolder";
    private static final String MODULE_TEXT_BUTTON_VIEW_HOLDER_CLASS =
            "miui.systemui.notification.focus.moduleV3.ModuleTextButtonViewHolder";
    private static final String MODULE_TINY_TEXT_BUTTON_VIEW_HOLDER_CLASS =
            "miui.systemui.notification.focus.moduleV3.ModuleTinyTextButtonViewHolder";
    private static final String BASE_ISLAND_MODULE_VIEW_HOLDER_CLASS =
            "miui.systemui.dynamicisland.module.BaseIslandModuleViewHolder";
    private static final String ISLAND_TEXT_VIEW_HOLDER_CLASS =
            "miui.systemui.dynamicisland.module.IslandTextViewHolder";
    private static final String ISLAND_RIGHT_TEXT_VIEW_HOLDER_CLASS =
            "miui.systemui.dynamicisland.module.IslandRightTextViewHolder";
    private static final String ISLAND_SAME_WIDTH_DIGIT_VIEW_HOLDER_CLASS =
            "miui.systemui.dynamicisland.module.IslandSameWidthDigitViewHolder";

    private static final ThreadLocal<Boolean> sReentry = new ThreadLocal<>();
    private static final ThreadLocal<Integer> sIslandBindDepth = new ThreadLocal<>();
    private static final Set<String> sHookedIslandContentClasses = ConcurrentHashMap.newKeySet();
    private static final Set<String> sHookedShaderFeatureClasses = ConcurrentHashMap.newKeySet();
    private static final Set<String> sHookedAnimationControllerClasses = ConcurrentHashMap.newKeySet();
    private static final Map<TextView, Boolean> sAdaptiveWatchers =
            java.util.Collections.synchronizedMap(new WeakHashMap<TextView, Boolean>());
    private static final Map<Object, String> sFocusContentKeyMap =
            java.util.Collections.synchronizedMap(new WeakHashMap<Object, String>());
    private static final ConcurrentMap<String, CachedTexts> sFullTextByKey = new ConcurrentHashMap<>();
    private static final Map<ClassLoader, Boolean> sInstalledHookLoaders =
            java.util.Collections.synchronizedMap(new WeakHashMap<ClassLoader, Boolean>());
    private static volatile boolean sBaseDexCtorHooked = false;
    private static volatile boolean sLoadClassHooked = false;

    static {
        TARGET_PACKAGES.add("com.android.systemui");
        TARGET_PACKAGES.add("miui.systemui.plugin");
    }

    public void handleLoadPackage(String packageName, ClassLoader classLoader) {
        if (!TARGET_PACKAGES.contains(packageName)) return;
        if ("com.android.systemui".equals(packageName)) {
            installHooksForClassLoader(classLoader);
            hookPluginClassLoaderBridge(classLoader);
            return;
        }
        installHooksForClassLoader(classLoader);
    }

    private void installHooksForClassLoader(ClassLoader classLoader) {
        if (classLoader == null) return;
        if (sInstalledHookLoaders.containsKey(classLoader)) return;
        sInstalledHookLoaders.put(classLoader, Boolean.TRUE);
        hookDynamicIslandShaderFeature(classLoader);
        hookBigIslandAnimationState(classLoader);
        hookFocusDynamicIslandExtrasBridge(classLoader);
        hookExactFirstLimitPoints(classLoader);
        hookIslandExpandedView(classLoader);
        hookSameWidthDigitSuffixStyle(classLoader);
        hookSameWidthDigitContentColor(classLoader);
    }

    private void hookFocusDynamicIslandExtrasBridge(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(FOCUS_NOTIFICATION_CONTROLLER_CLASS, false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"setUpDynamicIslandDataBundle".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts == null || pts.length != 1) continue;
                if (!StatusBarNotification.class.equals(pts[0])) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object result = param.getResult();
                            if (!(result instanceof Bundle)) return;
                            Bundle dataBundle = (Bundle) result;
                            StatusBarNotification sbn = null;
                            if (param.args != null && param.args.length > 0
                                    && param.args[0] instanceof StatusBarNotification) {
                                sbn = (StatusBarNotification) param.args[0];
                            }
                            Bundle notifExtras = null;
                            if (sbn != null) {
                                Notification n = sbn.getNotification();
                                if (n != null) notifExtras = n.extras;
                            }
                            String bigEffect = dataBundle.getString(EXTRA_BIG_ISLAND_EFFECT, null);
                            if (notifExtras != null) {
                                String nBig = notifExtras.getString(EXTRA_BIG_ISLAND_EFFECT, null);
                                if (!TextUtils.isEmpty(nBig)) {
                                    bigEffect = nBig;
                                }
                            }
                            if (TextUtils.isEmpty(bigEffect)) return;
                            dataBundle.putString(EXTRA_BIG_ISLAND_EFFECT, bigEffect);
                        } catch (Throwable ignore) {
                        }
                    }
                });
                return;
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private Bundle extractExtrasFromDynamicIslandData(Object dataObj) {
        try {
            Object extrasObj = invokeNoArg(dataObj, "getExtras");
            if (extrasObj instanceof Bundle) return (Bundle) extrasObj;
        } catch (Throwable ignore) {
        }
        return null;
    }

    private void hookDynamicIslandShaderFeature(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(DYNAMIC_FEATURE_CONFIG_CLASS, false, classLoader);
            String clsName = cls.getName();
            if (!sHookedShaderFeatureClasses.add(clsName)) return;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"getFEATURE_DYNAMIC_ISLAND_SHADER".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 0) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.TRUE);
                    }
                });
                return;
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private boolean hasVoiceAssistBigGlowRequest(Object dataObj) {
        Bundle extras = extractExtrasFromDynamicIslandData(dataObj);
        if (extras == null) return false;
        String bigEffect = extras.getString(EXTRA_BIG_ISLAND_EFFECT, "");
        return !TextUtils.isEmpty(bigEffect);
    }

    private void hookBigIslandAnimationState(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(DYNAMIC_ISLAND_ANIMATION_CONTROLLER_CLASS, false, classLoader);
            String clsName = cls.getName();
            if (!sHookedAnimationControllerClasses.add(clsName)) return;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"onStateChange".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts == null || pts.length < 1) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object stateObj = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                            if (stateObj == null) return;
                            Object dataObj = extractDynamicDataFromAnimationState(stateObj);
                            if (!hasVoiceAssistBigGlowRequest(dataObj)) return;
                            boolean isBig = isBigIslandStateTag(stateObj);
                            boolean isDeleted = isDeletedIslandStateTag(stateObj);
                            Object bigView = invokeNoArg(stateObj, "getBigIslandView");
                            if (bigView == null) return;
                            if (isBig) {
                                normalizeBigGlowView(bigView);
                                invokeStartGlowEffect(bigView);
                            } else if (isDeleted) {
                                invokeStopGlowEffect(bigView);
                            }
                        } catch (Throwable ignore) {
                        }
                    }
                });
                return;
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private Object extractDynamicDataFromAnimationState(Object stateObj) {
        if (stateObj == null) return null;
        String[] names = new String[] {
                "getCurrentIslandData",
                "getIslandData",
                "getData"
        };
        for (String n : names) {
            try {
                Object value = invokeNoArg(stateObj, n);
                if (value != null) return value;
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    private boolean isBigIslandStateTag(Object stateObj) {
        if (stateObj == null) return false;
        Object state = invokeNoArg(stateObj, "getState");
        String text = String.valueOf(state);
        return text.contains("BigIsland");
    }

    private boolean isDeletedIslandStateTag(Object stateObj) {
        if (stateObj == null) return false;
        Object state = invokeNoArg(stateObj, "getState");
        String text = String.valueOf(state);
        return text.contains("Deleted");
    }

    private void normalizeBigGlowView(Object bigView) {
        if (bigView == null) return;
        // Keep default adaptive glow size from SystemUI. Forcing suppressAdaptiveGlowViewSize
        // can make the halo exceed island edges on some builds.
    }

    private void invokeStartGlowEffect(Object target) {
        if (target == null) return;
        String[] names = new String[] {
                "startGlowEffect$miui_dynamicisland_release",
                "startGlowEffect"
        };
        for (String n : names) {
            try {
                Method m = target.getClass().getMethod(n);
                m.setAccessible(true);
                m.invoke(target);
                return;
            } catch (Throwable ignore) {
            }
        }
        try {
            for (Method m : target.getClass().getMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                if (!m.getName().contains("startGlowEffect")) continue;
                m.setAccessible(true);
                m.invoke(target);
                return;
            }
        } catch (Throwable ignore) {
        }
    }

    private void invokeStopGlowEffect(Object target) {
        if (target == null) return;
        String[] names = new String[] {
                "stopGlowEffect$miui_dynamicisland_release",
                "stopGlowEffect"
        };
        for (String n : names) {
            try {
                Method m = target.getClass().getMethod(n, boolean.class);
                m.setAccessible(true);
                m.invoke(target, Boolean.TRUE);
                return;
            } catch (Throwable ignore) {
            }
            try {
                Method m = target.getClass().getMethod(n);
                m.setAccessible(true);
                m.invoke(target);
                return;
            } catch (Throwable ignore) {
            }
        }
    }

    private void hookPluginClassLoaderBridge(ClassLoader classLoader) {
        if (!sLoadClassHooked) {
            synchronized (SystemUiHook.class) {
                if (!sLoadClassHooked) {
                    try {
                        findAndHookMethod("java.lang.ClassLoader", classLoader,
                                "loadClass", String.class, boolean.class, new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam param) {
                                        if (!(param.thisObject instanceof ClassLoader)) return;
                                        if (param.args == null || param.args.length == 0) return;
                                        Object nameObj = param.args[0];
                                        if (!(nameObj instanceof String)) return;
                                        String name = (String) nameObj;
                                        ClassLoader hit = (ClassLoader) param.thisObject;
                                        if (!isFocusModuleClassName(name)) return;
                                        installHooksForClassLoader(hit);
                                    }
                                });
                        sLoadClassHooked = true;
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": hook ClassLoader#loadClass bridge failed -> " + t.getMessage());
                    }
                }
            }
        }
        if (!sBaseDexCtorHooked) {
            synchronized (SystemUiHook.class) {
                if (!sBaseDexCtorHooked) {
                    try {
                        Class<?> baseDex = Class.forName("dalvik.system.BaseDexClassLoader", false, classLoader);
                        for (Constructor<?> c : baseDex.getDeclaredConstructors()) {
                            c.setAccessible(true);
                            XposedBridge.hookMethod(c, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    if (!(param.thisObject instanceof ClassLoader)) return;
                                    ClassLoader cl = (ClassLoader) param.thisObject;
                                    if (!isLikelyPluginClassLoader(cl)) return;
                                    installHooksForClassLoader(cl);
                                }
                            });
                        }
                        sBaseDexCtorHooked = true;
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": hook BaseDexClassLoader bridge failed -> " + t.getMessage());
                    }
                }
            }
        }
    }

    private boolean isFocusModuleClassName(String name) {
        if (TextUtils.isEmpty(name)) return false;
        return name.startsWith("miui.systemui.notification.focus.moduleV3.")
                || name.startsWith("miui.systemui.notification.focus.templateV3.")
                || name.startsWith("miui.systemui.dynamicisland.");
    }

    private boolean isLikelyPluginClassLoader(ClassLoader cl) {
        if (cl == null) return false;
        String s = String.valueOf(cl);
        if (TextUtils.isEmpty(s)) return false;
        String lower = s.toLowerCase();
        return lower.contains("sysui_component")
                || lower.contains("miui.systemui.plugin")
                || lower.contains("systemui_component");
    }

    private static void swallowOptionalHookFailure(Throwable t) {
        // Optional hook points may not exist across ROM/plugin versions.
        // Keep silent to avoid noisy logs when fallback paths still work.
    }

    private void hookExactFirstLimitPoints(ClassLoader classLoader) {
        // 仅保留“主要小文本2(subTitle)”相关限制修正，避免影响未展开岛A区域宽度。
        hookFocusSmallSubtitleFirstLimit(classLoader);
        hookFinalTitleMarqueePoints(classLoader);
    }

    private void hookFinalTitleMarqueePoints(ClassLoader classLoader) {
        hookNotifyDataChangedMarquee(classLoader, MODULE_TEXT_BUTTON_VIEW_HOLDER_CLASS);
        hookNotifyDataChangedMarquee(classLoader, MODULE_TINY_TEXT_BUTTON_VIEW_HOLDER_CLASS);
        hookNotifyDataChangedMarquee(classLoader, MODULE_TEXT_VIEW_HOLDER_CLASS);
    }

    private void hookSameWidthDigitSuffixStyle(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(ISLAND_SAME_WIDTH_DIGIT_VIEW_HOLDER_CLASS, false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"bind".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 2) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object self = param.thisObject;
                        if (self == null) return;
                        Object digitObj = getFieldValue(self, "sameWidthDigit");
                        Object titleObj = getFieldValue(self, "title");
                        Object contentObj = getFieldValue(self, "content");
                        if (!(contentObj instanceof TextView)) return;
                        enforceSameWidthDigitTypeface(self);
                        TextView source = null;
                        if (digitObj instanceof TextView) {
                            TextView digit = (TextView) digitObj;
                            if (digit.getVisibility() == View.VISIBLE) source = digit;
                        }
                        if (source == null && titleObj instanceof TextView) {
                            TextView title = (TextView) titleObj;
                            if (title.getVisibility() == View.VISIBLE) source = title;
                        }
                        if (source == null && titleObj instanceof TextView) {
                            source = (TextView) titleObj;
                        }
                        if (source == null) return;
                        harmonizeSuffixStyle(source, (TextView) contentObj);
                    }
                });
                return;
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void enforceSameWidthDigitTypeface(Object holder) {
        if (holder == null) return;
        TextView digit = null;
        TextView title = null;
        TextView content = null;
        Object d = getFieldValue(holder, "sameWidthDigit");
        Object t = getFieldValue(holder, "title");
        Object c = getFieldValue(holder, "content");
        if (d instanceof TextView) digit = (TextView) d;
        if (t instanceof TextView) title = (TextView) t;
        if (c instanceof TextView) content = (TextView) c;
        android.graphics.Typeface tf = android.graphics.Typeface.create("mipro-demibold", android.graphics.Typeface.NORMAL);
        sReentry.set(Boolean.TRUE);
        try {
            if (digit != null) {
                digit.setTypeface(tf);
                digit.setTextScaleX(1f);
                digit.setLetterSpacing(0f);
            }
            if (title != null) {
                title.setTypeface(tf);
                title.setTextScaleX(1f);
                title.setLetterSpacing(0f);
            }
            if (content != null) {
                content.setTypeface(tf);
                content.setTextScaleX(1f);
                content.setLetterSpacing(0f);
            }
        } catch (Throwable ignore) {
        } finally {
            sReentry.set(Boolean.FALSE);
        }
    }

    private void hookSameWidthDigitContentColor(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(BASE_ISLAND_MODULE_VIEW_HOLDER_CLASS, false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"setContentHighlightColor".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 4) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object self = param.thisObject;
                        if (self == null) return;
                        if (!ISLAND_SAME_WIDTH_DIGIT_VIEW_HOLDER_CLASS.equals(self.getClass().getName())) return;
                        if (param.args == null || param.args.length < 4) return;
                        Object contentObj = param.args[2];
                        Object textObj = param.args[3];
                        if (!(contentObj instanceof TextView)) return;
                        String text = textObj instanceof String ? (String) textObj : null;
                        if (text == null) return;
                        TextView content = (TextView) contentObj;

                        TextView src = null;
                        Object digitObj = getFieldValue(self, "sameWidthDigit");
                        if (digitObj instanceof TextView && ((TextView) digitObj).getVisibility() == View.VISIBLE) {
                            src = (TextView) digitObj;
                        }
                        if (src == null) {
                            Object titleObj = getFieldValue(self, "title");
                            if (titleObj instanceof TextView) src = (TextView) titleObj;
                        }
                        int color = src != null ? src.getCurrentTextColor() : content.getCurrentTextColor();
                        sReentry.set(Boolean.TRUE);
                        try {
                            invokeUpdateTextWithColor(content, text, color);
                            content.setTextColor(color);
                        } catch (Throwable ignore) {
                        } finally {
                            sReentry.set(Boolean.FALSE);
                        }
                    }
                });
                return;
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void invokeUpdateTextWithColor(TextView target, String text, int color) {
        if (target == null) return;
        try {
            Method m = target.getClass().getMethod("updateTextWithNewAppearance", CharSequence.class, Integer.class);
            m.setAccessible(true);
            m.invoke(target, text, Integer.valueOf(color));
        } catch (Throwable ignore) {
            target.setText(text, TextView.BufferType.SPANNABLE);
        }
    }

    private void harmonizeSuffixStyle(TextView title, TextView content) {
        if (title == null || content == null) return;
        sReentry.set(Boolean.TRUE);
        try {
            applyExactIslandTitleStyle(content);
            content.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, title.getTextSize());
            content.setTypeface(android.graphics.Typeface.create("mipro-demibold", android.graphics.Typeface.NORMAL));
            if (title.getTextColors() != null) {
                content.setTextColor(title.getTextColors());
            } else {
                content.setTextColor(title.getCurrentTextColor());
            }
            content.setAlpha(title.getAlpha());
            content.setIncludeFontPadding(title.getIncludeFontPadding());
            content.setGravity(title.getGravity());
            content.setAllCaps(title.isAllCaps());
            content.setElegantTextHeight(title.isElegantTextHeight());
            content.setFallbackLineSpacing(title.isFallbackLineSpacing());
            content.setShadowLayer(title.getShadowRadius(), title.getShadowDx(), title.getShadowDy(), title.getShadowColor());
            content.setLineSpacing(title.getLineSpacingExtra(), title.getLineSpacingMultiplier());
            content.setMinHeight(title.getMinHeight());
            content.setMinWidth(title.getMinWidth());
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                content.setLineHeight(title.getLineHeight());
            }
            ViewGroup.LayoutParams lp = content.getLayoutParams();
            ViewGroup.LayoutParams titleLp = title.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                if (titleLp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams tmlp = (ViewGroup.MarginLayoutParams) titleLp;
                    mlp.leftMargin = tmlp.leftMargin;
                    mlp.topMargin = tmlp.topMargin;
                    mlp.rightMargin = tmlp.rightMargin;
                    mlp.bottomMargin = tmlp.bottomMargin;
                } else {
                    mlp.leftMargin = 0;
                    mlp.topMargin = 0;
                    mlp.rightMargin = 0;
                    mlp.bottomMargin = 0;
                }
                content.setLayoutParams(mlp);
            }
            content.setPadding(title.getPaddingLeft(), title.getPaddingTop(), title.getPaddingRight(), title.getPaddingBottom());
            content.requestLayout();
        } catch (Throwable ignore) {
        } finally {
            sReentry.set(Boolean.FALSE);
        }
    }

    private void applyExactIslandTitleStyle(TextView target) {
        if (target == null) return;
        try {
            android.content.Context ctx = target.getContext();
            if (ctx == null) return;
            android.content.res.Resources res = ctx.getResources();
            if (res == null) return;
            String pkg = ctx.getPackageName();
            int styleId = res.getIdentifier("IslandTitleStyle", "style", pkg);
            if (styleId == 0) {
                styleId = res.getIdentifier("IslandTitleStyle", "style", "miui.systemui.plugin");
            }
            if (styleId != 0) {
                target.setTextAppearance(ctx, styleId);
            }
        } catch (Throwable ignore) {
        }
    }

    private void hookNotifyDataChangedMarquee(ClassLoader classLoader, String className) {
        try {
            Class<?> cls = Class.forName(className, false, classLoader);
            Method m = null;
            for (Method mm : cls.getDeclaredMethods()) {
                if (!"notifyDataChanged".equals(mm.getName())) continue;
                if (mm.getParameterTypes().length != 0) continue;
                m = mm;
                break;
            }
            if (m == null) return;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object self = param.thisObject;
                    if (self == null) return;
                    applyMarqueeForFieldTextView(self, "focusSmallTitle");
                    applyMarqueeForFieldTextView(self, "focusTitle");
                    applyMarqueeForFieldTextView(self, "focusTitleView");
                }
            });
        } catch (Throwable ignore) {
        }
    }

    private void applyMarqueeForFieldTextView(Object holder, String fieldName) {
        if (holder == null || TextUtils.isEmpty(fieldName)) return;
        try {
            Field f = findField(holder.getClass(), fieldName);
            if (f == null) return;
            f.setAccessible(true);
            Object obj = f.get(holder);
            if (!(obj instanceof TextView)) return;
            TextView tv = (TextView) obj;
            if (TextUtils.isEmpty(tv.getText())) return;
            ensureAdaptiveWatcher(tv);
            applyAdaptiveMarquee(tv);
        } catch (Throwable ignore) {
        }
    }

    private void hookCalculateMaxWidthWithSmall(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(DYNAMIC_ISLAND_BASE_CONTENT_VIEW_CLASS, false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"calculateMaxWidthWithSmall".equals(m.getName())) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object r = param.getResult();
                        if (!(r instanceof Number)) return;
                        int old = ((Number) r).intValue();
                        int widened = widenWidth(old);
                        if (param.thisObject instanceof View) {
                            View v = (View) param.thisObject;
                            int vw = v.getWidth() - v.getPaddingLeft() - v.getPaddingRight();
                            if (vw > widened) widened = vw;
                        }
                        if (r instanceof Integer) {
                            param.setResult(widened);
                        } else if (r instanceof Long) {
                            param.setResult((long) widened);
                        } else if (r instanceof Float) {
                            param.setResult((float) widened);
                        } else if (r instanceof Double) {
                            param.setResult((double) widened);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private int widenWidth(int value) {
        if (value <= 0) return value;
        long widened = value;
        widened = widened + Math.max(80L, widened / 2L);
        if (widened > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) widened;
    }

    private void hookExactTextLimitMethods(ClassLoader classLoader) {
        hookTextSetterLikeMethod(TEMPLATE_FACTORY_V3_CLASS, "setTextVisibleAndText", classLoader);
        hookTextSetterLikeMethod(MODULE_TEXT_VIEW_HOLDER_CLASS, "textChanged", classLoader);
        hookTextSetterLikeMethod(ISLAND_TEXT_VIEW_HOLDER_CLASS, "textChanged", classLoader);
    }

    private void hookIslandRightTextFirstLimit(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(ISLAND_RIGHT_TEXT_VIEW_HOLDER_CLASS, false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"updateWidth".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || p[0] != int.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0) return;
                        if (!(param.args[0] instanceof Integer)) return;
                        Object self = param.thisObject;
                        if (self == null) return;
                        int width = (Integer) param.args[0];
                        if (width <= 0) return;

                        Object ctxObj = invokeNoArg(self, "getContext");
                        if (!(ctxObj instanceof android.content.Context)) return;
                        android.content.Context ctx = (android.content.Context) ctxObj;
                        int bonus = 0;
                        bonus += getDimenPx(ctx, "island_area_padding");
                        bonus += getDimenPx(ctx, "text_padding");
                        bonus += getDimenPx(ctx, "island_area_padding_cutout");
                        bonus += getDimenPx(ctx, "island_text_padding_inner");
                        if (bonus <= 0) return;

                        long widened = (long) width + bonus;
                        if (widened > Integer.MAX_VALUE) widened = Integer.MAX_VALUE;
                        param.args[0] = (int) widened;
                    }
                });
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private int getDimenPx(android.content.Context ctx, String name) {
        if (ctx == null || TextUtils.isEmpty(name)) return 0;
        try {
            int id = ctx.getResources().getIdentifier(name, "dimen", ctx.getPackageName());
            if (id == 0) {
                id = ctx.getResources().getIdentifier(name, "dimen", "com.android.systemui");
            }
            if (id == 0) return 0;
            return ctx.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignore) {
            return 0;
        }
    }

    private void hookFocusSmallSubtitleFirstLimit(ClassLoader classLoader) {
        hookSubtitleWidthInSetViewWidth(classLoader);
        hookModuleTextButton2Bind(classLoader, MODULE_TEXT_BUTTON_VIEW_HOLDER_CLASS);
        hookModuleBindForSubtitle(classLoader, MODULE_DECO_PORT_TEXT_BUTTON_VIEW_HOLDER_CLASS);
    }

    private void hookModuleTextButton2Bind(ClassLoader classLoader, final String className) {
        try {
            Class<?> cls = Class.forName(className, false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"bind".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 2) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object self = param.thisObject;
                        if (self == null) return;
                        Object tpl = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                        ensureSubTitleFieldVisible(self, tpl, "smallSubTitle");
                        syncPureTimerToSubtitle(self, tpl, "smallSubTitle", "chronometerHint");
                        clearTitleWhenPureTimer(self, tpl);
                        fixTimerTitleAndSubtitleLeakForTextButton(self);
                    }
                });
                return;
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void hookSubtitleWidthInSetViewWidth(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName("miui.systemui.notification.focus.moduleV3.ModuleViewHolder", false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"setViewWidth".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 3) continue;
                if (p[0] != TextView.class || p[1] != int.class || p[2] != int.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length < 3) return;
                        Object tvObj = param.args[0];
                        if (!(tvObj instanceof TextView)) return;
                        TextView tv = (TextView) tvObj;
                        boolean subtitleTarget = isSubtitleTargetView(tv);
                        boolean titleTarget = isTitleTargetView(tv);
                        if (!subtitleTarget && !titleTarget) return;
                        if (!subtitleTarget) return;

                        CharSequence cs = tv.getText();
                        if (TextUtils.isEmpty(cs)) return;

                        int desired = (int) Math.ceil(tv.getPaint().measureText(cs.toString()))
                                + tv.getPaddingLeft() + tv.getPaddingRight();
                        ViewParent parent = tv.getParent();
                        if (parent instanceof View) {
                            View pv = (View) parent;
                            int available = pv.getWidth() - pv.getPaddingLeft() - pv.getPaddingRight();
                            if (available > 0) desired = Math.min(desired, available);
                        }
                        if (desired <= 0) return;

                        int oldW = param.args[1] instanceof Integer ? (Integer) param.args[1] : 0;
                        int minW = param.args[2] instanceof Integer ? (Integer) param.args[2] : 0;
                        if (oldW < desired) param.args[1] = desired;
                        if (minW < 1) param.args[2] = 1;
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length < 1) return;
                        Object tvObj = param.args[0];
                        if (!(tvObj instanceof TextView)) return;
                        TextView tv = (TextView) tvObj;
                        boolean subtitleTarget = isSubtitleTargetView(tv);
                        boolean titleTarget = isTitleTargetView(tv);
                        if (!subtitleTarget && !titleTarget) return;
                        if (TextUtils.isEmpty(tv.getText())) return;
                        if (titleTarget) {
                            ensureAdaptiveWatcher(tv);
                            applyAdaptiveMarquee(tv);
                            return;
                        }
                        sReentry.set(Boolean.TRUE);
                        try {
                            tv.setVisibility(View.VISIBLE);
                        } catch (Throwable ignore) {
                        } finally {
                            sReentry.set(Boolean.FALSE);
                        }
                    }
                });
                return;
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private boolean isSubtitleTargetView(TextView tv) {
        if (tv == null) return false;
        int id = tv.getId();
        if (id != View.NO_ID) {
            String idName = safeIdName(tv, id).toLowerCase();
            if (idName.contains("small_subtitle")) return true;
            if (idName.contains("small_sub_title")) return true;
            if (idName.contains("focus_small_subtitle")) return true;
            if (idName.contains("focus_small_sub_title")) return true;
            if (idName.endsWith("subtitle")) return true;
            if (idName.endsWith("sub_title")) return true;
        }
        return false;
    }

    private boolean isTitleTargetView(TextView tv) {
        if (tv == null) return false;
        int id = tv.getId();
        if (id == View.NO_ID) return false;
        String idName = safeIdName(tv, id).toLowerCase();
        if (idName.contains("small_subtitle") || idName.contains("sub_title") || idName.contains("subtitle")) {
            return false;
        }
        if (idName.contains("focus_small_title")) return true;
        if (idName.equals("focus_title")) return true;
        if (idName.endsWith("_title") && idName.contains("focus")) return true;
        return false;
    }

    private void hookModuleBindForSubtitle(ClassLoader classLoader, final String className) {
        try {
            Class<?> cls = Class.forName(className, false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"bind".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 2) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object self = param.thisObject;
                        if (self == null) return;
                        // hintInfo.type=2(按钮组件2): subTitle -> focusSmallSubtitleView
                        Object tpl = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                        ensureSubTitleFieldVisible(self, tpl, "focusSmallSubtitleView");
                        syncPureTimerToSubtitle(self, tpl, "focusSmallSubtitleView", "chronometerHintView");
                        clearTitleWhenPureTimer(self, tpl);
                        fixTimerTitleAndSubtitleLeakForDecoPort(self);
                        forceSmallSubtitleNoFirstTrim(self, "focusSmallSubtitleView");
                        // 组件2里 title 也在同一容器，避免相互挤压时再次触发首段省略
                    }
                });
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void forceSmallSubtitleNoFirstTrim(Object host, String fieldName) {
        Object tvObj = getFieldValue(host, fieldName);
        if (!(tvObj instanceof TextView)) return;
        TextView tv = (TextView) tvObj;
        sReentry.set(Boolean.TRUE);
        try {
            tv.setMaxWidth(Integer.MAX_VALUE / 4);
            tv.setMaxEms(Integer.MAX_VALUE / 4);
            tv.setSingleLine(true);
            tv.setMaxLines(1);
            tv.setHorizontallyScrolling(false);
            tv.setEllipsize(null);
        } catch (Throwable ignore) {
        } finally {
            sReentry.set(Boolean.FALSE);
        }
        ensureAdaptiveWatcher(tv);
        applyAdaptiveMarquee(tv);
    }

    private void ensureSubTitleFieldVisible(Object host, Object templateObj, String fieldName) {
        Object tvObj = getFieldValue(host, fieldName);
        if (!(tvObj instanceof TextView)) return;
        TextView tv = (TextView) tvObj;
        String text = extractSubTitle(host, templateObj);
        if (TextUtils.isEmpty(text)) {
            // 显式清空，避免Recycler/复用导致上一阶段文本残留
            sReentry.set(Boolean.TRUE);
            try {
                tv.setText("");
                tv.setSelected(false);
                tv.setEllipsize(null);
                tv.setHorizontallyScrolling(false);
            } catch (Throwable ignore) {
            } finally {
                sReentry.set(Boolean.FALSE);
            }
            return;
        }
        sReentry.set(Boolean.TRUE);
        try {
            tv.setVisibility(View.VISIBLE);
            tv.setText(text);
            tv.setSingleLine(true);
            tv.setMaxLines(1);
            tv.setHorizontallyScrolling(false);
            tv.setEllipsize(null);
            ViewGroup.LayoutParams lp = tv.getLayoutParams();
            if (lp != null && lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                tv.setLayoutParams(lp);
            }
            tv.requestLayout();
        } catch (Throwable ignore) {
        } finally {
            sReentry.set(Boolean.FALSE);
        }
        ensureAdaptiveWatcher(tv);
        applyAdaptiveMarquee(tv);
    }

    private void clearTitleWhenPureTimer(Object host, Object templateObj) {
        if (host == null || templateObj == null) return;
        try {
            Object hint = invokeNoArg(templateObj, "getHintInfo");
            if (hint == null) return;
            Object timerInfo = invokeNoArg(hint, "getTimerInfo");
            Object titleObj = invokeNoArg(hint, "getTitle");
            String title = titleObj instanceof String ? (String) titleObj : "";
            if (timerInfo == null) return;
            if (!TextUtils.isEmpty(title)) return;
            clearTitleField(host, "focusSmallTitle");
            clearTitleField(host, "focusTitle");
            clearTitleField(host, "focusTitleView");
        } catch (Throwable ignore) {
        }
    }

    private void clearTitleField(Object host, String fieldName) {
        Object tvObj = getFieldValue(host, fieldName);
        if (!(tvObj instanceof TextView)) return;
        TextView tv = (TextView) tvObj;
        sReentry.set(Boolean.TRUE);
        try {
            tv.setText("");
            tv.setSelected(false);
            tv.setEllipsize(null);
            tv.setHorizontallyScrolling(false);
        } catch (Throwable ignore) {
        } finally {
            sReentry.set(Boolean.FALSE);
        }
    }

    private void fixTimerTitleAndSubtitleLeakForTextButton(Object host) {
        if (host == null) return;
        try {
            Object chronoObj = getFieldValue(host, "chronometerHint");
            Object titleObj = getFieldValue(host, "focusSmallTitle");
            Object subObj = getFieldValue(host, "smallSubTitle");
            if (chronoObj instanceof View && titleObj instanceof TextView) {
                View chrono = (View) chronoObj;
                TextView titleTv = (TextView) titleObj;
                if (chrono.getVisibility() == View.VISIBLE) {
                    clearTextViewNow(titleTv);
                }
            }
            String subtitle = "";
            Object subStr = invokeNoArg(host, "getSubtitle");
            if (subStr instanceof String) subtitle = (String) subStr;
            if (subObj instanceof TextView && TextUtils.isEmpty(subtitle)) {
                clearTextViewNow((TextView) subObj);
            }
        } catch (Throwable ignore) {
        }
    }

    private void fixTimerTitleAndSubtitleLeakForDecoPort(Object host) {
        if (host == null) return;
        try {
            Object chronoObj = getFieldValue(host, "chronometerHintView");
            Object titleObj = getFieldValue(host, "focusSmallTitleView");
            Object subObj = getFieldValue(host, "focusSmallSubtitleView");
            if (chronoObj instanceof View && titleObj instanceof TextView) {
                View chrono = (View) chronoObj;
                TextView titleTv = (TextView) titleObj;
                if (chrono.getVisibility() == View.VISIBLE) {
                    clearTextViewNow(titleTv);
                }
            }
            String subtitle = "";
            Object subStr = invokeNoArg(host, "getSubtitle");
            if (subStr instanceof String) subtitle = (String) subStr;
            if (subObj instanceof TextView && TextUtils.isEmpty(subtitle)) {
                clearTextViewNow((TextView) subObj);
            }
        } catch (Throwable ignore) {
        }
    }

    private void clearTextViewNow(TextView tv) {
        if (tv == null) return;
        sReentry.set(Boolean.TRUE);
        try {
            tv.setText("");
            tv.setVisibility(View.GONE);
            tv.setSelected(false);
            tv.setEllipsize(null);
            tv.setHorizontallyScrolling(false);
        } catch (Throwable ignore) {
        } finally {
            sReentry.set(Boolean.FALSE);
        }
    }

    private void syncPureTimerToSubtitle(Object host, Object templateObj, String subFieldName, String chronoFieldName) {
        if (host == null || templateObj == null) return;
        try {
            Object hint = invokeNoArg(templateObj, "getHintInfo");
            if (hint == null) return;
            Object timerInfo = invokeNoArg(hint, "getTimerInfo");
            if (timerInfo == null) return;
            Object subObj = invokeNoArg(hint, "getSubTitle");
            String sub = subObj instanceof String ? ((String) subObj).trim() : "";
            if (!"{倒计时}".equals(sub) && !"{正计时}".equals(sub)) return;

            Object subTvObj = getFieldValue(host, subFieldName);
            Object chronoObj = getFieldValue(host, chronoFieldName);
            if (!(subTvObj instanceof TextView) || !(chronoObj instanceof TextView)) return;
            TextView subTv = (TextView) subTvObj;
            TextView chronoTv = (TextView) chronoObj;

            sReentry.set(Boolean.TRUE);
            try {
                subTv.setVisibility(View.VISIBLE);
                subTv.setText(chronoTv.getText());
                subTv.setSingleLine(true);
                subTv.setMaxLines(1);
                subTv.setEllipsize(null);
                subTv.setHorizontallyScrolling(false);
            } finally {
                sReentry.set(Boolean.FALSE);
            }

            if (chronoObj instanceof Chronometer) {
                Chronometer chronometer = (Chronometer) chronoObj;
                chronometer.setOnChronometerTickListener(c -> {
                    try {
                        sReentry.set(Boolean.TRUE);
                        subTv.setText(c.getText());
                    } catch (Throwable ignore) {
                    } finally {
                        sReentry.set(Boolean.FALSE);
                    }
                });
            }
            ensureAdaptiveWatcher(subTv);
            applyAdaptiveMarquee(subTv);
        } catch (Throwable ignore) {
        }
    }

    private String extractSubTitle(Object host, Object templateObj) {
        try {
            if (templateObj != null) {
                Object hint = invokeNoArg(templateObj, "getHintInfo");
                Object sub = hint == null ? null : invokeNoArg(hint, "getSubTitle");
                if (sub instanceof String && !TextUtils.isEmpty((String) sub)) {
                    return (String) sub;
                }
            }
        } catch (Throwable ignore) {
        }
        Object hostSub = invokeNoArg(host, "getSubtitle");
        return hostSub instanceof String ? (String) hostSub : "";
    }

    private void hookTextSetterLikeMethod(String className, String methodName, ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(className, false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!methodName.equals(m.getName())) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0) return;
                        for (int i = 0; i < param.args.length; i++) {
                            Object arg = param.args[i];
                            if (arg instanceof CharSequence) {
                                String expanded = expandFromAllCachedTexts(arg.toString());
                                if (!TextUtils.isEmpty(expanded) && !expanded.equals(arg.toString())) {
                                    param.args[i] = expanded;
                                }
                            }
                        }
                    }
                });
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private String expandFromAllCachedTexts(String src) {
        if (TextUtils.isEmpty(src) || sFullTextByKey.isEmpty()) return src;
        for (CachedTexts t : sFullTextByKey.values()) {
            if (t == null) continue;
            if (!TextUtils.isEmpty(t.left) && isLikelyFirstLimitTrim(src, t.left)) {
                return t.left;
            }
            if (!TextUtils.isEmpty(t.right) && isLikelyFirstLimitTrim(src, t.right)) {
                return t.right;
            }
        }
        return src;
    }

    private void hookIslandExpandedView(ClassLoader classLoader) {
        try {
            findAndHookMethod(FOCUS_CONTENT_CLASS, classLoader,
                    "setIslandExpandedView", View.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View root = (param.args != null && param.args.length > 0 && param.args[0] instanceof View)
                                    ? (View) param.args[0] : null;
                            tuneIslandViewTree(root);
                        }
                    });
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }

        try {
            findAndHookMethod(FOCUS_CONTENT_CLASS, classLoader,
                    "setIslandExpandedViewFake", View.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View root = (param.args != null && param.args.length > 0 && param.args[0] instanceof View)
                                    ? (View) param.args[0] : null;
                            tuneIslandViewTree(root);
                        }
                    });
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }

        hookFocusSetter(classLoader, "setFocusNotification");
        hookFocusSetter(classLoader, "setFocusNotificationDark");
        hookFocusSetter(classLoader, "setFocusNotificationModal");
        hookFocusSetter(classLoader, "setFocusNotificationDarkModal");
        hookFocusSetter(classLoader, "setTinyView");
        hookFocusSetter(classLoader, "setTinyViewDark");
        hookFocusSetter(classLoader, "setTinyViewModal");
        hookFocusSetter(classLoader, "setTinyViewDarkModal");
        hookFocusSetter(classLoader, "setTinyKeyguardView");
        hookFocusSetter(classLoader, "setTinyViewKeyguardDark");
        hookFocusViewMapSetter(classLoader);
        hookFocusKeySetter(classLoader);

        try {
            findAndHookMethod(DEVICE_LISTENER_CLASS, classLoader,
                    "handleDeviceNotification", Bundle.class, DEVICE_MODEL_CLASS,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 不再改写岛A/B文本，避免未展开态宽度异常挤压状态栏图标。
                        }
                    });
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void hookFocusSetter(ClassLoader classLoader, final String methodName) {
        try {
            findAndHookMethod(FOCUS_CONTENT_CLASS, classLoader,
                    methodName, View.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View root = (param.args != null && param.args.length > 0 && param.args[0] instanceof View)
                                    ? (View) param.args[0] : null;
                            if (!isActiveRoot(root)) return;
                        }
                    });
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void hookFocusViewMapSetter(ClassLoader classLoader) {
        try {
            findAndHookMethod(FOCUS_CONTENT_CLASS, classLoader,
                    "setFocusNotificationViewMap", Map.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length == 0) return;
                            Object arg = param.args[0];
                            if (!(arg instanceof Map)) return;
                            Map<?, ?> map = (Map<?, ?>) arg;
                            for (Map.Entry<?, ?> e : map.entrySet()) {
                                Object v = e.getValue();
                                if (v instanceof View) {
                                    View vv = (View) v;
                                    if (isActiveRoot(vv)) {
                                    }
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void hookFocusKeySetter(ClassLoader classLoader) {
        try {
            findAndHookMethod(FOCUS_CONTENT_CLASS, classLoader,
                    "setKey", String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object self = param.thisObject;
                            if (self == null) return;
                            String key = (param.args != null && param.args.length > 0 && param.args[0] instanceof String)
                                    ? (String) param.args[0] : "";
                            if (!TextUtils.isEmpty(key)) {
                                sFocusContentKeyMap.put(self, key);
                            }
                        }
                    });
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void installRuntimeIslandContentHook(Object listenerObj) {
        try {
            Object handler = getFieldValue(listenerObj, "deviceNotificationHandler");
            if (handler == null) return;
            Object controller = getFieldValue(handler, "controller");
            if (controller == null) return;

            Object content = invokeGetContent(controller);
            if (content == null) return;
            Class<?> cls = content.getClass();
            String clsName = cls.getName();
            if (!sHookedIslandContentClasses.add(clsName)) return;

            Class<?> cur = cls;
            while (cur != null) {
                for (Method m : cur.getDeclaredMethods()) {
                    String name = m.getName();
                    if (!("addDynamicIslandView".equals(name) || "updateDynamicIslandView".equals(name))) continue;
                    if (m.getParameterTypes().length != 2) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            enterIslandBind();
                            if (param.args != null && param.args.length > 0) {
                                tuneDynamicIslandData(param.args[0]);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args != null && param.args.length > 0) {
                                applyFullTextToRenderedViews(param.args[0]);
                            }
                            exitIslandBind();
                        }
                    });
                }
                cur = cur.getSuperclass();
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private Object invokeGetContent(Object controller) {
        for (Method m : controller.getClass().getMethods()) {
            if (m.getParameterTypes().length != 0) continue;
            Class<?> ret = m.getReturnType();
            if (ret == null || !DYNAMIC_ISLAND_CONTENT_IFACE.equals(ret.getName())) continue;
            try {
                m.setAccessible(true);
                return m.invoke(controller);
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    private void tuneDynamicIslandData(Object dataObj) {
        rewriteTickerDataWithFullText(dataObj);
    }

    private void cacheFullTextsFromModel(Object[] args) {
        if (args == null || args.length < 2) return;
        if (!(args[0] instanceof Bundle)) return;
        Object model = args[1];
        if (model == null) return;
        try {
            String key = ((Bundle) args[0]).getString("notifyId", "");
            if (TextUtils.isEmpty(key)) return;
            String left = readModelText(model, "getLeft");
            String right = readModelText(model, "getRight");
            if (TextUtils.isEmpty(left) && TextUtils.isEmpty(right)) return;
            CachedTexts texts = new CachedTexts(left, right);
            sFullTextByKey.put(key, texts);
        } catch (Throwable ignore) {
        }
    }

    private void cacheFullTextsFromIslandParam(Object[] args) {
        if (args == null || args.length < 1) return;
        if (!(args[0] instanceof Bundle)) return;
        try {
            Bundle b = (Bundle) args[0];
            String key = b.getString("notifyId", "");
            String json = b.getString("island_param", "");
            if (TextUtils.isEmpty(key) || TextUtils.isEmpty(json)) return;

            JSONObject root = new JSONObject(json);
            String left = "";
            String right = "";

            JSONObject l = root.optJSONObject("left");
            JSONObject r = root.optJSONObject("right");
            if (l != null) {
                JSONObject tp = l.optJSONObject("textParams");
                if (tp == null) tp = l.optJSONObject("text_params");
                if (tp != null) left = tp.optString("text", "");
            }
            if (r != null) {
                JSONObject tp = r.optJSONObject("textParams");
                if (tp == null) tp = r.optJSONObject("text_params");
                if (tp != null) right = tp.optString("text", "");
            }

            if (TextUtils.isEmpty(left) && TextUtils.isEmpty(right)) return;
            CachedTexts texts = new CachedTexts(left, right);
            sFullTextByKey.put(key, texts);
        } catch (Throwable ignore) {
        }
    }

    private String readModelText(Object model, String sideMethod) {
        try {
            Object side = invokeNoArg(model, sideMethod);
            if (side == null) return "";
            Object textParams = invokeNoArg(side, "getTextParams");
            if (textParams == null) return "";
            Object text = invokeNoArg(textParams, "getText");
            return text instanceof String ? (String) text : "";
        } catch (Throwable ignore) {
            return "";
        }
    }

    private void rewriteTickerDataWithFullText(Object dataObj) {
        try {
            Object keyObj = invokeNoArg(dataObj, "getKey");
            if (!(keyObj instanceof String)) return;
            String key = (String) keyObj;
            if (TextUtils.isEmpty(key)) return;
            CachedTexts cached = pickCachedTexts(key);
            if (cached == null) return;

            Object tickerObj = invokeNoArg(dataObj, "getTickerData");
            if (!(tickerObj instanceof String)) return;
            String tickerData = (String) tickerObj;
            if (TextUtils.isEmpty(tickerData)) return;

            JSONObject root = new JSONObject(tickerData);
            JSONObject big = root.optJSONObject("bigIslandArea");
            if (big == null) return;

            if (!TextUtils.isEmpty(cached.left)) {
                JSONObject left = big.optJSONObject("imageTextInfoLeft");
                if (left != null) {
                    JSONObject textInfo = left.optJSONObject("textInfo");
                    if (textInfo != null) textInfo.put("title", cached.left);
                }
            }
            if (!TextUtils.isEmpty(cached.right)) {
                JSONObject right = big.optJSONObject("imageTextInfoRight");
                if (right != null) {
                    JSONObject textInfo = right.optJSONObject("textInfo");
                    if (textInfo != null) textInfo.put("title", cached.right);
                }
            }

            String newTicker = root.toString();
            if (!tickerData.equals(newTicker)) {
                invokeOneArg(dataObj, "setTickerData", String.class, newTicker);
            }
        } catch (Throwable ignore) {
        }
    }

    private void applyFullTextToRenderedViews(Object dataObj) {
        try {
            Object keyObj = invokeNoArg(dataObj, "getKey");
            String key = keyObj instanceof String ? (String) keyObj : "";
            CachedTexts cached = pickCachedTexts(key);
            if (cached == null) return;

            View real = asView(invokeNoArg(dataObj, "getView"));
            View fake = asView(invokeNoArg(dataObj, "getFakeView"));
            applyFullTextToTree(real, cached);
            applyFullTextToTree(fake, cached);
        } catch (Throwable ignore) {
        }
    }

    private CachedTexts pickCachedTexts(String key) {
        if (TextUtils.isEmpty(key)) return null;
        return sFullTextByKey.get(key);
    }

    private void applyFullTextToTree(View root, CachedTexts cached) {
        if (root == null || cached == null) return;
        TextView left = findTextViewByIdName(root, "left_text");
        TextView right = findTextViewByIdName(root, "right_text");
        boolean hit = false;
        if (left != null && !TextUtils.isEmpty(cached.left)) {
            left.setText(cached.left);
            applyNoEllipsize(left);
            hit = true;
        }
        if (right != null && !TextUtils.isEmpty(cached.right)) {
            right.setText(cached.right);
            applyNoEllipsize(right);
            hit = true;
        }
        java.util.ArrayList<TextView> all = new java.util.ArrayList<>();
        collectTextViews(root, all);
        for (TextView tv : all) {
            String cur = String.valueOf(tv.getText());
            if (!TextUtils.isEmpty(cached.left) && isLikelyFirstLimitTrim(cur, cached.left)) {
                tv.setText(cached.left);
                applyNoEllipsize(tv);
                hit = true;
                continue;
            }
            if (!TextUtils.isEmpty(cached.right) && isLikelyFirstLimitTrim(cur, cached.right)) {
                tv.setText(cached.right);
                applyNoEllipsize(tv);
                hit = true;
            }
        }
        if (!hit && !all.isEmpty()) {
            if (!TextUtils.isEmpty(cached.left)) {
                all.get(0).setText(cached.left);
                applyNoEllipsize(all.get(0));
                hit = true;
            }
            if (all.size() > 1 && !TextUtils.isEmpty(cached.right)) {
                all.get(1).setText(cached.right);
                applyNoEllipsize(all.get(1));
                hit = true;
            }
        }
    }

    private boolean isLikelyFirstLimitTrim(String current, String full) {
        if (TextUtils.isEmpty(current) || TextUtils.isEmpty(full)) return false;
        String cur = current.trim();
        String target = full.trim();
        if (target.equals(cur)) return false;
        String normalized = cur.replace("...", "").replace("\u2026", "").trim();
        if (!TextUtils.isEmpty(normalized)) {
            if (target.startsWith(normalized)) return true;
            if (normalized.length() >= 4) {
                String head = normalized.substring(0, Math.min(4, normalized.length()));
                return target.startsWith(head);
            }
            return false;
        }
        String curNoDots = cur.replace("…", "").replace("...", "");
        if (TextUtils.isEmpty(curNoDots)) return false;
        if (target.startsWith(curNoDots)) return true;
        if (curNoDots.length() >= 4) {
            String head = curNoDots.substring(0, Math.min(4, curNoDots.length()));
            return target.startsWith(head);
        }
        return false;
    }

    private void applyCachedTextToGenericRoot(Object focusContentObj, View root) {
        if (root == null) return;
        if (focusContentObj == null) return;
        String key = sFocusContentKeyMap.get(focusContentObj);
        if (TextUtils.isEmpty(key)) return;
        CachedTexts cached = sFullTextByKey.get(key);
        if (cached == null) return;
        applyFullTextToTree(root, cached);
    }

    private void collectTextViews(View root, java.util.List<TextView> out) {
        if (root == null) return;
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            if (tv.getWidth() > 0 && tv.getWindowToken() != null) {
                out.add(tv);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectTextViews(vg.getChildAt(i), out);
            }
        }
    }

    private static boolean isActiveRoot(View root) {
        if (root == null) return false;
        if (root.getWindowToken() == null) return false;
        if (root.getVisibility() != View.VISIBLE) return false;
        if (root.getAlpha() <= 0f) return false;
        return root.getWidth() > 0 || root.getHeight() > 0;
    }


    private TextView findTextViewByIdName(View root, String idName) {
        if (root == null) return null;
        if (root instanceof TextView) {
            int id = root.getId();
            if (id != View.NO_ID && idName.equals(safeIdName(root, id))) {
                return (TextView) root;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                TextView t = findTextViewByIdName(vg.getChildAt(i), idName);
                if (t != null) return t;
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static void invokeOneArg(Object target, String methodName, Class<?> argType, Object arg) {
        if (target == null) return;
        try {
            Method m = target.getClass().getMethod(methodName, argType);
            m.setAccessible(true);
            m.invoke(target, arg);
        } catch (Throwable ignore) {
        }
    }

    private static boolean isReentry() {
        return Boolean.TRUE.equals(sReentry.get());
    }

    private static void applyNoEllipsize(TextView tv) {
        sReentry.set(Boolean.TRUE);
        try {
            tv.setSingleLine(true);
            tv.setMaxLines(1);
            tv.setHorizontallyScrolling(false);
            tv.setEllipsize(null);
            tv.setSelected(false);
            tv.setFocusable(false);
            tv.setFocusableInTouchMode(false);
            tv.requestLayout();
        } finally {
            sReentry.set(Boolean.FALSE);
        }
        ensureAdaptiveWatcher(tv);
        applyAdaptiveMarquee(tv);
    }

    private static void tuneIslandViewTree(View root) {
        if (root == null) return;
        if (root instanceof TextView) {
            tuneIslandText((TextView) root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                tuneIslandViewTree(vg.getChildAt(i));
            }
        }
    }

    private static void tuneIslandText(TextView tv) {
        try {
            tv.setSingleLine(true);
            tv.setMaxLines(1);
            tv.setHorizontallyScrolling(false);
            tv.setEllipsize(null);
            tv.setSelected(false);
            tv.setFocusable(false);
            tv.setFocusableInTouchMode(false);
            tv.setMaxWidth(Integer.MAX_VALUE / 4);
            tv.requestLayout();
            ensureAdaptiveWatcher(tv);
            applyAdaptiveMarquee(tv);
        } catch (Throwable ignore) {
        }
    }

    private static void ensureAdaptiveWatcher(final TextView tv) {
        if (tv == null) return;
        if (sAdaptiveWatchers.containsKey(tv)) return;
        sAdaptiveWatchers.put(tv, Boolean.TRUE);
        tv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                applyAdaptiveMarquee(tv);
            }
        });
    }

    private static void applyAdaptiveMarquee(final TextView tv) {
        if (tv == null) return;
        Runnable task = new Runnable() {
            @Override
            public void run() {
                if (tv.getLayout() == null) return;
                CharSequence cs = tv.getText();
                if (cs == null) return;
                float textWidth = tv.getPaint().measureText(cs.toString());
                int available = tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
                if (available <= 0) return;
                boolean needMarquee = textWidth > (available + 1f);

                sReentry.set(Boolean.TRUE);
                try {
                    tv.setHorizontallyScrolling(needMarquee);
                    tv.setEllipsize(needMarquee ? TextUtils.TruncateAt.MARQUEE : null);
                    tv.setSelected(needMarquee);
                    tv.setFocusable(needMarquee);
                    tv.setFocusableInTouchMode(needMarquee);
                    if (needMarquee) tv.setMarqueeRepeatLimit(-1);
                } finally {
                    sReentry.set(Boolean.FALSE);
                }
            }
        };
        tv.post(task);
    }

    private static void enterIslandBind() {
        Integer depth = sIslandBindDepth.get();
        if (depth == null) depth = 0;
        sIslandBindDepth.set(depth + 1);
    }

    private static void exitIslandBind() {
        Integer depth = sIslandBindDepth.get();
        if (depth == null || depth <= 1) {
            sIslandBindDepth.set(0);
            return;
        }
        sIslandBindDepth.set(depth - 1);
    }

    private static boolean isInIslandBind() {
        Integer depth = sIslandBindDepth.get();
        return depth != null && depth > 0;
    }

    private static String safeIdName(View view, int id) {
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static View asView(Object obj) {
        return obj instanceof View ? (View) obj : null;
    }

    private static Object getFieldValue(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static Object findFieldByTypeName(Object target, String typeNamePart) {
        if (target == null || TextUtils.isEmpty(typeNamePart)) return null;
        Class<?> cur = target.getClass();
        while (cur != null) {
            try {
                Field[] fields = cur.getDeclaredFields();
                for (Field f : fields) {
                    if (f == null) continue;
                    Class<?> ft = f.getType();
                    if (ft == null) continue;
                    String tn = ft.getName();
                    if (TextUtils.isEmpty(tn) || !tn.contains(typeNamePart)) continue;
                    f.setAccessible(true);
                    Object v = f.get(target);
                    if (v != null) return v;
                }
            } catch (Throwable ignore) {
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> cur = cls;
        while (cur != null) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    private static final class CachedTexts {
        final String left;
        final String right;

        CachedTexts(String left, String right) {
            this.left = left == null ? "" : left;
            this.right = right == null ? "" : right;
        }
    }
}
