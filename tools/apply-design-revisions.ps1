# 一次性脚本：按 TECH-PLAN §9 的修订清单更新 docs/DESIGN.md
# 之所以用脚本而不是逐个调用编辑工具：这些行含全角字符与制表框线字符，
# 用字符串精确替换更可靠，也便于在修订记录里说明「批量落实了哪几项」。
$ErrorActionPreference = 'Stop'
$path = (Resolve-Path 'docs\DESIGN.md').Path
$enc = New-Object System.Text.UTF8Encoding($false)
$text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
$changed = @()

function Replace-Once {
    param([string]$old, [string]$new, [string]$label)
    $script:text = $script:text
    if (-not $script:text.Contains($old)) {
        Write-Output "  [SKIP] $label  (未找到目标文本)"
        return
    }
    $i = $script:text.IndexOf($old)
    $script:text = $script:text.Substring(0, $i) + $new + $script:text.Substring($i + $old.Length)
    $script:changed += $label
    Write-Output "  [OK]   $label"
}

# ---- #1/#2/#3 已在上一步完成（§4.2 分工表 / §4.1 管线图 / §3.1 第 7 项）
$changed += '§4.2 双引擎分工（已完成）'
$changed += '§4.1 处理管线图（已完成）'
$changed += '§3.1 第 7 项 转写精化引擎（已完成）'

# ---- #4 §3.1 第 11 项：标点主力改为 SenseVoice 原生标点（ITN）
Replace-Once `
  -old '| 11 | 标点 | whisper 原生标点为主力；预览阶段辅以停顿启发式 |' `
  -new '| 11 | 标点 | **SenseVoice 原生标点为主力**（`use_itn=1`）；预览阶段辅以停顿启发式。`PunctuationProcessor` 职责下调为「数字规整与清理」，不再是标点主力（TECH-PLAN §5.3） |' `
  -label '#4 §3.1 第 11 项 标点职责'

# ---- #5 §3.4 接口伏笔：TextRefiner 的 MVP 实现改为 SenseVoiceRefiner
Replace-Once `
  -old '| `TextRefiner` | `WhisperRefiner`（whisper.cpp） | 更大模型 / 云端引擎 |' `
  -new '| `TextRefiner` | `TextRefiners.SenseVoice`（sherpa-onnx）——**当前因原生产物不可得而降级为 `TextRefiners.VoskOffline`**，见 `ENGINE-EXPERIMENT.md` | 更大模型 / 云端引擎 |' `
  -label '#5a §3.4 TextRefiner 的 MVP 实现'

Replace-Once `
  -old '| `TextPostProcessor` | `PunctuationProcessor` | `HotwordCorrector`（热词纠错） |' `
  -new '| `TextPostProcessor` | `PunctuationProcessor`（职责下调：数字规整与清理，**不是标点主力**） | `HotwordCorrector`（热词纠错） |' `
  -label '#5b §3.4 TextPostProcessor 职责说明'

# ---- #6 §5 关键技术决策表 · 识别引擎行
Replace-Once `
  -old '| 识别引擎 | 小 Vosk（关键词检测）+ whisper（转写） | 见 §4.2 | 纯 Vosk；纯 whisper |' `
  -new '| 识别引擎 | 小 Vosk（关键词检测 + 预览）+ SenseVoice（精化） | 见 §4.2 | whisper.cpp；纯 Vosk；纯 whisper |' `
  -label '#6 §5 识别引擎行'

# ---- #7 §5 工具链行：JDK 21 的理由现写「虚拟线程」，该理由不成立
Replace-Once `
  -old '| 工具链 | JDK 21 + Maven | 虚拟线程契合音频与识别管线；构建与测试标准化 | 现有 JDK 8 |' `
  -new '| 工具链 | JDK 21 + Maven | 两个引擎均有成熟的 Java 绑定；`jpackage` 免背 Chromium；构建与测试标准化。**注**：原写「虚拟线程契合音频与识别管线」——该理由不成立，全程序长期只有 3 根左右线程（TECH-PLAN §9 #7） | 现有 JDK 8 |' `
  -label '#7 §5 工具链行（修正不成立的理由）'

# ---- #11 §11 里程碑 M3
Replace-Once `
  -old '| **M3** | whisper 接入 + 浮窗预览 | **转写质量与延迟，项目 go/no-go 关卡** | |' `
  -new '| **M3** | SenseVoice 精化接入 + 浮窗预览 | **转写质量与延迟，项目 go/no-go 关卡**。⚠️ 当前精化位由 Vosk 离线重跑顶替（原生产物不可得），SenseVoice 接入待办，见 `ENGINE-EXPERIMENT.md` | |' `
  -label '#11 §11 M3'

# ---- #12 §12 未决问题 #6
Replace-Once `
  -old '| 6 | **whisper 具体选哪个模型**（base / small / medium），取决于 M3 的实测质量与延迟 | M3 中 |' `
  -new '| 6 | ~~whisper 选哪个模型~~ → **已关闭**：架构已定为 SenseVoice（TECH-PLAN §1.1）。剩余问题是 **SenseVoice 的具体量化版本**（int8 / fp32）与落地路径（onnxruntime 自写前处理 vs 自编译 sherpa-onnx JNI），见 `ENGINE-EXPERIMENT.md` §4 | M3 |' `
  -label '#12 §12 未决问题 #6'

# ---- #8 §6 性能预算：回填实测 + 补「转写中 CPU 占用」行
Replace-Once `
  -old '> 下表中**只有已实测的项标了具体数字来源**，其余是目标值，需在 M1–M3 阶段实测后回填。

