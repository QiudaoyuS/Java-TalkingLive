package com.talkinglive;

import com.talkinglive.system.CaretTracker;
import com.talkinglive.system.DpiScale;
import com.talkinglive.system.ForegroundWatcher;
import com.talkinglive.system.Win32;
import com.talkinglive.system.Win32WindowStyles;
import com.talkinglive.ui.FloatingBall;
import com.talkinglive.ui.PreviewBar;
import java.awt.Point;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 复现「运行后点不动任何东西」这个问题。
 *
 * <p>症状（用户报告）：屏幕上看不出任何异常，但任务栏、别的软件的关闭按钮、
 * 任务切换等全都点不动；重启后恢复。
 *
 * <p>这类问题的关键判据是**前台窗口能不能被别的程序抢走**。所以这个工具不只是
 * 「把界面摆出来看看」，而是主动做三件事去逼出问题：
 * <ol>
 *   <li>按 {@code App} 的方式造出悬浮球与浮窗预览条（含 Win32 扩展样式）。</li>
 *   <li>**记录每一次前台窗口变化**——如果前台被反复抢回我们这边，日志会暴露它。</li>
 *   <li>用 {@code SetForegroundWindow} 尝试把前台交给别的窗口，并断言是否成功。
 *       抢不过来，就说明存在焦点劫持。</li>
 * </ol>
 *
 * <p>另外还会打印每个窗口的样式与矩形，确认没有「看不见但很大」的窗口。
 *
 * <p>用法：{@code java -cp "target\talkinglive.jar;target\lib\*" com.talkinglive.ReproBlock}
 */
public final class ReproBlock {

    private ReproBlock() {}

    public static void main(String[] args) throws Exception {
        DpiScale.initProcessAwareness();
        System.out.println("=== 复现测试：运行后是否抢占屏幕 / 劫持焦点 ===");
        System.out.println("（观察 12 秒；期间请不要碰鼠标）");
        System.out.println();

        AtomicInteger focusGrabs = new AtomicInteger();
        StringBuilder trace = new StringBuilder();

        // 与 App.startUi() 完全一致地创建窗口
        final FloatingBall[] ballRef = new FloatingBall[1];
        final PreviewBar[] barRef = new PreviewBar[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            ballRef[0] = new FloatingBall(null, new FloatingBall.Listener() {
                @Override public void onLeftClick() {}
                @Override public void onTogglePause() {}
                @Override public void onOpenSettings() {}
                @Override public void onOpenLog() {}
                @Override public void onQuit() {}
            });
            ballRef[0].addNotify();
            Win32WindowStyles.applyNoActivateToolWindow(ballRef[0]);
            ballRef[0].applySavedGeometry(null);
            ballRef[0].setVisible(true);

            barRef[0] = new PreviewBar(null);
            barRef[0].addNotify();
            Win32WindowStyles.applyNoActivateToolWindow(barRef[0]);
        });
        FloatingBall ball = ballRef[0];
        PreviewBar bar = barRef[0];

        Thread.sleep(800);
        System.out.println("悬浮球窗口（AWT 逻辑坐标）: " + ball.getLocation() + " "
                + ball.getWidth() + "x" + ball.getHeight());
        System.out.println("  扩展样式 = 0x" + Integer.toHexString(Win32WindowStyles.extendedStyles(ball))
                + "  NoActivate=" + Win32WindowStyles.isNoActivate(ball)
                + "  ToolWindow=" + Win32WindowStyles.isToolWindow(ball));
        System.out.println("浮窗预览条（尚未 render，尺寸）: " + bar.getWidth() + "x" + bar.getHeight());
        System.out.println();

        // 记录前台变化的次数（与 App 一样挂一个 watcher）
        long selfPid = ProcessHandle.current().pid();
        ForegroundWatcher watcher = new ForegroundWatcher((from, to) -> {
            long pid = pidOf(to);
            boolean mine = pid == selfPid;
            if (mine) {
                focusGrabs.incrementAndGet();
            }
            synchronized (trace) {
                trace.append(String.format("  前台变化 0x%x -> 0x%x  pid=%d%s  '%s'%n",
                        from, to, pid, mine ? "  ★ 是本进程（焦点被抢回！）" : "",
                        ForegroundWatcher.title(to)));
            }
        });
        watcher.start();
        Thread.sleep(400);

        // ---- 关键判据 1：前台能不能交给别的窗口 ----
        long shell = shellWindow();
        long chrome = findWindowLike("Chrome_WidgetWin_1");
        long terminal = findWindowLike("CASCADIA_HOSTING_WINDOW_CLASS");

