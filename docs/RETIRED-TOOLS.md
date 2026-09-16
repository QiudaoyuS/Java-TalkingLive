# 已退休的诊断工具（0.9.0 清理）

这里记录**删掉了哪些一次性诊断入口、它们当初解决什么问题、以及下次怎么重建**。

为什么删而不是留：这些入口都是为**具体的、已经解决的问题**临时建的。
它们一直留在仓库里有三个实际代价 ——
占着 `ArchitectureTest` 的诊断入口白名单（每次改动都要把它们考虑进去）、
每次重构都要顺带改它们（托盘那次就是）、以及让 `mvnw package` 多编译一堆没人跑的代码。
**结论已经固化进产品代码与文档**，留着的是脚手架。

> 下次真遇到同类问题，照下面的"怎么重建"做一遍即可 ——
> 重建的成本远低于长期维护它们的成本。

---

## 1. `ReproClick`（186 行）+ `tools/` 里的对应说明

**当初解决什么**：用户报告「软件运行时抢占屏幕，点不动其他软件的按钮」。
它用 `Robot` 发**真实鼠标点击**，覆盖四种状态（悬浮球常驻 / 浮窗显示中 / 贴边轮询 / 菜单打开），
验证「应用运行时别的窗口还能不能收到真实鼠标点击」。

**结论**：四种状态下点击都能送达，**没有抢占**。报告见 `docs/IMPLEMENTATION-STATUS.md`。
单实例保护（`--allow-multiple` 之外拒绝第二份）是那次加的，因为两颗球确实会造成"点哪儿都怪怪的"。

**怎么重建**：新建一个类，`SystemTray`/窗口按 `App.startUi()` 的方式建好，
然后用 `new Robot().mouseMove/mousePress/mouseRelease` 在目标窗口上点，
统计目标窗口的 `mousePressed` 回调次数。

## 2. `ReproBlock`（182 行）

**当初解决什么**：同上问题的另一条路径 —— 排查「焦点是否被劫持」
（`SetForegroundWindow` 被系统拒绝、`AttachThreadInput` 行为异常等）。

**结论**：焦点没有被劫持。

**怎么重建**：建一个目标 `JFrame` 并注册 `WindowFocusListener`，
再按四种状态创建悬浮球，看目标窗口是否还能拿到焦点。

## 3. `BlockSnapshot`（239 行）+ `tools/snapshot.cmd`

**当初解决什么**：「点不动」现场只存在几秒（重启就没了），必须能当场取证。
它一次性抓下：① 可能遮屏的大窗口/置顶窗口（特别是 DT 覆盖层）；
② **卡住的修饰键**（Ctrl/Alt/Shift/Win 卡住是"看着没问题但点不动"的经典原因）；
③ 前台窗口；④ 光标下的窗口。报告写到 `%LOCALAPPDATA%\TalkingLive\block-snapshot.txt`。

**结论**：没抓到异常现场，问题未复现（详见 `IMPLEMENTATION-STATUS.md`）。
**注意"卡住的修饰键"这一项排查价值很高**，值得记住：那是"什么都没坏但什么都点不动"的头号原因。

**怎么重建**：`Win32.INSTANCE.GetAsyncKeyState(VK_*)` 查修饰键、`EnumWindows` 找大/置顶窗口、
`GetForegroundWindow` + `WindowFromPoint`。这些 API 在 `system/Win32.java` 里都还有。

## 4. `WindowProbe`（85 行）

**当初解决什么**：探测屏幕某坐标实际归属哪个窗口 —— 排查「点了没反应」时，
先要确认"我点的到底是不是我以为的那个窗口"。

**结论**：与 DPI 坐标空间的结论一起固化进了 `system/DpiScale` 与 `system/Win32`。

**怎么重建**：`Win32.INSTANCE.WindowFromPoint` + `GetWindowText` + `GetClassName`，几十行。

## 5. 5 个 `apply-*.ps1`（约 400 行）与旧 `tools/README.md`

**当初解决什么**：把 `docs/DESIGN.md` / `docs/TECH-PLAN.md` 按清单逐句改写
（`TECH-PLAN` §9 的 16 项修订），并保留"改了哪几句"的逐句对照。

**为什么删**：那些脚本的目标文本**已经不存在** —— 它们改过的段落在此后又被反复改过，
重跑只会输出 SKIP。作为"核对工具"已经失效，作为"历史记录"则完全被
`DESIGN.md` 的修订记录（0.1 → 1.11）覆盖。

**怎么重建**：不需要。文档的修订记录是权威来源。

---

## 保留下来的（以及为什么）

| 工具 | 为什么留 |
|---|---|
| `--doctor` / `--self-check`（`SelfTest` 847 行） | **日常验证的主力**，每次改动都跑 |
| `EngineSmoke` | 验证**原生库 + 词表 + 受限语法**这条最容易出问题、且单测覆盖不到的链路 |
| `EngineBench` | 回填 `DESIGN.md` §6 的性能预算；换模型/换机器时重跑 |
| `SampleInjector` | 「注入到真实目标程序」的手工验证（§9.3 清单第 4 条要用） |
| `LogViewer` | 用明确 UTF-8 读日志，避免再踩编码坑 |
| `DpiProbe` | DPI 坐标空间是"最容易反复踩的坑"，要能当场实测 |
| `tools/ListMics.java`、`tools/ProbeMixer.java` | 麦克风枚举与混音器探测，音频问题排查用 |
| `tools/verify-batch-bug.ps1` | 「代理对跨批边界死循环」的证明脚本；那个前端点值得保留证据 |
| `tools/run-console.cmd`、`tools/run-diagnose.cmd` | 需要看控制台输出时的入口 |
