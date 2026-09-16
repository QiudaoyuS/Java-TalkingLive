package com.talkinglive.system;

import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import java.awt.MouseInfo;
import java.awt.Point;
import javax.swing.JWindow;

/**
 * 坐标空间核对：{@code DESIGN.md} §4.4 点名的「本项目最容易反复踩的一个坑」到底怎么算。
 *
 * <p>设计文档写的是「Win32 用**物理像素**，Java 用**逻辑像素**，中间必须显式换算」。
 * 这条结论**依赖于进程的 DPI awareness**，所以不能靠背结论，必须实测：
 *
 * <ul>
 *   <li>进程是 <b>per-monitor aware</b>（本产品启动时就会设置）时，
 *       Win32 坐标系是**物理像素**，AWT 是**逻辑像素**，两者确实需要换算。</li>
 *   <li>进程 <b>不感知 DPI</b> 时，Windows 会把 Win32 坐标也折算成逻辑像素交给进程——
 *       此时两者看起来「相等」，但那是虚拟化后的假象。</li>
 * </ul>
 *
 * <p><b>⚠️ 两个实测踩过的坑，用这个工具的人必须知道：</b>
 * <ol>
 *   <li><b>必须在触碰任何 AWT 类之前设置 awareness。</b>JVM 的 toolkit 一旦初始化，
 *       再设 {@code SetProcessDpiAwarenessContext} 就会失败——本工具曾经因为这个
 *       顺序问题得出过**完全相反**的结论（把「虚拟化后的相等」当成了「本来就在同一空间」）。
 *       因此下面 {@link #main} 的第一件事就是设 awareness。</li>
 *   <li><b>从别的进程（例如 PowerShell）用 {@code GetWindowRect} 读一个 DPI-aware 进程的窗口，
 *       读到的往往是虚拟化后的逻辑坐标</b>，不是那个进程眼里的物理坐标。
 *       要判断本产品的位置对不对，必须**在本进程内**比对
 *       {@code Window.getLocationOnScreen()} 与 {@code GetWindowRect}。</li>
 * </ol>
 *
 * <p>用法：{@code java -cp "target\talkinglive.jar;target\lib\*" com.talkinglive.system.DpiProbe}
 */
public final class DpiProbe {

    private DpiProbe() {}

