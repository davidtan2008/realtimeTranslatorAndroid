# 10 翻译延迟：归因与优化方案

问题：真机上**面对面的译文出来得慢**。曾经试过「把中间结果送去翻译」，效果很差（译文乱跳、质量崩），已回退。

本文回答三件事：**慢在哪**、**上次为什么失败**、**这次该怎么做**。

测量环境：荣耀 ALP-AN00，骁龙 8+ Gen 1（1×X2 + 3×A710 + 4×A510），16 GB，Android 14。基准数字来自 `TranslationSelfTestActivity` 的真机自检（见 [`08`](08-on-device-llm.md) 第三节）。

---

## 0. 结论摘要

| # | 动作 | 预期收益 | 质量风险 | 工作量 | 类型 |
| --- | --- | --- | --- | --- | --- |
| 1 | **修 joiner 卡死**（短句要等下一句） | 0 到数秒（短句友好） | 无 | 1 h | 缺陷 |
| 2 | **流式回字幕**（生成 token 逐个上屏） | 体感 −0.5～0.8 s | 无 | 1–2 d | 体感 |
| 3 | **用 partial 投机 prefill 源句**（§5.1） | −0.25～0.4 s | 无（失配即回退） | 2–3 d | 真实提速 |
| 4 | 面对面单独调短 VAD 静音窗 | −0.15 s | 轻微（切句变碎） | 0.5 d | 真实提速 |
| 5 | 给 partial 解码限流，别抢 Gemma 的核 | 未知，可能 −0.2～0.4 s | 无 | 1 d | 需先测 |
| 6 | ARM 内核：开 `i8mm`（Q4_K 8×8 repack） | **已实测：反而慢 8–31%，不用** | 无 | 已完成 | 已否掉 |
| 7 | **增量分段翻译**（长句边听边译） | 长句提前 1–3 s 出首句译文 | **中等**（片段脱离上下文） | 3–5 d | 设计变更 |
| 8 | 加专用小翻译模型做分流（§5.4） | 推算很大，**无公开实测** | **未知，需真机 A/B** | 大 | 选型 |

1–3 是「低风险、马上能做」，建议先做。7 才是真正解决「长句等说完」的结构性方案，但要接受质量折衷，建议做成开关并单独 A/B。

---

## 1. 已知的硬数字（真机实测）

`TranslationSelfTestActivity` 在 honor 真机上的自检：

| 项 | 数值 |
| --- | --- |
| `prepare`（加载 4B + 双向前缀预热） | 18049 ms |
| en→zh `How are you.` | 1075 ms（`prefix_ms=15` + `suffix_ms=373` + `gen_ms=670`） |
| en→zh `It's very cute.` | 1145 ms |
| zh→en `这个也不对呀。` | 1717 ms |

从这一组数字能推出两件关键的事：

**（a）原文 prefill 占了三分之一。** `suffix_ms = 373 ms` 只为把 9–11 个源句 token 灌进 KV —— 占总时长 35%。前缀缓存只免掉了模板（`prefix_ms=15`），没免掉源句。

**（b）decode 没有多少「换内核就能拿到」的空间。** 本文件早先写过一段推算，说「有效带宽只有 18.6 GB/s、理论还有 1.3–1.6×」——那个推算基于对输出 token 数的猜测，**不可靠，已被实测否掉**（见 §5.2：换了更宽的 i8mm 内核反而慢 8–31%）。现在能确定的是：`gen_ms` 在 400–1200 ms 量级随输出长度变化，而瓶颈不在 int8 算力，换指令不会变快。

（自检日志里的 `out=` 是**字节数**不是 token 数，别拿它算每 token 耗时。）

**重要的口径问题**：这三个数字是在**没有并发听写**的自检里测的。真实面对面时，SenseVoice 的 partial 解码每隔 1 s 跑一次，而且每次解码的是**从头累积的整段音频**（`SampleAccumulator`，上限 8 s）。也就是说 8 秒的话会被解码 1+2+…+8 = 36 秒的音频，识别器的 `numThreads` 又开到 4——**正好和 Gemma 抢同一批 4 个大核**。

