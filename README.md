# TalkingLive - 常驻后台的 Windows 语音输入工具

说一句唤醒词，你讲的话会**实时出现在任意应用的光标处**。它不要求你在某个特定软件里工作 ——
微信、浏览器、IDE、AI 对话窗口，文字直接进你正在用的那个程序。

完全本地、单进程、不联网：麦克风音频不出这台机器，转写在你的 CPU 上跑完。

**技术栈**

- **语言与运行时**：Java 21 + Swing / AWT（悬浮球与浮窗是自绘，不背 WebView、不背 Chromium）
- **语音引擎**：**Vosk**（离线、纯 CPU）。唤醒 / 结束词走「受限语法」，实时预览与整段落字**共用同一个模型** —— 保证你看到的就是你得到的
- **Win32 互操作**：JNA（`SendInput` 文本注入、扩展窗口样式、前台窗口监听、光标定位）
- **测试**：JUnit 5 单测 + **结构化自检**（`java.awt.Robot` 驱动真实鼠标的 UI 断言 + 端到端替身管线）
- **构建与打包**：Maven Wrapper（`mvnw`，只需 JDK）+ `jpackage`
- **界面观感**：自绘悬浮球 / 浮窗（声浪柱跟随实时声级）+ FlatLaf 浅色主题的设置窗口

---

## 📌 当前状态与版本

当前版本 **0.9.0**（对应 `pom.xml` 的 `<version>` 与 git tag `v0.9.0`）。

**为什么是 0.9 而不是 1.0** —— 功能已经完整可用，但**还没有在真实设备上跑完
`DESIGN.md` §9.3 的手工验收清单**（唤醒命中率 / 误触发率 / 真实语音 CER /
注入到各类目标程序 / 长时间运行内存）。按本项目自己的规矩，那些只能由**真人**在真机上
验证，不能靠自动化代替：

- 唤醒命中率、误触发率、真实语音 CER —— 需要真人对着麦克风说话并人工标注参考文本
- 「注入到记事本 / 浏览器 / IDE / 微信」—— 需要真实目标程序
- 长时间运行（≥ 2h）内存不增长 —— 需要长跑

`0.9` 如实表示「功能完成、验收未完成」这个位置。跑完 §9.3 之后就该是 1.0。
当前已知缺口见 `docs/IMPLEMENTATION-STATUS.md` §3.2 / §3.4；**尚未修的问题**见
`docs/PENDING-ISSUES.md`。

> **两个版本号序列，别混**：这里的 `0.9.x` 是**发布版本**，只在定版时更新；
> git 提交首行的 `[N.n.m]` 是**提交序号**，每个 commit 都会 +1（规则与「定版时要改哪几处」
> 见 `AGENTS.md`）。提交序号跑到 `0.9.7` 时，发布版本仍然可以还是 `0.9.0`。

---

## 🚀 快速启动指南

### 1. 准备语音模型（**必做，否则程序仍常驻但会明确提示不可用**）

模型不进版本库，统一放在 `%LOCALAPPDATA%\TalkingLive\models\`。

```powershell
$dir = "$env:LOCALAPPDATA\TalkingLive\models"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Invoke-WebRequest `
  -Uri 'https://hf-mirror.com/localstack/vosk-models/resolve/main/vosk-model-small-cn-0.22.zip' `
  -OutFile "$dir\vosk-model-small-cn-0.22.zip"
