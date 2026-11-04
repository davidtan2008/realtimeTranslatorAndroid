# 对译 Duiyi · Android

Android 原生版：面对面实时翻译、同声传译、会议记录、任务历史。产品规则对齐 iOS 对译。

**全端侧**：听写、翻译、任务都留在手机上。不把云 STT / 云翻译当作静默降级。

技术栈：Kotlin + Jetpack Compose + Hilt + Room + **sherpa-onnx SenseVoice int8 + Silero VAD（听写）** + **llama.cpp + TranslateGemma 4B Q4_K_M（翻译）**。

最低系统：**Android 14（API 34）**，compile/target 35，仅 `arm64-v8a`。真机优先。

## 文档

| 文档 | 内容 | 时效 |
| --- | --- | --- |
| [docs/09-current-state.md](docs/09-current-state.md) | **实现现状总览（先看这个）** | ✅ 与代码一致 |
| [docs/10-latency-optimization.md](docs/10-latency-optimization.md) | 延迟归因与优化方案 | ✅ 与代码一致 |
| [docs/08-on-device-llm.md](docs/08-on-device-llm.md) | 端侧大模型：Tensor 架构、前缀 KV、大核绑定、真机数字 | ✅ 与代码一致 |
| [docs/01-product-prototype.md](docs/01-product-prototype.md) | 原型设计 | ✅ 产品规则未变 |
| [docs/02-architecture.md](docs/02-architecture.md) | 架构设计 | ⚠️ 分层仍有效，引擎名过时 |
| [docs/03-tech-choices.md](docs/03-tech-choices.md) | 技术选型 | ❌ 写的是 ML Kit，实际是 TranslateGemma |
| [docs/04-implementation-principles.md](docs/04-implementation-principles.md) | 实现原理 | ❌ 写的是系统识别器，实际是 SenseVoice |
| [docs/05-obstacles.md](docs/05-obstacles.md) | 实现阻碍点 | ✅ |
| [docs/06-compromises.md](docs/06-compromises.md) | 功能与效果打折扣的点 | ✅ |
| [docs/07-roadmap.md](docs/07-roadmap.md) | 落地路线图 | ⚠️ M1–M5 代码路径已落地，真机清单未签字 |
| [prototype/platform.html](prototype/platform.html) | 可交互原型 | — |

## 打开工程

本机 Android Studio：

```bash
open -a "/Volumes/DevDisk/Applications/Android Studio.app" .
```

SDK：`/Volumes/DevDisk/Library/Android/sdk`（写在 `local.properties`）。Gradle 用工程自带 wrapper；JDK 用 Android Studio 自带 JBR 21。

```bash
./gradlew.local.sh assembleDebug      # 自动挑一个 JDK 17/21，见下
./gradlew.local.sh testDebugUnitTest  # 纯 JVM 单测，当前全绿
```

### 工具链约束（升级 Android Studio 后踩过的坑）

Android Studio Quail 3（2026.1.3）把 Gradle 升到 8.14.5、内置 JBR 换成 JDK 25，这个组合和本工程的版本**不兼容**，而且报错信息完全指不到原因：

| 现象 | 真正的原因 | 处置 |
| --- | --- | --- |
| `Plugin [id: 'com.android.application', version: '8.7.2'] was not found`，且三个仓库都搜过 | 日志上一行其实是 `Resolved plugin ...`，**插件本身解析成功了**；是 Gradle 8.14.5 与 AGP 8.7.2 不兼容 | 用 **Gradle 8.10.2**（wrapper 里已固定） |
| `What went wrong: 25.0.2`（错误信息只有版本号） | Kotlin 2.0.21 的 `JavaVersion.parse` 解析不了 "25.0.2"，抛 `IllegalArgumentException` | Gradle 守护进程用 **JDK 17–21** |
| AS 提示升级 Gradle | 升级后会落回上面第一条 | **不要接受**，除非同时升级 AGP/Kotlin |

所以：

- `gradle/wrapper/gradle-wrapper.properties` 固定在 **8.10.2**，不要改。
- Gradle 的 JDK 要用 **17–21**。AS 里是 `Settings → Build Tools → Gradle → Gradle JDK`（本机已指向 `temurin-17`）；命令行用 `./gradlew.local.sh` 会自动找。
- AS 内置的 JBR（`.../Android Studio.app/Contents/jbr/...`）现在是 **JDK 25，不能用**。