> 所以「译文慢」很可能有一部分不是 Gemma 慢，而是 Gemma 在被 SenseVoice 拖。这一条**从未被测过**，是当前最大的未知数。第一步应该是把它测出来，而不是先改内核。

---

## 2. 一次面对面翻译的延迟预算

用户说一句 3 秒的中文，对面要看到英文：

| 阶段 | 估算 | 依据 |
| --- | --- | --- |
| 说话 | 3000 ms | 不可压缩 |
| VAD 端点静音 | **550 ms** | `SherpaSpeech.createVad`：`minSilenceDuration = 0.55f` |
| SenseVoice 整段解码 | 150–450 ms | RTF × 3 s，**未实测** |
| Joiner 缓冲 | **0 或 ≥1400 ms** | `HOLD_NS = 1.4 s`，见 §3 |
| 翻译排队 | 0–1700 ms | 对面那句还没译完时，`TranslationPump` 是 FIFO 单通道 |
| Gemma 源句 prefill | 300–600 ms | 实测 373 ms |
| Gemma 生成 | 500–1400 ms | 实测 670 ms（短句）～更长 |

**合计约 1.5–3.0 s**，与「有点慢」的手感一致。Gemma 只占其中一半；另外一半在 VAD 端点、ASR、以及调度上。

---

## 3. 先修缺陷：两个「等不到」的情况

这两条不是优化，是 bug。修完的收益比任何内核调优都大。

### 3.1 短句永远等不到译文（严重）

`SpokenSegmentJoiner` 会先把「不像句子」的短文本攒住，等 1.4 s 超时释放。问题在调用点——`SherpaSpeech.kt:151`：

```kotlin
val decoded = decodeSamples(rec, samples, primary)
if (decoded.first.isNotBlank()) emitAssembled(decoded.second, decoded.first, isFinal)
joiner.tick(System.nanoTime())?.let(::sendPiece)   // ← 唯一的 tick 调用点
```

`tick()` **只有在一段音频被解码之后才会被调用**。说话人一停，VAD 不再切段、partial 也不再产生，解码循环阻塞在 `select` 上，`tick()` 再也不会被触发。于是「好的」「Thank you」「我同意」这类不满足 `isSentenceLike`（中文 ≥10 汉字、英文 ≥6 词或 ≥28 字母、或以标点结尾）的短句：字幕只有原文，**译文要等到下一次有人说话才出现**。

`SpokenSegmentJoinerTest.timeoutFlushesHeldShort` 是绿的——它直接调 `tick()`，测不到调用点。

**修法**：不要依赖解码循环驱动超时。在 `captions()` 里起一个独立的心跳协程（例如每 200 ms 一次 `joiner.tick(System.nanoTime())`），或者在 `finals.receive()` 拿到段落后就安排一个 `delay(HOLD_NS)` 的兜底 job。同时给 `TranslationPump` 补一条「本句已在 X ms 前送达过 final」的状态，避免心跳触发后重复送译。

### 3.2 长句要等说完才出译文

`enqueueTranslation` 第一条就是 `if (!isFinal) return`（`ConversationViewModel.kt:487`、`InterpretationViewModel.kt:486`）。加上 VAD 只在 0.55 s 静音后才切段：**连续说 15 秒不换气，就有 15 秒屏幕上一个译文字都没有。**

这是「慢」最直观的来源，也是 §6 要正面解决的问题。

### 3.3 顺带

- `translator.prepare()` 在**每次** `startListening()` 里调用（`ConversationViewModel.kt:371`）。模型没加载时要 8–11 s + 双向预热 6 s，期间按钮只是灰掉、文案「正在准备…」（`Copy.preparing`），`onProgress {}` 是空 lambda。**首次点「听」的 18 秒几乎没有反馈。**建议：引导完成后立刻后台 `prepare()`；或在面对面页面上显示真实进度。
- 从会议模块回到面对面会再付一次 18 秒——`MeetingViewModel` 进入时 `release()` 卸载了模型，回来要重载。这个取舍是对的（把大核还给听写），但需要在 UI 上说清楚，或者做一个「保留预热」的开关。

---

## 4. 「译中间结果」上次为什么失败

