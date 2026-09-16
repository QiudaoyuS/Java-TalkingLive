package com.talkinglive.ui;

import com.talkinglive.core.AppConfig;
import com.talkinglive.core.StateMachine;
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
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 常驻悬浮球 —— 产品的**主要入口与状态指示**（{@code DESIGN.md} §4.4）。
 *
 * <p>启动后桌面上只有它：没有主窗口、没有控制台。左键手动开始/结束听写，
 * 右键弹出菜单，拖动可移动位置，拖到左右边缘贴边收起。
 *
 * <p>与浮窗预览条一样，它必须**不抢焦点**：点击悬浮球若让前台程序失去焦点，
 * 正在输入的文字就打断了，注入也会被 §7 的「前台窗口已变」判定放弃。
 *
 * <p>下面这几条是 demo 阶段用真实鼠标事件验证过的结论，直接沿用，改动前请先看
 * {@code docs/ENGINE-EXPERIMENT.md} 与 demo README：
 * <ul>
 *   <li><b>{@code JPopupMenu} 能从不抢焦点的窗口弹出</b>——曾经的真实风险点，已验证可行。</li>
 *   <li><b>贴边收起状态下 {@code mouseEntered} 收不到</b>（实测计数为 0），
 *       所以滑出/收回必须用**轮询**：150ms 一次，且只在贴边期间运行。</li>
 *   <li><b>几何按球体算，不按窗口算</b>：窗口 68px 而球体只占中间 52px，
 *       所以「露出 20px 窗口」实际只露出约 12px 球体。</li>
 *   <li><b>拖动必须夹在屏幕内</b>，否则用户找不到球。</li>
 * </ul>
 */
public class FloatingBall extends JWindow {

    private static final Logger log = LoggerFactory.getLogger(FloatingBall.class);

    /** 悬浮球对外发出的动作。 */
    public interface Listener {
        /** 左键：IDLE 时开始听写，LISTENING 时结束本段。 */
        void onLeftClick();

        /** 菜单「暂停监听 / 恢复监听」。 */
        void onTogglePause();

        /** 菜单「设置...」。 */
        void onOpenSettings();

        /** 菜单「查看日志」。 */
        void onOpenLog();

        /** 菜单「退出」。 */
        void onQuit();
    }

    /** 位置或贴边状态变了，需要持久化。 */
    public interface GeometryListener {
        void onGeometryChanged(int x, int y, AppConfig.DockSide dock);
    }

    private static final int WINDOW_SIZE = Theme.BALL_WINDOW;
    private static final int BALL_SIZE = Theme.BALL_DIAMETER;
    private static final int BALL_INSET = (WINDOW_SIZE - BALL_SIZE) / 2;

    /** 收起后露出多少像素的**窗口**（球体只占中间 52px，所以要加内边距）。 */
    private static final int PEEK = 20;

    /** 松手时距边缘这个距离以内就吸附（附录 A：判定距离 40px）。 */
    private static final int DOCK_THRESHOLD = 40;

    /** 移开鼠标后约 450ms 才收回（§2.3），150ms 轮询 × 3 次。 */
    private static final int OUTSIDE_TICKS_TO_HIDE = 3;

    private final Listener listener;
    private GeometryListener geometryListener;

    private String state = "IDLE";
    private boolean paused = false;
    private boolean hover = false;

    private Point pressPoint;
    private boolean dragged = false;

    private AppConfig.DockSide docked = AppConfig.DockSide.NONE;
    private boolean revealed = true;
    private Timer slideTimer;

    private float pulsePhase = 0f;
    private final Timer pulseTimer;

    /** 贴边状态下轮询鼠标位置——不依赖 ENTERED 事件是否送达。 */
    private Timer dockWatcher;
    private int outsideTicks = 0;

    /** 自检用：收到过几次 mouseEntered（验证「收起状态下收不到」这条结论）。 */
    private int enterCount = 0;

    private final BallPanel panel;

    public FloatingBall(Window owner, Listener listener) {
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

        panel = new BallPanel();
        setContentPane(panel);

        pulseTimer = new Timer(45, e -> {
            pulsePhase += 0.06f;
            if (pulsePhase > 1f) {
                pulsePhase -= 1f;
            }
            repaint();
        });

        installMouseHandlers();
    }

