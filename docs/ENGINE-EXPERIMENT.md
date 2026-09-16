# 精化引擎落地报告（ENGINE-EXPERIMENT）

> 版本 0.1 ｜ 状态：**部分实测，关键前提已确认，其余待真实麦克风**
>
> 本文是 `TECH-PLAN.md` §6.7 要求的交付物。原文要求产出一次「约半天的精化引擎验证实验」，
> 但本次实现过程中**一个关键前提被提前证伪**，因此本文的结论与 §6 的预期不同。
> 凡是**没有实测**的项，本文一律标注「未测」并说明为什么——不含推测出来的数字冒充实测。

---

## 1. 一句话结论

> **`TECH-PLAN` §1.1 定的方案 C（精化引擎 = SenseVoice / sherpa-onnx）在当前环境下
> 无法按原样落地**：sherpa-onnx 没有发布到 Maven Central 的 Java 绑定，
> 也没有 Windows x64 预编译产物。因此 MVP 的精化位采用**可插拔 + 明确降级**：
> 当前装的是 `TextRefiners.VoskOffline`（Vosk 整段离线重跑），
> 而 `TextRefiner` 接口、状态机、UI、注入路径**一行都没改**——这正是当初留这个接口的价值。

这条结论同时**关闭了 `TECH-PLAN` 附录 A.5 的最后一项**（原文：「❌ 未确认，需在 M3 前确认」，
并注明「这是本方案剩余的最大技术不确定性」）。

---

## 2. 前提核验：sherpa-onnx 与 SenseVoice 的可获取性

| 项 | 结论 | 依据 |
|---|---|---|
| `com.k2fsa.sherpa.onnx:*` 在 Maven Central | ❌ **不存在**（404） | 直接查仓库目录 |
| sherpa-onnx 官方 Java 绑定 | ⚠️ 存在于源码树 `sherpa-onnx/java-api/`，**但需自行构建**，且 JNI 层要自编译 | 官方文档「Non-Android Java」要求先 `cmake` 构建原生库 |
| Windows x64 预编译原生产物（jar 内） | ❌ **没有**（这是真正的阻塞点） | 上游只发 Android/iOS/Python/C++ 等产物；JVM 侧靠 JitPack 或本地构建 |
| JitPack 上的 sherpa-onnx | ✅ 可达（HTTP 200），但产物取决于上游构建脚本，**不保证含 Windows x64 DLL** | 不可作为 MVP 的可靠依赖 |
| SenseVoice ONNX 模型本身 | ✅ **可获取**：`model.int8.onnx` 239 MB + `tokens.txt` 316 KB，hf-mirror 实测可下载 | 见 §6 的下载地址 |

**判读**：模型侧没问题（239 MB int8 可下载），**问题全在 Java 绑定/原生产物这一侧**。
`sherpa-onnx` 的 JVM 支持要求使用者自己拿 C++ 工具链构建 JNI 动态库——
这与 `TECH-PLAN` §3.1「零环境依赖」的红线直接冲突（要求用户具备 MSVC + CMake 才能装这个软件）。

> 注意这条与 §5.4 的原判断有出入：原文写「二者理论上有 Windows x64 预编译产物，
> 无自编译 C++ 环节；**但需确认**」。**确认结果是没有**。

---

## 3. 实测数据（本机，2024 环境）

**环境**：Windows 11 家庭版中文版；Intel Core i7-12650H（16 逻辑核）；JDK 21.0.12.1；
模型 `vosk-model-small-cn-0.22`（解压后 65 MB）；`libvosk.dll` 25 MB。
**工具**：`java -cp ... com.talkinglive.engine.EngineBench`（本仓库自带，可复跑）。
**完整原始报告**：`%LOCALAPPDATA%\TalkingLive\engine-bench.txt`。

