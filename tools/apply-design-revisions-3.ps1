# 一次性脚本（第三批）：给 DESIGN.md 追加修订记录，并登记实现状态文档
$ErrorActionPreference = 'Stop'
$path = (Resolve-Path 'docs\DESIGN.md').Path
$enc = New-Object System.Text.UTF8Encoding($false)
$text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)

$oldRow = '| 1.1 | 2026-09-16 | — | 补齐缺章：交互清单、代码组织、性能预算、异常降级、测试策略、开发环境、未决问题；UI 章节改为悬浮球方案（含托盘图标与贴边收起）；新增附录 D 模型获取 |'
$newRows = @(
  $oldRow,
  '| 1.2 | 本次（实现阶段） | — | **按 `TECH-PLAN` §9 的修订清单逐条落实 16 项**：① 精化引擎 whisper.cpp → SenseVoice（§4.2 分工表、§4.1 管线图、§3.1 第 7 项、§3.4 接口伏笔、§5 决策表、§11 M3、§12 #6）；② 标点主力改为 SenseVoice 原生标点（§3.1 第 11 项，`PunctuationProcessor` 职责下调）；③ 修正 §5 工具链行里**不成立的「虚拟线程」理由**；④ §6 性能预算**回填实测**（模型加载 1253ms、堆内存 3MB、离线 RTF 0.241）并**新增「转写中 CPU 占用」一行**；⑤ §7/§8 精化失败与延迟风险改写，**新增 ITN 改字风险**；⑥ §10.1 标注 `JAVA_HOME` 已修正；⑦ 附录 B.2 补「Step 0 未比较纯 CPU 速度」这一教训；⑧ 附录 D.2 由 whisper 改为 SenseVoice（含下载地址与不可用原因）；⑨ §12 #5 单段上限定为 **60 秒** |'
) -join "`n"

if (-not $text.Contains($oldRow)) { Write-Output 'ABORT: revision row not found'; exit 1 }
$i = $text.IndexOf($oldRow)
$text = $text.Substring(0, $i) + $newRows + $text.Substring($i + $oldRow.Length)

$oldTail = '> 日期取自 git 提交时间；后续修订请在提交信息里注明版本号，并在此表追加一行。'
$newTail = @(
  '> 日期取自 git 提交时间；后续修订请在提交信息里注明版本号，并在此表追加一行。',
  '',
  '## 相关文档',
  '',
  '| 文档 | 作用 |',
  '|---|---|',
  '| `docs/TECH-PLAN.md` | 技术方案（与本文冲突时**以技术方案为准**） |',
  '| `docs/ENGINE-EXPERIMENT.md` | 精化引擎落地报告：实测数据、SenseVoice 不可直接依赖的确认、未测项清单 |',
  '| `docs/IMPLEMENTATION-STATUS.md` | **现在能干什么、不能干什么**：里程碑对照、逐项能力清单、实现期发现的真实缺陷 |',
  '| `demo/README.md` | P0 交互原型（纯模拟数据）的说明 |'
) -join "`n"

if (-not $text.Contains($oldTail)) { Write-Output 'ABORT: tail not found'; exit 1 }
$j = $text.LastIndexOf($oldTail)
$text = $text.Substring(0, $j) + $newTail + $text.Substring($j + $oldTail.Length)

[System.IO.File]::WriteAllText($path, $text, $enc)
Write-Output 'OK: DESIGN.md revision record + related docs section added'
