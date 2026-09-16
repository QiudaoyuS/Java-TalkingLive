package com.talkinglive.ui;

import com.talkinglive.core.AppConfig;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
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
 * <p><b>设计原则：只放每天会改的东西。</b>用户第 4 次反馈「设置界面太繁琐」后，
 * 这个窗口被彻底重写为**一屏五行**：
 *
 * <pre>
 *   唤醒词    [子曰      ]   ✓
 *   结束词    [到此为止  ]   ✓
 *   静音超时  [5 秒    ▾]
 *   自动发送  [ ] 说完后替我按发送键
 *   发送键    [Enter   ▾]        （仅在自动发送打开时可用）
 * </pre>
 *
 * <p>被拿掉的东西与它们的去向（每一件都不是「删掉」而是「搬家」）：
 * <ul>
 *   <li>日志 / 模型状态 / 自检三个页签 → {@link DiagnosticsWindow}。它们是**诊断**，
 *       不是设置；混在设置里既让设置变复杂，也让「想看日志」的人要在设置里翻。</li>
 *   <li>卡片说明、每行提示、页脚注解 → 全部删除。那些文字是开发期写给自己的解释，
 *       不是用户读懂这个界面所必需的；只有真正会踩坑的地方保留一句 tooltip。</li>
 *   <li>单段上限、注入间隔、数字规整 → 移出界面。它们是**一次性调参**，
 *       改法写在配置文件里，界面不再为它们增加复杂度。</li>
 * </ul>
 *
 * <p>保留的两处「多余」信息都经过权衡：<b>词表校验标记</b>（唤醒词/结束词后面的
 * ✓/✗ 与红字）—— Vosk 对词表外的词是**静默忽略**的（附录 C），不提示的话用户会陷入
 * 「改了没反应」；这是宁可多一行也不能省的一处。<b>配置文件路径</b> —— 一行小字，
 * 让上面那些被移出界面的参数仍然找得到。
 */
