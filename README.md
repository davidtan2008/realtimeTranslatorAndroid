# 对译 Duiyi · Android

Android 原生版：面对面实时翻译、同声传译、会议记录、任务历史。产品规则对齐 iOS 对译。

技术栈：Kotlin + Jetpack Compose + Hilt + Room + 端侧 `SpeechRecognizer`（优先 on-device，否则系统识别并 `EXTRA_PREFER_OFFLINE`）+ ML Kit Translation。不把云 STT / 云翻译当作静默降级。

最低系统：**Android 14（API 34）**，compile/target 35。真机优先。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/01-product-prototype.md](docs/01-product-prototype.md) | 原型设计 |
| [docs/02-architecture.md](docs/02-architecture.md) | 架构设计 |
| [docs/03-tech-choices.md](docs/03-tech-choices.md) | 技术选型 |
| [docs/04-implementation-principles.md](docs/04-implementation-principles.md) | 实现原理 |
| [docs/05-obstacles.md](docs/05-obstacles.md) | 实现阻碍点 |
| [docs/06-compromises.md](docs/06-compromises.md) | 功能与效果打折扣的点 |
| [docs/07-roadmap.md](docs/07-roadmap.md) | 落地路线图 |
| [prototype/platform.html](prototype/platform.html) | 可交互原型 |

## 打开工程

本机 Android Studio：

```bash
open -a "/Volumes/DevDisk/Applications/Android Studio.app" .
```

SDK：`/Volumes/DevDisk/Library/Android/sdk`（写在 `local.properties`）。Gradle 用工程自带 wrapper；JDK 用 Android Studio 自带 JBR 21。

```bash
export JAVA_HOME="/Volumes/DevDisk/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="/Volumes/DevDisk/Library/Android/sdk"
./gradlew assembleDebug
```

包名：`com.duiyi.android`  
应用名：对译

首次运行：欢迎 → 我看的语言 → 麦克风权限 → 下载 ML Kit 翻译包 → 首页四个入口。

## 真机与 GMS

开发真机：**荣耀 ALP-AN00**（Magic V2），Android 14，有 GMS。

听写策略（荣耀真机）：

1. 中间大圆按钮在没有任务时也会开任务并开始听，不必先找「新任务」。
2. 荣耀默认 `SpeechRecognizer`（小艺 / Celia）经常对第三方 App **不回结果**。有 Google 应用时，按查询到的 `RecognitionService` 优先绑定 Google。
3. 不在荣耀上使用 `createOnDeviceSpeechRecognizer` 空壳，也不设 `EXTRA_PREFER_OFFLINE`，也不设自定义静音窗口 extras（这些会让荣耀一直无字幕）。
4. 听写开始时不再 `checkRecognitionSupport`（会占用唯一识别器）。若 3 秒内没有 `onReadyForSpeech`，换下一个引擎；全部失败则提示安装 Google 应用。
5. 不调用 Cloud Speech API；设置页写明当前是 Google 系统识别还是端侧。

设置与引导会写明当前引擎是「端侧识别」还是「Google 语音」。

**无 GMS：** 国内大部分 Android 手机没有 Google Play 服务。v1 翻译走 ML Kit，无 GMS 时翻译标为不可用，文案说明后续会接厂商引擎。架构上 `SpeechRecognizing` / `Translating` 可替换，不要把整套产品绑死在 GMS 上。无 Gemini Nano 时总结只说「这台手机不能在本机生成摘要」，不要写「请打开 AI」。

v1 竖屏锁定。折叠屏外屏/内屏都不做双屏同时听写。

## 模块

| 模块 | 行为 |
| --- | --- |
| 面对面 | 上下对半，上屏容器旋转 180°；语种当路由键；新任务可按偏好自动开听 |
| 同声传译 | 单栏正字；不自动开听；读出必须已接耳机 |
| 会议记录 | 单语全文稿，超 2 万字提示结束并新开 |
| 任务历史 | 本地 Room；左滑删除；导出 UTF-8 txt 走系统分享 |

任务语义：新任务 → 听/暂停 → 结束任务。暂停 ≠ 结束。进后台停听写不停任务。空任务不进历史。