| 指标 | 实测值 | 对应 `DESIGN.md` §6 目标 | 判读 |
|---|---|---|---|
| 模型加载耗时 | **1253 ms** | 冷启动 < 3s（含模型加载） | ✅ 达标 |
| 常驻堆内存（模型加载后） | **3 MB** | 空闲内存 < 500MB | ✅ 远超预期（模型是原生内存，不在 Java 堆） |
| 峰值堆内存（跑完全部测量） | **3 MB** | 听写中内存 < 1.5GB | ✅ |
| 词表查询耗时 | **0.007 ms/次** | —（仅启动校验用） | ✅ 完全可忽略 |
| 创建流式识别器 | **111 ms/次** | — | ⚠️ 每段精化都要新建一次，见 §4 讨论 |
| 创建受限语法识别器 | **1 ms** | — | ✅ 只在启动与改配置时一次 |
| 磁盘占用（Vosk 模型 + DLL） | **65 + 25 MB** | 见 §6 磁盘预算 | ✅ |
| 整段离线重跑 1s 合成音频 | 551 ms（RTF 0.551） | 提交延迟 < 2.5s | ⚠️ 短音频固定开销占比高 |
| 整段离线重跑 5s 合成音频 | **1207 ms（RTF 0.241）** | 提交延迟 < 2.5s | ✅ 达标 |
| 整段离线重跑 15s 合成音频 | 7323 ms（RTF 0.488） | 提交延迟 < 2.5s | ⚠️ 15s 段落会超预算 |

### 3.1 「合成音频」口径说明（重要）

上表的识别吞吐用的是**合成音频**（缓慢起伏的正弦），因此：
- **只反映解码算力开销**，不代表真实语音的识别质量、CER、标点质量。
- 合成音频没有真实的语音结构，解码器的搜索空间与真人说话不同，
  所以 RTF 只能当**数量级参考**，不能当准确曲线。

真实语音的 RTF 与 CER 需要**真人录音**，属 `DESIGN.md` §9.3 的手工验证，本环境无麦克风（见 §5）。

### 3.2 与 `TECH-PLAN` §4.2 推算的对照（这是本次最有价值的一条数据）

| 引擎 | 5 秒音频纯 CPU 耗时 | 来源 |
|---|---|---|
| whisper-small | 推算 **12–15 s** | `TECH-PLAN` §4.2（外部实测 + 内核折算） |
| **Vosk 小模型（本次实测）** | **1.21 s** | 本文 §3 |
| SenseVoice（目标） | 推算 **约 0.5 s** | `TECH-PLAN` §4.3（外部实测） |

**判读**：`TECH-PLAN` §4 关于「whisper 纯 CPU 不可行」的结论得到**侧面印证**——
即便是比 whisper-small 小得多的 Vosk 小模型，纯 CPU 也要 1.2 秒/5 秒音频；
whisper-small 比它大一到两个数量级，推算 12–15 秒是合理的。
但同时也说明：**当前回退方案（Vosk 离线重跑）能守住 §6 的 2.5s 预算**，
不需要破 `TECH-PLAN` §3.1 的「不依赖核显」红线。

---

## 4. 方案 C 的落地路径（待办）

`sherpa-onnx` 不可直接依赖，因此精化的替代路径有两条，**都需要决策**：

### 路径 1：改用 onnxruntime + 自写 SenseVoice 前处理（推荐）

- 依赖 `com.microsoft.onnxruntime:onnxruntime`（**Maven Central 有，且带 Windows x64 原生库**）
- 自己实现：80 维 log-mel 滤波器组（10ms 帧移）→ `model.int8.onnx` 推理 → CTC 贪心解码 → `tokens.txt` 解码
- 优点：零自编译、纯 CPU、与方案 C 的目标一致（RTF ≈ 0.1）
- 成本：前处理与解码要自己写并验证（这是 §6 那半天的实验内容）
- **风险**：SenseVoice 的 ITN 与标点是与模型绑定的后处理，自写解码需确认能拿到同样效果

### 路径 2：自编译 sherpa-onnx JNI

