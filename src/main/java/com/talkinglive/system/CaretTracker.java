package com.talkinglive.system;

import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 光标定位的**降级链**（{@code DESIGN.md} §4.4 / §7）。
 *
 * <p>设计文档点名的风险：{@code GetCaretPos} 在 Chrome / Electron 里经常取不到——
 * 那些程序是自绘光标，根本不告诉 Windows 光标在哪。所以必须按顺序降级：
 *
 * <pre>
 *   ① 目标窗口里的真实光标（GetCaretPos + ClientToScreen）
 *   ② 取不到 → 目标窗口矩形（贴在标题栏下方）
 *   ③ 窗口也取不到 → 跟随鼠标（GetCursorPos）
 * </pre>
 *
 * <p><b>坐标空间（§4.4 的硬规矩）：</b>{@code GetCaretPos / GetCursorPos / GetWindowRect}
 * 返回的都是**物理像素**，而 Swing 的 {@code setLocation} 用**逻辑像素**。
 * 本类对外统一返回**AWT 逻辑坐标**，换算全部经过 {@link DpiScale}——
 * 别处不许再自己乘缩放系数。
 *
 * <p>本类属于 {@code system} 层（依赖 JNA）。
 */
public final class CaretTracker {

    private static final Logger log = LoggerFactory.getLogger(CaretTracker.class);

    /** 定位结果来自哪一级降级。 */
    public enum Source {
        /** 真实光标。 */
        CARET("光标"),
        /** 目标窗口矩形。 */
        WINDOW("目标窗口"),
        /** 鼠标位置。 */
        MOUSE("鼠标"),
        /** 全都拿不到。 */
        NONE("屏幕中心");

        private final String display;

        Source(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    /** 定位结果。坐标是 **AWT 逻辑像素**。 */
    public record Position(int x, int y, Source source) {}

    private CaretTracker() {}

    /**
     * 顺着降级链取一个用于摆放浮窗的逻辑坐标。
     *
     * @param targetWindow 段落开始时的目标窗口句柄；0 表示未知
     * @param offsetX      浮窗相对锚点的水平偏移（逻辑像素）
     * @param offsetY      浮窗相对锚点的垂直偏移（逻辑像素）
     */
    public static Position locate(long targetWindow, int offsetX, int offsetY) {
        Position caret = caretPosition(targetWindow);
        if (caret != null) {
            log.debug("浮窗锚点：光标 {}（窗口 0x{}）", caret, Long.toHexString(targetWindow));
            return new Position(caret.x() + offsetX, caret.y() + offsetY, Source.CARET);
        }
        Position win = windowPosition(targetWindow);
        if (win != null) {
            log.debug("浮窗锚点：目标窗口矩形 {}（GetCaretPos 取不到，已降级）", win);
            return new Position(win.x() + offsetX, win.y() + offsetY, Source.WINDOW);
        }
        Position mouse = mousePosition();
        if (mouse != null) {
            log.debug("浮窗锚点：鼠标 {}（窗口也取不到，已降级）", mouse);
            return new Position(mouse.x() + offsetX, mouse.y() + offsetY, Source.MOUSE);
        }
        log.debug("浮窗锚点：全部取不到，由调用方居中放置");
        return new Position(Integer.MIN_VALUE, Integer.MIN_VALUE, Source.NONE);
    }

    /**
     * 真实光标位置（物理 → 逻辑）。
     *
     * @return null 表示取不到（Chrome / Electron 常态）
     */
    public static Position caretPosition(long targetWindow) {
        if (!DpiScale.isWindows()) {
            return null;
        }
        try {
            HWND h = Win32.hwndOf(targetWindow);
            POINT p = new POINT();
            if (!Win32.User32.INSTANCE.GetCaretPos(p)) {
                return null;
            }
            // 光标坐标是相对**客户区**的，而 GetCaretPos 在自绘控件里会返回 (0,0)
            // 这种无意义的值——必须排除，否则浮窗会跑到窗口左上角。
            if (p.x == 0 && p.y == 0) {
                return null;
            }
            if (h != null && !Win32.User32.INSTANCE.ClientToScreen(h, p)) {
                return null;
            }
            int dpi = DpiScale.dpiForWindow(targetWindow);
            return new Position(DpiScale.physicalToLogical(p.x, dpi), DpiScale.physicalToLogical(p.y, dpi),
                    Source.CARET);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** 目标窗口矩形（取顶部，浮窗贴在标题栏下方）。 */
    public static Position windowPosition(long targetWindow) {
        if (!DpiScale.isWindows() || targetWindow == 0) {
            return null;
        }
        try {
            RECT r = new RECT();
            if (!Win32.User32.INSTANCE.GetWindowRect(Win32.hwndOf(targetWindow), r)) {
                return null;
            }
            int dpi = DpiScale.dpiForWindow(targetWindow);
            return new Position(DpiScale.physicalToLogical(r.left, dpi), DpiScale.physicalToLogical(r.top, dpi),
                    Source.WINDOW);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** 鼠标位置（物理 → 逻辑）。 */
    public static Position mousePosition() {
        if (!DpiScale.isWindows()) {
            return null;
        }
        try {
            POINT p = new POINT();
            if (!Win32.User32.INSTANCE.GetCursorPos(p)) {
                return null;
            }
            int dpi = DpiScale.systemDpi();
            return new Position(DpiScale.physicalToLogical(p.x, dpi), DpiScale.physicalToLogical(p.y, dpi),
                    Source.MOUSE);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** 当前前台窗口句柄。 */
    public static long foregroundWindow() {
        if (!DpiScale.isWindows()) {
            return 0;
        }
        try {
            return Win32.hwndValue(Win32.User32.INSTANCE.GetForegroundWindow());
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return 0;
        }
    }
}
