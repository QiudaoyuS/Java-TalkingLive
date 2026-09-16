package com.talkinglive.ui;

import java.awt.Color;
import java.awt.Font;

/**
 * 配色与字体（{@code DESIGN.md} §4.5）。
 *
 * <p><b>设计基调：Apple 式简洁 + 白色主色调。</b>这一版是从深色主题整体换过来的，
 * 换的理由是产品形态本身：它常驻桌面、只有一颗悬浮球 + 一个提示条，
 * 深色面板在明亮的桌面环境里像一块"贴在屏幕上的补丁"；白色配大量留白才像
 * 系统原生的一部分。
 *
 * <p>几条贯穿全局的规则（不是随手挑的颜色）：
 * <ul>
 *   <li><b>主色是白</b>：{@link #SURFACE} 是主背景，{@link #BG} 是窗口背景。
 *       两者都接近纯白，靠 {@link #BORDER} 的 1px 极浅描边分层，
 *       而不是靠深色块。</li>
 *   <li><b>文字只有三级</b>：{@link #TEXT}（主）/ {@link #TEXT_MUTED}（次要）/
 *       {@link #TEXT_FAINT}（淡淡一笔）。Apple 的层级感来自灰度而不是字号，
 *       所以各级之间拉开明显差距，但不靠加粗。</li>
 *   <li><b>强调色只用于"可操作"</b>：{@link #ACCENT} 是系统蓝，只出现在
 *       按钮/链接/选中的东西上。语义色（{@link #ERR}/{@link #OK}/{@link #WARN}）
 *       只在表达状态时出现，绝不拿来装饰。</li>
 *   <li><b>投影极淡</b>：{@link #SHADOW} 的 alpha 只有 20 上下。白色浮层靠
 *       极淡投影"浮起来"，投影一重就变成廉价感。</li>
 * </ul>
 *
 * <p>悬浮球的颜色**本身就是状态指示**（§4.4：待唤醒 / 听写中 / 提交中 / 已暂停），
 * 所以状态色在 {@link #ballBase} / {@link #ballRing} / {@link #waveColor} 里集中定义，
 * 别处不许自己写死。
 */
public final class Theme {

    // ==================== 主色（白） ====================

    /** 浮窗与菜单的底色：近白，略带透明度让它"浮"在桌面内容之上。 */
    public static final Color SURFACE = new Color(255, 255, 255, 244);
    /** 浮窗描边：极浅的一笔，用来在白色背景上划出边界。 */
    public static final Color SURFACE_BORDER = new Color(0, 0, 0, 26);

    /**
     * 设置/诊断窗口的背景。
     *
     * <p>取 {@code #f2f2f4}（比 Apple 的 systemGray6 再深一档）：窗口底比输入框
     * **深一点**，白色输入框才有"浮在纸上"的感觉；同时不能深到显脏。
     * 实测 #fafafc 时窗口底与输入框底只差 4%，整个界面发平 ——
     * 但光靠加深背景不够，控件边界主要来自 {@link #SHADOW}（见 SettingsWindow.field）。
     */
    public static final Color BG = new Color(242, 242, 244);
    /** 卡片、输入框这类"再上一层"的面。 */
    public static final Color PANEL = new Color(255, 255, 255);
    /** 输入框内部；白色主题下输入框比背景**更白**才显得可编辑。 */
    public static final Color FIELD = new Color(255, 255, 255);
    /** 极浅的分隔线与描边。 */
    public static final Color BORDER = new Color(0, 0, 0, 28);
    /** hover / 选中态的浅灰底。 */
    public static final Color HOVER = new Color(0, 0, 0, 12);

    // ==================== 文字三级 ====================

    /** 主文字（Apple 的 label 色）。 */
    public static final Color TEXT = new Color(29, 29, 31);
    /** 次要文字（约 60% 不透明度的 label）。 */
    public static final Color TEXT_MUTED = new Color(110, 110, 115);
    /** 淡淡一笔（约 35%）：注解、页脚这种"可看可不看"的信息。 */
    public static final Color TEXT_FAINT = new Color(170, 170, 175);
    /** 浮窗上的稳定/未稳定两级文字（§4.4）。 */
    public static final Color TEXT_STABLE = TEXT;
    public static final Color TEXT_VOLATILE = new Color(168, 168, 173);
    /** 提示条右侧的状态行文字。 */
    public static final Color TEXT_STATUS = TEXT_MUTED;

    // ==================== 语义色 ====================