    public static void main(String[] args) {
        // ★ 第一件事：设置进程 DPI awareness（必须在任何 AWT 类被加载之前）
        boolean awareSet = DpiScale.initProcessAwareness();
        // 用**权威来源**判断进程当前的 awareness：SetProcessDpiAwarenessContext 的返回值
        // 在某些情况下会是 false（例如 awareness 已经通过其他途径设好），不能拿它当结论。
        int aware = processAwarenessCode();
        boolean isAware = aware >= 1;

        System.out.println("=== 坐标空间核对（DESIGN.md §4.4 的坑）===");
        System.out.println();
        System.out.println("--- ① 进程与显示器事实 ---");
        System.out.println("os.name                     : " + System.getProperty("os.name"));
        System.out.println("DpiScale.initProcessAwareness() 返回 : " + awareSet
                + "（注意：返回 false 不代表没生效，见下一行）");
        System.out.println("GetProcessDpiAwareness(自己) : " + aware
                + "   （0=UNAWARE 1=SYSTEM_AWARE 2=PER_MONITOR_AWARE）"
                + (isAware ? "  ⇒ Win32 坐标 = 物理像素" : "  ⇒ Win32 坐标被虚拟化为逻辑像素"));
        System.out.println("Toolkit.getScreenResolution : " + java.awt.Toolkit.getDefaultToolkit().getScreenResolution());
        System.out.println("Toolkit.getScreenSize       : " + java.awt.Toolkit.getDefaultToolkit().getScreenSize());
        System.out.println("DpiScale.systemDpi()        : " + DpiScale.systemDpi());
        System.out.println("DpiScale.virtualBounds()    : " + DpiScale.virtualBounds());
        System.out.println("说明：上两行是 **AWT 逻辑像素**；物理屏幕尺寸约为逻辑值 × DPI/96 = "
                + String.format("%.2f", DpiScale.systemDpi() / 96.0) + " 倍");

        System.out.println();
        System.out.println("--- ② 鼠标位置：Win32 GetCursorPos  vs  AWT MouseInfo ---");
        POINT p = new POINT();
        boolean ok = Win32.User32.INSTANCE.GetCursorPos(p);
        Point awt = MouseInfo.getPointerInfo() == null ? new Point(-1, -1)
                : MouseInfo.getPointerInfo().getLocation();
        System.out.printf("GetCursorPos  = %d,%d   (ok=%s)%n", p.x, p.y, ok);
        System.out.printf("MouseInfo     = %d,%d%n", awt.x, awt.y);
        boolean same = Math.abs(p.x - awt.x) <= 1 && Math.abs(p.y - awt.y) <= 1;
        System.out.println("两者相等？    : " + same);
        System.out.println(same
                ? "  ⇒ Win32 与 AWT **在同一坐标空间**：CaretTracker 里不得再乘缩放（会跑到屏幕外）"
                : "  ⇒ Win32 是物理像素、AWT 是逻辑像素：必须经 DpiScale 换算");

        System.out.println();
        System.out.println("--- ③ 窗口位置：AWT setLocation  vs  Win32 GetWindowRect ---");
        JWindow w = new JWindow();
        w.setSize(120, 90);
        w.setLocation(300, 200);
        w.setVisible(true);
        try {
            Thread.sleep(600);
            Point onScreen = w.getLocationOnScreen();
            RECT r = new RECT();
            boolean rok = Win32.User32.INSTANCE.GetWindowRect(
                    Win32.hwndOf(Win32WindowStyles.hwndOf(w)), r);
            System.out.printf("AWT getLocationOnScreen = %d,%d  size=%dx%d%n",
                    onScreen.x, onScreen.y, w.getWidth(), w.getHeight());
            System.out.printf("Win32 GetWindowRect     = %d,%d %dx%d  (ok=%s)%n",
                    r.left, r.top, r.right - r.left, r.bottom - r.top, rok);
            boolean same2 = Math.abs(r.left - onScreen.x) <= 2 && Math.abs(r.top - onScreen.y) <= 2;
            System.out.println("两者相等？    : " + same2);
            System.out.println(same2
                    ? "  ⇒ 窗口坐标也在同一空间：setLocation 可以直接用 Win32 返回值"
                    : "  ⇒ 窗口坐标需要经 DpiScale 换算后再 setLocation");

            System.out.println();
            System.out.println("--- ④ DpiScale 换算是否幂等（若已是同一空间，换算就会破坏坐标）---");
            int lx = 300;
            int phys = DpiScale.awtToWin32(lx);
            int back = DpiScale.win32ToAwt(phys);
            System.out.printf("awtToWin32(%d) = %d ；win32ToAwt(%d) = %d%n", lx, phys, phys, back);
            System.out.println("往返一致？    : " + (back == lx));
            System.out.println("本条只说明换算可逆；**是否需要换算**由 ② ③ 的结论决定。");

            System.out.println();
            System.out.println("--- ⑤ 结论（写进代码注释用）---");
            if (!isAware) {
                System.out.println("本进程 DPI awareness = " + aware + "（不感知 DPI）：");
                System.out.println("Windows 会把 Win32 坐标也折算成逻辑像素，此时两边看起来『相等』是虚拟化的结果，");
                System.out.println("不能据此认为不需要换算。");
            } else if (same && same2) {
                System.out.println("本进程是 DPI-aware，但 Win32 与 AWT 坐标恰好相等——");
                System.out.println("说明这台机器的缩放是 100%（DPI=96），此时换算是恒等变换。");
            } else {
                System.out.println("本进程 DPI awareness = " + aware + "（DPI-aware）：");
                System.out.println("Win32 报**物理像素**、AWT 用**逻辑像素**，比值实测约 "
                        + String.format("%.2f", (double) r.left / Math.max(1, onScreen.x))
                        + "，与 DPI/96 = " + String.format("%.2f", DpiScale.systemDpi() / 96.0) + " 一致。");
                System.out.println("⇒ CaretTracker / DpiScale 的换算**不可省**：");
                System.out.println("  漏掉会让浮窗贴错位置；多乘一次会把坐标推出屏幕（设计文档记录的那次翻车）。");
            }
            System.out.println();
            System.out.println("★ 实测教训：从**别的进程**（如 PowerShell）读本产品窗口的 GetWindowRect，");
            System.out.println("  得到的通常是虚拟化后的**逻辑**坐标，与本进程算出的物理坐标差 DPI/96 倍。");
            System.out.println("  曾据此误判「窗口位置算错了」——其实位置是对的，是测量的坐标空间不对。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            w.dispose();
        }
        System.exit(0);
    }

    /** shcore.dll：查本进程当前的 DPI awareness 取值。 */
    interface Shcore extends com.sun.jna.win32.StdCallLibrary {
        Shcore INSTANCE = com.sun.jna.Native.load("shcore", Shcore.class,
                com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);

        int GetProcessDpiAwareness(com.sun.jna.Pointer hProcess, com.sun.jna.ptr.IntByReference value);
    }

    /** @return 本进程当前的 DPI awareness 代码；查不到返回 -1 */
    private static int processAwarenessCode() {
        try {
            var out = new com.sun.jna.ptr.IntByReference();
            int hr = Shcore.INSTANCE.GetProcessDpiAwareness(null, out);
            return hr == 0 ? out.getValue() : -1;
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return -1;
        }
    }
}
