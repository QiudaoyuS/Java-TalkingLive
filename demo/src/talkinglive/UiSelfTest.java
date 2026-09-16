package talkinglive;

import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * 悬浮球交互的自动化自检：右键菜单 + 贴边隐藏。
 *
 * <p>这两个行为光看代码看不出对错，只能真点一下、真拖一下。所以用
 * {@link Robot} 驱动真实鼠标事件来验证。
 *
 * <p><b>两条必须遵守的前提</b>（都是踩过坑才明白的）：
 *
 * <ol>
 *   <li><b>绝不能在 EDT 上跑。</b>{@link Robot#delay} 阻塞的是当前线程；
 *       如果那就是 EDT，Robot 产生的鼠标事件和 Swing 的动画 Timer 都排不进队，
 *       断言会全部失真 —— 表现为「明明点了却没反应」。</li>
 *   <li><b>坐标一律用 AWT 逻辑坐标，不要自己乘 DPI 缩放。</b>
 *       {@code Robot.mouseMove}、{@code MouseInfo.getPointerInfo()} 与
 *       {@code Window.getBounds()} 处在同一个坐标空间（都带缩放）。
 *       手动乘一次缩放系数会把坐标推到屏幕外并被夹到边缘，点到完全无关的地方。</li>
 * </ol>
 */
final class UiSelfTest {

    private static final String REPORT = "ui-selftest-report.txt";

    private UiSelfTest() {
    }

    /** @return true 表示全部通过 */
    static boolean run(FloatingBall ball, PreviewBar bar) {
        StringBuilder r = new StringBuilder();
        Robot robot;
        try {
            robot = new Robot();
        } catch (Exception ex) {
            write("无法创建 Robot: " + ex + "\nRESULT: FAIL\n");
            System.out.println("  RESULT: FAIL (Robot 不可用)");
            return false;
        }

        Point home = MouseInfo0();
        Rectangle screen = get(() -> ball.getGraphicsConfiguration().getBounds(),
                new Rectangle());
        Point ballPos = get(() -> {
            try {
                return ball.getLocationOnScreen();
            } catch (Exception e) {
                return new Point();
            }
        }, new Point());
        final int bw = get(ball::getWidth, 68);
        final int bh = get(ball::getHeight, 68);

        r.append("屏幕逻辑尺寸 = ").append(screen.width).append("x").append(screen.height)
                .append("（Robot / MouseInfo / getBounds 共用此坐标空间，勿再乘缩放）\n");
        r.append("悬浮球逻辑坐标 = ").append(ballPos.x).append(",").append(ballPos.y)
                .append("  尺寸 ").append(bw).append("x").append(bh).append("\n");
        r.append("悬浮球可获焦点 = ").append(get(ball::isFocusableWindow, true))
                .append("（期望 false）\n\n");

        // A) 直接显示菜单：验证「不抢焦点的窗口能否承载 JPopupMenu」
        boolean direct = false;
        try {
            run(() -> ball.showMenuAt(bw / 2, bh / 2));
            robot.delay(700);
            direct = menuOpen();
            r.append("A. 直接显示菜单         = ")
                    .append(direct ? "弹出成功" : "没有弹出").append("\n");
            dismiss(robot);
        } catch (Exception ex) {
            r.append("A. 直接显示菜单         = 异常 ").append(ex).append("\n");
        }

        // B) Robot 真实右键：验证鼠标事件能否投递到不抢焦点的窗口
        boolean clicked = false;
        try {
            int cx = ballPos.x + bw / 2;
            int cy = ballPos.y + bh / 2;
            robot.mouseMove(cx, cy);
            robot.delay(350);
            robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
            robot.delay(90);
            robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
            robot.delay(700);
            clicked = menuOpen();
            r.append("B. Robot 真实右键悬浮球 = ")
                    .append(clicked ? "弹出成功" : "没有弹出").append("\n");
            dismiss(robot);
        } catch (Exception ex) {
            r.append("B. Robot 真实右键悬浮球 = 异常 ").append(ex).append("\n");
        }

        // C) 贴边隐藏：挪到边缘应收起；鼠标碰露出的一条应滑出
        boolean docked = false;
        boolean revealed = false;
        boolean movedOut = false;
        try {
            final int homeY = ballPos.y;
            final Rectangle sc = screen;

            run(() -> ball.setLocation(sc.x + sc.width - 10, homeY));
            run(ball::dockForTest);
            robot.delay(700);                     // 等滑出动画（约 160ms）跑完
            docked = get(ball::isDockedHidden, false);
            final int hiddenX = get(ball::getX, Integer.MIN_VALUE);
            r.append("C. 贴边收起            = ")
                    .append(docked ? "已收起" : "没收起")
                    .append("  窗口 x=").append(hiddenX).append("\n");

            // 露出的一条贴在屏幕右缘，把鼠标挪上去（逻辑坐标直接用）
            final int sliverX = sc.x + sc.width - 5;
            final int sliverY = homeY + bh / 2;
            robot.mouseMove(sliverX, sliverY);
            robot.delay(800);
            revealed = !get(ball::isDockedHidden, true);
            final int nowX = get(ball::getX, Integer.MIN_VALUE);
            movedOut = nowX != hiddenX;   // 右侧贴边滑出时 x 会变小，别写成 >
            r.append("   + 鼠标移到露出部分  = ")
                    .append(revealed ? "已滑出" : "没滑出")
                    .append("  窗口 x=").append(nowX)
                    .append("  (mouseEntered 共 ").append(get(ball::enterCountForTest, -1))
                    .append(" 次)\n");
        } catch (Exception ex) {
            r.append("C. 贴边收起            = 异常 ").append(ex).append("\n");
        }

        boolean ok = direct && clicked && docked && revealed && movedOut;

        r.append("\n结论：\n");
        if (!direct) {
            r.append("  JPopupMenu 无法从不抢焦点的窗口弹出。\n")
                    .append("  需要改用自绘弹层，或让悬浮球在弹菜单时临时可获焦点。\n");
        } else if (!clicked) {
            r.append("  菜单本身可用，但鼠标事件没能投递到悬浮球。\n");
        } else if (!docked) {
            r.append("  右键菜单正常，但拖到边缘没有收起。\n");
        } else if (!revealed || !movedOut) {
            r.append("  右键菜单与收起正常，但鼠标移到露出部分没有滑出。\n");
        } else {
            r.append("  悬浮球右键菜单与贴边隐藏均工作正常。\n");
        }
        r.append("\nRESULT: ").append(ok ? "PASS" : "FAIL").append("\n");

        robot.mouseMove(home.x, home.y);
        write(r.toString());

        System.out.println("UI self-test (floating ball)");
        System.out.println("  A. direct popup  : " + (direct ? "PASS" : "FAIL"));
        System.out.println("  B. robot r-click : " + (clicked ? "PASS" : "FAIL"));
        System.out.println("  C. edge dock     : " + (docked && revealed && movedOut ? "PASS" : "FAIL"));
        System.out.println("  report = " + REPORT);
        return ok;
    }

    // ---------- 工具 ----------

    /** 在 EDT 上执行 —— 所有触碰 Swing 状态的操作都必须走这里。 */
    private static void run(Runnable action) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
            } else {
                SwingUtilities.invokeAndWait(action);
            }
        } catch (Exception ignored) {
            // 自检里不因单点失败中断
        }
    }

    private static <T> T get(Supplier<T> supplier, T fallback) {
        final Object[] box = {fallback};
        run(() -> box[0] = supplier.get());
        @SuppressWarnings("unchecked")
        T value = (T) box[0];
        return value;
    }

    private static boolean menuOpen() {
        return get(() -> {
            MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
            return path != null && path.length > 0;
        }, false);
    }

    private static void dismiss(Robot robot) {
        robot.keyPress(KeyEvent.VK_ESCAPE);
        robot.keyRelease(KeyEvent.VK_ESCAPE);
        robot.delay(250);
    }

    private static Point MouseInfo0() {
        try {
            return java.awt.MouseInfo.getPointerInfo().getLocation();
        } catch (Exception e) {
            return new Point(0, 0);
        }
    }

    /** 报告写 UTF-8 文件，控制台只输出 ASCII —— Windows 控制台默认是 GBK，中文会乱码。 */
    private static void write(String content) {
        PrintWriter w = null;
        try {
            w = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(REPORT), StandardCharsets.UTF_8));
            w.print(content);
        } catch (Exception e) {
            System.out.println("WARN: 无法写入 " + REPORT + ": " + e.getMessage());
        } finally {
            if (w != null) {
                w.close();
            }
        }
    }
}
