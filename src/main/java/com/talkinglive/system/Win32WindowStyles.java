package com.talkinglive.system;

import com.sun.jna.Native;
import java.awt.Component;
import java.awt.Window;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 给 Swing 窗口设置 Win32 扩展样式（{@code DESIGN.md} §4.4）。
 *
 * <p>为什么 Swing 自带的设置不够：{@code setFocusableWindowState(false)} 与
 * {@code setAutoRequestFocus(false)} 只影响 AWT 自己的焦点逻辑，
 * **Windows 层面仍然会把点击算作激活**。要真正不抢焦点必须设
 * {@code WS_EX_NOACTIVATE}；要不出现在 Alt+Tab 与任务栏必须设
 * {@code WS_EX_TOOLWINDOW}。
 *
 * <p><b>调用时机是这套 API 最容易做错的地方：必须在窗口第一次显示之前。</b>
 * 显示之后再设，会先闪一下焦点再还回去——用户看到的是「输入框闪了一下」，
 * 而且如果那一瞬间有按键，可能已经被前台程序吃掉。
 *
 * <p>实现上用 {@code JNA Native.getWindowPointer(Component)} 拿 HWND：
 * Swing 的 {@code JWindow} 直接暴露底层窗口，这正是选 Swing / AWT 而不是
 * JavaFX 的理由之一（§4.4 选型表）。
 */
public final class Win32WindowStyles {

    private static final Logger log = LoggerFactory.getLogger(Win32WindowStyles.class);

    private Win32WindowStyles() {}

    /**
     * 扩展窗口样式（{@code GWL_EXSTYLE}）的读写。
     *
     * <p>走 {@link Win32.WinStyle}（用默认类型映射加载的 user32）：jna-platform 自带的
     * {@code User32} 把 {@code LPARAM} 映射成指针，而 {@code SetWindowLongPtr} 要的是
     * 数值型 {@code LONG_PTR}，直接用会编译失败。
     */
    private static long getExtendedStyle(long hwnd) {
        return Win32.WinStyle.INSTANCE
                .GetWindowLongPtrW(Win32.hwndOf(hwnd), Win32.GWL_EXSTYLE)
                .longValue();
    }

    private static void setExtendedStyle(long hwnd, long value) {
        Win32.WinStyle.INSTANCE.SetWindowLongPtrW(Win32.hwndOf(hwnd), Win32.GWL_EXSTYLE,
                new com.sun.jna.platform.win32.BaseTSD.LONG_PTR(value));
    }

    /**
     * 取组件所在的顶层 HWND。
     *
     * <p>用 JNA 的 {@code Native.getWindowPointer(Window)}——它只接受 {@code Window}，
     * 因此组件会先取到它的顶层窗口。这样**不需要**碰 {@code Component.getPeer()}：
     * 那个包没有对模块开放，直接引用会编译失败（实测报「程序包 java.awt.peer 不可见」）。
     *
     * @return 句柄数值；非 Windows、无 native peer 或失败时返回 0
     */
    public static long hwndOf(Component c) {
        if (c == null || !DpiScale.isWindows()) {
            return 0;
        }
        java.awt.Window w = topLevel(c);
        if (w == null) {
            return 0;
        }
        try {
            com.sun.jna.Pointer p = Native.getWindowPointer(w);
            return p == null ? 0 : com.sun.jna.Pointer.nativeValue(p);
        } catch (RuntimeException | Error e) {
            log.debug("取窗口句柄失败（忽略）：{}", e.toString());
            return 0;
        }
    }

    /** 组件的顶层窗口；组件本身是 Window 就是它自己。 */
    private static java.awt.Window topLevel(Component c) {
        if (c instanceof java.awt.Window w) {
            return w;
        }
        return SwingUtilities.getWindowAncestor(c);
    }

    /**
     * 让窗口不抢焦点、不进 Alt+Tab。
     *
     * @param w 目标窗口；**必须在第一次 setVisible(true) 之前调用**
     * @return 是否设置成功
     */
    public static boolean applyNoActivateToolWindow(Window w) {
        return applyExtendedStyles(w, Win32.WS_EX_NOACTIVATE | Win32.WS_EX_TOOLWINDOW, 0);
    }

    /** 只设 {@code WS_EX_NOACTIVATE}（保留任务栏/Alt+Tab 项，例如设置窗口的反例用法）。 */
    public static boolean applyNoActivate(Window w) {
        return applyExtendedStyles(w, Win32.WS_EX_NOACTIVATE, 0);
    }

    /**
     * 增删扩展样式位。
     *
     * @param add    要置上的位
     * @param remove 要清掉的位
     */
    public static boolean applyExtendedStyles(Component c, int add, int remove) {
        long hwnd = hwndOf(c);
        if (hwnd == 0) {
            // 窗口还没有 native peer（未显示）时先 addNotify 把它建出来，
            // 因为扩展样式**必须在第一次显示之前**设置（见类注释）。
            awaitPeer(c);
            hwnd = hwndOf(c);
            if (hwnd == 0) {
                log.warn("拿不到窗口句柄，无法设置扩展样式（add=0x{}, remove=0x{}）",
                        Integer.toHexString(add), Integer.toHexString(remove));
                return false;
            }
        }
        try {
            long current = getExtendedStyle(hwnd);
            long next = (current | add) & ~remove;
            if (next == current) {
                return true;
            }
            setExtendedStyle(hwnd, next);
            long after = getExtendedStyle(hwnd);
            boolean ok = (after & add) == add && (after & remove) == 0;
            log.info("窗口扩展样式 0x{} -> 0x{}（请求 +0x{} -0x{}）{}",
                    Long.toHexString(current), Long.toHexString(after),
                    Integer.toHexString(add), Integer.toHexString(remove), ok ? "" : " ⚠ 未完全生效");
            if (!ok) {
                log.debug("SetWindowLongPtr 后 GetLastError={}", Native.getLastError());
            }
            return ok;
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.warn("设置窗口扩展样式失败：{}", e.toString());
            return false;
        }
    }

    /** 有 native peer 才算真正有窗口。 */
    private static void awaitPeer(Component c) {
        try {
            if (c instanceof Window w) {
                w.addNotify();
            } else {
                c.addNotify();
            }
        } catch (RuntimeException | Error e) {
            log.debug("addNotify 失败（忽略）：{}", e.toString());
        }
    }

    /** 读当前扩展样式，供自检断言。 */
    public static int extendedStyles(Component c) {
        long hwnd = hwndOf(c);
        if (hwnd == 0) {
            return 0;
        }
        try {
            return (int) getExtendedStyle(hwnd);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return 0;
        }
    }

    /** 自检用：是否已具备不抢焦点的样式。 */
    public static boolean isNoActivate(Component c) {
        return (extendedStyles(c) & Win32.WS_EX_NOACTIVATE) != 0;
    }

    /** 自检用：是否已具备工具窗口样式（不进 Alt+Tab）。 */
    public static boolean isToolWindow(Component c) {
        return (extendedStyles(c) & Win32.WS_EX_TOOLWINDOW) != 0;
    }
}
