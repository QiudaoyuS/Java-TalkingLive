package com.talkinglive.ui;

import com.talkinglive.core.AppPaths;
import com.talkinglive.core.InMemoryLogAppender;
import com.talkinglive.core.StatusLine;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 诊断窗口 —— 状态 / 日志 / 自检三页。
 *
 * <p><b>为什么它从设置窗口里搬出来</b>：用户第 4 次反馈「设置界面太繁琐」。
 * 复盘时发现，原先的设置窗口是**四个页签**：常规 + 日志 + 模型状态 + 自检。
 * 后三个其实是**诊断**，不是设置——它们不改变任何行为，只是给人看。
 * 把它们留在设置里有两重代价：设置页看起来像个控制面板（心理上的繁琐），
 * 而真正想「看一眼日志」的人还要先进设置。
 *
 * <p>现在两者的入口分开，各自只有一个目的：
 * <ul>
 *   <li>悬浮球右键 →「设置…」→ {@link SettingsWindow}（五行，日常会改的东西）</li>
 *   <li>悬浮球右键 →「状态与诊断…」→ 本窗口（系统到底好不好、最近发生了什么）</li>
 * </ul>
 *
 * <p>本窗口**不改任何配置**，因此不需要「保存」的概念，也没有失败路径要提示。
 */
