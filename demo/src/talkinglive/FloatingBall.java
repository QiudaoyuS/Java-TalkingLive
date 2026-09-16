package talkinglive;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * 常驻悬浮球 —— 产品始终可见的界面元素。
 *
 * <p>程序启动后桌面上只有它：没有主窗口、没有控制台。
 * 左键点击手动开始/结束听写，右键弹出菜单，拖动可移动位置。
 *
 * <p>与浮窗预览条一样，它必须**不抢焦点** —— 否则点击悬浮球会让前台
 * 程序失去焦点，正在输入的文字就打断了。
 *
 * <p><b>贴边隐藏</b>：拖到屏幕左右边缘附近松手，球会吸附到该边缘并收起，
 * 只露出一小条；鼠标移到露出部分就滑出来，移开再收回去。
 */
class FloatingBall extends JWindow {

    /** 悬浮球对外发出的动作。 */
    interface Listener {
        void onLeftClick();

        void onTogglePause();

        void onOpenSettings();

        void onOpenLog();

        void onQuit();
    }

    /** 吸附在哪一侧。 */
    private enum DockSide { NONE, LEFT, RIGHT }

    private static final int WINDOW_SIZE = 68;   // 含脉冲光圈留白
    private static final int BALL_SIZE = 52;
    private static final int BALL_INSET = (WINDOW_SIZE - BALL_SIZE) / 2;   // 球在窗口内的左内边距

    /** 收起后露出多少像素的<b>窗口</b>。球本身只占中间 52px，所以要加内边距。 */
    private static final int PEEK = 20;

    /** 松手时距边缘这个距离以内就吸附。 */
    private static final int DOCK_THRESHOLD = 40;

    /** 移开鼠标后延迟多久才收起（留出反悔时间，避免抖动）。 */
    private final Listener listener;

    private StateMachine.State state = StateMachine.State.IDLE;
    private boolean paused = false;
    private boolean hover = false;

    private Point pressPoint;
    private boolean dragged = false;

    private DockSide docked = DockSide.NONE;
    private boolean revealed = true;
    private Timer slideTimer;

    private float pulsePhase = 0f;
    private final Timer pulseTimer;

    /** 贴边状态下轮询鼠标位置 —— 不依赖 ENTERED 事件是否送达。 */
    private Timer dockWatcher;
    private int outsideTicks = 0;

    /** 自检用：收到过几次 mouseEntered。 */
    private int enterCount = 0;

    FloatingBall(Window owner, Listener listener) {
        super(owner);
        this.listener = listener;

        setFocusableWindowState(false);   // 不参与键盘焦点
        setAutoRequestFocus(false);       // 显示时不请求焦点
        setAlwaysOnTop(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setSize(WINDOW_SIZE, WINDOW_SIZE);

        if (getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(
                java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
            setBackground(new Color(0, 0, 0, 0));
        }

        BallPanel panel = new BallPanel();
        setContentPane(panel);

        pulseTimer = new Timer(45, e -> {
            pulsePhase += 0.06f;
            if (pulsePhase > 1f) {
                pulsePhase -= 1f;
            }
            repaint();
        });

        installMouseHandlers(panel);
        moveToDefaultPosition();
    }

    // ---------- 对外 ----------

    void setState(StateMachine.State s) {
        this.state = s;
        if (s == StateMachine.State.LISTENING && !paused) {
            if (!pulseTimer.isRunning()) {
                pulseTimer.start();
            }
        } else {
            pulseTimer.stop();
            pulsePhase = 0f;
        }
        repaint();
    }

    void setPaused(boolean p) {
        this.paused = p;
        setState(state);
    }

    /** 默认停在屏幕右侧中部，离边缘留出距离以免一上手就被吸附。 */
    private void moveToDefaultPosition() {
        Rectangle screen = getGraphicsConfiguration().getBounds();
        int x = screen.x + screen.width - WINDOW_SIZE - (DOCK_THRESHOLD + 20);
        int y = screen.y + screen.height / 2 - WINDOW_SIZE / 2;
        setLocation(x, y);
    }

    // ---------- 交互 ----------

    private void installMouseHandlers(BallPanel panel) {
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showMenu(e);
                    return;
                }
                revealNow();          // 收起状态下按下去，先滑出来再说
                pressPoint = e.getPoint();
                dragged = false;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (pressPoint == null) {
                    return;
                }
                boolean wasClick = !dragged && e.getPoint().distance(pressPoint) < 5;
                pressPoint = null;
                dragged = false;

                if (wasClick && SwingUtilities.isLeftMouseButton(e)) {
                    listener.onLeftClick();
                } else {
                    maybeDock();      // 拖完松手，判断要不要吸附
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                enterCount++;
                hover = true;
                repaint();
                revealNow();          // 鼠标碰到露出的一小条就滑出来
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                repaint();
            }
        });

