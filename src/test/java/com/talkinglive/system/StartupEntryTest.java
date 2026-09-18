package com.talkinglive.system;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 开机自启（{@code docs/DECISIONS.md} D4）的测试。
 *
 * <p>三件事必须守住：
 *
 * <ol>
 *   <li><b>启动器内容用 wscript 转交</b>——{@code cmd /c} 或 {@code start /b} 会在开机时
 *       留下一个控制台窗口（1.9 修过这个坑）；写死 javaw+jar 则会丢掉"未构建时回退到
 *       可见控制台"这个行为。</li>
 *   <li><b>能定位到仓库里的启动器</b>——定位失败时必须返回 null（由调用方明确放弃），
 *       而不是写一个指向不存在文件的启动项。</li>
 *   <li><b>安装 / 卸载可逆且幂等</b>——它写的是用户的启动文件夹。
 *       测试刻意用只有测试才会用的文件名（{@value #TEST_FILE}），
 *       所以永远不会碰用户真实的自启项；并且无论断言是否失败，都会在 finally 里删掉。</li>
 * </ol>
 */
class StartupEntryTest {

    /** 只有测试会用到的文件名 —— 绝不碰用户真实的自启项。 */
    private static final String TEST_FILE = "TalkingLive__TestOnly.vbs";

    @Test
    @DisplayName("启动器内容：wscript 转交仓库脚本，且不留控制台窗口")
    void launcherScriptDelegatesToRepoLauncher() {
        String script = StartupEntry.launcherScript(Path.of("C:", "some dir", "TalkingLive.vbs"));
        assertTrue(script.contains("WScript.Shell"), script);
        assertTrue(script.contains("wscript.exe"), script);
        assertTrue(script.contains("C:\\some dir\\TalkingLive.vbs"),
                "必须带上仓库启动器的绝对路径：" + script);
        assertTrue(script.contains(", 0, False"),
                "第二个参数 0 = 不显示窗口（这是不留控制台的关键）：" + script);
        assertTrue(script.startsWith("'"),
                "开头要有注释说明怎么取消，用户打开这个文件时才知道能删：" + script);
    }

    @Test
    @DisplayName("启动文件夹路径来自 APPDATA，且指向 shell:startup 那一处")
    void startupFolderComesFromAppData() {
        assumeTrue(StartupEntry.supported(), "只有 Windows 有启动文件夹");
        Path folder = StartupEntry.startupFolder();
        assertNotNull(folder, "APPDATA 缺失时应当返回 null（由调用方报错），而不是猜一个路径");
        assertTrue(folder.toString().contains("Start Menu"),
                "应当是资源管理器里 {shell:startup} 指向的那一处：" + folder);
        assertTrue(folder.toString().contains("Startup"), folder.toString());
    }

    @Test
    @DisplayName("能从自身位置往上找到仓库根目录下的启动器")
    void resolveLauncherFindsTheScript() {
        Path launcher = StartupEntry.resolveLauncher();
        assertNotNull(launcher,
                "从 target/classes、target/talkinglive.jar 或 target/test-classes 往上找都应当能找到");
        assertTrue(StartupEntry.LAUNCHER.equals(launcher.getFileName().toString()),
                "找到的应当是仓库启动器本身：" + launcher);
        assertTrue(java.nio.file.Files.isRegularFile(launcher), "推断出的路径必须真实存在：" + launcher);
    }

    @Test
    @DisplayName("安装 → 读回 → 卸载 → 幂等（写的是用户的启动文件夹，必须能干净撤回）")
    void installRoundTripIsReversible() throws IOException {
        assumeTrue(StartupEntry.supported(), "只有 Windows 有启动文件夹");
        Path repoLauncher = StartupEntry.resolveLauncher();
        assumeTrue(repoLauncher != null, "找不到仓库启动器时这项无从验证");

        try {
            StartupEntry.uninstall(TEST_FILE);              // 先清干净，避免上次残留干扰
            assertFalse(StartupEntry.isInstalled(TEST_FILE), "测试文件名不该预先存在");
            assertNull(StartupEntry.currentContent(TEST_FILE));

            StartupEntry.install(TEST_FILE, repoLauncher);
            assertTrue(StartupEntry.isInstalled(TEST_FILE), "写入后应当能查到");
            String content = StartupEntry.currentContent(TEST_FILE);
            assertNotNull(content);
            assertTrue(content.contains(repoLauncher.toAbsolutePath().toString()),
                    "内容里应当指向仓库启动器：" + content);

            StartupEntry.uninstall(TEST_FILE);
            assertFalse(StartupEntry.isInstalled(TEST_FILE), "卸载后必须消失");

            StartupEntry.uninstall(TEST_FILE);               // 幂等：再删一次不抛异常
            assertFalse(StartupEntry.isInstalled(TEST_FILE));
        } finally {
            // 无论断言怎么失败，都不许把测试文件留在用户的启动文件夹里
            StartupEntry.uninstall(TEST_FILE);
        }
    }

    @Test
    @DisplayName("卸载不存在的文件不抛异常（幂等）")
    void uninstallMissingIsNoop() throws IOException {
        assumeTrue(StartupEntry.supported(), "只有 Windows 有启动文件夹");
        Files.deleteIfExists(StartupEntry.startupFile(TEST_FILE));
        StartupEntry.uninstall(TEST_FILE);
        assertFalse(StartupEntry.isInstalled(TEST_FILE));
    }
}
