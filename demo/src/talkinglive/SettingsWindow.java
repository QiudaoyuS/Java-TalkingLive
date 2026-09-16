package talkinglive;

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
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.function.Consumer;

/**
 * 设置窗口 —— 从悬浮球右键菜单进入。
 *
 * <p>真实产品里它**按需打开**，关掉不等于退出程序。
 * 三个页签：常规（配置）/ 日志 / 演示（仅原型有）。
 */
class SettingsWindow extends JFrame {

    static final int TAB_GENERAL = 0;
    static final int TAB_LOG = 1;
    static final int TAB_DEMO = 2;

    private final Demo demo;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextArea logArea = new JTextArea();
    private final JLabel checkWake = new JLabel();
    private final JLabel checkEnd = new JLabel();

    SettingsWindow(Demo demo) {
        super("TalkingLive 设置");
        this.demo = demo;

        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);  // 关窗 != 退出
        getContentPane().setBackground(Theme.BG);

        tabs.setFont(Theme.font(12));
        tabs.addTab("常规", buildGeneral());
        tabs.addTab("日志", buildLog());
        tabs.addTab("演示", buildDemoTab());

        add(tabs, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        setSize(680, 580);
        setLocationRelativeTo(null);
        refreshWordCheck();
    }

    /** 打开并切到指定页签。 */
    void showTab(int index) {
        tabs.setSelectedIndex(index);
        setVisible(true);
        toFront();
    }

    void appendLog(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /** 词表校验提示的刷新（真实产品里这里是硬校验）。 */
    void refreshWordCheck() {
        checkWake.setText("✓  唤醒词「" + demo.cfgWakeWord + "」在模型词表内");
        checkEnd.setText("✓  结束词「" + demo.cfgEndWord + "」在模型词表内");
        checkWake.setForeground(Theme.OK);
        checkEnd.setForeground(Theme.OK);
    }

    // ==================== 常规 ====================

    private JComponent buildGeneral() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(Theme.BG);
        p.setBorder(new EmptyBorder(18, 20, 18, 20));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 4, 6, 10);
        c.anchor = GridBagConstraints.WEST;

        JTextField wake = field(demo.cfgWakeWord, 8);
        JTextField end = field(demo.cfgEndWord, 10);
        JTextField silence = field(String.valueOf(demo.cfgSilenceSeconds), 4);

        bindText(wake, v -> {
            demo.cfgWakeWord = v;
            refreshWordCheck();
        });
        bindText(end, v -> {
            demo.cfgEndWord = v;
            refreshWordCheck();
        });
        bindText(silence, v -> {
            try {
                demo.cfgSilenceSeconds = Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
                // 输入尚未完成，保持原值
            }
        });

        JCheckBox autoSend = new JCheckBox("自动发送");
        autoSend.setOpaque(false);
        autoSend.setForeground(Theme.TEXT);
        autoSend.setFont(Theme.font(12));
        autoSend.setSelected(demo.cfgAutoSend);
        autoSend.addItemListener(e -> demo.cfgAutoSend = autoSend.isSelected());

        JComboBox<String> key = new JComboBox<>(new String[]{"Enter", "Ctrl+Enter"});
        key.setFont(Theme.font(12));
        key.setSelectedItem(demo.cfgSendKey);
        key.addActionListener(e -> demo.cfgSendKey = (String) key.getSelectedItem());

        int row = 0;
        c.gridx = 0; c.gridy = row; p.add(label("唤醒词"), c);
        c.gridx = 1;                p.add(wake, c);
        c.gridx = 2;                p.add(label("结束词"), c);
        c.gridx = 3;                p.add(end, c);

        row++;
        c.gridx = 0; c.gridy = row; p.add(label("静音兜底"), c);
        c.gridx = 1;                p.add(silence, c);
        c.gridx = 2; c.gridwidth = 2; p.add(label("秒（0 = 关闭静音触发）"), c);
        c.gridwidth = 1;

        row++;
        c.gridx = 0; c.gridy = row; p.add(label("发送方式"), c);
        c.gridx = 1;                p.add(autoSend, c);
        c.gridx = 2;                p.add(key, c);