Expand-Archive "$dir\vosk-model-small-cn-0.22.zip" -DestinationPath $dir
```

> 官方站点 `alphacephei.com` 在国内实测约 **40 KB/s**；hf-mirror 实测约 **3.5 MB/s**（快约 87 倍）。
> 解压后 `graph/` 下**没有 `words.txt`**（词表编在 `Gr.fst` 二进制里），所以查词只能用
> `vosk_model_find_word()` API —— 见 `DESIGN.md` 附录 D.1。

装好大模型 `vosk-model-cn-0.22` 会自动生效（识别明显更准，代价是冷启动约 17–21 秒、
常驻内存约 2.4 GB）：获取路径与实测数据见 `docs/ENGINE-EXPERIMENT.md`。

### 2. 一键启动

**最省事的方式：双击 `run.cmd`** —— 它会自己找 JDK 21、必要时自动构建、然后静默启动悬浮球
（走 `TalkingLive.vbs`，桌面上不留黑窗口）。

```powershell
.\run.cmd                # 常驻后台，桌面上只有一颗悬浮球
.\run.cmd --mic-test     # 麦克风实测（15 秒，实时音量条）—— 建议第一次先跑这个
.\run.cmd --settings     # 启动并打开设置窗口
.\run.cmd --doctor       # 环境自检后退出
```

### 3. 第一次先跑麦克风实测

```powershell
.\run.cmd --mic-test
```

它会把「采音没进来」和「识别不准」这两种完全不同的问题当场分开：屏幕上有实时音量条、
实时预览文字与唤醒命中计数。对着麦克风说几句，只要有音量条就说明链路是通的。

### 4. 手动构建与运行（备选）

需要 **JDK 21**；Maven 用 wrapper（`mvnw`），无需单独安装。

```powershell
.\mvnw.cmd package        # 编译 + 跑全部单测
.\mvnw.cmd test           # 只跑单测
.\mvnw.cmd -Pdist package # jpackage 产出带运行时的 app image（target/dist）
```

```powershell
java -jar target\talkinglive.jar                    # 常驻后台，桌面上只有一颗悬浮球
java -jar target\talkinglive.jar --settings         # 启动并打开设置窗口
java -jar target\talkinglive.jar --refiner none     # 关闭精化（用预览文本直接注入）
java -jar target\talkinglive.jar --no-microphone    # 不打开麦克风（无设备时试界面用）
```

> ⚠️ **`JAVA_HOME` 必须指向 JDK 21**（本机为 `D:\Code\Java\jdk-21.0.12.1`）。
> Maven Wrapper 优先读 `JAVA_HOME`，指向 JDK 8 会直接构建失败。
> 注意：**已经打开的终端**里 `JAVA_HOME` 是启动时的旧值，改过环境变量后要重开终端。
>
> 需要看控制台输出时用 `tools\run-console.cmd`（正常启动是不留控制台的）。

---

## 💡 功能模块与使用指南

产品**没有主窗口** —— 你永远不会去「打开」它，所有操作都从悬浮球发起，或由语音触发。

### 1. 悬浮球 —— 唯一入口与状态指示

桌面上常驻一颗小球，**颜色即状态**：

| 外观 | 含义 |
|---|---|
| 灰色 + 极小的闲置起伏 | 待唤醒 |
| 红色 + 脉冲 | 听写中 |
| 橙色 | 提交中（正在精化 / 落字） |
| 暗灰 + 斜杠 | 已暂停 |
| 中间五根声浪柱 | 跟随实时声级，**待唤醒时也会动**（这样球始终是「活的」） |

**操作**：

- **左键**：待唤醒时＝开始听写；听写中＝结束本段
- **右键**：菜单 —— 手动开始 / 结束听写 · 暂停监听 / 恢复监听 · 设置... · 查看日志 · 退出
- **拖动**：移动位置（会自动夹在屏幕可见范围内）
- **贴边收起**：拖到屏幕左右边缘 40px 内松手 → 吸附到该侧、只露一小条；鼠标移到露出部分自动滑出，移开约 450ms 后收回

> 悬浮球**不抢焦点**（`WS_EX_NOACTIVATE` + `WS_EX_TOOLWINDOW`），所以它既不会打断你正在
> 输入的程序，也不会出现在 Alt+Tab 与任务栏里。

### 2. 语音听写主链路：说 → 看 → 落字

1. 在任意应用里把光标放到你想输入文字的位置
2. 说唤醒词（默认「**子曰**」，可在设置里改）→ 悬浮球变红，光标附近出现**浮窗预览条**
3. 正常说话，文字**实时出现在浮窗里** —— 此时目标程序完全不受影响
4. 说结束词（默认「**到此为止**」）、**静音满 5 秒**、或按**左键**结束本段 → 悬浮球转橙，进入提交
5. 整段文字一次性注入光标处；若开了自动发送，再替你按一次 Enter（默认关闭）

> 想反悔就按 **Esc**：整段取消，**一个字都不会打出去**（这是取消，与上面的「结束本段」是两件事）。

**几个刻意的设计**：

- **浮窗预览条不抢焦点**，且文字分两级样式：**已稳定的前缀用实色、仍在变动的尾部弱化** —— 你能一眼看出哪部分还会被改
- **整段注入，不碰剪贴板**：用 `SendInput` + `KEYEVENTF_UNICODE`，你的剪贴板不会被污染
- **Esc 是全局键**：目标应用在前台也有效，按下即整段取消，**一个字都不会打出去**
- **切窗口不会丢话**：提交时若前台已变，注入器会先尝试把焦点还原回原目标；还原不了就注入到当前焦点并**明确提示**「文字没打进原本的目标窗口」
- **英语唤醒词的做法**：中文模型的词表里没有拉丁 token，所以把英语发音写成汉字、**逐字拆开**用 —— 例如想要 `Firay` 的音，就填「**飞瑞**」（实测可稳定命中，见 `ENGINE-EXPERIMENT.md` §7.5）
- **热词纠正**：识别总把 `AI` 拆成 `A I` 时它会自动拼回；说的词与想要的写法没有字符级关系时（例如说「诶爱」想要 `AI`），在配置文件的 `hotwords` 里写 `诶爱=AI`

> **日志不记转写内容**：凡面向日志的文本都只记长度 + 指纹（`Logging.describe*`）。
> 需要排查识别质量时才临时开 `-Dtalkinglive.log.text=true`（`tools\run-diagnose.cmd`）。

### 3. 设置窗口 —— 一屏五行，改完即生效

从悬浮球右键「设置...」进入。刻意只放**每天会改的东西**，没有页签、没有卡片：

| 项 | 说明 |
|---|---|
| 唤醒词 | **必须在该模型词表内**（行尾有对勾 / 叉号图标即时校验）。不在表内 Vosk 会**静默忽略**它，不提示就会变成「改了没反应」；整词不在表内但每个字都在时，可以逐字拆开用，界面会给警告色提示（见下面「英语唤醒词的做法」） |
| 结束词 | **可以留空** —— 留空表示不用它收尾，段落改由静音或切窗口结束 |
| 静音超时 | 可选 0 / 2 / 3 / 5 / 8 / 10 秒（默认 5）；**0 = 关闭**。关掉后段落只靠结束词、悬浮球左键或切窗口收尾 |
| 自动发送 | 默认关闭；开启后可选 Enter / Ctrl+Enter |
| 发送键 | 聊天软件想换行就用 Ctrl+Enter |

改动**即时生效、无需保存**，界面上会显示「修改已保存并立即生效」。

> 注入间隔（`charGapMillis`）、单段上限（`maxSegmentSeconds`）、热词表（`hotwords`）是
> 「改一次就不动」的参数，只在 `config.json` 里改（路径显示在设置页页脚）。
> **改完请重启程序** —— 运行中的实例会把内存里的配置写回，手改的内容会被覆盖。

### 4. 状态与诊断窗口

悬浮球右键「**查看日志**」打开，三个页签：

- **状态**：模型（在用小模型还是大模型、为什么）、词表校验结果、麦克风、注入器、精化引擎各就绪情况
- **日志**：滚动日志（5MB × 3），明确用 UTF-8 读，不会乱码
- **自检**：一键跑结构化自检并显示报告

### 5. 自检、基准与手工验证工具

下一节列全了这些命令：环境自检（`--doctor`）、完整自检（`--self-check`）、
引擎基准（`EngineBench`，回填性能预算）、引擎冒烟（`EngineSmoke`，守最容易出问题的原生层）、
以及注入链路的手工验证（`SampleInjector`）。它们把「交付前必须验证的事」做成了可复现的一步。

---

## 📋 自检与诊断

**这是本项目最该先跑的东西** —— 它把「交付前必须验证的事」做成了可复现的一步。
`mvnw package` 会把运行时依赖复制到 `target/lib` 并写进 jar 清单的 `Class-Path`，
所以下面的命令直接 `java -jar` 即可。

```powershell
# ① 环境自检：模型 / 词表校验 / 麦克风 / 注入器 / 配置往返 / 端到端管线（不开 UI，不碰鼠标）
java -jar target\talkinglive.jar --doctor