上次的做法是：partial 一出就整段送 Gemma 翻译，译文到了就覆盖字幕。它有三个独立的致命伤：

1. **partial 假设本身在变。** SenseVoice 每秒重解一次累积音频，`我觉得这个方案` → `我觉得这个方案不太` → `我觉得这个方案不太行`。每次都整段重译，屏幕上就是不停地推翻重写，读起来像乱跳。
2. **Gemma 太慢，重译不划算。** 一次 1–1.7 s。1 Hz 的 partial 频率下，队列永远排满，`TranslationPump` 又是 FIFO 且**不取消已经在跑的 generate**——半句占满 4 个大核 1 秒，整句只能排队。结果是「又慢又乱」。
3. **没有提交点。** 覆盖式更新没有「这一段已经定稿、不再改」的概念，所以永远不稳定。

回退成「只译 final」之后，这三条都没了，但代价就是 §3.2 的长句等待。

**结论：方向没错，做法错了。** 正确的形态是「**只译稳定前缀，只追加，不覆盖**」——见 §6。

---

## 5. 让每一次翻译更快

### 5.1 投机 prefill：用 partial 提前灌掉源句的 373 ms

`TranslationPump` 送来的是一个源句 `suffix`，而 `suffix_ms = 373 ms` 纯粹是把它灌进 KV。**这段 prefill 可以提前做**——因为 partial 已经给了我们一个「final 的长前缀」。

做法：把**当前 latest partial 的源句**做一次投机 prefill：

1. 恢复模板前缀快照（15 ms）
2. decode 当前 partial 的 token（实测约 37 ms/token）
3. `llama_state_get_data` 快照到第 3 个槽（约 15 ms、11 MB）

final 到达时：
- 若 final 的 token 序列**以 partial 的 token 序列为严格前缀** → `set_data` 恢复该槽，只 decode 增量那几个 token
- 否则 → 丢弃，走今天的完整路径（**退化为现状，不会更慢**）

因为 partial 是从同一段音频前缀重解的，前缀匹配是常态。partial 通常覆盖 final 的 80–95%（最后一个 partial 之后还剩不足 1 s 的语音），所以省下的是那 373 ms 里的大部分。质量风险为零：**译文仍然是从 final 原文算出来的**，复用的只是 KV 快照。

**但「免费」是假的，这里要说清楚 —— 什么时候做这次 prefill？**

- 放到「端点静音那 550 ms」听起来完美，其实**不行**：VAD 判定结束后，final 段的 SenseVoice 解码正好也在那段时间跑，两边都要 4 个大核，会互相拖慢，净收益可能归零甚至为负。
- 可行的是**紧跟在每次 partial 解码之后**就顺手做这次 prefill。这时 Gemma 是空闲的（面对面轮流说话，上一句早译完了），CPU 有富余。代价是每秒多付约 300 ms 的 prefill 计算和一次 11 MB 快照。
- 双方抢话时 Gemma 不空闲，这次 prefill 会和上一句的生成抢核——**这种情况下应该跳过**（`TranslationPump` 有 pending 就不做投机）。

所以收益取决于「Gemma 是否空闲」，而面对面的常态是轮流说话、Gemma 空闲。**预计 −0.25～0.4 s**，需要按 §5.3 先把并发抢核测清楚再定。

实现上的一个坑（容易写错，且错了会译得莫名其妙）：**快照里必须留最后一个 token 给 `generate` 去 decode。** `llama_state_*` 恢复的是 KV，而 `llama_sampler_sample` 读的 logits 是 decode 产生的——如果恢复完直接采样，采到的是上一次 decode（可能是另一个方向的翻译）留下的 logits。所以正确做法是：

- `prefillSource` 只 prefill `prefix + 源句token[0..n-2]`，按这个序列建槽
- `generate` 命中后恢复快照，再 decode 最后一个 token（约 37–130 ms），然后采样

代价从「整段 prefill 373 ms」降到「1 个 token」，仍然省掉绝大部分；而且因为总会 decode 一个 token，logits 一定是有效的。槽位管理上把 speculative 槽和两个模板槽分开存（`kMaxSpeculatives = 2`，可随意淘汰），避免逐句的源句槽把模板槽挤掉。

