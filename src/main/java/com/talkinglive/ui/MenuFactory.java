package com.talkinglive.ui;

import java.awt.Component;
import java.awt.Point;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

/**
 * 菜单工厂 —— 全应用**唯一**定义菜单文案的地方。
 *
 * <p>{@link FloatingBall} 的右键菜单与托盘的右键菜单各自调用 {@link #build} 拿到
 * 一个**全新的、独立的** {@link JPopupMenu} 实例，再各自挂到自己的宿主窗口上。
 *
 * <p>这样安排同时满足两条看似矛盾的要求（用户反馈：「我希望托盘右键菜单和悬浮球
 * 右键菜单能够相互独立，并且两个菜单都是中文」）：
 * <ul>
 *   <li><b>相互独立</b>：两个实例、两个宿主窗口、两份"是否在显示"的状态，
 *       互不影响。关掉悬浮球不影响托盘菜单；点开一个再点另一个不会打架。</li>
 *   <li><b>内容一致</b>：文案与行为只在这里定义一份。若让两个入口各自写菜单，
 *       "两处文案不一致"迟早会发生（之前就出现过托盘菜单是英文、悬浮球是中文）。</li>
 * </ul>
 *
 * <p><b>为什么必须走 Swing {@code JPopupMenu}</b>：{@code TrayIcon} 构造时要求一个
 * {@code java.awt.PopupMenu}，但那是**原生 Win32 菜单**，实测在 150% DPI 下
 * 不认 AWT 设的字体、中文全成方块（给它每个 MenuItem setFont 也无效）。
 * 所以托盘右键改由 {@link TrayMenuAnchor} 弹出这里的 Swing 菜单。
 */
public final class MenuFactory {

    private MenuFactory() {}

    /**
     * 构造一个全新的菜单实例。
     *
     * <p>每次调用都返回**新对象**是刻意的：两个入口各持一份，谁也不会关掉
     * 谁正在显示的菜单。
     */
    public static JPopupMenu build(MenuActions actions) {
        JPopupMenu menu = new JPopupMenu();
        // 菜单字体走 Theme.menuFont：它保证有中文字形（见该方法的注释）。
        // 这里踩过坑：曾经只用 Theme.font，而另一次托盘菜单压根没设字体。
        menu.setFont(Theme.menuFont(12));

        JMenuItem manual = item(actions.paused() ? "手动开始听写（已暂停）" : "手动开始 / 结束听写");
        manual.setEnabled(!actions.paused());
        manual.addActionListener(a -> actions.onManualToggle());
        menu.add(manual);

        menu.addSeparator();

        JMenuItem pause = item(actions.paused() ? "恢复监听" : "暂停监听");
        pause.addActionListener(a -> actions.onTogglePause());
        menu.add(pause);

        menu.addSeparator();

        JMenuItem settings = item("设置...");
        settings.addActionListener(a -> actions.onOpenSettings());
        menu.add(settings);

        JMenuItem logs = item("查看日志");
        logs.addActionListener(a -> actions.onOpenLog());
        menu.add(logs);

        menu.addSeparator();

        JMenuItem quit = item("退出");
        quit.addActionListener(a -> actions.onQuit());
        menu.add(quit);

        return menu;
    }

    /**
     * 在组件的局部坐标系里弹出菜单。
     *
     * @return 弹出的菜单实例；**已经有一个在显示时返回 null**（见下方闸门）
     */
    public static JPopupMenu showAt(JPopupMenu menu, Component invoker, int x, int y) {
        if (menu.isVisible()) {
            return null;   // 闸门：同一个菜单实例不重复弹
        }
        menu.show(invoker, x, y);
        return menu;
    }

    /** 按屏幕坐标弹出（内部换算到 {@code invoker} 的坐标系）。 */
    public static JPopupMenu showAtScreen(JPopupMenu menu, Component invoker,
            int screenX, int screenY) {
        Point p = new Point(screenX, screenY);
        SwingUtilities.convertPointFromScreen(p, invoker);
        return showAt(menu, invoker, p.x, p.y);
    }

    private static JMenuItem item(String text) {
        JMenuItem i = new JMenuItem(text);
        i.setFont(Theme.menuFont(12));
        return i;
    }
}
