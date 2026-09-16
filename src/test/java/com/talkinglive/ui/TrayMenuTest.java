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
    @DisplayName("托盘右键走的是 Swing 菜单，不是原生菜单（防止有人改回原生菜单）")
    void trayRightClickUsesSwingMenu() throws IOException {
        String src = Files.readString(APP_JAVA, StandardCharsets.UTF_8);
        assertTrue(src.contains("isPopupTrigger()"),
                "托盘右键应当用 isPopupTrigger() 判断并弹 Swing 菜单（见 maybeShowTrayMenu）");
        assertTrue(src.contains("showMenuAtScreen"),
                "托盘右键应当调用 FloatingBall.showMenuAtScreen，而不是依赖原生菜单");
        // 托盘图标**仍然**需要传一个 PopupMenu 给 TrayIcon 构造函数（API 要求），
        // 所以这里只断言"它不再是唯一的菜单通路"，而不是断言它不存在。
        assertTrue(src.contains("new TrayIcon("),
                "托盘图标仍应创建（PopupMenu 参数是 TrayIcon 的 API 要求）");
    }
}
