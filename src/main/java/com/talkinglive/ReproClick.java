package com.talkinglive;

import com.talkinglive.system.CaretTracker;
import com.talkinglive.system.DpiScale;
import com.talkinglive.system.ForegroundWatcher;
import com.talkinglive.system.Win32WindowStyles;
import com.talkinglive.ui.FloatingBall;
import com.talkinglive.ui.PreviewBar;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * 决定性测试：**应用运行时，别的窗口还能不能收到真实鼠标点击**。
 *
 * <p>前面两个复现测试排除了「悬浮球抢焦点」与「托盘气泡阻塞」。这一个是直接判据：
 * 造一个「别的窗口」，把悬浮球按 {@code App} 的方式摆出来，然后用 {@link Robot}
 * 发真实鼠标点击，看那个窗口收不收到。
 *
 * <p>为什么用 Robot 而不是直接调 API：用户的抱怨是「鼠标点不动」，
 * 只有真实输入事件才能复现。Robot 走的是与真实鼠标同一条注入路径。
 *
 * <p>为了区分「点没送到」与「点送到了但窗口没激活」，这里同时统计
 * 那个窗口收到的 mousePressed 次数，以及点击前后前台窗口的变化。
 *
 * <p>用法：{@code java -cp "target\talkinglive.jar;target\lib\*" com.talkinglive.ReproClick}
 * <br>注意：测试期间会移动真实鼠标并点击，请不要操作鼠标。
 */
public final class ReproClick {

    private ReproClick() {}

