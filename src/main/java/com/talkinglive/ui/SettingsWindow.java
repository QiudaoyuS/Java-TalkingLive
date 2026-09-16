package com.talkinglive.ui;

import com.talkinglive.core.AppConfig;
import com.talkinglive.system.MicValidator;
import java.util.List;
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

        /**
         * 当前配置的**提醒**（合法但可能让用户意外的组合）。
         *
         * <p>与 {@link #applyConfig} 的失败原因区分开：那些是"不合法、被拒绝"，这些是
         * "能用、但你八成想知道"。窗口一打开就要显示，所以不能等用户改一次配置。
         */
        default List<String> configWarnings() {
            return List.of();
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
    /**
     * 正在回填控件（见 {@link #reloadFromConfig}）。
     *
     * <p>回填会给控件赋值，而控件上的监听器分不清"被赋值"和"用户改了" ——
     * 没有这个标志时，设置窗口一构造就会保存两次配置。
     */
    private boolean loading;
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
        // 构造完成后把状态提示换成"当前配置的提醒"（没有就清空）。
        // `reloadFromConfig` 会把控件逐个填上配置值，其中下拉框/勾选框的赋值会触发
        // 监听器 → 走到 commit → 显示「已保存」。但用户此刻**什么都没改**，
        // 一打开就写「修改已保存」是不实的状态。真正的改动会在那次 commit 里重新点亮它。
        showConfigWarnings();
    }

    /** 把状态提示恢复成空白（不显示任何结论）。 */
    private void clearStatus() {
        savedTimer.stop();
        problemLabel.setIcon(null);
        problemLabel.setText(" ");
        problemLabel.setForeground(Theme.TEXT_FAINT);
        problemLabel.setToolTipText(null);
    }

    /**
     * 打开窗口时把"当前配置的提醒"显示出来（如果有）。
     *
     * <p>这些提醒以前**只写进日志**，界面上完全看不到 —— 用户只能自己去翻日志文件。
     * 而它们说的恰恰是"你的配置能用，但结果可能不是你要的"（例如结束词留空 +
     * 静音也关着 → 只剩 60 秒上限收尾），属于必须让人看见的信息。
     *
     * <p>与"已保存"不同，**不自动淡出**：它是当前配置的状态，不是一次操作的结果。
     */
    private void showConfigWarnings() {
        List<String> warnings = host.configWarnings();
        if (warnings.isEmpty()) {
            clearStatus();
            return;
        }
        savedTimer.stop();
        problemLabel.setIcon(Icons.of(Icons.Kind.WARN, Icons.SMALL));
        setStatusText(String.join(" ", warnings));
        problemLabel.setForeground(Theme.WARN);
        problemLabel.setToolTipText(String.join("\n", warnings));
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
        // 它在界面上占**固定的一块**：成功时显示「修改已保存并立即生效」，
        // 失败时显示原因。两者共用同一个标签 —— 用户反馈「没有确认修改按钮」，
        // 实际缺的不是按钮而是**反馈**（见 commit 的注释）。
        //
        // 用固定高度而不是让文字撑开布局：否则提示一出现/消失，窗口就会跳动。
        //
        // **高 34px 而不是 20px**：一行放不下时改成换行，不再打省略号。
        // 这个教训花了两轮才学对 —— 先是把提示写短（还是被截），
        // 再是把标签加宽（加到 300px 上限还是被截，因为提示本来就比它宽）。
        // 真正的问题从来不是"文案太长"，而是**截断**这个处理方式本身：
        // 用户看到「已拆成单字「飞」…」时，恰好丢掉了"拆成了哪几个字"这个唯一重点。
        //
        // 34 这个数不是拍的：JLabel 默认上下内边距各 2px，行高 15px（11pt），
        // 34−4=30 → 正好 2 行。**曾经写 30 只得到 1 行**（26/15=1），
        // 于是长提示仍然被省略 —— 是自检 F4 把这条抓出来的，肉眼看不出差别。
        problemLabel = new JLabel(" ");
        problemLabel.setFont(Theme.font(11));
        problemLabel.setForeground(Theme.ERR);
        problemLabel.setVerticalAlignment(SwingConstants.TOP);
        problemLabel.setPreferredSize(new Dimension(STATUS_LABEL_WIDTH,
                statusLabelHeight(problemLabel.getFontMetrics(problemLabel.getFont()))));
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
        //
        // 每个监听器都先问 `if (loading) return;`：**回填控件也会触发监听器**。
        //
        // 这不是洁癖 —— 实测日志显示设置窗口一构造就连续保存了两次配置
        // （构造时 reloadFromConfig 回填 5 个控件、窗口首次显示时又回填一次），
        // 每次都重跑词表校验、重打日志、重算提醒。用户什么都没改，
        // 程序却做了两遍无用功，日志里也留下两遍同样的记录。
        bindText(wakeField, v -> {
            if (loading) {
                return;
            }
            cfg.setWakeWord(v);
            commit(cfg);
        });
        bindText(endField, v -> {
            if (loading) {
                return;
            }
            cfg.setEndWord(v);
            commit(cfg);
        });
        silenceBox.addActionListener(e -> {
            if (!loading && silenceBox.getSelectedIndex() >= 0) {
                cfg.setSilenceSeconds(SILENCE_CHOICES[silenceBox.getSelectedIndex()]);
                commit(cfg);
            }
        });
        autoSendBox.addItemListener(e -> {
            if (loading) {
                return;
            }
            cfg.setAutoSend(autoSendBox.isSelected());
            sendKeyBox.setEnabled(autoSendBox.isSelected());
            commit(cfg);
        });
        sendKeyBox.addActionListener(e -> {
            if (loading) {
                return;
            }
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
        setStatusText("修改已保存并立即生效");
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
        setStatusText(notice.brief());
        problemLabel.setForeground(Theme.WARN);
        problemLabel.setToolTipText(notice.describe());
    }

    /**
     * 把一段话放进固定大小的状态标签：**换行，不截断**。
     *
     * <p>截断是错的，已经错过两次：用户看到「唤醒词「飞瑞」已拆成单字「飞」…」，
     * 丢掉的恰好是"拆成了哪几个字"——那条提示唯一要说的事。
     *
     * <p>为什么用 HTML：{@link JLabel} 的单行文本永远不会自动换行，而
     * {@code <html>} 内容在标签内是**按宽度自动折行**的，正好就是想要的语义。
     * 折行交给 Swing 而不是自己按像素切 —— 那样还得考虑词边界与中英混排。
     *
     * <p>只有换行还不够：标签高度固定（不能让文字撑开布局，否则窗口会跳），
     * 所以超过可容纳行数的部分仍会被裁掉。因此这里**先用字体量一遍**，
     * 超长时保留头部并明确写出「…（悬停看全文）」，让用户知道还有内容可看，
     * 而不是看到一个没说清的省略号。
     */
    private void setStatusText(String text) {
        String one = text == null ? "" : text.trim();
        if (one.isEmpty()) {
            problemLabel.setText(" ");
            return;
        }
        String wrapped = wrapToFit(one, statusWidth(), statusMaxLines(),
                problemLabel.getFontMetrics(problemLabel.getFont()));
        problemLabel.setText("<html>" + escapeHtml(wrapped) + "</html>");
    }

    /** 状态标签可用像素宽度（减去内边距，留 6px 余量给取整误差）。 */
    private int statusWidth() {
        Insets in = problemLabel.getInsets();
        return Math.max(80, problemLabel.getPreferredSize().width - in.left - in.right - 6);
    }

    /** 状态标签按 11pt 行高能放下几行（用真实字体度量，不用常量猜）。 */
    private int statusMaxLines() {
        java.awt.FontMetrics fm = problemLabel.getFontMetrics(problemLabel.getFont());
        int line = fm == null ? 15 : fm.getHeight();
        int usable = problemLabel.getPreferredSize().height - problemLabel.getInsets().top
                - problemLabel.getInsets().bottom;
        return Math.max(1, usable / line);
    }

    /** 放不下时追加的提示语。**必须整体放得下**，不允许自己也被切掉一半。 */
    public static final String ELISION_MARK = "…（悬停看全文）";

    /**
     * 状态标签宽度。
     *
     * <p>360 而不是 300：300px 时那条结束词警告（40 字）两行装不下，
     * 会丢掉「长句会被从中间截断」这半句 —— 而那是提醒的全部意义。
     * 宽度从哪来：字段列本身就有约 366px，标签只是把它用满，
     * **不会撑大窗口**（实测 pack 后窗口宽度不变，见自检 F2 报的窗口尺寸）。
     */
    static final int STATUS_LABEL_WIDTH = 360;

    /** 状态标签显示几行。 */
    static final int STATUS_LABEL_LINES = 2;

    /**
     * 状态标签高度。
     *
     * <p><b>必须按真实字体的行高算，不能写死像素数。</b>实测同一个 11pt 字号：
     * 无头环境量到行高 15px，而应用运行时是 **22px**（字体解析结果不同，
     * 中文字体的 leading 也更大）。我先前写死 34px（按 15px 推算），
     * 结果 34÷22 = **1 行**，长提示照样被省略 —— 是自检 F4 把这个假装修好的地方
     * 抓出来的：它打印出「行高=22、可用行=1」，而肉眼看日志只会觉得"改了高度就该好了"。
     */
    static int statusLabelHeight(java.awt.FontMetrics fm) {
        int line = fm == null ? 22 : Math.max(1, fm.getHeight());
        return line * STATUS_LABEL_LINES + 2; // +2 给 JLabel 顶部那点空隙
    }

    /**
     * 按像素宽度折行，最多 {@code maxLines} 行；放不下时在末尾追加 {@link #ELISION_MARK}。
     *
     * <p><b>关键约束（实测抓出来的 bug）</b>：早先的实现把后缀直接追加在**已经排满**的
     * 最后一行尾部，于是那一行变成 376px —— 又一次超出宽度，又一次被 Swing 切掉，
     * 用户看到的还是省略号。所以这里的规则是：后缀作为一个**整体**（约 86px）
     * 也要算进宽度；放不下就继续从最后一行收字符，直到后缀能完整放下。
     * 收字符时按**码点**收，不会把代理对切成半个字。
     *
     * <p>**公开**（而不是包私有）是因为 {@code com.talkinglive.SelfTest} 在另一个包里
     * 也要用它做断言 —— 自检是"无头环境下最接近真人"的那道验证，不该因为可见性
     * 而放弃这条检查。参数与返回值都是纯数据，公开不泄露任何内部状态。
     */
    public static String wrapToFit(String text, int maxWidth, int maxLines,
            java.awt.FontMetrics fm) {
        if (text == null || text.isEmpty() || fm == null || maxWidth <= 0 || maxLines <= 0) {
            return text == null ? "" : text;
        }
        int[] cps = text.codePoints().toArray();
        StringBuilder out = new StringBuilder();
        int pos = 0; // 下一个要排的码点下标
        for (int line = 1; pos < cps.length; line++) {
            if (line > 1) {
                out.append('\n');
            }
            // 本行能塞下多少（硬上限）
            int width = 0;
            int fit = pos;
            while (fit < cps.length) {
                int w = fm.stringWidth(new String(Character.toChars(cps[fit])));
                if (width + w > maxWidth) {
                    break;
                }
                width += w;
                fit++;
            }
            if (fit == pos) {
                // 一个字都放不下（宽度设置异常）：放一个字避免死循环
                fit = pos + 1;
            }
            // 在硬上限之前找一个**可断点**，避免把「60 秒」这类词组或标点拆开。
            // 只在断点足够靠后（吃掉本行一半以上）时才用它 —— 否则行会短得难看。
            int breakAt = -1;
            if (fit < cps.length) {
                int half = pos + (fit - pos) / 2;
                for (int i = fit; i >= half; i--) {
                    if (i > pos && isBreakPoint(cps[i - 1])) {
                        breakAt = i;
                        break;
                    }
                }
            }
            // 禁则：不在**数字/字母与汉字之间**断开（会把「60 秒」「AI 输入」拆散），
            // 也不在汉字与数字/字母之间断开。此时宁可硬断在别处。
            int end = breakAt > pos ? breakAt : fit;
            if (end < cps.length && badSplit(cps, end)) {
                // 往回退到最近的不违反禁则的位置
                int back = end;
                while (back > pos && badSplit(cps, back)) {
                    back--;
                }
                if (back > pos) {
                    end = back;
                }
            }
            if (end >= cps.length) {
                out.append(codepointsToString(cps, pos, end));
                pos = end;
                continue;
            }
            if (line >= maxLines) {
                // 没有下一行了：把剩余内容收短到能让省略标记完整放下
                return withElision(out.toString(), cps, pos, end, maxWidth, fm);
            }
            out.append(codepointsToString(cps, pos, end));
            pos = end;
        }
        return out.toString();
    }

    /** 可断行处：空白，或中英文标点。 */
    private static boolean isBreakPoint(int cp) {
        if (Character.isWhitespace(cp)) {
            return true;
        }
        return "，。；：、！？—…（）「」《》,.;:!?)]}".indexOf(cp) >= 0;
    }

    /**
     * 在 {@code at} 处断开是否**不该**发生（禁则处理）。
     *
     * <p>两类不自然的断法：
     * <ul>
     *   <li>数字/字母 ｜ 汉字：把「60 秒」拆成「60」+「秒」—— 量词被甩到下一行读起来像换了个数；</li>
     *   <li>汉字 ｜ 数字/字母：把「识别 AI」拆成「识别」+「 AI」，同样别扭。</li>
     * </ul>
     * 两条都只在**紧邻**时才算违规，中间有空格就不管（空格本身就是断点）。
     */
    private static boolean badSplit(int[] cps, int at) {
        if (at <= 0 || at >= cps.length) {
            return false;
        }
        int prev = cps[at - 1];
        int next = cps[at];
        boolean prevAlnum = isAlnum(prev);
        boolean nextAlnum = isAlnum(next);
        boolean prevHan = isHan(prev);
        boolean nextHan = isHan(next);
        return (prevAlnum && nextHan) || (prevHan && nextAlnum);
    }

    private static boolean isAlnum(int cp) {
        return (cp >= '0' && cp <= '9') || (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z');
    }

    private static boolean isHan(int cp) {
        return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
    }

    private static String codepointsToString(int[] cps, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.appendCodePoint(cps[i]);
        }
        return sb.toString();
    }

    /**
     * 把「已排到行数上限」的内容收短到能让 {@link #ELISION_MARK} 完整放下为止。
     *
     * @param placed 已经排好的内容（可能含换行）
     * @param cps    原文码点
     * @param pos    本行起点（原文下标）
     * @param end    本行硬上限终点（原文下标）
     */
    private static String withElision(String placed, int[] cps, int pos, int end,
            int maxWidth, java.awt.FontMetrics fm) {
        int markWidth = fm.stringWidth(ELISION_MARK);
        int budget = maxWidth - markWidth;
        // 最后一行能保留多少字符（含标点优先的断点考虑）
        int keep = pos;
        int width = 0;
        while (keep < end) {
            int w = fm.stringWidth(new String(Character.toChars(cps[keep])));
            if (width + w > budget) {
                break;
            }
            width += w;
            keep++;
        }
        String prefix = placed.isEmpty() ? "" : placed;
        String tail = codepointsToString(cps, pos, keep);
        if (tail.isEmpty()) {
            // 连一个字符都放不下时，只保留省略标记 —— 告诉用户"还有内容"最重要
            int nl = prefix.lastIndexOf('\n');
            return (nl < 0 ? "" : prefix.substring(0, nl + 1)) + ELISION_MARK;
        }
        return prefix + stripTrailingSpace(tail) + ELISION_MARK;
    }

    private static String stripTrailingSpace(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1)) && s.charAt(end - 1) != '\n') {
            end--;
        }
        return s.substring(0, end);
    }

    /** 单测友好的重载：自己造一个 FontMetrics。 */
    static String wrapToFit(String text, int maxWidth, int maxLines, java.awt.Font font) {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            return wrapToFit(text, maxWidth, maxLines, g.getFontMetrics(font));
        } finally {
            g.dispose();
        }
    }

    /** HTML 里只转义这三个字符就够（状态文字不含标签）。 */
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 显示失败原因（面向用户，保留到下一次改动）。
     *
     * <p>同样走 {@link #setStatusText}：拒绝原因常常是词表校验那种长句
     * （实测首行就有约 380px），不换行的话用户看到的是省略号而不是问题本身。
     * 完整文本始终挂在 tooltip 上。
     */
    private void showProblem(String err) {
        savedTimer.stop();
        problemLabel.setIcon(Icons.of(Icons.Kind.CROSS, Icons.SMALL));
        setStatusText(err);
        problemLabel.setForeground(Theme.ERR);
        problemLabel.setToolTipText(err);
    }

    /** 清空状态文字时也要走同一套（保持 HTML 与非 HTML 状态一致）。 */
    private void clearStatusText() {
        problemLabel.setText(" ");
    }

    private void reloadFromConfig() {
        AppConfig cfg = host.config();
        // 回填期间屏蔽监听器：否则「控件被赋值」会被当成「用户改了值」，
        // 走到 commit → 保存配置 + 重跑词表校验 + 重算提醒。用户什么都没做，
        // 程序却做了两遍（实测日志里就是两遍）。
        loading = true;
        try {
            // 值没变就不动控件：否则会把用户正在输入的光标位置顶掉
            setIfIdle(wakeField, cfg.wakeWord());
            setIfIdle(endField, cfg.endWord());
            selectSilence(cfg.silenceSeconds());
            autoSendBox.setSelected(cfg.autoSend());
            sendKeyBox.setEnabled(cfg.autoSend());
            sendKeyBox.setSelectedItem(cfg.sendKey().display());
        } finally {
            loading = false;
        }
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
            // 每次打开都按**当前配置**重算提醒：用户可能刚在别处改过配置
            // （手改 config.json 后重启），也可能上次的改动把提醒消掉了。
            showConfigWarnings();
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
