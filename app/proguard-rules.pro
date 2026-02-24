# Xposed 模块保留规则 - 防止混淆破坏 Hook 入口
-keep class com.xiaoai.islandnotify.** { *; }
-keepclassmembers class com.xiaoai.islandnotify.** { *; }

# 保留 Xposed API
-keep class de.robv.android.xposed.** { *; }
