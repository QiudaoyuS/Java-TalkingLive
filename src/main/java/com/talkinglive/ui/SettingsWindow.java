package com.talkinglive.ui;

import com.talkinglive.core.AppConfig;
import com.talkinglive.core.InMemoryLogAppender;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 设置窗口 —— 从悬浮球（或托盘）右键菜单进入，**按需打开，关掉不等于退出**（§4.4）。
 *
 * <p>布局原则（§4.4「设置界面必须简洁清晰」，用户实测反馈驱动）：
 * <ul>
 *   <li><b>一行一个设置</b>，左侧固定宽度的标签列、右侧控件列。旧版把标签、输入框、
 *       说明文字塞进同一个 GridBagLayout 的 4 列里，于是同一行的输入框宽度随窗口变化、
 *       说明文字被挤到屏幕外，看起来就是「不清楚」。</li>
 *   <li><b>说明文字在控件下方</b>，用固定宽度的 HTML 换行。旧版说明在同一行右侧，
 *       宽度不受控，窗口一窄就被截断。</li>
 *   <li><b>同一语义只有一个图标尺寸</b>。旧版用「✓」「✗」字符当图标，字符尺寸由字体
 *       决定、矢量图标由像素决定，两者放一起必然一大一小。现在全部走 {@link Icons}，
 *       分组标题统一 {@link Icons#MEDIUM}、正文统一 {@link Icons#SMALL}。</li>
 *   <li><b>不常用的参数收进「高级」</b>。注入间隔、单段上限这类调参项默认折叠，
 *       让常规页只剩用户每天会看的东西。</li>
 * </ul>
 *
 * <p>四个页签：
 * <ul>
 *   <li><b>常规</b>：日常会改的配置，与 {@code DESIGN.md} 附录 A 一一对应。</li>
 *   <li><b>日志</b>：状态流转与错误，**不记转写内容**。</li>
 *   <li><b>模型状态</b>：Vosk / 精化引擎 / 麦克风 / 注入器是否可用，以及**词表校验提示**。
 *       这一页是 §4.4「词表校验提示放在设置窗口，否则用户会陷入『改了没反应』」
 *       的落点——它比什么都重要，因为 Vosk 对词表外的词是静默忽略的（附录 C）。</li>
 *   <li><b>自检</b>：把结构化自检结果贴出来，便于交付前核对。</li>
 * </ul>
 */
public class SettingsWindow extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(SettingsWindow.class);

    public static final int TAB_GENERAL = 0;
    public static final int TAB_LOG = 1;
    public static final int TAB_STATUS = 2;
    public static final int TAB_DIAGNOSTICS = 3;

    /** 标签列宽度。固定值让所有行的控件左边缘对齐——这是「简洁」的骨架。 */
    private static final int LABEL_COLUMN = 92;

    /** 说明文字换行宽度。按窗口内容宽度留出的常量，避免说明被截断。 */
    private static final int HINT_WIDTH = 470;

    /** 状态行里值列的换行宽度。 */
    private static final int STATUS_VALUE_WIDTH = 380;

    /** 文字类配置项（唤醒词等）的落盘延迟：等用户打完这个字，避免逐字写盘并弹错误。 */
    private static final int TEXT_COMMIT_DELAY_MS = 450;

    /**
     * 设置窗口与 App 之间的契约。
     *
     * <p>刻意做成接口而不是直接持有 App：设置窗口需要的东西很少，
     * 且「保存配置」这件事必须由 App 校验后再落盘（§4.3 配置必须显式校验）。
     */
    public interface Host {
        AppConfig config();

        /**
         * 校验并保存配置。
         *
         * @return null 表示成功；否则是面向用户的失败原因（会显示在窗口里）
         */
        String applyConfig(AppConfig candidate);

        /** 各引擎/设备的状态行，用于「模型状态」页。 */
        List<StatusLine> status();

        /** 结构化自检结果，用于「自检」页。 */
        String diagnosticsReport();

        /** 保存成功后的通知（用于刷新悬浮球等）。 */
        default void onConfigApplied(AppConfig applied) {}

        /** 窗口显示/隐藏时通知（用于暂停前台窗口监听）。 */
        default void onVisibilityChanged(boolean visible) {}
    }

    /** 一行状态：名称 / 值 / 是否正常 / 说明。 */
    public record StatusLine(String name, String value, boolean ok, String detail) {}

    private final Host host;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextArea logArea = new JTextArea();
    private final JLabel errorLabel = new JLabel(" ");
    private final JPanel statusPanel = new JPanel();
    private final JTextArea diagnosticsArea = new JTextArea();

    /** 词表校验徽标：直接贴在对应输入框旁边，不再单独排一段。 */
    private final JLabel wakeMark = new JLabel();
    private final JLabel endMark = new JLabel();
    private final JLabel stopMark = new JLabel();

    private JTextField wakeField;
    private JTextField endField;
    private JTextField stopWordField;
    private JTextField silenceField;
    private JTextField maxSegmentField;
    private JTextField gapField;
    private JCheckBox autoSendBox;
    private JCheckBox sendOnSilenceBox;
    private JCheckBox itnBox;
    private JCheckBox continuousBox;
    private JComboBox<String> sendKeyBox;
    private JPanel advancedBody;
    private JButton advancedToggle;
    private AutoCloseable logSubscription;

    public SettingsWindow(Host host) {
        super("TalkingLive 设置");
        this.host = host;

        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);  // 关窗 != 退出
        getContentPane().setBackground(Theme.BG);
        // 窗口图标与托盘同源，否则任务栏上会看到两个不同的产品形象
        setIconImage(Icons.image(Icons.Kind.MIC, 64, Theme.ballRing("IDLE", false)));

        tabs.setFont(Theme.font(12));
        tabs.setBackground(Theme.BG);
        tabs.setForeground(Theme.TEXT);
        tabs.addTab("常规", Icons.of(Icons.Kind.GEAR, Icons.SMALL), buildGeneral());
        tabs.addTab("日志", Icons.of(Icons.Kind.DOCUMENT, Icons.SMALL), buildLog());
        tabs.addTab("模型状态", Icons.of(Icons.Kind.WAVE, Icons.SMALL), buildStatus());
        tabs.addTab("自检", Icons.of(Icons.Kind.STETHOSCOPE, Icons.SMALL), buildDiagnostics());

        add(tabs, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        setSize(780, 660);
        setMinimumSize(new Dimension(660, 520));
        setLocationRelativeTo(null);
        refreshStatus();
        reloadFromConfig();
    }

    /** 打开并切到指定页签。 */
    public void showTab(int index) {
        if (index >= 0 && index < tabs.getTabCount()) {
            tabs.setSelectedIndex(index);
        }
        setVisible(true);
        toFront();
        host.onVisibilityChanged(true);
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        host.onVisibilityChanged(visible);
        if (visible) {
            startLogSubscription();
            refreshStatus();
            reloadFromConfig();
        }
    }

    // ==================== 常规 ====================

    private JComponent buildGeneral() {
        JPanel body = column();

        AppConfig cfg = host.config();

        wakeField = field(cfg.wakeWord(), 10);
        endField = field(cfg.endWord(), 10);
        stopWordField = field(cfg.stopWord(), 8);
        silenceField = field(String.valueOf(cfg.silenceSeconds()), 5);
        maxSegmentField = field(String.valueOf(cfg.maxSegmentSeconds()), 5);
        gapField = field(String.valueOf(cfg.charGapMillis()), 5);

        // ---- 语音指令：整个产品只有用户开口这一条控制通道（§2.2「无按键」），
        //      所以这三个词是**第一等重要**的设置，放在最上面。
        JPanel commands = card("语音指令", Icons.Kind.MIC,
                "说出唤醒词开始听写，说出结束词结束本段。词必须存在于 Vosk 词表内，"
                        + "否则引擎会静默忽略它（表现为「说了没反应」）。");
        addRow(commands, "唤醒词", wakeField, wakeMark,
                "开始听写。建议 2–3 个字（例：子曰）。");
        addRow(commands, "结束词", endField, endMark,
                "结束本段录音并落字。建议 2–3 个字（例：到此为止）。");
        body.add(commands);
        body.add(strut(10));

        bindText(wakeField, v -> {
            cfg.setWakeWord(v);
            commit(cfg);
        });
        bindText(endField, v -> {
            cfg.setEndWord(v);
            commit(cfg);
        });

        // ---- 听写
        JPanel dictation = card("听写", Icons.Kind.WAVE,
                "控制一段录音在什么时候自己结束。连续输入模式下，静音是「分段依据」"
                        + "而不是「兜底」——说完一段停顿一下就会立刻落字。");
        continuousBox = check("连续输入模式", cfg.continuousMode(), v -> {
            cfg.setContinuousMode(v);
            commit(cfg);
        });
        addRow(dictation, "连续输入", continuousBox, null,
                "唤醒一次后可以一段接一段地说，不必每句都喊唤醒词。"
                        + "开启后静音阈值自动放宽到 3 秒，且必须说退出词才会完全结束。");

        addRow(dictation, "退出词", stopWordField, stopMark,
                "连续输入模式下说它结束整个会话（回到待唤醒）。"
                        + "留空则关闭语音退出，只能靠悬浮球暂停。");
        bindText(stopWordField, v -> {
            cfg.setStopWord(v);
            commit(cfg);
        });

        bindNumber(silenceField, v -> cfg.setSilenceSeconds(v),
                AppConfig.MIN_SILENCE_SECONDS, AppConfig.MAX_SILENCE_SECONDS);
        addRow(dictation, "静音超时", silenceField, null,
                "秒内没有语音就自动结束本段（"
                        + AppConfig.MIN_SILENCE_SECONDS + "–" + AppConfig.MAX_SILENCE_SECONDS
                        + "，0 = 关闭自动结束）。");
        body.add(dictation);
        body.add(strut(10));

        // ---- 发送
        JPanel sending = card("发送", Icons.Kind.CHECK,
                "决定落字之后要不要替你按发送键。默认只落字不发送，"
                        + "这样在聊天软件里可以先看一眼再手动发送。");
        autoSendBox = check("自动发送", cfg.autoSend(), v -> {
            cfg.setAutoSend(v);
            commit(cfg);
        });
        sendKeyBox = new JComboBox<>(new String[] {
            AppConfig.SendKey.ENTER.display(), AppConfig.SendKey.CTRL_ENTER.display()});
        sendKeyBox.setFont(Theme.font(12));
        sendKeyBox.setBackground(Theme.FIELD);
        sendKeyBox.setForeground(Theme.TEXT);
        sendKeyBox.setSelectedItem(cfg.sendKey().display());
        sendKeyBox.addActionListener(e -> {
            cfg.setSendKey(AppConfig.SendKey.fromDisplay((String) sendKeyBox.getSelectedItem()));
            commit(cfg);
        });
        addRow(sending, "发送方式", autoSendBox, sendKeyBox,
                "发送键按目标软件选：微信/QQ/记事本用 Enter，"
                        + "聊天软件里想换行不发送时选 Ctrl+Enter。");

        sendOnSilenceBox = check("静音超时后也发送", cfg.sendOnSilenceTimeout(), v -> {
            cfg.setSendOnSilenceTimeout(v);
            commit(cfg);
        });
        addRow(sending, "", sendOnSilenceBox, null,
                "勾上后，因为「静音超时」而结束的段落也会按发送键；"
                        + "说结束词结束的段落总是会发送。");
        body.add(sending);
        body.add(strut(10));

        // ---- 高级：默认折叠。这里的参数改坏了会直接表现为「丢字」，
        //      所以既不能藏起来让用户无从下手，也不能摆在第一屏吓人。
        advancedBody = column();
        advancedBody.setVisible(false);
        bindNumber(maxSegmentField, v -> cfg.setMaxSegmentSeconds(v), 5, 600);
        addRow(advancedBody, "单段上限", maxSegmentField, null,
                "秒后强制结束本段（5–600），防止长时间不说话导致内存增长。");
        bindNumber(gapField, v -> cfg.setCharGapMillis(v),
                AppConfig.MIN_CHAR_GAP_MILLIS, AppConfig.MAX_CHAR_GAP_MILLIS);
        addRow(advancedBody, "注入间隔", gapField, null,
                "每字之间的毫秒数（" + AppConfig.MIN_CHAR_GAP_MILLIS + "–"
                        + AppConfig.MAX_CHAR_GAP_MILLIS + "）。微信、QQ 这类自绘输入框"
                        + "灌太快会吞字，调到 20–40 即可；记事本、浏览器用 0 最快。");

        itnBox = check("数字规整（ITN）", cfg.itn(), v -> {
            cfg.setItn(v);
            commit(cfg);
        });
        addRow(advancedBody, "精化引擎", itnBox, null,
                "把「二零二五年」写成「2025年」。会改字，因此可关。");

        JPanel advanced = column();
        advanced.setBackground(Theme.BG);
        advanced.setBorder(new EmptyBorder(0, 0, 0, 0));
        advancedToggle = button("▸  高级参数", e -> toggleAdvanced());
        advancedToggle.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        advanced.add(advancedToggle);
        advanced.add(strut(8));
        advanced.add(advancedBody);
        body.add(advanced);

        body.add(strut(14));
        errorLabel.setFont(Theme.font(12));
        errorLabel.setForeground(Theme.ERR);
        errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(errorLabel);

        body.add(strut(6));
        JLabel path = hint("改动立即写入 " + com.talkinglive.core.AppPaths.configFile());
        path.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(path);

        return scroll(body);
    }

    private void toggleAdvanced() {
        boolean show = !advancedBody.isVisible();
        advancedBody.setVisible(show);
        advancedToggle.setText((show ? "▾  " : "▸  ") + "高级参数");
        revalidate();
        repaint();
    }

    /**
     * 一次配置改动：校验 → 保存 → 刷新提示。
     *
     * <p>失败时只显示原因，**不破坏当前生效值**（§4.3）——所以这里显示的是
     * 「你刚输入的还不生效，原因是什么」，而不是把输入回滚掉。
     */
    private void commit(AppConfig candidate) {
        String err = host.applyConfig(candidate);
        if (err == null) {
            errorLabel.setText(" ");
            refreshStatus();
        } else {
            errorLabel.setText("<html><body style='width:" + HINT_WIDTH
                    + "px'>⚠ " + esc(err).replace("\n", "<br>") + "</body></html>");
            log.info("配置改动被拒绝：{}", err.replace("\n", " / "));
        }
        refreshWordCheck();
    }

    private void reloadFromConfig() {
        AppConfig cfg = host.config();
        setIfIdle(wakeField, cfg.wakeWord());
        setIfIdle(endField, cfg.endWord());
        setIfIdle(stopWordField, cfg.stopWord());
        setIfIdle(silenceField, String.valueOf(cfg.silenceSeconds()));
        setIfIdle(maxSegmentField, String.valueOf(cfg.maxSegmentSeconds()));
        setIfIdle(gapField, String.valueOf(cfg.charGapMillis()));
        if (autoSendBox != null) {
            autoSendBox.setSelected(cfg.autoSend());
        }
        if (sendOnSilenceBox != null) {
            sendOnSilenceBox.setSelected(cfg.sendOnSilenceTimeout());
        }
        if (itnBox != null) {
            itnBox.setSelected(cfg.itn());
        }
        if (continuousBox != null) {
            continuousBox.setSelected(cfg.continuousMode());
        }
        if (sendKeyBox != null) {
            sendKeyBox.setSelectedItem(cfg.sendKey().display());
        }
        refreshWordCheck();
    }

    /** 只在不打断用户输入时回填（否则用户打到一半会被覆盖）。 */
    private void setIfIdle(JTextField f, String value) {
        if (f != null && !f.getText().equals(value)) {
            f.setText(value);
        }
    }

    // ==================== 词表校验 ====================

    /** 词表校验提示。真实结果来自引擎（附录 C），不是写死的 ✓。 */
    private void refreshWordCheck() {
        applyWordMark(wakeMark, "唤醒词",
                wakeField == null ? host.config().wakeWord() : wakeField.getText().trim());
        applyWordMark(endMark, "结束词",
                endField == null ? host.config().endWord() : endField.getText().trim());
        applyWordMark(stopMark, "退出词",
                stopWordField == null ? host.config().stopWord() : stopWordField.getText().trim());
    }

    private void applyWordMark(JLabel label, String field, String word) {
        if (label == null) {
            return;
        }
        if (word == null || word.isBlank()) {
            label.setIcon(null);
            label.setText("可留空");
            label.setForeground(Theme.DIM);
            label.setToolTipText(null);
            return;
        }
        StatusLine line = host.status().stream()
                .filter(s -> s.name().equals("词表:" + field))
                .findFirst()
                .orElse(null);
        if (line == null) {
            label.setIcon(Icons.of(Icons.Kind.WARN, Icons.SMALL));
            label.setText("引擎未就绪");
            label.setForeground(Theme.WARN);
            label.setToolTipText("引擎未就绪，无法校验「" + word + "」是否在词表内");
            return;
        }
        // 徽标文字必须短：这一段和输入框同处一行，长了会把说明文字挤到看不见。
        // 完整结论（wordId、为什么必须校验）放在 tooltip 与「模型状态」页里。
        boolean ok = line.ok();
        label.setIcon(Icons.of(ok ? Icons.Kind.CHECK : Icons.Kind.CROSS, Icons.SMALL));
        label.setText(ok ? "在词表内" : "不在词表内");
        label.setForeground(ok ? Theme.OK : Theme.ERR);
        label.setToolTipText(line.detail() == null ? null
                : (ok ? "「" + word + "」" : "「" + word + "」") + "：" + line.detail());
    }

    // ==================== 日志 ====================

    private JComponent buildLog() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBackground(Theme.BG);
        p.setBorder(new EmptyBorder(14, 16, 14, 16));

        logArea.setEditable(false);
        // 必须含中文字形：Consolas 之类没有中文字形，中文会变成乱码方块
        logArea.setFont(Theme.font(11));
        logArea.setBackground(new Color(18, 20, 27));
        logArea.setForeground(new Color(150, 200, 170));
        logArea.setBorder(new EmptyBorder(8, 10, 8, 10));

        JScrollPane sp = new JScrollPane(logArea);
        sp.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        p.add(sp, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        bottom.setBackground(Theme.BG);
        JLabel note = hint("<html><body style='width:520px'>只记状态流转与错误，"
                + "<b>不记转写内容</b>。滚动 5MB × 3：<br>"
                + com.talkinglive.core.AppPaths.logDir() + "</body></html>");
        bottom.add(note, BorderLayout.CENTER);
        bottom.add(button("清空显示", e -> {
            InMemoryLogAppender.clear();
            logArea.setText("");
        }), BorderLayout.EAST);
        p.add(bottom, BorderLayout.SOUTH);
        return p;
    }

    private void startLogSubscription() {
        if (logSubscription != null) {
            return;
        }
        logArea.setText(String.join("\n", InMemoryLogAppender.lines()));
        if (!logArea.getText().isEmpty()) {
            logArea.append("\n");
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
        try {
            logSubscription = InMemoryLogAppender.subscribe(line ->
                    SwingUtilities.invokeLater(() -> {
                        logArea.append(line + "\n");
                        logArea.setCaretPosition(logArea.getDocument().getLength());
                    }));
        } catch (RuntimeException e) {
            log.warn("订阅内存日志失败：{}", e.toString());
        }
    }

    // ==================== 模型状态 ====================

    private JComponent buildStatus() {
        statusPanel.setLayout(new BoxLayout(statusPanel, BoxLayout.Y_AXIS));
        statusPanel.setBackground(Theme.BG);
        statusPanel.setBorder(new EmptyBorder(16, 18, 16, 18));

        return scroll(statusPanel);
    }

    /** 刷新「模型状态」页。每次打开窗口都会调。 */
    public void refreshStatus() {
        if (statusPanel == null) {
            return;
        }
        statusPanel.removeAll();

        List<StatusLine> lines = host.status();
        long bad = lines.stream().filter(l -> !l.ok()).count();

        JLabel headline = new JLabel(bad == 0
                ? "全部就绪（" + lines.size() + " 项）"
                : bad + " 项异常 / 共 " + lines.size() + " 项");
        // 顶部一句话结论：用户打开这一页只想知道「有没有问题、哪里有问题」
        headline.setIcon(Icons.of(bad == 0 ? Icons.Kind.CHECK : Icons.Kind.WARN, Icons.MEDIUM));
        headline.setFont(Theme.bold(14));
        headline.setForeground(bad == 0 ? Theme.OK : Theme.WARN);
        headline.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusPanel.add(headline);
        statusPanel.add(Box.createVerticalStrut(12));

        // 异常项排前面 —— 这一页的价值全在「哪里有问题」
        lines.stream().filter(l -> !l.ok()).forEach(l -> {
            statusPanel.add(statusRow(l));
            statusPanel.add(Box.createVerticalStrut(6));
        });
        lines.stream().filter(StatusLine::ok).forEach(l -> {
            statusPanel.add(statusRow(l));
            statusPanel.add(Box.createVerticalStrut(6));
        });

        statusPanel.add(Box.createVerticalStrut(10));
        statusPanel.add(statusNote());
        refreshWordCheck();
        statusPanel.revalidate();
        statusPanel.repaint();
    }

    private JComponent statusNote() {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel icon = new JLabel(Icons.of(Icons.Kind.WARN, Icons.MEDIUM));
        p.add(icon, BorderLayout.WEST);
        JLabel note = hint("<html><body style='width:" + (HINT_WIDTH + 40) + "px'>"
                + "Vosk 对词表外的词是<b>静默忽略</b>的：语法能建起来、不报错，"
                + "但那个词永远不会被识别到，表现就是「改了配置没反应」。"
                + "所以唤醒词/结束词/退出词在常规页显示的是<b>硬校验结论</b>，不是普通提示。"
                + "</body></html>");
        p.add(note, BorderLayout.CENTER);
        return p;
    }

    /**
     * 模型状态页的一行：图标 + 名称 + 值/说明。
     *
     * <p>用 GridBagLayout 而不是 BorderLayout：BorderLayout 会把名称那一格
     * 拉满剩余宽度，于是「值」被挤到最右边、名字和值之间空出一大片，
     * 看起来像两列不相关的信息。这里用「名称定宽 148 + 值占剩余」，
     * 值与说明紧贴名称，读起来是一句话。
     */
    private JComponent statusRow(StatusLine line) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        // BoxLayout 下不给最大宽度，行只会按 preferred 宽度画，右边留出一条空白；
        // 而且 GridBagLayout 里 weightx=1 的那一列也就分不到额外宽度。
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.NORTHWEST;

        c.gridx = 0;
        c.gridy = 0;
        c.insets = new Insets(1, 0, 0, 10);
        // 图标与首行文字的视觉中线对齐；顶对齐会让图标显得偏高
        c.anchor = GridBagConstraints.NORTHWEST;
        p.add(new JLabel(Icons.of(line.ok() ? Icons.Kind.CHECK : Icons.Kind.CROSS,
                Icons.MEDIUM)), c);

        c.gridx = 1;
        c.insets = new Insets(0, 0, 0, 12);
        JLabel name = new JLabel(line.name());
        name.setFont(Theme.bold(12));
        name.setForeground(Theme.TEXT);
        name.setPreferredSize(new Dimension(148, 20));
        p.add(name, c);

        c.gridx = 2;
        c.insets = new Insets(0, 0, 0, 0);
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        JLabel value = new JLabel("<html><body style='width:" + STATUS_VALUE_WIDTH + "px'>"
                + esc(line.value())
                + (line.detail() == null || line.detail().isBlank()
                        ? "" : "<br><span style='color:#8a90a6'>" + esc(line.detail()) + "</span>")
                + "</body></html>");
        value.setFont(Theme.font(11));
        value.setForeground(Theme.TEXT_MUTED);
        p.add(value, c);
        return p;
    }

    // ==================== 自检 ====================

    private JComponent buildDiagnostics() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBackground(Theme.BG);
        p.setBorder(new EmptyBorder(14, 16, 14, 16));

        diagnosticsArea.setEditable(false);
        diagnosticsArea.setFont(Theme.font(11));
        diagnosticsArea.setBackground(new Color(18, 20, 27));
        diagnosticsArea.setForeground(new Color(200, 210, 230));
        diagnosticsArea.setBorder(new EmptyBorder(8, 10, 8, 10));
        p.add(new JScrollPane(diagnosticsArea), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        bottom.setBackground(Theme.BG);
        bottom.add(hint("把这段结果贴给开发者即可定位环境问题（不含转写内容）"),
                BorderLayout.CENTER);
        bottom.add(button("重新自检", e -> diagnosticsArea.setText(host.diagnosticsReport())),
                BorderLayout.EAST);
        p.add(bottom, BorderLayout.SOUTH);
        return p;
    }

    /** 供 App 在窗口可见时填充自检结果。 */
    public void refreshDiagnostics() {
        diagnosticsArea.setText(host.diagnosticsReport());
    }

    // ==================== 零件 ====================

    private JComponent buildFooter() {
        JPanel p = new JPanel(new BorderLayout(12, 0));
        p.setBackground(Theme.BG);
        p.setBorder(new EmptyBorder(10, 16, 12, 16));

        p.add(hint("关掉这个窗口不会退出程序 —— 它仍驻留在悬浮球上。"), BorderLayout.CENTER);
        p.add(button("关闭", e -> setVisible(false)), BorderLayout.EAST);
        return p;
    }

    /** 一列内容，左对齐。所有卡片都放进这样的列里。 */
    private JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Theme.BG);
        p.setBorder(new EmptyBorder(16, 18, 18, 18));
        return p;
    }

    /**
     * 一张分组卡片：标题 + 一行说明 + 若干设置行。
     *
     * <p>卡片是「简洁」的关键——有边框和标题，用户扫一眼就知道哪些设置是一类的，
     * 不需要读每一行的文字去猜分组。
     */
    private JPanel card(String title, Icons.Kind icon, String description) {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(Theme.PANEL);
        outer.setAlignmentX(Component.LEFT_ALIGNMENT);
        outer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                new EmptyBorder(14, 16, 14, 16)));
        // 卡片横向撑满，纵向按内容 —— BoxLayout 下不设上限会纵向也撑满
        outer.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setOpaque(false);

        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel iconLabel = new JLabel(Icons.of(icon, Icons.MEDIUM));
        head.add(iconLabel, BorderLayout.WEST);
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(Theme.bold(14));
        titleLabel.setForeground(Theme.TEXT);
        head.add(titleLabel, BorderLayout.CENTER);
        inner.add(head);

        if (description != null && !description.isBlank()) {
            inner.add(Box.createVerticalStrut(4));
            JLabel desc = hint("<html><body style='width:" + HINT_WIDTH + "px'>"
                    + esc(description) + "</body></html>");
            desc.setAlignmentX(Component.LEFT_ALIGNMENT);
            inner.add(desc);
        }
        inner.add(Box.createVerticalStrut(10));

        JPanel rows = new JPanel(new GridBagLayout());
        rows.setOpaque(false);
        rows.setAlignmentX(Component.LEFT_ALIGNMENT);
        inner.add(rows);
        // 把「设置行容器」挂到卡片上，供 addRow 找到
        outer.putClientProperty("rows", rows);
        outer.add(inner, BorderLayout.CENTER);
        return outer;
    }

    /**
     * 往卡片里加一行设置。
     *
     * @param label  左侧标签，空串表示这行没有标签（承接上一行的复选说明）
     * @param main   主控件（输入框 / 复选框）
     * @param extra  同行右侧的附加控件（可为 null）
     * @param hint   控件下方的说明，可为 null
     */
    private void addRow(JPanel cardPanel, String label, JComponent main, JComponent extra,
            String hint) {
        JPanel rows = (JPanel) cardPanel.getClientProperty("rows");
        if (rows == null) {
            // 高级参数区是裸列，没有卡片壳
            rows = cardPanel;
        }
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;

        boolean hasHint = hint != null && !hint.isBlank();

        // GridBagLayout 不支持「追加一行」，所以自己记下一个可用行号。
        // 不能用 componentCount 推：一行会放 2–3 个组件，算出来是错的。
        Object last = rows.getClientProperty("nextRow");
        int y = last instanceof Integer i ? i : 0;

        String mainLabel = label == null ? "" : label;

        if (!mainLabel.isEmpty()) {
            c.gridx = 0;
            c.gridy = y;
            c.insets = new Insets(7, 0, 7, 12);
            // 有说明文字时标签顶对齐：控件那一格因为下面挂了说明会变高、被垂直居中，
            // 标签再居中就会看起来「浮在中间」。顶对齐后两者永远在同一条基线上。
            c.anchor = hasHint ? GridBagConstraints.NORTHWEST : GridBagConstraints.WEST;
            JLabel l = new JLabel("<html><body style='width:" + (LABEL_COLUMN - 6) + "px'>"
                    + esc(mainLabel) + "</body></html>");
            l.setFont(Theme.bold(12));
            l.setForeground(Theme.TEXT_MUTED);
            l.setPreferredSize(new Dimension(LABEL_COLUMN, 20));
            l.setVerticalAlignment(JLabel.TOP);
            rows.add(l, c);
        }
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 1;
        c.gridy = y;
        c.insets = new Insets(7, 0, 7, 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        rows.add(main, c);

        // 徽标/附加控件固定在自己那一格，不被拉伸
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        if (extra != null || hasMark(label)) {
            c.gridx = 2;
            c.gridy = y;
            c.insets = new Insets(7, 0, 7, 0);
            rows.add(extra != null ? extra : markFor(label), c);
        }

        if (hint != null && !hint.isBlank()) {            GridBagConstraints h = new GridBagConstraints();
            h.gridx = 1;
            h.gridy = y + 1;
            h.gridwidth = 2;
            h.anchor = GridBagConstraints.WEST;
            h.fill = GridBagConstraints.HORIZONTAL;
            h.weightx = 1;
            h.insets = new Insets(0, 0, 7, 0);
            rows.add(hintLabel(hint), h);
        }
        rows.putClientProperty("nextRow", y + (hint != null && !hint.isBlank() ? 2 : 1));
    }

    /** 哪些标签对应一个词表校验徽标。 */
    private boolean hasMark(String label) {
        return "唤醒词".equals(label) || "结束词".equals(label) || "退出词".equals(label);
    }

    private JLabel markFor(String label) {
        return switch (label) {
            case "唤醒词" -> wakeMark;
            case "结束词" -> endMark;
            case "退出词" -> stopMark;
            default -> new JLabel();
        };
    }

    /** 说明文字：固定宽度换行，避免窗口一窄就被截断。 */
    private JLabel hintLabel(String text) {
        JLabel l = new JLabel("<html><body style='width:" + HINT_WIDTH + "px'>"
                + esc(text) + "</body></html>");
        l.setFont(Theme.font(11));
        l.setForeground(Theme.DIM);
        return l;
    }

    private JLabel hint(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(11));
        l.setForeground(Theme.DIM);
        return l;
    }

    private JComponent strut(int h) {
        return (JComponent) Box.createVerticalStrut(h);
    }

    private JComponent scroll(JComponent content) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(Theme.BG);
        holder.add(content, BorderLayout.NORTH);

        JScrollPane sp = new JScrollPane(holder);
        sp.setBorder(null);
        sp.getViewport().setBackground(Theme.BG);
        sp.setBackground(Theme.BG);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private JTextField field(String value, int cols) {
        JTextField f = new JTextField(value, cols);
        f.setFont(Theme.font(12));
        f.setBackground(Theme.FIELD);
        f.setForeground(Theme.TEXT);
        f.setCaretColor(Theme.TEXT);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                new EmptyBorder(5, 8, 5, 8)));
        // 固定高度让所有输入框一样高：GridBagLayout 下不同字体度量的控件会差 1–2px
        f.setPreferredSize(new Dimension(f.getPreferredSize().width, 30));
        return f;
    }

    private JCheckBox check(String text, boolean selected, Consumer<Boolean> setter) {
        JCheckBox b = new JCheckBox(text);
        b.setOpaque(false);
        b.setForeground(Theme.TEXT);
        b.setFont(Theme.font(12));
        b.setSelected(selected);
        b.setFocusPainted(false);
        b.addItemListener(e -> setter.accept(b.isSelected()));
        return b;
    }

    private JButton button(String text, java.awt.event.ActionListener action) {
        JButton b = new JButton(text);
        b.setFont(Theme.font(12));
        b.setBackground(Theme.PANEL);
        b.setForeground(Theme.TEXT);
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                new EmptyBorder(6, 14, 6, 14)));
        b.addActionListener(action);
        return b;
    }

    // ------------------------------------------------------------ 绑定

    /**
     * 文字类配置的绑定：**防抖后**再落盘。
     *
     * <p>旧版逐字落盘：用户把「子曰」改成「小助手」的过程中会先落盘「子」，
     * 这是一个合法的唤醒词（能通过校验），但紧接着落盘「小」时就可能因为
     * 与结束词冲突被拒绝并弹错误 —— 用户看到的是「我还没打完它就报错」。
     * 450ms 的静默期之后才提交，就不会有这种中间态。
     */
    private void bindText(JTextField field, Consumer<String> setter) {
        Timer timer = new Timer(TEXT_COMMIT_DELAY_MS, null);
        timer.setRepeats(false);
        timer.addActionListener(e -> setter.accept(field.getText().trim()));
        field.getDocument().addDocumentListener(new DocumentListener() {
            private void restart() {
                timer.restart();
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                restart();
            }
        });
    }

    /** 数字类配置的绑定：只在能解析出整数时提交，且立刻提交（用户是在调参，看的就是即时效果）。 */
    private void bindNumber(JTextField field, Consumer<Integer> setter, int min, int max) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            private void push() {
                String t = field.getText().trim();
                if (t.isEmpty()) {
                    return;
                }
                try {
                    int v = Integer.parseInt(t);
                    if (v < min || v > max) {
                        return;   // 越界不提交：让用户把数字打完，别在中途弹错误
                    }
                    markRange(field, true);
                    setter.accept(v);
                } catch (NumberFormatException ignored) {
                    markRange(field, false);
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                push();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                push();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                push();
            }
        });
    }

    private void markRange(JTextField field, boolean ok) {
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ok ? Theme.BORDER : Theme.ERR),
                new EmptyBorder(5, 8, 5, 8)));
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
