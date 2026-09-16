package talkinglive;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.FontUIResource;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionListener;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;

/**
 * TalkingLive 交互原型（Demo）。
 *
 * <p><b>不实现任何真实后端</b>：没有麦克风、没有语音识别、没有 Win32 调用。
 * 识别结果是脚本模拟的，注入目标是一个本地文本框。
 *
 * <p><b>界面结构</b>（与真实产品一致）：
 * <pre>
 *   启动 ──▶ 桌面上只有一颗「悬浮球」，没有主窗口
 *            ├─ 左键点击   手动开始 / 结束听写
 *            ├─ 右键点击   弹出菜单
 *            └─ 拖动       移动位置
 *   菜单 ──▶ 「设置...」  打开设置窗口（常规 / 日志 / 演示）
 *          ▶ 「查看日志」  打开设置窗口并切到日志页
 *   说话 ──▶ 浮窗预览条   贴在光标附近显示实时文字
 * </pre>
 *
 * <p><b>真实的部分</b>：状态机；悬浮球与浮窗预览条都是真正的<b>不抢焦点置顶窗口</b>
 * （{@code setFocusableWindowState(false)} + {@code setAutoRequestFocus(false)}），
 * 这是设计里最容易做错、做错则产品直接废掉的地方。
 *
 * <p>运行见 demo/run.cmd。参数：{@code --auto} 自动循环演示；{@code --settings} 启动即打开设置。
 */
public class Demo implements FloatingBall.Listener {

    // ==================== 模拟数据 ====================

    /**
     * 模拟流式输出。每一步是引擎吐出的<b>整段文本</b>而非增量 ——
     * 这是流式识别的真实行为：引擎隔一会儿就基于全部音频重新解码一遍。
     * 注意场景 2 的「这个需球」→「这个需求」，那是引擎改主意了。
     */
    private static final String[][] PREVIEW_SCRIPTS = {
            {"今天", "今天天", "今天天气", "今天天气不错",
             "今天天气不错我们", "今天天气不错我们去公园吧"},

            {"这个", "这个需求", "这个需球", "这个需求",
             "这个需求我们", "这个需求我们下周再讨论"},

            {"帮我看一下", "帮我看一下那个", "帮我看一下那个报错",
             "帮我看一下那个报错日志", "帮我看一下那个报错日志的堆栈"},
    };

    /** 模拟 whisper 精化后的最终文本：更准，且带标点。 */
    private static final String[] FINAL_TEXTS = {
            "今天天气不错，我们去公园吧。",
            "这个需求我们下周再讨论。",
            "帮我看一下那个报错日志的堆栈。",
    };

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static boolean auto = false;
    private static boolean showSettings = false;
    private static boolean uiTest = false;

    public static void main(String[] args) {
        for (String a : args) {
            if ("--auto".equals(a)) {
                auto = true;
            } else if ("--settings".equals(a)) {
                showSettings = true;
            } else if ("--ui-test".equals(a)) {
                uiTest = true;
            }
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 用默认外观即可
        }
        applyDefaultFonts();
        SwingUtilities.invokeLater(() -> new Demo().start());
    }

    /**
     * 把默认字体统一成含中文字形的字体族。
     *
     * <p>踩过的坑：默认字体在部分 L&F 下没有中文字形，中文会渲染成一串乱码方块，
     * 很容易被误判成编码问题。
     */
    private static void applyDefaultFonts() {
        Font base = Theme.font(12);
        for (Enumeration<Object> keys = UIManager.getDefaults().keys(); keys.hasMoreElements(); ) {
            Object key = keys.nextElement();
            Object value = UIManager.get(key);
            if (value instanceof FontUIResource) {
                UIManager.put(key, new FontUIResource(base));
            }
        }
    }

    // ==================== 配置（原型放内存；真实产品写 JSON） ====================

    String cfgWakeWord = "子曰";
    String cfgEndWord = "到此为止";
    int cfgSilenceSeconds = 5;
    boolean cfgAutoSend = false;
    String cfgSendKey = "Enter";

    // ==================== 组件与运行时状态 ====================

    private final StateMachine sm = new StateMachine();
    private FloatingBall ball;
    private PreviewBar bar;
    private SettingsWindow settings;
    private TrayIcon trayIcon;
    private MenuItem pauseItem;
    private JTextArea targetArea;