# ② 完整自检：会开 UI 并真的移动鼠标右键悬浮球（需要真实桌面，会短暂占用鼠标）
java -jar target\talkinglive.jar --self-check

# ③ 引擎基准：回填 DESIGN.md §6 的性能预算（模型加载、内存、离线 RTF）
java -cp "target\talkinglive.jar;target\lib\*" com.talkinglive.engine.EngineBench

# ④ 引擎冒烟：Vosk 原生库加载 + 词表查询 + 受限语法（最容易出问题的一环）
java -cp "target\talkinglive.jar;target\lib\*" com.talkinglive.engine.EngineSmoke

# ⑤ 注入链路手工验证：倒计时后把样例文字真的打进你指定的程序
#    （DESIGN.md §9.3 清单第 4 条：记事本 / 浏览器 / IDE / 微信都要试）
java -cp "target\talkinglive.jar;target\lib\*" com.talkinglive.SampleInjector

# ⑥ 单元测试
.\mvnw.cmd test
```

自检报告里的「**真实路径**」一节值得单独说：它通过 `App` 的探针走**真实链路**
（真实配置热更新、真实唤醒检测器重建、真实按键、真实提示窗口），而不是替身 ——
因为此前最被信任的三条自检恰好都只测了替身，于是「界面说生效、实际没生效」照样全绿。

**报告与数据落盘**（全部 UTF-8；控制台只输出 ASCII 摘要，因为 Windows 控制台默认 GBK
会让中文乱码并**掩盖真正的失败信息** —— 这是 `DESIGN.md` §9.2 踩过的坑）：

```
%LOCALAPPDATA%\TalkingLive\
├── doctor-report.txt       # --doctor 报告
├── selftest-report.txt     # --self-check 报告
├── engine-bench.txt        # EngineBench 报告
├── config.json             # 配置（启动时强制校验）
├── logs\talkinglive.log    # 滚动 5MB×3，不记转写内容
├── recordings\             # 音频落盘（M1 用）
└── models\
    └── vosk-model-small-cn-0.22\   # 唤醒/结束词 + 实时预览