    public void setGeometryListener(GeometryListener l) {
        this.geometryListener = l;
    }

    // ---------- 对外 ----------

    public void setState(StateMachine.State s) {
        setState(s.name());
    }

    /** 用字符串而不是枚举，便于自检与将来扩展；状态名与 {@code StateMachine.State} 一致。 */
    public void setState(String s) {
        String next = s == null ? "IDLE" : s;
        boolean changed = !next.equals(this.state);
        this.state = next;
        if ("LISTENING".equals(next) && !paused) {
            if (!pulseTimer.isRunning()) {
                pulseTimer.start();
            }
        } else {
            pulseTimer.stop();
            pulsePhase = 0f;
        }
        if (changed) {
            log.debug("悬浮球状态变为 {}", next);
        }
        repaint();
    }

    public void setPaused(boolean p) {
        this.paused = p;
        if (p) {
            pulseTimer.stop();
            pulsePhase = 0f;
        } else if ("LISTENING".equals(state)) {
            pulseTimer.start();
        }
        repaint();
    }

    /** 依据保存的配置恢复位置与贴边状态（§4.4「位置与贴边状态需持久化到配置」）。 */
    public void applySavedGeometry(AppConfig.Ball saved) {
        Rectangle screen = getGraphicsConfiguration().getBounds();
        if (saved == null || !saved.hasPosition()) {
            moveToDefaultPosition();
            return;
        }
        if (saved.dockEnabled() && saved.dock() != AppConfig.DockSide.NONE) {
            docked = saved.dock();
            revealed = false;
            setLocation(hiddenX(docked), clampY(saved.y()));
            startDockWatcher();
        } else {
            docked = AppConfig.DockSide.NONE;
            revealed = true;
            setLocationClamped(saved.x(), saved.y());
        }
        // 屏幕布局变了（换了显示器/改了分辨率）时，夹一次保证可见。
        if (!screen.contains(getBounds())) {
            log.info("保存的位置在当前屏幕上不可见，已夹回屏幕内");
            setLocationClamped(getX(), getY());
        }
    }

    /** 默认停在屏幕右侧中部，离边缘留出距离以免一上手就被吸附。 */
    private void moveToDefaultPosition() {
        Rectangle screen = getGraphicsConfiguration().getBounds();
        int x = screen.x + screen.width - WINDOW_SIZE - (DOCK_THRESHOLD + 20);
        int y = screen.y + screen.height / 2 - WINDOW_SIZE / 2;
        setLocation(x, y);
        log.info("悬浮球默认位置：屏幕={} 计算=({},{}) 实际落位=({},{}) 尺寸={}x{}",
                screen, x, y, getX(), getY(), getWidth(), getHeight());
    }

    // ---------- 交互 ----------