- 优点：直接用官方 API
- 缺点：**要求使用者装 MSVC + CMake**，与「零环境依赖」红线冲突；分发体积也大
- 结论：**不推荐**，除非将来愿意把原生产物随包分发（那就变成了构建流水线的活）

**接口接入成本**：两条路径都只需新增一个 `TextRefiner` 实现并把
`App.createRefiner` 指向它。`TextRefiner` 接口、状态机、`CommitPolicy`、
注入路径、UI **全部无需改动**——`TECH-PLAN` §5.1「替换点只有一处」在本仓库里
**已经被代码结构证明**（见 `src/main/java/com/talkinglive/App.java` 的 `createRefiner`）。

---

## 5. 未测项（必须由真实设备补齐）

以下各项**本次没有测**，原因是**本机没有可用录音设备**
（`--doctor` 报告：`麦克风 不可用：系统没有可用的录音设备`）。
它们属 `DESIGN.md` §9.3「手工集成验证」，需要麦克风 + 真人说话：

| 项 | 状态 | 说明 |
|---|---|---|
| 唤醒响应 < 300ms | **未测** | 需要真人说「子曰」 |
| 首字延迟 < 800ms | **未测** | 需要真人连续说话 |
| 唤醒命中率 ≥ 9/10 | **未测** | §9.3 清单第 1 条 |
| 10 分钟误唤醒 = 0 | **未测** | §9.3 清单第 2 条 |
| 真实语音的 CER | **未测** | 需要 §6.2 要求的 6–8 段人工标注语料 |
| 真实语音的 RTF | **未测** | 上表的 RTF 来自合成音频 |
| 标点质量 | **未测** | 当前精化路径（Vosk）**本身不产标点**，只补句末标点 |
| ITN 是否改错字 | **不适用** | 当前精化路径没有 ITN；SenseVoice 接入后才需要测 |
| 段落音频含首尾静音时的表现 | **未测** | 需真实段落音频 |
| 是否会把唤醒词「子曰」转进正文 | **已做兜底** | 未实测引擎行为，但 `PunctuationProcessor` 已按精确子串强制剔除，并有单测 |
| 注入到真实应用的成功率 | **未测** | 需要真人在记事本/浏览器/IDE/微信里试 |

> **怎么补齐**：把 6–8 段 16kHz 单声道 wav 放到任意目录，然后跑
> ```
> java -cp "target\classes;target\lib\*" com.talkinglive.engine.EngineBench <模型目录> <你的.wav...>
> ```
> 它会为每个 wav 输出耗时与 RTF，并把识别结果写进报告（只记长度与指纹，不记内容）。

---

## 6. 模型获取（回填 `DESIGN.md` 附录 D.2）

### D.2-新：SenseVoice（待接入）

```
# 模型（int8 量化，239 MB）与词表
https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx
https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt
```

