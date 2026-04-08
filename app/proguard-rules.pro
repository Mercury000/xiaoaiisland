# Xposed 模块保留规则 - 防止混淆破坏 Hook 入口
-keep class com.xiaoai.islandnotify.** { *; }
-keepclassmembers class com.xiaoai.islandnotify.** { *; }

# 保留 modern Xposed API 与兼容桥
-keep class io.github.libxposed.** { *; }
-keep class com.xiaoai.islandnotify.modernhook.** { *; }
-keep class com.xiaoai.islandnotify.ModuleEntry { *; }
-adaptresourcefilecontents META-INF/xposed/java_init.list
