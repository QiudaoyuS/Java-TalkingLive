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
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 设置窗口 —— 从悬浮球（或托盘）右键菜单进入，**按需打开，关掉不等于退出**（§4.4）。
 *
 * <p>四个页签：
 * <ul>
 *   <li><b>常规</b>：配置项，与 {@code DESIGN.md} 附录 A 一一对应。</li>
 *   <li><b>日志</b>：状态流转与错误，**不记转写内容**。</li>
 *   <li><b>模型状态</b>：Vosk / 精化引擎 / 麦克风 / 注入器是否可用，以及**词表校验提示**。
 *       这一页是 §4.4「词表校验提示放在设置窗口的常规页，否则用户会陷入『改了没反应』」
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
    private final JLabel wakeCheck = new JLabel();
    private final JLabel endCheck = new JLabel();
    private final JPanel statusPanel = new JPanel();
    private final JTextArea diagnosticsArea = new JTextArea();

    private JTextField wakeField;
    private JTextField endField;
    private JTextField silenceField;
    private JTextField maxSegmentField;
    private JTextField gapField;
    private JCheckBox autoSendBox;
    private JCheckBox sendOnSilenceBox;
    private JCheckBox itnBox;
    private JCheckBox continuousBox;
    private JTextField stopWordField;
    private JComboBox<String> sendKeyBox;
    private AutoCloseable logSubscription;

    public SettingsWindow(Host host) {
        super("TalkingLive 设置");
        this.host = host;

        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);  // 关窗 != 退出
        getContentPane().setBackground(Theme.BG);

        tabs.setFont(Theme.font(12));
        tabs.addTab("常规", buildGeneral());
        tabs.addTab("日志", buildLog());
        tabs.addTab("模型状态", buildStatus());
        tabs.addTab("自检", buildDiagnostics());

        add(tabs, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        setSize(720, 620);
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
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(Theme.BG);
        p.setBorder(new EmptyBorder(18, 20, 18, 20));

        AppConfig cfg = host.config();

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 4, 6, 10);
        c.anchor = GridBagConstraints.WEST;

        wakeField = field(cfg.wakeWord(), 8);
        endField = field(cfg.endWord(), 10);
        silenceField = field(String.valueOf(cfg.silenceSeconds()), 4);
        maxSegmentField = field(String.valueOf(cfg.maxSegmentSeconds()), 4);

        // 改动即时校验 + 落盘；失败时把原因显示出来，且**不改动生效中的配置**。
        bind(wakeField, v -> {
            cfg.setWakeWord(v);
            commit(cfg);
        });
        bind(endField, v -> {
            cfg.setEndWord(v);
            commit(cfg);
        });
        bind(silenceField, v -> {
            try {
                cfg.setSilenceSeconds(Integer.parseInt(v.trim()));
                commit(cfg);
            } catch (NumberFormatException ignored) {
                // 输入尚未完成，等下一个字符
            }
        });
        bind(maxSegmentField, v -> {
            try {
                cfg.setMaxSegmentSeconds(Integer.parseInt(v.trim()));
                commit(cfg);
            } catch (NumberFormatException ignored) {
                // 同上
            }
        });

        autoSendBox = check("自动发送", cfg.autoSend(), v -> {
            cfg.setAutoSend(v);
            commit(cfg);
        });
        sendOnSilenceBox = check("静音超时后也发送", cfg.sendOnSilenceTimeout(), v -> {
            cfg.setSendOnSilenceTimeout(v);
            commit(cfg);
        });
        itnBox = check("精化引擎数字规整（ITN）", cfg.itn(), v -> {
            cfg.setItn(v);
            commit(cfg);
        });

        sendKeyBox = new JComboBox<>(new String[] {
            AppConfig.SendKey.ENTER.display(), AppConfig.SendKey.CTRL_ENTER.display()});
        sendKeyBox.setFont(Theme.font(12));
        sendKeyBox.setSelectedItem(cfg.sendKey().display());
        sendKeyBox.addActionListener(e -> {
            cfg.setSendKey(AppConfig.SendKey.fromDisplay((String) sendKeyBox.getSelectedItem()));
            commit(cfg);
        });

        int row = 0;
        c.gridx = 0; c.gridy = row; p.add(label("唤醒词"), c);
        c.gridx = 1;                p.add(wakeField, c);
        c.gridx = 2;                p.add(label("结束词"), c);
        c.gridx = 3;                p.add(endField, c);

        row++;
        c.gridx = 0; c.gridy = row; p.add(label("静音"), c);
        c.gridx = 1;                p.add(silenceField, c);
        c.gridx = 2; c.gridwidth = 2; p.add(hint("秒后结束录制（0–15，0 = 关闭）"), c);
        c.gridwidth = 1;

        row++;
        c.gridx = 0; c.gridy = row; p.add(label("单段上限"), c);
        c.gridx = 1;                p.add(maxSegmentField, c);
        c.gridx = 2; c.gridwidth = 2; p.add(hint("秒后自动结束本段（防止长录音内存增长）"), c);
        c.gridwidth = 1;

        row++;
        c.gridx = 0; c.gridy = row; p.add(label("发送方式"), c);
        c.gridx = 1;                p.add(autoSendBox, c);
        c.gridx = 2;                p.add(sendKeyBox, c);

        row++;
        c.gridx = 0; c.gridy = row; c.gridwidth = 4; p.add(sendOnSilenceBox, c);
        c.gridwidth = 1;

        row++;
        c.gridx = 0; c.gridy = row; c.gridwidth = 4; p.add(itnBox, c);
        c.gridwidth = 1;

        // 连续输入模式：唤醒一次后持续落字。默认关闭，保持旧契约不变。
        // 放在这里而不是藏进 JSON，因为它是「说长内容」的主要可用性开关。
        row++;
        c.gridx = 0; c.gridy = row; c.gridwidth = 4;
        continuousBox = check("连续输入模式：唤醒一次，连续落字（说退出词才结束）",
                cfg.continuousMode(), v -> {
                    cfg.setContinuousMode(v);
                    commit(cfg);
                });
        p.add(continuousBox, c);
        c.gridwidth = 1;

        row++;
        c.gridx = 0; c.gridy = row; p.add(label("退出词"), c);
        c.gridx = 1;
        stopWordField = field(cfg.stopWord(), 6);
        p.add(stopWordField, c);
        c.gridx = 2; c.gridwidth = 2;
        p.add(hint("说它结束整个会话（需在词表内；留空则不启用语音退出）"), c);
        c.gridwidth = 1;
        bind(stopWordField, v -> {
            cfg.setStopWord(v);
            commit(cfg);
        });

        // 字符注入间隔：微信/QQ 这类自绘输入框灌太快会丢字，需要放慢。
        // 这是实测出来的可调项（见 WindowsTextInjector.setCharGapMillis），
        // 必须让用户能调——不然「说了十个字只出来两个字」就只能靠改代码解决。
        row++;
        c.gridx = 0; c.gridy = row; p.add(label("注入间隔"), c);
        c.gridx = 1;
        gapField = field(String.valueOf(cfg.charGapMillis()), 4);
        p.add(gapField, c);
        c.gridx = 2; c.gridwidth = 2;
        p.add(hint("毫秒/每字（0–200）。微信/QQ 丢字时调大，记事本用 0 即可"), c);
        c.gridwidth = 1;
        bind(gapField, v -> {
            try {
                cfg.setCharGapMillis(Integer.parseInt(v.trim()));
                commit(cfg);
            } catch (NumberFormatException ignored) {
                // 输入尚未完成
            }
        });

        row++;
        c.gridx = 0; c.gridy = row; c.gridwidth = 4; c.insets = new Insets(16, 4, 2, 10);
        p.add(label("词表校验（Vosk 对词表外的词静默忽略，必须在此拦住）"), c);

        row++;
        c.gridy = row; c.insets = new Insets(2, 4, 2, 10);
        wakeCheck.setFont(Theme.font(12));
        p.add(wakeCheck, c);

        row++;
        c.gridy = row;
        endCheck.setFont(Theme.font(12));
        p.add(endCheck, c);

        row++;
        c.gridy = row; c.insets = new Insets(10, 4, 2, 10);
        errorLabel.setFont(Theme.font(11));
        p.add(errorLabel, c);

        row++;
        c.gridy = row; c.insets = new Insets(6, 4, 2, 10);
        p.add(hint("改动立即写入 " + com.talkinglive.core.AppPaths.configFile()), c);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(Theme.BG);
        wrap.add(p, BorderLayout.NORTH);
        return wrap;
    }

    /** 提交一次配置改动：校验 → 保存 → 刷新提示。失败时只显示原因，不破坏当前生效值。 */
    private void commit(AppConfig candidate) {
        String err = host.applyConfig(candidate);
        if (err == null) {
            errorLabel.setText(" ");
            errorLabel.setForeground(Theme.OK);
            refreshStatus();
        } else {
            errorLabel.setText("<html><body style='width:560px'>⚠ " + esc(err).replace("\n", "<br>")
                    + "</body></html>");
            errorLabel.setForeground(Theme.ERR);
            log.info("配置改动被拒绝：{}", err.replace("\n", " / "));
        }
        refreshWordCheck();
    }

    private void reloadFromConfig() {
        AppConfig cfg = host.config();
        if (wakeField != null && !wakeField.getText().equals(cfg.wakeWord())) {
            wakeField.setText(cfg.wakeWord());
        }
        if (endField != null && !endField.getText().equals(cfg.endWord())) {
            endField.setText(cfg.endWord());
        }
        refreshWordCheck();
    }

    /** 词表校验提示。真实结果来自引擎（附录 C），不是写死的 ✓。 */
    private void refreshWordCheck() {
        String wake = wakeField == null ? host.config().wakeWord() : wakeField.getText().trim();
        String end = endField == null ? host.config().endWord() : endField.getText().trim();
        applyWordCheck(wakeCheck, "唤醒词", wake);
        applyWordCheck(endCheck, "结束词", end);
    }

    private void applyWordCheck(JLabel label, String field, String word) {
        StatusLine line = host.status().stream()
                .filter(s -> s.name().equals("词表:" + field))
                .findFirst()
                .orElse(null);
        if (line == null) {
            label.setText("·  " + field + "「" + word + "」（引擎不可用，无法校验）");
            label.setForeground(Theme.WARN);
            return;
        }
        label.setText((line.ok() ? "✓  " : "✗  ") + field + "「" + word + "」"
                + (line.detail() == null ? "" : " " + line.detail()));
        label.setForeground(line.ok() ? Theme.OK : Theme.ERR);
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

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(Theme.BG);
        JLabel note = new JLabel("只记状态流转与错误，不记转写内容；滚动 5MB × 3："
                + com.talkinglive.core.AppPaths.logDir());
        note.setFont(Theme.font(11));
        note.setForeground(Theme.DIM);
        bottom.add(note, BorderLayout.WEST);
        JButton clear = button("清空显示", e -> {
            InMemoryLogAppender.clear();
            logArea.setText("");
        });
        bottom.add(clear, BorderLayout.EAST);
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

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(Theme.BG);
        wrap.add(statusPanel, BorderLayout.NORTH);
        return wrap;
    }

    /** 刷新「模型状态」页。每次打开窗口都会调。 */
    public void refreshStatus() {
        if (statusPanel == null) {
            return;
        }
        statusPanel.removeAll();
        JLabel title = new JLabel("当前可用性");
        title.setFont(Theme.bold(12));
        title.setForeground(Theme.ACCENT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusPanel.add(title);
        statusPanel.add(Box.createVerticalStrut(10));

        for (StatusLine line : host.status()) {
            statusPanel.add(linePanel(line));
            statusPanel.add(Box.createVerticalStrut(6));
        }
        statusPanel.add(Box.createVerticalStrut(10));
        JLabel note = new JLabel("<html><body style='width:600px'>"
                + "Vosk 对词表外的词是<b>静默忽略</b>的：语法能建起来、不报错，"
                + "但那个词永远不会被识别到，表现就是「改了配置没反应」。"
                + "所以本页的词表校验是硬校验，不是提示。"
                + "</body></html>");
        note.setFont(Theme.font(11));
        note.setForeground(Theme.TEXT_MUTED);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusPanel.add(note);

        refreshWordCheck();
        statusPanel.revalidate();
        statusPanel.repaint();
    }

    private JComponent linePanel(StatusLine line) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));

        JLabel mark = new JLabel(line.ok() ? "✓" : "✗");
        mark.setFont(Theme.bold(13));
        mark.setForeground(line.ok() ? Theme.OK : Theme.ERR);
        mark.setPreferredSize(new Dimension(18, 18));
        p.add(mark, BorderLayout.WEST);

        JLabel name = new JLabel(line.name());
        name.setFont(Theme.font(12));
        name.setForeground(Theme.TEXT);
        name.setPreferredSize(new Dimension(150, 18));
        p.add(name, BorderLayout.CENTER);

        JLabel value = new JLabel("<html><body style='width:380px'>" + esc(line.value())
                + (line.detail() == null || line.detail().isBlank() ? "" : "<br><i>" + esc(line.detail()) + "</i>")
                + "</body></html>");
        value.setFont(Theme.font(11));
        value.setForeground(Theme.TEXT_MUTED);
        p.add(value, BorderLayout.EAST);
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

        JButton rerun = button("重新自检", e -> diagnosticsArea.setText(host.diagnosticsReport()));
        p.add(rerun, BorderLayout.SOUTH);
        return p;
    }

    /** 供 App 在窗口可见时填充自检结果。 */
    public void refreshDiagnostics() {
        diagnosticsArea.setText(host.diagnosticsReport());
    }

    // ==================== 零件 ====================

    private JComponent buildFooter() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Theme.BG);
        p.setBorder(new EmptyBorder(0, 16, 12, 16));

        JLabel tip = new JLabel("关掉这个窗口不会退出程序 —— 它仍驻留在悬浮球上。");
        tip.setFont(Theme.font(11));
        tip.setForeground(Theme.TEXT_MUTED);
        p.add(tip, BorderLayout.WEST);
        p.add(button("关闭", e -> setVisible(false)), BorderLayout.EAST);
        return p;
    }

    private JTextField field(String value, int cols) {
        JTextField f = new JTextField(value, cols);
        f.setFont(Theme.font(12));
        f.setBackground(Theme.FIELD);
        f.setForeground(Theme.TEXT);
        f.setCaretColor(Theme.TEXT);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                new EmptyBorder(4, 6, 4, 6)));
        return f;
    }

    private JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(12));
        l.setForeground(Theme.TEXT_MUTED);
        return l;
    }

    private JLabel hint(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(11));
        l.setForeground(Theme.DIM);
        return l;
    }

    private JCheckBox check(String text, boolean selected, Consumer<Boolean> setter) {
        JCheckBox b = new JCheckBox(text);
        b.setOpaque(false);
        b.setForeground(Theme.TEXT);
        b.setFont(Theme.font(12));
        b.setSelected(selected);
        b.addItemListener(e -> setter.accept(b.isSelected()));
        return b;
    }

    private JButton button(String text, java.awt.event.ActionListener action) {
        JButton b = new JButton(text);
        b.setFont(Theme.font(12));
        b.addActionListener(action);
        return b;
    }

    private void bind(JTextField field, Consumer<String> setter) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            private void push() {
                setter.accept(field.getText());
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

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
