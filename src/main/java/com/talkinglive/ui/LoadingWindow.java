package com.talkinglive.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;

/**
 * 启动加载提示 —— 大模型加载期间的**可见等待**。
 *
 * <p><b>为什么必须有它</b>：识别模型（大模型 {@code vosk-model-cn-0.22}）加载要
 * <b>约 21 秒</b>（解压后 2.0GB，{@code vosk_model_new} 要读 533MB 的 {@code HCLG.fst}
 * 与 1.1GB 的 {@code G.carpa}）。而预览与落字现在共用这一个模型，所以它必须在启动时
 * 加载完 —— 否则用户会拿到一段"和最终结果不一样"的预览。
 *
 * <p>于是启动从 3 秒变成约 21 秒。**等待本身不是问题，不知道在等什么才是问题** ——
 * 21 秒里没有任何反馈，用户会以为程序卡死了。所以这里开一个极简窗口说明情况。
 *
 * <p>它刻意做得很轻：不抢焦点、没有按钮、只有一句话（外加一行小字说明为什么慢）。
 * 加载完成或失败后由 {@code App} 负责 {@code dispose}。
 */
public final class LoadingWindow extends JFrame {

    public LoadingWindow(String title, String detail) {
        super("TalkingLive");

        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                new EmptyBorder(18, 24, 18, 24)));

        JLabel head = new JLabel(title, SwingConstants.CENTER);
        head.setFont(Theme.bold(13));
        head.setForeground(Theme.TEXT);
        root.add(head, BorderLayout.CENTER);

        if (detail != null && !detail.isBlank()) {
            JLabel sub = new JLabel("<html><body style='width:260px;text-align:center'>"
                    + detail + "</body></html>", SwingConstants.CENTER);
            sub.setFont(Theme.font(11));
            sub.setForeground(Theme.DIM);
            root.add(sub, BorderLayout.SOUTH);
        }

        setContentPane(root);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setAlwaysOnTop(true);
        setResizable(false);
        pack();
        setMinimumSize(new Dimension(Math.max(getWidth(), 300), getHeight()));
        setLocationRelativeTo(null);
    }

    /** 一次性提示：只显示「在加载」，不假装知道进度。 */
    public static LoadingWindow forModelLoad() {
        return new LoadingWindow("正在加载识别模型…",
                "首次启动需要读取约 2GB 的语音模型，约 20 秒。之后启动会快一些。");
    }

    @Override
    public Color getBackground() {
        return Theme.BG;
    }
}
