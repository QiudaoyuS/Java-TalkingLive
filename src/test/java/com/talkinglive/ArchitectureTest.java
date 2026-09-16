package com.talkinglive;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * 诊断入口的**白名单**（{@code DESIGN.md} §4.5：App 是唯一有 main 的类）。
     *
     * <p>白名单必须显式列出而不是「凡是不叫 App 的都放过」：那等于没有约束。
     * 这里三个都是**只在开发/验证时手动运行**的诊断入口，不在产品运行路径上：
     * <ul>
     *   <li>{@code EngineSmoke} —— 验证 Vosk 原生库加载、词表查询、受限语法。这三件事
     *       都依赖真实模型与原生库，单测覆盖不到，但它们恰恰是最容易出问题的一环。</li>
     *   <li>{@code EngineBench} —— 实测并回填 {@code DESIGN.md} §6 的性能预算。</li>
     *   <li>{@code SampleInjector} —— 注入链路的手工验证入口（见 §9.3 清单第 4 条）。</li>
     *   <li>{@code system.DpiProbe} —— 核对 Win32 物理像素与 AWT 逻辑像素的坐标空间。
     *       这条「最容易反复踩的坑」（§4.4）不能靠背结论：进程 DPI awareness 不同，
     *       结论就相反，必须能当场实测。实测过程中还因此误判过一次窗口位置。</li>
     *   <li>{@code system.WindowProbe} —— 探测屏幕某位置实际归属哪个窗口，
     *       用于排查「点了没反应」。</li>
     *   <li>{@code ReproClick} —— 验证「应用运行时别的窗口还能不能收到真实鼠标点击」。
     *       这是用户报告「屏幕被抢占、什么都点不动」时的判据工具：它用 Robot 发真实点击，
     *       覆盖悬浮球常驻 / 浮窗显示中 / 贴边轮询 / 菜单打开四种状态。</li>
     *   <li>{@code ReproBlock}、{@code ReproTray} —— 同上问题的另两条排查路径：
     *       分别是「焦点是否被劫持」与「托盘气泡是否阻塞 UI 线程」。</li>
     * </ul>
     */
    private static final java.util.Set<String> DIAGNOSTIC_ENTRY_POINTS =
            java.util.Set.of("EngineSmoke.java", "EngineBench.java", "SampleInjector.java",
                    "DpiProbe.java", "WindowProbe.java",
                    "ReproClick.java", "ReproBlock.java", "ReproTray.java");

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
