package com.talkinglive;

import com.talkinglive.audio.AudioCapture;
import com.talkinglive.audio.SilenceDetector;
import com.talkinglive.core.AppConfig;
import com.talkinglive.core.AppPaths;
import com.talkinglive.core.DictationSession;
import com.talkinglive.core.InMemoryLogAppender;
import com.talkinglive.core.Logging;
import com.talkinglive.core.StateMachine;
import com.talkinglive.core.StatusLine;
import com.talkinglive.engine.LazyVoskModel;
import com.talkinglive.engine.SpeechRecognizer;
import com.talkinglive.engine.TextRefiner;
import com.talkinglive.engine.TextRefiners;
import com.talkinglive.engine.VoskKeywordDetector;
import com.talkinglive.engine.VoskModel;
import com.talkinglive.engine.VoskSpeechRecognizer;
import com.talkinglive.engine.WakeWordDetector;
import com.talkinglive.system.CaretTracker;
import com.talkinglive.system.DpiScale;
import com.talkinglive.system.ForegroundWatcher;
import com.talkinglive.system.MicValidator;
import com.talkinglive.system.Win32WindowStyles;
import com.talkinglive.system.WindowsTextInjector;
import com.talkinglive.text.CommitPolicy;
import com.talkinglive.text.PreviewText;
import com.talkinglive.text.PunctuationProcessor;
import com.talkinglive.text.TextInjector;
import com.talkinglive.text.TextPostProcessor;
import com.talkinglive.text.TextUtils;
import com.talkinglive.text.WholeSegmentPolicy;
import com.talkinglive.ui.DiagnosticsWindow;
import com.talkinglive.ui.FloatingBall;
import com.talkinglive.ui.Icons;
import com.talkinglive.ui.PreviewBar;
import com.talkinglive.ui.SettingsWindow;
import com.talkinglive.ui.Theme;
import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
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
     * **识别用**的大模型（精化路径），按需从后台加载。
     *
     * <p>为什么单独持有而不是直接给 {@link #asrModel}：大模型同步加载要 21.5 秒
     * （解压 2.0GB，见 {@link LazyVoskModel} 的类注释），远超 §6 的 3 秒冷启动预算。
     * {@link LazyVoskModel} 在后台线程加载，用户说到需要精化时通常已经就绪。
     *
     * <p>与 {@link #asrModel} 的分工：{@code asrModel} 是**实时预览**用的（必须立刻出字，
     * 只能用小模型），{@code largeModel} 是**段落精化**用的（可以等，所以要最准的那个）。
     */
    private LazyVoskModel largeModel;
    /**
     * **听写用**的模型（预览），可以与唤醒检测用的小模型不同。
     *
     * <p>分开的原因见 {@code AppPaths.asrModelDir}：唤醒词检测必须用小模型
     * （只有它支持运行时词表），而预览与精化只要有准确率。
     * 配了更大的模型时，准确率会显著提升（CER 17.15% → 7.43%）。
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
    private final SilenceDetector silence =
            new SilenceDetector(AppConfig.DEFAULT_SILENCE_SECONDS);

    // ---- UI ----
    private FloatingBall ball;
    private PreviewBar previewBar;
    private SettingsWindow settings;
    /** 诊断窗口（状态 / 日志 / 自检）。与设置分开：一个只读、一个只写。 */
    private DiagnosticsWindow diagnostics;

    // ---- 运行时状态 ----
    private final AtomicReference<DictationSession> session = new AtomicReference<>();
    private final List<String> notices = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean paused;
    private volatile MicValidator.Result wordCheck;
    private volatile String micError;
    private volatile String lastInjectionError;
    private TrayIcon trayIcon;

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

    // ------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Options opts = Options.parse(args);
        if (opts.help) {
            Options.printHelp();
            return;
        }
        App app = new App();
        try {
            app.start(opts);
        } catch (RuntimeException | IOException e) {
            // 启动阶段的失败必须可见：产品平时没有界面，静默退出的表现是
            // 「双击了没反应」，用户完全无从判断（§7）。
            log.error("启动失败：{}", e.toString(), e);
            if (opts.console || !opts.headless) {
                app.showFatal("TalkingLive 启动失败", e.getMessage() == null ? e.toString() : e.getMessage());
            }
            if (!opts.headless) {
                throw e;
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
                        + "此时可以用托盘图标，或先结束旧的 TalkingLive 进程再启动。\n"
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
        log.info("配置：wake={} end={} silence={}s autoSend={} sendKey={} maxSegment={}s itn={}",
                config.wakeWord(), config.endWord(), config.silenceSeconds(), config.autoSend(),
                config.sendKey().display(), config.maxSegmentSeconds(), config.itn());

        // ③ 引擎
        loadEngines(opts);

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
            wordCheck = MicValidator.validate(voskModel::findWord, config.wakeWord(), config.endWord());
            if (!wordCheck.ok()) {
                String msg = wordCheck.message();
                log.error("词表校验失败：{}", msg.replace("\n", " / "));
                notices.add(msg);
            } else {
                log.info("词表校验通过：wake={} end={}", config.wakeWord(), config.endWord());
            }
        } catch (RuntimeException e) {
            log.error("词表校验无法执行：{}", e.toString());
            notices.add("词表校验无法执行：" + e.getMessage()
                    + "\n这意味着「词表外的词静默失效」无法被拦住，请检查 Vosk 原生库。");
        }

        // 听写用模型（实时预览）。默认与唤醒模型相同（小模型，能立刻出字）；
        // 可用 -Dtalkinglive.model.asr=<目录> 或 TALKINGLIVE_ASR_MODEL 指向大模型。
        Path asrDir = AppPaths.asrModelDir();
        if (!asrDir.equals(modelDir)) {
            try {
                asrModel = VoskModel.load(asrDir);
                log.info("听写用模型（预览）：{} —— 与唤醒检测模型分开配置", asrDir);
            } catch (IOException | RuntimeException e) {
                log.error("听写用模型加载失败，回退到唤醒模型：{}", e.getMessage());
                asrModel = voskModel;
            }
        } else {
            asrModel = voskModel;
        }

        // 识别准确率的真正来源：大模型（CER 7.43% 对小模型的 17.15%）。
        // 只在**精化**路径上用它，因为流式预览必须立刻出字、等不了 21 秒的加载；
        // 而精化是「段落已录完、正在收尾」，用户本来就在等，那时多等一会儿可接受。
        //
        // --doctor / --self-check 是「跑完就退出的自检」，不该顺手把 2GB 的大模型
        // 拉进内存跑一遍 —— 那会让自检又多 20 秒、还多占 2GB，而它并不测这个。
        boolean interactive = !opts.headless && !opts.selfCheck;
        largeModel = interactive
                ? LazyVoskModel.detect(AppPaths.configuredAsrOverride())
                : new LazyVoskModel(null);   // 空目录 = 明确的「本次不启用」
        if (interactive && largeModel.dir() == null) {
            // 没装大模型时如实说明 —— 用户抱怨的「识别不准」根因就在这里，
            // 不能让他以为已经在用最准的模型了。
            log.warn("识别将使用**小模型**（CER 17.15%）：未发现大模型。"
                    + "更准的做法是把 vosk-model-cn-0.22 解压到 {}（预期 CER 7.43%）",
                    AppPaths.modelsDir());
        }

        // 唤醒 / 结束词检测
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

        // 实时预览。优先用大模型：预览文本是**注入的底稿**，预览错得越多、
        // 精化的纠错负担越重；而且用户很可能在应用启动后一两分钟才开口，
        // 那时大模型早已在后台加载完成。若尚未就绪（用户开得很快），
        // 就用小模型顶上 —— 预览的价值是「立刻看到字」，不能为了准确率让它卡住。
        try {
            VoskModel previewModel = largeModel != null
                    ? largeModel.getOr(asrModelOrFallback()) : asrModelOrFallback();
            recognizer = new VoskSpeechRecognizer(previewModel, (kind, text) -> onPreviewText(kind, text));
            log.info("实时预览就绪：{}", recognizer.describe());
            if (largeModel != null && largeModel.ready() && previewModel == largeModel.get()) {
                log.info("实时预览正在使用**大模型**，识别准确率显著高于小模型");
            }
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
     */
    private void rebuildPostProcess() {
        postProcess = new PunctuationProcessor(
                List.of(config.wakeWord(), config.endWord()), true, true);
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
     * 精化用的模型：优先用大模型（更准），没装/加载失败则退回小模型。
     *
     * <p>大模型的加载是异步的（{@link LazyVoskModel}）：这里会**等一下**加载完成，
     * 因为这段代码只在**真正要精化一段音频**的时候才被调用
     * （段落已录完、正在收尾，用户本来就在等结果）。
     *
     * <p><b>它绝不能在启动路径上被调用。</b>实测过一次事故：{@code createRefiner} 在启动时
     * 就把它算好并塞进 {@link TextRefiners.VoskOffline}，于是主线程在这里同步等了 21 秒，
     * 界面整整 21 秒不出现 —— 异步加载等于白做。所以这里传的是 {@code this::...}
     * 方法引用，模型在第一次精化时才解析。
     */
    private VoskModel refinerModelOrFallback() {
        if (largeModel != null) {
            VoskModel m = largeModel.get();
            if (m != null) {
                return m;
            }
        }
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
                    "Vosk 离线重跑", true, config.wakeWord());
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
                        "Vosk 离线重跑", true, config.wakeWord());
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
            if (paused) {
                // 采集中断导致的临时暂停，恢复后解除
                onUi(() -> {
                    paused = false;
                    sm.handle(StateMachine.Event.RESUME);
                    if (ball != null) {
                        ball.setPaused(false);
                    }
                });
            }
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
        if (kind == SpeechRecognizer.Kind.FINAL) {
            preview.commitFinal(fullText);
        } else {
            preview.setPartial(fullText);
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
                previewBar.render("", "", "听写中 · 说「" + config.endWord() + "」或静音 "
                        + config.silenceSeconds() + " 秒结束", anchorPoint());
                foreground.ignore(Win32WindowStyles.hwndOf(previewBar));
            });
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

            log.info("段落结束（{}）：时长 {}s 预览 {}", reason.display(),
                    String.format("%.2f", s.recordedSeconds()),
                    Logging.describeWithFingerprint(summary));

            onUi(() -> {
                if (previewBar != null) {
                    previewBar.setStatus("处理中 · 精化引擎：" + (refiner == null ? "无" : refiner.engineName()));
                }
            });

            Thread worker = new Thread(() -> {
                TextRefiner r = refiner;
                TextRefiner.Result result;
                boolean ending = TextUtils.endsWithSentencePunctuation(summary);
                if (r == null || !r.available()) {
                    result = TextRefiner.Result.fallback(summary,
                            r == null ? "无" : r.engineName(),
                            r == null ? "未装配精化引擎" : r.unavailableReason());
                } else {
                    result = r.refine(pcm, summary, ending);
                }
                // 段落已被取消/替换时，迟到的精化结果直接丢弃（§2.1）
                if (session.get() == null || session.get().generation() != generation
                        || !sm.committing()) {
                    log.info("精化结果迟到，已丢弃（段落已结束：gen={} 当前状态={}）",
                            generation, sm.state());
                    return;
                }
                pendingResult.set(result);
                sm.handle(StateMachine.Event.REFINE_DONE);
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
            if (text.isEmpty()) {
                log.info("本段没有可注入的文本（可能是误触发或只有静音），不注入");
                showNotice("本段没有内容", "没有识别到文字，因此没有注入。若经常如此，请检查麦克风与唤醒词。");
                finishCommit(s);
                return;
            }

            if (!ctx.inject() || !sm.shouldInject()) {
                // §7：提交时前台窗口已变 → 放弃注入 + 明确提示，且不自动发送
                String msg = "本段已放弃注入：提交时前台窗口已变，为避免把文字误发到别的程序，宁可丢弃。\n"
                        + "（识别到的内容是：" + abbreviate(text) + "）";
                lastInjectionError = msg;
                log.warn("放弃注入：前台窗口已变");
                showNotice("本段未注入", msg);
                finishCommit(s);
                return;
            }

            // 注入放到独立线程：SendInput 与阻塞式提示都不该占着 EDT
            Thread worker = new Thread(() -> doInject(s, text, ctx), "inject-worker");
            worker.setDaemon(true);
            worker.start();
        }

        @Override
        public void onSegmentAbandoned(String why) {
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

            if (config.autoSend() && shouldAutoSend(ctx.reason())) {
                TextInjector.Result pr = injector.press(TextInjector.KeyCombo.fromConfig(config.sendKey()));
                if (!pr.ok()) {
                    log.warn("自动发送失败：{}", pr.message());
                    showNotice("自动发送失败", pr.message());
                }
            } else if (config.autoSend()) {
                log.info("静音超时结束且未开启「静音超时后发送」，只注入不发送");
            }
            onUi(() -> {
                if (previewBar != null) {
                    previewBar.hideBar();
                }
            });
            finishCommit(s);
        } catch (RuntimeException e) {
            log.error("注入过程中发生异常：{}", e.toString(), e);
            lastInjectionError = e.toString();
            finishCommit(s);
        }
    }

    /**
     * 静音超时是否也要自动发送。
     *
     * <p>附录 A 单列了「静音超时后发送」这一项，默认关闭：静音兜底本来就是
     * 「用户忘了说结束词」的场景，此时再自动敲一次 Enter 风险更大。
     */
    private boolean shouldAutoSend(StateMachine.EndReason reason) {
        if (reason == StateMachine.EndReason.SILENCE_TIMEOUT) {
            return config.sendOnSilenceTimeout();
        }
        return true;
    }

    private void finishCommit(DictationSession s) {
        session.compareAndSet(s, null);
        preview.reset();
        silence.reset();
        sm.handle(StateMachine.Event.INJECTED);
    }

    // ------------------------------------------------------------ UI

    private void startUi(Options opts) {
        try {
            UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarkLaf());
            UIManager.put("Component.focusWidth", 0);
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

            installTray();

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

    private void installTray() {
        if (!SystemTray.isSupported()) {
            log.info("系统托盘不可用，托盘入口略过（悬浮球仍是主要入口）");
            return;
        }
        try {
            PopupMenu menu = new PopupMenu();

            MenuItem manual = new MenuItem(paused ? "手动开始听写（已暂停）" : "手动开始 / 结束听写");
            manual.setEnabled(!paused);
            manual.addActionListener(e -> onBallLeftClick());
            menu.add(manual);

            menu.addSeparator();
            MenuItem pause = new MenuItem(paused ? "恢复监听" : "暂停监听");
            pause.addActionListener(e -> togglePause());
            menu.add(pause);

            menu.addSeparator();
            MenuItem settingsItem = new MenuItem("设置...");
            settingsItem.addActionListener(e -> openSettings());
            menu.add(settingsItem);

            MenuItem logs = new MenuItem("状态与诊断…");
            logs.addActionListener(e -> openDiagnostics(DiagnosticsWindow.TAB_STATUS));
            menu.add(logs);

            menu.addSeparator();
            MenuItem quit = new MenuItem("退出");
            quit.addActionListener(e -> shutdown());
            menu.add(quit);

            trayIcon = new TrayIcon(trayImage(), "TalkingLive —— " + sm.state().display(), menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> onBallLeftClick());
            SystemTray.getSystemTray().add(trayIcon);
            log.info("托盘图标已就绪（⚠ Windows 11 默认把它收进「隐藏的图标」折叠面板，"
                    + "用户需手动拖出来一次 —— 所以它只是二级入口，悬浮球才是主要入口）");
        } catch (AWTException | RuntimeException e) {
            log.warn("托盘图标创建失败（不影响主要入口）：{}", e.toString());
        }
    }

    /** 托盘图标：与悬浮球同源的矢量话筒（{@link Icons}），不再各画一份。 */
    private static Image trayImage() {
        return Icons.trayImage();
    }

    /** 悬浮球与托盘共用的动作。 */
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

    private void onBallLeftClick() {
        ballLeftClick();
    }

    private void togglePause() {
        paused = !paused;
        sm.handle(paused ? StateMachine.Event.PAUSE : StateMachine.Event.RESUME);
        onUi(() -> {
            if (ball != null) {
                ball.setPaused(paused);
            }
            if (trayIcon != null) {
                trayIcon.setToolTip("TalkingLive —— " + (paused ? "已暂停" : sm.state().display()));
            }
        });
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
     * 提示气泡：注入失败等**静默失败必须可见**（§7）。
     *
     * <p><b>必须去重。</b>同一类失败常常是持续的（麦克风被拔掉就会每几秒失败一次），
     * 若每次都弹一个对话框，屏幕上会堆满窗口——实测症状是**对话框盖住了悬浮球，
     * 用户连点都点不到**，等于把唯一入口也弄丢了。因此同一个标题在
     * {@link #NOTICE_THROTTLE_MILLIS} 内只提示一次，其余只进日志。
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
            if (trayIcon != null) {
                try {
                    trayIcon.displayMessage(title, detail, TrayIcon.MessageType.INFO);
                    return;
                } catch (RuntimeException e) {
                    log.debug("托盘气泡失败，回退到对话框：{}", e.toString());
                }
            }
            // 对话框是非模态的：模态对话框会阻塞调用线程并可能盖住悬浮球
            javax.swing.JOptionPane pane = new javax.swing.JOptionPane(detail, javax.swing.JOptionPane.INFORMATION_MESSAGE);
            javax.swing.JDialog dialog = pane.createDialog(settings, title);
            dialog.setModal(false);
            dialog.setAlwaysOnTop(true);
            dialog.setVisible(true);
        });
    }

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

    /** 由设置窗口调用：校验 → 保存 → 作用于运行中的组件。 */
    private String applyConfig(AppConfig candidate) {
        try {
            candidate.validate();
        } catch (AppConfig.ConfigException e) {
            return e.getMessage();
        }
        // 词表校验（附录 C）：只有在模型可用时才能查，查不了不阻止保存但会提示。
        if (voskModel != null) {
            try {
                java.util.Map<String, String> pairs = new java.util.LinkedHashMap<>();
                pairs.put("唤醒词", candidate.wakeWord());
                pairs.put("结束词", candidate.endWord());
                MicValidator.Result r = MicValidator.validate(voskModel::findWord, pairs);
                wordCheck = r;
                if (!r.ok()) {
                    return r.message();
                }
            } catch (RuntimeException e) {
                log.warn("词表校验无法执行，配置仍被保存：{}", e.toString());
            }
        }
        // 热更新：静音秒数、词表（需要重建识别器）
        boolean wordsChanged = !candidate.wakeWord().equals(config.wakeWord())
                || !candidate.endWord().equals(config.endWord());
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
        public Boolean wordInVocabulary(String word) {
            return App.this.wordInVocabulary(word);
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
        if (largeModel == null || largeModel.dir() == null) {
            asrOk = false;
            asrDetail = "正在用小模型识别（CER 17.15%，准确率上限就到这里）。"
                    + "把 vosk-model-cn-0.22 解压到 " + AppPaths.modelsDir()
                    + " 即自动启用大模型（CER 7.43%，准确率翻倍），详见 docs/ENGINE-EXPERIMENT.md";
        } else if (largeModel.ready()) {
            asrOk = true;
            asrDetail = "正在用大模型识别（CER 7.43%，比小模型准一倍）：" + largeModel.dir();
        } else if (largeModel.error() != null) {
            asrOk = false;
            asrDetail = "大模型加载失败，已退回小模型：" + largeModel.error();
        } else {
            asrOk = true;   // 加载中不算故障，只是还没好
            asrDetail = "大模型正在后台加载（约 20 秒，不阻塞使用）：" + largeModel.dir();
        }
        out.add(new StatusLine("识别准确率",
                largeModel != null && largeModel.ready() ? "大模型" : "小模型", asrOk, asrDetail));

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

        // 托盘
        out.add(new StatusLine("托盘图标",
                trayIcon != null ? "已安装" : "不可用", trayIcon != null,
                trayIcon != null ? "⚠ Windows 11 默认折叠它，悬浮球才是主要入口" : null));

        if (lastInjectionError != null) {
            out.add(new StatusLine("最近一次注入失败", "见日志", false, lastInjectionError));
        }
        return out;
    }

    private void addWordLine(List<StatusLine> out, String field, String word) {
        String key = "词表:" + field;
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
     * <p>不启动 UI，因此不含「悬浮球不抢焦点 / 右键菜单能否弹出 / 贴边收起」这几项——
     * 那些需要真实桌面与真实鼠标，跑在 {@code --self-check} 里。
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
        sb.append('\n').append(SelfTest.run().report());
        sb.append("\n注：--doctor 不启动 UI，因此上面没有「悬浮球不抢焦点 / 右键菜单」那几项。\n")
                .append("    那几项需要真实桌面与真实鼠标，请用 --self-check 单独跑。\n");
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
        SelfTest.Result r = SelfTest.run();
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

    /** 诊断报告文本（供测试与自检页）。 */
    public String diagnosticsText() {
        return SelfTest.run().report();
    }

    // ------------------------------------------------------------ 关闭

    private void shutdown() {
        log.info("正在退出…");
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
        // 先关大模型再关小模型：大模型是独立句柄，但它的加载线程可能仍在跑，
        // LazyVoskModel.close 会把已加载的句柄释放掉、尚未加载完的则由加载线程自己收尾。
        closeQuietly(largeModel);
        // asrModel 可能与小模型是同一个句柄（未单独配置时），重复 close 是安全的
        // （VoskModel 内部用 AtomicBoolean 防了重复释放），但语义上先放大模型更清楚。
        closeQuietly(asrModel);
        closeQuietly(voskModel);
        persistConfig();
        onUi(() -> {
            if (trayIcon != null && SystemTray.isSupported()) {
                SystemTray.getSystemTray().remove(trayIcon);
            }
            if (ball != null) {
                ball.dispose();
            }
            if (previewBar != null) {
                previewBar.dispose();
            }
            if (settings != null) {
                settings.dispose();
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