        row++;
        c.gridx = 0; c.gridy = row; c.gridwidth = 4;
        c.insets = new Insets(20, 4, 2, 10);
        p.add(checkWake, c);

        row++;
        c.gridy = row;
        c.insets = new Insets(2, 4, 14, 10);
        p.add(checkEnd, c);

        row++;
        c.gridy = row;
        c.insets = new Insets(2, 4, 2, 10);
        p.add(hint("改动立即生效（原型行为；真实产品写入 JSON 配置）"), c);

        checkWake.setFont(Theme.font(12));
        checkEnd.setFont(Theme.font(12));

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(Theme.BG);
        wrap.add(p, BorderLayout.NORTH);
        return wrap;
    }

    // ==================== 日志 ====================

    private JComponent buildLog() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBackground(Theme.BG);
        p.setBorder(new EmptyBorder(14, 16, 14, 16));

        logArea.setEditable(false);
        logArea.setFont(Theme.font(11));   // 必须含中文字形
        logArea.setBackground(new Color(18, 20, 27));
        logArea.setForeground(new Color(150, 200, 170));
        logArea.setBorder(new EmptyBorder(8, 10, 8, 10));

        JScrollPane sp = new JScrollPane(logArea);
        sp.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        p.add(sp, BorderLayout.CENTER);

        JLabel note = new JLabel(
                "只记状态流转与错误，不记转写内容；滚动 5MB × 3，"
                        + "位置 %LOCALAPPDATA%\\TalkingLive\\logs\\");
        note.setFont(Theme.font(11));
        note.setForeground(Theme.DIM);
        p.add(note, BorderLayout.SOUTH);
        return p;
    }

    // ==================== 演示（仅原型） ====================

    private JComponent buildDemoTab() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Theme.BG);
        p.setBorder(new EmptyBorder(16, 18, 16, 18));

        JLabel warn = new JLabel(
                "以下按钮仅交互原型有 —— 真实产品靠真实语音触发，界面上没有这些按钮。");
        warn.setFont(Theme.font(11));
        warn.setForeground(Theme.WARN);
        warn.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(warn);
        p.add(Box.createVerticalStrut(14));

        p.add(grid(
                demo.button("① 说「子曰」", e -> demo.simulateWake()),
                demo.button("② 说「到此为止」", e -> demo.simulateEndWord()),
                demo.button("③ 静音超时", e -> demo.simulateSilence())));
        p.add(Box.createVerticalStrut(8));
        p.add(grid(
                demo.button("模拟：切窗口", e -> demo.simulateWindowChange()),
                demo.button("模拟：按 Esc", e -> demo.simulateCancel()),
                demo.button("重播整段流程", e -> demo.replay())));
        p.add(Box.createVerticalStrut(20));

        JLabel title = new JLabel("模拟的「其它程序光标处」");
        title.setFont(Theme.bold(12));
        title.setForeground(Theme.ACCENT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(title);
        p.add(Box.createVerticalStrut(6));

        JScrollPane sp = new JScrollPane(demo.targetArea());
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sp.setPreferredSize(new Dimension(580, 150));
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
        sp.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        p.add(sp);

        return p;
    }

    private JComponent grid(JComponent... items) {
        JPanel g = new JPanel(new GridLayout(1, items.length, 8, 8));
        g.setOpaque(false);
        g.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (JComponent i : items) {
            g.add(i);
        }
        g.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        return g;
    }

    // ==================== 零件 ====================

    private JComponent buildFooter() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Theme.BG);
        p.setBorder(new EmptyBorder(0, 16, 12, 16));

        JLabel tip = new JLabel("关掉这个窗口不会退出程序 —— 它仍驻留在悬浮球上。");
        tip.setFont(Theme.font(11));
        tip.setForeground(Theme.DIM);
        p.add(tip, BorderLayout.WEST);
        p.add(demo.button("关闭", e -> setVisible(false)), BorderLayout.EAST);
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
        l.setForeground(Theme.DIM);
        return l;
    }

    private JLabel hint(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(11));
        l.setForeground(Theme.DIM);
        return l;
    }

    private void bindText(JTextField field, Consumer<String> setter) {
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
}
