package talkinglive;

import java.awt.Color;
import java.awt.Font;

/** 统一的深色主题配色与字体。 */
final class Theme {

    static final Color BG     = new Color(28, 31, 40);
    static final Color PANEL  = new Color(37, 41, 53);
    static final Color FIELD  = new Color(24, 27, 36);
    static final Color TEXT   = new Color(228, 232, 242);
    static final Color DIM    = new Color(138, 144, 166);
    static final Color ACCENT = new Color(94, 129, 244);
    static final Color OK     = new Color(66, 190, 130);
    static final Color WARN   = new Color(232, 163, 61);
    static final Color ERR    = new Color(226, 94, 94);
    static final Color BORDER = new Color(72, 79, 100);

    /**
     * 用「Microsoft YaHei UI」而不是 Consolas 之类。
     * Consolas 没有任何中文字形，用它渲染中文会得到一串乱码方块。
     */
    private static final String FAMILY = "Microsoft YaHei UI";

    static Font font(int size) {
        return new Font(FAMILY, Font.PLAIN, size);
    }

    static Font bold(int size) {
        return new Font(FAMILY, Font.BOLD, size);
    }

    private Theme() {
    }
}