放置位置：`%LOCALAPPDATA%\TalkingLive\models\sense-voice\`

### 当前实际使用：Vosk 小模型

与 `DESIGN.md` 附录 D.1 一致，无需变更：

```
https://hf-mirror.com/localstack/vosk-models/resolve/main/vosk-model-small-cn-0.22.zip
```

实测下载 **43.9 MB / 5.4 秒**（hf-mirror），解压后 65 MB。
解压后目录结构符合 D.1 的描述（`am/ conf/ graph/ ivector/`，`graph/` 下**没有** `words.txt`）。

---

## 7. 顺带确认的两条工程事实（原文档列为「未验证」）

### 7.1 词表查询的确切语义（附录 C 的落地依据）

`vosk_model_find_word` 的 C 签名是 **`int vosk_model_find_word(VoskModel*, const char*)`**，
返回**词 id**，**不在词表内返回 -1**。实测结果与 `DESIGN.md` 附录 B.1 的记录**逐条吻合**：

| 词 | 实测 wordId | 附录 B.1 记录 |
|---|---|---|
| 子曰 | **98204** | 98204 ✅ |
| 小助手 | 29194 | 29194 ✅ |
| 到此为止 | **87877** | 87877 ✅ |
| 结束 | 58069 | 58069 ✅ |
| 完毕 | 27351 | 27351 ✅ |
| 输入 | 11732 | 11732 ✅ |
| 本段结束 | **-1**（不在表内） | ❌ 不在表内 ✅ |
| 小秘书 / 好了 / 结束输入 | -1 | ❌ 不在表内 ✅ |

这条同时说明一件事：**把该函数声明成返回指针会得到完全错误的结果**——
id 数值会被当成地址，于是「任何词都像在词表内」，附录 C 的静默失效完全拦不住。
本仓库用 JNA 自己声明，并有一条冒烟自检专门守这个（`EngineSmoke`）。

### 7.2 受限语法的确切格式（踩过两次坑）

`vosk_recognizer_new_grm` 接受的是**纯 JSON 字符串数组**：

```json
["子曰","到此为止","[unk]"]
```

**不是** `{"phrase_list":[...]}`。后者是 Vosk **Python 绑定**的包装，它自己会把列表拆出来
再交给 C API（见 `kaldi_recognizer.cc`：`json::JSON::Load(grammar)` 之后直接 `obj.length()` / `obj[i]`）。
传对象进去的实测症状：

```
WARNING (VoskAPI:UpdateGrammarFst():recognizer.cc:283)
  Expecting array of strings, got: '{"phrase_list":["子曰","到此为止","[unk]"]}'
java.lang.Error: Invalid memory access
    at org.vosk.LibVosk.vosk_recognizer_new_grm(Native Method)
```

另外，**官方 Java 绑定不能用于中文**：它用 JNA 默认的**平台编码**加载原生库，
中文 Windows 上是 GBK，于是中文语法被编成 GBK 字节交给按 UTF-8 解释的 Vosk，
报的是同一行 `Expecting array of strings, got: '{"phrase_list":["??","????","[unk]"]}'`。
解决办法是自己声明 JNA 接口并在加载时指定 `OPTION_STRING_ENCODING=UTF-8`
（见 `system/VoskNative` 与 `system/VoskNativeLoader`）。

### 7.3 「大模型不支持运行时词表」有了代码级判据

`DESIGN.md` 附录 B.2 的结论（只有小模型支持运行时改词表）在 C 侧对应的判据是：
**模型是否同时加载了 `graph/HCLr.fst` 与 `graph/Gr.fst`**。
只有二者都存在时，`KaldiRecognizer(model, rate, grammar)` 才会真的用语法重建解码图；
否则它只打一行 `Runtime graphs are not supported by this model` 然后**忽略语法继续跑完整词表**——
唤醒词检测会悄悄失效。本仓库把这个判据实现成了
`VoskModel.supportsRuntimeGrammar()`，并在构造唤醒检测器时**硬校验**。

---

## 8. ITN 取舍（`TECH-PLAN` §8 #2）

- **当前精化路径（Vosk 离线重跑）没有 ITN**，因此不存在「改字」风险。
- `AppConfig.itn` 字段已存在（默认 `true`），但**当前没有引擎消费它**——
  这是刻意的：不写一个「看起来生效其实没有」的开关。
- SenseVoice 接入后再按 §6.3 的方法测「数字与英文是否被规整成错误内容」，
  然后决定开/关/按场景。

---

## 9. 对 `DESIGN.md` 的回填结果

见 `DESIGN.md` §6、§4.2、§3.1 第 7/11 项、§7、§8、附录 D.2 的修订
（本次已按 `TECH-PLAN` §9 的修订清单逐条落实，并在 `DESIGN.md` 修订记录里留痕）。

---

## 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 0.1 | 本次 | 首版：确认 sherpa-onnx Java 绑定不可直接依赖（关闭 TECH-PLAN 附录 A.5 最后一项）；实测 Vosk 模型加载/内存/离线 RTF；确认词表查询语义与受限语法格式（附录 B.1 逐条吻合）；列出未测项与两条落地路径 |
