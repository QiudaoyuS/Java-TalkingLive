package com.talkinglive.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 托盘入口的约束测试。
 *
 * <p><b>它来自四轮用户反馈，每一轮都是一次实测教训。</b>完整事实链（改动前务必读完）：
 *
 * <ol>
 *   <li>给原生 {@code MenuItem} {@code setFont(Theme.menuFont)} → <b>无效</b>。
 *       它是 Win32 原生菜单，不认 AWT 的字体设置，中文画成方块。</li>
 *   <li>改用 Swing {@code JPopupMenu}（由 {@code MouseListener} 触发）→
 *       中文菜单没出来，出来的是原生菜单。根因：挂了 {@code PopupMenu} 时
 *       Windows 会**自己**在右键弹它，与 {@code MouseListener} 是两套并行机制。</li>
 *   <li>构造时传 {@code null}（不挂 PopupMenu）→ 原生菜单消失，
 *       但<b>左右键都没反应</b>，连 {@code MouseListener} 里的日志都不出现。
 *       结论：<b>这个环境下没有 PopupMenu 就收不到托盘鼠标事件</b>
 *       （{@code addMouseListener} 本身与 popup 无关，所以限制在原生的 Windows
 *       消息层：托盘图标的鼠标消息随弹出菜单机制一起注册）。</li>
 *   <li>于是最终形态是"两条路并存"：原生 PopupMenu 用<b>中文</b>标签
 *       （保证事件通路；它用系统菜单字体，理论上有中文字形），
 *       同时保留 {@code MouseListener} 驱动自绘的 Swing 菜单
 *       （自绘的中文绝对没问题），两条路都写日志，用真实点击判断哪条真的通。</li>
 * </ol>
 *
 * <p>本测试守住的是这个形态里**不能退化的部分**，而不是某一轮的实现细节 ——
 * 那些细节已经被推翻过三次了。
 */
class TrayMenuTest {

    private static final Path APP_JAVA =
            Path.of("src", "main", "java", "com", "talkinglive", "App.java");

    private static final Path MENU_FACTORY =
            Path.of("src", "main", "java", "com", "talkinglive", "ui", "MenuFactory.java");

    private static final Path FLOATING_BALL =
            Path.of("src", "main", "java", "com", "talkinglive", "ui", "FloatingBall.java");

    @Test
    @DisplayName("托盘必须有事件通路：挂上 PopupMenu（传 null 时左右键都收不到事件）")
    void trayKeepsAPopupMenuForEventDelivery() throws IOException {
        String src = Files.readString(APP_JAVA, StandardCharsets.UTF_8);
        List<String> stmts = statements(src, "new TrayIcon(");
        assertTrue(!stmts.isEmpty(), "应当在 App.java 里找到 new TrayIcon(...)");
        for (String stmt : stmts) {
            assertTrue(!stmt.contains(", null)"),
                    """
                            不要把 TrayIcon 的 PopupMenu 传成 null。
                            实测：没有 PopupMenu 时托盘图标的鼠标事件根本送不到 Java 层
                            （左右键都没反应，MouseListener 里的日志一次都不出现），
                            因为托盘图标的鼠标消息是随弹出菜单机制一起注册的。
                            实际语句：
                              """ + stmt);
        }
    }

    @Test
    @DisplayName("托盘的原生菜单项用中文（它走系统菜单字体，与自绘字体无关）")
    void nativeMenuItemsAreChinese() throws IOException {
        // 注意：这里**不再**要求 ASCII。第三轮曾把原生菜单改成英文来规避方块，
        // 但那样用户看到的是英文菜单（"变成英文了"），体验更差；
        // 而且原生菜单用的是系统菜单字体，中文本来就应该正常。
        String src = Files.readString(APP_JAVA, StandardCharsets.UTF_8);
        List<String> labels = new ArrayList<>();
        for (String line : Files.readAllLines(APP_JAVA, StandardCharsets.UTF_8)) {
            String t = line.strip();
            if (t.startsWith("*") || t.startsWith("//")) {
                continue;
            }
            int i = t.indexOf("new MenuItem(\"");
            if (i >= 0) {
                int a = i + "new MenuItem(\"".length();
                int b = t.indexOf('"', a);
                if (b > a) {
                    labels.add(t.substring(a, b));
                }
            }
        }
        assertTrue(!labels.isEmpty(), "托盘应当有原生菜单项");
        for (String label : labels) {
            assertTrue(label.codePoints().anyMatch(cp -> cp > 0x7F),
                    "托盘原生菜单项「" + label + "」应当是中文 —— 用户要求两个菜单都是中文。"
                            + "（曾经为了规避方块把它改成英文，结果用户看到英文菜单，更差）");
        }
        // 顺手确认源码里的字面量确实是"托盘那份"（在同一文件里能找到中文菜单项）
        assertTrue(src.contains("原生菜单与 Swing 菜单同时挂着"),
                "App.java 应当保留说明「两条路并存」的日志，便于用真实点击判断哪条通");
    }