| 指标 | 目标 | 说明 |
|---|---|---|
| 唤醒响应 | < 300ms | 说完唤醒词到悬浮球变色 |
| 首字延迟 | < 800ms | 开始说话到浮窗出现第一个字 |
| 提交延迟 | < 2.5s | 说完结束词到文字注入完成，含 whisper 推理 |
| 空闲 CPU | < 2% | 仅跑唤醒词检测 |
| 空闲内存 | < 500MB | 小 Vosk 模型常驻 |
| 听写中内存 | < 1.5GB | 加上 whisper 模型与段落音频缓存 |
| 磁盘占用 | 小 Vosk 42MB + whisper 模型（待定）+ 日志 ≤15MB | 见附录 D |
| 冷启动 | < 3s | 含模型加载 |
| 单段最长时长 | 待定（见 §12） | 防止长录音导致内存增长 |' `
  -new '> 下表中标「实测」的项来自本机真实测量（`java -cp ... com.talkinglive.engine.EngineBench`，
> 完整报告见 `%LOCALAPPDATA%\TalkingLive\engine-bench.txt` 与 `docs/ENGINE-EXPERIMENT.md` §3）；
> 标「未测」的项需要**麦克风与真人说话**，属 §9.3 的手工集成验证。

| 指标 | 目标 | 实测 | 说明 |
|---|---|---|---|
| 唤醒响应 | < 300ms | **未测** | 说完唤醒词到悬浮球变色；需真人 |
| 首字延迟 | < 800ms | **未测** | 开始说话到浮窗出现第一个字；需真人 |
| 提交延迟 | < 2.5s | **实测：离线重跑 5s 音频约 1.2s**（RTF 0.241）；15s 段落约 7.3s ⚠️ | 说完结束词到文字注入完成。原说明含「whisper 推理」已不成立；现为精化 + 注入 |
| **转写中 CPU 占用** | **新增行：目标「单核饱和但不超过 4 核」** | **未测** | 推理必然短时占满多核；SenseVoice 接入后再按 `TECH-PLAN` §6.3 的 1/4/默认三档线程数实测回填 |
| 空闲 CPU | < 2% | **未测** | 仅跑唤醒词检测（150ms 轮询 + Vosk 流式解码） |
| 空闲内存 | < 500MB | **实测：堆内 3 MB** | 实测远低于预算：Vosk 模型是**原生内存**，不在 Java 堆里。整进程 RSS 待补测 |
| 听写中内存 | < 1.5GB | **实测：堆内 3 MB**（跑完全部基准测量后） | 段落 PCM 上限已定为 60s（`AppConfig.maxSegmentSeconds`），16kHz 单声道约 1.9 MB |
| 磁盘占用 | 小 Vosk 42MB + 精化模型（待定）+ 日志 ≤15MB | **实测：Vosk 解压 65MB + libvosk.dll 25MB**；SenseVoice int8 为 239MB（未下载） | 见附录 D |
| 冷启动 | < 3s | **实测：模型加载 1253ms** | 含模型加载；再加 JVM 启动与 UI 构建，冷启动达标有把握但未端到端测量 |
| 单段最长时长 | **默认 60 秒**（`AppConfig.maxSegmentSeconds`，可配 5–600） | — | 见 §12 #5：防止长录音导致内存增长。60s 上限对「静音 5s 结束」的正常段落无影响 |' `
  -label '#8 §6 性能预算表回填 + 补转写中 CPU 行'

# ---- #9 §7 异常与降级：whisper 一行改为 SenseVoice
Replace-Once `
  -old '| whisper 推理失败 / 超时 | 段落未精化 | 退回用 Vosk 预览文本注入，并记日志 |' `
  -new '| 精化（SenseVoice）推理失败 / 超时 | 段落未精化 | 退回用 Vosk 预览文本注入，并记日志。已有实现与单测：`TextRefiner.Result.fallback` |' `
  -label '#9 §7 精化失败降级行'

# ---- #10 §8 风险表：whisper 推理延迟改写 + 新增 ITN 改字风险
Replace-Once `
  -old '| **whisper 推理延迟** | 段落结束后需等待一次推理（CPU 上约 1–3 秒） | 浮窗显示「处理中」；必要时换更小模型或启用 GPU |' `
  -new '| **精化推理延迟**（原：whisper 推理延迟，风险已大幅下降） | 段落结束后需等待一次推理。原风险为 whisper-small 纯 CPU 约 12–15s（`TECH-PLAN` §4）；换 SenseVoice 后目标约 0.5s | 浮窗显示「处理中」；当前实现（Vosk 离线重跑）实测 RTF 0.241 守住 2.5s；**长段（>15s）会超预算**，靠单段上限 60s 与静音结束兜底 |' `
  -label '#10a §8 精化延迟风险改写'

Replace-Once `
  -old '| **坐标空间混淆** |' `
  -new '| **ITN 会改字**（新） | SenseVoice 的 `use_itn=1` 不只是加标点，还会**规整数字**。官方英文样例中 `fifty pieces of gold` 被规整成 `50 pieces of code` | `AppConfig.itn` 开关已预留；接入后按 `TECH-PLAN` §6.3 实测中文场景是否可接受，再决定开/关/按场景 |' + "`n" + '| **坐标空间混淆** |' `
  -label '#10b §8 新增 ITN 改字风险'

Write-Output ''
Write-Output "已落实 $($changed.Count) 处："
$changed | ForEach-Object { Write-Output "  - $_" }

[System.IO.File]::WriteAllText($path, $text, $enc)
Write-Output ''
Write-Output "已写入 $path"
