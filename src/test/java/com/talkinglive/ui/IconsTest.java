package com.talkinglive.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 图标一致性的**强制**测试。
 *
 * <p>用户实测反馈原文：「设置界面不够简洁，不够清晰，<b>图标大小不一致</b>」。
 * 成因是托盘、悬浮球、设置窗口各画各的：托盘写死 32px、悬浮球写死 13×20、
 * 设置页用「✓」「✗」字符当图标。字符尺寸由字体决定、矢量图标由像素决定，
 * 放一起必然一大一小 —— {@link Icons} 就是为消除这个而存在的单一定义点。
 *
 * <p>既然「不集中定义」正是缺陷本身，就不能只靠约定。这里把两条性质变成会失败的测试：
 * <ol>
 *   <li><b>形状随尺寸等比</b>：同一图标在任意尺寸下，图形相对画布的占比必须一致
 *       （见 {@link Proportional#inkIsProportional}）。</li>
 *   <li><b>没有人绕过 {@link Icons} 画图标</b>：源码扫描 {@code ui} 包与 {@code App}，
 *       禁止裸图元调用，也禁止把对勾/叉/感叹号当图标用。</li>
 * </ol>
 */
class IconsTest {

    /** 所有图标都必须能绘制的尺寸：设置页小图标、状态行图标、大图标、托盘。 */
    private static final int[] SIZES = {Icons.SMALL, Icons.MEDIUM, Icons.LARGE, Icons.TRAY};

    private static final Path SRC = Path.of("src", "main", "java", "com", "talkinglive");

    private static BufferedImage render(Icons.Kind kind, int size, Color color) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Icons.of(kind, size, color).paintIcon(null, g, 0, 0);
        g.dispose();
        return img;
    }

    /** 不透明像素的外接框 [x0, y0, x1, y1]；全透明返回 null。 */
    private static int[] inkBounds(BufferedImage img) {
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = -1;
        int y1 = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) > 8) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return x1 < 0 ? null : new int[] {x0, y0, x1, y1};
    }

    /**
     * 图形占画布的比例，取宽高两个方向的**较大者**。
     *
     * <p>为什么取较大者而不是平均留白：留白 = 1 − 占比，对称图形的左右留白天然相等，
     * 所以「较大占比」≈「较小留白」，含义更稳定；也不会因为某方向被描边圆头多撑出
     * 半个线宽而抖动。
     */
    private static double inkRatio(BufferedImage img) {
        int[] b = inkBounds(img);
        if (b == null) {
            return 0;
        }
        double w = (b[2] - b[0] + 1) / (double) img.getWidth();
        double h = (b[3] - b[1] + 1) / (double) img.getHeight();
        return Math.max(w, h);
    }

    @Nested
    @DisplayName("每个图标在任意尺寸下都能画出来")
    class Renderable {

        @Test
        @DisplayName("所有语义 × 所有尺寸都有可见像素，且不出画布")
        void allKindsRenderAtAllSizes() {
            List<String> problems = new ArrayList<>();
            for (Icons.Kind kind : Icons.Kind.values()) {
                for (int size : SIZES) {
                    BufferedImage img = render(kind, size, Color.WHITE);
                    int[] b = inkBounds(img);
                    if (b == null) {
                        problems.add(kind + "@" + size + "px：没有任何可见像素");
                        continue;
                    }
                    // 描边圆头会溢出半个线宽，允许 1px；再多说明路径坐标写错了
                    if (b[0] < -1 || b[1] < -1 || b[2] > size || b[3] > size) {
                        problems.add(kind + "@" + size + "px：图形超出画布 "
                                + java.util.Arrays.toString(b));
                    }
                }
            }
            assertTrue(problems.isEmpty(), "图标绘制有问题：" + problems);
        }

        @Test
        @DisplayName("图标不是一整块实心方块（说明画的是形状，不是填充）")
        void notSolidBlock() {
            for (Icons.Kind kind : Icons.Kind.values()) {
                BufferedImage img = render(kind, Icons.LARGE, Color.WHITE);
                int opaque = 0;
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        if ((img.getRGB(x, y) >>> 24) > 8) {
                            opaque++;
                        }
                    }
                }
                double ratio = opaque / (double) (img.getWidth() * img.getHeight());
                assertTrue(ratio < 0.85, kind + " 覆盖了 " + Math.round(ratio * 100)
                        + "% 的画布，看起来是实心块而不是图标");
                assertTrue(ratio > 0.02, kind + " 只覆盖了 " + Math.round(ratio * 100)
                        + "% 的画布，几乎看不见");
            }
        }
    }

    @Nested
    @DisplayName("形状随尺寸等比 —— 「图标大小不一致」的回归测试")
    class Proportional {

        /**
         * 核心断言：同一图标在任意尺寸下，图形占画布的**比例**必须基本一致。
         *
         * <p>这正是用户看到的问题：托盘上的球比其他图标大一圈。若以后有人给某个尺寸
         * 单独改内边距、或像旧代码那样写死绝对像素，这里就会失败。
         *
         * <p>容差 10%：14px 下图形只占 14 个像素，描边圆头与抗锯齿会带来 ±1px 的
         * 量化误差，相对误差就有 1/14 ≈ 7%。再收紧就变成「和抗锯齿较劲」，
         * 而不是在测等比性。
         */
        @Test
        @DisplayName("同一图标的图形占比在各尺寸下一致（容差 10% 画布）")
        void inkIsProportional() {
            List<String> problems = new ArrayList<>();
            for (Icons.Kind kind : Icons.Kind.values()) {
                Double ref = null;
                String refSize = null;
                for (int size : SIZES) {
                    double ratio = inkRatio(render(kind, size, Color.WHITE));
                    if (ratio <= 0) {
                        continue;
                    }
                    if (ref == null) {
                        ref = ratio;
                        refSize = size + "px";
                        continue;
                    }
                    if (Math.abs(ratio - ref) > 0.10) {
                        problems.add(kind + "：" + refSize + " 占比 " + Math.round(ref * 100)
                                + "%，而 " + size + "px 是 " + Math.round(ratio * 100) + "%");
                    }
                }
            }
            assertTrue(problems.isEmpty(),
                    "同一图标的图形占比在不同尺寸下不一致，用户看到的就是「图标大小不一致」："
                            + problems);
        }

        @Test
        @DisplayName("图形足够大：不是画布角上的一小块")
        void inkIsNotTiny() {
            List<String> problems = new ArrayList<>();
            for (Icons.Kind kind : Icons.Kind.values()) {
                for (int size : SIZES) {
                    double ratio = inkRatio(render(kind, size, Color.WHITE));
                    if (ratio < 0.55) {
                        problems.add(kind + "@" + size + "px 只占画布 "
                                + Math.round(ratio * 100) + "%");
                    }
                }
            }
            assertTrue(problems.isEmpty(), """
                    图标在画布里太小：同一个尺寸常量下，占得小的图标看起来就「比别人的小」。
                    请在 Icons 里把该路径的坐标铺开到 2–22 的范围：
                      """ + String.join("\n  ", problems));
        }

        @Test
        @DisplayName("图形不是退化的方块（否则占比断言会失去意义）")
        void shapesAreNotDegenerate() {
            for (Icons.Kind kind : Icons.Kind.values()) {
                int[] b = inkBounds(render(kind, Icons.LARGE, Color.WHITE));
                assertNotEquals(null, b, kind + " 没有可见像素");
                assertTrue(b[2] - b[0] >= Icons.LARGE / 2 && b[3] - b[1] >= Icons.LARGE / 2,
                        kind + " 的图形太小，无法判断形状：" + java.util.Arrays.toString(b));
            }
        }
    }

    @Nested
    @DisplayName("图标定义为单一来源（源码扫描）")
    class SingleSource {

        /** 图元调用：出现这些说明有人在自己画图标，而不是走 Icons。 */
        private static final List<String> RAW_PRIMITIVES = List.of(
                "fillOval(", "drawOval(", "fillRoundRect(", "fillArc(", "drawArc(",
                "fillPolygon(", "drawPolygon(");

        /** 当图标用的字符（对勾/叉/感叹号）。字符宽度由字体决定，和矢量图标无法对齐。 */
        private static final String[] GLYPHS =
                {"\u2713", "\u2717", "\u26a0", "\u2714", "\u2718"};

        /**
         * 同一个字符的**转义写法**（{@code \u2713}）。
         *
         * <p>必须一起挡：第一版扫描只查真实字符，于是有人在设置页写了
         * {@code label.setText(in ? "\u2713" : "\u2717")} 就绕过了这条规则 ——
         * 而它造成的「字符图标与矢量图标混用」问题一模一样。
         * 这个漏洞是自己写完界面后回头核对时发现的。
         */
        private static final String[] GLYPH_ESCAPES =
                {"\\u2713", "\\u2717", "\\u26a0", "\\u2714", "\\u2718"};

        @Test
        @DisplayName("ui 包与 App 里没有绕过 Icons 的裸图元绘制")
        void noRawPrimitivesOutsideIcons() throws IOException {
            List<String> violations = new ArrayList<>();
            for (Path f : uiAndAppFiles()) {
                if (f.getFileName().toString().equals("Icons.java")) {
                    continue;   // 这里是唯一允许画图元的地方
                }
                String name = f.getFileName().toString();
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String t = lines.get(i).strip();
                    if (t.startsWith("*") || t.startsWith("//")) {
                        continue;   // 注释里会引用这些调用名来解释为什么不能这么写
                    }
                    for (String p : RAW_PRIMITIVES) {
                        if (t.contains(p)) {
                            violations.add(name + ":" + (i + 1) + " -> " + t);
                        }
                    }
                }
            }
            assertTrue(violations.isEmpty(), """
                    图标必须在 com.talkinglive.ui.Icons 里集中定义（托盘、悬浮球、设置窗口共用一份路径），
                    但发现以下位置在直接画图元，这会让同一语义在不同位置形状不一致：
                      """ + String.join("\n  ", violations) + """

                    请改调用 Icons.of(kind, size) / Icons.image(kind, size, color)。
                    """);
        }

        @Test
        @DisplayName("没有把对勾/叉/感叹号这类字符当图标用")
        void noGlyphIcons() throws IOException {
            List<String> violations = new ArrayList<>();
            for (Path f : uiAndAppFiles()) {
                if (f.getFileName().toString().equals("Icons.java")) {
                    continue;
                }
                String name = f.getFileName().toString();
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String t = lines.get(i).strip();
                    if (t.startsWith("*") || t.startsWith("//")) {
                        continue;
                    }
                    for (String g : GLYPHS) {
                        if (t.contains("\"" + g + "\"")) {
                            violations.add(name + ":" + (i + 1) + " -> " + t);
                        }
                    }
                    for (String g : GLYPH_ESCAPES) {
                        if (t.contains("\"" + g) || t.contains(g + "\"")) {
                            violations.add(name + ":" + (i + 1) + " -> " + t);
                        }
                    }
                }
            }
            assertTrue(violations.isEmpty(), """
                    对勾/叉/感叹号是**字符**，尺寸由字体决定，和矢量图标放一起必然一大一小
                    （这正是用户报告的「图标大小不一致」）。请改用 Icons.Kind.CHECK / CROSS / WARN：
                      """ + String.join("\n  ", violations));
        }

        private static List<Path> uiAndAppFiles() throws IOException {
            List<Path> out = new ArrayList<>();
            try (Stream<Path> s = Files.walk(SRC.resolve("ui"))) {
                out.addAll(s.filter(p -> p.toString().endsWith(".java")).toList());
            }
            out.add(SRC.resolve("App.java"));
            return out;
        }
    }

    @Nested
    @DisplayName("尺寸常量本身")
    class Sizes {

        @Test
        @DisplayName("尺寸阶梯单调递增 —— 否则「统一尺寸」会被写成一个值")
        void sizeLadderIsMonotonic() {
            assertTrue(Icons.SMALL < Icons.MEDIUM, "SMALL 必须小于 MEDIUM");
            assertTrue(Icons.MEDIUM < Icons.LARGE, "MEDIUM 必须小于 LARGE");
            assertTrue(Icons.LARGE < Icons.TRAY, "LARGE 必须小于 TRAY");
        }

        @Test
        @DisplayName("所有尺寸都是偶数（奇数尺寸下对称路径会左右差 1px）")
        void sizesAreEven() {
            for (int size : SIZES) {
                assertTrue(size % 2 == 0, size + "px 应为偶数");
            }
        }

        @Test
        @DisplayName("托盘图标是 ARGB 且画得出东西")
        void trayImageIsUsable() {
            BufferedImage img = Icons.trayImage();
            assertEquals(Icons.TRAY, img.getWidth());
            assertEquals(Icons.TRAY, img.getHeight());
            assertEquals(BufferedImage.TYPE_INT_ARGB, img.getType());
            assertNotEquals(null, inkBounds(img), "托盘图标必须画得出东西");
        }
    }
}
