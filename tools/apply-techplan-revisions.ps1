# 一次性脚本：把 TECH-PLAN 附录 A.5 的最后一项标为已确认
$ErrorActionPreference = 'Stop'
$path = (Resolve-Path 'docs\TECH-PLAN.md').Path
$enc = New-Object System.Text.UTF8Encoding($false)
$text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)

$old = '| SenseVoice 的 Java 绑定（JNI）与 Windows x64 预编译产物 | ❌ 未确认，**需在 M3 前确认** |'
$new = '| SenseVoice 的 Java 绑定（JNI）与 Windows x64 预编译产物 | ✅ **已确认：不可直接依赖**（`docs/ENGINE-EXPERIMENT.md` §2）。sherpa-onnx 无 Maven Central 产物，官方 Java 绑定需自行用 MSVC + CMake 构建 JNI 动态库——与 §3.1「零环境依赖」红线冲突。模型本身可下载（int8 239MB，hf-mirror 实测可达） |'
if (-not $text.Contains($old)) { Write-Output 'ABORT: 找不到 A.5 最后一行'; exit 1 }
$text = $text.Replace($old, $new)

$oldNote = '> ⚠️ 最后一项是**落地可行性**问题：若 sherpa-onnx 的 Java 绑定或 Windows x64
> 原生产物不可用，方案 C 的集成成本会显著上升。**建议在 M0 期间优先确认**，
> 这是本方案剩余的最大技术不确定性。'
$newNote = '> ✅ **最后一项已确认，结论是「按原样落不了地」**：原判断「若不可用，方案 C 的集成成本会显著上升」
> 是正确的预警，实际情况比它更硬——**没有可直接依赖的产物**，不是成本上升而是需要换一条路。
>
> **已采取的处置**（详见 `docs/ENGINE-EXPERIMENT.md` §4）：
> ① `TextRefiner` 接口与所有调用路径**保持不变**（§5.1「替换点只有一处」被代码结构证明）；
> ② 精化位由 `TextRefiners.VoskOffline`（Vosk 整段离线重跑）顶替，实测 **RTF 0.241**
> （5 秒音频约 1.2s），仍守住 `DESIGN.md` §6 的「提交延迟 < 2.5s」，
> **因此不需要破 §3.1 的「不依赖核显」红线**；
> ③ SenseVoice 的两条落地路径已列出：改用 **onnxruntime（Maven Central 有，含 Windows x64 原生库）
> 自写 SenseVoice 前处理与解码**（推荐，符合零环境依赖），或自编译 sherpa-onnx JNI（不推荐）。'
if (-not $text.Contains($oldNote)) { Write-Output 'ABORT: 找不到 A.5 的注记'; exit 1 }
$text = $text.Replace($oldNote, $newNote)

$oldRev = '| 0.2 | 2026-09-16 | — | **架构收敛为方案 C**'
$i = $text.LastIndexOf($oldRev)
if ($i -lt 0) { Write-Output 'ABORT: 找不到修订记录 0.2'; exit 1 }
$lineEnd = $text.IndexOf("`n", $i)
$row = @(
  '',
  '| 0.3 | 本次（实现阶段） | — | **关闭附录 A.5 最后一项**：确认 sherpa-onnx 无 Maven Central Java 绑定、无 Windows x64 预编译产物，方案 C 的精化引擎**不可按原样落地**（模型本身可下载）。据此在 `docs/ENGINE-EXPERIMENT.md` 记录实测数据（Vosk 模型加载 1253ms、堆内存 3MB、离线 RTF 0.241）与两条替代落地路径，并在 `docs/IMPLEMENTATION-STATUS.md` 给出实现状态。**本文的架构决策未被推翻**：`TextRefiner` 接口位置与预览侧全部保持不变，§3.1「不依赖核显」的红线也守住了 |'
) -join "`n"
$text = $text.Substring(0, $lineEnd) + $row + $text.Substring($lineEnd)

[System.IO.File]::WriteAllText($path, $text, $enc)
Write-Output 'OK: TECH-PLAN.md 附录 A.5 与修订记录已更新'
