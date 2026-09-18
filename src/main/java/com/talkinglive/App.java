package com.talkinglive;

import com.talkinglive.audio.AudioCapture;
import com.talkinglive.audio.SilenceDetector;
import com.talkinglive.core.AppConfig;
import com.talkinglive.core.AppPaths;
import com.talkinglive.core.DictationSession;
import com.talkinglive.core.Logging;
import com.talkinglive.core.StateMachine;
import com.talkinglive.core.StatusLine;
import com.talkinglive.core.WordSuggestions;
import com.talkinglive.engine.SpeechRecognizer;
import com.talkinglive.engine.TextRefiner;
import com.talkinglive.engine.TextRefiners;
import com.talkinglive.engine.VoskKeywordDetector;
import com.talkinglive.engine.VoskModel;
import com.talkinglive.engine.VoskSpeechRecognizer;
import com.talkinglive.engine.WakePhrase;
import com.talkinglive.engine.WakeWordDetector;
import com.talkinglive.system.CaretTracker;
import com.talkinglive.system.DpiScale;
import com.talkinglive.system.EscapeWatcher;
import com.talkinglive.system.ForegroundWatcher;
import com.talkinglive.system.MicValidator;
import com.talkinglive.system.Win32WindowStyles;
import com.talkinglive.system.WindowsTextInjector;
import com.talkinglive.text.CommitPolicy;
import com.talkinglive.text.HotwordCorrector;
import com.talkinglive.text.PreviewText;
import com.talkinglive.text.PunctuationProcessor;
import com.talkinglive.text.TextInjector;
import com.talkinglive.text.TextPostProcessor;
import com.talkinglive.text.TextUtils;
import com.talkinglive.text.WholeSegmentPolicy;
import com.talkinglive.ui.DiagnosticsWindow;
import com.talkinglive.ui.FloatingBall;
import com.talkinglive.ui.LoadingWindow;
import com.talkinglive.ui.PreviewBar;
import com.talkinglive.ui.SettingsWindow;
import com.talkinglive.ui.Theme;
import com.talkinglive.ui.Toast;
import java.awt.EventQueue;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 装配与启动 —— **唯一有 main 的类**（{@code DESIGN.md} §4.5）。
 *
 * <p>本类只做三件事：把各层装起来、把事件接起来、把状态画出来。
 * 所有决策与算法都在 {@code core} / {@code text} / {@code engine} 里，
 * 因此本类不参与单测——它需要的那些逻辑已经被拆出去单测过了。
 *
 * <p>一次听写的完整路径（与 {@link StateMachine.Listener} 的契约一一对应）：
 * <pre>
 *   音频线程 ──▶ AudioCapture ──┬─▶ WakeWordDetector（受限语法，检测唤醒/结束词）
 *                              └─▶ SpeechRecognizer（流式预览）──▶ PreviewText ──▶ PreviewBar
 *   状态机 ──▶ onSegmentEndRequested ──▶ 定稿预览文本 + 取 PCM 快照
 *          ──▶ 后台线程跑 TextRefiner ──▶ REFINE_DONE ──▶ onCommitReady
 *          ──▶ 校验前台窗口 ──▶ SendInput 注入 ──▶ （可选）自动发送 ──▶ INJECTED
 * </pre>
 */
