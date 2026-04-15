package com.xiaoai.islandnotify;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Bundle;

import com.xzakota.hyper.notification.common.model.TimerInfo;
import com.xzakota.hyper.notification.focus.FocusNotification;
import com.xzakota.hyper.notification.focus.model.ActionInfo;
import com.xzakota.hyper.notification.focus.model.BaseInfo;
import com.xzakota.hyper.notification.focus.model.HintInfo;
import com.xzakota.hyper.notification.focus.model.PicInfo;
import com.xzakota.hyper.notification.island.model.BigIslandArea;
import com.xzakota.hyper.notification.island.model.ImageTextInfo;
import com.xzakota.hyper.notification.island.model.SameWidthDigitInfo;
import com.xzakota.hyper.notification.island.model.ShareData;
import com.xzakota.hyper.notification.island.model.SmallIslandArea;
import com.xzakota.hyper.notification.island.model.TextInfo;
import com.xzakota.hyper.notification.island.template.IslandTemplate;

import java.util.Calendar;
import java.util.Locale;

final class IslandContentBuilder {

    private IslandContentBuilder() {}

    private static final int STATE_COUNTDOWN = 0;
    private static final int STATE_ELAPSED = 1;
    private static final int STATE_FINISHED = 2;

    private static final String VAR_COURSE_NAME = "{\u8bfe\u540d}";
    private static final String VAR_START_TIME = "{\u5f00\u59cb}";
    private static final String VAR_END_TIME = "{\u7ed3\u675f}";
    private static final String VAR_CLASSROOM = "{\u6559\u5ba4}";
    private static final String VAR_SECTION = "{\u8282\u6b21}";
    private static final String VAR_TEACHER = "{\u6559\u5e08}";
    private static final String VAR_COUNTDOWN = "{\u5012\u8ba1\u65f6}";
    private static final String VAR_ELAPSED = "{\u6b63\u8ba1\u65f6}";
    private static final String EXTRA_OWNER_KEY = "xiaoai.islandnotify.owner";
    private static final String EXTRA_OWNER_VALUE = "com.xiaoai.islandnotify";

    private static final String UNSUPPORTED_ELAPSED_IN_PRE = "\u4e0a\u8bfe\u524d\u4e0d\u652f\u6301\u6b63\u8ba1\u65f6";
    private static final String UNSUPPORTED_COUNTDOWN_IN_POST = "\u4e0b\u8bfe\u540e\u4e0d\u652f\u6301\u5012\u8ba1\u65f6";
    private static final String FALLBACK_HINT_SUBCONTENT = "\u5730\u70b9";

    static final class CourseSnapshot {
        final String courseName;
        final String startTime;
        final String endTime;
        final String classroom;
        final String sectionRange;
        final String teacher;

        CourseSnapshot(String courseName, String startTime, String endTime,
                       String classroom, String sectionRange, String teacher) {
            this.courseName = safeStr(courseName);
            this.startTime = safeStr(startTime);
            this.endTime = safeStr(endTime);
            this.classroom = safeStr(classroom);
            this.sectionRange = safeStr(sectionRange);
            this.teacher = safeStr(teacher);
        }
    }

    static final class BuildOptions {
        final int islandButtonMode;
        final String manualMuteAction;
        final String manualUnmuteAction;
        final String manualSkipAction;
        final String targetPackage;
        final String picKeyShare;
        final Context context;
        final Icon sourceSmallIcon;
        final int notificationId;
        final String notificationTag;
        final int automationAlarmId;

        BuildOptions(int islandButtonMode, String manualMuteAction, String manualUnmuteAction,
                     String manualSkipAction,
                     String targetPackage, String picKeyShare,
                     Context context, Icon sourceSmallIcon,
                     int notificationId, String notificationTag,
                     int automationAlarmId) {
            this.islandButtonMode = islandButtonMode;
            this.manualMuteAction = safeStr(manualMuteAction);
            this.manualUnmuteAction = safeStr(manualUnmuteAction);
            this.manualSkipAction = safeStr(manualSkipAction);
            this.targetPackage = safeStr(targetPackage);
            this.picKeyShare = safeStr(picKeyShare);
            this.context = context;
            this.sourceSmallIcon = sourceSmallIcon;
            this.notificationId = notificationId;
            this.notificationTag = safeStr(notificationTag);
            this.automationAlarmId = automationAlarmId;
        }
    }