落地：`llama_jni.cpp` 的 `prefillSource` / `generate`、Kotlin 侧 `Translating.prefillSource`、由 `TranslationPump.speculate` 在 partial 到达时按「pump 空闲」触发。

### 5.2 ARM 内核：i8mm —— **已实测，结论是不要用**

这一条原本的推测是「开 i8mm 换 Q4_K 8×8 内核，预期快 1.2–1.5×」。**真机实测把这个推测否掉了。**

先说两条已经查清的事实：

- **repack 本来就在跑。** 解开 ggml DEBUG 日志后，真机日志里是 `repack tensor blk.0.attn_q.weight with q4_K_8x4` —— dotprod 版内核，与读源码的推断一致（`ggml_cpu_has_matmul_int8()` 为假时选 `q4_K_8x4_q8_K`）。
- **A510 支持 I8MM，SIGILL 的担心是多余的。** 启动日志里 `kernel cpu features: dotprod=1 i8mm=1`。arm64 内核只有在**所有** CPU 都实现某特性时才会置上对应的 HWCAP2 位，所以这颗 SoC 的小核也支持 I8MM。`CMakeLists.txt` 里那句「little cores may not run i8mm」不成立。

**然后实测结果（Honor ALP-AN00，两次构建各跑 3 轮自检，同一组句子）：**

| 指标 | dotprod 中位 | i8mm 中位 | 变化 |
| --- | --- | --- | --- |
| 生成（短输出，suffix=9） | 437 ms | 470 ms | **+8%** |
| 生成（中输出，suffix=11） | 418 ms | 469 ms | **+12%** |
| 生成（长输出，suffix=10） | 840 ms | 1102 ms | **+31%** |
| 整句 zh→en | 1298 ms | 1605 ms | **+24%** |
| 源句 prefill（suffix=9） | 465 ms | 374 ms | −20% |
| 源句 prefill（suffix=10） | 436 ms | 506 ms | +16% |

**生成是每句耗时的大头，而它在 i8mm 下全面变慢**，长输出时慢了 31%（两组样本区间不重叠）。prefill 一栏自相矛盾（一处快 20%、一处慢 16%），看不出可靠收益。

**所以默认构建保持 `armv8.2-a+dotprod`（`q4_K_8x4`）。** 一个说得通的原因：我们几乎全是 **GEMV**（batch=1 的逐 token 解码，prefill 也只有十来个 token），而 8×8 交错布局主要是为 **GEMM**（大批量）设计的；在 GEMV 上它换不来收益，反而可能因为访存模式与循环开销更差。这也说明「指令级 2× 吞吐」推不出「端到端更快」——瓶颈不在 int8 算力。

构建开关仍然保留（`-PduiyiArmArch=armv8.6-a+dotprod+i8mm`），因为它把一个原本说不清的问题变成了**一次可复现的 A/B**；但答案是不用。

样本量与噪声：每组 3 轮，单轮 `gen_ms` 的波动约 ±20%，且每轮都要重新加载 2.4 GB 模型，无法排除热漂移。上述「整体变慢」的结论依赖的是长输出那一档不重叠的区间，以及总量指标的一致方向；`en2zh` 那一句反而快了 5%，说明单句级别仍有余量。要更硬的结论需要更多轮次和更受控的热状态。