    @Test
    @DisplayName("托盘有自己的 Swing 菜单与宿主，不借用悬浮球")
    void trayMenuUsesItsOwnHostAndInstance() throws IOException {
        String src = Files.readString(APP_JAVA, StandardCharsets.UTF_8);
        assertTrue(src.contains("new TrayMenuAnchor()"),
                "托盘菜单应当有自己的宿主窗口（TrayMenuAnchor），"
                        + "而不是借悬浮球当宿主 —— 借用会让两个菜单不独立");
        assertTrue(src.contains("MenuFactory.showAt(trayMenu, trayMenuAnchor"),
                "托盘应当把**自己的**菜单实例弹在**自己的**锚窗口上");
        assertTrue(src.contains("MenuFactory.build(menus)"),
                "托盘菜单应当由 MenuFactory 构造（文案与悬浮球那份保持同一份定义）");
        int block = src.indexOf("private void showTrayMenu()");
        assertTrue(block > 0, "应当有 showTrayMenu()");
        String body = src.substring(block, Math.min(src.length(), block + 3000));
        assertTrue(!body.contains("ball.showMenuAt"),
                """
                        托盘不应该借用悬浮球当弹窗宿主。用户明确要求两个菜单
                        「相互独立」：共用宿主会让点开一个再点另一个时行为互相影响
                        （实测表现就是"点其他位置时悬浮球菜单自己弹出来"）。
                        """);
    }

    @Test
    @DisplayName("悬浮球菜单也来自 MenuFactory（两处文案不会漂移）")
    void ballMenuComesFromFactory() throws IOException {
        String ball = Files.readString(FLOATING_BALL, StandardCharsets.UTF_8);
        assertTrue(ball.contains("MenuFactory.build(menuActions)"),
                "悬浮球菜单也应当来自 MenuFactory，否则两处文案迟早不一致");
    }

    @Test
    @DisplayName("两个菜单都是中文，文案由 MenuFactory 单点定义")
    void menusAreChineseFromOneDefinition() throws IOException {
        String src = Files.readString(MENU_FACTORY, StandardCharsets.UTF_8);
        for (String label : new String[] {"手动开始", "暂停监听", "恢复监听", "设置", "查看日志", "退出"}) {
            assertTrue(src.contains(label), "MenuFactory 里应当有中文菜单项：" + label);
        }
    }

    @Test
    @DisplayName("菜单字体覆盖中文（自绘菜单的前提）")
    void menuFontCoversChinese() {
        String labels = "手动开始 / 结束听写暂停监听恢复监听设置查看日志退出";
        java.awt.Font f = Theme.menuFont(12);
        int bad = f.canDisplayUpTo(labels);
        if (bad >= 0) {
            fail("菜单字体 " + f.getFamily() + " 缺少字形：'" + labels.charAt(bad)
                    + "' 会画成方块。菜单文案与 Theme.menuFont 必须始终匹配。");
        }
    }

    /**
     * 从源码里取出包含 {@code marker} 的那条语句（把跨行的空白揉成单个空格）。
     *
     * <p>存在的理由：源码扫描断言若按"单行是否包含 A 且 B"来写，遇到换行就会假失败。
     * 我在这上面栽过两次（{@code new TrayIcon(...)} 的最后一个参数在下一行；
     * 以及 {@code isPopupTrigger} 出现在注释里也被算成违规）——
     * 所以这里统一用"整条语句"来判。
     */
    private static List<String> statements(String src, String marker) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while ((i = src.indexOf(marker, i)) >= 0) {
            int end = src.indexOf(';', i);
            if (end < 0) {
                break;
            }
            out.add(src.substring(i, end + 1).replaceAll("\\s+", " "));
            i = end;
        }
        return out;
    }
}
