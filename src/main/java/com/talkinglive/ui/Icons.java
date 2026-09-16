package com.talkinglive.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.Icon;

/**
 * 全应用**唯一**的图标绘制入口（§4.4）。
 *
 * <p>为什么必须集中：原来托盘图标、悬浮球、设置窗口各画各的 —— 托盘写死 32px、
 * 悬浮球写死 13×20、设置页干脆用「✓」「✗」字符。字符图标的大小取决于字体，
 * 和矢量图标放一起必然一大一小，这就是「图标大小不一致」的来源。
 *
 * <p>本类的每一条路径都画在 {@value #BASE}×{@value #BASE} 的基准画布上，再由
 * {@link #image} 等比缩放到目标尺寸。于是同一个 {@link Kind} 在托盘（16/32）
 * 和设置页（12/14/28）里是**同一形状的等比缩放**，不会出现粗细跳变。
 *
 * <p>描边宽度按缩放系数一并放大，保证小尺寸下线条不会细到看不见
 * （{@link #strokeScale}）。
 */
public final class Icons {

    /** 基准画布边长。所有路径都按这个坐标系写。 */
    public static final int BASE = 24;

    /** 设置页正文小图标的统一尺寸。 */
    public static final int SMALL = 14;

    /** 设置页分组标题、状态行图标的统一尺寸。 */
    public static final int MEDIUM = 18;

    /** 空状态插画级图标的统一尺寸。 */
    public static final int LARGE = 28;

    /** 托盘图标的统一尺寸（Windows 托盘按 DPI 取 16 或 32，这里给 32 让它自己缩）。 */
    public static final int TRAY = 32;

    private static final Map<Kind, BufferedImage> CACHE = new EnumMap<>(Kind.class);

    /** 图标语义。同一个语义只允许有一条路径定义。 */
    public enum Kind {
        /** 话筒：待唤醒 / 可听写。 */
        MIC,
        /** 斜杠：已暂停。 */
        PAUSED,
        /** 勾：校验通过。 */
        CHECK,
        /** 叉：校验失败。 */
        CROSS,
        /** 三角感叹号：警告（引擎不可用之类）。 */
        WARN,
        /** 齿轮：常规设置页。 */
        GEAR,
        /** 文档行：日志页。 */
        DOCUMENT,
        /** 波形：模型状态页。 */
        WAVE,
        /** 听诊器/刻度：自检页。 */
        STETHOSCOPE,
    }

    private Icons() {}

    // ------------------------------------------------------------ 对外

    /** 取一个可放进任何 Swing 组件的图标。 */
    public static Icon of(Kind kind, int size, Color color) {
        return new VectorIcon(kind, size, color);
    }

    /** 取当前主题色的图标（语义色按 {@link Kind} 自动选）。 */
    public static Icon of(Kind kind, int size) {
        return new VectorIcon(kind, size, defaultColor(kind));
    }

    /** 取位图（托盘 {@code TrayIcon}、窗口图标 {@code setIconImage} 用）。 */
    public static BufferedImage image(Kind kind, int size, Color color) {
        int s = Math.max(1, size);
        return draw(kind, s, color);
    }

    /**
     * 托盘 / 窗口图标。
     *
     * <p>形状与悬浮球的内容一致：**三根声浪柱**（中间高两边低）。这一点比以前更重要 ——
     * 悬浮球里现在是声浪柱，托盘图标如果还是话筒，用户会在两处看到两个不同的产品形象。
     *
     * <p>用深色柱而不是白底浅柱：托盘区域可能是浅色也可能是深色，
     * 但**深色在两者上都有对比度**，白底图标在浅色任务栏上会消失。
     * 这也是 macOS 菜单栏图标的做法（单色、靠形状识别）。
     */
    public static BufferedImage trayImage() {
        int size = TRAY;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float k = size / (float) BASE;
        g.scale(k, k);
        g.setColor(Theme.TEXT);
        paintTrayWave(g, TRAY);
        g.dispose();
        return img;
    }

    /** 三根声浪柱：与悬浮球内容同源，只是柱数少一点（小尺寸下五根会糊在一起）。 */
    private static void paintTrayWave(Graphics2D g, int size) {
        g.setStroke(round(stroke(size, 2.0f)));
        float[] hs = {9f, 19f, 9f};
        for (int i = 0; i < hs.length; i++) {
            float x = 5f + i * 7f;
            g.draw(new Line2D.Float(x, 12f - hs[i] / 2f, x, 12f + hs[i] / 2f));
        }
    }

