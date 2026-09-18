package com.talkinglive;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分层约束的**强制**测试。
 *
 * <p>{@code DESIGN.md} §4.5 写的是：「{@code core} / {@code text} / 各接口的算法部分
 * <b>不得依赖 AWT、JNA、引擎原生库</b>，以便在无麦克风、无桌面的环境下跑单元测试。
 * <b>这是本项目测试策略能成立的前提</b>。」
 *
 * <p>§9.1 又把这条重复了一遍。既然它是整个测试策略的前提，就不能只靠约定——
 * 一旦有人在 {@code core} 里 import 一个 AWT 类，测试策略会在他不知道的情况下失效。
 * 所以这里用源码扫描把它变成会失败的测试。
 *
 * <p><b>同样的道理适用于 §4.3 的另外两条约束</b>（「麦克风只开一路」「跨边界坐标只经
 * {@code DpiScale}」）。它们此前只活在 README / DESIGN 的**散文**里：写得很清楚，
 * 但没有任何东西拦得住违反。而这两条的后果都不是崩溃，而是**症状与别的原因无法区分**——
 * 各开一路麦克风表现为「识别不准」（与麦克风坏了分不出来，缺陷 #13 就被骗过一次），
 * 混用物理/逻辑像素表现为「点到了完全无关的地方」（§4.4 承认这是本项目最容易反复踩的坑）。
 * 所以它们现在也在这里被扫描强制；{@code DESIGN.md} §4.3 的对照表逐条列出「谁来喊」。
 */
class ArchitectureTest {

    private static final Path SRC = Path.of("src", "main", "java", "com", "talkinglive");

    /** 纯逻辑层：这些包里一旦出现下列依赖，就必须重新审视设计。 */
    private static final List<String> PURE_PACKAGES = List.of("core", "text");

    /** 纯逻辑层禁止出现的依赖。SLF4J 除外：它只是门面，core 里用它记状态流转不破坏可测性。 */
    private static final List<Pattern> FORBIDDEN_IN_PURE = List.of(
            Pattern.compile("^import\\s+java\\.awt\\b"),
            Pattern.compile("^import\\s+javax\\.swing\\b"),
            Pattern.compile("^import\\s+javax\\.sound\\b"),
            Pattern.compile("^import\\s+com\\.sun\\.jna\\b"),
            Pattern.compile("^import\\s+org\\.vosk\\b"),
            Pattern.compile("^import\\s+com\\.formdev\\b"));

    /** 全项目都禁止的依赖（不应被引入的技术栈）。 */
    private static final List<Pattern> FORBIDDEN_EVERYWHERE = List.of(
            Pattern.compile("^import\\s+org\\.json\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+com\\.fasterxml\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+okhttp\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+retrofit\\b", Pattern.MULTILINE));