    /** 系统蓝。**只用于可操作的元素**（按钮、链接、选中项）。 */
    public static final Color ACCENT = new Color(0, 122, 255);
    /** 成功。 */
    public static final Color OK = new Color(52, 199, 89);
    /** 警告。 */
    public static final Color WARN = new Color(255, 149, 0);
    /** 错误 / 听写中。 */
    public static final Color ERR = new Color(255, 59, 48);

    /** 极淡投影（浮层"浮起来"靠它，alpha 必须很小）。 */
    public static final Color SHADOW = new Color(0, 0, 0, 22);
    /** 稍强一点的投影，给悬浮球用。 */
    public static final Color SHADOW_STRONG = new Color(0, 0, 0, 38);

    // ==================== 日志/自检这类"代码面" ====================

    /**
     * 日志与自检文本区的底色。
     *
     * <p>刻意**不用**深色终端面：白色窗口里嵌一块黑色的确很"极客"，
     * 但与 Apple 式简洁相反 —— 它会把窗口切成两块互不相干的区域。
     * 极浅灰底 + 深色字既保持"这是原始输出"的语义，又不打断整体。
     */
    public static final Color CODE_BG = new Color(246, 246, 248);
    /** 代码面上的文字。 */
    public static final Color CODE_TEXT = new Color(60, 60, 67);

    // ==================== 悬浮球尺寸 ====================

    /** 悬浮球尺寸：窗口 68px，球体只占中间 52px，外圈留给听写脉冲动画（§4.4）。 */
    public static final int BALL_WINDOW = 68;
    public static final int BALL_DIAMETER = 52;

    public static final int PREVIEW_MIN_WIDTH = 380;
    public static final int PREVIEW_TEXT_WIDTH = 340;

    private Theme() {}

    // ==================== 悬浮球状态色 ====================

    /**
     * 球体底色。
     *
     * <p>白色主题下球体**始终是白的**，状态靠内部波浪柱的颜色与幅度表达 ——
     * 这是有意的：把整个球染红会很扎眼，而一颗白球里蓝色/红色的声浪既清楚又克制。
     * 只有"已暂停"例外，那时球体压成浅灰，因为它表示"这个控件现在不工作"。
     */
    public static Color ballBase(String state, boolean paused) {
        if (paused) {
            return new Color(242, 242, 245);
        }
        return Color.WHITE;
    }

    /** 球体描边。听写中时描边带一点状态色，作为颜色之外的第二个信号（无障碍）。 */
    public static Color ballRing(String state, boolean paused) {
        if (paused) {
            return new Color(0, 0, 0, 30);
        }
        return switch (state) {
            case "LISTENING" -> new Color(255, 59, 48, 90);
            case "COMMITTING" -> new Color(255, 149, 0, 90);
            default -> new Color(0, 0, 0, 30);
        };
    }

    /**
     * 球内**波浪柱**的颜色。
     *
     * <p>待唤醒时用中性灰（它只是在待命，不该抢注意力）；一旦开始听写就换成
     * 系统蓝，因为此刻"它在工作"是最重要的信息。
     */
    public static Color waveColor(String state, boolean paused) {
        if (paused) {
            return TEXT_FAINT;
        }
        return switch (state) {
            case "LISTENING" -> ACCENT;
            case "COMMITTING" -> WARN;
            default -> new Color(142, 142, 147);
        };
    }

    // ==================== 字体 ====================