        System.out.println("--- 尝试把前台交给别的窗口（这是「点不动」的判据）---");
        for (long[] cand : new long[][] {{chrome}, {terminal}, {shell}}) {
            long h = cand[0];
            if (h == 0) {
                System.out.println("  （跳过：找不到该窗口）");
                continue;
            }
            long before = CaretTracker.foregroundWindow();
            boolean ok = Win32.User32.INSTANCE.SetForegroundWindow(Win32.hwndOf(h));
            Thread.sleep(400);
            long after = CaretTracker.foregroundWindow();
            System.out.printf("  SetForegroundWindow(0x%x '%s') = %s ；之后前台 = 0x%x%s%n",
                    h, trim(ForegroundWatcher.title(h)), ok, after,
                    after == h ? "  ✓ 成功" : "  ✗ 没抢到（当前是 0x" + Long.toHexString(after) + "）");
            System.out.println("     （before=0x" + Long.toHexString(before) + "）");
        }

        // ---- 关键判据 2：浮窗 render 之后前台有没有被它抢走 ----
        System.out.println();
        System.out.println("--- 浮窗 render 之后，前台有没有被抢走 ---");
        long beforeRender = CaretTracker.foregroundWindow();
        javax.swing.SwingUtilities.invokeAndWait(() ->
                bar.render("测试文字", "仍在变", "听写中", new Point(600, 400)));
        Thread.sleep(600);
        long afterRender = CaretTracker.foregroundWindow();
        System.out.println("  render 前 0x" + Long.toHexString(beforeRender)
                + " → 后 0x" + Long.toHexString(afterRender)
                + (beforeRender == afterRender ? "  ✓ 未变" : "  ✗ 变了！浮窗抢走了前台"));
        System.out.println("  浮窗自身 hwnd = 0x" + Long.toHexString(Win32WindowStyles.hwndOf(bar)));
        System.out.println("  浮窗尺寸 = " + bar.getWidth() + "x" + bar.getHeight()
                + "（内容变长时 pack() 会让它变大，注意别失控）");

        // ---- 关键判据 3：持续观察 6 秒，看前台是否被反复抢回 ----
        System.out.println();
        System.out.println("--- 持续观察 6 秒 ---");
        Thread.sleep(6000);

        System.out.println();
        System.out.println("=== 观察期间的前台变化 ===");
        synchronized (trace) {
            System.out.print(trace);
        }
        System.out.println();
        System.out.println("焦点被抢回本进程的次数 : " + focusGrabs.get()
                + (focusGrabs.get() > 0 ? "  ★ 存在焦点劫持！" : "  ✓ 没有主动抢焦点"));
        System.out.println("前台窗口当前的归属       : 0x"
                + Long.toHexString(CaretTracker.foregroundWindow()));

        watcher.close();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            bar.dispose();
            ball.dispose();
        });
        System.out.println();
        System.out.println("结论提示：");
        System.out.println("  · 若「焦点被抢回次数 > 0」→ 是本产品在抢焦点，去找 toFront/setVisible/requestFocus 的调用点。");
        System.out.println("  · 若 SetForegroundWindow 全部失败且前台停在别的窗口 → 焦点没被劫持，问题在别处。");
        System.out.println("  · 若浮窗 render 后前台变了 → 立刻修浮窗的显示方式（这是最可能的原因）。");
        System.exit(0);
    }

    private static long pidOf(long hwnd) {
        if (hwnd == 0) {
            return 0;
        }
        var ref = new com.sun.jna.ptr.IntByReference();
        Win32.User32.INSTANCE.GetWindowThreadProcessId(Win32.hwndOf(hwnd), ref);
        return ref.getValue();
    }

    private static long shellWindow() {
        try {
            return Win32.User32.INSTANCE.FindWindow("Shell_TrayWnd", null) == null ? 0
                    : com.sun.jna.Pointer.nativeValue(
                            Win32.User32.INSTANCE.FindWindow("Shell_TrayWnd", null).getPointer());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 在可见顶层窗口里按类名找一个。 */
    private static long findWindowLike(String className) {
        long[] found = {0};
        Win32.User32.INSTANCE.EnumWindows((h, l) -> {
            if (!Win32.User32.INSTANCE.IsWindowVisible(h)) {
                return true;
            }
            String cls = classNameOf(h);
            if (cls.startsWith(className)) {
                found[0] = com.sun.jna.Pointer.nativeValue(h.getPointer());
                return false;
            }
            return true;
        }, null);
        return found[0];
    }

    private static String classNameOf(com.sun.jna.platform.win32.WinDef.HWND h) {
        try {
            char[] buf = new char[256];
            int n = Win32.User32.INSTANCE.GetClassNameW(h, buf, buf.length);
            return n <= 0 ? "" : new String(buf, 0, n);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String trim(String s) {
        return s == null ? "" : (s.length() <= 24 ? s : s.substring(0, 24) + "…");
    }
}
