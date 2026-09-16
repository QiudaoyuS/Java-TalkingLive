# 一次性脚本：把「麦克风已打通」这件事更新进 IMPLEMENTATION-STATUS.md
$ErrorActionPreference = 'Stop'
$path = (Resolve-Path 'docs\IMPLEMENTATION-STATUS.md').Path
$enc = New-Object System.Text.UTF8Encoding($false)
$text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
$changed = @()

function Replace-Once {
    param([string]$old, [string]$new, [string]$label)
    if (-not $script:text.Contains($old)) { Write-Output "  [SKIP] $label"; return }
    $i = $script:text.IndexOf($old)
    $script:text = $script:text.Substring(0, $i) + $new + $script:text.Substring($i + $old.Length)
    $script:changed += $label
    Write-Output "  [OK]   $label"
}

# ---- 总览一句话
Replace-Once `
  -old '> 代码已经写完并通过全部自动化验证（**246 项单测 + 44 项结构化自检，0 失败**），
> 唯一无法在此环境验证的是**需要麦克风与真人说话的那一部分**（唤醒命中率、误触发率、
> 真实识别质量、注入到真实应用的成功率）。精化引擎的 SenseVoice 因上游原生产物不可得而降级。' `
  -new '> 代码已经写完并通过全部自动化验证（**246 项单测 + 44 项结构化自检，0 失败**），
> **麦克风链路已在本机实测打通**（44100Hz 立体声 → 16kHz 单声道，采音正常）。
> 仍需真人才能验证的是：唤醒**命中率**、误触发率、真实识别质量、
> 注入到真实应用的成功率。精化引擎的 SenseVoice 因上游原生产物不可得而降级。' `
  -label '总览一句话'

# ---- 3.1 表格追加麦克风链路一行（插到第 6 项之前，保持顺序合理）
$old6 = '| 6 | 音频单路采集 + **显式重采样**（48k→16k） | 24 项单测（含频率保持、跨块相位连续、立体声下混、位深） |'
$new6 = @(
  '| 5b | **麦克风链路真实可用**（设备原生 44100Hz 立体声 → 16kHz 单声道） | 本机实测：`--mic-test` 收到 379 帧、本底噪声 RMS=0.0107；启动健康检查 1 秒 21 帧 |',
  $old6
) -join "`n"
Replace-Once -old $old6 -new $new6 -label '3.1 追加麦克风链路一行'

$old18 = '| 18 | 分层约束**被测试强制**（core/text 不得依赖 AWT/JNA/原生库） | `ArchitectureTest` 源码扫描 7 项 |'
$new18 = @(
  $old18,
  '| 19 | **启动时麦克风健康检查**（区分「采音没进来」与「识别不准」） | 本机实测：1 秒 21 帧、RMS=0.0107 低于静音阈值 0.012，判定通过 |',
  '| 20 | **`--mic-test` 麦克风实测模式**（实时音量条 + 预览文字 + 唤醒命中计数） | 本机实测通过，这是补 §9.3 清单第 1–3 条的工具 |'
) -join "`n"
Replace-Once -old $old18 -new $new18 -label '3.1 追加健康检查与 --mic-test 两行'

# ---- 3.2 把「麦克风实际打开与采集」一行改为已完成（保留其余未验证项）
Replace-Once `
  -old '| 1 | 麦克风实际打开与采集 | 本机无可用录音设备（`--doctor` 明确报告） | 插上麦克风后跑 `App`，看日志「麦克风已打开：…→ 目标格式 16kHz」 |' `
  -new '| ~~1~~ | ~~麦克风实际打开与采集~~ → ✅ **已实测打通**（见 3.1 第 5b 项） | — | — |' `
  -label '3.2 麦克风一行标记为已打通'

# ---- 4. 验证命令补 --mic-test
Replace-Once `
  -old '# ⑥ 真正跑起来（需要麦克风）
java -jar target\talkinglive.jar
```' `
  -new '# ⑥ 麦克风实测：实时看音量条 + 预览文字 + 唤醒命中（需要麦克风，15 秒）
java -jar target\talkinglive.jar --mic-test

# ⑦ 真正跑起来（需要麦克风）
java -jar target\talkinglive.jar
```' `
  -label '4. 补 --mic-test 命令'

# ---- 5. 缺陷表追加两条（第 13、14 条）
$oldLast = '| 12 | 流式重采样的相位累积每块多吐一个样本 | 长流缓慢多出样本（1 秒多 8 个） | `AudioTest.chunkedMatchesWhole` |'
$newLast = @(
  $oldLast,
  '| 13 | **用 `getSourceLineInfo` 去找输入设备** | 枚举恒返回 0 项，于是产品报告「系统没有可用的录音设备」——而麦克风其实是好的、`getLine()` 能直接打开。**用户会以为自己的麦克风坏了，产品完全不可用** | `--doctor` 报「没有可用录音设备」，但 `Win32_SoundDevice` 里明明有 Realtek 声卡；用 `tools/ListMics`/`ProbeMixer` 定位到 `getTargetLineInfo` 才返回 2 项。已改为 `getTargetLineInfo` 并在枚举为空时回退（实测后麦克风立刻可用） |',
  '| 14 | `candidate.open(固定格式)` 写死要 16kHz | 真实设备原生是 44.1k/48k 立体声，硬要 16kHz 会打开失败或让系统做一次质量不可控的重采样 | 同上；已改为 `open()` 用设备自己的默认格式，转换交由 `AudioConverter` 显式完成（TECH-PLAN §7 第 2 项的原始意图） |'
) -join "`n"
Replace-Once -old $oldLast -new $newLast -label '5. 缺陷表追加第 13/14 条'

# ---- 3.4 已知缺口：补一条静音阈值的实测观察
$oldGap = '| 精化在**每段**都新建识别器（实测 111ms） | 每段固定开销 111ms | 可改成复用池；当前未做（111ms 在 2.5s 预算内） |'
$newGap = @(
  $oldGap,
  '| **静音阈值是写死的 0.012** | 本机实测本底噪声 RMS=0.0107，**已经贴着阈值**。环境稍吵（或麦克风增益偏高）就会让静音计时被"噪声"不断重置，段落不再因静音自动结束 | 启动健康检查会在本底噪声超阈值时**记 WARN**（已实现）。更彻底的做法是按本底噪声自适应，或改成可配置——当前未做 |'
) -join "`n"
Replace-Once -old $oldGap -new $newGap -label '3.4 补静音阈值观察'

# ---- 修订记录
$oldRevTail = '| 0.1 | 本次 | 首版：里程碑对照、逐项能力清单（含未验证项与原因）、验证命令、实现期发现的 12 个真实缺陷 |'
$newRevTail = @(
  $oldRevTail,
  '| 0.2 | 本次 | **修正一处过时结论**：3.2 原写「本机无可用录音设备」，实为代码用错了枚举 API（缺陷 #13）。改为 `getTargetLineInfo` 后麦克风实测打通，已把该项移入 3.1 并新增启动健康检查与 `--mic-test` 模式；缺陷表补至 14 条 |'
) -join "`n"
Replace-Once -old $oldRevTail -new $newRevTail -label '修订记录追加 0.2'

[System.IO.File]::WriteAllText($path, $text, $enc)
Write-Output ''
Write-Output "已更新 $($changed.Count) 处"
