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
 * 常驻悬浮球 —— 产品**唯一始终可见**的界面元素。
 *
 * <p>程序启动后桌面上只有它：没有主窗口、没有控制台。
 * 左键点击手动开始/结束听写，右键弹出菜单，拖动可移动位置。
 *
 * <p>与浮窗预览条一样，它必须**不抢焦点** —— 否则点击悬浮球会让前台
 * 程序失去焦点，正在输入的文字就打断了。
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

    private static final int WINDOW_SIZE = 68;   // 含脉冲光圈留白
    private static final int BALL_SIZE = 52;

    private final Listener listener;

    private StateMachine.State state = StateMachine.State.IDLE;
    private boolean paused = false;
    private boolean hover = false;

    private Point pressPoint;
    private boolean dragged = false;

    private float pulsePhase = 0f;
    private final Timer pulseTimer;

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

    /** 默认停在屏幕右侧中部，避免遮挡常见内容区。 */
    private void moveToDefaultPosition() {
        Rectangle screen = getGraphicsConfiguration().getBounds();
        int x = screen.x + screen.width - WINDOW_SIZE - 40;
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
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                hover = true;
                repaint();
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
                Point p = e.getLocationOnScreen();
                setLocationClamped(p.x - pressPoint.x, p.y - pressPoint.y);
            }
        });
    }

    /**
     * 把悬浮球夹在屏幕范围内。
     *
     * <p>没有托盘图标之后，悬浮球是**退出程序的唯一入口** ——
     * 一旦被拖到屏幕外就再也点不到了，只能去任务管理器。所以必须夹住位置。
     */
    private void setLocationClamped(int x, int y) {
        Rectangle screen = getGraphicsConfiguration().getBounds();
        int maxX = screen.x + screen.width - WINDOW_SIZE;
        int maxY = screen.y + screen.height - WINDOW_SIZE;
        setLocation(Math.max(screen.x, Math.min(x, maxX)),
                Math.max(screen.y, Math.min(y, maxY)));
    }

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

            if (paused) {
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
