package com.talkinglive.ui;

import java.awt.Color;
import java.awt.Font;

/**
 * 配色与字体（{@code DESIGN.md} §4.5）。
 *
 * <p>设计基调：常驻桌面、半透明、深色。悬浮球的颜色**本身就是状态指示**
 * （§4.4：灰=待唤醒、红+脉冲=听写中、橙=提交中、暗灰+斜杠=已暂停），
 * 所以状态色在 {@link #ballBase} / {@link #ballRing} 里集中定义，别处不许自己写死。
 */
public final class Theme {

    /** 浮窗与菜单的底色。 */
    public static final Color SURFACE = new Color(22, 25, 34, 242);
    public static final Color SURFACE_BORDER = new Color(96, 108, 140, 130);

    /** 文字：已稳定 / 仍在变 / 状态行。两级文字样式对应的三个色（§4.4）。 */
    public static final Color TEXT_STABLE = new Color(238, 241, 248);
    public static final Color TEXT_VOLATILE = new Color(113, 120, 144);
    public static final Color TEXT_STATUS = new Color(132, 140, 168);

    /** 语义色。 */
    public static final Color ERR = new Color(226, 94, 94);
    public static final Color WARN = new Color(226, 158, 74);
    public static final Color OK = new Color(94, 196, 140);
    public static final Color DIM = new Color(120, 127, 145);

    /** 设置窗口用的深色配色（与浮窗同一基调，但需要更大的面积层次）。 */
    public static final Color BG = new Color(28, 31, 40);
    public static final Color PANEL = new Color(37, 41, 53);
    public static final Color FIELD = new Color(24, 27, 36);
    public static final Color TEXT = new Color(228, 232, 242);
    public static final Color TEXT_MUTED = new Color(138, 144, 166);
    public static final Color ACCENT = new Color(94, 129, 244);
    public static final Color BORDER = new Color(72, 79, 100);

    /** 悬浮球尺寸：窗口 68px，球体只占中间 52px，外圈留给听写脉冲动画（§4.4）。 */
    public static final int BALL_WINDOW = 68;
    public static final int BALL_DIAMETER = 52;

    public static final int PREVIEW_MIN_WIDTH = 380;
    public static final int PREVIEW_TEXT_WIDTH = 340;

    private Theme() {}

    /** 悬浮球底色。{@code state} 用字符串以避免 ui 依赖 core 的枚举顺序。 */
    public static Color ballBase(String state, boolean paused) {
        if (paused) {
            return new Color(74, 80, 96);
        }
        return switch (state) {
            case "LISTENING" -> ERR;
            case "COMMITTING" -> WARN;
            default -> new Color(48, 54, 70);
        };
    }

    /** 悬浮球边缘色（hover 时会被提亮）。 */
    public static Color ballRing(String state, boolean paused) {
        if (paused) {
            return new Color(120, 127, 145);
        }
        return switch (state) {
            case "LISTENING" -> new Color(255, 150, 150);
            case "COMMITTING" -> new Color(255, 214, 150);
            default -> DIM;
        };
    }

    /**
     * 字体。
     *
     * <p>字号跟随系统 DPI（§4.4「字号跟随系统 DPI」）：以 96 DPI 为基准，
     * 按系统分辨率等比放大。Windows 上「Microsoft YaHei UI」的中文观感最好，
     * 缺失时回退到逻辑字体（Swing 会自己挑一个能显示中文的）。
     */
    public static Font font(int baseSize) {
        float scale = dpiScale();
        float size = baseSize * scale;
        for (String family : new String[] {"Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI"}) {
            Font f = new Font(family, Font.PLAIN, 12);
            // Java 对不存在的字体族会返回逻辑字体（族名变成 Dialog），据此判断可用性
            if (f.getFamily().equalsIgnoreCase(family)) {
                return f.deriveFont(size);
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(size));
    }

    /** 系统 DPI 缩放系数（96 DPI = 1.0）。 */
    public static float dpiScale() {
        try {
            int dpi = (int) Math.round(java.awt.Toolkit.getDefaultToolkit().getScreenResolution());
            return dpi <= 0 ? 1f : dpi / 96f;
        } catch (RuntimeException e) {
            return 1f;
        }
    }

    /** 粗体，同样跟随 DPI。 */
    public static Font bold(int baseSize) {
        return font(baseSize).deriveFont(Font.BOLD);
    }
}
