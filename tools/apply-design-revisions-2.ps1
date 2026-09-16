# 一次性脚本（第二批）：落实 TECH-PLAN §9 修订清单的剩余项
# #13 附录 B.2 补「Step 0 未比较纯 CPU 速度」
# #14 附录 D.2 改为 SenseVoice 模型
# #15 §10.1 环境：JAVA_HOME 已修正
# #16 §4.5 代码组织：WhisperRefiner.java → TextRefiners（实际实现）
$ErrorActionPreference = 'Stop'
$path = (Resolve-Path 'docs\DESIGN.md').Path
$enc = New-Object System.Text.UTF8Encoding($false)
$text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
$changed = @()

function Replace-Once {
    param([string]$old, [string]$new, [string]$label)
    if (-not $script:text.Contains($old)) {
        Write-Output "  [SKIP] $label"
        return
    }
    $i = $script:text.IndexOf($old)
    $script:text = $script:text.Substring(0, $i) + $new + $script:text.Substring($i + $old.Length)
    $script:changed += $label
    Write-Output "  [OK]   $label"
}

# ---- #15 §10.1 环境：JAVA_HOME
Replace-Once `
  -old '> ⚠️ 本机 `JAVA_HOME` 仍指向 `jdk1.8.0_111`，而 PATH 里的 `javapath` shim 指向 21。
> 两者不一致，建议把 `JAVA_HOME` 也改到 JDK 21，避免构建工具挑错版本。' `
  -new '> ✅ **已修正**（TECH-PLAN §7 第 1 项）：机器级 `JAVA_HOME` 现为 `D:\Code\Java\jdk-21.0.12.1`。
> 注意：**已经打开的终端**里 `JAVA_HOME` 仍是旧值（进程环境变量在启动时确定），
> 需重开终端或显式设置后才能用 `mvnw` 构建——本仓库的 `README.md` 里写明了这一点。' `
  -label '#15 §10.1 JAVA_HOME 已修正'

# ---- #16 §4.5 代码组织：WhisperRefiner.java → 实际实现
Replace-Once `
  -old '│   ├── WhisperRefiner.java        实现（段落精化）' `
  -new '│   ├── TextRefiners.java          实现（段落精化：SenseVoice / VoskOffline / Unavailable）' `
  -label '#16 §4.5 代码组织 精化实现'

# ---- #13 附录 B.2 补充说明
Replace-Once `
  -old '另注：官方标点恢复模型仅覆盖 en / ru / de，**中文无标点模型**，进一步印证转写须交给 whisper。' `
  -new '另注：官方标点恢复模型仅覆盖 en / ru / de，**中文无标点模型**，进一步印证转写须交给另一个引擎。

> ⚠️ **补充（TECH-PLAN §9 #13）**：Step 0 **只比较了准确率这一维**，
> **没有比较纯 CPU 速度**。而速度这一维后来成了架构决策的关键：
> whisper-small 纯 CPU 处理 5 秒音频约 12–15 秒（`TECH-PLAN` §4.2），
> 与 §6 的「提交延迟 < 2.5s」差一个数量级，于是才有了方案 C（精化引擎换 SenseVoice）。
> 本次实现又实测到：**即便小得多的 Vosk 小模型，纯 CPU 也要 1.21 秒/5 秒音频**（RTF 0.241），
> 侧面印证了那个推算的量级。教训：**引擎选型必须同时看准确率与纯 CPU 速度**。' `
  -label '#13 附录 B.2 补速度维度'

# ---- #14 附录 D.2 改为 SenseVoice
Replace-Once `
  -old '### D.2 whisper 模型

**待定。** 具体选 base / small / medium 取决于 M3 阶段的质量与延迟实测
（见 §12 未决问题 #6）。确定后在此补上模型名与下载地址。' `
  -new '### D.2 精化模型（SenseVoice）

模型名已定：**`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`**，int8 量化。

```
# 模型（239 MB）与词表（316 KB）——hf-mirror 实测可下载
https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx
https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt
```

放置位置：`%LOCALAPPDATA%\TalkingLive\models\sense-voice\`（`AppPaths.refinerModelDir()`）。

> ⚠️ **为什么现在还装不起来**：模型能下载，但 **sherpa-onnx 没有 Maven Central 的
> Java 绑定，也没有 Windows x64 预编译原生产物**。上游只发 Android/iOS/Python/C++ 产物，
> JVM 侧要使用者自己用 MSVC + CMake 构建 JNI 动态库——这与 `TECH-PLAN` §3.1
> 「零环境依赖」的红线冲突。
>
> 因此当前精化位由 **Vosk 整段离线重跑**顶替（实测 RTF 0.241，守住 §6 的 2.5s 预算）。
> 两条落地路径（onnxruntime 自写前处理 / 自编译 JNI）与取舍见
> `docs/ENGINE-EXPERIMENT.md` §4。' `
  -label '#14 附录 D.2 改为 SenseVoice'

[System.IO.File]::WriteAllText($path, $text, $enc)
Write-Output ''
Write-Output "已落实 $($changed.Count) 处："
$changed | ForEach-Object { Write-Output "  - $_" }
