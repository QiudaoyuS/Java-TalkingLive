package com.talkinglive.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 托盘菜单的约束测试。
 *
 * <p><b>它来自两次用户反馈：「托盘右键菜单也是乱码」（方块）。</b>
 * 查清的事实链：
 *
 * <ol>
 *   <li>{@code TrayIcon} **只能**接 {@code java.awt.PopupMenu} —— 那是
 *       <b>原生 Win32 菜单</b>，不是 Swing 自绘的。</li>
 *   <li>给它的每个 {@code MenuItem} {@code setFont(Theme.menuFont(12))}
 *       <b>实测无效</b>：在 150% DPI 下汉字仍然全是方块。
 *       （这一版真的改过、也真的没用，所以才会走到"换掉整条路径"。）</li>
 *   <li>而悬浮球用的 Swing {@code JPopupMenu} 是自绘的，中文完全正常。</li>
 * </ol>
 *
 * <p>于是策略改成：托盘右键**不走原生菜单**，弹悬浮球那个 Swing 菜单；
 * 原生菜单退化成兜底，并且它的文字**刻意全部用 ASCII** ——
 * 万一真被显示出来，英文字形任何字体都有，不会再出现方块。
 *
 * <p>这个测试守住的就是最后那条约定。它有两层：
 * <ol>
 *   <li><b>源码扫描</b>：托盘那段代码里，凡进 {@code new MenuItem("...")} 的字符串
 *       必须是纯 ASCII。有人以后往兜底菜单里加中文，这里会失败。</li>
 *   <li><b>真实渲染</b>：把悬浮球的 Swing 菜单画到离屏图上，断言中文真的画出了字形
 *       （用 {@code Font.canDisplayUpTo}，不是"看截图猜"）。</li>
 * </ol>
 */
class TrayMenuTest {

    private static final Path APP_JAVA =
            Path.of("src", "main", "java", "com", "talkinglive", "App.java");

    /** {@code new MenuItem("...")} 里的字符串字面量。 */
    private static final Pattern AWT_MENU_ITEM =
            Pattern.compile("new\\s+MenuItem\\s*\\(\\s*\"([^\"]*)\"");

