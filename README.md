# TalkingLive

常驻后台的 Windows 语音输入工具。说唤醒词后，你讲的话会实时出现在**任意应用**的光标处。

| 文档 | 作用 |
|---|---|
| `docs/IMPLEMENTATION-STATUS.md` | **现在能干什么、不能干什么**（先看这个） |
| `docs/DESIGN.md` | 产品设计（要做什么） |
| `docs/TECH-PLAN.md` | 技术方案（用什么实现；与设计冲突时以它为准） |
| `docs/ENGINE-EXPERIMENT.md` | 精化引擎落地报告：实测数据、未测项 |
| `demo/` | P0 交互原型（纯模拟数据，已完成的里程碑） |

---

## 构建

需要 **JDK 21**。Maven 用 wrapper（`mvnw`），无需单独安装。

```powershell
.\mvnw.cmd package        # 编译 + 跑全部单测（246 项）
.\mvnw.cmd test           # 只跑单测
.\mvnw.cmd -Pdist package # jpackage 产出带运行时的 app image（target/dist）
```

> ⚠️ `JAVA_HOME` 必须指向 JDK 21（本机为 `D:\Code\Java\jdk-21.0.12.1`）。
> Maven Wrapper 优先读 `JAVA_HOME`，指向 JDK 8 会直接构建失败。
> 注意：**已经打开的终端**里 `JAVA_HOME` 是启动时的旧值，改过环境变量后要重开终端。

## 运行

```powershell
.\mvnw.cmd package

java -jar target\talkinglive.jar                    # 常驻后台，桌面上只有一颗悬浮球
java -jar target\talkinglive.jar --settings         # 启动并打开设置窗口
java -jar target\talkinglive.jar --refiner none     # 关闭精化（用预览文本直接注入）
java -jar target\talkinglive.jar --no-microphone    # 不打开麦克风（无设备时试界面用）
```

## 自检与诊断

**这是本项目最该先跑的东西**——它把「交付前必须验证的事」做成了可复现的一步。
`mvnw package` 会把运行时依赖复制到 `target/lib` 并写进 jar 清单的 `Class-Path`，
所以下面的命令直接 `java -jar` 即可。

```powershell
# ① 环境自检：模型 / 词表校验 / 麦克风 / 注入器 / 配置往返 / 端到端管线（不开 UI，不碰鼠标）
java -jar target\talkinglive.jar --doctor

# ② 完整自检：会开 UI 并**真的移动鼠标右键悬浮球**（需要真实桌面，会短暂占用鼠标）
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

报告落盘（全部 UTF-8；控制台只输出 ASCII 摘要，因为 Windows 控制台默认 GBK 会让中文乱码
并**掩盖真正的失败信息**——这是 `DESIGN.md` §9.2 踩过的坑）：

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

## 模型

模型**不进版本库**，统一放在 `%LOCALAPPDATA%\TalkingLive\models\`。见 `docs/DESIGN.md` 附录 D。

Vosk 中文小模型（必须，否则程序仍常驻但会明确提示不可用）：

```powershell
$dir = "$env:LOCALAPPDATA\TalkingLive\models"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Invoke-WebRequest `
  -Uri 'https://hf-mirror.com/localstack/vosk-models/resolve/main/vosk-model-small-cn-0.22.zip' `
  -OutFile "$dir\vosk-model-small-cn-0.22.zip"
Expand-Archive "$dir\vosk-model-small-cn-0.22.zip" -DestinationPath $dir
```

> 官方站点 `alphacephei.com` 在国内实测约 40 KB/s；hf-mirror 实测约 3.5 MB/s。
> 解压后 `graph/` 下**没有 `words.txt`**（词表编在 `Gr.fst` 二进制里），
> 所以查词只能用 `vosk_model_find_word()` API——见 `DESIGN.md` 附录 D.1。

精化引擎（SenseVoice）的模型与落地现状见 `docs/ENGINE-EXPERIMENT.md`。

---

## 架构分层（重要，且**被测试强制**）

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
└── ui                      FloatingBall / PreviewBar / SettingsWindow / Theme
```

### 三条必须遵守的工程约束

1. **麦克风只开一路**（`DESIGN.md` §4.3）：唤醒检测与预览识别共用同一个
   `TargetDataLine`，由 `AudioCapture` 分发。禁止各自开设备——Windows 上会互相抢设备。
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