    static Bundle build(CourseSnapshot info, int state, SharedPreferences prefs, BuildOptions options) {
        if (info == null || options == null) return new Bundle();
        try {
            long startMs = computeClassStartMs(info.startTime);
            long endMs = computeClassStartMs(info.endTime);
            long now = System.currentTimeMillis();
            boolean isFinished = state == STATE_FINISHED;
            boolean isActive = state != STATE_COUNTDOWN;
            boolean showSkipClass = options.islandButtonMode == 3;

            boolean showMute = options.islandButtonMode == 0 || options.islandButtonMode == 2;
            boolean showDnd = options.islandButtonMode == 1 || options.islandButtonMode == 2;

            String muteAction = showSkipClass
                    ? options.manualSkipAction
                    : (isFinished ? options.manualUnmuteAction : options.manualMuteAction);
            String actionTitle;
            if (showSkipClass) {
                actionTitle = "\u6211\u8981\u9003\u8bfe";
            } else if (showMute && showDnd) {
                actionTitle = isFinished ? "\u89e3\u9664\u9759\u9ed8" : "\u4e0a\u8bfe\u9759\u9ed8";
            } else if (showDnd) {
                actionTitle = isFinished ? "\u89e3\u9664\u52ff\u6270" : "\u4e0a\u8bfe\u52ff\u6270";
            } else {
                actionTitle = isFinished ? "\u89e3\u9664\u9759\u97f3" : "\u4e0a\u8bfe\u9759\u97f3";
            }

            long timerMs;
            int timerType;
            String hintContent;
            if (isFinished) {
                timerMs = endMs;
                timerType = 1;
                hintContent = "\u5df2\u7ecf\u4e0b\u8bfe";
            } else if (isActive) {
                if (endMs > 0) {
                    timerMs = endMs;
                    timerType = (endMs > now) ? -1 : 1;
                    hintContent = "\u8ddd\u79bb\u4e0b\u8bfe";
                } else {
                    timerMs = startMs;
                    timerType = 1;
                    hintContent = "\u5df2\u7ecf\u4e0a\u8bfe";
                }
            } else {
                timerMs = startMs;
                timerType = (startMs > now) ? -1 : 1;
                hintContent = (startMs > now) ? "\u5373\u5c06\u4e0a\u8bfe" : "\u5df2\u7ecf\u4e0a\u8bfe";
            }

            int stageIndex = stageIndexByState(state);
            String stageSuffix = ConfigDefaults.stageSuffix(stageIndex);
            final boolean showIconA = PrefsAccess.readConfigBool(prefs, "icon_a", true);
            final boolean leftHighlightEnabled = true;
            final boolean rightHighlightEnabled = true;
            final boolean legacyOutEffectEnabled = PrefsAccess.readConfigBool(
                    prefs, "out_effect_enabled", true);
            final boolean legacyOutEffectExists = prefs.contains("out_effect_enabled");
            final boolean statusEffectDefault = legacyOutEffectExists ? legacyOutEffectEnabled : false;
            final boolean expandEffectDefault = legacyOutEffectExists ? legacyOutEffectEnabled : true;
            final boolean outEffectExpandEnabled = PrefsAccess.readConfigBool(
                    prefs, "out_effect_expand_enabled", expandEffectDefault);
            final boolean outEffectStatusEnabled = PrefsAccess.readConfigBool(
                    prefs, "out_effect_status_enabled", statusEffectDefault);

            String aFallback = resolveTemplate(
                    ConfigDefaults.stagedTemplateDefault("tpl_a", stageSuffix, ""),
                    info, info.courseName);
            String aTitle = applyTimerVars(
                    applyExtraVars(resolveTemplate(
                            PrefsAccess.readStagedTemplate(prefs, "tpl_a", stageSuffix, ""), info, aFallback), info),
                    state, startMs, endMs, now);
            String bFallback = resolveTemplate(
                    ConfigDefaults.stagedTemplateDefault("tpl_b", stageSuffix, ""),
                    info, info.classroom.isEmpty() ? "\u2014" : info.classroom);
            String bTemplateRaw = applyExtraVars(resolveTemplate(
                    PrefsAccess.readStagedTemplate(prefs, "tpl_b", stageSuffix, ""), info, bFallback), info);
            IslandBSameWidthSpec bSameWidthSpec = buildIslandBSameWidthSpec(
                    bTemplateRaw, state, startMs, endMs, now);
            String bTitle = bSameWidthSpec != null && !bSameWidthSpec.supported
                    ? bSameWidthSpec.unsupportedText
                    : applyTimerVars(bTemplateRaw, state, startMs, endMs, now);

            String defaultTimeLine = info.startTime
                    + (info.endTime.isEmpty() ? "" : " | " + info.endTime);
            String baseTitle = applyTimerVars(applyExtraVars(resolveTemplate(
                    PrefsAccess.readStagedString(
                            prefs,
                            "tpl_base_title",
                            stageSuffix,
                            ConfigDefaults.expandedTemplateDefault(stageIndex, 0, info.courseName)),
                    info, info.courseName), info), state, startMs, endMs, now);
            String baseContent = applyTimerVars(applyExtraVars(resolveTemplate(
                    PrefsAccess.readStagedString(
                            prefs,
                            "tpl_base_content",
                            stageSuffix,
                            ConfigDefaults.expandedTemplateDefault(stageIndex, 5, defaultTimeLine)),
                    info, defaultTimeLine), info), state, startMs, endMs, now);
            String baseSubContent = applyTimerVars(applyExtraVars(resolveTemplate(
                    PrefsAccess.readStagedString(
                            prefs,
                            "tpl_base_subcontent",
                            stageSuffix,
                            ConfigDefaults.expandedTemplateDefault(stageIndex, 6, "")),
                    info, ""), info), state, startMs, endMs, now);

            String hintTitleText = resolveTemplate(
                    PrefsAccess.readStagedString(
                            prefs,
                            "tpl_hint_title",
                            stageSuffix,
                            ConfigDefaults.expandedTemplateDefault(stageIndex, 1, "")),
                    info, "");
            String rawHintTitleText = safeStr(hintTitleText).trim();
            boolean hintTitleWantsCountdown = hintTitleText.contains(VAR_COUNTDOWN);
            boolean hintTitleWantsElapsed = hintTitleText.contains(VAR_ELAPSED);
            boolean hintTitlePureCountdown = VAR_COUNTDOWN.equals(rawHintTitleText);
            boolean hintTitlePureElapsed = VAR_ELAPSED.equals(rawHintTitleText);
            boolean usePureTimerAsDynamicTitle;

            String hintContentText = applyTimerVars(applyExtraVars(resolveTemplate(
                    PrefsAccess.readStagedString(
                            prefs,
                            "tpl_hint_content",
                            stageSuffix,
                            ConfigDefaults.expandedTemplateDefault(stageIndex, 3, hintContent)),
                    info, hintContent), info), state, startMs, endMs, now);
            String hintSubContentText = resolveTemplate(
                    PrefsAccess.readStagedString(
                            prefs,
                            "tpl_hint_subcontent",
                            stageSuffix,
                            ConfigDefaults.expandedTemplateDefault(stageIndex, 4, FALLBACK_HINT_SUBCONTENT)),
                    info, FALLBACK_HINT_SUBCONTENT);
            String hintSubTitleText = resolveTemplate(
                    PrefsAccess.readStagedString(
                            prefs,
                            "tpl_hint_subtitle",
                            stageSuffix,
                            ConfigDefaults.expandedTemplateDefault(
                                    stageIndex, 2, info.classroom.isEmpty() ? "\u2014" : info.classroom)),
                    info, info.classroom.isEmpty() ? "\u2014" : info.classroom);
            String rawHintSubTitleText = safeStr(hintSubTitleText).trim();
            boolean hintSubTitleWantsCountdown = hintSubTitleText.contains(VAR_COUNTDOWN);
            boolean hintSubTitleWantsElapsed = hintSubTitleText.contains(VAR_ELAPSED);
            boolean hintSubTitlePureCountdown = VAR_COUNTDOWN.equals(rawHintSubTitleText);
            boolean hintSubTitlePureElapsed = VAR_ELAPSED.equals(rawHintSubTitleText);
            boolean usePureTimerAsDynamicSubTitle;
            hintSubContentText = applyTimerVars(hintSubContentText, state, startMs, endMs, now);
            if (state == STATE_ELAPSED) {
                if (hintTitleWantsCountdown || hintSubTitleWantsCountdown) {
                    timerMs = endMs;
                    timerType = (endMs > now) ? -1 : 1;
                } else if (hintTitleWantsElapsed || hintSubTitleWantsElapsed) {
                    timerMs = startMs;
                    timerType = 1;
                }
            }
            boolean unsupportedElapsedInPre = state == STATE_COUNTDOWN && hintTitleWantsElapsed;
            boolean unsupportedCountdownInPost = state == STATE_FINISHED && hintTitleWantsCountdown;
            boolean unsupportedSubElapsedInPre = state == STATE_COUNTDOWN && hintSubTitleWantsElapsed;
            boolean unsupportedSubCountdownInPost = state == STATE_FINISHED && hintSubTitleWantsCountdown;
            if (unsupportedElapsedInPre) {
                hintTitleText = hintTitleText.replace(VAR_ELAPSED, UNSUPPORTED_ELAPSED_IN_PRE);
            }
            if (unsupportedCountdownInPost) {
                hintTitleText = hintTitleText.replace(VAR_COUNTDOWN, UNSUPPORTED_COUNTDOWN_IN_POST);
            }
            if (unsupportedSubElapsedInPre) {
                hintSubTitleText = hintSubTitleText.replace(VAR_ELAPSED, UNSUPPORTED_ELAPSED_IN_PRE);
            }
            if (unsupportedSubCountdownInPost) {
                hintSubTitleText = hintSubTitleText.replace(VAR_COUNTDOWN, UNSUPPORTED_COUNTDOWN_IN_POST);
            }
            boolean unsupportedTimerVar = unsupportedElapsedInPre || unsupportedCountdownInPost;
            usePureTimerAsDynamicTitle = !unsupportedTimerVar && (
                    (state == STATE_COUNTDOWN && hintTitlePureCountdown)
                            || (state == STATE_ELAPSED && (hintTitlePureCountdown || hintTitlePureElapsed))
                            || (state == STATE_FINISHED && hintTitlePureElapsed)
            );
            boolean unsupportedSubTimerVar = unsupportedSubElapsedInPre || unsupportedSubCountdownInPost;
            usePureTimerAsDynamicSubTitle = !unsupportedSubTimerVar && (
                    (state == STATE_COUNTDOWN && hintSubTitlePureCountdown)
                            || (state == STATE_ELAPSED && (hintSubTitlePureCountdown || hintSubTitlePureElapsed))
                            || (state == STATE_FINISHED && hintSubTitlePureElapsed)
            );
            if (usePureTimerAsDynamicTitle) {
                hintTitleText = "";
            } else {
                hintTitleText = applyTimerVars(hintTitleText, state, startMs, endMs, now);
                hintTitleText = applyExtraVars(hintTitleText, info);
            }
            if (!usePureTimerAsDynamicSubTitle) {
                hintSubTitleText = applyTimerVars(hintSubTitleText, state, startMs, endMs, now);
            }
            hintSubContentText = applyExtraVars(hintSubContentText, info);
            hintSubTitleText = applyExtraVars(hintSubTitleText, info);
            final String finalHintTitleText = hintTitleText;
            final String finalHintContentText = hintContentText;
            final String finalHintSubContentText = hintSubContentText;
            final String finalHintSubTitleText = hintSubTitleText;
            final boolean finalUsePureTimerAsDynamicTitle = usePureTimerAsDynamicTitle;

            String timeRange = info.startTime + (info.endTime.isEmpty() ? "" : "-" + info.endTime);
            String phase = ConfigDefaults.stagePhase(stageIndex);
            int islandToVal = PrefsAccess.readConfigInt(
                    prefs, "to_island_val_" + phase, ConfigDefaults.TIMEOUT_VALUE);
            String islandToUnit = PrefsAccess.readConfigString(
                    prefs, "to_island_unit_" + phase, ConfigDefaults.TIMEOUT_UNIT);
            int islandTimeoutSec = -1;
            if (islandToVal > 0) {
                if ("s".equals(islandToUnit)) {
                    islandTimeoutSec = islandToVal;
                } else if ("h".equals(islandToUnit)) {
                    islandTimeoutSec = islandToVal * 3600;
                } else {
                    islandTimeoutSec = islandToVal * 60;
                }
            }
            final int finalIslandTimeoutSec = islandTimeoutSec;
            final long finalTimerMs = timerMs;
            final int finalTimerType = timerType;
            String tickerText = applyExtraVars(resolveTemplate(
                    PrefsAccess.readStagedTemplate(prefs, "tpl_ticker", stageSuffix, ""),
                    info, buildTickerText(info)), info);
            tickerText = applyTimerVars(tickerText, state, startMs, endMs, now);
            final String finalTickerText = tickerText;

            Bundle extras = FocusNotification.buildV3(template -> {
                template.setBusiness("course_schedule");
                template.setUpdatable(true);
                template.setTicker(finalTickerText);
                template.setAodTitle(finalTickerText);
                if (outEffectExpandEnabled) template.setOutEffectSrc("outer_glow");
                template.setEnableFloat(isActive);
                if (isActive) template.setIslandFirstFloat(true);

                BaseInfo baseInfo = new BaseInfo();
                baseInfo.setType(2);
                baseInfo.setTitle(baseTitle);
                if (isActive) baseInfo.setShowDivider(true);
                if (!baseContent.isEmpty()) baseInfo.setContent(baseContent);
                if (!baseSubContent.isEmpty()) baseInfo.setSubContent(baseSubContent);
                template.setBaseInfo(baseInfo);

                PicInfo picInfo = new PicInfo();
                picInfo.setType(1);
                template.setPicInfo(picInfo);

                ActionInfo actionInfo = new ActionInfo();
                actionInfo.setActionIntentType(2);
                Intent actionIntent = new Intent(muteAction);
                actionIntent.setPackage(options.targetPackage);
                actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                actionIntent.putExtra("course_name", info.courseName);
                actionIntent.putExtra("start_time", info.startTime);
                actionIntent.putExtra("end_time", info.endTime);
                actionIntent.putExtra("notif_id", options.notificationId);
                actionIntent.putExtra("notif_tag", options.notificationTag);
                actionIntent.putExtra("automation_alarm_id", options.automationAlarmId);
                actionInfo.setActionIntent(actionIntent.toUri(Intent.URI_INTENT_SCHEME));
                actionInfo.setActionTitle(actionTitle);

                HintInfo hintInfo = new HintInfo();
                hintInfo.setType(2);
                if (finalUsePureTimerAsDynamicTitle) {
                    hintInfo.setTitle(null);
                } else {
                    hintInfo.setTitle(finalHintTitleText);
                }
                hintInfo.setContent(finalHintContentText);
                hintInfo.setSubContent(finalHintSubContentText);
                hintInfo.setSubTitle(finalHintSubTitleText);
                hintInfo.setActionInfo(actionInfo);
                if (finalTimerMs > 0) {
                    TimerInfo timerInfo = new TimerInfo();
                    timerInfo.setTimerType(finalTimerType);
                    timerInfo.setTimerWhen(finalTimerMs);
                    timerInfo.setTimerSystemCurrent(now);
                    hintInfo.setTimerInfo(timerInfo);
                }
                template.setHintInfo(hintInfo);

                TextInfo aTextInfo = new TextInfo();
                aTextInfo.setTitle(aTitle);
                aTextInfo.setShowHighlightColor(leftHighlightEnabled);
                ImageTextInfo imageTextInfoLeft = new ImageTextInfo();
                imageTextInfoLeft.setType(1);
                imageTextInfoLeft.setTextInfo(aTextInfo);
                if (showIconA) {
                    com.xzakota.hyper.notification.island.model.PicInfo islandPicInfo =
                            new com.xzakota.hyper.notification.island.model.PicInfo();
                    islandPicInfo.setType(1);
                    imageTextInfoLeft.setPicInfo(islandPicInfo);
                }

                BigIslandArea bigIslandArea = new BigIslandArea();
                bigIslandArea.setImageTextInfoLeft(imageTextInfoLeft);
                if (bSameWidthSpec != null && bSameWidthSpec.supported) {
                    SameWidthDigitInfo sameWidthDigitInfo = new SameWidthDigitInfo();
                    TimerInfo islandTimerInfo = new TimerInfo();
                    islandTimerInfo.setTimerType(bSameWidthSpec.timerType);
                    islandTimerInfo.setTimerWhen(bSameWidthSpec.timerWhen);
                    islandTimerInfo.setTimerSystemCurrent(now);
                    sameWidthDigitInfo.setTimerInfo(islandTimerInfo);
                    if (!safeStr(bSameWidthSpec.suffix).isEmpty()) {
                        sameWidthDigitInfo.setContent(bSameWidthSpec.suffix);
                    }
                    sameWidthDigitInfo.setTurnAnim(true);
                    sameWidthDigitInfo.setShowHighlightColor(rightHighlightEnabled);
                    bigIslandArea.setSameWidthDigitInfo(sameWidthDigitInfo);
                } else {
                    TextInfo bTextInfo = new TextInfo();
                    bTextInfo.setTitle(bTitle);
                    bTextInfo.setShowHighlightColor(rightHighlightEnabled);
                    bigIslandArea.setTextInfo(bTextInfo);
                }

                SmallIslandArea smallIslandArea = new SmallIslandArea();

                ShareData shareData = new ShareData();
                shareData.setPic(options.picKeyShare);
                shareData.setTitle(info.courseName + "\n");
                shareData.setContent((info.classroom.isEmpty() ? "" : info.classroom) + "\n");
                shareData.setShareContent(info.courseName + "\n"
                        + (info.classroom.isEmpty() ? "" : info.classroom + "\n")
                        + (timeRange.isEmpty() ? "" : timeRange));

                IslandTemplate islandTemplate = new IslandTemplate();
                islandTemplate.setIslandProperty(1);
                islandTemplate.setBigIslandArea(bigIslandArea);
                islandTemplate.setSmallIslandArea(smallIslandArea);
                islandTemplate.setShareData(shareData);
                if (finalIslandTimeoutSec > 0) islandTemplate.setIslandTimeout(finalIslandTimeoutSec);
                template.setIsland(islandTemplate);
            });
            if (outEffectStatusEnabled) {
                extras.putString("miui.bigIsland.effect.src", "outer_glow");
                extras.putString("miui.effect.src", "outer_glow");
            } else {
                extras.remove("miui.bigIsland.effect.src");
                extras.remove("miui.effect.src");
            }
            extras.putString(EXTRA_OWNER_KEY, EXTRA_OWNER_VALUE);
            return extras;
        } catch (Throwable ignored) {
            return new Bundle();
        }
    }

