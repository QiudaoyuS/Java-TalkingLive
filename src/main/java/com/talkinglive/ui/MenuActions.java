package com.talkinglive.ui;

/**
 * 菜单动作的抽象 —— 让**托盘菜单**与**悬浮球菜单**共用一份文案与行为定义，
 * 同时各自拥有**独立的弹窗实例与宿主窗口**。
 *
 * <p><b>为什么要抽象出来</b>（用户反馈：「我希望托盘右键菜单和悬浮球右键菜单能够
 * 相互独立，并且两个菜单都是中文」）：
 *
 * <p>上一版托盘右键是把菜单弹在**悬浮球窗口**上的（{@code showMenuAtScreen(ball, ...)}）。
 * 那样有三个问题：
 * <ol>
 *   <li><b>不独立。</b>两个入口共用同一个宿主窗口与同一份"当前是否已有菜单在显示"的
 *       状态，于是点开托盘菜单之后再点悬浮球、或反之，行为会互相影响
 *       （实测表现就是"点其他位置时悬浮球菜单自己弹出来"）。</li>
 *   <li><b>坐标系别扭。</b>托盘的事件坐标是托盘图标的局部坐标，要换算成屏幕坐标、
 *       再换算成悬浮球窗口的坐标，绕两圈，出错面大。</li>
 *   <li><b>语义不对。</b>从托盘点的菜单，弹窗却"属于"悬浮球 —— 关掉悬浮球等于
 *       关掉托盘的弹窗宿主。</li>
 * </ol>
 *
 * <p>现在两个入口各自 {@code new} 一个 {@link javax.swing.JPopupMenu}、各自挂在自己的
 * 宿主窗口上（悬浮球挂悬浮球，托盘挂一个贴着托盘位置的极简锚窗口）。
 * 共用的只有**文案与行为**，也就是这个接口 —— 这样"两个菜单内容一致"仍然是
 * 结构上保证的，而不是靠人记得改两处。
 *
 * <p>刻意做成接口而不是直接用 {@code FloatingBall.Listener}：
 * 这个接口的语义是"菜单上能做什么"，与"悬浮球的鼠标交互"是两件事，
 * 混在一起会让 {@code FloatingBall} 的职责继续膨胀。
 */
public interface MenuActions {

    /** 菜单「手动开始 / 结束听写」。 */
    void onManualToggle();

    /** 菜单「暂停监听 / 恢复监听」。 */
    void onTogglePause();

    /** 菜单「设置...」。 */
    void onOpenSettings();

    /** 菜单「查看日志」。 */
    void onOpenLog();

    /** 菜单「退出」。 */
    void onQuit();

    /** 当前是否已暂停 —— 决定前两项的文案。 */
    boolean paused();

    /**
     * 什么都不做的实现。
     *
     * <p>给**诊断入口**用（{@code SelfTest} / {@code ReproClick} / {@code ReproBlock}）：
     * 它们构造悬浮球只是为了验证窗口样式、点击穿透、菜单能否弹出，点菜单项本身没有意义。
     * 与其在三个工具里各写一份匿名空实现，不如在这里给一份。
     */
    static MenuActions noop() {
        return new MenuActions() {
            @Override
            public void onManualToggle() {}

            @Override
            public void onTogglePause() {}

            @Override
            public void onOpenSettings() {}

            @Override
            public void onOpenLog() {}

            @Override
            public void onQuit() {}

            @Override
            public boolean paused() {
                return false;
            }
        };
    }
}
