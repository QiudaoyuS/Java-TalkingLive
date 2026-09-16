package com.talkinglive;

import com.talkinglive.system.CaretTracker;
import com.talkinglive.system.DpiScale;
import com.talkinglive.system.ForegroundWatcher;
import java.awt.EventQueue;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 复现测试之二：托盘气泡 + 通知是否会让界面「卡住」或长时间占住前台。
 *
 * <p>为什么怀疑它：用户报告的症状是「屏幕上看不出异常，但什么都点不动，重启后恢复」。
 * 这种组合最典型的机制是**某个进程占着前台不放**——前台被占住时，任务栏、
 * 别的程序的标题栏按钮都无法正常获得点击（Windows 会把激活请求拦掉）。
 * 而托盘气泡（{@code TrayIcon.displayMessage}）在 Windows 上是同步调用
 * Shell_NotifyIcon，通知中心异常时可能长时间阻塞甚至挂住。
 *
 * <p>这个工具做两件可量化的探测：
 * <ol>
 *   <li>在 EDT 上发气泡，测**每次调用耗时**。若某次耗时以秒计，说明这条路径会卡住 UI 线程。</li>
 *   <li>发气泡前后检查前台窗口有没有变成本进程（气泡可能把焦点拉到我们这边）。</li>
 * </ol>
 *
 * <p>用法：{@code java -cp "target\talkinglive.jar;target\lib\*" com.talkinglive.ReproTray}
 */
public final class ReproTray {

    private ReproTray() {}

    public static void main(String[] args) throws Exception {
        DpiScale.initProcessAwareness();
        System.out.println("=== 复现测试：托盘气泡是否阻塞 / 抢前台 ===");
        System.out.println();

        if (!SystemTray.isSupported()) {
            System.out.println("系统托盘不可用，无法测试。");
            System.exit(2);
            return;
        }

        long selfPid = ProcessHandle.current().pid();
        AtomicLong maxBlockMs = new AtomicLong();

        final TrayIcon[] iconRef = new TrayIcon[1];
        long createMs = timeOf(() -> {
            PopupMenu menu = new PopupMenu();
            MenuItem mi = new MenuItem("测试项");
            menu.add(mi);
            TrayIcon icon = new TrayIcon(trayImage(), "TalkingLive 复现测试", menu);
            icon.setImageAutoSize(true);
            iconRef[0] = icon;
            try {
                SystemTray.getSystemTray().add(icon);
            } catch (Exception e) {
                System.out.println("  添加托盘图标失败：" + e);
            }
        });
        System.out.printf("创建并添加托盘图标耗时 : %d ms%n", createMs);
        Thread.sleep(500);

        System.out.println();
        System.out.println("--- 连续发 5 次气泡，测每次耗时 ---");
        for (int i = 1; i <= 5; i++) {
            long before = CaretTracker.foregroundWindow();
            final int n = i;
            long ms = timeOf(() -> {
                try {
                    iconRef[0].displayMessage("TalkingLive 测试 " + n,
                            "这是一条测试通知，用于测量 displayMessage 的阻塞时间。",
                            TrayIcon.MessageType.INFO);
                } catch (RuntimeException e) {
                    System.out.println("    第 " + n + " 次抛错：" + e);
                }
            });
            Thread.sleep(400);
            long after = CaretTracker.foregroundWindow();
            long pid = pidOf(after);
            maxBlockMs.accumulateAndGet(ms, Math::max);
            System.out.printf("  第 %d 次：耗时 %4d ms   前台 0x%x -> 0x%x %s%n",
                    i, ms, before, after,
                    pid == selfPid ? "★ 前台变成了本进程！" : "（前台未变成本进程）");
        }

        System.out.println();
        System.out.println("--- 检查气泡之后前台是否仍可被别的程序接管 ---");
        long fg = CaretTracker.foregroundWindow();
        System.out.printf("  当前前台 = 0x%x  pid=%d  '%s'%n",
                fg, pidOf(fg), ForegroundWatcher.title(fg));
        System.out.println("  前台归属本进程？ " + (pidOf(fg) == selfPid));

        System.out.println();
        System.out.println("=== 结论 ===");
        System.out.println("  气泡最大阻塞时间 : " + maxBlockMs.get() + " ms");
        if (maxBlockMs.get() > 1000) {
            System.out.println("  ★ 单次 displayMessage 阻塞超过 1 秒——这是 UI 卡死的直接原因，必须移出 EDT。");
        } else {
            System.out.println("  气泡没有明显阻塞（< 1s），不是「点不动」的原因。");
        }
        System.out.println();
        System.out.println("提示：TrayIcon.displayMessage 是**同步**调用 Shell_NotifyIcon。");
        System.out.println("      在 EDT 上调它，通知系统一旦变慢，整个界面就会失去响应——");
        System.out.println("      而失去响应的前台进程，会让用户感觉「整个屏幕都点不动」。");

        SystemTray.getSystemTray().remove(iconRef[0]);
        System.exit(0);
    }

    private static long timeOf(Runnable r) {
        long t0 = System.nanoTime();
        try {
            if (EventQueue.isDispatchThread()) {
                r.run();
            } else {
                EventQueue.invokeAndWait(r);
            }
        } catch (Exception e) {
            // 忽略
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static long pidOf(long hwnd) {
        if (hwnd == 0) {
            return 0;
        }
        var ref = new com.sun.jna.ptr.IntByReference();
        com.talkinglive.system.Win32.User32.INSTANCE
                .GetWindowThreadProcessId(com.talkinglive.system.Win32.hwndOf(hwnd), ref);
        return ref.getValue();
    }

    private static Image trayImage() {
        int size = 32;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setColor(new java.awt.Color(48, 54, 70));
        g.fillOval(2, 2, size - 4, size - 4);
        g.dispose();
        return img;
    }
}
