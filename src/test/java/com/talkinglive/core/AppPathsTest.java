package com.talkinglive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数据目录**必须和安装目录分家** —— 这是"卸载不会吃掉用户数据"的唯一保证。
 *
 * <p>为什么值得一条测试：jpackage 的 Windows 安装包**按用户安装时默认就装在**
 * {@code %LOCALAPPDATA%\TalkingLive}，而卸载会把那个目录**整棵删掉**。实测过一次：
 * 卸载把 {@code models}（3.4GB）、{@code config.json}、{@code logs} 一起端走了 ——
 * 因为程序和数据住在同一个目录里。
 *
 * <p>所以数据根目录刻意叫 {@code TalkingLiveData}，而不是 {@code TalkingLive}。
 * 一次"顺手把两个名字改成一样"的重构，就会让用户在下一次卸载时丢光模型，而且
 * **没有任何提示**（卸载器报告成功）。理由、证据与复核条件见
 * {@code docs/DECISIONS.md} 的 **D11 修订**。
 */
class AppPathsTest {

    @Test
    @DisplayName("数据根目录不与安装目录同名")
    void dataRootIsNotTheInstallDirectory() {
        String local = System.getenv("LOCALAPPDATA");
        assumeTrue(local != null && !local.isBlank(), "没有 LOCALAPPDATA（非 Windows 环境）时跳过");

        // 本用例断言的是**默认**布局，而测试期的 argLine 会把根目录重定向到 target/test-home
        // （否则 mvnw test 会往用户真实日志里写）。所以这里临时清掉再恢复。
        String saved = System.getProperty(AppPaths.HOME_PROPERTY);
        System.clearProperty(AppPaths.HOME_PROPERTY);
        try {
            Path home = AppPaths.home();
            Path installDir = Paths.get(local, "TalkingLive").toAbsolutePath().normalize();
            assertNotEquals(installDir, home,
                    "数据目录等于安装目录 = 卸载时连数据一起删（实测过一次，3.4GB 模型没了）");
            assertEquals("TalkingLiveData", home.getFileName().toString(),
                    "数据根目录应当是 %LOCALAPPDATA%\\TalkingLiveData —— 改名字前先看 AppPaths.home() 的注释");
        } finally {
            if (saved != null) {
                System.setProperty(AppPaths.HOME_PROPERTY, saved);
            }
        }
    }

    @Test
    @DisplayName("配置 / 日志 / 模型都住在数据根目录下（不会被分散到别处）")
    void everythingLivesUnderHome() {
        Path home = AppPaths.home();
        assertTrue(AppPaths.configFile().startsWith(home), "配置应在数据根目录下：" + AppPaths.configFile());
        assertTrue(AppPaths.logDir().startsWith(home), "日志应在数据根目录下：" + AppPaths.logDir());
        assertTrue(AppPaths.modelsDir().startsWith(home), "模型应在数据根目录下：" + AppPaths.modelsDir());
    }
}