    /**
     * 字体。
     *
     * <p>字号跟随系统 DPI（§4.4）：以 96 DPI 为基准，按系统分辨率等比放大。
     * 家族按 Apple 的观感挑：优先 {@code SF Pro}（装了的话），
     * 其次 Windows 上最接近它的 {@code Microsoft YaHei UI}（中文字形也最好），
     * 最后退回逻辑字体（Swing 会自己挑一个能显示中文的）。
     */
    public static Font font(int baseSize) {
        float scale = dpiScale();
        float size = baseSize * scale;
        for (String family : new String[] {
            "SF Pro Text", "SF Pro Display", "Helvetica Neue",
            "Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI"}) {
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

    /**
     * 粗体。
     *
     * <p>Apple 的层级主要靠灰度而不是字重，所以这里刻意**用得少**：
     * 只有窗口标题、卡片标题这种需要与正文区分的地方才加粗，
     * 而不是每个标签都加粗（那会显得又重又乱）。
     */
    public static Font bold(int baseSize) {
        return font(baseSize).deriveFont(Font.BOLD);
    }

    /**
     * 等宽字体，用于日志与自检这类"原始输出"。
     *
     * <p><b>这里踩过两个真实的坑，都值得写清楚。</b>
     *
     * <p><b>坑一：字体没有中文字形。</b>第一版按
     * 「SF Mono / Menlo / Consolas / Cascadia Mono / Microsoft YaHei UI」的顺序挑，
     * 注释里还写着「家族列表里每个都有中文字形」—— 那句话是**错的**，而且没人验证过。
     * 实测：{@code mono = Consolas}，而 Consolas 的 {@code canDisplayUpTo("日志")} 返回 0，
     * 即**第一个字就没有字形** —— 日志里的汉字全成了空方块（□□）。
     * 它的样子和"编码乱码"一模一样，只看截图分不出来，但根因完全不同。
     *
     * <p><b>坑二：为了中文把等宽丢了。</b>只加一条"能显示中文"的条件之后，
     * 选中的变成了 Microsoft YaHei UI —— 它覆盖中文，但**不是等宽**
     * （实测 i 宽 3、W 宽 12），日志的列立刻歪掉。
     *
     * <p><b>结论：两个条件必须同时满足，而物理字体里没有两全的</b> ——
     * Consolas/Segoe UI 等宽无中文，YaHei/Noto 有中文不等宽。
     * 出路是**逻辑字体**：{@code Monospaced} 与 {@code DialogInput} 由 JVM 映射到
     * 既等宽又覆盖中文的物理字体（本机实测两者都满足）。
     * 所以逻辑字体放在最前，物理等宽字体只在确认有中文字形时才用。
     *
     * <p>这个坑由 {@code FontGlyphCoverageTest} 钉住：它同时断言"覆盖中文"与"确实等宽"，
     * 任何一边退步都会失败。
     */
    public static Font mono(int baseSize) {
        float size = baseSize * dpiScale();
        for (String family : new String[] {
            "Monospaced", "DialogInput",                 // 逻辑字体：等宽 + 中文都满足
            "SF Mono", "Menlo", "Cascadia Mono", "Consolas",   // 物理等宽：有中文才用
            "Microsoft YaHei UI", "Microsoft YaHei"}) {        // 最后才牺牲等宽保中文
            Font f = new Font(family, Font.PLAIN, 12);
            boolean usable = isRealFamily(f, family) && hasCjkGlyphs(f);
            if (usable) {
                return f.deriveFont(size);
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, Math.round(size));
    }

    /**
     * 菜单字体（悬浮球的 Swing 菜单与托盘的 AWT 菜单都用它）。
     *
     * <p><b>为什么菜单要单独一个入口</b>：菜单文字曾经成过方块。根因有两层 ——
     * 一是 JVM 默认菜单字体在 150% DPI 下解析不到中文字形；二是（历史上的）托盘菜单
     * 走的是原生 Win32 菜单，它根本不认 AWT 设的字体。现在只剩自绘的 Swing 菜单，
     * 但仍要用一个"已确认能画中文"的字体入口，避免默认字体再次把汉字画成方块。
     *
     * <p>这里把"菜单用哪个字体"变成一个可检查的对象：先挑有中文字形的物理字体，
     * 挑不到就退回逻辑字体 {@code Dialog}（JVM 会映射到覆盖中文的物理字体）。
     * 判据仍是 {@link #hasCjkGlyphs} —— 元测试
     * {@code FontGlyphCoverageTest} 会断言它的返回值得以画出中文。
     */
    public static Font menuFont(int baseSize) {
        float size = baseSize * dpiScale();
        for (String family : new String[] {"Microsoft YaHei UI", "Microsoft YaHei", "Dialog"}) {
            Font f = new Font(family, Font.PLAIN, 12);
            if (isRealFamily(f, family) && hasCjkGlyphs(f)) {
                return f.deriveFont(size);
            }
        }
        return new Font(Font.DIALOG, Font.PLAIN, Math.round(size));
    }

    /**
     * 这个 {@link Font} 是否真的解析到了指定族。
     *
     * <p>必须显式判断：{@code new Font("SF Pro Text", ...)} 在不存在的族上**不会抛异常**，
     * 而是返回回退字体 Dialog —— 而 Dialog 恰好覆盖中文，
     * 于是"能否显示中文"这条判据会被不存在的字体骗过去。
     */
    static boolean isRealFamily(Font f, String family) {
        return f.getFamily().equalsIgnoreCase(family)
                || f.getName().equalsIgnoreCase(family);
    }

    /** 这个字体是否覆盖常用汉字（用 {@code canDisplayUpTo}，它比"渲染后数墨量"可靠）。 */
    static boolean hasCjkGlyphs(Font f) {
        return f.canDisplayUpTo("\u65e5\u5fd7\u6d4b\u8bd5\u72b6\u6001\u8bca\u65ad") < 0;
    }
}
