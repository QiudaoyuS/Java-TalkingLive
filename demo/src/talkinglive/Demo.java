package talkinglive;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * TalkingLive 交互原型（Demo）。
 *
 * <p><b>不实现任何真实后端</b>：没有麦克风、没有语音识别、没有 Win32 调用。
 * 所有识别结果都是脚本模拟的，注入目标是一个本地的文本框。
 *
 * <p>目的只有一个：<b>看整体界面与操作流程是否合理</b>。
 *
 * <p>两处是"真的"：
 * <ul>
 *   <li>浮窗是真正的不抢焦点置顶窗口（{@code setFocusableWindowState(false)}
 *       + {@code setAutoRequestFocus(false)}），可以在下面的输入框里打字、
 *       同时观察浮窗弹出时会不会抢走焦点 —— 这是 DESIGN.md 里标记的最硬的坑</li>
 *   <li>状态机是真实的 {@link StateMachine}，流程与设计一致</li>
 * </ul>
 *
 * <p>运行：见 demo/run.cmd
 */
public class Demo {

    // ==================== 模拟数据 ====================

    /**
     * 模拟 Vosk 的流式输出。
     *
     * <p>每一步是引擎吐出的<b>整段文本</b>，不是增量 —— 这是流式识别的真实行为：
     * 引擎隔一段时间就基于全部音频重新解码一遍。注意场景 2 里的
     * "这个需球" -> "这个需求"，那是引擎改主意了。
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

    private static final Color C_BG        = new Color(28, 31, 40);
    private static final Color C_PANEL     = new Color(37, 41, 53);
    private static final Color C_TEXT      = new Color(228, 232, 242);
    private static final Color C_DIM       = new Color(138, 144, 166);
    private static final Color C_ACCENT    = new Color(94, 129, 244);
    private static final Color C_OK        = new Color(66, 190, 130);
    private static final Color C_WARN      = new Color(232, 163, 61);
    private static final Color C_ERR       = new Color(226, 94, 94);

    // ==================== 状态 ====================

    private final StateMachine sm = new StateMachine();

    private JFrame console;
    private JLabel stateBadge;
    private JLabel hintLabel;
    private JTextArea targetArea;
    private JTextArea logArea;
    private JTextField wakeField;
    private JTextField endField;
    private JTextField silenceField;
    private JCheckBox autoSendBox;
    private JComboBox<String> sendKeyBox;

    private FloatBar bar;
    private TrayIcon trayIcon;
    private MenuItem pauseItem;

    private boolean paused = false;
    private boolean windowChanged = false;

    private int scenario = 0;
    private int step = 0;
    private String lastPartial = "";
    private Timer streamTimer;
    private Timer silenceTimer;
    private Timer workTimer;
    private int silenceLeft = 0;

    // ==================== 入口 ====================

    /** --auto：自动循环演示整段流程，不需要手动点按钮。 */
    private static boolean AUTO = false;

    public static void main(String[] args) {
        for (String a : args) {
            if ("--auto".equals(a)) {
                AUTO = true;
            }
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 用默认外观即可
        }
        SwingUtilities.invokeLater(() -> new Demo().start());
    }

    private void start() {
        buildConsole();
        buildFloatBar();
        buildTray();
        wireStateMachine();

        console.setVisible(true);
        setStateVisual(sm.state());

        log("INFO", "应用启动 version=0.1.0-demo");
        log("INFO", "配置加载 唤醒词=\"" + wakeField.getText() + "\" 结束词=\""
                + endField.getText() + "\" 静音兜底=" + silenceField.getText() + "s");
        log("INFO", "音频设备打开 16kHz/16bit/mono device=\"(模拟)\"");
        log("INFO", "状态变更 IDLE（等待唤醒词）");

        if (AUTO) {
            startAutoLoop();
        }
    }

    // ---------- 自动演示 ----------

    private long autoLastAction = 0;

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

    // ==================== 控制台窗口 ====================