    public static void main(String[] args) throws Exception {
        DpiScale.initProcessAwareness();
        System.out.println("=== 决定性测试：应用运行时别的窗口还能不能收到点击 ===");
        System.out.println("（会移动鼠标并点击一个测试窗口，请不要操作鼠标）");
        System.out.println();

        AtomicInteger clicksOnTarget = new AtomicInteger();
        AtomicInteger focusEvents = new AtomicInteger();

        // ---- 造一个「别的窗口」充当目标程序 ----
        final JFrame[] targetRef = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame("TalkingLive 复现测试目标窗口");
            JLabel label = new JLabel("点我试试（这个窗口应该能正常收到点击）",
                    javax.swing.SwingConstants.CENTER);
            f.add(label);
            f.setSize(420, 200);
            Rectangle vb = DpiScale.virtualBounds();
            f.setLocation(vb.x + 120, vb.y + 160);
            f.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    clicksOnTarget.incrementAndGet();
                    System.out.println("  ✓ 目标窗口收到 mousePressed @ " + e.getPoint()
                            + "（这是「能点动」的证据）");
                }
            });
            f.addWindowFocusListener(new java.awt.event.WindowFocusListener() {
                @Override
                public void windowGainedFocus(java.awt.event.WindowEvent e) {
                    focusEvents.incrementAndGet();
                }

                @Override
                public void windowLostFocus(java.awt.event.WindowEvent e) {
                }
            });
            f.setVisible(true);
            targetRef[0] = f;
        });
        Thread.sleep(900);

        // ---- 按 App 的方式摆出悬浮球与浮窗 ----
        final FloatingBall[] ballRef = new FloatingBall[1];
        final PreviewBar[] barRef = new PreviewBar[1];
        SwingUtilities.invokeAndWait(() -> {
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
        Thread.sleep(700);

        JFrame target = targetRef[0];
        FloatingBall ball = ballRef[0];
        PreviewBar bar = barRef[0];

        Robot robot = new Robot();
        Point home = java.awt.MouseInfo.getPointerInfo().getLocation();

        // ---- 场景 1：只摆悬浮球，点目标窗口 ----
        int clicks1 = clickTarget(robot, target, clicksOnTarget);
        long fgAfter1 = CaretTracker.foregroundWindow();
        System.out.printf("场景 1（仅悬浮球常驻）：目标窗口收到 %d 次点击；前台=0x%x '%s'%n",
                clicks1, fgAfter1, trim(ForegroundWatcher.title(fgAfter1)));
        System.out.println();

        // ---- 场景 2：浮窗预览条显示出来（模拟听写中），再点目标窗口 ----
        SwingUtilities.invokeAndWait(() ->
                bar.render("正在听写的一段文字", "还在变", "听写中", new Point(700, 500)));
        Thread.sleep(500);
        System.out.println("浮窗已显示，尺寸 = " + bar.getWidth() + "x" + bar.getHeight()
                + "，位置 = " + bar.getLocation());
        int clicks2 = clickTarget(robot, target, clicksOnTarget);
        long fgAfter2 = CaretTracker.foregroundWindow();
        System.out.printf("场景 2（浮窗预览条显示中）：目标窗口收到 %d 次点击；前台=0x%x '%s'%n",
                clicks2, fgAfter2, trim(ForegroundWatcher.title(fgAfter2)));
        System.out.println();

        // ---- 场景 3：悬浮球贴边收起（轮询在跑），再点目标窗口 ----
        Rectangle vb = DpiScale.virtualBounds();
        SwingUtilities.invokeAndWait(() -> {
            ball.setLocationForTest(vb.x + vb.width - 10, ball.getY());
            ball.dockForTest();
        });
        Thread.sleep(900);
        System.out.println("悬浮球已贴边收起：" + ball.isDockedHidden()
                + "，位置 = " + ball.getLocation());
        int clicks3 = clickTarget(robot, target, clicksOnTarget);
        long fgAfter3 = CaretTracker.foregroundWindow();
        System.out.printf("场景 3（贴边收起 + 150ms 轮询中）：目标窗口收到 %d 次点击；前台=0x%x '%s'%n",
                clicks3, fgAfter3, trim(ForegroundWatcher.title(fgAfter3)));
        System.out.println();

        // ---- 场景 4：悬浮球右键菜单打开着 ----
        SwingUtilities.invokeAndWait(() -> ball.showMenuAt(20, 20));
        Thread.sleep(600);
        boolean menuOpen = SwingUtilities.isEventDispatchThread() ? false
                : javax.swing.MenuSelectionManager.defaultManager().getSelectedPath().length > 0;
        System.out.println("悬浮球右键菜单已打开 = " + menuOpen);
        int clicks4 = clickTarget(robot, target, clicksOnTarget);
        long fgAfter4 = CaretTracker.foregroundWindow();
        System.out.printf("场景 4（菜单打开中）：目标窗口收到 %d 次点击；前台=0x%x '%s'%n",
                clicks4, fgAfter4, trim(ForegroundWatcher.title(fgAfter4)));
        robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);
        robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
        Thread.sleep(300);
        System.out.println();

        // ---- 结论 ----
        int total = clicks1 + clicks2 + clicks3 + clicks4;
        System.out.println("=== 结论 ===");
        System.out.println("  四个场景共点 4 次，目标窗口收到 " + total + " 次");
        System.out.println("  目标窗口获得焦点次数 : " + focusEvents.get());
        if (total == 4) {
            System.out.println("  ✓ 全部送达——悬浮球/浮窗/贴边轮询/菜单 都没有阻断真实鼠标点击。");
            System.out.println("    因此用户的「点不动」不是由这些常驻窗口造成的，需要另找原因。");
        } else {
            System.out.println("  ✗ 有 " + (4 - total) + " 次点击没有送达！");
            System.out.println("    请对照上面的场景编号，找出是哪一种状态导致了阻断。");
        }

        robot.mouseMove(home.x, home.y);
        SwingUtilities.invokeAndWait(() -> {
            bar.dispose();
            ball.dispose();
            target.dispose();
        });
        System.exit(0);
    }

    /** 点目标窗口中心，返回这次点击是否被收到（对照计数器差值）。 */
    private static int clickTarget(Robot robot, JFrame target, AtomicInteger counter) {
        int before = counter.get();
        Point p = new Point();
        try {
            SwingUtilities.invokeAndWait(() -> {
                Point loc = target.getLocationOnScreen();
                p.setLocation(loc.x + target.getWidth() / 2, loc.y + target.getHeight() / 2);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (java.lang.reflect.InvocationTargetException e) {
            System.out.println("  取窗口位置失败：" + e);
            return 0;
        }
        robot.mouseMove(p.x, p.y);
        robot.delay(300);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(120);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(500);
        return counter.get() - before;
    }

    private static String trim(String s) {
        return s == null ? "" : (s.length() <= 30 ? s : s.substring(0, 30) + "…");
    }
}
