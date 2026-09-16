package com.talkinglive.system;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;

/**
 * DPI 换算的**唯一收敛点**（{@code TECH-PLAN} §7 第 3 项）。
 *
 * <p>{@code DESIGN.md} §4.4 已识别这个风险但没说收敛到哪，技术方案给的结论是：
 * <b>进程级统一 DPI awareness，并把所有跨边界换算收敛到单一工具类</b>。
 *
 * <p>为什么必须收敛：Win32 用的是**物理像素**，Java（AWT）用的是**逻辑像素**
 * （已经带系统缩放）。设计文档的原话是「这是本项目最容易反复踩的一个坑」，
 * 而且原型阶段已经踩过一次——手动乘一次缩放系数会把坐标推到屏幕外。
 *
 * <p><b>硬规矩</b>：禁止在任何地方混用 {@code Robot.mouseMove}（逻辑像素）
 * 与 {@code SetCursorPos}（物理像素）。要跨就过这里。
 *
 * <p>本类是整个 {@code system} 层里唯一依赖 AWT 的类之一（另一个是 {@code ForegroundWatcher}
 * 之外没有别的）。core / text 不得引用它。
 */
public final class DpiScale {

    /** DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2，值 -4 以指针形式传入。 */
    private static final long PER_MONITOR_AWARE_V2 = -4L;

    private static volatile boolean awarenessSet;

    private DpiScale() {}

    /**
     * 进程级 DPI awareness。**必须在任何窗口创建之前调用一次。**
     *
     * <p>不设置的话 Windows 会对进程做 DPI 虚拟化：窗口被位图拉伸（模糊），
     * 而且 {@code GetCursorPos} 与 AWT 坐标之间的换算关系会随窗口所在显示器变化，
     * 让「光标定位」这条降级链彻底不可信。
     *
     * @return 是否设置成功（非 Windows 或系统过旧时为 false，调用方不应视为错误）
     */
    public static synchronized boolean initProcessAwareness() {
        if (awarenessSet) {
            return true;
        }
        if (!isWindows()) {
            awarenessSet = true;
            return false;
        }
        try {
            // PER_MONITOR_AWARE_V2 = -4。失败时逐级回退，不抛异常：
            // Windows 8.1 及更早没有这个函数，回退到 shcore 的进程级 awareness 即可。
            boolean ok = Win32.User32.INSTANCE.SetProcessDpiAwarenessContext(
                    com.sun.jna.Pointer.createConstant(PER_MONITOR_AWARE_V2));
            if (!ok) {
                ok = Shcore.INSTANCE.SetProcessDpiAwareness(2) == 0; // PROCESS_PER_MONITOR_DPI_AWARE, S_OK
            }
            awarenessSet = true;
            log().info("进程 DPI awareness 设置{}（DPI={}）", ok ? "成功" : "失败（回退到默认）", systemDpi());
            return ok;
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            awarenessSet = true;
            log().warn("设置进程 DPI awareness 失败：{}", e.toString());
            return false;
        }
    }

    /** shcore.dll：Windows 8.1+ 的进程级 DPI awareness。 */
    interface Shcore extends com.sun.jna.win32.StdCallLibrary {
        Shcore INSTANCE = com.sun.jna.Native.load("shcore", Shcore.class,
                com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);

        /** @return HRESULT，0 表示 S_OK */
        int SetProcessDpiAwareness(int value);
    }

    private static org.slf4j.Logger log() {
        return org.slf4j.LoggerFactory.getLogger(DpiScale.class);
    }

    // ------------------------------------------------------------ 换算

    /**
     * 逻辑像素 → 物理像素。
     *
     * @param dpi 目标显示器/窗口的 DPI（96 = 100%）
     */
    public static int logicalToPhysical(int logical, int dpi) {
        return (int) Math.round(logical * dpi / 96.0);
    }

    /** 物理像素 → 逻辑像素。 */
    public static int physicalToLogical(int physical, int dpi) {
        return (int) Math.round(physical * 96.0 / dpi);
    }

    /** 窗口所在显示器的 DPI。句柄无效时回退到主屏 DPI。 */
    public static int dpiForWindow(long hwnd) {
        if (isWindows() && hwnd != 0) {
            try {
                int dpi = Win32.User32.INSTANCE.GetDpiForWindow(Win32.hwndOf(hwnd));
                if (dpi > 0) {
                    return dpi;
                }
            } catch (UnsatisfiedLinkError | RuntimeException ignored) {
                // 回退
            }
        }
        return systemDpi();
    }

    /** 当前进程视角的系统 DPI。 */
    public static int systemDpi() {
        try {
            return (int) Math.round(Toolkit.getDefaultToolkit().getScreenResolution());
        } catch (RuntimeException e) {
            return 96;
        }
    }

    /** AWT 逻辑像素 → 物理像素（用于 Win32 调用）。 */
    public static int awtToWin32(int logical) {
        return logicalToPhysical(logical, systemDpi());
    }

    /** Win32 物理像素 → AWT 逻辑像素（用于移动 Swing 窗口）。 */
    public static int win32ToAwt(int physical) {
        return physicalToLogical(physical, systemDpi());
    }

    // ------------------------------------------------------------ 多显示器

    /**
     * 所有显示器的**逻辑**边界并集。
     *
     * <p>用途：悬浮球「拖到屏幕外必须夹回来」（§4.4）与浮窗「屏幕边缘翻转」（§4.4）
     * 都要知道完整的可用空间。用 AWT 的逻辑坐标，因为调用方是 Swing。
     */
    public static Rectangle virtualBounds() {
        Rectangle bounds = new Rectangle();
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            for (java.awt.GraphicsDevice device : ge.getScreenDevices()) {
                bounds = bounds.union(device.getDefaultConfiguration().getBounds());
            }
        } catch (RuntimeException | Error e) {
            // 无头环境或图形子系统异常：回退到 Toolkit 的屏幕尺寸
        }
        if (bounds.isEmpty()) {
            var d = Toolkit.getDefaultToolkit().getScreenSize();
            bounds = new Rectangle(0, 0, d.width, d.height);
        }
        return bounds;
    }

    /**
     * 把窗口矩形夹在虚拟屏幕内（§4.4「拖动必须夹在屏幕范围内」）。
     *
     * @return 夹紧后的左上角坐标
     */
    public static java.awt.Point clampToScreen(int x, int y, int w, int h) {
        Rectangle vb = virtualBounds();
        int maxX = vb.x + Math.max(0, vb.width - w);
        int maxY = vb.y + Math.max(0, vb.height - h);
        return new java.awt.Point(
                Math.max(vb.x, Math.min(x, maxX)),
                Math.max(vb.y, Math.min(y, maxY)));
    }

    /** 是否 Windows。 */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
