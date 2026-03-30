package com.xiaoai.islandnotify;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import com.xiaoai.islandnotify.modernhook.XC_MethodHook;
import com.xiaoai.islandnotify.modernhook.XposedBridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.json.JSONObject;

import static com.xiaoai.islandnotify.modernhook.XposedHelpers.findAndHookMethod;

public class SystemUiHook {

    private static final String TAG = "IslandNotifySysUI";
    private static final String TARGET_PACKAGE = "com.android.systemui";

    private static final String DYNAMIC_ISLAND_CONTENT_IFACE =
            "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandContent";
    private static final String FOCUS_CONTENT_CLASS =
            "com.android.systemui.plugins.miui.notification.FocusNotificationContent";
    private static final String DEVICE_LISTENER_CLASS =
            "com.android.systemui.devicenotification.listener.DeviceNotificationListenerImpl";
    private static final String DEVICE_MODEL_CLASS =
            "com.android.systemui.devicenotification.bean.DeviceNotificationModel";

    private static final ThreadLocal<Boolean> sReentry = new ThreadLocal<>();
    private static final ThreadLocal<Integer> sIslandBindDepth = new ThreadLocal<>();
    private static final Set<String> sHookedIslandContentClasses = ConcurrentHashMap.newKeySet();
    private static final Set<String> sDumpedFocusMethods = ConcurrentHashMap.newKeySet();
    private static final Map<TextView, Boolean> sAdaptiveWatchers =
            java.util.Collections.synchronizedMap(new WeakHashMap<TextView, Boolean>());
    private static final Map<Object, String> sFocusContentKeyMap =
            java.util.Collections.synchronizedMap(new WeakHashMap<Object, String>());
    private static final ConcurrentMap<String, CachedTexts> sFullTextByKey = new ConcurrentHashMap<>();
    private static volatile boolean sLoggedInstalled = false;