    private boolean paused = false;
    private boolean windowChanged = false;

    private int scenario = 0;
    private int step = 0;
    private String lastPartial = "";
    private Timer streamTimer;
    private Timer silenceTimer;
    private Timer workTimer;
    private int silenceLeft = 0;
    private long autoLastAction = 0;

    // ==================== 启动 ====================

    private void start() {
        targetArea = buildTargetArea();

        bar = new PreviewBar(null);
        settings = new SettingsWindow(this);
        ball = new FloatingBall(null, this);
        buildTray();
        wireStateMachine();

        ball.setVisible(true);
        setStateVisual(sm.state());

        log("INFO", "应用启动 version=0.1.0-demo");
        log("INFO", "配置加载 唤醒词=\"" + cfgWakeWord + "\" 结束词=\"" + cfgEndWord
                + "\" 静音结束录制=" + cfgSilenceSeconds + "s");
        log("INFO", "音频设备打开 16kHz/16bit/mono device=\"(模拟)\"");
        log("INFO", "状态变更 IDLE（等待唤醒词）");
        log("INFO", "提示：桌面右侧有一颗悬浮球，右键它可以看到菜单");

        if (showSettings) {
            settings.showTab(SettingsWindow.TAB_GENERAL);
        }
        if (auto) {
            settings.showTab(SettingsWindow.TAB_DEMO);
            startAutoLoop();
        }
        if (uiTest) {
            Timer t = new Timer(1500, e -> {
                ((Timer) e.getSource()).stop();
                runUiSelfTest();
            });
            t.setRepeats(false);
            t.start();
        }
    }

    /**
     * 用 {@link java.awt.Robot} 真实地右键悬浮球，验证菜单能否弹出。
     *
     * <p>为什么需要它：悬浮球是<b>不抢焦点</b>的窗口（{@code setFocusableWindowState(false)}），
     * 而 {@code JPopupMenu} 挂在这种窗口上是有风险的 —— 菜单可能弹不出来，
     * 或者弹出来关不掉。这一点光看代码看不出来，只能真点一下。
     *
     * <p>会让鼠标移动并右键一次，结束后复位。
     */
    private void runUiSelfTest() {
        StringBuilder r = new StringBuilder();
        java.awt.Robot robot;
        try {
            robot = new java.awt.Robot();
        } catch (Exception ex) {
            writeText("ui-selftest-report.txt", "无法创建 Robot: " + ex + "\nRESULT: FAIL\n");
            System.out.println("  RESULT: FAIL (Robot 不可用)");
            System.exit(1);
            return;
        }

        java.awt.Point home = java.awt.MouseInfo.getPointerInfo().getLocation();
        java.awt.Point ballPos = ball.getLocationOnScreen();

        // 关键：getLocationOnScreen() 返回逻辑坐标，Robot 要的是物理坐标，
        // 两者相差一个 DPI 缩放。不换算就会点到错误的地方。
        java.awt.geom.AffineTransform tx =
                ball.getGraphicsConfiguration().getDefaultTransform();
        double kx = tx.getScaleX();
        double ky = tx.getScaleY();
        int cx = (int) Math.round((ballPos.x + ball.getWidth() / 2.0) * kx);
        int cy = (int) Math.round((ballPos.y + ball.getHeight() / 2.0) * ky);

        r.append("DPI 缩放 = ").append(kx).append(" x ").append(ky).append("\n");
        r.append("悬浮球逻辑坐标 = ").append(ballPos.x).append(",").append(ballPos.y)
                .append("  尺寸 ").append(ball.getWidth()).append("x").append(ball.getHeight())
                .append("\n");
        r.append("换算后物理坐标 = ").append(cx).append(",").append(cy).append("\n");
        r.append("悬浮球可获焦点 = ").append(ball.isFocusableWindow())
                .append("（期望 false）\n\n");

        // A) 直接显示菜单：验证「不抢焦点的窗口能否承载 JPopupMenu」
        boolean direct = false;
        try {
            ball.showMenuAt(ball.getWidth() / 2, ball.getHeight() / 2);
            robot.delay(700);
            direct = isMenuOpen();
            r.append("A. 直接显示菜单         = ").append(direct ? "弹出成功" : "没有弹出").append("\n");
            dismissMenu(robot);
        } catch (Exception ex) {
            r.append("A. 直接显示菜单         = 异常 ").append(ex).append("\n");
        }

        // B) Robot 真实右键：验证鼠标事件能否投递到不抢焦点的窗口
        boolean clicked = false;
        try {
            robot.mouseMove(cx, cy);
            robot.delay(350);
            robot.mousePress(java.awt.event.InputEvent.BUTTON3_DOWN_MASK);
            robot.delay(90);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON3_DOWN_MASK);
            robot.delay(700);
            clicked = isMenuOpen();
            r.append("B. Robot 真实右键悬浮球 = ").append(clicked ? "弹出成功" : "没有弹出").append("\n");
            dismissMenu(robot);
        } catch (Exception ex) {
            r.append("B. Robot 真实右键悬浮球 = 异常 ").append(ex).append("\n");
        }