public final class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    // ---- 配置与核心 ----
    private volatile AppConfig config;
    private final StateMachine sm = new StateMachine();
    private final PreviewText preview = new PreviewText();
    private final CommitPolicy commitPolicy = new WholeSegmentPolicy();

    // ---- 引擎 ----
    private VoskModel voskModel;
    /**
     * **识别用**模型 —— 预览、落字、精化**三者共用同一个**。
     *
     * <p>装了大模型时它就是大模型（{@link #loadRecognitionModel} 在启动时同步加载，
     * 约 21 秒）；没装或加载失败时回退到 {@link #voskModel}（小模型）。
     *
     * <p>为什么改成"三者共用"：以前预览用小模型、落字用大模型，于是**预览里看到的
     * 和最终落进去的不是同一句话**，而且是悄悄不一样 —— 实测小模型漏掉「AI」
     * （它的词表里没有任何英文）、把「输入」听成「收入」。实测流式速度两者几乎一致
     * （RTF 0.253 对 0.256），所以共用只增加启动时间，不增加识别时的 CPU。
     *
     * <p>{@link #voskModel} 仍然要单独留着，因为**唤醒词检测只能用它**：
     * 大模型不支持运行时语法（实测 C 层输出
     * {@code Runtime graphs are not supported by this model}）。
     */
    private VoskModel asrModel;
    private WakeWordDetector wakeDetector;
    private SpeechRecognizer recognizer;
    private TextRefiner refiner;
    private String wakeDetectorError;
    private String recognizerError;
    private String modelError;

    // ---- 系统 ----
    /**
     * 文本注入器。
     *
     * <p>在 {@link #start} 里按配置设置字符间隔——微信/QQ 这类自绘输入框灌太快会丢字，
     * 间隔必须能调（见 {@link WindowsTextInjector#setCharGapMillis}）。
     */
    private final WindowsTextInjector injector = new WindowsTextInjector();
    private AudioCapture capture;
    private ForegroundWatcher foreground;
    /**
     * 全局 Esc 监听（§2.2/§2.3 的「任何时刻按 Esc 可取消整段」）。
     *
     * <p>没有它的话，{@code StateMachine.Event.CANCEL} 在生产代码里**没有生产者** ——
     * 悬浮球与浮窗都不抢焦点、收不到键盘事件，也没有任何全局热键。
     * 见 {@link EscapeWatcher} 的类注释。
     */
    private EscapeWatcher escapeWatcher;
    private final SilenceDetector silence =
            new SilenceDetector(AppConfig.DEFAULT_SILENCE_SECONDS);

    // ---- UI ----
    private FloatingBall ball;
    private PreviewBar previewBar;
    private SettingsWindow settings;
    /** 诊断窗口（状态 / 日志 / 自检）。与设置分开：一个只读、一个只写。 */
    private DiagnosticsWindow diagnostics;
    /**
     * 不抢焦点的提示条（替代原来的 {@code JOptionPane} 对话框）。
     *
     * <p>对话框会抢焦点 → 触发「前台窗口已变」→ 用户刚说的一整段被丢弃，
     * 所以这里必须是 NOACTIVATE 的窗口。见 {@link Toast} 的类注释。
     */
    private volatile Toast toast;

    // ---- 运行时状态 ----
    private final AtomicReference<DictationSession> session = new AtomicReference<>();
    private final List<String> notices = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean paused;
    private volatile MicValidator.Result wordCheck;
    /**
     * 上一次配置保存**成功**时想告诉用户的事；null 表示无话可说。
     *
     * <p>目前只有一个来源：唤醒词整体不在词表内、被逐字拆成了单字序列。
     * 它必须与"失败原因"分开走——合在一起会让每次保存都显示"修改被拒绝"，
     * 正好把事情说反（功能是可用的）。
     */
    private volatile MicValidator.Problem wakeNotice;
    private volatile String micError;
    private volatile String lastInjectionError;

    /** 同类提示的去重时间戳（见 {@link #showNotice}）。 */
    private final java.util.Map<String, Long> lastNoticeAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** 单实例锁；null 表示没拿到（已有实例在运行）。 */
    private java.nio.channels.FileChannel singleInstanceLock;

    /**
     * 取单实例锁（对 {@code %LOCALAPPDATA%\TalkingLive\.lock} 加排它锁）。
     *
     * <p>用文件锁而不是「找同名进程」：进程名匹配会被 javaw.exe 包装、
     * 命令行参数不同、以及权限差异干扰，而文件锁由操作系统保证互斥。
     * 进程崩溃时锁会被自动释放，不会留下「永远启动不了」的僵尸状态。
     *
     * @return 持有锁的 channel；已有实例在运行时返回 null
     */
    private static java.nio.channels.FileChannel acquireSingleInstanceLock() {
        try {
            Path lockFile = AppPaths.home().resolve(".lock");
            java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(lockFile,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE);
            java.nio.channels.FileLock lock = ch.tryLock();
            if (lock == null) {
                ch.close();
                return null;
            }
            // 锁对象不单独持有：channel 关闭时锁自动释放
            return ch;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            // 同一个 JVM 里已经拿过（自检场景）
            return null;
        } catch (IOException | RuntimeException e) {
            // 拿不到锁不该阻止启动——宁可多跑一份，也不要因为文件系统问题完全用不了
            log.warn("单实例锁不可用（{}），本次不启用单实例保护：{}",
                    AppPaths.home().resolve(".lock"), e.toString());
            return null;
        }
    }

    /** 同一个标题的提示在这个间隔内只弹一次。 */
    private static final long NOTICE_THROTTLE_MILLIS = 60_000;

    /**
     * 安装 / 卸载开机自启（D4）。
     *
     * <p><b>输出刻意用 ASCII</b>：Windows 控制台默认 GBK，中文会乱码 —— 而乱码不只是难看，
     * 它会**掩盖真正的失败信息**（{@code DESIGN.md} §9.2 踩过的坑）。所以面向控制台的
     * 摘要一律 ASCII，中文说明留在 README 与注释里。
     *
     * <p>机制是**启动文件夹**而不是注册表 Run 键 —— 后者被安全策略保护、未签名进程写不进去
     * （实测证据见 {@link com.talkinglive.system.StartupEntry} 的类注释）。
     */
    private static void runStartupCommand(Options opts) {
        if (!com.talkinglive.system.StartupEntry.supported()) {
            System.out.println("[startup] not supported on this platform (Windows only)");
            return;
        }
        try {
            if (opts.uninstallStartup) {
                com.talkinglive.system.StartupEntry.uninstall(
                        com.talkinglive.system.StartupEntry.STARTUP_FILE_NAME);
                System.out.println("[startup] autostart removed: deleted "
                        + com.talkinglive.system.StartupEntry.startupFile(
                                com.talkinglive.system.StartupEntry.STARTUP_FILE_NAME));
                return;
            }
            Path launcher = com.talkinglive.system.StartupEntry.resolveLauncher();
            if (launcher == null) {
                // 找不到启动器就**什么都不写**：写一个指向不存在文件的启动项
                // 等于"开机后什么都没发生"，比不设置更糟。
                System.out.println("[startup] launcher not found: "
                        + com.talkinglive.system.StartupEntry.LAUNCHER
                        + " (expected next to target/). Nothing was changed.");
                System.out.println("[startup] run this from the project root, e.g.:"
                        + " java -jar target\\talkinglive.jar --install-startup");
                return;
            }
            com.talkinglive.system.StartupEntry.install(
                    com.talkinglive.system.StartupEntry.STARTUP_FILE_NAME, launcher);
            System.out.println("[startup] autostart enabled: wrote "
                    + com.talkinglive.system.StartupEntry.startupFile(
                            com.talkinglive.system.StartupEntry.STARTUP_FILE_NAME));
            System.out.println("[startup] it launches silently at logon via: " + launcher);
            System.out.println("[startup] note: the model still needs 16-18s to load"
                    + " (a loading window is shown)");
            System.out.println("[startup] to undo: java -jar target\\talkinglive.jar --uninstall-startup");
        } catch (IOException | RuntimeException e) {
            System.out.println("[startup] failed: " + e);
        }
    }

    // ------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        // 全局兜底：本程序平时由 javaw 启动（桌面上不留控制台），未捕获异常的栈
        // 会**直接消失**——用户看到的是"它自己不见了"，且日志里什么都没有。
        // §7 的原则是"任何失败都必须可见"，所以至少把栈写进日志文件。
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                log.error("线程「{}」未捕获的异常：{}", thread.getName(), error.toString(), error));

        Options opts = Options.parse(args);
        if (opts.help) {
            Options.printHelp();
            return;
        }
        if (opts.installStartup || opts.uninstallStartup) {
            // 这类命令**不进 start()**：不需要配置、不需要模型、不需要单实例锁，
            // 也不该有任何界面。（自启默认不开 —— 写注册表必须由用户显式要求，见 D4。）
            runStartupCommand(opts);
            return;
        }
        App app = new App();
        try {
            app.start(opts);
        } catch (RuntimeException | IOException e) {
            // 启动阶段的失败必须可见：产品平时没有界面，静默退出的表现是
            // 「双击了没反应」，用户完全无从判断（§7）。
            //
            // ★ 自从启动器改用 javaw 隐藏控制台（用户要求「不要黑窗口」），
            //   这个弹窗从「最好有」变成**唯一**的失败通路 —— 没有它，
            //   javaw 会把异常栈丢进虚空，用户只会看到「双击了没反应」。
            //   所以这里的条件只排除真正在无界面环境跑的情况（--doctor 等）。
            log.error("启动失败：{}", e.toString(), e);
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                app.showFatal("TalkingLive 启动失败", e.getMessage() == null ? e.toString() : e.getMessage());
            }
            if (opts.console || opts.headless) {
                throw e;   // 控制台模式：把栈也打出来，便于排查
            }
            System.exit(1);
        }
    }

    /** 命令行选项。 */
    static final class Options {
        boolean settings;
        boolean headless;
        boolean console;
        boolean help;
        boolean noMicrophone;
        boolean selfCheck;
        boolean micTest;
        /** 允许同时运行多份（默认禁止，见 start() 里的单实例保护）。 */
        boolean allowMultiple;
        String refiner;
        /** 安装 / 卸载开机自启（D4；见 {@link com.talkinglive.system.StartupEntry}）。 */
        boolean installStartup;
        boolean uninstallStartup;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--settings" -> o.settings = true;
                    case "--headless", "--doctor" -> o.headless = true;
                    case "--console" -> o.console = true;
                    case "--help", "-h" -> o.help = true;
                    case "--no-microphone" -> o.noMicrophone = true;
                    case "--self-check" -> o.selfCheck = true;
                    case "--mic-test" -> o.micTest = true;
                    case "--allow-multiple" -> o.allowMultiple = true;
                    case "--install-startup" -> o.installStartup = true;
                    case "--uninstall-startup" -> o.uninstallStartup = true;
                    case "--refiner" -> {
                        if (i + 1 < args.length) {
                            o.refiner = args[++i];
                        }
                    }
                    default -> log.warn("忽略未知参数：{}", args[i]);
                }
            }
            return o;
        }

        static void printHelp() {
            System.out.println("""
                    TalkingLive —— 说出唤醒词，讲的话实时出现在任意应用的光标处

                    用法： java -jar talkinglive.jar [选项]

                      （无）             常驻后台，桌面上只有一颗悬浮球
                      --settings        启动并打开设置窗口
                      --mic-test        麦克风实测：不启动界面，实时打印音量与识别结果
                                        （验证音频链路与唤醒词命中；按 Ctrl+C 结束）
                      --self-check      跑结构化自检后退出（打印 ASCII 摘要，报告写入文件）
                      --doctor          环境自检：模型 / 麦克风 / 词表校验 / 注入能力，然后退出
                      --no-microphone   不打开麦克风（无麦克风环境下试界面用）
                      --refiner <名>    指定精化引擎：auto | vosk-offline | none
                      --allow-multiple  允许同时运行多份（默认禁止，避免多颗悬浮球）
                      --install-startup 设置开机自启（默认不开；写入当前用户的「启动」文件夹）
                      --uninstall-startup 取消开机自启（删掉那个启动器文件）
                      --console         除日志文件外也输出到控制台（默认为真）
                      --help            显示本帮助
                    """);
        }
    }

    // ------------------------------------------------------------ 启动

    private void start(Options opts) throws IOException, InterruptedException {
        com.talkinglive.core.AppPaths.ensureDirectories();
        log.info("=== TalkingLive 启动 ===  home={}", AppPaths.home());

        // ① 单实例保护。
        //    跑起两份的后果很具体：桌面上出现两颗悬浮球、两套前台窗口监听、
        //    两次注入——用户看到的是「一堆球挡着、点哪儿都怪怪的」。
        //    宁可明确拒绝启动第二次，也不要让它变成一件说不清的事。
        if (!opts.allowMultiple) {
            singleInstanceLock = acquireSingleInstanceLock();
            if (singleInstanceLock == null) {
                String msg = "TalkingLive 已经在运行了。\n"
                        + "桌面上应该已经有一颗悬浮球 —— 请用它（左键开始/结束听写，右键菜单）。\n"
                        + "如果找不到它，它可能被拖到屏幕边缘贴边收起了，或者被全屏程序遮住；\n"
                        + "此时请先结束旧的 TalkingLive 进程再启动。\n"
                        + "（确实要同时跑多份，请加 --allow-multiple）";
                log.warn("检测到已有实例在运行，本次启动中止");
                if (!opts.headless && !opts.selfCheck && !opts.micTest) {
                    showFatal("TalkingLive 已经在运行", msg);
                } else {
                    System.out.println("[single-instance] " + msg.replace("\n", " "));
                }
                return;
            }
        }

        // ② 进程级 DPI awareness 必须在任何窗口创建之前（TECH-PLAN §7 第 3 项）
        DpiScale.initProcessAwareness();

        // ② 配置：读取并**强制校验**（§4.3）。非法配置必须拒绝并提示，不能静默用默认值。
        AppPaths.Loaded loaded = AppPaths.loadOrCreateConfig();
        this.config = loaded.config();
        if (loaded.created()) {
            log.info("首次启动，已生成默认配置：{}", AppPaths.configFile());
            notices.add("已生成默认配置文件：" + AppPaths.configFile());
        }
        log.info("配置：wake={} end={} silence={}s autoSend={} sendKey={} maxSegment={}s hotwords={}条",
                config.wakeWord(), config.endWord(), config.silenceSeconds(), config.autoSend(),
                config.sendKey().display(), config.maxSegmentSeconds(), config.hotwordMap().size());

        // ③ 引擎
        //
        // 识别模型（约 2GB 的大模型）要同步加载约 21 秒，所以先开一个"正在加载"的
        // 极简窗口 —— 21 秒里没有任何反馈，用户会以为程序卡死了。
        // 无界面模式（--doctor / --self-check / --mic-test）不加窗口：
        // 它们本来就不加载大模型（见 loadEngines 里的 interactive 判断）。
        boolean showLoading = !opts.headless && !opts.selfCheck && !opts.micTest
                && !java.awt.GraphicsEnvironment.isHeadless();
        if (showLoading) {
            onUi(() -> {
                try {
                    loadingWindow = LoadingWindow.forModelLoad();
                    loadingWindow.setVisible(true);
                } catch (RuntimeException e) {
                    log.warn("加载提示窗口创建失败（不影响启动）：{}", e.toString());
                }
            });
        }
        try {
            loadEngines(opts);
        } finally {
            if (loadingWindow != null) {
                onUi(() -> {
                    loadingWindow.dispose();
                    loadingWindow = null;
                });
            }
        }

        // ④ 系统
        silence.setTimeoutSeconds(config.silenceSeconds());
        injector.setCharGapMillis(config.charGapMillis());
        foreground = new ForegroundWatcher((from, to) -> sm.handle(StateMachine.Event.FOREGROUND_CHANGED));

        // ⑤ 音频
        capture = new AudioCapture();
        capture.addListener(new CaptureBridge());
        if (!opts.noMicrophone) {
            boolean ok = capture.start();
            if (!ok) {
                micError = capture.lastError();
                log.error("麦克风不可用：{}", micError);
                notices.add("麦克风不可用：" + micError + "；程序仍常驻，恢复设备后会自动重连。");
            } else {
                verifyMicrophoneIsLive();
            }
        } else {
            log.info("按参数要求未打开麦克风");
        }

        sm.addListener(new Orchestrator());

        if (opts.micTest) {
            micTest();
            shutdown();
            return;
        }

        if (opts.headless) {
            // --doctor：先做完所有检查再决定要不要碰界面。
            // 注意这里**不启动 UI**：否则无麦克风时的「采集中断」提示对话框会盖住屏幕，
            // 让自检与诊断变得不可用（实测踩过）。
            doctor(opts);
            shutdown();
            return;
        }

        // ⑥ UI
        startUi(opts);

        // ⑦ 全局 Esc → 取消本段。
        //    §2.2/§2.3 承诺「任何时刻按 Esc 可取消整段」，而在此之前
        //    Event.CANCEL 在生产代码里根本没有生产者（见 EscapeWatcher 类注释）。
        //    放在 startUi 之后：这个模式（含 --self-check）才会跑 UI，
        //    而 --doctor / --mic-test 不该去碰键盘状态。
        startEscapeWatcher();

        if (opts.selfCheck) {
            runSelfCheckAndExit();
            return;
        }

        log.info("启动完成：状态={} 麦克风={} 唤醒检测={} 预览={} 精化={}",
                sm.state(), capture.available() ? "就绪" : "不可用",
                wakeDetector != null ? "就绪" : "不可用",
                recognizer != null ? "就绪" : "不可用",
                refiner != null ? refiner.engineName() : "不可用");
    }

    /**
     * 启动时验证麦克风**真的在出音频**，而不只是「打开成功」。
     *
     * <p>为什么需要这一步：{@code DESIGN.md} §7 最糟的体验是「我说了半天它没反应」。
     * 而这句话背后有**两条完全不同的原因**，排查方向也完全不同：
     * <ol>
     *   <li>采音就没进来——麦克风被静音、增益为 0、隐私设置拦住、选错了设备（例如选到了
     *       虚拟声卡）。这时音量恒为 0。</li>
     *   <li>采音正常但识别不对——设备、增益、模型或词表的问题。</li>
     * </ol>
     * 启动后静默观察约 1 秒（用户此时通常还没说话，本底噪声是个很好的判据）：
     * 若一个采样都没读到，或 RMS 恒为 0，就**明确提示**是第 ① 类问题。
     *
     * <p>注意这里只是「有数据在流动」的最低判据，**不能**据此推断用户说话时音量够大——
     * 那要靠 {@code --mic-test} 让用户一边说话一边看音量条。
     */
    private void verifyMicrophoneIsLive() {
        java.util.concurrent.atomic.AtomicInteger frames = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Double> peak =
                new java.util.concurrent.atomic.AtomicReference<>(0.0);
        AudioCapture.Listener probe = new AudioCapture.Listener() {
            @Override
            public void onPcm(byte[] pcm, double rms) {
                frames.incrementAndGet();
                if (rms > peak.get()) {
                    peak.set(rms);
                }
            }
        };
        capture.addListener(probe);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            capture.removeListener(probe);
        }

        int n = frames.get();
        double p = peak.get();
        if (n == 0) {
            micError = "麦克风已打开但一个音频帧都没读到（设备可能被独占或驱动异常）";
            log.error("{}", micError);
            notices.add(micError + "\n请换一个录音设备，或用 --mic-test 实测。");
        } else if (p == 0.0) {
            // 全零样本：典型是「打开了但没接到任何输入」，例如选到了未连接的虚拟设备、
            // 或系统把该设备静音了。这与「本底噪声很低」不同——后者不会是精确的 0。
            micError = "麦克风能读到数据但全部是静音（RMS 恒为 0）——可能选错了设备（例如虚拟声卡）"
                    + "或设备被系统静音";
            log.error("{}", micError);
            notices.add(micError + "\n请检查 Windows 声音设置里的输入设备，或用 --mic-test 实测。");
        } else {
            log.info("麦克风健康检查通过：1 秒内收到 {} 帧，本底噪声 RMS={}（阈值 {}）",
                    n, String.format("%.4f", p), SilenceDetector.DEFAULT_THRESHOLD);
            if (p >= SilenceDetector.DEFAULT_THRESHOLD) {
                // 本底噪声就超过静音阈值：静音兜底会被"噪声"不断重置，
                // 段落可能永远不会因静音而自动结束（只能靠结束词或悬浮球）。
                log.warn("本底噪声 RMS={} 已超过静音阈值 {}，静音自动结束可能不易触发"
                        + "（环境偏吵或麦克风增益偏高）",
                        String.format("%.4f", p), SilenceDetector.DEFAULT_THRESHOLD);
            }
        }
    }

    // ------------------------------------------------------------ 引擎装配

    private void loadEngines(Options opts) {
        Path modelDir = AppPaths.voskModelDir();
        try {
            voskModel = VoskModel.load(modelDir);
        } catch (IOException | RuntimeException e) {
            modelError = e.getMessage();
            log.error("Vosk 模型加载失败：{}", modelError);
            notices.add("Vosk 模型加载失败：" + modelError + "\n获取方式见 docs/DESIGN.md 附录 D。");
            refiner = new TextRefiners.Unavailable("精化", "Vosk 模型不可用");
            return;
        }

        // 词表校验：§4.3 的硬要求。失败即拒绝该配置并提示（附录 C）。
        try {
            wordCheck = MicValidator.validate(voskModel::findWord,
                    wakeEndPairs(config.wakeWord(), config.endWord()),
                    word -> WakePhrase.resolve(voskModel::findWord, word)
                        .map(WakePhrase::tokens).orElse(java.util.List.of()));
            if (!wordCheck.ok()) {
                String msg = wordCheck.message();
                log.error("词表校验失败：{}", msg.replace("\n", " / "));
                notices.add(msg);
            } else {
                log.info("词表校验通过：wake={} end={}", config.wakeWord(), config.endWord());
            }
            // 拆字提示独立于成败：唤醒词被拆成单字序列时 wordCheck.ok() 为 true，
            // 但用户必须知道自己被听成的是哪几个字，否则"说了没反应"无从排查。
            // 走日志而不是 notices —— 它是提示，不该弹一个"出错了"样子的框。
            if (wordCheck.ok() && wordCheck.message() != null) {
                log.info("{}", wordCheck.message().replace("\n", " / "));
            }
        } catch (RuntimeException e) {
            log.error("词表校验无法执行：{}", e.toString());
            notices.add("词表校验无法执行：" + e.getMessage()
                    + "\n这意味着「词表外的词静默失效」无法被拦住，请检查 Vosk 原生库。");
        }

        // ★ 识别模型（预览 + 落字共用同一个）。优先大模型。
        //
        //   为什么预览与落字**必须**用同一个模型：不同模型会给出不同的文本 ——
        //   实测小模型把「人工智能输入测试」听成「人工智能收入测试」，而**小模型
        //   词表里没有任何英文**（A–Z、AI、APP、CPU 全不在表内）从而漏掉「AI」。
        //   两个模型分开时，用户在预览里看到的和最终落进去的不是同一句话，
        //   而且是**悄悄**不一样 —— 这类预期差比功能缺失更让人困惑。
        //
        //   为什么可以共用：实测流式识别速度两者几乎一致 ——
        //   5.2s 音频 RTF 0.253（小）对 0.256（大），13.6s 音频 0.175 对 0.189。
        //   所以共用只增加加载时间，不增加识别时的 CPU。
        //
        //   --doctor / --self-check 是「跑完就退出」的自检，不该顺手把 2GB 的大模型
        //   拉进内存跑一遍 —— 那会让自检多 20 秒、多占 2GB，而它并不测这个。
        boolean interactive = !opts.headless && !opts.selfCheck;
        asrModel = interactive ? loadRecognitionModel() : voskModel;

        // 唤醒 / 结束词检测 —— **仍然只能用那个小模型**。
        // 大模型词表静态、不支持运行时语法（实测 C 层输出
        // "Runtime graphs are not supported by this model"），而唤醒词是用户配的、
        // 必须动态生效。所以这不是保守的选择，是唯一可行的分工。
        try {
            wakeDetector = new VoskKeywordDetector(voskModel, config.wakeWord(), config.endWord(),
                    hit -> onKeywordHit(hit));
            log.info("唤醒检测就绪：{}", wakeDetector.describe());
        } catch (IOException | RuntimeException e) {
            wakeDetectorError = e.getMessage();
            log.error("唤醒检测不可用：{}", wakeDetectorError);
            notices.add("唤醒词检测不可用：" + wakeDetectorError
                    + "\n仍可用悬浮球左键手动开始/结束听写（§2.3 的备用路径）。");
        }

        // 实时预览：与落字同一个模型
        try {
            recognizer = new VoskSpeechRecognizer(asrModelOrFallback(),
                    (kind, text) -> onPreviewText(kind, text));
            log.info("实时预览就绪：{}（识别模型 {}）", recognizer.describe(),
                    asrModel != null && asrModel != voskModel ? "大模型" : "小模型");
        } catch (IOException | RuntimeException e) {
            recognizerError = e.getMessage();
            log.error("实时预览不可用：{}", recognizerError);
            recognizer = null;
        }

        // 精化引擎
        refiner = createRefiner(opts);
        log.info("精化引擎：{}", refiner.describe());

        rebuildPostProcess();
    }

    /**
     * 建立文本后处理链（§3.4）。
     *
     * <p>它的第一职责不是标点（标点由精化引擎的原生能力提供，TECH-PLAN §5.3），
     * 而是**把唤醒词与结束词从正文里剔掉**——TECH-PLAN §6.3 把「段落音频以唤醒词开头，
     * 被转出则正文多出『子曰』」列为需验证的正确性风险，这里做兜底。
     *
     * <p><b>热词纠正放在标点处理之前</b>：先按用户意图改字，再去处理标点。
     * 反过来（先标点后纠正）会让「A I」这类被标点切开的字母串躲过拼合。
     */
    private void rebuildPostProcess() {
        postProcess = new HotwordCorrector(config.hotwordMap())
                .andThen(new PunctuationProcessor(
                        List.of(config.wakeWord(), config.endWord()), true, true));
        if (!config.hotwordMap().isEmpty()) {
            log.info("热词纠正已启用：{} 条（内容不记录，只记条数）", config.hotwordMap().size());
        }
    }

    /**
     * 选择精化引擎。
     *
     * <p>默认 {@code auto}：有 SenseVoice 就用 SenseVoice，否则退回
     * {@link TextRefiners.VoskOffline}（对整段音频做一次离线 Vosk 重跑）。
     * 这一点必须**如实反映在状态页与日志里**——它是 {@code DESIGN.md} §7
     * 允许的降级路径，但不能让用户以为精化是 SenseVoice 做的。
     */
    /**
     * 加载识别用模型（预览与落字共用）：优先大模型，没装或加载失败则回退小模型。
     *
     * <p><b>这里是同步加载，会花约 21 秒。</b>这是刻意的取舍：预览要用它，
     * 而预览在**录音一开始**就需要 —— 没法像以前那样「后台慢慢加载、等精化时才用」。
     * 与其让用户在前 21 秒获得一份「和最终结果不一样」的预览，不如明确等一次。
     *
     * <p>加载期间的可见性由 {@code ui.LoadingWindow} 提供：等待本身不可怕，
     * **不知道为什么在等**才可怕。
     */
    private VoskModel loadRecognitionModel() {
        if (!config.useLargeModel()) {
            // 用户自己关掉了大模型（D1 的出口）。这里**必须说清楚代价**，
            // 而不是安静地用回小模型 —— 否则他会以为"识别变差了是软件的问题"。
            log.info("按配置使用小模型识别（useLargeModel=false）：启动更快、内存更省，准确率较低");
            notices.add("已按配置（useLargeModel=false）使用小模型识别：启动更快、内存更省，"
                    + "但准确率较低，且小模型词表里没有任何英文（AI / PDF 这类词会被漏掉）。\n"
                    + "想改回大模型：把 config.json 的 useLargeModel 设回 true 并重启程序。");
            return voskModel;
        }
        Path dir = AppPaths.asrModelDir();
        boolean hasLarge = !dir.equals(AppPaths.voskModelDir());
        long t0 = System.nanoTime();
        try {
            VoskModel m = VoskModel.load(dir);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (hasLarge) {
                log.info("识别模型：大模型 {}（预览与落字共用，加载 {}ms）", dir, ms);
            } else {
                log.warn("识别模型：**小模型**（CER 17.15%，且词表内没有任何英文，"
                        + "因此英文词会被漏掉）。更准的做法是把 vosk-model-cn-0.22 解压到 {}"
                        + "（CER 7.43%，词表含 AI/APP/CPU 等）", AppPaths.modelsDir());
                // 写进 notices 而不是只记日志：这是**用户能自己解决**的一件事，
                // 而且他抱怨的「识别不准/漏英文」根因就在这里，必须让他看见。
                notices.add("未安装大模型，正在用小模型识别：准确率较低，且会漏掉英文词（如 AI）。\n"
                        + "想要更准：把 vosk-model-cn-0.22 解压到 " + AppPaths.modelsDir() + " 即自动启用。");
            }
            return m;
        } catch (IOException | RuntimeException e) {
            log.error("识别模型加载失败，回退到唤醒用小模型：{}", e.getMessage());
            notices.add("大模型加载失败，已回退到小模型：" + e.getMessage());
            return voskModel;
        }
    }

    /**
     * 精化用的模型：与预览、落字同一个（{@link #asrModel}）。
     *
     * <p>历史上这里会去等一个大模型，因为那时预览用的是小模型。现在三者统一，
     * 这个方法退化成一次取值 —— 保留方法名是为了让调用点读起来仍然表达意图。
     */
    private VoskModel refinerModelOrFallback() {
        return asrModelOrFallback();
    }

    /** 实时预览用模型；未单独配置时就是唤醒用的那个。 */
    private VoskModel asrModelOrFallback() {
        return asrModel != null ? asrModel : voskModel;
    }
    private TextRefiner createRefiner(Options opts) {
        String choice = opts.refiner == null ? "auto" : opts.refiner.trim().toLowerCase();
        return switch (choice) {
            case "none", "off" -> new TextRefiners.Unavailable("精化（已按参数关闭）", "用户以 --refiner none 关闭");
            case "vosk-offline" -> new TextRefiners.VoskOffline(this::refinerModelOrFallback,
                    "Vosk 离线重跑", config.wakeWord());
            default -> {
                if (voskModel == null) {
                    yield new TextRefiners.Unavailable("精化", "Vosk 模型不可用，无法建立兜底精化路径");
                }
                // SenseVoice（sherpa-onnx）当前没有可依赖的 Maven 中央仓 Java 绑定，
                // 因此这里永远是「显式不可用 + 明确降级」，而不是假装成功。
                // 接入时只需在此处返回新的 TextRefiner 实现（TECH-PLAN §5.1 唯一替换点）。
                // 传入唤醒词：精化会先把它的音频段裁掉再识别。
                // 理由见 VoskOffline.refine —— 唤醒词最容易被听错（实测「子曰」→「在」），
                // 而文本层无法可靠区分「被听错的唤醒词」与「正文」，
                // 音频层裁剪才是正解：让精化根本看不到那一段。
                //
                // ★ 传的是**方法引用**而不是现取的模型：大模型要 21.5 秒才加载完，
                //   在这里求值就等于把 21 秒加回启动时间（实测过一次：界面 21 秒不出现）。
                yield new TextRefiners.VoskOffline(this::refinerModelOrFallback,
                        "Vosk 离线重跑", config.wakeWord());
            }
        };
    }

    // ------------------------------------------------------------ 音频分发

    /**
     * 把采集到的 PCM 分发给两路（§4.1「分发」）。
     *
     * <p>与「麦克风只开一路」（§4.3）配套：设备只被 {@code AudioCapture} 打开一次，
     * 唤醒检测与预览识别都在这里喂数据。
     */
    private final class CaptureBridge implements AudioCapture.Listener {

        @Override
        public void onPcm(byte[] pcm, double rms) {
            // 悬浮球的声浪柱（用户要求「跟随收音的声浪大小变化」）。
            // 放在**最前面**且不做任何判断：它反映的是"麦克风此刻听到了多大声"，
            // 与状态机无关 —— 待唤醒时也照常更新，这样球始终是活的。
            // setLevel 只是写一个 volatile 字段，开销可忽略，可以在音频线程上调。
            FloatingBall b = ball;
            if (b != null) {
                b.setLevel(rms);
            }

            // 唤醒/结束词检测：常驻运行，暂停时不喂（「忽略唤醒词与结束词」§2.3）
            WakeWordDetector wd = wakeDetector;
            if (wd != null && !paused) {
                wd.accept(pcm);
            }

            if (!sm.listening()) {
                return;
            }
            DictationSession s = session.get();
            if (s == null) {
                return;
            }

            if (silence.accept(rms, pcm.length / 32000.0)) {
                sm.handle(StateMachine.Event.SILENCE_TIMEOUT);
                return;
            }
            SpeechRecognizer sr = recognizer;
            if (sr != null) {
                sr.accept(pcm);
            }
            if (s.appendPcm(pcm)) {
                log.info("单段达到时长上限 {}s，自动结束本段（§7 防止长录音内存增长）",
                        config.maxSegmentSeconds());
                sm.handle(StateMachine.Event.MAX_SEGMENT_REACHED);
            }
        }

        @Override
        public void onStreamError(String reason) {
            micError = reason;
            notices.add("采集中断：" + reason + "；正在尝试重连…");
            // ★ 必须让状态机知道音频断了。
            //
            //   原先这里只做 UI（悬浮球变暗 + 提示），**不给状态机任何事件** ——
            //   而所有收尾条件（静音超时、单段时长上限）都是在收到 PCM 时才投递的。
            //   音频一断就再没有帧：静音计时冻结、时长上限也永远不到，状态机就停在
            //   LISTENING 不动，悬浮球还显示"听写中 · 静音 N 秒后结束"（倒计时已经冻住）。
            //   用户对着空气说话，且没有任何提示。见 Event.AUDIO_LOST。
            sm.handle(StateMachine.Event.AUDIO_LOST);
            onUi(() -> {
                if (ball != null) {
                    ball.setPaused(true);   // 悬浮球变暗（§7）
                }
                showNotice("采集中断", reason + "\n程序仍在运行，将自动尝试重连。");
            });
        }

        @Override
        public void onStreamRecovered() {
            micError = null;
            notices.add("麦克风已恢复");
            // ★ 这里原来写的是 `if (paused) { paused = false; sm.handle(RESUME); }`，
            //   注释说是"解除采集中断导致的临时暂停"。但采集中断**从来没有设过**
            //   App.paused —— onStreamError 只调了 ball.setPaused(true)，那是个纯视觉标志。
            //   于是这个分支实际命中的是**用户自己按下的暂停**：麦克风一恢复，
            //   程序就替他把暂停解除了，还发了一个用户没要求的 RESUME。
            //   现在把状态收敛成一个来源：视觉跟随用户真实的暂停状态，
            //   也不代替用户做决定（用户暂停着，就该继续暂停）。
            onUi(() -> {
                if (ball != null) {
                    ball.setPaused(paused);
                }
            });
            showNotice("麦克风已恢复", "音频链路已重连。");
        }
    }

    // ------------------------------------------------------------ 事件接线

    private void onKeywordHit(WakeWordDetector.Hit hit) {
        if (paused) {
            return;
        }
        log.info("命中{}词：{}", hit.kind() == WakeWordDetector.Kind.WAKE ? "唤醒" : "结束", hit.word());
        sm.handle(switch (hit.kind()) {
            case WAKE -> StateMachine.Event.WAKE_WORD;
            case END -> StateMachine.Event.END_WORD;
        });
    }

    private void onPreviewText(SpeechRecognizer.Kind kind, String fullText) {
        // 预览也过后处理链：否则预览条里的「A I」与最终落字的「AI」会不一样，
        // 用户会以为字被改了。链里的标点处理对预览是幂等的（预览本来就没标点），
        // 真正起作用的是热词纠正那一层。
        String text = postProcess.process(fullText);
        if (kind == SpeechRecognizer.Kind.FINAL) {
            preview.commitFinal(text);
        } else {
            preview.setPartial(text);
        }
        String stable = preview.committedText();
        String pending = preview.volatileSuffix();
        onUi(() -> {
            if (previewBar == null || !sm.listening()) {
                return;
            }
            if (!preview.hasContent(1)) {
                return;
            }
            previewBar.render(stable, pending, statusLine(), anchorPoint());
            // 浮窗句柄变化要持续同步给窗口监听（§4.3 必须忽略自身句柄）
            foreground.ignore(Win32WindowStyles.hwndOf(previewBar));
        });
    }

    /** 状态机回调 → 各层动作。 */
    private final class Orchestrator implements StateMachine.Listener {

        @Override
        public void onStateChanged(StateMachine.State from, StateMachine.State to,
                StateMachine.Event cause) {
            onUi(() -> {
                if (ball != null) {
                    ball.setState(to);
                }
            });
            if (to == StateMachine.State.IDLE) {
                foreground.setEnabled(true);
            }
        }

        @Override
        public void onSegmentStartRequested() {
            long target = CaretTracker.foregroundWindow();
            DictationSession s = new DictationSession(sm.generation(), target,
                    ForegroundWatcher.title(target), config.maxSegmentSeconds());
            // 记下目标窗口里**有键盘焦点的控件**。提交时若前台已变，注入前需要把焦点
            // 还原到「这个控件」而不只是「这个窗口」——浏览器的地址栏/编辑框都是子窗口，
            // 只切顶层窗口的话文字可能落到窗口本身，用户感受仍是「打不进去」。
            s.setTargetFocus(CaretTracker.focusedControlOf(target));
            session.set(s);
            silence.reset();
            silence.setTimeoutSeconds(config.silenceSeconds());
            preview.reset();
            lastInjectionError = null;

            log.info("段落开始：目标窗口 0x{}（{}），焦点控件 0x{}",
                    Long.toHexString(target), s.targetWindowTitle(),
                    Long.toHexString(s.targetFocus()));

            SpeechRecognizer sr = recognizer;
            if (sr != null) {
                sr.reset();
            }
            foreground.rebase();
            // 段落期间真的发生切换才算数：先在事件层把自身窗口登记为忽略对象（§4.3）
            onUi(() -> {
                if (ball != null) {
                    foreground.ignore(Win32WindowStyles.hwndOf(ball));
                }
                if (previewBar != null) {
                    foreground.ignore(Win32WindowStyles.hwndOf(previewBar));
                }
                if (settings != null && settings.isVisible()) {
                    foreground.ignore(Win32WindowStyles.hwndOf(settings));
                }
            });
            log.info("段落开始：{}，目标窗口 0x{}（{}）", s,
                    Long.toHexString(target), ForegroundWatcher.title(target));

            onUi(() -> {
                if (previewBar == null) {
                    return;
                }
                // 提示语必须跟着"有没有结束词"变：没有结束词时还说「说「」或静音…」
                // 会让用户以为自己漏配了（结束词是可选项，见 AppConfig.validate）。
                previewBar.render("", "", listeningHint(), anchorPoint());
                foreground.ignore(Win32WindowStyles.hwndOf(previewBar));
            });
        }

        /** 听写中的收尾提示；按"有没有结束词"给不同说法。 */
        private String listeningHint() {
            String silence = "静音 " + config.silenceSeconds() + " 秒";
            String end = config.endWord();
            if (end.isBlank()) {
                return "听写中 · " + silence + "后自动结束";
            }
            return "听写中 · 说「" + end + "」或" + silence + "结束";
        }

        /**
         * 段落结束：停止录音、定稿预览文本，并发起**异步**精化。
         *
         * <p>这里刻意只做同步能做完的事（取快照、定稿），精化跑在后台线程上——
         * 因为状态机需要立刻进入 COMMITTING，而精化可能是几百毫秒到几秒。
         */
        @Override
        public void onSegmentEndRequested(StateMachine.EndReason reason) {
            DictationSession s = session.get();
            if (s == null) {
                log.warn("段落结束但没有会话上下文（{}），直接回到待唤醒", reason);
                sm.handle(StateMachine.Event.INJECTED);
                return;
            }
            String previewText;
            if (recognizer != null) {
                previewText = recognizer.finish();
            } else {
                previewText = preview.finish();
            }
            if (previewText == null || previewText.isBlank()) {
                previewText = preview.finish();
            }
            s.setPreviewText(previewText);
            // lambda 只能捕获 effectively-final 的量：定稿后的预览文本存一份 final 副本
            final String summary = s.previewText();
            byte[] pcm = s.pcmSnapshot();
            long generation = s.generation();

            // 进入 COMMITTING 的同时上闹钟：无论后面哪一环卡住，最坏也只是多等一次超时
            startCommitWatchdog(generation);

            log.info("段落结束（{}）：时长 {}s 预览 {}", reason.display(),
                    String.format("%.2f", s.recordedSeconds()),
                    Logging.describeWithFingerprint(summary));

            onUi(() -> {
                if (previewBar != null) {
                    previewBar.setStatus("处理中 · 精化引擎：" + (refiner == null ? "无" : refiner.engineName()));
                }
            });

            Thread worker = new Thread(() -> {
                // ★ 整个线程体必须兜住 Throwable。
                //
                //   这个线程是 Event.REFINE_DONE 的**唯一生产者**：它一旦异常退出，
                //   状态机就永久停在 COMMITTING —— 此后唤醒词、悬浮球、结束词、静音
                //   全被忽略，只能杀进程。而本程序平时由 javaw 启动（没有控制台），
                //   异常连痕迹都留不下。
                //   原来的写法只覆盖了"精化**返回**失败"（走 fallback），覆盖不了
                //   "精化**抛异常**"—— 那条路径上没有任何人投递 REFINE_DONE。
                TextRefiner.Result result;
                try {
                    TextRefiner r = refiner;
                    boolean ending = TextUtils.endsWithSentencePunctuation(summary);
                    if (r == null || !r.available()) {
                        result = TextRefiner.Result.fallback(summary,
                                r == null ? "无" : r.engineName(),
                                r == null ? "未装配精化引擎" : r.unavailableReason());
                    } else {
                        result = r.refine(pcm, summary, ending);
                    }
                } catch (Throwable t) {
                    // 走**既有**的降级路径：退回预览文本落字，而不是把整段话丢掉。
                    log.error("精化过程抛出异常，退回预览文本：{}", t.toString(), t);
                    result = TextRefiner.Result.fallback(summary, "精化异常", t.toString());
                }
                try {
                    // 段落已被取消/替换时，迟到的精化结果直接丢弃（§2.1）
                    if (session.get() == null || session.get().generation() != generation
                            || !sm.committing()) {
                        log.info("精化结果迟到，已丢弃（段落已结束：gen={} 当前状态={}）",
                                generation, sm.state());
                        return;
                    }
                    pendingResult.set(result);
                    sm.handle(StateMachine.Event.REFINE_DONE);
                } catch (Throwable t) {
                    // 真到了这里说明状态机那条路也断了；提交看门狗（COMMIT_TIMEOUT）是最后一层。
                    log.error("投递精化结果时出错（提交看门狗会兜底）：{}", t.toString(), t);
                }
            }, "refine-worker");
            worker.setDaemon(true);
            worker.start();
        }

        @Override
        public void onCommitReady(StateMachine.CommitContext ctx) {
            DictationSession s = session.get();
            TextRefiner.Result result = pendingResult.getAndSet(null);
            if (s == null || result == null) {
                log.warn("提交就绪但缺少上下文或精化结果，跳过注入");
                sm.handle(StateMachine.Event.INJECTED);
                return;
            }

            String text = postProcess.process(s.resolveFinalText(result.text()));
            if (result.refined()) {
                log.info("精化成功（{}）：耗时 {}ms RTF={} 结果 {}",
                        result.engine(), result.millis(),
                        String.format("%.3f", result.rtf(s.recordedSeconds())),
                        Logging.describeWithFingerprint(text));
            } else {
                log.warn("未精化，退回预览文本注入（原因：{}）结果 {}",
                        result.note(), Logging.describeWithFingerprint(text));
            }

            // ★ 链路诊断行：把「预览 / 精化 / 实际注入」三段文本并排打出来。
            //   存在的理由：实测遇到第二段注入的文字里混着上一段句子
            //   （用户说「在进行麦克风测试」，注入「。在进行麦克风测试。」），
            //   只看长度与指纹判断不出「哪一截是旧的」，必须并排看前缀。
            //   前缀默认不打印（遵守「不记转写内容」），
            //   用 -Dtalkinglive.log.text=true 打开。
            log.info("提交链路：gen={} 来源={} 预览[{}] 精化[{}] 注入[{}]",
                    ctx.generation(), s.textSource(result.text()),
                    Logging.stamp(s.previewText()),
                    result.refined() ? Logging.stamp(result.text()) : "（未精化）",
                    Logging.stamp(text));
            if (Boolean.getBoolean("talkinglive.log.text")) {
                log.info("提交内容（诊断模式）：预览『{}』精化『{}』注入『{}』",
                        Logging.prefix(s.previewText(), Logging.DIAG_PREFIX_CODE_POINTS),
                        Logging.prefix(result.text(), Logging.DIAG_PREFIX_CODE_POINTS),
                        Logging.prefix(text, Logging.DIAG_PREFIX_CODE_POINTS));
            }
            maybeHintLostPreviewEnglish(s.previewText(), text);
            if (text.isEmpty()) {
                log.info("本段没有可注入的文本（可能是误触发或只有静音），不注入");
                showNotice("本段没有内容", "没有识别到文字，因此没有注入。若经常如此，请检查麦克风与唤醒词。");
                finishCommit(s);
                return;
            }

            // ★ 这里**曾经**是 `if (!ctx.inject() || !sm.shouldInject()) { 放弃注入 }`。
            //
            //   实测判定这条规则太狠：它把用户刚说的一整段话直接丢掉
            //   （日志里真实出现过「说了 3.32 秒，因为前台变了被丢弃」），
            //   而切窗口往往只是想看一眼别的东西，或者干脆是被通知/输入法候选的
            //   抖动带偏的。状态机那边早就注释成"改为照常提交"了
            //   （StateMachine.onForegroundChanged），但 App 这条分支一直没跟着改 ——
            //   于是"不再丢整段"这个修复只存在于注释和自检里，产品行为仍是丢弃。
            //
            //   现在照常注入：注入器会先尝试把焦点还原回目标窗口
            //   （WindowsTextInjector.restoreForeground），还原不了就注入到当前焦点
            //   并在下面 r.message() 那一段**明确提示** ——
            //   文字最坏是"落在了别的地方、可以剪走"，而不是凭空消失。
            //
            //   唯一仍然要拦住的是**自动发送**：前台已经变了还去按回车，
            //   等于把没写完的消息发给另一个程序。见 doInject 里的 autoSendAllowed。

            // 注入放到独立线程：SendInput 与阻塞式提示都不该占着 EDT
            Thread worker = new Thread(() -> doInject(s, text, ctx), "inject-worker");
            worker.setDaemon(true);
            worker.start();
        }

        @Override
        public void onSegmentAbandoned(String why) {
            cancelCommitWatchdog();     // 本段已经结束，闹钟撤掉
            DictationSession s = session.getAndSet(null);
            preview.reset();
            silence.reset();
            SpeechRecognizer sr = recognizer;
            if (sr != null) {
                // 取消也要把引擎复位，否则残留音频会污染下一段
                sr.reset();
            }
            log.info("放弃本段：{}（会话={}）", why, s);
            onUi(() -> {
                if (previewBar != null) {
                    previewBar.hideBar();
                }
            });
            showNotice("本段已取消", why);
        }
    }

    private final AtomicReference<TextRefiner.Result> pendingResult = new AtomicReference<>();
    private volatile TextPostProcessor postProcess = TextPostProcessor.identity();

    /**
     * 提交看门狗的超时时间。
     *
     * <p>取值依据（都按最坏情况算，宁长勿短）：单段上限 60 秒音频、离线精化 RTF
     * 实测 0.48 上限 → 约 29 秒；注入 1000 字 × 20ms 字符间隔 → 约 20 秒。
     * 合计约 50 秒，取 90 秒留足余量。它不是"快速失败"，而是**最后一道网**：
     * 把"永久 COMMITTING、只能杀进程"变成"最坏多等 90 秒，然后明确告知并恢复"。
     */
    private static final int COMMIT_TIMEOUT_SECONDS = 90;

    /** 提交看门狗线程（守护，单线程）。 */
    private final java.util.concurrent.ScheduledExecutorService commitWatchdog =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "commit-watchdog");
                t.setDaemon(true);
                return t;
            });
    private volatile java.util.concurrent.ScheduledFuture<?> commitTimeoutFuture;

    /**
     * 启动提交看门狗（见 {@link StateMachine.Event#COMMIT_TIMEOUT}）。
     *
     * <p>为什么要绑 {@code generation}：看门狗是异步的，可能和"上一段的看门狗迟到"
     * 或"本段已收尾、下一段又进了 COMMITTING"重叠。带上代数就能精确判断
     * "超时的确实是**当前这一段**"，不会误伤新段落。
     */
    private void startCommitWatchdog(long generation) {
        cancelCommitWatchdog();
        try {
            commitTimeoutFuture = commitWatchdog.schedule(() -> {
                if (sm.committing() && sm.generation() == generation) {
                    log.error("提交超时（{}s）：本段仍未收尾，由看门狗取消并回到待唤醒",
                            COMMIT_TIMEOUT_SECONDS);
                    sm.handle(StateMachine.Event.COMMIT_TIMEOUT);
                }
            }, COMMIT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            log.warn("提交看门狗启动失败（不影响正常提交）：{}", e.toString());
        }
    }

    private void cancelCommitWatchdog() {
        java.util.concurrent.ScheduledFuture<?> f = commitTimeoutFuture;
        commitTimeoutFuture = null;
        if (f != null) {
            f.cancel(false);
        }
    }

    /** 注入 + 自动发送 + 回到 IDLE。在独立线程上执行。 */
    private void doInject(DictationSession s, String text, StateMachine.CommitContext ctx) {
        try {
            CommitPolicy.CommitPlan plan = commitPolicy.plan(s.injectedText(), text);
            TextInjector.Result r;
            if (injector instanceof WindowsTextInjector w) {
                r = w.inject(plan.backspaces(), plan.text(), s.targetWindow(), s.targetFocus());
            } else {
                r = injector.inject(plan.backspaces(), plan.text());
            }

            if (!r.ok()) {
                lastInjectionError = r.message();
                log.error("注入失败：{}", r.message());
                showNotice("注入失败", r.message() + "\n（识别到的内容是：" + abbreviate(text) + "）");
                finishCommit(s);
                return;
            }
            s.appendInjected(plan.text());
            log.info("已注入 {}（目标窗口 0x{}）", Logging.describeWithFingerprint(plan.text()),
                    Long.toHexString(s.targetWindow()));

            // 注入成功但**没打进原本的目标**：文字进了当前焦点所在处。
            // 这种情况必须让用户知道——否则他会以为「又没反应」，而实际上文字
            // 就在别的窗口里等着他剪走。这是「可挽回」与「纯损失」的区别。
            if (r.message() != null && !r.message().isBlank()) {
                lastInjectionError = r.message();
                log.warn("注入到了非目标位置：{}", r.message());
                showNotice("文字没打进原本的目标窗口", r.message()
                        + "\n（识别到的内容是：" + abbreviate(text) + "）");
            }

            // 自动发送的条件收敛成一条纯规则（含"前台已变就不发"），见 StateMachine.autoSendAllowed
            if (config.autoSend() && StateMachine.autoSendAllowed(ctx, config.sendOnSilenceTimeout())) {
                TextInjector.Result pr = injector.press(TextInjector.KeyCombo.fromConfig(config.sendKey()));
                if (!pr.ok()) {
                    log.warn("自动发送失败：{}", pr.message());
                    showNotice("自动发送失败", pr.message());
                }
            } else if (config.autoSend() && !ctx.inject()) {
                // 前台在提交时变了：文字照注入（可能落在别处，已在上方提示），
                // 但**绝不**按发送键 —— 一个回车落在聊天工具里就是"把没写完的消息发出去"。
                log.info("提交时前台窗口已变，已跳过自动发送（避免把发送键打到别的程序）");
            } else if (config.autoSend()) {
                log.info("静音超时结束且未开启「静音超时后发送」，只注入不发送");
            }
            onUi(() -> {
                if (previewBar != null) {
                    previewBar.hideBar();
                }
            });
            finishCommit(s);
        } catch (Throwable e) {
            // 兜 Throwable 而不只是 RuntimeException：注入线程一旦异常退出，
            // 就没人再投递 Event.INJECTED，状态机同样会永久停在 COMMITTING。
            // 无论是 Exception 还是 Error（原生崩溃/OOM），都必须让状态机收尾。
            log.error("注入过程中发生异常：{}", e.toString(), e);
            lastInjectionError = e.toString();
            finishCommit(s);
        }
    }

    /**
     * 自动发送的两条规则（静音超时开关、提交时前台已变则不发送）已移到
     * {@link StateMachine#autoSendAllowed(StateMachine.CommitContext, boolean)} ——
     * 后者需要 {@code CommitContext} 里"前台是否还是目标"那一维，而在 App 里看不见它，
     * 于是"前台变了还按回车"这个真正的风险没有任何地方拦得住。移过去之后它是纯函数，
     * 由 {@code StateMachineTest} 直接钉住。
     */
    private void finishCommit(DictationSession s) {
        cancelCommitWatchdog();     // 本段已收尾，闹钟撤掉（否则会误伤下一段）
        session.compareAndSet(s, null);
        preview.reset();
        silence.reset();
        sm.handle(StateMachine.Event.INJECTED);
    }

    /**
     * 预览丢英文的提示：小模型（流式预览用的那个）的中文词表里**没有任何英文**
     * （实测 A–Z、AI、APP、CPU 全部不在表内），所以预览条里看不到英文词，
     * 而最终落字走大模型 —— 它会补上。
     *
     * <p>为什么要专门提示：不提示的话用户会以为「它没听见我说的 AI」，
     * 而事实是「听见了，只是预览显示不出来」。这类**预期差**比功能缺失更让人困惑，
     * 而且用户没法自己看出来（预览条上没有任何线索）。
     *
     * <p>只在「精化结果里有英文、预览里没有」时提示 —— 也就是确实发生了这件事才说，
     * 平时不占屏幕。
     */
    private void maybeHintLostPreviewEnglish(String previewText, String finalText) {
        String previewStr = previewText == null ? "" : previewText;
        String word = asciiWordOf(finalText);
        if (word.isEmpty()) {
            return;   // 结果里没有英文词，那就没这回事
        }
        // 找不到才提示：大小写可能不同，因此用不区分大小写的包含判断
        if (previewStr.toLowerCase(java.util.Locale.ROOT)
                .contains(word.toLowerCase(java.util.Locale.ROOT))) {
            return;
        }
        log.info("预览丢英文：小模型词表不含英文，落字由大模型补上（词长 {} 字符）", word.length());
        showNotice("预览不含英文词", "小模型的中文词表里没有英文字母，所以预览条显示不出「"
                + word + "」这类词；最终落字由大模型完成，会正常写入。");
    }

    /** 取一段文本里第一段连续的 ASCII 字母数字（长度 ≥2 才算「英文词」）。 */
    private static String asciiWordOf(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[A-Za-z]{2,}[A-Za-z0-9]*").matcher(text == null ? "" : text);
        return m.find() ? m.group() : "";
    }

    // ------------------------------------------------------------ UI

    /** 启动加载提示窗口（大模型加载期间显示，加载完成即销毁）。 */
    private LoadingWindow loadingWindow;

    private void startUi(Options opts) {
        try {
            // Apple 式简洁 + 白色主色调：用 FlatLaf 的**浅色**外观，并把
            // 几个默认值改成更接近系统原生的样子（圆角、极浅描边、
            // 系统蓝作强调色、输入框不画那圈粗焦点环）。
            com.formdev.flatlaf.FlatLightLaf.setup();
            UIManager.put("Component.focusWidth", 0);
            UIManager.put("Component.innerFocusWidth", 0);
            UIManager.put("Component.arc", 8);
            UIManager.put("Button.arc", 8);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("CheckBox.arc", 5);
            UIManager.put("Component.borderColor", new java.awt.Color(0, 0, 0, 30));
            UIManager.put("Component.focusedBorderColor", Theme.ACCENT);
            UIManager.put("Panel.background", Theme.BG);
            UIManager.put("Component.accentColor", Theme.ACCENT);
            UIManager.put("Component.selectionBackground", new java.awt.Color(0, 122, 255, 46));
            UIManager.put("Component.selectionForeground", Theme.TEXT);
            // 滚动条做细：默认那根粗滚动条在白色窗口里非常显眼
            UIManager.put("ScrollBar.width", 10);
            UIManager.put("ScrollBar.thumbArc", 10);
            UIManager.put("ScrollBar.track", Theme.BG);
        } catch (Exception e) {
            log.warn("FlatLaf 不可用，使用默认外观：{}", e.toString());
        }

        onUi(() -> {
            ball = new FloatingBall(null, new BallActions());
            // 不抢焦点必须在窗口**第一次显示之前**设好，否则会先闪一下焦点（§4.4）
            ball.addNotify();
            Win32WindowStyles.applyNoActivateToolWindow(ball);
            ball.setGeometryListener((x, y, dock) -> {
                config.ball().setPosition(x, y);
                config.ball().setDock(dock);
                persistConfig();
            });
            ball.applySavedGeometry(config.ball());
            ball.setState(sm.state());
            ball.setPaused(paused);
            ball.setVisible(true);

            previewBar = new PreviewBar(null);
            previewBar.addNotify();
            Win32WindowStyles.applyNoActivateToolWindow(previewBar);

            settings = new SettingsWindow(new SettingsHost());
            diagnostics = new DiagnosticsWindow(new DiagnosticsHost());


            foreground.start();
            if (ball != null) {
                foreground.ignore(Win32WindowStyles.hwndOf(ball));
            }
            if (previewBar != null) {
                foreground.ignore(Win32WindowStyles.hwndOf(previewBar));
            }

            if (opts.settings) {
                settings.setVisible(true);
                settings.toFront();
                foreground.ignore(Win32WindowStyles.hwndOf(settings));
            }
            if (!notices.isEmpty()) {
                // 启动期攒下的失败必须可见（§7）
                showNotice("TalkingLive 已启动，但有需要注意的事项",
                        String.join("\n\n", notices));
            }
        });
    }

    /**
     * 接线全局 Esc → {@code StateMachine.Event.CANCEL}。
     *
     * <p>接线失败（非 Windows / 原生库不可用）不算致命：产品其余部分照常工作，
     * 只是回到"没有 Esc 取消"的状态 —— 但**必须记进日志**，否则用户会以为
     * 按了没反应是别的原因。
     */
    private void startEscapeWatcher() {
        try {
            escapeWatcher = EscapeWatcher.system(() -> sm.handle(StateMachine.Event.CANCEL));
            escapeWatcher.start();
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            escapeWatcher = null;
            log.warn("Esc 取消无法接线（GetAsyncKeyState 不可用）：{}", e.toString());
        }
    }

    /** 悬浮球的鼠标交互与菜单动作（悬浮球是全应用唯一入口）。 */
    private final class BallActions implements FloatingBall.Listener {

        @Override
        public void onLeftClick() {
            ballLeftClick();
        }

        @Override
        public void onTogglePause() {
            togglePause();
        }

        @Override
        public void onOpenSettings() {
            openSettings();
        }

        @Override
        public void onOpenLog() {
            openDiagnostics(DiagnosticsWindow.TAB_LOG);
        }

        @Override
        public void onResetPosition() {
            onUi(() -> {
                if (ball == null) {
                    return;
                }
                ball.resetToCenter();
                showNotice("已重置悬浮球位置",
                        "球已移到屏幕中央并解除贴边（位置已保存，下次启动仍在中央）。");
            });
        }

        @Override
        public void onHideTemporarily() {
            onUi(() -> {
                if (ball == null) {
                    return;
                }
                ball.hideTemporarily(FloatingBall.HIDE_MILLIS);
                // 必须说清两件事：多久回来、以及**它还在工作** ——
                // 否则用户会以为隐藏等于退出，然后去杀进程（那正好是这个菜单要避免的）。
                showNotice("悬浮球已临时隐藏",
                        Math.round(FloatingBall.HIDE_MILLIS / 60000.0) + " 分钟后自动出现。"
                                + "隐藏的只是界面：唤醒词照常有效，说唤醒词即可开始听写。");
            });
        }

        @Override
        public void onQuit() {
            shutdown();
        }
    }

    private void ballLeftClick() {
        if (ball != null) {
            ball.persistGeometry();
        }
        sm.handle(StateMachine.Event.TOGGLE);
    }

    private void togglePause() {
        boolean pausing = !paused;
        // ★ 在"提交中"按暂停会有一个用户意料之外的结果：本段**仍会落字**。
        //   这是刻意的（见 StateMachine.onRefineDone 的说明：暂停的语义是"别再听新的"，
        //   而不是"把已经录完的段落掐死"—— 后者等于丢话，与切窗口那次修正同一个道理）。
        //   但"我按了暂停，它还是把字打出来了"必须被解释，否则用户会以为按键没生效。
        //   所以这里明确告知，并把真正的取消方式（Esc）告诉他。
        boolean committing = pausing && sm.state() == StateMachine.State.COMMITTING;
        paused = pausing;
        sm.handle(pausing ? StateMachine.Event.PAUSE : StateMachine.Event.RESUME);
        onUi(() -> {
            if (ball != null) {
                ball.setPaused(paused);
            }
        });
        if (committing) {
            showNotice("已暂停监听",
                    "本段已经在提交中，仍会把文字落下来（暂停只影响下一段）。\n"
                            + "如果不想让它落字，按 Esc 取消本段。");
        }
        log.info("监听{}", paused ? "已暂停" : "已恢复");
    }

    /** 打开**设置**窗口（五行，只放日常会改的东西）。 */
    private void openSettings() {
        onUi(() -> {
            if (settings == null) {
                return;
            }
            settings.setVisible(true);
            settings.toFront();
            foreground.ignore(Win32WindowStyles.hwndOf(settings));
        });
    }

    /**
     * 打开**诊断**窗口（状态 / 日志 / 自检）。
     *
     * <p>与设置分开是这次精简的核心：这三页不改变任何行为，只是给人看。
     * 混在设置里既让设置看着像控制面板，也让「想看日志」的人要先进入设置。
     */
    private void openDiagnostics(int tab) {
        onUi(() -> {
            if (diagnostics == null) {
                return;
            }
            if (tab == DiagnosticsWindow.TAB_SELF_CHECK) {
                diagnostics.refreshSelfCheck();
            }
            diagnostics.showTab(tab);
            foreground.ignore(Win32WindowStyles.hwndOf(diagnostics));
        });
    }

    /**
     * 提示条：注入失败等**静默失败必须可见**（§7）。
     *
     * <p><b>必须去重。</b>同一类失败常常是持续的（麦克风被拔掉就会每几秒失败一次），
     * 若每次都弹一个窗口，屏幕上会堆满窗口——实测症状是**窗口盖住了悬浮球，
     * 用户连点都点不到**，等于把唯一入口也弄丢了。因此同一个标题在
     * {@link #NOTICE_THROTTLE_MILLIS} 内只提示一次，其余只进日志。
     *
     * <p><b>必须不抢焦点。</b>这里原先是 {@code JOptionPane} 对话框，它会成为前台窗口，
     * 于是"提示"本身触发了「前台窗口已变」——用户刚说的一整段话因此被丢弃。
     * 现在换成 {@link Toast}（同样不抢焦点、不遮挡悬浮球、自动消失），
     * 并且登记进 {@code ForegroundWatcher} 的忽略名单兜底。见 {@code ui.Toast} 的类注释。
     */
    private void showNotice(String title, String detail) {
        log.info("提示：{} —— {}", title, detail == null ? "" : detail.replace("\n", " / "));

        long now = System.currentTimeMillis();
        Long last = lastNoticeAt.get(title);
        if (last != null && now - last < NOTICE_THROTTLE_MILLIS) {
            log.debug("同类提示在 {}ms 内已出现过，本次只记日志不再弹窗", NOTICE_THROTTLE_MILLIS);
            return;
        }
        lastNoticeAt.put(title, now);

        onUi(() -> {
            // 托盘气泡已随托盘入口一起去掉（2026-09-16）。现在的通路是不抢焦点的提示条：
            // 它没有对话框那两个致命副作用（抢焦点、盖住悬浮球）。
            if (toast == null) {
                toast = new Toast();
                // 不抢焦点必须在窗口**第一次显示之前**设好（§4.4），否则会先闪一下焦点
                toast.addNotify();
                Win32WindowStyles.applyNoActivateToolWindow(toast);
                if (foreground != null) {
                    foreground.ignore(Win32WindowStyles.hwndOf(toast));
                }
            }
            toast.show(title, detail);
        });
    }

    /**
     * 致命错误的对话框（启动期失败、"已经在运行"）。
     *
     * <p>这里**刻意保留真对话框**，与 {@link #showNotice} 的提示条区分开：
     * 它出现的场合是"程序根本起不来"，此时没有悬浮球、没有提示条可用，
     * 而启动器是 {@code javaw}（没有控制台）—— 一个必须被看见、且必须拦住用户的
     * 模态错误框是唯一通路。运行期的提示才需要不抢焦点。
     */
    private void showFatal(String title, String detail) {
        try {
            javax.swing.JOptionPane.showMessageDialog(null, detail, title,
                    javax.swing.JOptionPane.ERROR_MESSAGE);
        } catch (RuntimeException e) {
            System.err.println(title + ": " + detail);
        }
    }

    private void onUi(Runnable r) {
        if (EventQueue.isDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /**
     * 浮窗锚点。
     *
     * <p>降级链：光标 → 目标窗口矩形 → 跟随鼠标（§4.4）。
     * {@code CaretTracker} 已经把 Win32 物理像素换算成 AWT 逻辑像素，
     * 这里不许再乘缩放系数（§4.4 点名的坑）。
     */
    private java.awt.Point anchorPoint() {
        DictationSession s = session.get();
        long target = s == null ? CaretTracker.foregroundWindow() : s.targetWindow();
        CaretTracker.Position p = CaretTracker.locate(target, 0, 0);
        java.awt.Point point = new java.awt.Point(p.x(), p.y());
        if (!p.source().name().equals("NONE")) {
            log.debug("浮窗锚点来源：{}", p.source().display());
        }
        return point;
    }

    private String statusLine() {
        DictationSession s = session.get();
        double remain = silence.remainingSeconds();
        String base = switch (sm.state()) {
            case LISTENING -> "听写中";
            case COMMITTING -> "提交中";
            default -> "待唤醒";
        };
        if (sm.listening() && silence.enabled()) {
            return base + " · 静音 " + String.format("%.0f", Math.max(0, remain)) + " 秒后结束";
        }
        if (s != null && sm.committing()) {
            return base + " · 精化引擎：" + (refiner == null ? "无" : refiner.engineName());
        }
        return base;
    }

    private static String abbreviate(String text) {
        String t = text == null ? "" : text;
        return t.length() <= 80 ? t : t.substring(0, 80) + "…";
    }

    // ------------------------------------------------------------ 配置

    /**
     * 唤醒词/结束词 → 词表校验用的 field -> word 映射。
     *
     * <p>字段名用 {@link WordSuggestions} 的常量而不是就地写字面量：{@code MicValidator}
     * 要靠这个字符串决定"哪些字段允许拆字"，两边各写一遍迟早会不一致
     * （不一致的后果是唤醒词被当成结束词那样严格校验，拆字功能悄悄失效）。
     */
    private static java.util.Map<String, String> wakeEndPairs(String wakeWord, String endWord) {
        java.util.Map<String, String> pairs = new java.util.LinkedHashMap<>();
        pairs.put(WordSuggestions.FIELD_WAKE, wakeWord);
        pairs.put(WordSuggestions.FIELD_END, endWord);
        return pairs;
    }

    /** 由设置窗口调用：校验 → 保存 → 作用于运行中的组件。 */
    private String applyConfig(AppConfig candidate) {
        // 先清掉上一次的提示：不保留的话，换成正常唤醒词后设置窗口仍会显示
        // 上一轮的"已拆开使用"，用户会以为改没生效。
        wakeNotice = null;
        try {
            candidate.validate();
        } catch (AppConfig.ConfigException e) {
            return e.getMessage();
        }
        // 合法但可能意外的组合（例如结束词留空 + 静音也关着）。**只记日志不拦**：
        // 那是用户有权做的选择，拦住就成了新的"限制"。日志会进诊断窗口。
        for (String w : candidate.warnings()) {
            log.warn("配置提醒：{}", w);
        }
        // 词表校验（附录 C）：只有在模型可用时才能查，查不了不阻止保存但会提示。
        if (voskModel != null) {
            try {
                MicValidator.Result r = MicValidator.validate(voskModel::findWord,
                        wakeEndPairs(candidate.wakeWord(), candidate.endWord()),
                        word -> WakePhrase.resolve(voskModel::findWord, word)
                        .map(WakePhrase::tokens).orElse(java.util.List.of()));
                wordCheck = r;
                if (!r.ok()) {
                    return r.message();
                }
                // 唤醒词被逐字拆开时不是错误，但要如实告诉用户拆成了哪几个字。
                // 走 wakeNotice 而不是返回值：返回值非 null 会被当成失败。
                if (!r.spelled().isEmpty()) {
                    wakeNotice = r.spelled().get(0);
                    log.info("{}", wakeNotice.describe());
                }
            } catch (RuntimeException e) {
                log.warn("词表校验无法执行，配置仍被保存：{}", e.toString());
            }
        }
        // 热更新：静音秒数、词表（需要重建识别器）
        //
        // ★ 先把**旧值**取出来再比。原来写的是 `!candidate.wakeWord().equals(config.wakeWord())`，
        //   而设置窗口那时传进来的正是 config 自己（活配置对象），于是这句成了
        //   "同一个对象和自己比" —— 恒为 false，rebuildKeywordDetector() 永远不会被调用。
        //   症状：改完唤醒词，界面说「已保存并立即生效」，实际还在听旧词，必须重启。
        //   现在设置窗口传的是副本（AppConfig.copy()），这里再用旧值快照兜一层：
        //   无论调用方传的是不是同一个对象，判断都成立。
        String oldWakeWord = config.wakeWord();
        String oldEndWord = config.endWord();
        boolean wordsChanged = !candidate.wakeWord().equals(oldWakeWord)
                || !candidate.endWord().equals(oldEndWord);
        this.config = candidate;
        silence.setTimeoutSeconds(candidate.silenceSeconds());
        injector.setCharGapMillis(candidate.charGapMillis());
        if (wordsChanged) {
            rebuildKeywordDetector();
            rebuildPostProcess();
        }
        persistConfig();
        log.info("配置已更新：wake={} end={} silence={}s autoSend={} maxSegment={}s",
                candidate.wakeWord(), candidate.endWord(), candidate.silenceSeconds(),
                candidate.autoSend(), candidate.maxSegmentSeconds());
        return null;
    }

    private void rebuildKeywordDetector() {
        WakeWordDetector old = wakeDetector;
        try {
            wakeDetector = new VoskKeywordDetector(voskModel, config.wakeWord(), config.endWord(),
                    this::onKeywordHit);
            if (old != null) {
                old.close();
            }
            wakeDetectorError = null;
            log.info("唤醒/结束词检测已按新配置重建：{}", wakeDetector.describe());
        } catch (IOException | RuntimeException e) {
            wakeDetectorError = e.getMessage();
            log.error("按新配置重建唤醒检测失败，保留旧的：{}", wakeDetectorError);
            wakeDetector = old;
        }
    }

    private void persistConfig() {
        try {
            AppPaths.saveConfig(config);
        } catch (IOException | RuntimeException e) {
            log.error("保存配置失败：{}", e.toString());
        }
    }

    // ------------------------------------------------------------ 状态与自检

    /** 设置窗口的 Host 实现。只有「读配置 / 写配置 / 查词表」三件事。 */
    private final class SettingsHost implements SettingsWindow.Host {

        @Override
        public AppConfig config() {
            return config;
        }

        @Override
        public String applyConfig(AppConfig candidate) {
            return App.this.applyConfig(candidate);
        }

        @Override
        public MicValidator.Problem lastApplyNotice() {
            return App.this.wakeNotice;
        }

        @Override
        public java.util.List<String> configWarnings() {
            return App.this.config.warnings();
        }

        @Override
        public Boolean wordInVocabulary(String word) {
            return App.this.wordInVocabulary(word);
        }

        @Override
        public Boolean canSpellWord(String word) {
            return App.this.canSpellWord(word);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            onWindowVisibilityChanged(visible);
        }
    }

    /** 诊断窗口的 Host 实现。只读，因此不需要校验/保存那条路径。 */
    private final class DiagnosticsHost implements DiagnosticsWindow.Host {

        @Override
        public List<StatusLine> status() {
            return App.this.statusLines();
        }

        @Override
        public String diagnosticsReport() {
            return SelfTest.run().report();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            onWindowVisibilityChanged(visible);
        }
    }

    /**
     * 两个窗口打开时都要做的事：暂停「切窗口结束段落」判定。
     *
     * <p>它们会真的抢焦点，不暂停的话「一打开设置就把正在录的段落判成用户切走了」。
     * 两个窗口共用一份逻辑，且必须把**两个句柄**都加进忽略名单 ——
     * 否则从设置切到诊断会被当成切换目标应用。
     */
    private void onWindowVisibilityChanged(boolean visible) {
        foreground.setEnabled(!visible);
        if (settings != null && settings.isVisible()) {
            foreground.ignore(Win32WindowStyles.hwndOf(settings));
        }
        if (diagnostics != null && diagnostics.isVisible()) {
            foreground.ignore(Win32WindowStyles.hwndOf(diagnostics));
        }
    }

    /** 词是否在词表内；模型不可用或查询失败时返回 null（界面显示「?」而不是误判）。 */
    private Boolean wordInVocabulary(String word) {
        if (voskModel == null || word == null || word.isBlank()) {
            return voskModel == null ? null : Boolean.TRUE;
        }
        try {
            return voskModel.findWordId(word) >= 0;
        } catch (RuntimeException e) {
            log.warn("词表查询失败：{}", e.toString());
            return null;
        }
    }

    /**
     * 词能否**逐字拆开**使用（整词不在表内、但每个字都在）。
     *
     * <p>这是"英语唤醒词"的判定入口：中文模型永远发不出 {@code Firay}
     * （词表内拉丁 token 为 0 个），但「飞瑞」能——只要每个字单独在表内，
     * 受限语法就能用单字序列拼出这个词表里不存在的发音。
     *
     * @return null 表示模型未就绪、无法判断（界面显示「?」而不是误判为不可用）
     */
    private Boolean canSpellWord(String word) {
        if (voskModel == null) {
            return null;
        }
        if (word == null || word.isBlank()) {
            return Boolean.FALSE;
        }
        try {
            // 必须**真的被拆开**（token 多于一个）才算「可拆」：只有一个 token 说明
            // 整词命中，那属于普通情况，不该在界面上显示成警告。
            return WakePhrase.resolve(voskModel::findWord, word)
                    .map(p -> p.tokens().size() > 1)
                    .orElse(false);
        } catch (RuntimeException e) {
            log.warn("拆字判定失败：{}", e.toString());
            return null;
        }
    }

    /** 各组件状态行，供诊断窗口与 --doctor。 */
    private List<StatusLine> statusLines() {
        List<StatusLine> out = new ArrayList<>();

        out.add(new StatusLine("配置文件",
                AppPaths.configFile().toString(), true, null));

        // 模型
        if (voskModel != null) {
            out.add(new StatusLine("Vosk 模型", "已加载", true, voskModel.path().toString()));
        } else {
            out.add(new StatusLine("Vosk 模型", "缺失或损坏", false,
                    modelError + "  获取方式见 docs/DESIGN.md 附录 D"));
        }

        // 词表校验逐项
        addWordLine(out, "唤醒词", config.wakeWord());
        addWordLine(out, "结束词", config.endWord());

        // 唤醒检测
        out.add(new StatusLine("唤醒/结束词检测",
                wakeDetector != null ? "就绪" : "不可用",
                wakeDetector != null,
                wakeDetector != null ? wakeDetector.describe()
                        : wakeDetectorError + "（仍可用悬浮球左键手动听写）"));

        // 预览
        out.add(new StatusLine("实时预览",
                recognizer != null ? "就绪" : "不可用", recognizer != null,
                recognizer != null ? recognizer.describe() : recognizerError));

        // 识别准确率：这是用户最关心的「说得对不对」的直接来源，必须如实写清楚。
        // 用户反馈「在微信上输入识别有问题」，根因就是这里的模型档位 ——
        // 小模型 CER 17.15%、大模型 7.43%，差一倍以上。
        String asrDetail;
        boolean asrOk;
        boolean usingLarge = asrModel != null && asrModel != voskModel;
        if (usingLarge) {
            asrOk = true;
            asrDetail = "预览与落字共用**大模型**（CER 7.43%，词表含 AI/APP/CPU 等英文）："
                    + asrModel.path()
                    + " —— 代价：常驻工作集约 3.6GB / 私有提交约 4.6GB、启动同步加载 16–18 秒"
                    + "（实测；想省内存可在 config.json 里设 useLargeModel=false 并重启）";
        } else if (!config.useLargeModel()) {
            // 用户自己关掉的：**不能报成红项** —— 那是他的选择，不是故障。
            // 但必须把代价写清楚，否则他只会觉得"这软件识别不准"。
            asrOk = true;
            asrDetail = "**按配置使用小模型**（config.json 的 useLargeModel=false）：启动更快、"
                    + "内存更省，代价是准确率较低（CER 17.15% 对 7.43%），"
                    + "且小模型词表内没有任何英文 —— AI / PDF 这类词会被漏掉。"
                    + "想用回大模型：把该键设回 true 并重启";
        } else {
            asrOk = false;
            asrDetail = "预览与落字都用**小模型**（CER 17.15%，且词表内没有任何英文，"
                    + "所以英文词会被漏掉）。把 vosk-model-cn-0.22 解压到 "
                    + AppPaths.modelsDir()
                    + " 即自动启用大模型（CER 7.43%，准确率翻倍），详见 docs/ENGINE-EXPERIMENT.md";
        }
        out.add(new StatusLine("识别准确率", usingLarge ? "大模型" : "小模型", asrOk, asrDetail));
        out.add(new StatusLine("唤醒词检测",
                wakeDetector != null ? "小模型 · 受限语法" : "不可用", wakeDetector != null,
                "唤醒词必须用支持运行时词表的小模型 —— 大模型不支持运行时语法"
                        + "（实测 C 层输出 Runtime graphs are not supported by this model）"));

        // 精化
        boolean refinerOk = refiner != null && refiner.available();
        out.add(new StatusLine("精化引擎",
                refiner == null ? "未装配" : (refinerOk ? "就绪" : "降级"), refinerOk,
                refiner == null ? null : refiner.describe()
                        + (refinerOk ? "" : " —— 按 §7 会退回预览文本注入，并明确提示")));

        // 麦克风
        boolean micOk = capture != null && capture.available();
        out.add(new StatusLine("麦克风", micOk ? "就绪" : "不可用", micOk,
                capture == null ? null : capture.describe()));

        // 注入
        out.add(new StatusLine("文本注入",
                injector.available() ? "就绪" : "不可用", injector.available(),
                injector.available() ? injector.describe() : injector.unavailableReason()));

        if (lastInjectionError != null) {
            out.add(new StatusLine("最近一次注入失败", "见日志", false, lastInjectionError));
        }
        return out;
    }

    private void addWordLine(List<StatusLine> out, String field, String word) {
        String key = "词表:" + field;
        // 空结束词 = 用户选择不用结束词，是**正常配置**，不是"不在词表内"的故障。
        // 早期这里会把它报成红项，诊断窗口看上去像坏了（实测发现）。
        if (word == null || word.isBlank()) {
            out.add(new StatusLine(key, "未启用", true,
                    "留空表示不用它收尾；段落改由静音超时或切换窗口结束"));
            return;
        }
        if (voskModel == null) {
            out.add(new StatusLine(key, word, false, "模型不可用，无法校验"));
            return;
        }
        try {
            int id = voskModel.findWordId(word);
            boolean ok = id >= 0;
            out.add(new StatusLine(key, word, ok,
                    ok ? "在词表内（wordId=" + id + "）"
                       : "不在词表内 —— Vosk 会静默忽略它，永远识别不到"));
        } catch (RuntimeException e) {
            out.add(new StatusLine(key, word, false, "校验失败：" + e.getMessage()));
        }
    }

    /**
     * 麦克风实测（{@code --mic-test}）：不启动界面，把真实音频链路跑起来并实时打印。
     *
     * <p><b>为什么必须有这个模式。</b>「麦克风能打开」与「唤醒词真的能命中」是两件事，
     * 而后者是整个产品能不能用的分水岭（{@code DESIGN.md} §11 的 M2 验收目标）。
     * 自动化测试做不到「让真人说一句话」，所以这里把链路接到控制台上，让人一边说话
     * 一边看到事实：音量条、Vosk 的实时预览文字、以及唤醒/结束词是否命中。
     *
     * <p>打印的音量条尤其重要：如果说话时音量条不动，说明问题在**采音**（设备/增益/静音），
     * 而不是识别——这是排查「说了没反应」时第一个要分清的岔路。
     */
    private void micTest() throws InterruptedException {
        System.out.println("=== TalkingLive 麦克风实测 ===");
        System.out.println("模型        : " + (voskModel != null ? voskModel.path() : "不可用"));
        System.out.println("唤醒词/结束词: " + config.wakeWord() + " / " + config.endWord());
        System.out.println("精化引擎    : " + (refiner == null ? "无" : refiner.describe()));
        System.out.println();
        if (capture == null || !capture.available()) {
            System.out.println("❌ 麦克风不可用：" + (capture == null ? "未初始化" : capture.lastError()));
            System.out.println("   请检查：是否插了麦克风、Windows 隐私设置是否允许应用访问麦克风、");
            System.out.println("   以及是否被其他程序独占。");
            return;
        }
        System.out.println("麦克风      : " + capture.describe());
        System.out.println();
        System.out.println("请对着麦克风说话。先试连续说「" + config.wakeWord() + "」若干遍。");
        System.out.println("音量条会随声音伸缩；命中唤醒词会打印 [命中 唤醒词]。");
        System.out.println("（15 秒后自动结束；也可 Ctrl+C 提前退出）");
        System.out.println("─".repeat(72));

        java.util.concurrent.atomic.AtomicInteger frames = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Double> peak = new java.util.concurrent.atomic.AtomicReference<>(0.0);
        StringBuilder lastPreview = new StringBuilder();
        java.util.concurrent.atomic.AtomicInteger drawTick = new java.util.concurrent.atomic.AtomicInteger();

        AudioCapture.Listener probe = new AudioCapture.Listener() {
            @Override
            public void onPcm(byte[] pcm, double rms) {
                frames.incrementAndGet();
                if (rms > peak.get()) {
                    peak.set(rms);
                }
                // 每约 10 帧（≈0.4 秒）画一次，避免刷屏
                if (drawTick.incrementAndGet() % 10 == 0) {
                    int bars = (int) Math.min(40, rms * 400);
                    String bar = "█".repeat(Math.max(0, bars));
                    String previewNote = lastPreview.length() == 0 ? "" : "  预览：" + lastPreview;
                    System.out.printf("\r音量 %-40s %.4f%s", bar, rms, previewNote);
                    System.out.flush();
                }
            }

            @Override
            public void onStreamError(String reason) {
                System.out.println();
                System.out.println("⚠ 采集中断：" + reason);
            }

            @Override
            public void onStreamRecovered() {
                System.out.println();
                System.out.println("✓ 麦克风已恢复");
            }

            @Override
            public void onFormat(javax.sound.sampled.AudioFormat actual) {
                System.out.println("设备实际格式：" + (int) actual.getSampleRate() + "Hz/"
                        + actual.getSampleSizeInBits() + "bit/" + actual.getChannels() + "声道"
                        + "  → 转换为 16kHz/16bit/单声道");
            }
        };
        capture.addListener(probe);

        // 唤醒词命中检测由 App 装配时建立的 VoskKeywordDetector 负责，
        // 命中会走 onKeywordHit → 状态机进入 LISTENING。这里不额外建检测器
        // （受限语法的解码图重建不便宜），改为轮询状态机的代数来发现命中。
        if (wakeDetector == null) {
            System.out.println("⚠ 唤醒词检测不可用（" + wakeDetectorError + "），本次只能看音量与预览。");
        }

        // 预览：挂到现有 recognizer 之外再建一个观察用的流式识别器
        VoskModel.Recognizer preview = null;
        if (voskModel != null) {
            try {
                preview = voskModel.createRecognizer(16000.0f);
            } catch (IOException e) {
                System.out.println("⚠ 预览识别器创建失败：" + e.getMessage());
            }
        }
        final VoskModel.Recognizer previewRef = preview;

        // 把采集分发同时接到预览上（麦克风仍只开一路，这里只是加一个消费者）
        AudioCapture.Listener previewTap = new AudioCapture.Listener() {
            @Override
            public void onPcm(byte[] pcm, double rms) {
                if (previewRef == null) {
                    return;
                }
                if (previewRef.accept(pcm, pcm.length)) {
                    String t = previewRef.result();
                    if (!t.isEmpty()) {
                        lastPreview.setLength(0);
                        lastPreview.append(t);
                    }
                } else {
                    String p = previewRef.partialResult();
                    if (!p.isEmpty()) {
                        lastPreview.setLength(0);
                        lastPreview.append(p);
                    }
                }
            }
        };
        capture.addListener(previewTap);

        // 唤醒词命中计数：轮询状态机的代数（命中唤醒词会让代数递增并进入 LISTENING）
        for (int i = 0; i < 150; i++) {   // 150 × 100ms = 15 秒
            Thread.sleep(100);
            if (sm.generation() > hits.get()) {
                hits.set((int) sm.generation());
                System.out.println();
                System.out.println("✓ [命中 唤醒词] 第 " + sm.generation()
                        + " 次唤醒（状态=" + sm.state().display() + "）");
            }
        }

        System.out.println();
        System.out.println("─".repeat(72));
        System.out.println("实测结束：");
        System.out.println("  采集帧数     : " + frames.get());
        System.out.println("  峰值音量 RMS : " + String.format("%.4f", peak.get())
                + (peak.get() < SilenceDetector.DEFAULT_THRESHOLD
                        ? "  ⚠ 低于静音阈值 " + SilenceDetector.DEFAULT_THRESHOLD
                          + " —— 说话时若始终这么低，问题在采音（麦克风静音/增益/选错设备），不在识别"
                        : "  ✓ 已超过静音阈值，采音正常"));
        System.out.println("  唤醒命中次数 : " + hits.get()
                + (hits.get() == 0 ? "  ⚠ 一次都没命中——请确认是否真的说了「" + config.wakeWord() + "」" : ""));
        System.out.println("  最后预览文字 : " + (lastPreview.length() == 0 ? "（无）"
                : Logging.describeWithFingerprint(lastPreview.toString())));
        if (lastPreview.length() > 0) {
            System.out.println("  预览内容     : " + lastPreview);
        }
        System.out.println();
        System.out.println("提示：识别质量与命中率的正式验收见 docs/DESIGN.md §9.3 手工清单。");

        capture.removeListener(probe);
        capture.removeListener(previewTap);
        if (previewRef != null) {
            previewRef.close();
        }
    }

    /**
     * 环境自检（{@code --doctor} / {@code --headless}）。
     *
     * <p><b>它真的不碰 UI</b>：UI 段（悬浮球 / 预览条 / 设置窗口 / Robot 操作真实鼠标）
     * 由参数关掉，报告里会**如实写明"UI 段：按参数跳过"**（跳过 ≠ 通过）。
     * 这条承诺此前是假的 —— 那时它调的是完整自检，桌面可用就会造一颗悬浮球并移动鼠标，
     * 而文档写着"不碰鼠标"（实测抓出，见 {@code docs/DECISIONS.md} 的 D5）。
     */
    private void doctor(Options opts) {
        StringBuilder sb = new StringBuilder();
        sb.append("TalkingLive 环境自检\n");
        sb.append("home        : ").append(AppPaths.home()).append('\n');
        sb.append("config      : ").append(AppPaths.configFile()).append('\n');
        sb.append("os          : ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append('\n');
        sb.append("java        : ").append(System.getProperty("java.version")).append('\n');
        sb.append("dpi         : ").append(DpiScale.systemDpi()).append(" (scale ")
                .append(String.format("%.2f", Theme.dpiScale())).append(")\n");
        for (StatusLine line : statusLines()) {
            sb.append(String.format("%-16s: %-10s %s%s%n", line.name(), line.value(),
                    line.ok() ? "[ok]  " : "[FAIL]",
                    line.detail() == null ? "" : line.detail().replace("\n", " | ")));
        }
        sb.append('\n').append(SelfTest.run(new SelfTestEnv(), false).report());
        sb.append("\n注：--doctor 不跑 UI 段（不造窗口、不碰鼠标），上面那一行已如实标注为「按参数跳过」。\n")
                .append("    要验悬浮球 / 预览条 / 设置窗口与真实鼠标路径，请跑 --self-check。\n");
        String text = sb.toString();
        System.out.println(toAscii(text));
        try {
            Path report = AppPaths.home().resolve("doctor-report.txt");
            java.nio.file.Files.writeString(report, text, java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("\n[doctor] full report written to " + report);
        } catch (IOException e) {
            System.out.println("[doctor] cannot write report: " + e);
        }
    }

    /** 跑自检并退出（{@code --self-check}）。 */
    private void runSelfCheckAndExit() {
        // 带探针跑：真实路径那几条断言（改配置是否真生效、Esc 是否真接线、
        // 提示条是否真的不抢焦点）只有拿到 App 才能验。
        // includeUi=true：UI 段（悬浮球 / 预览条 / 设置窗口 / 真实鼠标）归本命令，
        // --doctor 那边明确关掉（见 SelfTest.run 的注释与 DECISIONS.md D5）。
        SelfTest.Result r = SelfTest.run(new SelfTestEnv(), true);
        String ascii = toAscii(r.report());
        System.out.println(ascii);
        try {
            Path report = AppPaths.home().resolve("selftest-report.txt");
            java.nio.file.Files.writeString(report, r.report(), java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("[self-check] report written to " + report);
        } catch (IOException e) {
            System.out.println("[self-check] cannot write report: " + e);
        }
        System.out.println("[self-check] " + r.passed() + " passed, " + r.failed() + " failed");
        System.exit(r.failed() == 0 ? 0 : 1);
    }

    /**
     * 控制台只输出 ASCII 摘要。
     *
     * <p>理由来自 demo 阶段踩过的坑：Windows 控制台默认代码页是 GBK，
     * 中文会乱码并**掩盖真正的失败信息**（§9.2）。报告文件用 UTF-8 写，内容完整。
     */
    private static String toAscii(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(c < 128 ? c : '?');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 关闭

    private void shutdown() {
        log.info("正在退出…");
        // Esc 监听要先停：它会在任意线程上回调状态机，退出过程中不该再有新事件进来
        if (escapeWatcher != null) {
            log.info("Esc 取消监听共触发 {} 次", escapeWatcher.fires());
            escapeWatcher.close();
            escapeWatcher = null;
        }
        cancelCommitWatchdog();
        commitWatchdog.shutdownNow();
        try {
            if (capture != null) {
                capture.close();
            }
        } catch (RuntimeException e) {
            log.warn("关闭音频采集出错：{}", e.toString());
        }
        try {
            if (foreground != null) {
                foreground.close();
            }
        } catch (RuntimeException e) {
            log.warn("关闭窗口监听出错：{}", e.toString());
        }
        closeQuietly(wakeDetector);
        closeQuietly(recognizer);
        closeQuietly(refiner);
        // asrModel 与 voskModel 在小模型回退时是**同一个句柄**：重复 close 是安全的
        // （VoskModel 内部用 AtomicBoolean 防了重复释放），先关识别模型再关唤醒模型。
        closeQuietly(asrModel);
        closeQuietly(voskModel);
        persistConfig();
        onUi(() -> {
            if (ball != null) {
                ball.dispose();
            }
            if (previewBar != null) {
                previewBar.dispose();
            }
            if (settings != null) {
                settings.dispose();
            }
            if (toast != null) {
                toast.hideToast();
                toast.dispose();
            }
        });
        log.info("=== TalkingLive 退出 ===");
        if (System.getProperty("talkinglive.noExit", "false").equals("true")) {
            return;   // 自检模式：不真的结束 JVM
        }
        System.exit(0);
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception e) {
            log.warn("关闭资源出错：{}", e.toString());
        }
    }

    // ------------------------------------------------------------ 测试支撑

    /**
     * 自检用的 App 探针（见 {@code SelfTest.Env} 的注释）。
     *
     * <p>存在的理由是三条真实教训：自检里「Esc 取消」「切窗口照常提交」「配置改了生效」
     * 这三条**都只测了替身**，于是真实路径坏了它们照样全绿。
     * 这里把真实对象交出去，让自检直接对它们下断言。
     */
    private final class SelfTestEnv implements SelfTest.Env {

        @Override
        public AppConfig config() {
            return config;
        }

        @Override
        public String applyConfig(AppConfig candidate) {
            return applyConfigForTest(candidate);
        }

        @Override
        public String wakeDetectorDescription() {
            return wakeDetector == null ? null : wakeDetector.describe();
        }

        @Override
        public SilenceDetector silenceDetector() {
            return silence;
        }

        @Override
        public StateMachine stateMachine() {
            return sm;
        }

        @Override
        public boolean escapeWatcherRunning() {
            EscapeWatcher w = escapeWatcher;
            return w != null && w.running();
        }

        @Override
        public boolean uiStarted() {
            return ball != null;
        }

        @Override
        public void showNotice(String title, String detail) {
            App.this.showNotice(title, detail);
        }

        @Override
        public java.awt.Window lastNoticeWindow() {
            return toast;
        }

        @Override
        public void hideNotice() {
            Toast t = toast;
            if (t != null) {
                onUi(t::hideToast);
            }
        }
    }

    AppConfig configForTest() {
        return config;
    }

    StateMachine stateMachineForTest() {
        return sm;
    }

    List<StatusLine> statusForTest() {
        return statusLines();
    }

    /** 供自检在无麦克风环境下驱动完整流程。 */
    void injectAudioForTest(byte[] pcm) {
        new CaptureBridge().onPcm(pcm, SilenceDetector.rms16le(pcm));
    }

    String applyConfigForTest(AppConfig candidate) {
        return applyConfig(candidate);
    }
}