public class SettingsWindow extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(SettingsWindow.class);

    /** 标签列宽度。固定值让所有行的控件左边缘对齐。 */
    private static final int LABEL_COLUMN = 76;

    /** 输入类配置的落盘延迟：等用户打完再提交，避免逐字写盘并弹出中间态错误。 */
    private static final int TEXT_COMMIT_DELAY_MS = 450;

    /** 静音超时的可选项（秒）。0 = 关闭自动结束。 */
    private static final int[] SILENCE_CHOICES = {0, 2, 3, 5, 8, 10};

    /**
     * 设置窗口与 App 之间的契约。
     *
     * <p>刻意做成接口而不是直接持有 App：「保存配置」必须由 App 校验后再落盘（§4.3）。
     */
    public interface Host {
        AppConfig config();

        /**
         * 校验并保存配置。
         *
         * @return null 表示成功；否则是面向用户的失败原因
         */
        String applyConfig(AppConfig candidate);

        /** 某个词是否在当前模型的词表内；模型不可用时返回 null（无法校验）。 */
        Boolean wordInVocabulary(String word);

        /** 窗口显示/隐藏时通知（用于暂停前台窗口监听）。 */
        default void onVisibilityChanged(boolean visible) {}
    }

    private final Host host;

    private JTextField wakeField;
    private JTextField endField;
    private JComboBox<String> silenceBox;
    private JCheckBox autoSendBox;
    private JComboBox<String> sendKeyBox;
    private JLabel wakeMark;
    private JLabel endMark;
    private JLabel problemLabel;

    public SettingsWindow(Host host) {
        super("TalkingLive 设置");
        this.host = host;

        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);   // 关窗 != 退出
        setIconImage(Icons.image(Icons.Kind.MIC, 64, Theme.ballRing("IDLE", false)));

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BG);
        root.add(buildForm(), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        setContentPane(root);

        // 只有五行，窗口就该按内容大小走 —— 定死一个大窗口正是「看着繁琐」的来源之一
        pack();
        setMinimumSize(new Dimension(getWidth(), getHeight()));
        setLocationRelativeTo(null);

        reloadFromConfig();
    }

    // ==================== 表单 ====================

    private JComponent buildForm() {
        AppConfig cfg = host.config();

        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(Theme.BG);
        p.setBorder(new EmptyBorder(16, 18, 8, 18));

        wakeField = field(cfg.wakeWord(), 12);
        endField = field(cfg.endWord(), 12);
        wakeMark = new JLabel();
        endMark = new JLabel();

        silenceBox = new JComboBox<>();
        for (int s : SILENCE_CHOICES) {
            silenceBox.addItem(s == 0 ? "关闭" : s + " 秒");
        }
        style(silenceBox);

        autoSendBox = new JCheckBox("说完后替我按发送键");
        autoSendBox.setOpaque(false);
        autoSendBox.setForeground(Theme.TEXT);
        autoSendBox.setFont(Theme.font(12));
        autoSendBox.setFocusPainted(false);

        sendKeyBox = new JComboBox<>(new String[] {
            AppConfig.SendKey.ENTER.display(), AppConfig.SendKey.CTRL_ENTER.display()});
        style(sendKeyBox);

        int y = 0;
        y = row(p, y, "唤醒词", wakeField, wakeMark, "说它开始听写");
        y = row(p, y, "结束词", endField, endMark, "说它结束本段并落字");
        y = row(p, y, "静音超时", silenceBox, null, "这么久没说话就自动结束本段");
        // 这一行刻意**没有标签**：勾选框自己的文字已经说清了它是什么，
        // 再加一个「自动发送」标签就是同一句话说两遍。
        // 此时标签列的值由 row() 内部补一个等宽占位，保证控件起点仍然对齐。
        y = row(p, y, "", autoSendBox, null, null);
        row(p, y, "发送键", sendKeyBox, null, "按哪个键发送；聊天软件想换行就用 Ctrl+Enter");

        // 校验失败提示：只在真的有问题时出现，且占的是**底部固定的一行**，
        // 不参与布局计算 —— 否则提示一出现界面就跳动。
        problemLabel = new JLabel(" ");
        problemLabel.setFont(Theme.font(11));
        problemLabel.setForeground(Theme.ERR);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 99;
        c.gridwidth = 3;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(6, 0, 0, 0);
        p.add(problemLabel, c);

        // ---- 行为绑定
        bindText(wakeField, v -> {
            cfg.setWakeWord(v);
            commit(cfg);
        });
        bindText(endField, v -> {
            cfg.setEndWord(v);
            commit(cfg);
        });
        silenceBox.addActionListener(e -> {
            if (silenceBox.getSelectedIndex() >= 0) {
                cfg.setSilenceSeconds(SILENCE_CHOICES[silenceBox.getSelectedIndex()]);
                commit(cfg);
            }
        });
        autoSendBox.addItemListener(e -> {
            cfg.setAutoSend(autoSendBox.isSelected());
            sendKeyBox.setEnabled(autoSendBox.isSelected());
            commit(cfg);
        });
        sendKeyBox.addActionListener(e -> {
            cfg.setSendKey(AppConfig.SendKey.fromDisplay((String) sendKeyBox.getSelectedItem()));
            commit(cfg);
        });

        return p;
    }

    /** 一行：标签 + 主控件 + 可选徽标。返回下一行的行号。标签传空串表示这行不需要标签。 */
    private int row(JPanel p, int y, String label, JComponent main, JComponent mark,
            String tooltip) {
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = y;
        c.insets = new Insets(6, 0, 6, 10);
        // 空标签也要占住这一格：不占的话控件会左移，整列就不齐了。
        // 用空 JLabel 而不是「不添加组件」—— 后者会让 GridBagLayout 把这一行压扁。
        JLabel l = new JLabel(label);
        l.setFont(Theme.font(12));
        l.setForeground(Theme.TEXT_MUTED);
        l.setPreferredSize(new Dimension(LABEL_COLUMN, 22));
        p.add(l, c);

        c.gridx = 1;
        c.insets = new Insets(6, 0, 6, mark == null ? 0 : 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        main.setToolTipText(tooltip);
        p.add(main, c);

        if (mark != null) {
            c.gridx = 2;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 0;
            c.insets = new Insets(6, 0, 6, 0);
            p.add(mark, c);
        }
        return y + 1;
    }

    private JComponent buildFooter() {
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setBackground(Theme.BG);
        p.setBorder(new EmptyBorder(0, 18, 12, 18));

        JLabel path = new JLabel("更多参数在 config.json");
        path.setFont(Theme.font(11));
        path.setForeground(Theme.DIM);
        path.setToolTipText(com.talkinglive.core.AppPaths.configFile().toString());
        p.add(path, BorderLayout.CENTER);

        JLabel close = new JLabel("关闭", SwingConstants.RIGHT);
        close.setFont(Theme.font(12));
        close.setForeground(Theme.ACCENT);
        close.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        close.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                setVisible(false);
            }
        });
        p.add(close, BorderLayout.EAST);
        return p;
    }

    // ==================== 提交与刷新 ====================

    /**
     * 一次配置改动：校验 → 保存 → 刷新提示。
     *
     * <p>失败时**不破坏当前生效值**（§4.3）—— 显示的是「你刚输入的还不生效，原因是什么」。
     */
    private void commit(AppConfig candidate) {
        String err = host.applyConfig(candidate);
        problemLabel.setText(err == null ? " " : err.replace("\n", "  "));
        if (err != null) {
            log.info("配置改动被拒绝：{}", err.replace("\n", " / "));
        }
        refreshMarks();
    }

    private void reloadFromConfig() {
        AppConfig cfg = host.config();
        // 值没变就不动控件：否则会把用户正在输入的光标位置顶掉
        setIfIdle(wakeField, cfg.wakeWord());
        setIfIdle(endField, cfg.endWord());
        selectSilence(cfg.silenceSeconds());
        autoSendBox.setSelected(cfg.autoSend());
        sendKeyBox.setEnabled(cfg.autoSend());
        sendKeyBox.setSelectedItem(cfg.sendKey().display());
        refreshMarks();
    }

    private void setIfIdle(JTextField f, String value) {
        if (f != null && !f.getText().equals(value)) {
            f.setText(value);
        }
    }

    private void selectSilence(int seconds) {
        for (int i = 0; i < SILENCE_CHOICES.length; i++) {
            if (SILENCE_CHOICES[i] == seconds) {
                silenceBox.setSelectedIndex(i);
                return;
            }
        }
        // 配置里是列表外的值（例如用户手改了 JSON）：不擅自改动它，
        // 而是把当前值临时加进下拉框，让界面如实显示。
        String label = seconds + " 秒";
        silenceBox.addItem(label);
        silenceBox.setSelectedItem(label);
    }

    /** 唤醒词 / 结束词是否真的在模型词表内（附录 C 的静默失效就靠这一行拦住）。 */
    private void refreshMarks() {
        applyMark(wakeMark, wakeField == null ? host.config().wakeWord() : wakeField.getText());
        applyMark(endMark, endField == null ? host.config().endWord() : endField.getText());
    }

    private void applyMark(JLabel label, String word) {
        if (label == null) {
            return;
        }
        Boolean in = word == null || word.isBlank() ? Boolean.TRUE : host.wordInVocabulary(word);
        if (in == null) {
            label.setIcon(Icons.of(Icons.Kind.WARN, Icons.SMALL));
            label.setText("");
            label.setToolTipText("模型未就绪，无法校验「" + word + "」");
            return;
        }
        // 图标走 Icons（矢量、随 DPI 缩放），不用「✓」「✗」字符 ——
        // 字符尺寸由字体决定、矢量图标由像素决定，混用就会一大一小
        // （用户报告的「图标大小不一致」正是这么来的）。
        label.setIcon(Icons.of(in ? Icons.Kind.CHECK : Icons.Kind.CROSS, Icons.SMALL));
        label.setText("");
        label.setToolTipText(in ? "在词表内，可以识别"
                : "「" + word + "」不在词表内 —— Vosk 会静默忽略它，永远不会被识别到");
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        host.onVisibilityChanged(visible);
        if (visible) {
            reloadFromConfig();
        }
    }

    // ==================== 零件 ====================

    private JTextField field(String value, int cols) {
        JTextField f = new JTextField(value, cols);
        f.setFont(Theme.font(12));
        f.setBackground(Theme.FIELD);
        f.setForeground(Theme.TEXT);
        f.setCaretColor(Theme.TEXT);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                new EmptyBorder(4, 7, 4, 7)));
        f.setPreferredSize(new Dimension(f.getPreferredSize().width, 28));
        return f;
    }

    private void style(JComboBox<String> box) {
        box.setFont(Theme.font(12));
        box.setBackground(Theme.FIELD);
        box.setForeground(Theme.TEXT);
        box.setFocusable(false);
    }

    /**
     * 文字类配置的绑定：**防抖后**再落盘。
     *
     * <p>逐字落盘会连中间态一起写：把「子曰」改成「小助手」的过程中会先落「子」，
     * 再落「小」——而后者可能与结束词冲突、被拒绝并弹错误，用户看到的是
     * 「我还没打完它就报错」。
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

    // ==================== 供自检断言 ====================

    /**
     * 界面上**真的**有几个可改项（输入框 / 复选框 / 下拉框），直接数出来的。
     *
     * <p>自检断言用这个方法而不是一个手写的常量：常量会随界面改动漂移，
     * 而「设置页有没有又长回去」恰恰是要长期守住的性质 —— 用户已经为此反馈过三次。
     */
    public int editableControlCount() {
        return countEditable(getContentPane());
    }

    /** 界面上出现的设置标签（非空那些），用于断言「必需项都还在」。 */
    public java.util.List<String> visibleLabels() {
        java.util.List<String> out = new java.util.ArrayList<>();
        collectLabels(getContentPane(), out);
        return out;
    }

    private static int countEditable(java.awt.Container c) {
        int n = 0;
        for (java.awt.Component comp : c.getComponents()) {
            if (comp instanceof JTextField || comp instanceof JCheckBox
                    || comp instanceof JComboBox) {
                n++;
            }
            if (comp instanceof java.awt.Container inner) {
                n += countEditable(inner);
            }
        }
        return n;
    }

    private static void collectLabels(java.awt.Container c, java.util.List<String> out) {
        for (java.awt.Component comp : c.getComponents()) {
            if (comp instanceof JLabel l && l.getText() != null && !l.getText().isBlank()) {
                out.add(l.getText());
            }
            if (comp instanceof java.awt.Container inner) {
                collectLabels(inner, out);
            }
        }
    }

    // 注：本窗口没有页签 —— 日志 / 模型状态 / 自检已搬到 DiagnosticsWindow。
    // 那里是诊断，这里是设置，两者混在一起正是「设置太繁琐」的一部分原因。
}