    public void handleLoadPackage(String packageName, ClassLoader classLoader) {
        if (!TARGET_PACKAGE.equals(packageName)) return;
        hookIslandExpandedView(classLoader);
        hookTextViewSetEllipsize(classLoader);
        hookTextViewSetText(classLoader);
        hookTextViewOnAttached(classLoader);
        XposedBridge.log(TAG + ": hook installed for " + packageName);
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
            XposedBridge.log(TAG + ": hook FocusNotificationContent#setIslandExpandedView ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook setIslandExpandedView failed -> " + t.getMessage());
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
            XposedBridge.log(TAG + ": hook FocusNotificationContent#setIslandExpandedViewFake ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook setIslandExpandedViewFake failed -> " + t.getMessage());
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
                            cacheFullTextsFromModel(param.args);
                            cacheFullTextsFromIslandParam(param.args);
                            installRuntimeIslandContentHook(param.thisObject);
                            if (!sLoggedInstalled && param.args != null && param.args.length > 0
                                    && param.args[0] instanceof Bundle) {
                                String json = ((Bundle) param.args[0]).getString("island_param", "");
                                XposedBridge.log(TAG + ": island_param length=" + (json == null ? 0 : json.length()));
                                sLoggedInstalled = true;
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hook DeviceNotificationListenerImpl#handleDeviceNotification ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook handleDeviceNotification failed -> " + t.getMessage());
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
                            tuneIslandViewTree(root);
                            applyCachedTextToGenericRoot(param.thisObject, root);
                            dumpFocusTextViewsOnce(methodName, root);
                            XposedBridge.log(TAG + ": FocusNotificationContent#" + methodName + " hit");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + methodName + " failed -> " + t.getMessage());
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
                            int hit = 0;
                            for (Map.Entry<?, ?> e : map.entrySet()) {
                                Object v = e.getValue();
                                if (v instanceof View) {
                                    View vv = (View) v;
                                    if (isActiveRoot(vv)) {
                                        tuneIslandViewTree(vv);
                                        hit++;
                                    }
                                }
                            }
                            XposedBridge.log(TAG + ": FocusNotificationContent#setFocusNotificationViewMap hit, views=" + hit);
                        }
                    });
            XposedBridge.log(TAG + ": hook FocusNotificationContent#setFocusNotificationViewMap ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook setFocusNotificationViewMap failed -> " + t.getMessage());
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
            XposedBridge.log(TAG + ": hook FocusNotificationContent#setKey ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook setKey failed -> " + t.getMessage());
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

            int hookCount = 0;
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
                    hookCount++;
                }
                cur = cur.getSuperclass();
            }
            XposedBridge.log(TAG + ": runtime content class=" + clsName + ", hookCount=" + hookCount);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": installRuntimeIslandContentHook failed -> " + t.getMessage());
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
        View real = asView(invokeNoArg(dataObj, "getView"));
        View fake = asView(invokeNoArg(dataObj, "getFakeView"));
        tuneIslandViewTree(real);
        tuneIslandViewTree(fake);
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
                XposedBridge.log(TAG + ": tickerData rewritten from cached full text, key=" + key);
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
        if (hit) {
            XposedBridge.log(TAG + ": view text replaced from full cache");
        }
    }

    private boolean isLikelyFirstLimitTrim(String current, String full) {
        if (TextUtils.isEmpty(current) || TextUtils.isEmpty(full)) return false;
        String cur = current.trim();
        String target = full.trim();
        if (target.equals(cur)) return false;
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
        CachedTexts cached;
        if (focusContentObj != null) {
            String key = sFocusContentKeyMap.get(focusContentObj);
            if (TextUtils.isEmpty(key)) return;
            cached = sFullTextByKey.get(key);
        } else {
            return;
        }
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

    private void dumpFocusTextViewsOnce(String methodName, View root) {
        if (root == null) return;
        String key = methodName + "@" + root.getClass().getName();
        if (!sDumpedFocusMethods.add(key)) return;
        java.util.ArrayList<TextView> all = new java.util.ArrayList<>();
        collectTextViews(root, all);
        XposedBridge.log(TAG + ": dump " + key + " textViews=" + all.size());
        for (int i = 0; i < all.size() && i < 6; i++) {
            TextView tv = all.get(i);
            String idName = "";
            int id = tv.getId();
            if (id != View.NO_ID) idName = safeIdName(tv, id);
            String txt = String.valueOf(tv.getText());
            int len = txt == null ? 0 : txt.length();
            XposedBridge.log(TAG + ": tv[" + i + "] id=" + idName
                    + " maxW=" + tv.getMaxWidth()
                    + " width=" + tv.getWidth()
                    + " len=" + len
                    + " ellipsize=" + String.valueOf(tv.getEllipsize()));
            logAncestorLimits(tv, "tv[" + i + "]");
        }
    }

    private void logAncestorLimits(TextView tv, String tag) {
        if (tv == null) return;
        View cur = tv;
        int depth = 0;
        while (cur != null && depth < 8) {
            int id = cur.getId();
            String idName = id == View.NO_ID ? "" : safeIdName(cur, id);
            ViewGroup.LayoutParams lp = cur.getLayoutParams();
            int lpW = lp == null ? Integer.MIN_VALUE : lp.width;
            int lpH = lp == null ? Integer.MIN_VALUE : lp.height;
            float weight = Float.NaN;
            if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                weight = ((android.widget.LinearLayout.LayoutParams) lp).weight;
            }
            XposedBridge.log(TAG + ": " + tag + " ancestor[" + depth + "] class="
                    + cur.getClass().getName()
                    + " id=" + idName
                    + " width=" + cur.getWidth()
                    + " lpW=" + lpW
                    + " lpH=" + lpH
                    + " weight=" + weight);
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
            depth++;
        }
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

    private void hookTextViewSetEllipsize(ClassLoader classLoader) {
        try {
            findAndHookMethod("android.widget.TextView", classLoader,
                    "setEllipsize", TextUtils.TruncateAt.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isReentry()) return;
                            if (!(param.thisObject instanceof TextView)) return;
                            TextView tv = (TextView) param.thisObject;
                            if (!shouldTuneIslandTextView(tv)) return;
                            param.args[0] = null;
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (isReentry()) return;
                            if (!(param.thisObject instanceof TextView)) return;
                            TextView tv = (TextView) param.thisObject;
                            if (!shouldTuneIslandTextView(tv)) return;
                            applyNoEllipsize(tv);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook setEllipsize failed -> " + t.getMessage());
        }
    }

    private void hookTextViewSetText(ClassLoader classLoader) {
        try {
            findAndHookMethod("android.widget.TextView", classLoader,
                    "setText", CharSequence.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (isReentry()) return;
                            if (!(param.thisObject instanceof TextView)) return;
                            TextView tv = (TextView) param.thisObject;
                            if (!shouldTuneIslandTextView(tv)) return;
                            applyNoEllipsize(tv);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook setText(CharSequence) failed -> " + t.getMessage());
        }

        try {
            findAndHookMethod("android.widget.TextView", classLoader,
                    "setText", CharSequence.class, TextView.BufferType.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (isReentry()) return;
                            if (!(param.thisObject instanceof TextView)) return;
                            TextView tv = (TextView) param.thisObject;
                            if (!shouldTuneIslandTextView(tv)) return;
                            applyNoEllipsize(tv);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook setText(CharSequence,BufferType) failed -> " + t.getMessage());
        }
    }

    private void hookTextViewOnAttached(ClassLoader classLoader) {
        try {
            findAndHookMethod("android.widget.TextView", classLoader,
                    "onAttachedToWindow", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (isReentry()) return;
                            if (!(param.thisObject instanceof TextView)) return;
                            TextView tv = (TextView) param.thisObject;
                            if (!shouldTuneIslandTextView(tv)) return;
                            applyNoEllipsize(tv);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook onAttachedToWindow failed -> " + t.getMessage());
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

    private static boolean shouldTuneIslandTextView(TextView tv) {
        if (isInIslandBind()) return true;
        int id = tv.getId();
        if (id != View.NO_ID) {
            String idName = safeIdName(tv, id).toLowerCase();
            if (idName.contains("island")) return true;
            if (idName.contains("focus")) return true;
            if ("left_text".equals(idName) || "right_text".equals(idName)) return true;
        }
        return hasIslandAncestor(tv);
    }

    private static boolean hasIslandAncestor(View view) {
        View cur = view;
        while (cur != null) {
            String cls = cur.getClass().getName().toLowerCase();
            if (cls.contains("island") || cls.contains("focusnotification")) return true;
            int id = cur.getId();
            if (id != View.NO_ID) {
                String name = safeIdName(cur, id).toLowerCase();
                if (name.contains("island") || name.contains("focus")) return true;
            }
            ViewParent parent = cur.getParent();
            cur = (parent instanceof View) ? (View) parent : null;
        }
        return false;
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
