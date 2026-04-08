# 课程表超级岛

> LSPosed 模块，将小爱同学的**课程表提醒通知**升级为小米**超级岛**（Dynamic Island）形态，支持倒计时/正计时/下课三阶段动态内容，以及完全自定义的显示模板。

---

## 功能

| 功能 | 说明 |
|------|------|
| 超级岛注入 | 劫持 `com.miui.voiceassist` 发送课程提醒通知并注入 `miui.focus.param` 参数；支持自定义提醒时机 |
| 三阶段状态 | **课前**（倒计时）→ **上课中**（正计时）→ **下课后**（正计时）自动切换 |
| 自定义模板 | 每阶段的岛A（左）、岛B（右）、息屏文字、展开态岛均可独立配置 |
| 点击跳转 | 点击超级岛可跳转到小爱同学课表页 |
| 上课静音 | 超级岛内嵌"上课静音 / 解除静音"快捷按钮；同时支持自动化 |
| 自动叫醒 | 根据上午/下午首节课程的节次设定指定时间闹钟 |
| 假期/调休  | 支持从网络获取/自行添加假期或调休，假期日不提醒，调休日按指定周次星期 |

---

## 环境要求

- **设备**：运行HyperOS3的小米/红米手机
- **框架**：[LSPosed](https://github.com/LSPosed/LSPosed)（API101）

---

## 安装

1. 在 [Releases](https://github.com/Xposed-Modules-Repo/com.xiaoai.islandnotify/releases)下载最新 APK 并安装
2. 打开 LSPosed 管理器 → 模块 → 找到**课程表超级岛** → 启用模块
3. 作用域勾选推荐作用域
4. 重启所有作用域
5. 打开模块主界面，确认"模块已激活"状态

---

## 自定义显示

**可用变量：**

| 变量       | 含义                                               |
| ---------- | -------------------------------------------------- |
| `{课名}`   | 课程名称（如"高等数学"）                           |
| `{开始}`   | 上课时间（如"08:00"）                              |
| `{结束}`   | 下课时间（如"09:40"）                              |
| `{教室}`   | 上课地点（如"教科A-101"）                          |
| `{节次}`   | 上课节次（如“1-2”）                                |
| `{教师}`   | 教师姓名（如“张三”）                               |
| `{倒计时}` | 按三阶段分别为距离上课倒计时/距离下课倒计时/不支持 |
| `{正计时}` | 按三阶段分别为不支持/已经上课正计时/已经下课正计时 |

**注意：同一阶段仅可存在一个计时类型**

### 状态栏岛显示模板

在模块主界面的"状态栏岛显示自定义"卡片中，可为三个阶段分别配置：

| 阶段 | 触发时机 | 岛A默认 | 岛B默认 | 息屏默认 |
|------|---------|---------|---------|---------|
| 上课前 | 通知发出 → 上课前 | `{教室}` | `{开始}上课` | `{教室}｜{开始}上课` |
| 上课中 | 上课时刻到达 | `{课名}` | `{结束}下课` | `{课名}｜{结束}下课` |
| 下课后 | 下课时刻到达 | `{课名}` | `已经下课` | `{课名}｜已经下课` |

### 展开态岛显示模板

在模块主界面的"状态栏岛显示自定义"卡片中，可为三个阶段分别配置：

| 阶段   | 触发时机          | 主要标题 | 次要文本1         | 次要文本2 | 前置文本1 | 前置文本2 | 主要小文本1 | 主要小文本2 |
| ------ | ----------------- | -------- | ----------------- | --------- | --------- | --------- | ----------- | ----------- |
| 上课前 | 通知发出 → 上课前 | `{课名}` | `{开始} | {结束}` | 空        | `即将上课` | `地点`    | `{倒计时}`  | `{教室}`    |
| 上课中 | 上课时刻到达      | `{课名}` | `{开始} | {结束}` | 空        | `距离下课` | `地点`    | `{倒计时}`  | `{教室}`    |
| 下课后 | 下课时刻到达      | `{课名}` | `{开始} | {结束}` | 空        | `已经下课` | `地点`    | `{正计时}`  | `{教室}`    |

---

## 构建

### 环境要求

- JDK 21
- Android SDK Platform 37
- Android Build-Tools 37.0.0

### 依赖准备

项目当前使用 `libxposed` API 101：

- `compileOnly("io.github.libxposed:api:101.0.0")`
- `implementation("io.github.libxposed:service:101.0.0")`

### Release 签名配置（本地）

`assembleRelease` 强制要求存在：`build/config/signing.properties`

示例：

```properties
storeFile=D:/keystore/release.jks
storePassword=你的Store密码
keyAlias=你的别名
keyPassword=你的Key密码
```

注意：
- `storeFile` 必须指向存在的 `.jks` 文件
- 仅 `Release` 需要该文件，`Debug` 不需要
- 缺失时会报：`缺少build/config/signing.properties签名配置文件`

### 构建命令（Gradle）

```bash
# Debug
./gradlew assembleDebug

# Release
./gradlew assembleRelease
```

### 构建命令（Python 脚本）

```bash
python build/build.py
```

脚本支持 Debug / Release / clean 后构建，并在构建失败时自动尝试一次恢复重试。

### 输出路径

- Debug：`build/debug/courseisland_<version>_debug.apk`
- Release：`build/release/courseisland_<version>.apk`

### 版本管理

版本号由 `app/build.gradle` 自动生成，格式为 `yyyymmddxx`（年月日 + 当日序号）：

- `versionCode`：如 `2026040816`
- `versionName`：
  - Debug：`2026040816_debug`
  - Release：`2026040816`

### CI / 发布（GitHub Actions）

- `CI Debug Build`：执行 `./gradlew --no-daemon clean assembleDebug`
- `Release Build & Publish`：先跑 `debug-check`，通过后再执行 `assembleRelease` 并发布 release

### CI Release 所需 Secrets

`Release Build & Publish` 工作流会生成 `build/config/signing.properties`，依赖以下 Secrets：

- `SIGNING_KEYSTORE_BASE64`：Base64 编码的 `.jks` 文件内容
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`
- `RELEASE_TOKEN`：可选；用于推送 tag（未设置时回退到 `github.token`）

---

## 项目结构

```text
xiaoailand/
├── app/                                    # 主模块
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/xiaoai/islandnotify/
│       │   ├── ModuleEntry.java            # 模块入口（LSPosed 回调分发）
│       │   ├── MainActivity.java           # 壳 Activity（承载 Compose）
│       │   ├── MainComposeEntry.kt         # 主界面（配置项/UI）
│       │   ├── hook/                       # Hook 实现（MainHook/SystemUiHook/DeskClockHook）
│       │   ├── integration/                # 调用小爱内部接口：静音/勿扰切换与课表主动刷新
│       │   ├── schedule/                   # 调度与超时配置
│       │   ├── config/                     # 默认值、迁移、配置读写
│       │   ├── holiday/                    # 节假日逻辑
│       │   └── modernhook/                 # libxposed API 101 适配封装
│       ├── resources/META-INF/xposed/
│       │   ├── java_init.list
│       │   ├── scope.list
│       │   └── module.prop
│       └── res/                            # drawable / values / values-night / mipmap-*
├── hyperx-compose/                         # Compose UI 组件子模块（本地 module）
│   └── src/main/
│       ├── kotlin/dev/lackluster/hyperx/compose/
│       └── res/
├── .github/workflows/
│   ├── ci-debug.yml
│   ├── release.yml
│   └── sync-release-to-xposed-repo.yml
├── build/                                  # 本地构建脚本与产物目录
│   └── build.py
├── gradle/wrapper/                         # Gradle Wrapper
├── settings.gradle
├── build.gradle
├── gradle.properties
├── version.properties
└── README.md
```

