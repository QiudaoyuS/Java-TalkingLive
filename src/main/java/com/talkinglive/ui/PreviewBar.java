package com.talkinglive.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.border.EmptyBorder;

/**
 * 浮窗预览条 —— 说话时贴在光标附近的实时文字预览。
 *
 * <p><b>「产品的脸，需要认真打磨」</b>（{@code DESIGN.md} §4.4），也是这个项目
 * 最容易做错、做错则产品直接废掉的部分。三条必须遵守的规则：
 *
 * <ol>
 *   <li><b>不抢焦点。</b>{@code setFocusableWindowState(false)} 与
 *       {@code setAutoRequestFocus(false)} 缺一不可。浮窗一旦抢走前台程序的焦点，
 *       注入就会被 §7 的「前台窗口已变」判定放弃，产品直接失效。
 *       另外 Win32 侧还要 {@code WS_EX_NOACTIVATE}（{@code Win32WindowStyles} 负责），
 *       且**必须在窗口显示之前设置**，否则会先闪一下焦点再还回去。</li>
 *   <li><b>两级文字样式。</b>已稳定部分用实色，仍在变动的尾部用弱化色——
 *       这是 §4.4 明确要求、且 TECH-PLAN §5.2 明确列为「不变」的体验。</li>
 *   <li>屏幕边缘翻转：右侧越界向左翻，下方越界向上翻。</li>
 * </ol>
 */
public class PreviewBar extends JWindow {

    private final JLabel statusLabel = new JLabel();
    private final JLabel textLabel = new JLabel();

    /** 浮窗相对锚点的偏移（逻辑像素）。 */
    private static final int OFFSET_X = 18;
    private static final int OFFSET_Y = 22;

    public PreviewBar(Window owner) {
        super(owner);
        setFocusableWindowState(false);
        setAutoRequestFocus(false);
        setAlwaysOnTop(true);

        if (getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(
                java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
            setBackground(new Color(0, 0, 0, 0));
        }

        JPanel root = new JPanel(new BorderLayout(0, 5)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.SURFACE);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 18, 18));
                g2.setColor(Theme.SURFACE_BORDER);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, getHeight() - 2, 18, 18));
                g2.dispose();
            }
        };
        root.setOpaque(false);
        root.setBorder(new EmptyBorder(10, 15, 13, 15));

        statusLabel.setFont(Theme.font(11));
        statusLabel.setForeground(Theme.TEXT_STATUS);
        textLabel.setFont(Theme.font(15));

        root.add(statusLabel, BorderLayout.NORTH);
        root.add(textLabel, BorderLayout.CENTER);
        setContentPane(root);
    }

    /**
     * 渲染并显示。
     *
     * @param stable  引擎已稳定的前缀（实色）
     * @param pending 仍在变动、可能被改写的尾部（弱化色）
     * @param status  状态行
     * @param anchor  锚点（AWT 逻辑坐标）；{@code x == Integer.MIN_VALUE} 表示居中
     */
    public void render(String stable, String pending, String status, Point anchor) {
        statusLabel.setText(status);

        boolean hasStable = stable != null && !stable.isEmpty();
        boolean hasPending = pending != null && !pending.isEmpty();

        StringBuilder html = new StringBuilder();
        html.append("<html><body style='width:").append(Theme.PREVIEW_TEXT_WIDTH).append("px'>");
        if (hasStable) {
            html.append("<span style='color:#eef1f8'>").append(esc(stable)).append("</span>");
        }
        if (hasPending) {
            html.append("<span style='color:#717890'>").append(esc(pending)).append("</span>");
        }
        if (!hasStable && !hasPending) {
            html.append("<span style='color:#717890'>（等待语音…）</span>");
        }
        html.append("</body></html>");
        textLabel.setText(html.toString());

        pack();
        if (getWidth() < Theme.PREVIEW_MIN_WIDTH) {
            setSize(Theme.PREVIEW_MIN_WIDTH, getHeight());
        }
        reposition(anchor);
        if (!isVisible()) {
            setVisible(true);
        }
    }

    /** 只更新状态行（提交中、精化中等），不动文字。 */
    public void setStatus(String status) {
        statusLabel.setText(status);
    }

    public void hideBar() {
        setVisible(false);
    }

    /**
     * 定位到锚点附近，并做屏幕边缘翻转。
     *
     * <p>翻转规则（§4.4）：右侧越界向左翻，下方越界向上翻。
     */
    private void reposition(Point anchor) {
        if (anchor == null || anchor.x == Integer.MIN_VALUE || anchor.y == Integer.MIN_VALUE) {
            setLocationRelativeTo(null);
            return;
        }
        Rectangle screen = getGraphicsConfiguration().getBounds();

        int x = anchor.x + OFFSET_X;
        int y = anchor.y + OFFSET_Y;
        if (x + getWidth() > screen.x + screen.width) {
            x = Math.max(screen.x + 8, anchor.x - getWidth() - OFFSET_X);   // 右侧越界向左翻
        }
        if (y + getHeight() > screen.y + screen.height) {
            y = Math.max(screen.y + 8, anchor.y - getHeight() - OFFSET_Y);  // 下方越界向上翻
        }
        setLocation(x, y);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 自检用：当前显示的文字。 */
    public String textForTest() {
        return textLabel.getText();
    }

    public String statusForTest() {
        return statusLabel.getText();
    }
}