    private void installMouseHandlers() {
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
     * <p>即使有托盘图标兜底，把球拖到屏幕外也会让人找不到它，所以夹住位置（§4.4）。
     */
    private void setLocationClamped(int x, int y) {
        Rectangle screen = getGraphicsConfiguration().getBounds();
        int maxX = screen.x + screen.width - WINDOW_SIZE;
        int maxY = screen.y + screen.height - WINDOW_SIZE;
        setLocation(Math.max(screen.x, Math.min(x, maxX)),
                Math.max(screen.y, Math.min(y, maxY)));
    }

    private int clampY(int y) {
        Rectangle screen = getGraphicsConfiguration().getBounds();
        return Math.max(screen.y, Math.min(y, screen.y + screen.height - WINDOW_SIZE));
    }

    // ---------- 贴边收起 ----------

    /** 松手时判断是否该吸附到某一侧。 */
    private void maybeDock() {
        Rectangle screen = getGraphicsConfiguration().getBounds();
        int centerX = getX() + getWidth() / 2;

        AppConfig.DockSide target = AppConfig.DockSide.NONE;
        if (centerX - screen.x < DOCK_THRESHOLD) {
            target = AppConfig.DockSide.LEFT;
        } else if (screen.x + screen.width - centerX < DOCK_THRESHOLD) {
            target = AppConfig.DockSide.RIGHT;
        }

        docked = target;
        if (target == AppConfig.DockSide.NONE) {
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
        notifyGeometry();
    }

    /** 收起后窗口的 x —— 只留 PEEK 像素在屏幕内（球体实际只露出约 12px）。 */
    private int hiddenX(AppConfig.DockSide side) {
        Rectangle screen = getGraphicsConfiguration().getBounds();
        return side == AppConfig.DockSide.LEFT
                ? screen.x - (WINDOW_SIZE - PEEK)
                : screen.x + screen.width - PEEK;
    }

    /** 完全展开后窗口的 x。 */
    private int revealedX(AppConfig.DockSide side) {
        Rectangle screen = getGraphicsConfiguration().getBounds();
        return side == AppConfig.DockSide.LEFT
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
     * <p><b>为什么必须轮询而不是 {@code mouseEntered}：</b>悬浮球收起时大部分在屏幕外
     * （只剩 PEEK 像素可见），原型自检里 {@code mouseEntered} 计数为 <b>0</b>——
     * 事件根本没送达。轮询只在贴边期间运行、150ms 一次，开销可忽略，
     * 且行为完全可控（§4.4 附录 B.3 的实测结论）。
     */
    private void pollDockHover() {
        if (docked == AppConfig.DockSide.NONE) {
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
            if (outsideTicks >= OUTSIDE_TICKS_TO_HIDE) {
                outsideTicks = 0;
                revealed = false;
                slideTo(hiddenX(docked), getY());
            }
        }
    }

    private void revealNow() {
        if (docked == AppConfig.DockSide.NONE || revealed) {
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

    private void notifyGeometry() {
        if (geometryListener != null) {
            geometryListener.onGeometryChanged(getX(), getY(), docked);
        }
    }

    /** 拖动结束后手动触发一次持久化（鼠标释放时位置已定）。 */
    public void persistGeometry() {
        notifyGeometry();
    }

    // ---------- 菜单 ----------

    private void showMenu(MouseEvent e) {
        showMenuAt(e.getX(), e.getY());
    }

    /**
     * 在指定位置弹出菜单。
     *
     * <p>抽出来是为了能被自动化自检直接调用——「菜单能否从**不抢焦点**的窗口上
     * 弹出来」是这个设计里真实存在的风险点（§4.4）。
     */
    public void showMenuAt(int x, int y) {
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

    // ---------- 自检支撑 ----------

    /** 当前是否处于贴边收起状态（供自检断言）。 */
    public boolean isDockedHidden() {
        return docked != AppConfig.DockSide.NONE && !revealed;
    }

    public AppConfig.DockSide dockSide() {
        return docked;
    }

    public boolean revealed() {
        return revealed;
    }

    /** 供自检直接触发吸附判定。 */
    public void dockForTest() {
        maybeDock();
    }

    /** 供自检读取「收到过几次 mouseEntered」。 */
    public int enterCountForTest() {
        return enterCount;
    }

    public void setLocationForTest(int x, int y) {
        setLocation(x, y);
    }

    /**
     * 按屏幕范围夹紧并落位（正常拖动路径在拖动过程中就调用了它）。
     *
     * <p>公开出来是为了能被自检直接验证「拖出屏幕会被夹回来」（§4.4）。
     */
    public void clampIntoPlace() {
        setLocationClamped(getX(), getY());
    }

    /** 供自检推进贴边轮询（不依赖真实 Timer 时序）。 */
    public void pollDockForTest() {
        pollDockHover();
    }

    // ---------- 绘制 ----------

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
            if ("LISTENING".equals(state) && !paused) {
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
            g2.setColor(Theme.ballBase(state, paused));
            g2.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f));

            // 边缘
            Color ring = Theme.ballRing(state, paused);
            g2.setColor(hover ? ring.brighter() : ring);
            g2.setStroke(new BasicStroke(hover ? 2.2f : 1.4f));
            g2.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2f - 1, r * 2f - 1));

            // 收起时只露出一条，把图标往露出的一侧挪，否则看到的是空白
            if (docked != AppConfig.DockSide.NONE && !revealed) {
                int shift = docked == AppConfig.DockSide.LEFT ? BALL_INSET : -BALL_INSET;
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