        boolean ok = direct && clicked;

        r.append("\n结论：\n");
        if (!direct) {
            r.append("  JPopupMenu 无法从不抢焦点的窗口弹出。\n")
                    .append("  需要改用自绘弹层，或让悬浮球在弹菜单时临时可获焦点。\n");
        } else if (!clicked) {
            r.append("  菜单本身可用，但鼠标事件没能投递到悬浮球。\n")
                    .append("  检查坐标换算，或窗口样式是否吞掉了鼠标事件。\n");
        } else {
            r.append("  悬浮球右键菜单工作正常。\n");
        }
        r.append("\nRESULT: ").append(ok ? "PASS" : "FAIL").append("\n");

        robot.mouseMove(home.x, home.y);
        writeText("ui-selftest-report.txt", r.toString());

        System.out.println("UI self-test (right-click floating ball)");
        System.out.println("  A. direct popup  : " + (direct ? "PASS" : "FAIL"));
        System.out.println("  B. robot r-click : " + (clicked ? "PASS" : "FAIL"));
        System.out.println("  report = ui-selftest-report.txt");
        System.exit(ok ? 0 : 1);
    }

    private static boolean isMenuOpen() {
        javax.swing.MenuElement[] path =
                javax.swing.MenuSelectionManager.defaultManager().getSelectedPath();
        return path != null && path.length > 0;
    }

    private static void dismissMenu(java.awt.Robot robot) {
        robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);
        robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
        robot.delay(250);
    }

    private static void writeText(String path, String content) {
        java.io.PrintWriter w = null;
        try {
            w = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(path), java.nio.charset.StandardCharsets.UTF_8));
            w.print(content);
        } catch (Exception e) {
            System.out.println("WARN: 无法写入 " + path + ": " + e.getMessage());
        } finally {
            if (w != null) {
                w.close();
            }
        }
    }

    private JTextArea buildTargetArea() {
        JTextArea area = new JTextArea(4, 40);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Theme.font(15));
        area.setBackground(new Color(250, 250, 252));
        area.setForeground(new Color(30, 32, 40));
        area.setBorder(new EmptyBorder(8, 10, 8, 10));
        return area;
    }

    /** 供设置窗口的「演示」页签嵌入。 */
    JTextArea targetArea() {
        return targetArea;
    }

    // ==================== 悬浮球回调 ====================

    @Override
    public void onLeftClick() {
        // 手动听写：不用喊唤醒词，适合安静环境或唤醒词不好念的场合
        if (paused) {
            log("WARN", "[ball] 已暂停监听，忽略手动听写");
            return;
        }
        if (sm.state() == StateMachine.State.IDLE) {
            log("INFO", "[ball] 悬浮球左键 → 手动开始听写");
            startListening();
        } else if (sm.state() == StateMachine.State.LISTENING) {
            log("INFO", "[ball] 悬浮球左键 → 手动结束本段");
            simulateEndWord();
        }
    }

    @Override
    public void onTogglePause() {
        paused = !paused;
        if (pauseItem != null) {
            pauseItem.setLabel(paused ? "恢复监听" : "暂停监听");
        }
        log("INFO", paused ? "监听已暂停（不再响应唤醒词与结束词）" : "监听已恢复");
        setStateVisual(sm.state());
    }

    @Override
    public void onOpenSettings() {
        settings.showTab(SettingsWindow.TAB_GENERAL);
    }

    @Override
    public void onOpenLog() {
        settings.showTab(SettingsWindow.TAB_LOG);
    }

    @Override
    public void onQuit() {
        log("INFO", "退出");
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
        System.exit(0);
    }

    // ==================== 状态机接线 ====================

    private void wireStateMachine() {
        sm.onTransition((from, to) -> {
            log("INFO", "[state] " + from + " -> " + to);
            setStateVisual(to);
            if (to == StateMachine.State.COMMITTING) {
                onCommit();
            } else if (to == StateMachine.State.IDLE
                    && from == StateMachine.State.LISTENING) {
                stopTimers();
                bar.hideBar();
                log("INFO", "[kws] 已取消，本段丢弃，一个字都没有注入");
            }
        });
    }

    private void setStateVisual(StateMachine.State s) {
        if (ball != null) {
            ball.setPaused(paused);
            ball.setState(s);
        }
        updateTray(s);
    }

    // ==================== 模拟事件 ====================

    /** 语音唤醒。 */
    void simulateWake() {
        if (paused) {
            log("WARN", "[kws] 已暂停监听，忽略唤醒词");
            return;
        }
        if (sm.state() != StateMachine.State.IDLE) {
            log("WARN", "[kws] 当前不在待唤醒状态，忽略");
            return;
        }
        log("INFO", "[kws] 唤醒词命中「" + cfgWakeWord + "」");
        startListening();
    }

    private void startListening() {
        windowChanged = false;
        scenario = scenario % PREVIEW_SCRIPTS.length;
        step = 0;
        lastPartial = "";

        sm.fire(StateMachine.Event.WAKE);
        log("INFO", "[state] 目标窗口锁定 0x001A2B3C \"记事本（模拟）\"");
        log("INFO", "[asr] 开始流式识别（小 Vosk，无标点）");

        renderBar("", "", "聆听中 · 小 Vosk 流式（无标点、会改写）");
        startStreaming();
    }

    private void startStreaming() {
        stopTimers();
        streamTimer = new Timer(360, e -> tickStream());
        streamTimer.start();
    }

    private void tickStream() {
        String[] script = PREVIEW_SCRIPTS[scenario];
        if (step >= script.length) {
            stopTimers();
            startSilenceCountdown();
            return;
        }
        String current = script[step++];

        // 前缀比后缀稳定：用最长公共前缀演示「已稳定 / 仍在变」的分界
        String stable = lcp(lastPartial, current);
        String pending = current.substring(stable.length());
        lastPartial = current;

        renderBar(stable, pending, "聆听中 · 小 Vosk 流式（无标点、会改写）");
        log("INFO", "[asr] 中间结果 #" + step + " len=" + current.length() + "（内容不记录）");
    }

    private void startSilenceCountdown() {
        final int total = cfgSilenceSeconds;
        if (total <= 0) {
            renderBar(lastPartial, "", "静音自动结束已关闭 · 请说结束词");
            log("INFO", "[state] 静音自动结束已关闭，等待结束词");
            return;
        }
        silenceLeft = total;
        renderSilence();
        silenceTimer = new Timer(1000, e -> {
            silenceLeft--;
            if (silenceLeft <= 0) {
                stopTimers();
                log("INFO", "[state] 静音超时 " + total + ".0s，自动结束本段");
                sm.fire(StateMachine.Event.SILENCE);
            } else {
                renderSilence();
            }
        });
        silenceTimer.start();
    }

    private void renderSilence() {
        renderBar(lastPartial, "",
                "等待结束词「" + cfgEndWord + "」· 静音 " + silenceLeft + "s 后结束录制");
    }

    void simulateEndWord() {
        if (sm.state() != StateMachine.State.LISTENING) {
            log("WARN", "[kws] 当前不在听写中，忽略结束词");
            return;
        }
        stopTimers();
        log("INFO", "[kws] 结束词命中「" + cfgEndWord + "」");
        sm.fire(StateMachine.Event.END_WORD);
    }

    void simulateSilence() {
        if (sm.state() != StateMachine.State.LISTENING) {
            log("WARN", "[state] 当前不在听写中，忽略静音事件");
            return;
        }
        stopTimers();
        log("INFO", "[state] 静音超时触发（手动模拟）");
        sm.fire(StateMachine.Event.SILENCE);
    }

    void simulateWindowChange() {
        if (sm.state() != StateMachine.State.LISTENING) {
            log("WARN", "[win] 当前不在听写中，忽略窗口变化");
            return;
        }
        windowChanged = true;
        stopTimers();
        log("INFO", "[win] 前台窗口变化 0x001A2B3C -> 0x002F1E4A");
        sm.fire(StateMachine.Event.WINDOW_CHANGE);
    }

    void simulateCancel() {
        if (sm.state() == StateMachine.State.IDLE) {
            log("WARN", "[state] 当前无进行中的听写");
            return;
        }
        stopTimers();
        log("INFO", "[input] 用户按下 Esc");
        sm.fire(StateMachine.Event.CANCEL);
    }

    void replay() {
        stopTimers();
        bar.hideBar();
        scenario = (scenario + 1) % PREVIEW_SCRIPTS.length;
        while (sm.state() != StateMachine.State.IDLE) {
            sm.fire(StateMachine.Event.CANCEL);
        }
        targetArea.setText("");
        log("INFO", "---- 重播：切换到场景 " + (scenario + 1) + " ----");
        setStateVisual(sm.state());

        Timer delay = new Timer(400, e -> {
            ((Timer) e.getSource()).stop();
            simulateWake();
        });
        delay.setRepeats(false);
        delay.start();
    }

    // ==================== 提交 ====================

    private void onCommit() {
        stopTimers();
        renderBar(lastPartial, "", "处理中 · whisper 正在精化（约 1.5s）");
        log("INFO", "[refiner] whisper 启动（重跑本段音频）");

        workTimer = new Timer(1500, e -> {
            ((Timer) e.getSource()).stop();
            String finalText = FINAL_TEXTS[scenario % FINAL_TEXTS.length];
            log("INFO", "[refiner] whisper 完成 len=" + finalText.length() + "（内容不记录）");
            renderBar(finalText, "", "即将注入");
            log("INFO", "[inject] 校验前台窗口 0x001A2B3C ...");

            Timer t2 = new Timer(700, e2 -> {
                ((Timer) e2.getSource()).stop();
                doInject(finalText);
            });
            t2.setRepeats(false);
            t2.start();
        });
        workTimer.setRepeats(false);
        workTimer.start();
    }

    private void doInject(String text) {
        bar.hideBar();

        if (windowChanged) {
            log("WARN", "[inject] 注入放弃 原因=前台窗口已变更 "
                    + "期望=0x001A2B3C 实际=0x002F1E4A");
            notifyUser("刚才那段没有注入成功",
                    "提交时前台窗口已经变了。\n为避免把文字误发到别的程序，已放弃注入。");
            sm.fire(StateMachine.Event.COMMITTED);
            return;
        }

        targetArea.append(text);
        targetArea.setCaretPosition(targetArea.getDocument().getLength());
        log("INFO", "[inject] 注入成功 chars=" + text.length() + " target=0x001A2B3C");

        if (cfgAutoSend) {
            targetArea.append("Enter".equals(cfgSendKey) ? "\n" : "\n[Ctrl+Enter]\n");
            log("INFO", "[send] 自动发送 key=" + cfgSendKey);
        }
        sm.fire(StateMachine.Event.COMMITTED);
    }

    private void notifyUser(String title, String body) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, body, TrayIcon.MessageType.WARNING);
        }
        JOptionPane.showMessageDialog(null, body, title, JOptionPane.WARNING_MESSAGE);
    }

    // ==================== 浮窗 ====================

    private void renderBar(String stable, String pending, String status) {
        bar.setAnchor(previewAnchor());
        bar.render(stable, pending, status);
    }

    /** 设置窗口开着就贴模拟输入框，否则贴悬浮球（真实产品一律跟随系统光标）。 */
    private JComponent previewAnchor() {
        if (settings != null && settings.isVisible()) {
            return targetArea;
        }
        return (JComponent) ball.getContentPane();
    }

    // ==================== 托盘（悬浮球之外的兜底入口） ====================

    private void buildTray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        PopupMenu menu = new PopupMenu();
        pauseItem = new MenuItem("暂停监听");
        pauseItem.addActionListener(e -> onTogglePause());
        menu.add(pauseItem);
        menu.addSeparator();

        MenuItem s = new MenuItem("设置...");
        s.addActionListener(e -> onOpenSettings());
        menu.add(s);

        MenuItem l = new MenuItem("查看日志");
        l.addActionListener(e -> onOpenLog());
        menu.add(l);
        menu.addSeparator();

        MenuItem q = new MenuItem("退出");
        q.addActionListener(e -> onQuit());
        menu.add(q);

        trayIcon = new TrayIcon(stateIcon(Theme.DIM), "TalkingLive · 待唤醒", menu);
        trayIcon.setImageAutoSize(true);
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception ex) {
            log("WARN", "托盘图标添加失败: " + ex.getMessage());
        }
    }

    private void updateTray(StateMachine.State s) {
        if (trayIcon == null) {
            return;
        }
        if (paused) {
            trayIcon.setImage(stateIcon(Theme.WARN));
            trayIcon.setToolTip("TalkingLive · 已暂停");
            return;
        }
        switch (s) {
            case LISTENING:
                trayIcon.setImage(stateIcon(Theme.ERR));
                trayIcon.setToolTip("TalkingLive · 听写中");
                break;
            case COMMITTING:
                trayIcon.setImage(stateIcon(Theme.WARN));
                trayIcon.setToolTip("TalkingLive · 提交中");
                break;
            default:
                trayIcon.setImage(stateIcon(Theme.DIM));
                trayIcon.setToolTip("TalkingLive · 待唤醒");
                break;
        }
    }

    private static Image stateIcon(Color color) {
        int s = 16;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.setStroke(new BasicStroke(2.2f));
        g.drawOval(3, 3, s - 7, s - 7);
        g.dispose();
        return img;
    }

    // ==================== 自动演示 ====================

    private void startAutoLoop() {
        log("INFO", "[demo] 自动演示模式已开启（--auto），将持续循环整段流程");
        Timer driver = new Timer(400, e -> driveAuto());
        driver.start();
    }

    /** 按状态机当前状态推进流程：待唤醒则唤醒，听写完则说结束词。 */
    private void driveAuto() {
        long now = System.currentTimeMillis();
        if (now - autoLastAction < 3600) {
            return;
        }
        if (sm.state() == StateMachine.State.IDLE) {
            autoLastAction = now;
            simulateWake();
        } else if (sm.state() == StateMachine.State.LISTENING
                && (streamTimer == null || !streamTimer.isRunning())) {
            autoLastAction = now;
            simulateEndWord();
        }
    }

    // ==================== 工具 ====================

    private void stopTimers() {
        if (streamTimer != null) {
            streamTimer.stop();
        }
        if (silenceTimer != null) {
            silenceTimer.stop();
        }
        if (workTimer != null) {
            workTimer.stop();
        }
    }

    /** 最长公共前缀 —— 方案 C「稳定前缀注入」的核心算法。 */
    static String lcp(String a, String b) {
        if (a == null || b == null) {
            return "";
        }
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return a.substring(0, i);
    }

    void log(String level, String message) {
        if (settings != null) {
            settings.appendLog(LocalTime.now().format(TS) + " " + pad(level) + " " + message);
        }
    }

    private static String pad(String level) {
        StringBuilder b = new StringBuilder(level);
        while (b.length() < 5) {
            b.append(' ');
        }
        return b.toString();
    }

    /** 扁平深色按钮 —— 系统默认外观在深色面板上过于突兀。 */
    JButton button(String text, ActionListener listener) {
        JButton b = new FlatButton(text);
        b.setFont(Theme.font(12));
        b.setFocusable(false);
        b.addActionListener(listener);
        return b;
    }

    static class FlatButton extends JButton {
        FlatButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setForeground(Theme.TEXT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(9, 10, 9, 10));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            Color bg;
            if (getModel().isPressed()) {
                bg = new Color(60, 68, 92);
            } else if (getModel().isRollover()) {
                bg = new Color(52, 58, 76);
            } else {
                bg = new Color(43, 48, 63);
            }
            g2.setColor(bg);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 9, 9));
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
