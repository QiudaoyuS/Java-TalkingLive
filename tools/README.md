# tools/

手工诊断、启动辅助与工程脚本。**全部不在产品运行路径上**，只在排查或改版本时用。

| 文件 | 作用 |
|---|---|
| `set-version.ps1` | **改版本号用它**（`AGENTS.md`「发布版本跟随提交序号」）：无参数 = 取 HEAD 的 `[N.n.m]` 加 1 并写进三处（README 顶部 / `pom.xml` 的 `<version>` 与 `<appVersion>`）；给一个号就用那个号；`-Check` 只核对（三处一致 + 等于 HEAD 首行，适合在 CI 里跑）；`-Tag` 核对通过后给 HEAD 打 `v<号>`。含中文，故存成 UTF-8 **BOM**（见文末两条坑） |
| `get-model.cmd` | **第一次用之前**双击它：选小模型 / 大模型 → 自动下载（hf-mirror，失败回退官方）→ 解压到 `%LOCALAPPDATA%\TalkingLive\models\` → 校验并删掉 zip。也可脚本化：`get-model.cmd 1 --no-pause`（1=小模型、2=大模型） |
| `verify-batch-bug.ps1` | 复现并证明「代理对跨批边界时 `batchByCodePoints` 死循环」—— 那个 bug 正是用户报告的「点不动任何东西」（死循环卡住 UI 线程）。想把前端点留证据时跑它 |
| `ListMics.java` | 列出系统所有录音设备（排查「没有可用录音设备」/ 选错设备） |
| `ProbeMixer.java` | 探测音频混音器的支持格式（排查设备格式与 16kHz 目标格式不匹配） |
| `run-console.cmd` | 用 `java.exe` 启动，**保留控制台**。正常启动（双击 `run.cmd`）走 `javaw`、不留控制台；需要看输出时用这个 |
| `run-diagnose.cmd` | 同上，并加 `-Dtalkinglive.log.text=true` 打开日志里的转写内容（仅在排查识别问题时用） |

## 诊断入口（在 `src/main/java` 里，不在本目录）

这些类的 `main` 需要手动运行，用法统一是：

```powershell
java -cp "target\talkinglive.jar;target\lib\*" com.talkinglive.<类名> [参数]
```

| 类 | 作用 |
|---|---|
| `App --doctor` | 环境自检：模型 / 词表校验 / 麦克风 / 注入能力，跑完退出 |
| `App --self-check` | 结构化自检（44+ 项，含真实 Robot 驱动的 UI 断言） |
| `App --mic-test` | 麦克风实测：实时音量条 + 预览文字 + 唤醒命中计数 |
| `engine.EngineSmoke` | 验证原生库加载、词表查询、受限语法（单测覆盖不到的那一环） |
| `engine.EngineBench` | 实测性能并回填 `DESIGN.md` §6 预算 |
| `SampleInjector` | 注入链路手工验证（§9.3 清单第 4 条） |
| `system.DpiProbe` | 核对 Win32 物理像素与 AWT 逻辑像素的坐标空间 |
| `system.LogViewer` | 用**明确 UTF-8** 打印日志尾部（不必再和编码打交道） |

## 已退休的工具

一批**为已解决问题临时建的**诊断入口已在 0.9.0 清理中删除
（`ReproClick` / `ReproBlock` / `BlockSnapshot` / `WindowProbe` / `snapshot.cmd` /
5 个 `apply-*.ps1`）。它们当初解决什么问题、结论是什么、**下次怎么重建**，
见 `docs/RETIRED-TOOLS.md`。

## 两个操作上的坑（都踩过，值得记住）

1. **脚本文件若含中文，必须带 UTF-8 BOM。**
   Windows PowerShell 5.1 对**无 BOM** 的 `.ps1` 按 ANSI（中文 Windows 上是 GBK）解码，
   脚本里的中文会变成乱码，进而因为全角字符被误解析而报
   `Expressions are only allowed as the first element of a pipeline`。
   同理：**`.cmd` / `.bat` 一律只用 ASCII** —— cmd.exe 在切换代码页之前就解析整个文件，
   UTF-8 或 GBK 字节都会把命令解析搞坏（实测把文件变成了乱码）。

2. **不要用文本编辑工具逐行改 `docs/DESIGN.md` 里的 ASCII/框线图。**
   那些行含全角字符与制表框线（`│ ┌ └`），且前导空格数不规整，
   字面量替换很容易「看起来一样但匹配不上」。按行号或 `IndexOf` 定位可靠得多。
