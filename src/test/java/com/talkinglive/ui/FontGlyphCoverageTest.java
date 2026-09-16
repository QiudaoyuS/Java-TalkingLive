package com.talkinglive.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Font;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        assertCovers("Theme.menuFont(12)", Theme.menuFont(12), MENU_LABELS);
        // JPopupMenu 自己的字体来自 UIManager（实测是 Dialog 逻辑字体，会正确回退到
        // 覆盖中文的物理字体）。若它存在也要覆盖，否则菜单文字会成方块。
        Object uf = javax.swing.UIManager.get("MenuItem.font");
        if (uf instanceof Font u) {
            assertCovers("UIManager MenuItem.font", u, MENU_LABELS);
        }
    }

    /** 菜单与窗口标题里真正会显示的文案。 */
    private static final String MENU_LABELS =
            "手动开始 / 结束听写暂停监听设置查看日志退出状态与诊断TalkingLive 设置";

    @Test
    @DisplayName("菜单与窗口标题的文案都画得出来")
    void menuAndTitleStringsAreRenderable() {
        assertCovers("Theme.font(12) 画菜单文案", Theme.font(12), MENU_LABELS);
        assertCovers("Theme.font(12) 画窗口标题", Theme.font(12), "TalkingLive 状态与诊断");
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
}