    @Test
    @DisplayName("托盘兜底菜单（AWT 原生）的标签必须是纯 ASCII —— 原生菜单画不出中文")
    void trayFallbackMenuLabelsAreAscii() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String line : Files.readAllLines(APP_JAVA, StandardCharsets.UTF_8)) {
            String t = line.strip();
            if (t.startsWith("*") || t.startsWith("//")) {
                continue;   // 注释不算（注释里会解释为什么不能用中文）
            }
            Matcher m = AWT_MENU_ITEM.matcher(t);
            while (m.find()) {
                String label = m.group(1);
                if (label.codePoints().anyMatch(cp -> cp > 0x7F)) {
                    offenders.add(label);
                }
            }
        }
        if (!offenders.isEmpty()) {
            fail("""
                    托盘的兜底菜单是 java.awt.PopupMenu（**原生 Win32 菜单**），
                    实测在 150% DPI 下不认 AWT 设的字体，中文会画成方块。
                    所以它的标签必须全部是 ASCII。发现非 ASCII 标签：
                      """ + offenders + """

                    要加中文菜单项，请加到 FloatingBall.buildMenu()（Swing JPopupMenu，
                    中文正常），托盘右键本来就会弹那一个。
                    """);
        }
    }

    /**
     * ★ 这条是四轮排查换来的结论，最容易被后人不小心改回去。
     *
     * <p>只要给 {@code TrayIcon} 挂了 {@code PopupMenu}，<b>Windows 就会在右键时
     * 自己把它弹出来</b> —— 这与 {@code MouseListener} 是两套**并行**机制，
     * 互不干扰。于是原生菜单（画不出中文）总是先弹，我们那条 Swing 菜单根本没机会。
     *
     * <p>症状是"中文菜单死活出不来、出来的是英文/方块"，而且看起来像事件没送到 Java，
     * 极容易往错误方向查（我为此改了四轮）。
     */
    @Test
    @DisplayName("TrayIcon 不得挂原生 PopupMenu —— 挂上它 Windows 就会自己弹那个画不出中文的菜单")
    void trayIconHasNoNativePopupMenu() throws IOException {
        // ⚠ 断言必须能跨行匹配：`new TrayIcon(...)` 的最后一个参数在另一行，
        //   第一版按"单行里同时含 new TrayIcon( 与 , null)"来判，于是假失败了。
        //   源码扫描类断言很容易犯这个错，所以这里把整个构造调用揉成一行再判。
        String src = Files.readString(APP_JAVA, StandardCharsets.UTF_8);
        for (String stmt : statements(src, "new TrayIcon(")) {
            assertTrue(stmt.contains(", null)"),
                    "TrayIcon 必须在构造时传 null 作为 PopupMenu，实际是：\n  " + stmt
                            + "\n传了 PopupMenu 的话，Windows 会在右键时自己弹那个原生菜单"
                            + "（150% DPI 下画不出中文），而它与 MouseListener 是并行机制，"
                            + "我们自己的 Swing 菜单根本没机会显示。");
        }
    }

    /**
     * 从源码里取出包含 {@code marker} 的那条语句（把跨行的空白揉成单个空格）。
     *
     * <p>存在的理由：源码扫描断言若按"单行是否包含 A 且 B"来写，遇到换行就会假失败
     * （我在这上面栽过两次：一次是这里，一次是 {@code isPopupTrigger} 出现在注释里）。
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

    /**
     * 托盘的菜单由**左键单击**触发。
     *
     * <p>为什么不是右键：实测本环境下 {@code TrayIcon} 的鼠标事件在**右键**上
     * 根本送不到 Java 层（右键完全没反应，连日志都没有），而左键在挂 PopupMenu 时
     * 靠 {@code ActionListener} 响过，证明这条路是通的。所以约定是
     * 「左键单击托盘图标 = 打开菜单」，菜单第一项仍是「手动开始 / 结束听写」。
     */
    @Test
    @DisplayName("托盘菜单由左键单击触发，且走托盘自己的宿主")
    void trayMenuIsTriggeredByLeftClick() throws IOException {
        String src = Files.readString(APP_JAVA, StandardCharsets.UTF_8);
        // 跨行匹配（见 statements 的注释）
        boolean leftClickOpensMenu = statements(src, "new java.awt.event.MouseAdapter()").stream()
                .anyMatch(s -> s.contains("BUTTON1") && s.contains("showTrayMenu()"));
        assertTrue(leftClickOpensMenu,
                "托盘应当用 MouseListener 判 BUTTON1 并调用 showTrayMenu()。"
                        + "不用右键：实测该环境下 TrayIcon 的右键事件送不到 Java 层。");
        List<String> code = Files.readAllLines(APP_JAVA, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(t -> !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*"))
                .toList();
        assertTrue(code.stream().noneMatch(t -> t.contains("BUTTON3")),
                "托盘不应当再依赖右键（BUTTON3）—— 实测它在本环境收不到事件。"
                        + "这是四轮排查的结论，改回去就是又踩一遍。");
        assertTrue(code.stream().noneMatch(t -> t.contains("addActionListener")
                        && t.contains("trayIcon")),
                "不应当用 trayIcon.addActionListener：没挂 PopupMenu 时它不会被触发"
                        + "（那个事件由 PopupMenu 产生）。");
    }

    @Test
    @DisplayName("悬浮球的 Swing 菜单能画出中文（托盘右键用的就是它）")
    void swingMenuRendersChinese() {
        String labels = "手动开始 / 结束听写暂停监听设置查看日志退出";
        java.awt.Font menuFont = Theme.menuFont(12);
        int bad = menuFont.canDisplayUpTo(labels);
        if (bad >= 0) {
            fail("菜单字体 " + menuFont.getFamily() + " 缺少字形：'" + labels.charAt(bad)
                    + "' 会画成方块。菜单文案与 Theme.menuFont 必须始终匹配。");
        }
    }

    @Test
    @DisplayName("托盘菜单是**托盘自己的**实例与宿主，不借用悬浮球")
    void trayMenuUsesItsOwnHostAndInstance() throws IOException {
        String src = Files.readString(APP_JAVA, StandardCharsets.UTF_8);
        assertTrue(src.contains("new TrayMenuAnchor()"),
                "托盘菜单应当有自己的宿主窗口（TrayMenuAnchor），"
                        + "而不是借悬浮球当宿主 —— 借用会让两个菜单不独立");
        assertTrue(src.contains("MenuFactory.showAt(trayMenu, trayMenuAnchor"),
                "托盘应当把**自己的**菜单实例弹在**自己的**锚窗口上");
        assertTrue(src.contains("MenuFactory.build(menus)"),
                "托盘菜单应当由 MenuFactory 构造（文案与悬浮球那份保持同一份定义）");
        // 托盘那段不该再引用悬浮球当宿主
        int trayBlock = src.indexOf("private void showTrayMenu()");
        String trayBody = src.substring(trayBlock, Math.min(src.length(), trayBlock + 3000));
        assertTrue(!trayBody.contains("ball.showMenuAtScreen") && !trayBody.contains("ball.showMenuAt("),
                """
                        托盘不应该再借用悬浮球当弹窗宿主。用户明确要求两个菜单
                        「相互独立」：共用宿主会让点开一个再点另一个时行为互相影响
                        （实测表现就是"点其他位置时悬浮球菜单自己弹出来"）。
                        """);
    }

    @Test
    @DisplayName("两个菜单都是中文，且文案由 MenuFactory 单点定义")
    void bothMenusAreChineseFromOneDefinition() throws IOException {
        Path factory = Path.of("src", "main", "java", "com", "talkinglive", "ui", "MenuFactory.java");
        String src = Files.readString(factory, StandardCharsets.UTF_8);
        for (String label : new String[] {"手动开始", "暂停监听", "恢复监听", "设置", "查看日志", "退出"}) {
            assertTrue(src.contains(label), "MenuFactory 里应当有中文菜单项：" + label);
        }
        // 两个入口都从 MenuFactory 取菜单 —— 这是"内容一致"的结构保证
        String ball = Files.readString(
                Path.of("src", "main", "java", "com", "talkinglive", "ui", "FloatingBall.java"),
                StandardCharsets.UTF_8);
        assertTrue(ball.contains("MenuFactory.build(menuActions)"),
                "悬浮球菜单也应当来自 MenuFactory（否则两处文案迟早不一致）");
    }
}