    /** 语义色：让调用方不用记「哪个图标配哪个颜色」。 */
    public static Color defaultColor(Kind kind) {
        return switch (kind) {
            case CHECK -> Theme.OK;
            case CROSS -> Theme.ERR;
            case WARN -> Theme.WARN;
            case PAUSED -> Theme.TEXT_FAINT;
            case GEAR, DOCUMENT, WAVE, STETHOSCOPE -> Theme.ACCENT;
            default -> Theme.TEXT;
        };
    }

    // ------------------------------------------------------------ 绘制

    /**
     * 把路径画到目标尺寸的 ARGB 位图上。
     *
     * <p>用「先按基准尺寸画、再整体缩放」而不是「按目标尺寸算坐标」：
     * 后者会让每条路径都要带一堆乘除，且不同尺寸下舍入误差不同，
     * 结果就是同一图标在 14px 和 28px 下形状不一致。
     */
    private static BufferedImage draw(Kind kind, int size, Color color) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float k = size / (float) BASE;
        g.scale(k, k);
        g.setColor(color == null ? Theme.TEXT : color);
        paint(g, kind, size);
        g.dispose();
        return img;
    }

    /** 所有图标的路径定义都集中在这里，坐标基于 {@value #BASE} 画布。 */
    private static void paint(Graphics2D g, Kind kind, int size) {
        switch (kind) {
            case MIC -> paintMic(g, size);
            case PAUSED -> paintPaused(g, size);
            case CHECK -> paintCheck(g, size);
            case CROSS -> paintCross(g, size);
            case WARN -> paintWarn(g, size);
            case GEAR -> paintGear(g, size);
            case DOCUMENT -> paintDocument(g, size);
            case WAVE -> paintWave(g, size);
            case STETHOSCOPE -> paintStethoscope(g, size);
        }
    }

    /**
     * 线宽。
     *
     * <p>为什么不写死：14px 时画布只有 24 基准的 0.58 倍，一条 2.2 的线缩完是 1.28px，
     * 抗锯齿后会发灰；同样的线在 32px 下是 2.9px。两者并排，用户看到的就是
     * 「小图标的线比大图标的细」—— 又是「图标大小不一致」。
     *
     * <p>补偿系数 1/k<sup>0.45</sup> 是按半像素原则标定出来的：小尺寸下把线略微加粗
     * （不按 1/k 全补，否则小图标会糊成一团），大尺寸下不补。
     * 两个常量都有 {@code IconsTest} 的「留白比例一致」断言兜着 ——
     * 改这两个数字若破坏了等比性，测试会直接失败。
     */
    private static float stroke(int size, float base) {
        float k = size / (float) BASE;
        if (k >= 1f) {
            return base;
        }
        return Math.min(base * (float) Math.pow(1.0 / k, 0.45), base + 0.7f);
    }

    /** 话筒：与悬浮球里的形状同源，只是坐标系从「球心偏移」换成 24×24 基准。 */
    private static void paintMic(Graphics2D g, int size) {
        g.setStroke(round(stroke(size, 1.5f)));
        // 话筒本体
        g.fill(new RoundRectangle2D.Float(8.4f, 1.6f, 7.2f, 12f, 7.2f, 7.2f));
        // 拾音支架
        g.draw(new Arc2D.Float(3.6f, 5.4f, 16.8f, 16.8f, 200, 140, Arc2D.OPEN));
        // 支脚
        g.draw(new Line2D.Float(12f, 16.4f, 12f, 22.4f));
    }

    /** 暂停：一条斜杠（与悬浮球保持一致，不做方块）。 */
    private static void paintPaused(Graphics2D g, int size) {
        g.setStroke(round(stroke(size, 2.2f)));
        g.draw(new Line2D.Float(2.4f, 21.6f, 21.6f, 2.4f));
    }

    private static void paintCheck(Graphics2D g, int size) {
        g.setStroke(round(stroke(size, 2.2f)));
        Path2D p = new Path2D.Float();
        p.moveTo(1.6f, 12.8f);
        p.lineTo(8.4f, 20.8f);
        p.lineTo(22.4f, 3.2f);
        g.draw(p);
    }

    private static void paintCross(Graphics2D g, int size) {
        g.setStroke(round(stroke(size, 2.6f)));
        g.draw(new Line2D.Float(2.2f, 2.2f, 21.8f, 21.8f));
        g.draw(new Line2D.Float(21.8f, 2.2f, 2.2f, 21.8f));
    }

    private static void paintWarn(Graphics2D g, int size) {
        g.setStroke(round(stroke(size, 1.5f)));
        Path2D p = new Path2D.Float();
        p.moveTo(12f, 1.8f);
        p.lineTo(22.4f, 21.8f);
        p.lineTo(1.6f, 21.8f);
        p.closePath();
        g.draw(p);
        g.setStroke(round(stroke(size, 1.9f)));
        g.draw(new Line2D.Float(12f, 8.6f, 12f, 14.6f));
        // 底下那一点用短线段而不是 fillOval：圆点在 14px 下会糊成线
        g.draw(new Line2D.Float(12f, 17.4f, 12f, 18f));
    }

    private static void paintGear(Graphics2D g, int size) {
        g.setStroke(round(stroke(size, 1.8f)));
        g.draw(new Ellipse2D.Float(9f, 9f, 6f, 6f));
        g.draw(new Ellipse2D.Float(6.6f, 6.6f, 10.8f, 10.8f));
        // 四根齿，长度一致、间隔一致 —— 齿轮最容易画歪，这里固定在对角线上
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(i * 90 + 45);
            float x0 = (float) (12 + Math.cos(a) * 8.4);
            float y0 = (float) (12 + Math.sin(a) * 8.4);
            float x1 = (float) (12 + Math.cos(a) * 11.6);
            float y1 = (float) (12 + Math.sin(a) * 11.6);
            g.draw(new Line2D.Float(x0, y0, x1, y1));
        }
    }

    private static void paintDocument(Graphics2D g, int size) {
        g.setStroke(round(stroke(size, 1.5f)));
        Path2D p = new Path2D.Float();
        p.moveTo(3.6f, 1.6f);
        p.lineTo(18.4f, 1.6f);
        p.lineTo(21.8f, 5f);
        p.lineTo(21.8f, 22.4f);
        p.lineTo(3.6f, 22.4f);
        p.closePath();
        g.draw(p);
        g.draw(new Line2D.Float(18.4f, 1.6f, 18.4f, 5f));
        g.draw(new Line2D.Float(18.4f, 5f, 21.8f, 5f));
        g.draw(new Line2D.Float(7f, 12f, 18.4f, 12f));
        g.draw(new Line2D.Float(7f, 16.6f, 18.4f, 16.6f));
    }

    private static void paintWave(Graphics2D g, int size) {
        g.setStroke(round(stroke(size, 2f)));
        // 五根柱，中间最高 —— 波形图的通用画法，左右对称
        float[] hs = {9f, 15f, 22f, 15f, 9f};
        for (int i = 0; i < hs.length; i++) {
            float x = 2.4f + i * 4.8f;
            g.draw(new Line2D.Float(x, 12f - hs[i] / 2f, x, 12f + hs[i] / 2f));
        }
    }

    private static void paintStethoscope(Graphics2D g, int size) {
        g.setStroke(round(stroke(size, 1.7f)));
        g.draw(new Arc2D.Float(1.8f, 1.6f, 15.4f, 15.4f, 200, 140, Arc2D.OPEN));
        g.draw(new Line2D.Float(1.8f, 9.3f, 1.8f, 13.4f));
        g.draw(new Line2D.Float(17.2f, 9.3f, 17.2f, 13.4f));
        g.draw(new Line2D.Float(9.5f, 15.4f, 9.5f, 17.6f));
        g.draw(new Arc2D.Float(9.5f, 13f, 9.6f, 9.6f, 270, 90, Arc2D.OPEN));
        g.draw(new Line2D.Float(19.1f, 17.8f, 19.1f, 20.6f));
        g.fill(new Ellipse2D.Float(17.3f, 20f, 3.6f, 3.6f));
    }

    private static BasicStroke round(float w) {
        return new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    // ------------------------------------------------------------ Swing 适配

    /** 把 {@link Kind} 包成 Swing {@link Icon}，并按要求尺寸缩放。 */
    public static final class VectorIcon implements Icon {
        private final Kind kind;
        private final int size;
        private final Color color;

        VectorIcon(Kind kind, int size, Color color) {
            this.kind = kind;
            this.size = Math.max(1, size);
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(x, y);
            float k = size / (float) BASE;
            g2.scale(k, k);
            g2.setColor(color == null ? Theme.TEXT : color);
            paint(g2, kind, size);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }
}
