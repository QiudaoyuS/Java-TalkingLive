# 一次性脚本：把「连续输入模式」写进 DESIGN.md
$ErrorActionPreference = 'Stop'
$path = (Resolve-Path 'docs\DESIGN.md').Path
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

# ---- §2.3 交互清单：补连续模式的入口与退出 ----
$oldRow = '| 打开设置 | 菜单「设置...」 | 任意 | 打开设置窗口的「常规」页 |'
$newRow = @'
| **说退出词（连续模式）** | 说「完毕」（可配） | 任意 | 结束整个聆听会话，回到待唤醒；**当前正在说的那一段会先提交**，不丢内容 |
| 打开设置 | 菜单「设置...」 | 任意 | 打开设置窗口的「常规」页 |
'@
Replace-Once -old $oldRow -new $newRow -label '§2.3 交互清单补退出词'

# ---- §2.2 完整使用：补一段连续模式的用法 ----
$anchor = '> 任何时刻按 **Esc** 可取消整段，一个字都不会打出去。'
$insert = @'
> 任何时刻按 **Esc** 可取消整段，一个字都不会打出去。

### 2.2.1 连续输入模式（可选，默认关闭）

上面那套流程是**一轮一句**：每说一段都要重喊唤醒词。说长内容（发一段微信、
写一段笔记）时，这会不断打断表达。开启「连续输入模式」后变成：

```
说「子曰」→ 进入连续聆听
   ├─ 说完一句（静音 3 秒 / 说「到此为止」）→ 落一段字，**继续聆听**
   ├─ 继续说下一句 → 再落一段字，继续聆听
   └─ 说「完毕」→ 结束整个会话，回到待唤醒
```

设计要点：

- **静音与结束词分工互补**：静音 3 秒是兜底（说完自然停顿即落字），
  说「到此为止」是明确手段（想一下之前先落字）。纯静音会把思考切成两段，
  纯结束词又要求用户时刻记得说。
- **退出词是必需的，不是可选的**：本产品的目标是**全程语音操控**——
  有些用户无法使用鼠标或按键，所以「停止听写」必须有语音通路，
  不能只放在悬浮球菜单里。默认词取「完毕」（附录 B.1 实测在词表内；
  不取「结束」是因为它太常用，容易在正文里被误命中）。
- **说退出词不丢内容**：当前正在说的那一段会**先提交再退出**。
- **连续模式下静音默认 3 秒**（而非单段模式的 5 秒）：那时静音是分段依据而不是兜底。
- **模式默认关闭**，一轮一句的旧契约不变，避免改掉已有用户的行为预期。
'@
Replace-Once -old $anchor -new $insert -label '§2.2.1 连续输入模式说明'

# ---- 附录 A 配置项 ----
$oldA = '| 贴边收起 | 开启 | 判定距离 40px、露出 20px（暂硬编码） |'
$newA = @'
| 贴边收起 | 开启 | 判定距离 40px、露出 20px（暂硬编码） |
| **连续输入模式** | 关闭 | 开 / 关。开启后唤醒一次连续落字，说退出词才结束会话 |
| **退出词** | 完毕 | 需在模型词表内（启动时校验）；空值表示不启用语音退出 |
| **连续模式无语音自动退出** | 60 秒 | 0–3600 秒；0 表示不自动退出。防止用户说完就走、忘了说退出词 |
'@
Replace-Once -old $oldA -new $newA -label '附录 A 补三个配置项'

# ---- 修订记录 ----
$oldRev = '| 1.2 | 本次（实现阶段） | — | **按 `TECH-PLAN` §9 的修订清单逐条落实 16 项**'
$i = $text.IndexOf($oldRev)
if ($i -lt 0) { Write-Output '  [SKIP] 修订记录锚点' } else {
    $lineEnd = $text.IndexOf("`n", $i)
    $row = @(
      '',
      '| 1.3 | 本次 | — | **新增「连续输入模式」（可选，默认关闭）**：唤醒一次后持续聆听、逐段落字，说退出词才结束会话；§2.2 补 2.2.1 小节、§2.3 补退出词一行、附录 A 补三个配置项。**产品定位澄清**：本产品是**通用语音输入**（微信发消息、写文档、AI 对话都是它的用法），只负责「人说话 → 文字进光标」，不负责音频输出与对话轮次管理。据此，「全程语音操控」被确立为设计要求——退出词因此是必需项而非可选项（有些用户无法按按钮）。同时**撤销**先前把「回声防护」当作致命缺陷的判断：本产品不输出音频，不存在软件自身回声问题。'
    ) -join "`n"
    $script:text = $script:text.Substring(0, $lineEnd) + $row + $script:text.Substring($lineEnd)
    $changed += '修订记录追加 1.3'
    Write-Output '  [OK]   修订记录追加 1.3'
}

[System.IO.File]::WriteAllText($path, $text, $enc)
Write-Output ''
Write-Output "已落实 $($changed.Count) 处"
