package com.talkinglive.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/**
 * 不抢焦点的提示条 —— {@code DESIGN.md} §7「任何失败都必须可见」的实现方式。
 *
 * <p><b>为什么不能用 {@code JOptionPane}：</b>原来的 {@code showNotice} 弹的是一个
 * 普通对话框，它没有 {@code WS_EX_NOACTIVATE}、也没有 {@code setFocusableWindowState(false)}，
 * 于是它会**成为前台窗口**。而前台窗口一变，窗口监听就判定成"用户切走了"，
 * 状态机随即将本段转入提交并标记"目标已变" —— 结果是：
 * <b>一个提示反过来把用户刚说的一整段话弄丢了</b>。
 * 触发条件只是麦克风抖了一下（"采集中断"提示）。提示条不该有这个副作用。
 *
 * <p>两条硬要求与浮窗预览条一致（§4.4）：{@code setFocusableWindowState(false)} 与
 * {@code setAutoRequestFocus(false)}，并且 {@code WS_EX_NOACTIVATE} 必须在窗口
 * **第一次显示之前**设置（由调用方在 {@code addNotify()} 之后调
 * {@link com.talkinglive.system.Win32WindowStyles#applyNoActivateToolWindow}）。
 * 另外调用方还要把它登记进 {@code ForegroundWatcher.ignore}，
 * 这样即使样式设置失败也不会被当成"用户切窗口"。
 *
 * <p>它在屏幕底部居中显示、几秒后自动消失 —— 不挡悬浮球（那是本产品唯一的入口，
 * 历史上正是"反复弹出的对话框盖住悬浮球"导致用户连点都点不到）。
 */
public final class Toast extends JWindow {

    /** 自动消失时间。够看完一句话，又不会长期占位。 */
    private static final int AUTO_HIDE_MILLIS = 6000;
    /** 正文折行宽度与卡片最大宽度。 */
    private static final int TEXT_WIDTH = 380;
    private static final int MAX_WIDTH = 460;
    /** 离屏幕底部的距离：留出任务栏的位置，且不压住悬浮球常驻的中部区域。 */
    private static final int BOTTOM_MARGIN = 96;

    private final JLabel titleLabel = new JLabel();
    private final JLabel detailLabel = new JLabel();
    private Timer hideTimer;

    public Toast() {
        super((Window) null);
        // 不抢焦点：与 PreviewBar 同样的两条，缺一不可（§4.4）
        setFocusableWindowState(false);
        setAutoRequestFocus(false);
        setAlwaysOnTop(true);

        if (getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(
                java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
            setBackground(new Color(0, 0, 0, 0));
        }

        JPanel root = new JPanel(new BorderLayout(0, 4)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                // 与预览条同一套白色浮层画法：极淡投影 + 白底 + 一圈极浅描边
                g2.setColor(Theme.SHADOW_STRONG);
                g2.fill(new RoundRectangle2D.Float(0, 2, getWidth() - 1, getHeight() - 3, 18, 18));
                g2.setColor(Theme.SURFACE);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 3, 18, 18));
                g2.setColor(Theme.SURFACE_BORDER);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, getHeight() - 4, 18, 18));
                g2.dispose();
            }
        };
        root.setOpaque(false);
        root.setBorder(new EmptyBorder(12, 16, 14, 16));

        titleLabel.setFont(Theme.bold(12));
        titleLabel.setForeground(Theme.TEXT);
        detailLabel.setFont(Theme.font(12));
        detailLabel.setForeground(Theme.TEXT_MUTED);

        root.add(titleLabel, BorderLayout.NORTH);
        root.add(detailLabel, BorderLayout.CENTER);
        setContentPane(root);
    }

    /**
     * 显示一条提示。重复调用会**替换**内容并重新计时，而不是叠出第二个窗口
     * （叠窗口正是原来那套对话框最糟的地方）。
     */
    public void show(String title, String detail) {
        titleLabel.setText(title == null ? "" : title);
        detailLabel.setText(toHtml(detail));
        pack();
        if (getWidth() > MAX_WIDTH) {
            setSize(MAX_WIDTH, getHeight());
        }
        reposition();
        if (!isVisible()) {
            setVisible(true);
        }
        if (hideTimer == null) {
            hideTimer = new Timer(AUTO_HIDE_MILLIS, e -> hideToast());
            hideTimer.setRepeats(false);
        }
        hideTimer.restart();
    }

    public void hideToast() {
        if (hideTimer != null) {
            hideTimer.stop();
        }
        setVisible(false);
    }

    /** 屏幕底部居中；多显示器时用当前所在屏的边界。 */
    private void reposition() {
        Rectangle screen = getGraphicsConfiguration().getBounds();
        int x = screen.x + Math.max(0, (screen.width - getWidth()) / 2);
        int y = screen.y + Math.max(0, screen.height - getHeight() - BOTTOM_MARGIN);
        setLocation(x, y);
    }

    /** 正文按固定宽度折行（JLabel 的单行文本不会自动换行，HTML 会）。 */
    private static String toHtml(String detail) {
        String text = detail == null ? "" : detail;
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
        return "<html><body style='width:" + TEXT_WIDTH + "px'>" + escaped + "</body></html>";
    }

    /** 自检用：当前显示的标题。 */
    public String titleForTest() {
        return titleLabel.getText();
    }

    /** 自检用：当前显示的正文（HTML 形式）。 */
    public String detailForTest() {
        return detailLabel.getText();
    }
}
