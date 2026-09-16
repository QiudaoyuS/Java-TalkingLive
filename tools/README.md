# tools/

这里的 `apply-*.ps1` 是**一次性脚本**，已经执行完毕，**不要重复运行**
（它们做的是字符串替换，第二次跑会因为找不到目标文本而报 SKIP，无害但无意义）。

## 为什么把一次性脚本留在仓库里

这些脚本记录的是「`docs/DESIGN.md` 与 `docs/TECH-PLAN.md` 到底按哪一份清单、改了哪几句」。
文档修订本身在文档的修订记录里，但**逐句的前后对照**只存在于脚本里。
将来若要核对「技术方案 §9 的 16 项修订是否真的都落实了」，跑一遍这些脚本
（在干净检出上）就能得到逐项 OK/SKIP 的清单，比人工比对可靠。

| 脚本 | 作用 | 对应 |
|---|---|---|
| `apply-design-revisions.ps1` | `DESIGN.md` 修订清单第一批（#4 #5 #6 #7 #8 #9 #10 #11 #12） | `TECH-PLAN.md` §9 |
| `apply-design-revisions-2.ps1` | 第二批（#13 #14 #15 #16） | 同上 |
| `apply-design-revisions-3.ps1` | 追加 `DESIGN.md` 修订记录行与「相关文档」表 | — |
| `apply-techplan-revisions.ps1` | 关闭 `TECH-PLAN.md` 附录 A.5 最后一项并追加修订记录 | `ENGINE-EXPERIMENT.md` §2 |

## 两个操作上的坑（都踩过）

1. **脚本文件必须带 UTF-8 BOM。**
   Windows PowerShell 5.1 对**无 BOM** 的 `.ps1` 按 ANSI（中文 Windows 上是 GBK）解码，
   脚本里的中文会变成乱码，进而因为全角字符被误解析而报
   `Expressions are only allowed as the first element of a pipeline`。
   仓库里的这几个脚本都已经带 BOM。

2. **不要用编辑工具逐行改 `DESIGN.md` 里的 ASCII 流程图。**
   那些行含全角字符与制表框线（`│ ┌ └`），且前导空格数不规整，
   字面量替换很容易「看起来一样但匹配不上」。脚本里改用
   `IndexOf`/`Replace` 或按行号定位，可靠得多。