    private static List<Path> javaFilesUnder(String... packagePath) throws IOException {
        Path dir = SRC;
        for (String p : packagePath) {
            dir = dir.resolve(p);
        }
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /**
     * 去掉注释后的源码。
     *
     * <p>必须去注释再匹配：这些类的文档注释里会**引用**被禁止的包名来解释
     * 「为什么不能这么做」（例如 {@code TextUtils} 里写了「若引用 java.awt.x 就会…」），
     * 直接扫原文会把说明文字当成违规（实测踩过）。
     */
    private static String codeOnly(Path f) throws IOException {
        String src = Files.readString(f, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
            } else if (c == '"') {
                // 字符串字面量也要跳过：里面可能出现包名（例如错误消息里提到 java.awt）
                out.append('"');
                i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                out.append('"');
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    @Test
    @DisplayName("core / text 不得依赖 AWT / Swing / JNA / 原生引擎 / 音频（§4.5 的前提）")
    void purePackagesStayPure() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String pkg : PURE_PACKAGES) {
            for (Path f : javaFilesUnder(pkg)) {
                String code = codeOnly(f);
                for (String line : code.split("\n")) {
                    String t = line.strip();
                    for (Pattern p : FORBIDDEN_IN_PURE) {
                        if (p.matcher(t).find()) {
                            violations.add(f.getFileName() + ": " + t);
                        }
                    }
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("""
                    core / text 必须保持纯逻辑（DESIGN.md §4.5：这是测试策略能成立的前提），
                    但发现了以下越界依赖：
                      """ + String.join("\n  ", violations) + """

                    请把需要这些依赖的代码移到 engine / system / audio / ui 层。
                    （日志是例外：SLF4J 只是门面，core 里用它记状态流转不破坏可测性。）
                    """);
        }
    }

    @Test
    @DisplayName("core 也不得依赖 engine（依赖方向必须是从外向内的）")
    void coreDoesNotDependOnEngine() throws IOException {
        for (Path f : javaFilesUnder("core")) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            assertTrue(!src.contains("import com.talkinglive.engine."),
                    f.getFileName() + " 不应 import engine 包");
            assertTrue(!src.contains("import com.talkinglive.system."),
                    f.getFileName() + " 不应 import system 包");
            assertTrue(!src.contains("import com.talkinglive.ui."),
                    f.getFileName() + " 不应 import ui 包");
            assertTrue(!src.contains("import com.talkinglive.audio."),
                    f.getFileName() + " 不应 import audio 包");
        }
    }

    @Test
    @DisplayName("text 也不得依赖 engine / system / ui / audio")
    void textDoesNotDependOnUpperLayers() throws IOException {
        for (Path f : javaFilesUnder("text")) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            for (String pkg : List.of("engine", "system", "ui", "audio")) {
                assertTrue(!src.contains("import com.talkinglive." + pkg + "."),
                        f.getFileName() + " 不应 import " + pkg + " 包");
            }
        }
    }

    @Test
    @DisplayName("引擎接口本身不依赖 AWT / JNA（只有实现可以）")
    void engineInterfacesArePure() throws IOException {
        List<String> interfaces = List.of(
                "WakeWordDetector.java", "SpeechRecognizer.java", "TextRefiner.java");
        for (Path f : javaFilesUnder("engine")) {
            if (!interfaces.contains(f.getFileName().toString())) {
                continue;
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String t = line.strip();
                assertTrue(!t.startsWith("import java.awt") && !t.startsWith("import com.sun.jna")
                                && !t.startsWith("import org.vosk"),
                        f.getFileName() + " 是接口，不应依赖 AWT/JNA/vosk：" + t);
            }
        }
    }

    @Test
    @DisplayName("全项目不得引入 JSON 库 / HTTP 客户端（配置解析自带，产品全离线）")
    void noUnwantedDependencies() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> all = Files.walk(SRC)) {
            for (Path f : all.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                for (Pattern p : FORBIDDEN_EVERYWHERE) {
                    if (p.matcher(src).find()) {
                        violations.add(f.getFileName() + ": " + p.pattern());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "不应引入这些依赖（DESIGN.md §1.3 全离线、§4.5 纯逻辑）：" + violations);
    }

    // ------------------------------------------------------------ §4.3 另外两条工程约束

    /**
     * 唯一允许取麦克风的文件（{@code DESIGN.md} §4.3「麦克风只开一路」）。
     *
     * <p>诊断工具（{@code tools/*.java}）不在 {@code src} 下，因此不受这条约束 ——
     * 它们本来就是用来单独试设备的（{@code ListMics} / {@code ProbeMixer}）。
     */
    private static final String MIC_OWNER = "AudioCapture.java";

    /** 「打开一路采集」会碰到的 Java Sound API。 */
    private static final List<Pattern> MIC_APIS = List.of(
            Pattern.compile("\\bTargetDataLine\\b"),
            Pattern.compile("\\bAudioSystem\\b"),
            Pattern.compile("\\bDataLine\\b"),
            Pattern.compile("\\bgetMixerInfo\\b"),
            Pattern.compile("\\bgetTargetLineInfo\\b"));

    @Test
    @DisplayName("麦克风只在 AudioCapture 里取一次（§4.3「麦克风只开一路」）")
    void microphoneIsAcquiredInOnePlaceOnly() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> all = Files.walk(SRC)) {
            for (Path f : all.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (MIC_OWNER.equals(f.getFileName().toString())) {
                    continue;
                }
                String code = codeOnly(f);
                for (Pattern p : MIC_APIS) {
                    if (p.matcher(code).find()) {
                        violations.add(f.getFileName() + " 里出现了 " + p.pattern());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "麦克风必须只开一路（DESIGN.md §4.3）：唤醒检测与实时预览共用同一个"
                        + " TargetDataLine，由 AudioCapture 分发。各自开设备会在 Windows 上互相"
                        + " 抢设备、时序对不齐，而症状是「识别不准」—— 与「麦克风坏了」分不出来"
                        + "（缺陷 #13 就是这么被骗过一次的）。越界使用：" + violations);
    }

    /**
     * 允许碰这些坐标 API 的文件（每条都带理由）。
     *
     * <p>读法：正则 → 允许出现的文件。名单之外出现即失败，失败消息里会带上这条约束的理由，
     * 因此新增一处合法用法时，改动是「进名单 + 写一句理由」，而不是悄悄绕过。
     */
    private static final Map<String, List<String>> COORDINATE_APIS = coordinateApis();

    private static Map<String, List<String>> coordinateApis() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        // 物理像素的唯一出口。目前产品代码里没有任何一处调用它 —— 这条规则是为将来准备的：
        // 一旦要用（例如把球移回屏幕中央），它必须与 Win32 侧的换算成对出现，所以只能在这里。
        m.put("\\bSetCursorPos\\b", List.of("DpiScale.java"));
        // 逻辑像素的驱动者：只有自检按设计要真的移动鼠标（§9.2 与它的两条前提）。
        m.put("\\bRobot\\b", List.of("SelfTest.java"));
        // 读鼠标位置的三处，各自有理由：
        //   FloatingBall —— 贴边收起后要判断鼠标是否还停在露出的那一条上（轮询，§4.4）
        //   SelfTest     —— 核对 Robot 真的把鼠标移到了目标点
        //   DpiProbe     —— 它的职责本来就是对照 Win32 物理像素与 AWT 逻辑像素
        m.put("\\bMouseInfo\\b", List.of("FloatingBall.java", "SelfTest.java", "DpiProbe.java"));
        return m;
    }

    @Test
    @DisplayName("跨边界坐标只经 DpiScale（§4.3 第 2 条 / §4.4「最容易反复踩的坑」）")
    void coordinatesGoThroughDpiScale() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> all = Files.walk(SRC)) {
            for (Path f : all.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = f.getFileName().toString();
                String code = codeOnly(f);
                for (Map.Entry<String, List<String>> e : COORDINATE_APIS.entrySet()) {
                    if (e.getValue().contains(name)) {
                        continue;
                    }
                    if (Pattern.compile(e.getKey()).matcher(code).find()) {
                        violations.add(name + " 里出现了 " + e.getKey()
                                + "（只允许出现在 " + e.getValue() + "）");
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "Win32 用物理像素、Java 用逻辑像素，混用会把坐标推到屏幕外或点到完全无关的"
                        + "地方（§4.4 承认这是本项目最容易反复踩的坑）。所有跨边界换算必须只经"
                        + " system.DpiScale。越界使用：" + violations);
    }

    /**
     * 诊断入口的**白名单**（{@code DESIGN.md} §4.5：App 是唯一有 main 的类）。
     *
     * <p>白名单必须显式列出而不是「凡是不叫 App 的都放过」：那等于没有约束。
     *
     * <p><b>这里只留"长期有价值"的入口。</b>0.9.0 的清理把一批**为已解决问题临时建的**
     * 入口删掉了（{@code ReproClick} / {@code ReproBlock} / {@code BlockSnapshot} /
     * {@code WindowProbe}，以及 tools 下 5 个 {@code apply-*.ps1}）——
     * 那些结论已经固化进产品代码与文档，留着只是脚手架，却一直占着这张白名单、
     * 每次重构都要顺带改它们（托盘那次就是）。删掉的原因、它们当初解决什么问题、
     * 以及**下次怎么重建**，都记在 {@code docs/RETIRED-TOOLS.md} 里。
     *
     * <p>现在这张表里每一项都是"长期有用"的：
     * <ul>
     *   <li>{@code EngineSmoke} —— 验证 Vosk 原生库加载、词表查询、受限语法。这三件事
     *       都依赖真实模型与原生库，单测覆盖不到，但它们恰恰是最容易出问题的一环。</li>
     *   <li>{@code EngineBench} —— 实测并回填 {@code DESIGN.md} §6 的性能预算。
     *       换模型、换机器时要重跑。</li>
     *   <li>{@code SampleInjector} —— 注入链路的手工验证入口（见 §9.3 清单第 4 条）。</li>
     *   <li>{@code system.DpiProbe} —— 核对 Win32 物理像素与 AWT 逻辑像素的坐标空间。
     *       这条「最容易反复踩的坑」（§4.4）不能靠背结论：进程 DPI awareness 不同，
     *       结论就相反，必须能当场实测。实测过程中还因此误判过一次窗口位置。</li>
     *   <li>{@code system.LogViewer} —— 用**明确的 UTF-8** 打印日志尾部。
     *       存在的理由是一次实测反馈：「日志中存在乱码」。查下来日志内容本来就是
     *       UTF-8，只是没有 BOM，记事本与 {@code Get-Content} 会按系统 ANSI 去解。
     *       这条入口让用户不必再和编码打交道。</li>
     * </ul>
     */
    private static final java.util.Set<String> DIAGNOSTIC_ENTRY_POINTS =
            java.util.Set.of("EngineSmoke.java", "EngineBench.java", "SampleInjector.java",
                    "DpiProbe.java", "LogViewer.java");

    @Test
    @DisplayName("产品路径上只有一个 main：App（§4.5）")
    void singleProductMainMethod() throws IOException {
        List<String> withMain = new ArrayList<>();
        try (Stream<Path> all = Files.walk(SRC)) {
            for (Path f : all.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                if (src.contains("public static void main(String[]")) {
                    withMain.add(f.getFileName().toString());
                }
            }
        }
        List<String> product = withMain.stream()
                .filter(n -> !DIAGNOSTIC_ENTRY_POINTS.contains(n))
                .toList();
        assertTrue(product.size() == 1 && product.contains("App.java"),
                "产品路径上只应有 App 有 main。实际有 main 的文件：" + withMain
                        + "；其中非白名单的：" + product
                        + "。若是新增的诊断入口，请加进 DIAGNOSTIC_ENTRY_POINTS 并说明理由。");
    }

    @Test
    @DisplayName("每个源文件都带 package 声明与类级文档注释")
    void filesAreDocumented() throws IOException {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> all = Files.walk(SRC)) {
            for (Path f : all.filter(p -> p.toString().endsWith(".java")).toList()) {
                // 按行读并 strip：仓库里是 LF、Windows 工作区检出后是 CRLF，
                // 直接对原文做 "\npackage" 匹配会因 \r 而假失败（实测踩过）。
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                boolean hasPackage = lines.stream().anyMatch(l -> l.strip().startsWith("package "));
                if (!hasPackage) {
                    problems.add(f.getFileName() + ": 缺少 package 声明");
                }
                if (!lines.stream().anyMatch(l -> l.contains("/**"))) {
                    problems.add(f.getFileName() + ": 缺少类级文档注释");
                }
            }
        }
        assertTrue(problems.isEmpty(), "文档缺失：" + problems);
    }
}