> 依据来源：[Arm A64 指令集文档（SMMLA / FEAT_I8MM）](https://developer.arm.com/documentation/ddi0602/2021-12/SVE-Instructions/SMMLA--Signed-integer-matrix-multiply-accumulate-)、[binutils 的 Cortex-X2 特性定义提交](https://sourceware.org/git/?p=binutils-gdb.git;a=commitdiff;h=50aaf5e6ee2687417ca4fe1a596e54a7691828db)、[A710/X2 的 SMMLA 微基准（MegPeak）](https://zhuanlan.zhihu.com/p/563985133)。这些资料给出的是指令级吞吐，与本机实测的端到端结果不一致 —— 以实测为准。

### 5.3 别让 partial 解码抢 Gemma 的核

见 §1 末。SenseVoice 的 partial 解码是**平方级**的重复劳动，还开满 4 线程。可做的（按代价从低到高）：

- partial 频率 1 s → 1.5～2 s（`PARTIAL_NS`）
- partial 解码只喂**最近 N 秒**的音频，而不是累积全段（`SampleAccumulator.snapshot()` → 取尾部窗口）。这同时修掉平方级开销和 8 s 上限
- partial 解码的 `numThreads` 降到 2

先测：在自检之外加一组「并发听写时的 `gen_ms`」，和 670 ms 对比。差多少，就是这一条的价值。

### 5.4 换模型：唯一可能带来量级提升的方向（但会动质量）

前面 5.1–5.3 都是「在 4B 自回归这条路上榨水分」，天花板大概是 1.5–2×。想要一个量级的提升，只能换**模型类别**：**专用翻译模型（encoder-decoder 或小型 decoder-only）**。

为什么它可能快很多，原理是清楚的：

- **每次生成的字节数少一两个数量级。** decode 是带宽绑定的（实测 ≈134 ms/token 对应 2.489 GB 权重）。1.8B int8 是 ~1.8 GB、330M 是 ~0.3 GB —— 每 token 成本随权重体积缩。
- **没有 prompt 开销。** 现在每次要 `prefix_ms + suffix_ms ≈ 390 ms`，纯粹是把 80 token 模板和源句灌进 KV。专用翻译模型直接吃源句，这段整个消失。

**先说一个已经排除的选项：TranslateGemma 没有更小的版本。** 网上能找到的最小可部署产物是 ONNX q4 的 3.09 GB —— 比现在用的 GGUF 还大。所以「换个小的 TranslateGemma」这条路不存在。

**候选（许可证与体积都从 HF API 直接核过，不是搜索摘要）：**

| 模型 | 许可证 | 体积 | 备注 |
| --- | --- | --- | --- |
| `tencent/Hy-MT2-1.8B` | **Apache-2.0** ✅ | 有 **461.9 MB 的 1.25-bit GGUF**；另有 1.82 GB int8 `.litertlm`（LiteRT-LM 可直接跑 Android） | 许可证干净，体积最小，最值得先试 |
| `tencent/HY-MT1.5-1.8B` | ⚠️ **HF 上未声明** | 1.13 GB Q4_K_M | 有真实上架 Android 的案例，但**许可证不明，先别碰** |
| `alirezamsh/small100`（SMaLL-100） | **MIT** ✅ | 330M，int8 ONNX 约 609 MB | **纯速度路线最优**：MIT、小、`casawolice/small100-onnx` 直接带 `examples/android/Small100Translator.kt` |
| `NiuTrans/LMT-60-0.6B` / `1.7B` | Apache-2.0 | — | 备选 |
| `facebook/nllb-200-distilled-600M` | ❌ **CC-BY-NC-4.0** | — | **非商用，直接排除** |
| `google/madlad400-3b-mt` | Apache-2.0 | 官方仓库**自带** `model-q2k/q3k/q4k.gguf` | llama.cpp 有 `LLM_ARCH_T5` 条目，但 T5 支持成熟度需实测 |
| Opus-MT / Marian | CC-BY-4.0 | 77M | CTranslate2 **没有 Android 路径**，且逐语言对 |

**运行时这块是通的**：`sherpa-onnx-v1.13.5.aar` 的 `jni/arm64-v8a/libonnxruntime.so` 已经把 ONNX Runtime 带进包里了（`app/build.gradle.kts` 里那句 `pickFirsts += "**/libonnxruntime.so"` 正是为它写的），所以跑 ONNX 版翻译模型**不需要引入新的原生依赖**。但要注意 **sherpa-onnx 本身完全不做机器翻译**（只有 ASR/TTS/VAD），得直接用 ONNX Runtime 的 Java/Kotlin API。

**必须诚实标注的不确定性（这是本文最弱的一环）：**

- **公开资料里找不到任何「encoder-decoder 翻译模型在手机 CPU 上的实测延迟」。** CTranslate2 的 Opus-MT 数据是 x86 服务器上的；llama.cpp 的骁龙数据跑在 **Hexagon NPU 上，不是 CPU**。所以「5–10×」是**推算，不是实测**。
- 可参照的只有 decoder-only 的实测：Google 公布的 LiteRT-LM CPU 数据里，Gemma3-1B 在 S24 Ultra 上 **33 tk/s**。S24 Ultra 是 8 Gen 3，**本机 8+ Gen 1 大致要再慢 1.5–2.5×**（估算）。1.8B 的专用模型不会比这更快——除非 1.25-bit 那种极端量化真的成立。
- **质量未知，大概率是降的。** 4B 指令模型在口语、专名、上下文一致性上强于小型专用模型。面对面的句子短、口语重，恰好是专用 NMT 相对最不吃亏的场景，**但这是假设**。
- **语言覆盖**：Hy-MT2 是多语的；small100 是 100 语言单模型；Opus-MT 要按语言对逐个上。

**建议的形态：不是替换，而是分流。** 短句、口语（面对面的绝大多数）走快模型；长句、复杂句仍走 4B。平均延迟大幅下降，质量退化只落在快模型 hold 不住的那一小部分。

**但在有真机对话 A/B 之前不要动。** 正确的下一步是：拿 `Hy-MT2-1.8B` 的 1.25-bit GGUF 和 `small100` 的 ONNX 各跑一遍，用**同一组真实对话句子**同时测「每句延迟」和「人工可读性」，再决定。只靠论文指标或本节的推算都不够。

### 5.5 输出 token 数就是时间

`gen_ms` 与输出 token 数线性相关，这在自检里已经能看到：zh→en `这个也不对呀。`（1717 ms）比 en→zh `How are you.`（1075 ms）慢，**不是方向更难，是英文译文更长**。所以：

- `TranslateGemmaPrompt.maxOutputTokens` 已按源句长度封顶（16–64），保持。
- 不要指望这点还有多少空间。自回归的物理账，砍不掉。

---

## 6. 让长句不再「等说完」：增量分段翻译

这才是回应「译文出来得慢」的结构性方案，也是 §4 那个想法的正确版本。三个关键设计：

**（1）用流式的提交点，不用抖动的 partial。**

从连续的 partial 里算**稳定前缀**：一段文本如果在最近 N 次 partial（或最近 ~800 ms）里逐字未变，就认为它稳定了。只在**标点边界**上提交（SenseVoice 开了 ITN，中文输出带标点，`，。？` 天然是翻译单元）。

**（2）只追加，绝不覆盖。**

一个提交块翻译一次，结果**追加**到该侧 `CaptionLine.translatedText` 尾部，此后不再修改。屏幕上译文是单调增长的，永远不会回退——这正是上次失败的地方。`PaneState.applyTranslation` 目前用 `SpokenText.preferredTranslation` 取「较长的一版」，需要为增量模式加一条 `append` 语义。

**（3）队列要能跟上，且不重复劳动。**

`TranslationPump` 现在按 `utteranceId` 覆盖待处理项，但**不取消 in-flight 的 generate**。增量模式下每次提交都是一个新块，不存在「重复劳动」，队列压力可控；但需要给已经提交的块打标记，保证同一块不会被送两次。

**代价，必须说清楚**：

- **片段脱离上下文。** `这个方案，` 单独送去译，可能出 `this plan,`；依赖后文才能定的词（代词、量词、时态）会翻得比整句差。这是质量风险的主要来源，也是「增量」和「整句」之间真正的取舍。
- **调用次数变多。** 每块都要付一次 `prefix_ms`（15 ms）+ 尾部开销。总时间不降，甚至略升，换来的是**首句译文提前 1–3 秒**。
- **切句规则要和 `SpokenSegmentJoiner` 对齐**，否则会出现「原文一行、译文两段」的错位。

建议：做成设置项（「追求速度 / 追求质量」），默认关，先在真机上 A/B 几轮对话再定默认值。

---

## 7. 让等待不难受：流式回字幕

`llama_jni.cpp` 的生成循环是一次性返回整串（`to_jstring`）。改成在 `decode_tokens` 之后、每个 token 通过 JNI 回调（或写入一个 `StringBuilder` 供轮询）增量吐出：

- 首字上屏从 ~2.5 s 提前到 ~1.7 s
- 字幕逐字长出来，用户能看见「在干活」
- **总耗时不变**，但这是**零质量风险**的体感优化

配合 §6 的增量模式，两者叠加效果最好：块的结果本身可以边生成边上屏。

UI 侧要处理的是「同一个 `utteranceId` 的译文在增长」——`PaneState` 已经有 `phase`，加一个 `translating` 中间态即可，不需要新的状态机。

---

## 8. 明确不做的

沿用 [`08`](08-on-device-llm.md) 的结论，并补充：

- **再加线程 / 用小核**：只有 4 个大核，屏障会让情况更糟
- **OpenCL / Hexagon**：内核适配与稳定性成本远超收益，且高通对非 QNN 路径的支持在收缩
- **换 12B / FP16**：更准但更慢，同传不可用
- **把上一句塞回 prompt**：4B 会把 `[context]` 译成 `[背景]` 并串台，已实证
- **缩短官方翻译模板**：命中前缀缓存后对句延迟**没有影响**（`prefix_ms=15`），只影响冷启动
- **对 partial 做全量重译**：§4 已证伪

---

## 9. 实施状态

各步骤的代码已落地（2026-09-12）。**但没有任何一步经过编译和真机验证** —— 改动量跨 20 多个文件，其中 `llama_jni.cpp` 全新增减未编译过。下表区分「代码已写」与「已验证」。

| 步骤 | 代码 | 验证状态 |
| --- | --- | --- |
| 0 测量 | 已写：`LatencyProbe` + `LatencyDebugActivity` + SherpaSpeech/GemmaTranslator/TranslationPump 埋点 | 待编译；待真机跑一段对话读数 |
| 1a joiner 卡死 | 已写：ticker channel 进入 decode 循环的 `select`；`SpokenSegmentJoiner` 报告持有时长；补 3 条单测 | 待编译；单测需跑 |
| 1b 流式回字幕 | 已写：JNI 累积 progress + `LlamaNative.progress`；`TranslationPump` 轮询 120 ms；ViewModel 只更新字幕不写库 | 待编译；真机观感待确认 |
| 2a partial 限流 | 已写：`ComputeGate`，生成期间跳过 partial 解码（final 永不跳过） | 待编译。**注意**：为避免「改完就测不出」，跳过开关可在调试页运行时开关，用于 A/B |
| 2b 投机 prefill | 已写：`prefillSource` + 独立 speculative 槽（不挤占模板槽）+ `generate` 命中则跳过 prefill | 待编译；收益待实测 |
| 3 i8mm | 已写：`-PduiyiArmArch` 构建开关（默认 dotprod）；`setVerboseLogging` 让自检能确认 repack；启动日志打印**运行时** CPU 特性 | **已验证并已否掉**：repack 实测在用 `q4_K_8x4`；`kernel cpu features: dotprod=1 i8mm=1`（A510 也支持）；i8mm 构建实测生成慢 8–31%，默认保持 dotprod。见 §5.2 |
| 4 增量分段翻译 | 已写：`StablePrefixTracker`（+9 条单测）、`incrementalTranslation` 偏好、两个模块的设置项、`append` 语义 | 待编译；质量需真机 A/B，**默认关** |
| 5 专用小模型 | 未做（按决定推迟到有 A/B 数据之后） | — |

**两个刻意的设计取舍，需要知道：**

1. **`ComputeGate` 默认在生成期间跳过 partial 解码。** 代价是这段时间里另一方的「正在说…」实时原文会短暂停更（最长约一次生成，1–1.7 s）。换来的是 Gemma 不被抢核。调试页可以一键关掉再测一轮，直接对比 `gen` 时长。
2. **增量翻译默认关。** 打开后译文按小段追加、只增不改；质量风险见 §6。

---

## 10. 建议的执行顺序

**第 0 步：把预算测出来，而不是猜。** 工具已经在了，现在需要的是数据。

```bash
# 装包后平放在两人中间，正常聊 8–10 轮中英
adb shell am start -n com.duiyi.android/.debug.LatencyDebugActivity
adb pull /data/data/com.duiyi.android/files/latency-probe.txt
```

调试页显示每项的中位数与最近 12 轮的明细；文件里是每轮一行、可解析的 `key=value`。重点看四个数：

1. `speech→vad endpoint` —— VAD 端点静音（预期 ≈550 ms，是常数）
2. `final_decode` 与 `final_rtf` —— SenseVoice 整段解码耗时与实时率（**至今从未测过**）
3. `gen` 与 `partials_during_gen` —— 生成耗时，以及生成期间有多少次 partial 解码在抢核
4. `joiner_hold` —— 从 `ingest` 到 `flush` 的间隔（能直接暴露 §3.1 是否真的修好）

**抢核那一条要测两轮**：调试页顶部的开关能在运行时切换「生成期间跳过 partial 解码」。开一轮、关一轮，对比 `gen` 的中位数——差多少，就是 §5.3 的价值。这一条数据出来之前，不要去调 VAD 静音窗或 `HOLD_NS`。

**第 1 步：修 joiner 卡死 + 流式回字幕。** 已实现。验证方式：说一句不带标点的短句（「好的」「Thank you」），松口后译文应在约 1.4 s 内出现，而不是等下一句。`joiner_hold` 一栏应稳定在 1400 ms 附近。

**第 2 步：partial 限流 + 投机 prefill。** 已实现。前者用第 0 步的两轮对比来判值不值得留（`partials_skipped` > 0 且 `gen` 明显下降才留）；后者看 `llama-jni.log` 里的 `speculative prefill hit` 是否出现在多数句子上。

**第 3 步：i8mm 内核 —— 已做完，结论是不要用。** 真机实测生成慢 8–31%，默认保持 dotprod。过程与数字见 §5.2。结论是完整的（不是「没测出来」）：repack 本来就在跑、A510 支持 I8MM、换 8×8 内核没有收益。要复现：

```bash
./gradlew assembleDebug -PduiyiArmArch=armv8.6-a+dotprod+i8mm   # 对照默认构建
# 自检会自动开 ggml DEBUG，llama-jni.log 里可看到
#   "kernel cpu features: dotprod=1 i8mm=1"
#   "repack tensor ... q4_K_8x4"（dotprod）/ "q4_K_8x8"（i8mm）
#   "generate ... gen_ms=..."（对比这一项）
```

**第 4 步：增量分段翻译。** 已实现，默认关。在面对面/同传的设置里打开，用同一段长句（带逗号的连续 15 秒）对比两种模式的**首句译文出现时间**和**可读性**。质量退化明显就一直关着。

**第 5 步（选型，见 §5.4）**：如果以上都做完还是不够快，才考虑引入专用小翻译模型做短句分流（首选 `Hy-MT2-1.8B` 的 1.25-bit GGUF 或 `small100` 的 ONNX，两者许可证都干净，且 ONNX Runtime 已在包里）。这是最后的手段 —— 唯一会真正动到译文质量的选项，而且**公开资料里没有任何手机 CPU 上的 encoder-decoder 翻译实测延迟**，必须先用同一组真实对话句子跑出「延迟 + 可读性」再决定。

---

## 参考实现位置

| 主题 | 位置 |
| --- | --- |
| VAD 参数 / partial 频率 / 解码循环 | `services/speech/SherpaSpeech.kt:116-283, 358-376, 411-416` |
| joiner 超时（与唯一调用点） | `domain/routing/SpokenSegmentJoiner.kt:45-49`、`SherpaSpeech.kt:151` |
| 只译 final | `features/conversation/ConversationViewModel.kt:487`、`features/interpretation/InterpretationViewModel.kt:486` |
| 串行队列，不取消 in-flight | `services/translation/TranslationPump.kt` |
| 前缀快照 / 大核绑定 / 生成循环 | `app/src/main/cpp/llama_jni.cpp:224-256, 396-413` |
| 输出 token 上限 | `services/translation/TranslateGemmaPrompt.kt:111-117` |
| 构建参数（`-march`、repack） | `app/src/main/cpp/CMakeLists.txt`、`app/build.gradle.kts` |
| 真机自检 | `debug/TranslationSelfTestActivity.kt` |