        panel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressPoint == null) {
                    return;
                }
                dragged = true;
                stopSlide();
                Point p = e.getLocationOnScreen();
                setLocationClamped(p.x - pressPoint.x, p.y - pressPoint.y);
            }
        });
    }

    /**
     * 把悬浮球夹在屏幕范围内。
     *
     * <p>即使有托盘图标兜底，把球拖到屏幕外也会让人找不到它，所以夹住位置。
     */
    private void setLocationClamped(int x, int y) {
        Rectangle screen = getGraphicsConfiguration().getBounds();
        int maxX = screen.x + screen.width - WINDOW_SIZE;
        int maxY = screen.y + screen.height - WINDOW_SIZE;
        setLocation(Math.max(screen.x, Math.min(x, maxX)),
                Math.max(screen.y, Math.min(y, maxY)));
    }

    // ---------- 贴边隐藏 ----------

    /** 松手时判断是否该吸附到某一侧。 */
    private void maybeDock() {
        Rectangle screen = getGraphicsConfiguration().getBounds();
        int centerX = getX() + getWidth() / 2;

        DockSide target = DockSide.NONE;
        if (centerX - screen.x < DOCK_THRESHOLD) {
            target = DockSide.LEFT;
        } else if (screen.x + screen.width - centerX < DOCK_THRESHOLD) {
            target = DockSide.RIGHT;
        }

        docked = target;
        if (target == DockSide.NONE) {
            revealed = true;          // 离开边缘就恢复常驻显示
            outsideTicks = 0;
            stopDockWatcher();
            setLocationClamped(getX(), getY());
        } else {
            revealed = false;
            outsideTicks = 0;
            slideTo(hiddenX(target), getY());
            startDockWatcher();
        }
    }

    /** 收起后窗口的 x —— 只留 PEEK 像素在屏幕内。 */
    private int hiddenX(DockSide side) {
        Rectangle screen = getGraphicsConfiguration().getBounds();
        return side == DockSide.LEFT
                ? screen.x - (WINDOW_SIZE - PEEK)
                : screen.x + screen.width - PEEK;
    }

    /** 完全展开后窗口的 x。 */
    private int revealedX(DockSide side) {
        Rectangle screen = getGraphicsConfiguration().getBounds();
        return side == DockSide.LEFT
                ? screen.x
                : screen.x + screen.width - WINDOW_SIZE;
    }

    private void startDockWatcher() {
        if (dockWatcher == null) {
            dockWatcher = new Timer(150, e -> pollDockHover());
        }
        if (!dockWatcher.isRunning()) {
            dockWatcher.start();
        }
    }

    private void stopDockWatcher() {
        if (dockWatcher != null) {
            dockWatcher.stop();
        }
    }

    /**
     * 贴边状态下轮询鼠标位置，决定滑出还是收起。
     *
     * <p>为什么用轮询而不是 {@code mouseEntered}：悬浮球收起时大部分在屏幕外
     * （只剩 PEEK 像素可见），实测这种情况下鼠标事件不一定会送达。
     * 轮询只在贴边期间运行，开销可以忽略，且行为可控。
     */
    private void pollDockHover() {
        if (docked == DockSide.NONE) {
            stopDockWatcher();
            return;
        }
        if (dragged) {
            return;
        }
        java.awt.PointerInfo info = MouseInfo.getPointerInfo();
        if (info == null) {
            return;
        }
        boolean over = getBounds().contains(info.getLocation());

        if (over) {
            outsideTicks = 0;
            if (!revealed) {
                revealNow();
            }
        } else if (revealed) {
            outsideTicks++;
            if (outsideTicks >= 3) {          // 约 450ms，留出反悔时间
                outsideTicks = 0;
                revealed = false;
                slideTo(hiddenX(docked), getY());
            }
        }
    }

    /** 自检用：收到的 mouseEntered 次数。 */
    int enterCountForTest() {
        return enterCount;
    }

    private void revealNow() {
        if (docked == DockSide.NONE || revealed) {
            return;
        }
        revealed = true;
        slideTo(revealedX(docked), getY());
    }


    private void stopSlide() {
        if (slideTimer != null) {
            slideTimer.stop();
        }
    }

    /** 动画滑到目标位置（ease-out，约 160ms）。 */
    private void slideTo(final int targetX, final int targetY) {
        stopSlide();
        final int fromX = getX();
        final int fromY = getY();
        if (fromX == targetX && fromY == targetY) {
            return;
        }
        final int steps = 12;
        final int[] n = {0};
        slideTimer = new Timer(13, e -> {
            n[0]++;
            double p = Math.min(1.0, n[0] / (double) steps);
            double eased = 1 - Math.pow(1 - p, 3);
            if (n[0] >= steps) {
                ((Timer) e.getSource()).stop();
                setLocation(targetX, targetY);
            } else {
                setLocation((int) Math.round(fromX + (targetX - fromX) * eased),
                        (int) Math.round(fromY + (targetY - fromY) * eased));
            }
        });
        slideTimer.start();
    }

    // ---------- 菜单 ----------

    private void showMenu(MouseEvent e) {
        showMenuAt(e.getX(), e.getY());
    }

    /**
     * 在指定位置弹出菜单。
     *
     * <p>抽出来是为了能被自动化测试直接调用 —— 「菜单能否从
     * <b>不抢焦点</b>的窗口上弹出来」是这个设计里真实存在的风险点。
     */
    void showMenuAt(int x, int y) {
        JPopupMenu menu = new JPopupMenu();
        menu.setFont(Theme.font(12));

        JMenuItem manual = item(paused ? "手动开始听写（已暂停）" : "手动开始 / 结束听写");
        manual.setEnabled(!paused);
        manual.addActionListener(a -> listener.onLeftClick());
        menu.add(manual);

        menu.addSeparator();

        JMenuItem pause = item(paused ? "恢复监听" : "暂停监听");
        pause.addActionListener(a -> listener.onTogglePause());
        menu.add(pause);

        menu.addSeparator();

        JMenuItem settings = item("设置...");
        settings.addActionListener(a -> listener.onOpenSettings());
        menu.add(settings);

        JMenuItem logs = item("查看日志");
        logs.addActionListener(a -> listener.onOpenLog());
        menu.add(logs);

        menu.addSeparator();

        JMenuItem quit = item("退出");
        quit.addActionListener(a -> listener.onQuit());
        menu.add(quit);

        menu.show(this, x, y);
    }

    private static JMenuItem item(String text) {
        JMenuItem i = new JMenuItem(text);
        i.setFont(Theme.font(12));
        return i;
    }

    // ---------- 测试支撑 ----------

    /** 当前是否处于贴边收起状态（供自检断言）。 */
    boolean isDockedHidden() {
        return docked != DockSide.NONE && !revealed;
    }

    /** 供自检直接触发吸附判定。 */
    void dockForTest() {
        maybeDock();
    }

    // ---------- 绘制 ----------

    /** 主色随状态变化，颜色本身就是状态指示。 */
    private Color baseColor() {
        if (paused) {
            return new Color(74, 80, 96);
        }
        switch (state) {
            case LISTENING:  return Theme.ERR;
            case COMMITTING: return Theme.WARN;
            default:         return new Color(48, 54, 70);
        }
    }

    private Color ringColor() {
        if (paused) {
            return new Color(120, 127, 145);
        }
        switch (state) {
            case LISTENING:  return new Color(255, 150, 150);
            case COMMITTING: return new Color(255, 214, 150);
            default:         return Theme.DIM;
        }
    }

    private class BallPanel extends javax.swing.JPanel {

        BallPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            int r = BALL_SIZE / 2;

            // 听写中的脉冲光圈
            if (state == StateMachine.State.LISTENING && !paused) {
                int pr = (int) (r + pulsePhase * (r * 0.5));
                int alpha = (int) (150 * (1f - pulsePhase));
                g2.setColor(new Color(226, 94, 94, Math.max(alpha, 0)));
                g2.setStroke(new BasicStroke(2.4f));
                g2.draw(new Ellipse2D.Float(cx - pr, cy - pr, pr * 2f, pr * 2f));
            }

            // 投影
            g2.setColor(new Color(0, 0, 0, 70));
            g2.fill(new Ellipse2D.Float(cx - r, cy - r + 3, r * 2f, r * 2f));

            // 球体
            g2.setColor(baseColor());
            g2.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f));

            // 边缘
            g2.setColor(hover ? ringColor().brighter() : ringColor());
            g2.setStroke(new BasicStroke(hover ? 2.2f : 1.4f));
            g2.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2f - 1, r * 2f - 1));

            // 收起时只露出一条，把图标往露出的一侧挪，否则看到的是空白
            if (docked != DockSide.NONE && !revealed) {
                int shift = docked == DockSide.LEFT ? BALL_INSET : -BALL_INSET;
                Graphics2D g3 = (Graphics2D) g2.create();
                g3.translate(shift, 0);
                if (paused) {
                    drawPaused(g3, cx, cy);
                } else {
                    drawMic(g3, cx, cy);
                }
                g3.dispose();
            } else if (paused) {
                drawPaused(g2, cx, cy);
            } else {
                drawMic(g2, cx, cy);
            }

            g2.dispose();
        }
    }

    private void drawMic(Graphics2D g2, int cx, int cy) {
        int w = 13;
        int h = 20;
        g2.setColor(new Color(232, 236, 246, 235));
        // 话筒本体
        g2.fill(new RoundRectangle2D.Float(cx - w / 2f, cy - h / 2f - 3, w, h, w, w));
        // 拾音支架
        g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Arc2D.Float(cx - 10, cy - 10, 20, 20, 200, 140, Arc2D.OPEN));
        // 支脚
        g2.draw(new java.awt.geom.Line2D.Float(cx, cy + 9, cx, cy + 13));
    }

    private void drawPaused(Graphics2D g2, int cx, int cy) {
        g2.setColor(new Color(210, 216, 230, 235));
        g2.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new java.awt.geom.Line2D.Float(cx - 9, cy + 9, cx + 9, cy - 9));
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(WINDOW_SIZE, WINDOW_SIZE);
    }
}
