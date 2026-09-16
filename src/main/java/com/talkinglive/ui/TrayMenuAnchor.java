package com.talkinglive.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
import javax.swing.JWindow;

/**
 * 托盘菜单的宿主窗口 —— 让托盘菜单**独立于悬浮球**存在。
 *
 * <p>它是一个 1×1 的透明、不可见装饰的窗口，只在弹出菜单时被挪到指定位置。
 * 之所以需要它（而不是把菜单挂在悬浮球上）：
 *
 * <ul>
 *   <li><b>独立性</b>：托盘菜单的生命周期与悬浮球解耦。悬浮球被拖到屏幕另一边、
 *       被贴边收起、甚至被隐藏，都不该影响托盘菜单弹在哪里、能不能弹。</li>
 *   <li><b>坐标系简单</b>：托盘鼠标事件给的是托盘图标的局部坐标，直接把这个锚窗口
 *       挪到屏幕坐标处，菜单就"长在托盘那里"，不需要绕经第三方窗口的坐标系
 *       （上一版正是绕了两圈，出错面大）。</li>
 *   <li><b>语义正确</b>：从托盘点的菜单，弹窗属于托盘这条入口。</li>
 * </ul>
 *
 * <p><b>不抢焦点是硬要求</b>（§4.4）：它和悬浮球一样要打上
 * {@code WS_EX_NOACTIVATE}，且必须在**第一次显示之前**设置好 ——
 * 否则弹菜单的一瞬间会把前台窗口从用户正在打字的程序上抢走，
 * 注入就会被 §7 的「前台窗口已变」判定放弃。
 * 这个顺序由 {@code App} 负责（先 {@code addNotify} 再设样式），
 * 与悬浮球、提示条的做法一致。
 */
public final class TrayMenuAnchor extends JWindow {

    /** 锚窗口尺寸：只要是个合法的窗口就行，菜单会自己找位置。 */
    private static final int SIZE = 1;

    public TrayMenuAnchor() {
        super((java.awt.Window) null);
        setFocusableWindowState(false);   // 不参与键盘焦点
        setAutoRequestFocus(false);       // 显示时不请求焦点
        setAlwaysOnTop(true);
        setSize(new Dimension(SIZE, SIZE));

        if (getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(
                java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
            setBackground(new Color(0, 0, 0, 0));
        }
    }

    /**
     * 把锚点挪到屏幕坐标处（菜单会以它为基准定位）。
     *
     * <p>每次弹菜单前都要调：托盘图标本身会随任务栏位置/溢出面板开合而变，
     * 记死一个位置必然错。
     */
    public void moveTo(int screenX, int screenY) {
        setLocation(screenX, screenY);
    }

    /** 便于日志与自检辨认。 */
    @Override
    public String toString() {
        Point p = getLocation();
        return "TrayMenuAnchor@" + p.x + "," + p.y;
    }
}
