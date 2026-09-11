# 05 实现阻碍点 · Android

这些不是「多花几天就能抹平」的工程问题，而是系统能力、厂商差异和产品承诺互相打架的地方。路线图里每一条都要有应对，而不是希望下个 API 补齐。

---

## 1. 没有双路 SpeechAnalyzer

iOS 面对面的核心是：**两个 Transcriber 同时听，用语种路由，而不是说话人分离**。

Android 公开 API 同一时刻一个 `SpeechRecognizer`。`ERROR_RECOGNIZER_BUSY` 会挡住第二实例。Android 14 的「多语种识别 + 自动切换」是**一个模型里切语言**，不是两路并行：

- 两人抢着说、间隙极短，会丢词（官方在 Android 15 发行说明里承认过）。
- 切换 extras 只能限制「前几秒允许切」或「切 N 次后锁死」，锁死后对面换语言不会跟。
- `onLanguageDetection` 在部分 OEM 上从不回调。

阻碍：面对面「无需手动切换谁在说」在 Android 上是**尽力而为**，不是等价实现。真机矩阵必须含：中英对谈、插话、粤语口音中文、日英（若开日语）。

应对：Assembler 脚本锁 + 15 的 switch extras；失败则设置里提供「手动对调」作为逃生（默认仍自动）。不并行开两个识别器。

---

## 2. 端侧识别不是 Android 的统一能力

`isOnDeviceRecognitionAvailable` 在 Pixel、部分三星上为真，在大量国内 ROM 上为假或「只有中文端侧」。泰语 / 越南语端侧包经常不存在。

`checkRecognitionSupport` 的 `installedOnDeviceLanguages` 与 Google App 版本、系统「离线语音识别」开关绑定。用户关掉 Google 服务（华为海外、部分定制机）则整条链路不存在。

阻碍：无法对「能装 Android 14 的手机」承诺六语离线听写。Catalog 必须是动态的，首页选语种会随设备变。

应对：不合格设备停在引导，文案点名能力，不静默改云识别。国内无 GMS 机型见第 7 条。

---

## 3. 识别会话被系统掐断

历史行为：无语音若干秒或大约一分钟，`onError`。会议记录和 45 分钟同传必须靠自动续听。

续听副作用：

- 句中被切，下一会话把后半句当新 utterance，字幕会断成两行。
- 部分 OEM 续听需要用户再点一次（极少，但要探测）。
- 续听瞬间丢失语言检测状态。

应对：Assembler continuation；会议模块把「断线重连」做成无 UI 抖动；连续失败 3 次才提示「轻点继续」。

---

## 4. 无法稳定自喂 PCM

iOS 用 `AVAudioEngine` tap 把同一路 PCM 送给 Analyzer，所以能精确选 `measurement`。Android `SpeechRecognizer` 不提供一等公民的 `AudioRecord` 注入。

结果：面对面想关 AEC、同传读出想开 AEC，都只能靠 `AudioManager` 模式和希望识别服务尊重它。很多实现忽略 `AudioSource`。

应对：分档实现（见实现原理 2.1）。不要为了「架构漂亮」在 v1 写一套喂包反射。

---

## 5. ML Kit 翻译包与系统翻译不是一回事

体积：每个语言包约十几 MB，双向中英大约 30 MB 量级，六语全下会到上百 MB。引导必须按用户所选对下载，不能预置六语全排列。

下载依赖 Play 服务 / ML Kit 动态模块。无 GMS 设备这条路直接断。

质量：口语、专有名词、日语敬语弱于 Apple Translation。产品不能宣传「和 iOS 同一译文」。

---

## 6. Gemini Nano 覆盖面极窄

ML Kit GenAI Summarization 绑 AICore，主要是 Pixel 8/9 及少量机型，还排除解锁 bootloader。中端机、几乎所有国内品牌 = `DeviceNotEligible`。

阻碍：不能把「会后总结」当 Android 版卖点。导出才是所有人都能完成的闭环。

---

## 7. 无 GMS 与国内 ROM

对译 Android 版若走 Play 发行：ML Kit、端侧识别、AICore 路径清晰。

若要上国内应用商店、跑华为 / 小米 / OPPO / vivo 无 GMS 或弱 GMS：

| 能力 | 无 GMS 时 |
| --- | --- |
| ML Kit Translation | 不可用 |
| Google on-device ASR | 经常不可用 |
| 厂商自带识别 | 各写一套，协议不一致 |

这是**发行策略阻碍**，不是代码能绕开的。v1 定位：**以 GMS + 端侧识别可用的设备为合格设备**。国内无 GMS 作为明确不做，或 v2 接厂商 SDK（会破坏「一套引擎」）。

华为手机在国内的主场是鸿蒙原生工程，不要在本 Android 工程里塞 HMS。

---

## 8. TTS 声音与路由

`TextToSpeech` 引擎可能是 Google、三星、小米。越南语 / 泰语声可能缺失或要另下。

蓝牙：A2DP 能听 TTS 但 SCO 才能同时录音，部分手机开读出后面场 ASR 会塌。`VOICE_COMMUNICATION` 会切窄带。

阻碍：同传「戴耳机听译文同时听现场」在中端蓝牙耳机上可能二选一。

应对：优先有线；蓝牙失败则关读出保留字幕。测真机：Pixel + 常见蓝牙豆。

---

## 9. 倒字 + 系统手势 + 挖孔

Compose `rotate(180f)` 后：

- 点击热区跟着转，上半屏误触结束任务——按钮必须只放未旋转层。
- TalkBack 阅读顺序混乱，要对 peer pane 单独 `traversalIndex`。
- 挖孔 / 摄像头居中：上半屏文字要避开 `WindowInsets.displayCutout`，倒字后 insets 坐标系易反。

真机用平放 + 挖孔机拍一张对照。

---

## 10. 权限与 Play 政策

仅 `RECORD_AUDIO`。若有人提议前台服务续听：Play 对麦克风前台服务用途检查严，且违背「离开即停」。拒绝该需求。

数据安全表必须写「音频不上传」。ML Kit 模型下载算网络，不要写成「完全无网络」。

---

## 11. 测试无法在 JVM 里证明听写

Assembler / Router 可单测。`SpeechRecognizer` 必须真机。CI 不能当合格门禁。

阻碍：回归依赖人工真机清单（见路线图 M5）。模拟器上 `isOnDeviceRecognitionAvailable` 常假，禁止用模拟器绿条代替。

---

## 12. 与 iOS 算法对齐的人力

`UtteranceAssembler`、`SpokenText`、`ClauseSplitter` 有大量边界（姓名替换、非拉丁续写阈值）。Kotlin 移植漏一条，面对面就会「同一句打两行」或「英引擎吃掉中文」。

应对：共享黄金测试集，不允许「看起来差不多」合并。