```

---

## 🏗️ 架构分层（重要，且**被测试强制**）

`core` / `text` 与各引擎接口**不依赖 AWT / JNA / 引擎原生库**，
以便在无麦克风、无桌面的环境下跑单元测试。这是本项目测试策略能成立的前提
（`DESIGN.md` §4.5）。

这条约束不是靠约定，而是由 `ArchitectureTest` **扫源码强制**：
往 `core` 里 import 一个 `java.awt` 类就会让构建失败。

```
com.talkinglive
├── App                     装配与启动（唯一有 main 的产品类）
├── SelfTest                结构化自检（UI Robot 自检 + 端到端管线）
├── core                    StateMachine / AppConfig / AppPaths / DictationSession
│                           / JsonCodec / Logging / WordSuggestions        ← 纯逻辑
├── text                    CommitPolicy / TextPostProcessor / PreviewText
│                           / TextUtils / TextInjector(接口)                ← 纯逻辑
├── audio                   AudioCapture（单路采集 + 分发）/ AudioConverter
│                           （显式重采样）/ SilenceDetector / WavFile
├── engine                  WakeWordDetector / SpeechRecognizer / TextRefiner（接口）
│                           + VoskModel / VoskKeywordDetector
│                           / VoskSpeechRecognizer / TextRefiners（实现）
├── system                  WindowsTextInjector / ForegroundWatcher / CaretTracker
│                           / Win32 / Win32WindowStyles / DpiScale
│                           / VoskNative / VoskNativeLoader / MicValidator
│                           / EscapeWatcher（全局 Esc → 取消本段）
└── ui                      FloatingBall / PreviewBar / SettingsWindow / Toast
                            / DiagnosticsWindow / Theme / Icons
```

### 三条必须遵守的工程约束

1. **麦克风只开一路**（`DESIGN.md` §4.3）：唤醒检测与预览识别共用同一个
   `TargetDataLine`，由 `AudioCapture` 分发。禁止各自开设备 —— Windows 上会互相抢设备。
2. **所有跨边界坐标换算只经 `system.DpiScale`**（`TECH-PLAN` §7 第 3 项）：
   Win32 用**物理像素**、Java 用**逻辑像素**，禁止在任何地方混用
   `Robot.mouseMove` 与 `SetCursorPos`。
3. **日志不记转写内容**（`DESIGN.md` §3.1 第 12 项）：任何面向日志的文本都必须过
   `Logging.describe*`（只记长度 + 指纹）。

### 三条踩过坑的路基（改之前请先看）

1. **Vosk 的中文必须走本仓库自己的 JNA 绑定**，不能用官方 `org.vosk`：
   官方绑定用平台编码（中文 Windows 上是 GBK）编码字符串，
   中文语法会直接崩在原生层。详见 `system/VoskNative` 的类注释。
2. **受限语法是纯 JSON 数组**，不是 `{"phrase_list": [...]}`（后者是 Python 绑定的壳）。
3. **不抢焦点必须在窗口第一次显示之前设置 Win32 扩展样式**
   （`Win32WindowStyles`），只在 Swing 侧设 `setFocusableWindowState(false)` 不够。

> 还有一条同样值钱的经验：**自检里最被信任的那几条，往往恰好没覆盖真实路径**。
> 0.9.2 / 0.9.4 修掉的一批缺陷（Esc 取消、切窗口丢话、改配置不生效）都属于这一类 ——
> 详见 `DESIGN.md` 修订记录 1.15。

---

## 📚 想深入看哪份文档

| 文档 | 作用 |
|---|---|
| `docs/IMPLEMENTATION-STATUS.md` | **现在能干什么、不能干什么**（先看这个） |
| `docs/PENDING-ISSUES.md` | **已知但尚未修的问题**：按优先级降序，每条给位置 / 后果 / 修法 / 成本 |
| `docs/DESIGN.md` | 产品设计（要做什么），含交互清单、异常与降级、手工验收清单 |
| `docs/TECH-PLAN.md` | 技术方案（用什么实现；与设计冲突时以它为准） |
| `docs/ENGINE-EXPERIMENT.md` | 精化引擎落地报告：实测数据、SenseVoice 不可直接依赖的确认、未测项 |
| `docs/RETIRED-TOOLS.md` | 已退休的诊断工具：当初解决什么问题、下次怎么重建 |
| `tools/README.md` | 诊断入口与启动脚本一览 |
| `demo/` | P0 交互原型（纯模拟数据，已完成的里程碑） |
