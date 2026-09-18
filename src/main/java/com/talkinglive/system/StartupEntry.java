package com.talkinglive.system;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 开机自启（决策见 {@code docs/DECISIONS.md} 的 D4）。
 *
 * <p><b>为什么现在要它</b>：产品定位是"常驻后台的工具"，而它在大模型下**冷启动要 16–18 秒**
 * （实测；且用户已经接受这个代价）。"常驻"的代价本该只付一次，可是不自启就意味着
 * <b>每天第一次用都要等那 18 秒</b> —— 而用户没有"打开它"这个心智动作（没有主窗口，
 * 球也不在），他的结论只会是"它坏了"。
 *
 * <p><b>为什么默认不开</b>：自启是用户的决定，不是软件的默认行为。所以它是一个子命令
 * （{@code --install-startup}），而不是装上就生效的副作用。
 *
 * <h2>为什么用「启动文件夹」而不是注册表 Run 键</h2>
 *
 * <p>这是**实测之后**的选择，不是偏好。在那台开发机上直接写
 * {@code HKCU\...\CurrentVersion\Run} 会被系统拒绝：
 *
 * <pre>
 *   RegOpenKeyEx(Run, KEY_SET_VALUE)      = 0    ← 打开成功
 *   RegSetValueEx(Run, "TalkingLive")     = 5    ← 拒绝访问
 *   RegSetValueEx(HKCU\Software, ...)     = 0    ← 对照：普通键可以写
 *   reg add "HKCU\...\Run" ...            = 成功 ← 但 reg.exe 是微软签名程序
 * </pre>
 *
 * <p>也就是说：<b>Run 键被安全策略保护，未签名的进程（java.exe）写不进去</b>，
 * 而这与代码怎么写无关。于是改用启动文件夹 —— 同一个系统机制（登录时启动、每用户、
 * 无需管理员），但它还多了两个好处：<b>用户看得见那个文件</b>，取消自启就是删掉它。
 *
 * <p>启动器内容只有一句：转交仓库里的 {@code TalkingLive.vbs}（它负责定位目录、并在
 * 未构建时回退到可见的控制台 —— 否则"开机后什么都没有"无法解释）。用 {@code wscript}
 * 跑是为了**不留控制台窗口**（{@code start /b} 做不到，1.9 修过这个坑）。
 */
public final class StartupEntry {

    /** 启动文件夹里的文件名（删掉它即取消开机自启）。 */
    public static final String STARTUP_FILE_NAME = "TalkingLive.vbs";

    /** 启动器脚本名（仓库根目录，被启动文件夹里那个文件转交）。 */
    public static final String LAUNCHER = "TalkingLive.vbs";

    private StartupEntry() {}

