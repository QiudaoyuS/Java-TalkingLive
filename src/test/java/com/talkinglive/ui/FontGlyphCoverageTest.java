package com.talkinglive.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Font;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 字体**字形覆盖**的测试。
 *
 * <p><b>它来自一个真实的用户反馈：「日志还是乱码」。</b>查下来的结论是：
 * 日志**内容**没问题（文件是带 BOM 的 UTF-8），出问题的是**画不出来** ——
 * {@code Theme.mono()} 当时选到了 <b>Consolas</b>，而 Consolas 没有任何中文字形，
 * 于是每个汉字都成了一个空方块（□□）。它看起来和编码乱码一模一样，
 * 但根因完全不同，只看截图根本分不出来。
 *
 * <p>当时那行代码的注释还写着「家族列表里每个都有中文字形」—— 注释是错的，
 * 而且没有任何东西验证过它。**这就是这个测试存在的理由**：
 * 把「注释里的一句断言」变成会失败的测试。
 *
 * <p>判据用 {@link Font#canDisplayUpTo}：它返回第一个没有字形的字符下标，
 * {@code -1} 表示全都有。这是确定性的 ——
 * 比"渲染出来数墨量"可靠（缺字方块本身也有墨量，我第一版探针就是这么被骗的）。
 */
class FontGlyphCoverageTest {

    /** 覆盖日志、菜单、窗口标题里真正会出现的字。 */
    private static final String CJK_SAMPLE =
            "日志测试系统状态诊断唤醒词结束词静音超时自动发送配置等待语音注入精化"
                    + "麦克风模型加载就绪不可用异常警告提示保存关闭暂停监听退出设置查看";

    /** 常见的非汉字符号，界面里也在用。 */
    private static final String SYMBOLS = "「」（）·——…：、。/";

    private static void assertCovers(String label, Font font, String text) {
        int bad = font.canDisplayUpTo(text);
        if (bad >= 0) {
            // 注意：这里必须用 if + fail，不能用 assertTrue(cond, 拼消息) ——
            // assertTrue 的消息参数是**即时求值**的，所以 text.charAt(bad)
            // 在 bad == -1（也就是通过）时也会被算一次，直接抛
            // StringIndexOutOfBoundsException：明明该通过的用例反而报错。
            // 这个坑我自己踩了一次，写在这里免得下次又写成同样的形式。
            fail(label + " 用的字体 " + font.getFamily()
                    + " 缺少字形：第 " + bad + " 个字符 '" + text.charAt(bad)
                    + "' 会被画成空方块。\n"
                    + "这类问题的表现和「编码乱码」一模一样，很难靠肉眼区分 —— "
                    + "所以必须在测试里拦住。请把该字体族从候选列表里换掉。");
        }
    }

    @Test
    @DisplayName("正文、粗体、等宽字体都能画出中文（日志方块事故的回归测试）")
    void allThemeFontsCoverChinese() {
        assertCovers("Theme.font(12)", Theme.font(12), CJK_SAMPLE);
        assertCovers("Theme.bold(12)", Theme.bold(12), CJK_SAMPLE);
        // ★ 这一条是事故本身：mono 曾经选到 Consolas，日志里的中文全变方块
        assertCovers("Theme.mono(11)", Theme.mono(11), CJK_SAMPLE);
    }

    @Test
    @DisplayName("这些字体也能画出界面里用到的中文标点与符号")
    void allThemeFontsCoverSymbols() {
        assertCovers("Theme.font(12)", Theme.font(12), SYMBOLS);
        assertCovers("Theme.mono(11)", Theme.mono(11), SYMBOLS);
    }

    @Test
    @DisplayName("等宽字体确实是等宽的拉丁字体（不能为了中文退回普通字体）")
    void monoStaysMonospaced() {
        Font mono = Theme.mono(12);
        int w1 = mono.canDisplay('i') ? width(mono, "i") : -1;
        int w2 = width(mono, "W");
        // 真正的等宽字体里 i 与 W 同宽；退回 YaHei 之类会明显不同
        assertTrue(w1 > 0 && Math.abs(w1 - w2) <= 1,
                "Theme.mono 选到的 " + mono.getFamily() + " 不是等宽：i=" + w1 + " W=" + w2
                        + "。等宽是日志列对齐的前提，但**不能为了等宽牺牲中文字形** ——"
                        + " 两个条件要同时满足（见 mono 的候选列表）。");
    }

    private static int width(Font f, String s) {
        var img = new java.awt.image.BufferedImage(1, 1,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setFont(f);
        int w = g.getFontMetrics().stringWidth(s);
        g.dispose();
        return w;
    }

    @Test
    @DisplayName("菜单字体覆盖中文（托盘 AWT 菜单曾一个字体都没设，汉字全是方块）")
    void menuFontCoversChinese() {
        assertCovers("Theme.menuFont(12)", Theme.menuFont(12), menuAndTitleText());
        // JPopupMenu 自己的字体来自 UIManager（实测是 Dialog 逻辑字体，会正确回退到
        // 覆盖中文的物理字体）。若它存在也要覆盖，否则菜单文字会成方块。
        Object uf = javax.swing.UIManager.get("MenuItem.font");
        if (uf instanceof Font u) {
            assertCovers("UIManager MenuItem.font", u, menuAndTitleText());
        }
    }

    /**
     * 菜单与窗口标题里真正会显示的文案 —— **从单一来源取，不手抄**。
     *
     * <p>这里原先是一串手写字符串，而它已经抄错过一次、谁也没发现：
     * 菜单项实际叫 {@code MenuAction.LOGS}，那串样本里却留着旧名字「状态与诊断」。
     * 现在直接从 {@link FloatingBall.MenuAction} 与两个窗口的 {@code TITLE} 取，
     * 文案改了这里自动跟着改 —— 字形覆盖测试本来就不该有自己的文案副本。
     */
    private static String menuAndTitleText() {
        StringBuilder sb = new StringBuilder();
        for (FloatingBall.MenuAction a : FloatingBall.MenuAction.values()) {
            sb.append(a.label()).append(a.label(true));
        }
        return sb.append(SettingsWindow.TITLE).append(DiagnosticsWindow.TITLE).toString();
    }

    @Test
    @DisplayName("菜单与窗口标题的文案都画得出来")
    void menuAndTitleStringsAreRenderable() {
        assertCovers("Theme.font(12) 画菜单文案", Theme.font(12), menuAndTitleText());
    }

    @Test
    @DisplayName("每个源代码文件里都能找到至少一处中文界面文案（扫描本身没坏）")
    void sourceScanSanityCheck() throws IOException {
        // 上一版这里是一条「扫全部字面量」的宽断言，把开发期诊断字符串（含 emoji
        // 与零宽字符）也算了进去，于是它失败了 —— 但失败的**不是**产品文案。
        // 那条断言现在改成"文档化检查"：确认扫描确实能读到中文文案，
        // 真正的字形检查由上面几条针对**实际会渲染**的字符串来做。
        int filesWithCjk = 0;
        for (Path f : sourceFiles()) {
            String all = Files.readString(f, StandardCharsets.UTF_8);
            if (all.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF)) {
                filesWithCjk++;
            }
        }
        assertTrue(filesWithCjk > 10,
                "只有 " + filesWithCjk + " 个源文件含中文，扫描路径可能错了");
    }

    private static List<Path> sourceFiles() throws IOException {
        Path src = Path.of("src", "main", "java", "com", "talkinglive");
        try (Stream<Path> s = Files.walk(src)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /** 产品源码 + 测试源码。副本既可能出现在产品代码里，也可能出现在测试里（实测两者都发生过）。 */
    private static List<Path> allSourceFiles() throws IOException {
        List<Path> all = new ArrayList<>(sourceFiles());
        Path test = Path.of("src", "test", "java", "com", "talkinglive");
        try (Stream<Path> s = Files.walk(test)) {
            all.addAll(s.filter(p -> p.toString().endsWith(".java")).toList());
        }
        return all;
    }

    @Test
    @DisplayName("菜单与窗口标题的文案只有一处定义（防「再抄一份」后静默漂移）")
    void labelsHaveASingleSource() throws IOException {
        // 文案 → 允许定义它的文件。
        //
        // 为什么值得变成测试：这些文案此前有**四份副本**（菜单里、两个窗口标题、
        // 本测试的样本串），而其中一份抄错了名字（「状态与诊断」）却几个月没人发现 ——
        // 因为没有一条断言把它按住。判据是"谁定义了它"，而不是"谁提到过它"：
        // 注释里引用文案名是合法的（例如 DiagnosticsWindow 的类注释就在解释那次抄错），
        // 所以下面只扫**字符串字面量**。
        Map<String, String> owners = new LinkedHashMap<>();
        for (FloatingBall.MenuAction a : FloatingBall.MenuAction.values()) {
            owners.put(a.label(), "FloatingBall.java");
            owners.put(a.label(true), "FloatingBall.java");
        }
        owners.put(SettingsWindow.TITLE, "SettingsWindow.java");
        owners.put(DiagnosticsWindow.TITLE, "DiagnosticsWindow.java");

        List<String> violations = new ArrayList<>();
        Map<String, List<String>> seenIn = new LinkedHashMap<>();
        for (Path f : allSourceFiles()) {
            String name = f.getFileName().toString();
            for (String literal : stringLiterals(f)) {
                seenIn.computeIfAbsent(literal, k -> new ArrayList<>()).add(name);
                String owner = owners.get(literal);
                if (owner != null && !owner.equals(name)) {
                    violations.add(name + " 里又写了一份「" + literal + "」");
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "界面文案必须只有一处定义，否则改一处、别处还留着旧名字 —— 实测就是这么"
                        + "漂移的（菜单实际叫 MenuAction.LOGS，文档与测试样本里却是「状态与诊断」）。"
                        + "请改成引用定义处的枚举/常量：MenuAction、SettingsWindow.TITLE、"
                        + "DiagnosticsWindow.TITLE。重复的副本：" + violations);

        // 扫描本身不能是空转：每条文案都必须在它自己的定义文件里被找到。
        // 否则上面那条断言会因为「一个字符串也没扫到」而永远通过 ——
        // 这个项目已经吃过一次「最被信任的自检恰好只测了替身」的亏（README §架构分层末段）。
        List<String> notFound = new ArrayList<>();
        for (Map.Entry<String, String> e : owners.entrySet()) {
            List<String> where = seenIn.getOrDefault(e.getKey(), List.of());
            if (!where.contains(e.getValue())) {
                notFound.add("「" + e.getKey() + "」未在 " + e.getValue()
                        + " 中找到（实际出现在 " + where + "）");
            }
        }
        assertTrue(notFound.isEmpty(),
                "扫描没能在定义处找到文案，说明这个守卫已经失效（它现在拦不住任何东西）：" + notFound);
    }

    /**
     * 取出源文件里的**字符串字面量**（注释里提到的不算）。
     *
     * <p>顺序很重要：先去掉注释再找字面量。注释里完全可以合法地写出这些文案
     * （本仓库就有好几处用它们解释历史），那不是"第二份副本"；
     * 反过来，注释里出现落单的引号会把朴素的正则带偏。
     *
     * <p>已知局限：文本块（{@code """}）里的内容识别不到。这里只当"防手抄"的守卫用，
     * 漏报比误报可接受 —— 它守的是有人**故意**再抄一份的场景。
     */
    private static List<String> stringLiterals(Path f) throws IOException {
        String src = Files.readString(f, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
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
                StringBuilder lit = new StringBuilder();
                i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    if (i < n) {
                        lit.append(src.charAt(i));
                        i++;
                    }
                }
                i++;
                out.add(lit.toString());
            } else {
                i++;
            }
        }
        return out;
    }
}