    private void buildConsole() {
        console = new JFrame("TalkingLive — 交互原型控制台");
        console.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        console.setLayout(new BorderLayout());
        console.getContentPane().setBackground(C_BG);

        console.add(buildHeader(), BorderLayout.NORTH);
        console.add(buildBody(), BorderLayout.CENTER);
        console.add(buildLogPane(), BorderLayout.SOUTH);

        console.setSize(760, 800);
        console.setLocation(80, 60);
    }

    private JComponent buildHeader() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(C_BG);
        p.setBorder(new EmptyBorder(14, 18, 10, 18));

        JLabel title = new JLabel("TalkingLive 交互原型");
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 17));
        title.setForeground(C_TEXT);

        stateBadge = new JLabel("  ", SwingConstants.CENTER);
        stateBadge.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 13));
        stateBadge.setOpaque(true);
        stateBadge.setBorder(new EmptyBorder(5, 14, 5, 14));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(title, BorderLayout.WEST);
        top.add(stateBadge, BorderLayout.EAST);

        hintLabel = new JLabel("真实产品没有这个窗口 —— 它常驻托盘。这里只是用来触发各种场景。");
        hintLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11));
        hintLabel.setForeground(C_DIM);

        p.add(top, BorderLayout.NORTH);
        p.add(hintLabel, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildBody() {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(C_BG);
        body.setBorder(new EmptyBorder(0, 18, 0, 18));

        body.add(sectionTitle("模拟事件"));
        body.add(buildEventButtons());

        body.add(sectionTitle("配置"));
        body.add(buildConfigPanel());

        body.add(sectionTitle("模拟的「其它程序光标处」"));
        body.add(buildTargetPane());

        body.add(Box.createVerticalStrut(6));
        return body;
    }

    private JLabel sectionTitle(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 12));
        l.setForeground(C_ACCENT);
        l.setBorder(new EmptyBorder(12, 0, 6, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JComponent buildEventButtons() {
        JPanel wrap = new JPanel(new GridLayout(0, 3, 8, 8));
        wrap.setBackground(C_BG);
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);

        wrap.add(button("① 说「子曰」", e -> simulateWake()));
        wrap.add(button("② 说「到此为止」", e -> simulateEndWord()));
        wrap.add(button("③ 静音超时", e -> simulateSilence()));
        wrap.add(button("模拟：切窗口", e -> simulateWindowChange()));
        wrap.add(button("模拟：按 Esc", e -> simulateCancel()));
        wrap.add(button("重播整段流程", e -> replay()));
        return wrap;
    }

    private JButton button(String text, java.awt.event.ActionListener l) {
        JButton b = new FlatButton(text);
        b.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        b.setFocusable(false);
        b.addActionListener(l);
        return b;
    }

    private JComponent buildConfigPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(C_PANEL);
        p.setBorder(new EmptyBorder(10, 12, 10, 12));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 8);
        c.anchor = GridBagConstraints.WEST;

        wakeField = styledField("子曰", 6);
        endField = styledField("到此为止", 7);
        silenceField = styledField("5", 3);
        autoSendBox = new JCheckBox("自动发送");
        autoSendBox.setOpaque(false);
        autoSendBox.setForeground(C_TEXT);
        autoSendBox.setFont(font(12));
        sendKeyBox = new JComboBox<>(new String[]{"Enter", "Ctrl+Enter"});
        sendKeyBox.setFont(font(12));

        c.gridx = 0; c.gridy = 0; p.add(label("唤醒词"), c);
        c.gridx = 1;             p.add(wakeField, c);
        c.gridx = 2;             p.add(label("结束词"), c);
        c.gridx = 3;             p.add(endField, c);
        c.gridx = 4;             p.add(checkMark(), c);

        c.gridx = 0; c.gridy = 1; p.add(label("静音兜底"), c);
        c.gridx = 1;             p.add(silenceField, c);
        c.gridx = 2;             p.add(label("秒"), c);
        c.gridx = 3;             p.add(autoSendBox, c);
        c.gridx = 4;             p.add(sendKeyBox, c);

        return p;
    }

    /** 演示词表校验的提示：真实产品里这个词必须通过模型词表校验。 */
    private JLabel checkMark() {
        JLabel l = new JLabel("✓ 词表校验通过");
        l.setFont(font(11));
        l.setForeground(C_OK);
        return l;
    }

    private JComponent buildTargetPane() {
        targetArea = new JTextArea(4, 40);
        targetArea.setLineWrap(true);
        targetArea.setWrapStyleWord(true);
        targetArea.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 15));
        targetArea.setBackground(new Color(250, 250, 252));
        targetArea.setForeground(new Color(30, 32, 40));
        targetArea.setBorder(new EmptyBorder(8, 10, 8, 10));

        JScrollPane sp = new JScrollPane(targetArea);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sp.setPreferredSize(new Dimension(700, 130));
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        sp.setBorder(BorderFactory.createLineBorder(new Color(70, 76, 96)));
        return sp;
    }

    private JComponent buildLogPane() {
        logArea = new JTextArea(8, 40);
        logArea.setEditable(false);
        logArea.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11));
        logArea.setBackground(new Color(18, 20, 27));
        logArea.setForeground(new Color(150, 200, 170));
        logArea.setBorder(new EmptyBorder(6, 10, 6, 10));

        JScrollPane sp = new JScrollPane(logArea);
        sp.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 66, 84)));
        sp.setPreferredSize(new Dimension(760, 150));
        return sp;
    }

    private JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(font(12));
        l.setForeground(C_DIM);
        return l;
    }

    private Font font(int size) {
        return new Font("Microsoft YaHei UI", Font.PLAIN, size);
    }
    private JTextField styledField(String text, int cols) {
        JTextField f = new JTextField(text, cols);
        f.setFont(font(12));
        f.setBackground(new Color(24, 27, 36));
        f.setForeground(C_TEXT);
        f.setCaretColor(C_TEXT);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(72, 79, 100)),
                new EmptyBorder(4, 6, 4, 6)));
        return f;
    }

    /** 扁平深色按钮 —— 系统默认外观在深色面板上过于突兀。 */
    static class FlatButton extends JButton {
        FlatButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setForeground(new Color(226, 231, 243));
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

    // ==================== 浮窗 ====================

    private void buildFloatBar() {
        bar = new FloatBar(console);
        bar.setAnchor(targetArea);
    }

    /**
     * 不抢焦点的浮窗预览条。
     *
     * <p>关键是 {@code setFocusableWindowState(false)} 与
     * {@code setAutoRequestFocus(false)} —— 缺了任何一条，浮窗弹出时都会
     * 抢走前台窗口的焦点，导致文字注不进去。
     */
    static class FloatBar extends JWindow {

        private final JLabel statusLabel = new JLabel();
        private final JLabel textLabel = new JLabel();
        private JComponent anchor;

        FloatBar(JFrame owner) {
            super(owner);
            setFocusableWindowState(false);   // 不参与焦点
            setAutoRequestFocus(false);       // 显示时不请求焦点
            setAlwaysOnTop(true);

            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice();
            if (gd.isWindowTranslucencySupported(
                    GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
                setBackground(new Color(0, 0, 0, 0));
            }

            JPanel root = new JPanel(new BorderLayout(0, 5)) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(22, 25, 34, 242));
                    g2.fill(new RoundRectangle2D.Float(
                            0, 0, getWidth() - 1, getHeight() - 1, 18, 18));
                    g2.setColor(new Color(96, 108, 140, 130));
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(new RoundRectangle2D.Float(
                            0.5f, 0.5f, getWidth() - 2, getHeight() - 2, 18, 18));
                    g2.dispose();
                }
            };
            root.setOpaque(false);
            root.setBorder(new EmptyBorder(10, 15, 13, 15));

            statusLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11));
            statusLabel.setForeground(new Color(132, 140, 168));
            textLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 15));

            root.add(statusLabel, BorderLayout.NORTH);
            root.add(textLabel, BorderLayout.CENTER);
            setContentPane(root);
        }

        void setAnchor(JComponent anchor) {
            this.anchor = anchor;
        }

        /**
         * @param stable  引擎已稳定的前缀（正常色）
         * @param pending 仍在变动、可能被改写（弱化色）
         * @param status  状态行
         */
        void render(String stable, String pending, String status) {
            statusLabel.setText(status);
            boolean hasStable = stable != null && !stable.isEmpty();
            boolean hasPending = pending != null && !pending.isEmpty();

            StringBuilder html = new StringBuilder();
            html.append("<html><body style='width:340px'>");
            if (hasStable) {
                html.append("<span style='color:#eef1f8'>")
                        .append(esc(stable)).append("</span>");
            }
            if (hasPending) {
                html.append("<span style='color:#717890'>")
                        .append(esc(pending)).append("</span>");
            }
            if (!hasStable && !hasPending) {
                html.append("<span style='color:#717890'>（等待语音…）</span>");
            }
            html.append("</body></html>");
            textLabel.setText(html.toString());

            pack();
            if (getWidth() < 380) {
                setSize(380, getHeight());
            }
            reposition();
            if (!isVisible()) {
                setVisible(true);
            }
        }

        private void reposition() {
            if (anchor == null || !anchor.isShowing()) {
                setLocationRelativeTo(null);
                return;
            }
            Point p = anchor.getLocationOnScreen();
            Rectangle screen = getGraphicsConfiguration().getBounds();

            int x = p.x + 24;
            int y = p.y + anchor.getHeight() + 10;
            if (x + getWidth() > screen.x + screen.width) {
                x = screen.x + screen.width - getWidth() - 16;
            }
            if (y + getHeight() > screen.y + screen.height) {
                y = p.y - getHeight() - 10;
            }
            setLocation(x, y);
        }

        void hideBar() {
            setVisible(false);
        }
    }

    // ==================== 托盘 ====================

    private void buildTray() {
        if (!SystemTray.isSupported()) {
            log("WARN", "系统托盘不可用，跳过");
            return;
        }
        PopupMenu menu = new PopupMenu();

        pauseItem = new MenuItem("暂停监听");
        pauseItem.addActionListener(e -> togglePause());
        menu.add(pauseItem);
        menu.addSeparator();

        MenuItem cfg = new MenuItem("打开配置文件（演示）");
        cfg.addActionListener(e -> log("INFO", "打开配置文件 %LOCALAPPDATA%\\TalkingLive\\config.json"));
        menu.add(cfg);

        MenuItem lg = new MenuItem("查看日志（演示）");
        lg.addActionListener(e -> log("INFO", "打开日志 %LOCALAPPDATA%\\TalkingLive\\logs\\talkinglive.log"));
        menu.add(lg);
        menu.addSeparator();

        MenuItem quit = new MenuItem("退出");
        quit.addActionListener(e -> {
            SystemTray.getSystemTray().remove(trayIcon);
            System.exit(0);
        });
        menu.add(quit);

        trayIcon = new TrayIcon(stateIcon(C_DIM, true), "TalkingLive · 待唤醒", menu);
        trayIcon.setImageAutoSize(true);
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException ex) {
            log("WARN", "托盘图标添加失败: " + ex.getMessage());
        }
    }

    private void togglePause() {
        paused = !paused;
        pauseItem.setLabel(paused ? "恢复监听" : "暂停监听");
        log("INFO", paused ? "监听已暂停（音频采集仍在，但忽略一切唤醒词）"
                : "监听已恢复");
        setStateVisual(sm.state());
    }

    private void updateTray(StateMachine.State s) {
        if (trayIcon == null) {
            return;
        }
        if (paused) {
            trayIcon.setImage(stateIcon(C_WARN, true));
            trayIcon.setToolTip("TalkingLive · 已暂停");
            return;
        }
        switch (s) {
            case IDLE:
                trayIcon.setImage(stateIcon(C_DIM, true));
                trayIcon.setToolTip("TalkingLive · 待唤醒");
                break;
            case LISTENING:
                trayIcon.setImage(stateIcon(C_ERR, false));
                trayIcon.setToolTip("TalkingLive · 听写中");
                break;
            default:
                trayIcon.setImage(stateIcon(C_WARN, false));
                trayIcon.setToolTip("TalkingLive · 提交中");
                break;
        }
    }

    private static Image stateIcon(Color color, boolean ring) {
        int s = 16;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        if (ring) {
            g.setStroke(new BasicStroke(2.2f));
            g.drawOval(3, 3, s - 7, s - 7);
        } else {
            g.fillOval(1, 1, s - 3, s - 3);
        }
        g.dispose();
        return img;
    }

    // ==================== 状态机接线 ====================

    private void wireStateMachine() {
        sm.onTransition((from, to) -> {
            log("INFO", "[state] " + from + " -> " + to);
            setStateVisual(to);
            updateTray(to);
            if (to == StateMachine.State.COMMITTING) {
                onCommit();
            } else if (to == StateMachine.State.IDLE
                    && from == StateMachine.State.LISTENING) {
                // Esc 取消：整段丢弃，一个字都没注入
                stopTimers();
                bar.hideBar();
                log("INFO", "[kws] 已取消，本段丢弃，一个字都没有注入");
            }
        });
    }

    private void setStateVisual(StateMachine.State s) {
        if (stateBadge == null) {
            return;
        }
        if (paused) {
            stateBadge.setText("  已暂停  ");
            stateBadge.setBackground(C_WARN);
            stateBadge.setForeground(new Color(30, 30, 30));
            return;
        }
        stateBadge.setText("  " + sm.label() + "  ");
        switch (s) {
            case IDLE:
                stateBadge.setBackground(new Color(70, 76, 96));
                stateBadge.setForeground(C_TEXT);
                break;
            case LISTENING:
                stateBadge.setBackground(C_ERR);
                stateBadge.setForeground(Color.WHITE);
                break;
            default:
                stateBadge.setBackground(C_WARN);
                stateBadge.setForeground(new Color(30, 30, 30));
                break;
        }
        updateTray(s);
    }

    // ==================== 模拟事件 ====================

    private void simulateWake() {
        if (paused) {
            log("WARN", "[kws] 已暂停监听，忽略唤醒词");
            return;
        }
        if (sm.state() != StateMachine.State.IDLE) {
            log("WARN", "[kws] 当前不在待唤醒状态，忽略");
            return;
        }
        windowChanged = false;
        scenario = scenario % PREVIEW_SCRIPTS.length;
        step = 0;
        lastPartial = "";

        log("INFO", "[kws] 唤醒词命中「" + wakeField.getText() + "」");
        sm.fire(StateMachine.Event.WAKE);
        log("INFO", "[state] 目标窗口锁定 0x001A2B3C \"记事本（模拟）\"");
        log("INFO", "[asr] 开始流式识别（小 Vosk，无标点）");

        bar.render("", "", "聆听中 · 小 Vosk 流式（无标点、会改写）");
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

        // 前缀比后缀稳定：这里用最长公共前缀演示"已稳定 / 仍在变"的分界
        // （方案 B 里整段都算待确认，这是方案 C 的视觉预览）
        String stable = lcp(lastPartial, current);
        String pending = current.substring(stable.length());
        lastPartial = current;

        bar.render(stable, pending, "聆听中 · 小 Vosk 流式（无标点、会改写）");
        log("INFO", "[asr] 中间结果 #" + step + " len=" + current.length() + "（内容不记录）");
    }

    private void startSilenceCountdown() {
        final int total = parseSilenceSeconds();
        if (total <= 0) {
            bar.render(lastPartial, "", "已关闭静音兜底 · 请说结束词");
            log("INFO", "[state] 静音兜底已关闭，等待结束词");
            return;
        }
        silenceLeft = total;
        renderSilence(total);
        silenceTimer = new Timer(1000, e -> {
            silenceLeft--;
            if (silenceLeft <= 0) {
                stopTimers();
                log("INFO", "[state] 静音兜底触发 持续=" + total + ".0s");
                sm.fire(StateMachine.Event.SILENCE);
            } else {
                renderSilence(total);
            }
        });
        silenceTimer.start();
    }

    private void renderSilence(int total) {
        bar.render(lastPartial, "",
                "等待结束词「" + endField.getText() + "」· 静音兜底 " + silenceLeft + "s");
    }

    private void simulateEndWord() {
        if (sm.state() != StateMachine.State.LISTENING) {
            log("WARN", "[kws] 当前不在听写中，忽略结束词");
            return;
        }
        stopTimers();
        bar.render(lastPartial, "",
                "听到结束词「" + endField.getText() + "」");
        log("INFO", "[kws] 结束词命中「" + endField.getText() + "」");
        sm.fire(StateMachine.Event.END_WORD);
    }

    private void simulateSilence() {
        if (sm.state() != StateMachine.State.LISTENING) {
            log("WARN", "[state] 当前不在听写中，忽略静音事件");
            return;
        }
        stopTimers();
        log("INFO", "[state] 静音兜底触发（手动模拟）");
        sm.fire(StateMachine.Event.SILENCE);
    }

    private void simulateWindowChange() {
        if (sm.state() != StateMachine.State.LISTENING) {
            log("WARN", "[win] 当前不在听写中，忽略窗口变化");
            return;
        }
        windowChanged = true;
        stopTimers();
        log("INFO", "[win] 前台窗口变化 0x001A2B3C -> 0x002F1E4A");
        sm.fire(StateMachine.Event.WINDOW_CHANGE);
    }

    private void simulateCancel() {
        if (sm.state() == StateMachine.State.IDLE) {
            log("WARN", "[state] 当前无进行中的听写");
            return;
        }
        stopTimers();
        log("INFO", "[input] 用户按下 Esc");
        sm.fire(StateMachine.Event.CANCEL);
    }

    private void replay() {
        stopTimers();
        bar.hideBar();
        scenario = (scenario + 1) % PREVIEW_SCRIPTS.length;
        while (sm.state() != StateMachine.State.IDLE) {
            sm.fire(StateMachine.Event.CANCEL);
        }
        targetArea.setText("");
        log("INFO", "---- 重播：切换到场景 " + (scenario + 1) + " ----");
        setStateVisual(sm.state());

        Timer delay = new Timer(500, e -> {
            ((Timer) e.getSource()).stop();
            simulateWake();
        });
        delay.setRepeats(false);
        delay.start();
    }

    // ==================== 提交 ====================

    private void onCommit() {
        stopTimers();
        final String preview = lastPartial;
        bar.render(preview, "", "处理中 · whisper 正在精化（约 1.5s）");
        log("INFO", "[refiner] whisper 启动（重跑本段音频）");

        workTimer = new Timer(1500, e -> {
            ((Timer) e.getSource()).stop();
            String finalText = FINAL_TEXTS[scenario % FINAL_TEXTS.length];
            log("INFO", "[refiner] whisper 完成 len=" + finalText.length() + "（内容不记录）");
            bar.render(finalText, "", "即将注入");
            log("INFO", "[inject] 校验前台窗口 0x001A2B3C ...");

            final Timer t2 = new Timer(700, e2 -> {
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
            log("WARN", "[inject] 注入放弃 原因=前台窗口已变更 期望=0x001A2B3C 实际=0x002F1E4A");
            notifyUser("刚才那段没有注入成功",
                    "提交时前台窗口已经变了。\n为避免把文字误发到别的程序，已放弃注入。");
            sm.fire(StateMachine.Event.COMMITTED);
            return;
        }

        targetArea.append(text);
        targetArea.setCaretPosition(targetArea.getDocument().getLength());
        log("INFO", "[inject] 注入成功 chars=" + text.length() + " target=0x001A2B3C");

        if (autoSendBox.isSelected()) {
            String key = (String) sendKeyBox.getSelectedItem();
            targetArea.append("Enter".equals(key) ? "\n" : "\n[Ctrl+Enter]\n");
            log("INFO", "[send] 自动发送 key=" + key);
        }
        sm.fire(StateMachine.Event.COMMITTED);
    }

    private void notifyUser(String title, String body) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, body, TrayIcon.MessageType.WARNING);
        }
        JOptionPane.showMessageDialog(console, body, title, JOptionPane.WARNING_MESSAGE);
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

    private int parseSilenceSeconds() {
        try {
            return Integer.parseInt(silenceField.getText().trim());
        } catch (NumberFormatException e) {
            return 5;
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

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void log(String level, String message) {
        logArea.append(LocalTime.now().format(TS) + " " + pad(level) + " " + message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private static String pad(String level) {
        StringBuilder b = new StringBuilder(level);
        while (b.length() < 5) {
            b.append(' ');
        }
        return b.toString();
    }
}