    /** 当前平台是否支持（只有 Windows 有启动文件夹）。不支持时返回 false，**不抛异常**。 */
    public static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * 启动文件夹路径：{@code %APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup}。
     *
     * <p>用 {@code APPDATA} 环境变量而不是硬编码盘符：它天然指向**当前用户**的漫游目录，
     * 与资源管理器里 {shell:startup} 指的是同一处（实测过）。
     *
     * @return 目录路径；拿不到 {@code APPDATA} 时返回 null
     */
    public static Path startupFolder() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return null;
        }
        return Paths.get(appData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
    }

    /** 启动文件夹里那个启动器的完整路径；拿不到 {@code APPDATA} 时返回 null。 */
    public static Path startupFile(String fileName) {
        Path folder = startupFolder();
        return folder == null ? null : folder.resolve(fileName);
    }

    /**
     * 找到仓库根目录下的 {@code TalkingLive.vbs}：从自身位置**逐级往上找**（最多 4 层）。
     *
     * <p>为什么不写死"上一级"：程序可能从 {@code target\talkinglive.jar}（上一级是 target、
     * 再上一级才是仓库根）、{@code target\classes}、或测试的 {@code target\test-classes}
     * （多一层）运行，还可能是 jpackage 出来的 app image（那时根本没有这个脚本）。
     * 逐级往上找对这几种情况都成立；找不到就返回 null，由调用方**明确告知并放弃** ——
     * 绝不写一个指向不存在文件的启动项（那会变成"开机后什么都没发生"）。
     *
     * @return 启动器路径；找不到时返回 null
     */
    public static Path resolveLauncher() {
        try {
            Path self = Paths.get(StartupEntry.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(self) ? self : self.getParent();
            for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
                Path vbs = dir.resolve(LAUNCHER);
                if (Files.isRegularFile(vbs)) {
                    return vbs;
                }
            }
        } catch (RuntimeException | java.net.URISyntaxException e) {
            // 拿不到自身位置：当作"找不到启动器"，由调用方提示
        }
        return null;
    }

    /**
     * 启动文件夹里那个启动器的内容（**可单测的纯函数**）。
     *
     * <p>刻意委托给仓库里的启动器而不是直接写 {@code javaw -jar}：仓库那个会处理
     * "还没构建"（那时需要一次可见的构建过程），并且是**已经存在**的启动方式 ——
     * 自启不该另写一套，否则两处会各自演化。
     */
    public static String launcherScript(Path repoLauncher) {
        String target = repoLauncher.toAbsolutePath().toString().replace("\"", "\"\"");
        return "' 由 TalkingLive 的 --install-startup 生成：开机时静默启动它。\n"
                + "' 取消开机自启：删除本文件，或运行 --uninstall-startup。\n"
                + "CreateObject(\"WScript.Shell\").Run \"wscript.exe \"\"\" & \"" + target
                + "\" & \"\"\"\", 0, False\n";
    }

    /**
     * 安装：把启动器写进启动文件夹。
     *
     * <p><b>为什么要"先写临时文件再原子替换 + 重试"</b>（两次实测教训）：
     * <ol>
     *   <li>在启动文件夹里**新建 .vbs** 会触发实时防护扫描，而扫描期间该路径会被短暂锁住 ——
     *       同一操作连跑三次**一次失败两次成功**（失败那次进程多花了 30 秒）。这不是代码错，
     *       但用户会遇到，所以必须自己跨过那个窗口；</li>
     *   <li>万一第一次没写成就留下一个**半个脚本**，而它躺在启动文件夹里意味着
     *       "每次开机弹一个错误框" —— 比不设置自启更糟。原子替换保证不会出现半个文件。</li>
     * </ol>
     *
     * @param fileName     启动文件夹里的文件名（测试用别的名字，避免碰用户真实的自启项）
     * @param repoLauncher 仓库里的 {@code TalkingLive.vbs}
     * @throws IOException 目录不可写、或重试后仍失败
     */
    public static void install(String fileName, Path repoLauncher) throws IOException {
        Path file = startupFile(fileName);
        if (file == null) {
            throw new IOException("拿不到启动文件夹路径（APPDATA 环境变量缺失）");
        }
        Files.createDirectories(file.getParent());
        String content = launcherScript(repoLauncher);
        Path tmp = file.resolveSibling(fileName + ".tmp");
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Files.writeString(tmp, content, StandardCharsets.UTF_8);
                moveReplacing(tmp, file);
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 临时候选文件删不掉不影响正确性：真正的目标文件还没被碰过
                }
                sleepQuietly(200L * attempt);
            }
        }
        throw last;
    }

    /** 卸载：删掉启动器文件。不存在时**什么也不做**（幂等 —— 用户可能连点两次）。 */
    public static void uninstall(String fileName) throws IOException {
        Path file = startupFile(fileName);
        if (file == null) {
            return;
        }
        // 与 install 同理：刚被扫描过的文件可能短暂锁住，给它几次机会
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (IOException e) {
                last = e;
                sleepQuietly(200L * attempt);
            }
        }
        throw last;
    }

    /** 原子替换（不支持时退化为普通替换）。 */
    private static void moveReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 是否已安装（启动文件是否存在）。 */
    public static boolean isInstalled(String fileName) {
        Path file = startupFile(fileName);
        return file != null && Files.isRegularFile(file);
    }

    /** 已安装时启动文件的内容；未安装时返回 null。 */
    public static String currentContent(String fileName) throws IOException {
        Path file = startupFile(fileName);
        return file != null && Files.isRegularFile(file)
                ? Files.readString(file, StandardCharsets.UTF_8)
                : null;
    }
}