    private static String buildTickerText(CourseSnapshot info) {
        return info.startTime.isEmpty() ? info.courseName : info.startTime + "\u4e0a\u8bfe";
    }

    private static String resolveTemplate(String tpl, CourseSnapshot info, String fallback) {
        if (tpl == null || tpl.isEmpty()) return fallback;
        return tpl
                .replace(VAR_COURSE_NAME, info.courseName)
                .replace(VAR_START_TIME, info.startTime)
                .replace(VAR_END_TIME, info.endTime)
                .replace(VAR_CLASSROOM, info.classroom)
                .replace(VAR_SECTION, info.sectionRange)
                .replace(VAR_TEACHER, info.teacher);
    }

    private static String applyExtraVars(String text, CourseSnapshot info) {
        if (text == null) return "";
        return text
                .replace(VAR_SECTION, info.sectionRange)
                .replace(VAR_TEACHER, info.teacher);
    }

    private static IslandBSameWidthSpec buildIslandBSameWidthSpec(
            String template, int state, long startMs, long endMs, long now) {
        if (template == null || template.isEmpty()) return null;
        int idxCountdown = template.indexOf(VAR_COUNTDOWN);
        int idxElapsed = template.indexOf(VAR_ELAPSED);
        if (idxCountdown < 0 && idxElapsed < 0) return null;

        final boolean useCountdown;
        final int idx;
        final String token;
        if (idxCountdown >= 0 && (idxElapsed < 0 || idxCountdown <= idxElapsed)) {
            useCountdown = true;
            idx = idxCountdown;
            token = VAR_COUNTDOWN;
        } else {
            useCountdown = false;
            idx = idxElapsed;
            token = VAR_ELAPSED;
        }

        String prefix = template.substring(0, Math.max(0, idx));
        if (!prefix.trim().isEmpty()) {
            return null;
        }
        String suffix = safeStr(template.substring(Math.min(template.length(), idx + token.length())));

        if (!useCountdown && state == STATE_COUNTDOWN) {
            return IslandBSameWidthSpec.unsupported(template.replace(VAR_ELAPSED, UNSUPPORTED_ELAPSED_IN_PRE));
        }
        if (useCountdown && state == STATE_FINISHED) {
            return IslandBSameWidthSpec.unsupported(template.replace(VAR_COUNTDOWN, UNSUPPORTED_COUNTDOWN_IN_POST));
        }

        int timerType;
        long timerWhen;
        if (useCountdown) {
            if (state == STATE_ELAPSED) {
                timerWhen = endMs;
                timerType = (endMs > now) ? -1 : 1;
            } else {
                timerWhen = startMs;
                timerType = (startMs > now) ? -1 : 1;
            }
        } else {
            timerWhen = startMs;
            timerType = 1;
        }
        if (timerWhen <= 0L) return null;
        return IslandBSameWidthSpec.supported(timerType, timerWhen, suffix);
    }

