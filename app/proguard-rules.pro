# Xposed 模块保留规则 - 防止混淆破坏 Hook 入口
-keep class com.xiaoai.islandnotify.** { *; }
-keepclassmembers class com.xiaoai.islandnotify.** { *; }

# 保留 libxposed API 101+ 入口
-keep class io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.service.** { *; }
