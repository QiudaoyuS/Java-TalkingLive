package talkinglive;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.border.EmptyBorder;
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

/**
 * 浮窗预览条 —— 说话时贴在光标附近的实时文字预览。
 *
 * <p>这是产品的脸。三个必须遵守的点：
 * <ul>
 *   <li><b>不抢焦点</b>：{@code setFocusableWindowState(false)} 与
 *       {@code setAutoRequestFocus(false)} 缺一不可，否则浮窗弹出会让
 *       前台程序失去焦点，文字根本注不进去</li>
 *   <li>窗口变化事件里必须忽略它自身的句柄，否则会被当成「用户切窗口」</li>
 *   <li>贴边要翻转，别把字挤出屏幕</li>
 * </ul>
 */
class PreviewBar extends JWindow {

    private final JLabel statusLabel = new JLabel();
    private final JLabel textLabel = new JLabel();
    private JComponent anchor;

    PreviewBar(Window owner) {
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
                g2.setColor(new Color(22, 25, 34, 242));
                g2.fill(new RoundRectangle2D.Float(
                        0, 0, getWidth() - 1, getHeight() - 1, 18, 18));
                g2.setColor(new Color(96, 108, 140, 130));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(
                        0.5f, 0.5f, getWidth() - 2, getHeight() - 2, 18, 18));
                g2.dispose();
            }
        };
        root.setOpaque(false);
        root.setBorder(new EmptyBorder(10, 15, 13, 15));

        statusLabel.setFont(Theme.font(11));
        statusLabel.setForeground(new Color(132, 140, 168));
        textLabel.setFont(Theme.font(15));

        root.add(statusLabel, BorderLayout.NORTH);
        root.add(textLabel, BorderLayout.CENTER);
        setContentPane(root);
    }

    /** 演示用：让浮窗贴在模拟的「目标程序输入框」下方。真实产品跟随系统光标。 */
    void setAnchor(JComponent anchor) {
        this.anchor = anchor;
    }

    /**
     * @param stable  引擎已稳定的前缀（正常色）
     * @param pending 仍在变动、可能被改写（弱化色）
     * @param status  状态行
     */
    void render(String stable, String pending, String status) {
        statusLabel.setText(status);

        boolean hasStable = stable != null && !stable.isEmpty();
        boolean hasPending = pending != null && !pending.isEmpty();

        StringBuilder html = new StringBuilder();
        html.append("<html><body style='width:340px'>");
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
        if (getWidth() < 380) {
            setSize(380, getHeight());
        }
        reposition();
        if (!isVisible()) {
            setVisible(true);
        }
    }

    void hideBar() {
        setVisible(false);
    }

    private void reposition() {
        if (anchor == null || !anchor.isShowing()) {
            setLocationRelativeTo(null);
            return;
        }
        Point p = anchor.getLocationOnScreen();
        Rectangle screen = getGraphicsConfiguration().getBounds();

        int x = p.x + 24;
        int y = p.y + anchor.getHeight() + 10;
        if (x + getWidth() > screen.x + screen.width) {
            x = screen.x + screen.width - getWidth() - 16;
        }
        if (y + getHeight() > screen.y + screen.height) {
            y = p.y - getHeight() - 10;
        }
        setLocation(x, y);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
