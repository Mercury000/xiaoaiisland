package com.xiaoai.islandnotify;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 更新检查器
 * 检查GitHub Releases页面的最新版本
 */
public class UpdateChecker {
    private static final String TAG = "UpdateChecker";
    private static final String PREFS_NAME = "update_check";
    private static final String KEY_IGNORED_VERSION = "ignored_version";

    // GitHub API配置
    private static final String GITHUB_API_URL = "https://api.github.com/repos/Mercury000/courseisland/releases/latest";
    private static final String GITHUB_RELEASES_URL = "https://github.com/Mercury000/courseisland/releases";

    // 网络容错配置
    private static final int MAX_RETRY_COUNT = 3;  // 最大重试次数
    private static final long RETRY_DELAY_MS = 1000;  // 重试间隔（毫秒）

    private final Context context;
    private final OkHttpClient client;
    private final Handler mainHandler;

    public UpdateChecker(Context context) {
        this.context = context.getApplicationContext();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)  // 连接超时：15秒
                .readTimeout(15, TimeUnit.SECONDS)     // 读取超时：15秒
                .writeTimeout(15, TimeUnit.SECONDS)    // 写入超时：15秒
                .retryOnConnectionFailure(true)        // 连接失败自动重试
                .build();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 检查更新
     * @param callback 回调接口
     * @param isManual 是否为手动检查
     */
    public void checkUpdate(UpdateCheckCallback callback, boolean isManual) {
        // 在后台线程执行
        new Thread(() -> {
            // 重试机制
            for (int retryCount = 0; retryCount <= MAX_RETRY_COUNT; retryCount++) {
                try {
                    // 执行API调用
                    Request request = new Request.Builder()
                            .url(GITHUB_API_URL)
                            .addHeader("Accept", "application/vnd.github.v3+json")
                            .addHeader("User-Agent", "CourseTableIsland-UpdateChecker")
                            .build();

                    Response response = client.newCall(request).execute();

                    if (!response.isSuccessful()) {
                        // 如果不是最后一次重试，等待后继续重试
                        if (retryCount < MAX_RETRY_COUNT) {
                            Log.w(TAG, "请求失败，准备第" + (retryCount + 1) + "次重试");
                            try {
                                Thread.sleep(RETRY_DELAY_MS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            continue;
                        }
                        mainHandler.post(() -> callback.onError("无法连接到GitHub"));
                        return;
                    }

                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);

                    // 解析版本信息
                    String latestVersionTag = json.getString("tag_name");
                    String latestVersion = parseVersionFromTag(latestVersionTag);
                    String releaseName = json.optString("name", "");
                    String releaseBody = json.optString("body", "");
                    String htmlUrl = json.optString("html_url", GITHUB_RELEASES_URL);

                    // 检查是否已忽略此版本
                    SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    String ignoredVersion = prefs.getString(KEY_IGNORED_VERSION, "");

                    // 如果是自动检查且已忽略此版本，则不弹窗
                    if (!isManual && ignoredVersion.equals(latestVersion)) {
                        mainHandler.post(() -> callback.onAlreadyLatest(getLocalVersion()));
                        return;
                    }

                    // 比较版本号
                    String localVersion = getLocalVersion();
                    int compareResult = compareVersions(localVersion, latestVersion);

                    if (compareResult < 0) {
                        // 有新版本
                        UpdateInfo updateInfo = new UpdateInfo();
                        updateInfo.localVersion = localVersion;
                        updateInfo.latestVersion = latestVersion;
                        updateInfo.releaseName = releaseName;
                        updateInfo.releaseBody = releaseBody;
                        updateInfo.htmlUrl = htmlUrl;
                        updateInfo.isManual = isManual;

                        mainHandler.post(() -> callback.onNewVersionAvailable(updateInfo));
                    } else {
                        // 已是最新版本
                        mainHandler.post(() -> callback.onAlreadyLatest(localVersion));
                    }
                    return; // 成功，退出重试循环

                } catch (Exception e) {
                    Log.e(TAG, "检查更新失败（第" + (retryCount + 1) + "次尝试）", e);
                    // 如果不是最后一次重试，等待后继续重试
                    if (retryCount < MAX_RETRY_COUNT) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                    // 所有重试都失败，返回错误
                    mainHandler.post(() -> callback.onError("无法连接到GitHub"));
                    return;
                }
            }
        }).start();
    }

    /**
     * 忽略此版本
     */
    public void ignoreVersion(String version) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_IGNORED_VERSION, version).apply();
    }

    /**
     * 清除忽略的版本
     */
    public void clearIgnoredVersion() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_IGNORED_VERSION).apply();
    }

    /**
     * 获取本地版本号
     */
    private String getLocalVersion() {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .versionName;
        } catch (Exception e) {
            Log.e(TAG, "获取本地版本号失败", e);
            return "0";
        }
    }

    /**
     * 从GitHub tag解析版本号
     * 例如：v_2026022501 -> 2026022501
     */
    private String parseVersionFromTag(String tag) {
        if (tag == null || tag.isEmpty()) {
            return "0";
        }

        // 移除前缀 v_ 或 v
        String version = tag;
        if (version.startsWith("v_")) {
            version = version.substring(2);
        } else if (version.startsWith("v")) {
            version = version.substring(1);
        }

        // 移除可能的后缀（如 -alpha, -beta 等）
        int dashIndex = version.indexOf('-');
        if (dashIndex > 0) {
            version = version.substring(0, dashIndex);
        }

        return version;
    }

    /**
     * 比较两个版本号
     * @param v1 本地版本号
     * @param v2 GitHub版本号
     * @return v1 < v2 返回负数，v1 == v2 返回0，v1 > v2 返回正数
     */
    private int compareVersions(String v1, String v2) {
        try {
            // 尝试解析为数字进行比较
            long num1 = Long.parseLong(v1);
            long num2 = Long.parseLong(v2);
            return Long.compare(num1, num2);
        } catch (NumberFormatException e) {
            // 如果解析失败，使用字符串比较
            return v1.compareTo(v2);
        }
    }

    /**
     * 更新检查回调接口
     */
    public interface UpdateCheckCallback {
        /**
         * 发现新版本
         */
        void onNewVersionAvailable(UpdateInfo updateInfo);

        /**
         * 已是最新版本
         */
        void onAlreadyLatest(String currentVersion);

        /**
         * 检查失败
         */
        void onError(String errorMessage);
    }

    /**
     * 更新信息
     */
    public static class UpdateInfo {
        public String localVersion;
        public String latestVersion;
        public String releaseName;
        public String releaseBody;
        public String htmlUrl;
        public boolean isManual;
    }
}