> 想跟上新 IDE 的默认值（Gradle 8.14.5 + JDK 25）需要一起升级 AGP、Kotlin、KSP、Hilt 并回归测试，是独立的一次改动，没做。

> ⚠️ `third_party/llama.cpp` 是指向 `/Volumes/DevDisk/src/llama.cpp` 的符号链接。该目录缺失时构建会从 GitHub 拉 tag `b10775`，**与本机实际编译的版本不一致**。换机器前先确认这个链接指向哪里。

包名：`com.duiyi.android`　应用名：对译

首次运行：欢迎 → 我看的语言 → 麦克风权限 → 下载模型（SenseVoice ≈240 MB + TranslateGemma ≈2.49 GB）→ 首页四个入口。

## 真机（荣耀 ALP-AN00）

开发真机：**荣耀 ALP-AN00**（Magic V2 同代），骁龙 8+ Gen 1，16 GB，Android 14。

听写策略按厂商分叉（`SpeechEnginePolicy`）：

1. **荣耀 / 华为 → 一律走 sherpa-onnx SenseVoice**，不尝试系统识别器（默认引擎对第三方 App 经常不回结果）。
2. 其他厂商 → `SpeechRecognizer`，优先端侧，按 Google → 其他 → 厂商 排序；Google 应用缺麦克风权限时直接报 `GoogleMicrophoneDenied`。
3. 不用 `EXTRA_PREFER_OFFLINE` 之外的花招；荣耀不设自定义静音窗口 extras。

> 非荣耀路径（Pixel / 三星）**只写了代码，没有真机验证过**。

翻译：`GemmaTranslator` 是进程级 Hilt 单例。点「听」时 `prepare()` 加载 4B（约 8–11 s）并对每个语言对预热前缀（每个约 3 s）。引导页只下载权重、不做预热；会议模块进入时 `release()` 卸载，把大核还给听写。

设置与引导会写明当前引擎是「端侧识别」还是「Google 语音」。

## 模块

| 模块 | 行为 |
| --- | --- |
| 面对面 | 上下对半，上屏容器旋转 180°；语种当路由键；只译 final；新任务可按偏好自动开听 |
| 同声传译 | 单栏正字；不自动开听；读出必须已接耳机；TTS 队列上限 2，按增量后缀朗读 |
| 会议记录 | 单语全文稿，不翻译；进入即卸载 4B；超 2 万字提示结束并新开 |
| 任务历史 | 本地 Room；左滑删除；导出 UTF-8 txt 走系统分享 |

任务语义：新任务 → 听/暂停 → 结束任务。暂停 ≠ 结束。进后台停听写不停任务。空任务不进历史。

## 诊断

延迟探针与调试页（`LatencyDebugActivity`）：

```bash
adb shell am start -n com.duiyi.android/.debug.LatencyDebugActivity
adb pull /data/data/com.duiyi.android/files/latency-probe.txt
```

面对面说几轮之后打开，可以看到每一轮的分解：VAD 端点 / SenseVoice 解码（含 RTF）/ joiner 持有 / 排队 / 首字 / 生成 / 总计。页顶开关可在运行时切换「生成期间跳过 partial 解码」，用来对比抢核的影响。

翻译自检验（不含听写）：

```bash
adb shell am start -n com.duiyi.android/.debug.TranslationSelfTestActivity
```

它会顺带打开 ggml 的 DEBUG 日志，`files/llama-jni.log` 里能看到 Q4_K 用了哪个 repack 内核、以及二进制编译时的 ARM 特性。

## 已知问题

延迟（面对面端到端约 2–2.7 s）与 6 个已确认缺陷见 [docs/09-current-state.md](docs/09-current-state.md) 第 6 节；延迟归因与优化方案见 [docs/10-latency-optimization.md](docs/10-latency-optimization.md)。

**当前状态：延迟优化的第 0–4 步代码已落地，但尚未编译与真机验证** —— 见 `10` 第 9 节的实施状态表。

### 构建开关

| 开关 | 用途 |
| --- | --- |
| `-PduiyiArmArch=armv8.6-a+dotprod+i8mm` | 改用 Q4_K 的 8×8 repack 内核。**已实测，在本机上反而更慢**（生成慢 8–31%），默认保持 `armv8.2-a+dotprod`。开关留着只是为了让这个对比可复现，见 `docs/10-latency-optimization.md` §5.2 |
