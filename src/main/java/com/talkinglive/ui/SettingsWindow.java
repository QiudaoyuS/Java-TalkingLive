package com.talkinglive.ui;

import com.talkinglive.core.AppConfig;
import com.talkinglive.system.MicValidator;
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
 * 设置窗口 —— 从悬浮球右键菜单进入，**按需打开，关掉不等于退出**（§4.4）。
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

        /**
         * 上一次 {@link #applyConfig} **成功**时想要告诉用户的事；没有则返回 null。
         *
         * <p>为什么需要一条独立于返回值的通道：有一种"成功了但用户必须知道"的情况——
         * 唤醒词整体不在词表内、被逐字拆成了单字序列（见 {@code engine.WakePhrase}）。
         * 这既不是错误（功能可用，不该显示红 ✗），也不该被静默吞掉
         * （用户不知道要被听成哪几个字，出问题时无从排查）。
         * 早期把它并进返回值，结果是**每次都显示"修改被拒绝"**，正好把事情说反了。
         *
         * <p>返回 {@code Problem} 而不是字符串：界面需要用它的 {@code brief()} 显示一行
         * 短版本、用 {@code describe()} 做 tooltip。实测完整说明有 555px 宽，而状态标签
         * 只有约 292px，直接用会被截成「…已...」，用户恰恰看不到"拆成了哪几个字"。
         */
        default MicValidator.Problem lastApplyNotice() {
            return null;
        }

        /** 某个词是否在当前模型的词表内；模型不可用时返回 null（无法校验）。 */
        Boolean wordInVocabulary(String word);

        /**
         * 某个词能否**逐字拆开**使用（整词不在词表内、但每个字都在）。
         *
         * <p>只对唤醒词有意义：结束词不允许拆字（后果是"立刻停止录音"，
         * 单字太容易误触发）。返回 null 表示模型未就绪、无法判断。
         */
        default Boolean canSpellWord(String word) {
            return null;
        }

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
    /** 「已保存」提示的自动淡出计时器（见 showSaved）。 */
    private Timer savedTimer;

    public SettingsWindow(Host host) {
        super("TalkingLive 设置");
        this.host = host;

        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);   // 关窗 != 退出
        setIconImage(Icons.appIcon());

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
        // 构造完成后把状态提示清空：`reloadFromConfig` 会把控件逐个填上配置值，
        // 其中下拉框/勾选框的赋值会触发监听器 → 走到 commit → 显示「已保存」。
        // 但用户此刻**什么都没改**，一打开就写「修改已保存」是不实的状态。
        // 真正的改动会在那次 commit 里重新点亮它。
        clearStatus();
    }

    /** 把状态提示恢复成空白（不显示任何结论）。 */
    private void clearStatus() {
        savedTimer.stop();
        problemLabel.setIcon(null);
        problemLabel.setText(" ");
        problemLabel.setForeground(Theme.TEXT_FAINT);
        problemLabel.setToolTipText(null);
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

        // 改动结果提示（成功或失败都显示在这里）。
        //
        // 它在界面上占**固定的一行**：成功时显示「修改已保存并立即生效」，
        // 失败时显示原因。两者共用同一个标签 —— 用户反馈「没有确认修改按钮」，
        // 实际缺的不是按钮而是**反馈**（见 commit 的注释）。
        //
        // 用固定高度而不是让文字撑开布局：否则提示一出现/消失，窗口就会跳动。
        problemLabel = new JLabel(" ");
        problemLabel.setFont(Theme.font(11));
        problemLabel.setForeground(Theme.ERR);
        problemLabel.setPreferredSize(new Dimension(300, 20));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 99;
        c.gridwidth = 3;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(6, 0, 0, 0);
        p.add(problemLabel, c);

        // 「已保存」提示 2 秒后自动清掉：留久了会让人以为那是一个需要处理的状态。
        savedTimer = new Timer(2000, e -> clearStatus());
        savedTimer.setRepeats(false);

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

        JLabel path = new JLabel("改动即时生效，无需保存");
        path.setFont(Theme.font(11));
        path.setForeground(Theme.TEXT_FAINT);
        path.setToolTipText("更多参数（含注入间隔等）在 " + com.talkinglive.core.AppPaths.configFile());
        p.add(path, BorderLayout.CENTER);

        // 按钮文字是「完成」而不是「关闭」：关掉设置窗口确实等于完成配置，
        // 而"关闭"会让人以为还有东西没保存。这里和上面那行一起回答同一个疑问 ——
        // 「我改了到底生效没有、要不要点保存」。
        JLabel close = new JLabel("完成", SwingConstants.RIGHT);
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
     * 一次配置改动：校验 → 保存 → 把结果**显示出来**。
     *
     * <p>失败时**不破坏当前生效值**（§4.3）—— 显示的是「你刚输入的还不生效，原因是什么」。
     *
     * <p><b>成功时也必须显示。</b>用户反馈「设置界面没有确认修改按钮」——
     * 实际行为是「改完立即生效」，但界面上**一点反馈都没有**：没有保存按钮、
     * 没有「已保存」提示，页脚只写着「更多参数在 config.json」。
     * 于是用户会去找那个按钮，找不到就以为"改了没生效"。
     *
     * <p>这里刻意**不**改成"暂存 + 点保存才生效"：改完即生效本来就是更好的交互
     * （少一步、不会忘记保存），用户的真实需求是**知道它生效了**。
     * 所以给的是可见的确认，而不是一个按钮。
     */
    private void commit(AppConfig candidate) {
        String err = host.applyConfig(candidate);
        if (err != null) {
            showProblem(err);
            log.info("配置改动被拒绝：{}", err.replace("\n", " / "));
        } else {
            MicValidator.Problem notice = host.lastApplyNotice();
            if (notice == null) {
                showSaved();
            } else {
                // 成功了，但有话要说（唤醒词被逐字拆开）。不能显示成错误：
                // 功能是可用的，显示红 ✗ 会让用户以为自己配错了。
                showNotice(notice);
            }
        }
        refreshMarks();
    }

    /** 显示「已保存」（短暂显示后自动淡出，避免长期占位）。 */
    private void showSaved() {
        savedTimer.stop();
        problemLabel.setIcon(Icons.of(Icons.Kind.CHECK, Icons.SMALL));
        problemLabel.setText("修改已保存并立即生效");
        problemLabel.setForeground(Theme.OK);
        problemLabel.setToolTipText(null);
        savedTimer.restart();
    }

    /**
     * 显示「成功但有话要说」。
     *
     * <p>用警告色而不是错误色或成功色：它确实生效了（不是红），但也确实不是
     * 用户输入的那个整词（不是绿）——唤醒词被拆成单字后，用户必须把每个字都说清楚，
     * 否则会出现"说了没反应"而以为自己配错了。
     *
     * <p>**不自动淡出**：它携带的是行动指引（该怎么念），用户需要能反复看。
     *
     * <p>标签用 {@code brief()} 的短版本，完整说明挂 tooltip：实测完整版有 555px 宽，
     * 而状态标签只有约 292px，直接用会被 Swing 截成「…已...」，用户看不到重点。
     */
    private void showNotice(MicValidator.Problem notice) {
        savedTimer.stop();
        problemLabel.setIcon(Icons.of(Icons.Kind.WARN, Icons.SMALL));
        problemLabel.setText(fittingText(notice.brief()));
        problemLabel.setForeground(Theme.WARN);
        problemLabel.setToolTipText(notice.describe());
    }

    /**
     * 把文本截到状态标签放得下的长度。
     *
     * <p>这里是**按实际字体的像素宽度量**，不是按字符数估算 —— 同一个字符串在
     * 雅黑与 Segoe UI 下宽度能差 1.5 倍（实测同一条提示 555px 对 361px），
     * 按字符数猜必然在某个字体或 DPI 下失手，而失手的表现就是用户又看到一个省略号。
     *
     * <p>截断时在**码点**边界切，避免把代理对切成半个字。
     */
    private String fittingText(String text) {
        java.awt.FontMetrics fm = problemLabel.getFontMetrics(problemLabel.getFont());
        if (fm == null) {
            return text;
        }
        // 留 8px 余量：标签有内边距，且不同 DPI 下取整会再吃掉一点。
        int limit = Math.max(40, problemLabel.getPreferredSize().width
                - problemLabel.getInsets().left - problemLabel.getInsets().right - 8);
        if (fm.stringWidth(text) <= limit) {
            return text;
        }
        int[] cps = text.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        for (int cp : cps) {
            String next = sb.toString() + new String(Character.toChars(cp)) + "…";
            if (fm.stringWidth(next) > limit) {
                break;
            }
            sb.appendCodePoint(cp);
        }
        return sb.length() == 0 ? text : sb + "…";
    }

    /**
     * 显示失败原因（面向用户，保留到下一次改动）。
     *
     * <p>同样要过 {@link #fittingText}：拒绝原因常常是词表校验那种长句
     * （实测「本段结束」那条在雅黑下 344px，超出标签宽度），不截的话用户看到的
     * 是省略号而不是问题本身。完整文本始终挂在 tooltip 上。
     */
    private void showProblem(String err) {
        savedTimer.stop();
        problemLabel.setIcon(Icons.of(Icons.Kind.CROSS, Icons.SMALL));
        problemLabel.setText(fittingText(err.replace("\n", "  ")));
        problemLabel.setForeground(Theme.ERR);
        problemLabel.setToolTipText(err);
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

    /** 唤醒词 / 结束词是否真的能被识别（附录 C 的静默失效就靠这一行拦住）。 */
    private void refreshMarks() {
        applyMark(wakeMark,
                wakeField == null ? host.config().wakeWord() : wakeField.getText(), true);
        applyMark(endMark, endField == null ? host.config().endWord() : endField.getText(), false);
    }

    private void applyMark(JLabel label, String word) {
        applyMark(label, word, false);
    }

    /**
     * 唤醒词/结束词的行尾标记。
     *
     * @param wakeWord 是否是唤醒词那一行；只有唤醒词允许"逐字拆开用"
     */
    private void applyMark(JLabel label, String word, boolean wakeWord) {
        if (label == null) {
            return;
        }
        if (word == null || word.isBlank()) {
            label.setIcon(Icons.of(Icons.Kind.CHECK, Icons.SMALL));
            label.setText("");
            label.setToolTipText("在词表内，可以识别");
            return;
        }
        Boolean in = host.wordInVocabulary(word);
        if (in == null) {
            label.setIcon(Icons.of(Icons.Kind.WARN, Icons.SMALL));
            label.setText("");
            label.setToolTipText("模型未就绪，无法校验「" + word + "」");
            return;
        }
        // 整词不在表内，但逐字都在 → **能用**（被拆成单字序列）。
        // 标成警告而不是对勾：它确实不是原样的整词，用户得把每个字说清楚。
        if (!in && wakeWord) {
            Boolean spellable = host.canSpellWord(word);
            if (Boolean.TRUE.equals(spellable)) {
                label.setIcon(Icons.of(Icons.Kind.WARN, Icons.SMALL));
                label.setText("");
                label.setToolTipText("「" + word + "」整体不在词表内，但每个字都在 —— "
                        + "会按单字拆开使用，说的时候请把每个字都说清楚");
                return;
            }
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

    /**
     * 输入框。
     *
     * <p>白色主题下"边界感"不能靠加深背景来做 —— 窗口底已经是浅灰了，再深就脏。
     * Apple 的做法是**白底 + 1px 极浅描边 + 极淡投影**：控件的边界来自投影，
     * 而描边只是防止它在纯白区域里消失。实测只靠"背景略深"时，
     * 窗口底与输入框底的对比只有 4%（#f6f6f8 对 #ffffff），看起来发平。
     */
    private JTextField field(String value, int cols) {
        JTextField f = new JTextField(value, cols);
        f.setFont(Theme.font(12));
        f.setBackground(Theme.FIELD);
        f.setForeground(Theme.TEXT);
        f.setCaretColor(Theme.TEXT);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                new EmptyBorder(4, 8, 4, 8)));
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