    private static String applyTimerVars(String text, int state, long startMs, long endMs, long now) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        if (state == STATE_COUNTDOWN) {
            text = text.replace(VAR_ELAPSED, UNSUPPORTED_ELAPSED_IN_PRE);
        } else if (state == STATE_FINISHED) {
            text = text.replace(VAR_COUNTDOWN, UNSUPPORTED_COUNTDOWN_IN_POST);
        }
        long countdownMs;
        long elapsedMs;
        if (state == STATE_COUNTDOWN) {
            countdownMs = Math.max(0L, startMs - now);
            elapsedMs = 0L;
        } else if (state == STATE_ELAPSED) {
            countdownMs = Math.max(0L, endMs - now);
            elapsedMs = Math.max(0L, now - startMs);
        } else {
            countdownMs = 0L;
            elapsedMs = Math.max(0L, now - endMs);
        }
        return text
                .replace(VAR_COUNTDOWN, formatTimerText(countdownMs))
                .replace(VAR_ELAPSED, formatTimerText(elapsedMs));
    }

    private static String formatTimerText(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0L) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.getDefault(), "%02d:%02d", m, s);
    }

    private static int stageIndexByState(int state) {
        if (state == STATE_ELAPSED) return ConfigDefaults.STAGE_ACTIVE;
        if (state == STATE_FINISHED) return ConfigDefaults.STAGE_POST;
        return ConfigDefaults.STAGE_PRE;
    }

    private static long computeClassStartMs(String startTime) {
        if (startTime == null || startTime.isEmpty()) return -1;
        try {
            String[] parts = startTime.split(":");
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, h);
            cal.set(Calendar.MINUTE, m);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }

    private static final class IslandBSameWidthSpec {
        final boolean supported;
        final int timerType;
        final long timerWhen;
        final String suffix;
        final String unsupportedText;

        private IslandBSameWidthSpec(boolean supported, int timerType, long timerWhen,
                                     String suffix, String unsupportedText) {
            this.supported = supported;
            this.timerType = timerType;
            this.timerWhen = timerWhen;
            this.suffix = suffix == null ? "" : suffix;
            this.unsupportedText = unsupportedText == null ? "" : unsupportedText;
        }

        static IslandBSameWidthSpec supported(int timerType, long timerWhen, String suffix) {
            return new IslandBSameWidthSpec(true, timerType, timerWhen, suffix, "");
        }

        static IslandBSameWidthSpec unsupported(String text) {
            return new IslandBSameWidthSpec(false, 0, 0L, "", text);
        }
    }
}