public class DiagnosticsWindow extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsWindow.class);

    public static final int TAB_STATUS = 0;
    public static final int TAB_LOG = 1;
    public static final int TAB_SELF_CHECK = 2;

    /** 状态行里值/说明列的换行宽度。 */
    private static final int VALUE_WIDTH = 420;

    /** 状态行名称列宽度。 */
    private static final int NAME_COLUMN = 150;

    /**
     * 与 App 之间的契约。只需要「读」的能力 —— 这正是它和设置窗口该分开的信号：
     * 一个只读、一个只写。
     */
    public interface Host {
        /** 各引擎 / 设备的状态行。 */
        List<StatusLine> status();

        /** 结构化自检结果（跑一次自检，可能较慢）。 */
        String diagnosticsReport();

        /** 窗口显示/隐藏时通知（用于暂停前台窗口监听）。 */
        default void onVisibilityChanged(boolean visible) {}
    }

    private final Host host;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel statusPanel = new JPanel();
    private final JTextArea logArea = new JTextArea();
    private final JTextArea selfCheckArea = new JTextArea();
    private AutoCloseable logSubscription;

    public DiagnosticsWindow(Host host) {
        super("TalkingLive 状态与诊断");
        this.host = host;

        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        setIconImage(Icons.image(Icons.Kind.WAVE, 64, Theme.ACCENT));

        tabs.setFont(Theme.font(12));
        tabs.setBackground(Theme.BG);
        tabs.setForeground(Theme.TEXT);
        tabs.addTab("状态", Icons.of(Icons.Kind.WAVE, Icons.SMALL), buildStatus());
        tabs.addTab("日志", Icons.of(Icons.Kind.DOCUMENT, Icons.SMALL), buildLog());
        tabs.addTab("自检", Icons.of(Icons.Kind.STETHOSCOPE, Icons.SMALL), buildSelfCheck());

        add(tabs, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        setSize(760, 620);
        setMinimumSize(new Dimension(560, 420));
        setLocationRelativeTo(null);
        refreshStatus();
    }

    /** 打开并切到指定页。 */
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
        }
    }

    // ==================== 状态 ====================

    private JComponent buildStatus() {
        statusPanel.setLayout(new BoxLayout(statusPanel, BoxLayout.Y_AXIS));
        statusPanel.setBackground(Theme.BG);
        statusPanel.setBorder(new EmptyBorder(16, 18, 16, 18));

        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(Theme.BG);
        holder.add(statusPanel, BorderLayout.NORTH);
        return scroll(holder);
    }

    /** 刷新状态页。每次打开窗口都会调。 */
    public void refreshStatus() {
        if (statusPanel == null) {
            return;
        }
        statusPanel.removeAll();

        List<StatusLine> lines = host.status();
        long bad = lines.stream().filter(l -> !l.ok()).count();

        // 顶部一句话结论：打开这一页的人只想知道「有没有问题、哪里有问题」
        JLabel headline = new JLabel(bad == 0
                ? "全部就绪（" + lines.size() + " 项）"
                : bad + " 项异常 / 共 " + lines.size() + " 项");
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

        statusPanel.revalidate();
        statusPanel.repaint();
    }

    private JComponent statusRow(StatusLine line) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        // BoxLayout 下不给最大宽度，行只会按 preferred 宽度画，右边留出一条空白
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.NORTHWEST;

        c.gridx = 0;
        c.gridy = 0;
        c.insets = new Insets(1, 0, 0, 10);
        p.add(new JLabel(Icons.of(line.ok() ? Icons.Kind.CHECK : Icons.Kind.CROSS,
                Icons.MEDIUM)), c);

        c.gridx = 1;
        c.insets = new Insets(0, 0, 0, 12);
        JLabel name = new JLabel(line.name());
        name.setFont(Theme.bold(12));
        name.setForeground(Theme.TEXT);
        name.setPreferredSize(new Dimension(NAME_COLUMN, 20));
        p.add(name, c);

        c.gridx = 2;
        c.insets = new Insets(0, 0, 0, 0);
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        JLabel value = new JLabel("<html><body style='width:" + VALUE_WIDTH + "px'>"
                + esc(line.value())
                + (line.detail() == null || line.detail().isBlank()
                        ? "" : "<br><span style='color:#8a90a6'>" + esc(line.detail()) + "</span>")
                + "</body></html>");
        value.setFont(Theme.font(11));
        value.setForeground(Theme.TEXT_MUTED);
        p.add(value, c);
        return p;
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
        bottom.add(small("只记状态流转与错误，不记转写内容。文件：" + AppPaths.logDir()),
                BorderLayout.CENTER);
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

    // ==================== 自检 ====================

    private JComponent buildSelfCheck() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBackground(Theme.BG);
        p.setBorder(new EmptyBorder(14, 16, 14, 16));

        selfCheckArea.setEditable(false);
        selfCheckArea.setFont(Theme.font(11));
        selfCheckArea.setBackground(new Color(18, 20, 27));
        selfCheckArea.setForeground(new Color(200, 210, 230));
        selfCheckArea.setBorder(new EmptyBorder(8, 10, 8, 10));
        p.add(new JScrollPane(selfCheckArea), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        bottom.setBackground(Theme.BG);
        bottom.add(small("把这段结果贴给开发者即可定位环境问题（不含转写内容）"),
                BorderLayout.CENTER);
        bottom.add(button("重新自检", e -> selfCheckArea.setText(host.diagnosticsReport())),
                BorderLayout.EAST);
        p.add(bottom, BorderLayout.SOUTH);
        return p;
    }

    /** 供 App 在窗口可见时填充自检结果。 */
    public void refreshSelfCheck() {
        selfCheckArea.setText(host.diagnosticsReport());
    }

    // ==================== 零件 ====================

    private JComponent buildFooter() {
        JPanel p = new JPanel(new BorderLayout(12, 0));
        p.setBackground(Theme.BG);
        p.setBorder(new EmptyBorder(8, 16, 12, 16));
        p.add(small("这一页只读，不改变任何行为；配置在「设置…」里改。"), BorderLayout.CENTER);
        p.add(button("关闭", e -> setVisible(false)), BorderLayout.EAST);
        return p;
    }

    private JLabel small(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(11));
        l.setForeground(Theme.DIM);
        return l;
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

    private JComponent scroll(JComponent content) {
        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getViewport().setBackground(Theme.BG);
        sp.setBackground(Theme.BG);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
