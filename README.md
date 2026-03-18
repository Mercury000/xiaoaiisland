# 课程表超级岛

> LSPosed 模块，将小爱同学的**课程表提醒通知**升级为小米**超级岛**（Dynamic Island）形态，支持倒计时/正计时/下课三阶段动态内容，以及完全自定义的显示模板。

---

## 功能

| 功能 | 说明 |
|------|------|
| 超级岛注入 | 拦截 `com.miui.voiceassist` 发出的课程提醒通知，注入 `miui.focus.param` 参数 |
| 三阶段状态 | **课前**（倒计时）→ **上课中**（正计时）→ **下课后**（正计时）自动切换 |
| 自定义模板 | 每阶段的岛A（左）、岛B（右）、息屏文字均可独立配置 |
| 点击跳转 | 点击超级岛可跳转到小爱同学课表页 |
| 上课静音 | 超级岛内嵌"上课静音 / 解除静音"快捷按钮 |
| 隐藏图标 | 可在管理界面一键隐藏桌面图标，LSPosed 设置入口保留 |
| 可靠调度 | 使用 `AlarmManager.setExactAndAllowWhileIdle` 精确唤醒，避免 Doze 模式下状态丢失 |

---

## 环境要求

- **设备**：小米 / Redmi / POCO，运行 HyperOS / MIUI 14+（Android 12+）
- **框架**：[LSPosed](https://github.com/LSPosed/LSPosed)（Zygisk 版）
- **作用域**：超级小爱（`com.miui.voiceassist`）

---

## 安装

1. 在 [Releases](../../releases) 下载最新 APK 并安装
2. 打开 LSPosed 管理器 → 模块 → 找到**课程表超级岛** → 启用模块
3. 作用域勾选 **超级小爱（com.miui.voiceassist）**
4. 重启 `com.miui.voiceassist` 进程（或重启手机）
5. 打开模块主界面，确认"模块已激活"状态

---

## 自定义显示模板

在模块主界面的"状态栏岛显示自定义"卡片中，可为三个阶段分别配置：

| 阶段 | 触发时机 | 岛A默认 | 岛B默认 | 息屏默认 |
|------|---------|---------|---------|---------|
| 课前 | 通知发出 → 上课前 | `{开始}上课` | `{教室}` | `{开始}上课 {教室}` |
| 上课中 | 上课时刻到达 | `{课名}` | `{教室}` | `上课中 {教室}` |
| 下课后 | 下课时刻到达 | `{结束}下课` | `{教室}` | `下课了 {教室}` |

**可用变量：**

| 变量 | 含义 |
|------|------|
| `{课名}` | 课程名称（如"高等数学"） |
| `{开始}` | 上课时间（如"08:00"） |
| `{结束}` | 下课时间（如"09:40"） |
| `{教室}` | 上课地点（如"教科A-101"） |

---

## 构建

### 依赖

项目通过 Maven Central 依赖 **libxposed API 101+**，无需手动下载或放置任何 jar 文件。`settings.gradle` 中已包含 `mavenCentral()`，Gradle 会自动拉取：

```groovy
// app/build.gradle（已配置好，无需手动操作）
compileOnly 'io.github.libxposed:api:101.0.0'
implementation 'io.github.libxposed:service:101.0.0'
```

### 构建命令

```bash
# Debug
./gradlew assembleDebug

# Release（自动递增 versionCode）
./gradlew assembleRelease
```

输出 APK：`app/build/outputs/apk/release/课程表超级岛_{version}.apk`

### 版本管理

版本号由 `app/build.gradle` 自动生成，格式为 `yyyymmddxx`（年月日+当日构建序号）：

- **VERSION_CODE**：自动生成，例如 `2026022501`
- **VERSION_NAME**：自动生成，例如 `2026022501_debug` 或 `2026022501_release`

每次执行 `assembleRelease` 或 `assembleDebug` 时，版本号会自动递增。

---

## 项目结构

```
app/src/main/
├── java/com/xiaoai/islandnotify/
│   ├── MainHook.java        # LSPosed Hook 核心：拦截通知、构建岛 JSON、调度状态切换
│   ├── MainActivity.java    # 模块主界面：激活状态、自定义模板、隐藏图标、测试通知
│   └── MuteReceiver.java    # 上课静音 / 解除静音广播接收器
├── res/
│   ├── layout/
│   │   └── activity_main.xml          # 主界面布局
│   ├── values/
│   │   └── strings.xml                # 字符串资源
│   └── xml/
│       └── file_paths.xml             # FileProvider 配置
└── AndroidManifest.xml
```

---

## 常见问题

**Q：超级岛没有出现？**
- 确认 LSPosed 作用域已勾选 `com.miui.voiceassist`
- 确认模块主界面显示"模块已激活"
- 检查课程提醒通知的 channelId 是否包含 `COURSE_SCHEDULER_REMINDER`（logcat 过滤 `IslandNotifyHook`）

**Q：自定义模板不生效？**
- 保存后需要等下一条真实通知触发，已发出的通知不会更新
- 首次安装后请进入主界面点一次"保存"，确保配置广播到 voiceassist 进程

**Q：状态切换不准时？**
- 超级岛的状态通过 `AlarmManager` 精确调度。如果关闭了"允许精确闹钟"权限，可能有几分钟延迟

**Q：如何隐藏桌面图标？**
- 在模块主界面点击"隐藏桌面图标"按钮即可
- 隐藏后仍可通过 LSPosed 管理器或系统设置的应用管理访问模块

---

## License

MIT License.
