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
import java.awt.geom.Ellipse2D;
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

    /** 球心图标尺寸：略小于球径的一半多一点，留出边缘环的空间。 */
    private static final int MIC_ICON_SIZE = Math.round(BALL_SIZE * 0.58f);

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

    /**
     * 当前声浪电平（原始归一化 RMS，0..1）。由音频线程经 {@link #setLevel} 写入。
     *
     * <p>用 {@code double} 而不是原子类型：它是**显示**用的，偶尔读到撕裂的旧值
     * 只会让某根柱子高一点点，下一帧就修好了；为它引入同步反而会在音频线程上
     * 制造锁竞争。
     */
    private volatile double rawLevel = 0;

    /** 平滑后的显示电平（0..1）。在 EDT 上逐帧向 {@link #rawLevel} 靠近。 */
    private double displayLevel = 0;
    /** 待唤醒时的闲置起伏相位，让球看起来"活着"而不是一块静止的图案。 */
    private double idlePhase = 0;
    private final Timer levelTimer;

    /** 声浪柱的显示电平上限 —— RMS 直接映射的话正常说话只有 0.05，柱子几乎不动。 */
    private static final double LEVEL_FULL_SCALE = 0.22;

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

        // 声浪的逐帧推进：新电平**立刻**跟上（不然说话时柱子会慢半拍），
        // 回落则慢一些（不然每个字的间隙柱子都会塌到底，看起来像在闪）。
        // 33ms ≈ 30fps，对这种小幅动画足够，也不会把 EDT 占满。
        levelTimer = new Timer(33, e -> {
            double target = Math.min(1.0, rawLevel / LEVEL_FULL_SCALE);
            displayLevel = target >= displayLevel
                    ? displayLevel + (target - displayLevel) * 0.55
                    : displayLevel + (target - displayLevel) * 0.18;
            idlePhase += 0.11;
            if (idlePhase > Math.PI * 2) {
                idlePhase -= Math.PI * 2;
            }
            repaint();
        });
        levelTimer.start();

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

    /**
     * 更新声浪电平（原始归一化 RMS，0..1）。**会被音频线程调用，必须立刻返回。**
     *
     * <p>它只写一个 volatile 字段，平滑与重绘都交给 EDT 上的 {@link #levelTimer} ——
     * 音频线程上不能做任何有代价的事，否则丢帧就是丢音频。
     */
    public void setLevel(double rms) {
        this.rawLevel = rms < 0 ? 0 : rms;
    }

    /**
     * 把悬浮球当前的画面画到一张离屏图上（供自检断言）。
     *
     * <p>为什么需要它：本轮的核心外观需求是「柱子跟随声浪大小变化」，而这类事情
     * 光靠肉眼截图确认不可靠 —— 截图的坐标要过 DPI 换算、还要求当时真的有人在说话。
     * 离屏渲染把"画面"变成可比对的像素：给一个电平和另一个电平，两张图必须不同，
     * 而且电平原样反映在柱子的高度上。
     */
    public java.awt.image.BufferedImage renderForTest() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                Math.max(1, getWidth()), Math.max(1, getHeight()),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        panel.paint(g);
        g.dispose();
        return img;
    }

    /** 当前显示电平（供自检断言「柱子确实会随声音变化」）。 */
    public double displayLevelForTest() {
        return displayLevel;
    }

    /**
     * 立刻把显示电平推到目标值（仅供自检）。
     *
     * <p>正常路径上电平由 {@code levelTimer} 逐帧平滑逼近 —— 那是刻意的（见构造函数里的
     * 说明），但自检不能等它跑几十帧，所以这里直通。
     */
    public void settleLevelForTest() {
        displayLevel = Math.min(1.0, rawLevel / LEVEL_FULL_SCALE);
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
        showMenu(buildMenu(), this, x, y);
    }

    /**
     * 按**屏幕坐标**弹出同一个菜单，调用方自己指定"挂在哪个组件上"。
     *
     * <p>存在的理由是托盘入口：{@code TrayIcon} 用的 {@code java.awt.PopupMenu} 是
     * **原生 Win32 菜单**，实测在 150% DPI 下**不认 AWT 设的字体**，
     * 汉字全画成方块（给每个 MenuItem setFont 也没用 —— 那一版改过，无效）。
     * 而 Swing 的 {@code JPopupMenu} 是自绘的，中文字形完全正常。
     * 所以托盘右键不再走原生菜单，改弹这一个。
     *
     * @param invoker    用哪个组件当弹窗的宿主（决定坐标系与生命周期），通常传悬浮球
     * @param screenX    屏幕坐标 X（Win32 与 AWT 在同一坐标空间，见 DpiScale）
     * @param screenY    屏幕坐标 Y
     */
    public void showMenuAtScreen(java.awt.Component invoker, int screenX, int screenY) {
        java.awt.Point p = new java.awt.Point(screenX, screenY);
        javax.swing.SwingUtilities.convertPointFromScreen(p, invoker);
        showMenu(buildMenu(), invoker, p.x, p.y);
    }

    /**
     * 弹出菜单，带一道**闸门**：已经有一个在显示时就不再弹。
     *
     * <p>闸门的由来（用户反馈）：「点击托盘右键后，再点击其他位置时悬浮球右键菜单
     * 自动弹出」。分析下来是菜单在短时间内被多次请求 —— {@code JPopupMenu.show}
     * 可以重复调用，于是会重叠、也会在被点掉之后又被弹回来。
     * 这里直接以"当前有没有菜单在显示"为准，比去猜事件来源可靠得多。
     *
     * <p>注意用的是 {@code isVisible()} 而不是自建布尔量：Swing 会在菜单被点掉、
     * 被 Esc 关掉、或失焦时把 visible 置回 false，自建标志位必然与它不同步。
     */
    private void showMenu(JPopupMenu menu, java.awt.Component invoker, int x, int y) {
        if (activeMenu != null && activeMenu.isVisible()) {
            log.debug("已有菜单在显示，忽略这次弹出请求");
            return;
        }
        activeMenu = menu;
        menu.show(invoker, x, y);
    }

    /** 当前正在显示的菜单（见 {@link #showMenu} 的闸门）。 */
    private JPopupMenu activeMenu;

    /** 构造菜单（悬浮球右键与托盘右键共用同一份，避免两处文案/行为漂移）。 */
    private JPopupMenu buildMenu() {
        JPopupMenu menu = new JPopupMenu();
        // 菜单字体走 Theme.menuFont：它保证有中文字形（详情见 Theme.menuFont 的注释）——
        // 这里曾经用 Theme.font，两者在本机恰好都指向 YaHei UI，但语义不同：
        // menuFont 是"给菜单用的、已确认能画中文的字体"。
        menu.setFont(Theme.menuFont(12));

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

        return menu;
    }

    private static JMenuItem item(String text) {
        JMenuItem i = new JMenuItem(text);
        i.setFont(Theme.menuFont(12));
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
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);

            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            int r = BALL_SIZE / 2;

            // 听写中的脉冲光圈：白色主题下用状态色的**淡**描边（浓了会像警报）
            if ("LISTENING".equals(state) && !paused) {
                int pr = (int) (r + pulsePhase * (r * 0.5));
                int alpha = (int) (90 * (1f - pulsePhase));
                g2.setColor(new Color(Theme.ERR.getRed(), Theme.ERR.getGreen(),
                        Theme.ERR.getBlue(), Math.max(alpha, 0)));
                g2.setStroke(new BasicStroke(2.0f));
                g2.draw(new Ellipse2D.Float(cx - pr, cy - pr, pr * 2f, pr * 2f));
            }

            // 投影：极淡、只偏下 3px。白色浮层靠它"浮起来"，
            // 投影一重就从"系统原生"变成"网页按钮"。
            g2.setColor(Theme.SHADOW_STRONG);
            g2.fill(new Ellipse2D.Float(cx - r, cy - r + 3, r * 2f, r * 2f));

            // 球体（白色主题下始终是白球，状态靠内部波浪的颜色与幅度表达）
            g2.setColor(Theme.ballBase(state, paused));
            g2.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f));

            // 边缘：1px 极浅描边。hover 时略加深，作为"可点"的反馈
            Color ring = Theme.ballRing(state, paused);
            g2.setColor(hover ? Theme.HOVER : ring);
            g2.setStroke(new BasicStroke(hover ? 1.6f : 1.0f));
            g2.draw(new Ellipse2D.Float(cx - r + 0.5f, cy - r + 0.5f, r * 2f - 1, r * 2f - 1));

            // 内容：暂停 = 斜杠；否则 = 声浪柱
            if (docked != AppConfig.DockSide.NONE && !revealed) {
                // 收起时只露出一条，把图案往露出的一侧挪，否则看到的是空白
                int shift = docked == AppConfig.DockSide.LEFT ? BALL_INSET : -BALL_INSET;
                Graphics2D g3 = (Graphics2D) g2.create();
                g3.translate(shift, 0);
                drawContent(g3, cx, cy);
                g3.dispose();
            } else {
                drawContent(g2, cx, cy);
            }

            g2.dispose();
        }
    }

    private void drawContent(Graphics2D g2, int cx, int cy) {
        if (paused) {
            drawPaused(g2, cx, cy);
        } else {
            drawWaveBars(g2, cx, cy);
        }
    }

    /**
     * 声浪柱 —— 悬浮球的内容。
     *
     * <p>替代了原来的话筒图标：用户明确要求"拾音器一样的波浪柱，跟随收音的声浪大小变化"。
     * 这不只是好看一点：话筒是一个静态图案，而柱子**本身就是麦克风正在工作的证据** ——
     * 说话时它长高、安静时它收平，用户不必去别处确认"到底有没有在录"。
     *
     * <p><b>柱子从同一条底线向上长，而不是从中心上下对称伸展。</b>
     * 这个选择有实测依据：对称伸展的柱子在变高时，像素只是从一头搬到另一头，
     * **总墨量几乎不变**（自检 F1 就是这么把它抓出来的：电平 0.00→0.68，
     * 柱子像素 2314→2314）。从底线向上长才是"音量"的通用表达 ——
     * 语音备忘录、会议软件的音量条都是这个画法，而且墨量真的随音量增加。
     *
     * <p>其余三处刻意的设计：
     * <ul>
     *   <li><b>五根柱子，中间高两边低</b>：均衡器的通用形状，看一眼就知道是音频。</li>
     *   <li><b>待唤醒时的"闲置起伏"</b>：完全不动的柱子会被误读成"卡住了"。
     *       幅度极小的正弦错相位起伏让它看起来是活的，又不至于在用户没说话时
     *       假装听到了什么。</li>
     *   <li><b>最低高度不为 0</b>：柱子塌成一条线会让整颗球显得空。留一点底。</li>
     * </ul>
     */
    private void drawWaveBars(Graphics2D g2, int cx, int cy) {
        boolean live = "LISTENING".equals(state) && !paused;
        // 形状因子：中间最高，向两侧递减（均衡器的通用形状）
        double[] shape = {0.5, 0.78, 1.0, 0.78, 0.5};
        int bars = shape.length;
        int gap = 3;
        int barWidth = 3;
        int totalWidth = bars * barWidth + (bars - 1) * gap;
        int left = cx - totalWidth / 2 + barWidth / 2;

        double level = live ? displayLevel : 0;
        // 底线：略微偏下，给柱子留出最长的生长空间
        int baseY = cy + (int) (BALL_SIZE * 0.17);
        int maxHeight = (int) (BALL_SIZE * 0.56);

        g2.setColor(Theme.waveColor(state, paused));
        g2.setStroke(new BasicStroke(barWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        for (int i = 0; i < bars; i++) {
            double f;
            if (live) {
                // 每根柱子给一点差异，否则五根一模一样地跳，像进度条而不像声浪
                double jitter = 0.78 + 0.22 * Math.sin(idlePhase * 1.9 + i * 1.4);
                f = Math.max(level * jitter, 0.07);
            } else {
                // 闲置起伏：0.16–0.30 之间轻轻呼吸
                f = 0.16 + 0.14 * (0.5 + 0.5 * Math.sin(idlePhase + i * 1.1));
            }
            int h = Math.max(3, (int) Math.round(maxHeight * shape[i] * f));
            int x = left + i * (barWidth + gap);
            g2.draw(new java.awt.geom.Line2D.Float(x, baseY - h, x, baseY));
        }
    }

    /**
     * 暂停图案：一条斜杠。
     *
     * <p>形状来自 {@link Icons}（与托盘图标同一份路径），这里只负责把它摆到球心、
     * 缩到球内合适的大小与颜色。它取代了原来的话筒图标 —— 球的内容现在是声浪柱，
     * 而"暂停"必须与"正在拾音"一眼可分，所以用一个完全不同的形状（斜杠）而不是
     * 改颜色：颜色在灰度/色觉障碍下不可靠，形状可靠。
     */
    private void drawPaused(Graphics2D g2, int cx, int cy) {
        drawIcon(g2, Icons.Kind.PAUSED, cx, cy, MIC_ICON_SIZE, Theme.TEXT_FAINT);
    }

    private void drawIcon(Graphics2D g2, Icons.Kind kind, int cx, int cy, int size, Color color) {
        Icons.of(kind, size, color).paintIcon(null, g2, cx - size / 2, cy - size / 2);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(WINDOW_SIZE, WINDOW_SIZE);
    }
}
