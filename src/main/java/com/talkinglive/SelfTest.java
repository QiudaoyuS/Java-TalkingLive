package com.talkinglive;

import com.talkinglive.audio.SilenceDetector;
import com.talkinglive.audio.WavFile;
import com.talkinglive.core.AppConfig;
import com.talkinglive.core.DictationSession;
import com.talkinglive.core.StateMachine;
import com.talkinglive.core.StatusLine;
import com.talkinglive.engine.TextRefiner;
import com.talkinglive.system.MicValidator;
import com.talkinglive.system.Win32WindowStyles;
import com.talkinglive.text.CommitPolicy;
import com.talkinglive.text.PreviewText;
import com.talkinglive.text.PunctuationProcessor;
import com.talkinglive.text.TextInjector;
import com.talkinglive.text.TextUtils;
import com.talkinglive.text.WholeSegmentPolicy;
import com.talkinglive.ui.DiagnosticsWindow;
import com.talkinglive.ui.FloatingBall;
import com.talkinglive.ui.PreviewBar;
import com.talkinglive.ui.SettingsWindow;
import com.talkinglive.ui.Theme;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;

/**
 * 结构化自检：把「交付前必须跑一遍」的东西做成可执行、可复现的一步。
 *
 * <p>为什么需要它，而不只是 JUnit：{@code DESIGN.md} §9.1 分了三层测试，
 * 其中**「UI 自检」这一层 JUnit 做不了**——「悬浮球不抢焦点」「右键菜单能弹出」
 * 「贴边收起与滑出」只能真的用鼠标点一下（§9.2 的原型阶段结论）。
 * 同时「音频链路 + 注入」这一层在无麦克风环境下也需要一个**端到端**的替身验证。
 *
 * <p>纯逻辑部分（状态机、码点、配置、静音计时、词表校验）在 JUnit 里，
 * 见 {@code src/test/java}。这里只放 JUnit 覆盖不了的。
 *
 * <p><b>两条必须遵守的前提</b>（demo 阶段踩坑才明白的，§4.4）：
 * <ol>
 *   <li><b>绝不能在 EDT 上跑。</b>{@code Robot.delay} 阻塞的是当前线程；
 *       如果那就是 EDT，Robot 产生的鼠标事件与 Swing 的动画 Timer 都排不进队，
 *       断言会全部失真——表现为「明明点了却没反应」。</li>
 *   <li><b>坐标一律用 AWT 逻辑坐标，不要自己乘 DPI 缩放。</b>
 *       {@code Robot.mouseMove}、{@code MouseInfo.getPointerInfo()} 与
 *       {@code Window.getBounds()} 处在同一个坐标空间。手动乘一次缩放系数
 *       会把坐标推到屏幕外并被夹到边缘，点到完全无关的地方。</li>
 * </ol>
 */
public final class SelfTest {

    /** 单项结果。 */
    public record Item(String category, String name, boolean ok, String detail) {
        String line() {
            return String.format("%-6s %-4s %-34s %s", "[" + category + "]", ok ? "ok" : "FAIL", name,
                    detail == null ? "" : detail);
        }
    }

    /** 全部结果。 */
    public record Result(List<Item> items) {
        public long passed() {
            return items.stream().filter(Item::ok).count();
        }

        public long failed() {
            return items.stream().filter(i -> !i.ok()).count();
        }

        /** 面向人的完整报告（UTF-8 写文件 / 设置窗口显示）。 */
        public String report() {
            StringBuilder sb = new StringBuilder();
            sb.append("TalkingLive 自检报告\n");
            sb.append("=".repeat(78)).append('\n');
            String category = null;
            for (Item i : items) {
                if (!i.category().equals(category)) {
                    category = i.category();
                    sb.append("\n--- ").append(category).append(" ---\n");
                }
                sb.append(i.line()).append('\n');
            }
            sb.append('\n').append("=".repeat(78)).append('\n');
            sb.append("合计 ").append(items.size()).append(" 项：通过 ").append(passed())
                    .append("，失败 ").append(failed()).append('\n');
            if (failed() > 0) {
                sb.append("\n失败项：\n");
                for (Item i : items) {
                    if (!i.ok()) {
                        sb.append("  · [").append(i.category()).append("] ").append(i.name())
                                .append(" —— ").append(i.detail() == null ? "" : i.detail()).append('\n');
                    }
                }
            }
            return sb.toString();
        }
    }

    private final List<Item> items = new ArrayList<>();

    private SelfTest() {}

    /**
     * App 侧的探针：让自检能走**真实路径**，而不是替身。
     *
     * <p>为什么要专门做这个接口：本项目最被信任的三条自检，恰好都覆盖不到它们
     * 声称覆盖的真实路径 ——
     * <ul>
     *   <li>「Esc 取消后一个字都没注入」直接给状态机发事件，而**输入路径当时根本不存在**；</li>
     *   <li>「切窗口后仍照常提交」用的是替身注入器，而真实 {@code App} 那条分支仍然丢话；</li>
     *   <li>配置生效链路只有 {@code StubHost.applyConfig}（它恒返回 {@code null}），
     *       于是"改唤醒词永远不重建检测器"完全没被拦住。</li>
     * </ul>
     * 共同点是：**断言测的是替身，不是产品**。所以这里让 {@code App} 把真实对象交出来，
     * 由自检直接对它们下断言。没拿到探针就如实报告，不假装通过。
     */
    public interface Env {

        /** App 当前生效的配置（自检只读它；要改就先 {@code copy()}）。 */
        default AppConfig config() {
            return null;
        }

        /** 走**真实的**配置热更新链路（校验 → 落盘 → 作用于运行中的组件）。返回错误消息或 null。 */
        default String applyConfig(AppConfig candidate) {
            return "App 未提供 applyConfig 探针";
        }

        /** 运行中的唤醒检测器描述（含它此刻在听的词）；不可用时 null。 */
        default String wakeDetectorDescription() {
            return null;
        }

        /** 运行中的静音计时器；不可用时 null。 */
        default SilenceDetector silenceDetector() {
            return null;
        }

        /** App 自己的状态机。 */
        default StateMachine stateMachine() {
            return null;
        }

        /** 全局 Esc 监听是否在跑。 */
        default boolean escapeWatcherRunning() {
            return false;
        }

        /** UI 是否已启动（{@code --doctor} 不启动 UI，那两项检查无从验证）。 */
        default boolean uiStarted() {
            return false;
        }

        /** 触发一次真实的提示条。 */
        default void showNotice(String title, String detail) {
        }

        /** 最近一次提示条的窗口；没有则为 null。 */
        default java.awt.Window lastNoticeWindow() {
            return null;
        }

        /** 收起提示条。 */
        default void hideNotice() {
        }
    }

    /** 跑全部自检。**必须在非 EDT 线程上调用**（见类注释）。 */
    public static Result run() {
        return new Result(List.copyOf(collect(true).items));
    }

    /**
     * 跑全部自检，外加「真实路径」一节（见 {@link Env}）。
     *
     * <p>只有拿到 {@code App} 探针时才跑那一节 —— 没有探针就不声称验证过。
     */
    public static Result run(Env env) {
        return run(env, true);
    }

    /**
     * 跑自检，并由调用方决定**是否包含 UI 段**。
     *
     * <p>为什么要这个开关：{@code --doctor} 承诺的是「环境自检后退出」，而它此前调的是
     * 上面那个无参版本 —— 只要桌面可用就会跑 UI 段，也就是**造一颗悬浮球并用
     * {@code Robot} 真的移动鼠标**。文档与注释都写着「不碰鼠标」，与真实行为相反
     * （实测抓出来的：日志里 {@code --doctor} 的输出含 B 项与 {@code ballVisible=true}）。
     * 于是那个承诺永远是假的，而用户可能正在用鼠标做别的事。
     *
     * <p>现在按调用方分工：{@code --doctor} 传 {@code false}（不碰 UI，并在报告里如实写明跳过），
     * {@code --self-check} 传 {@code true}（UI 段与真实路径那节归它）。
     *
     * @param env         App 探针；null 表示不跑「真实路径」一节
     * @param includeUi   是否包含 UI 段（造窗口 + Robot 操作真实鼠标）
     */
    public static Result run(Env env, boolean includeUi) {
        SelfTest t = collect(includeUi);
        if (env != null) {
            t.runRealPath(env);
        }
        return new Result(List.copyOf(t.items));
    }

    private static SelfTest collect(boolean includeUi) {
        SelfTest t = new SelfTest();
        t.runCoreLogic();
        t.runPipeline();
        if (!includeUi) {
            // 如实写进报告：跳过不等于通过。否则报告看上去"全绿"，
            // 而读者会以为 UI 那几项也验过了（本项目吃过这个亏）。
            t.add("UI", "UI 段：按参数跳过", true,
                    "--doctor 不造窗口、不碰鼠标（这是它的承诺）；要验悬浮球/预览条/设置窗口请跑 --self-check");
        } else if (canTouchUi()) {
            t.runUi();
        } else {
            t.add("UI", "图形环境", false, "无可用显示（headless），UI 自检已跳过——这类检查必须在真实桌面上跑");
        }
        return t;
    }

    private static boolean canTouchUi() {
        try {
            return !GraphicsEnvironment.isHeadless();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 端到端管线的装配体：真实的 {@code StateMachine} / {@code PreviewText} /
     * {@code CommitPolicy} / {@code PunctuationProcessor}，只有注入器是替身。
     *
     * <p>单独做成一个类而不是匿名内部类，是因为监听器里需要回调状态机自身——
     * 匿名内部类引用正在构造的局部变量会编译不过（「变量 sm 未初始化」）。
     */
    static final class PipelineHarness implements StateMachine.Listener {

        final StateMachine sm = new StateMachine(this);
        final FakeInjector injector;
        final PreviewText preview;
        final CommitPolicy policy;
        final TextRefiner refiner;
        final PunctuationProcessor post;

        DictationSession session;
        TextRefiner.Result lastResult;
        volatile boolean committed;
        /** 放行次数（{@link #committed} 只记"有没有过"，断言"这一次也放行了"需要计数）。 */
        volatile int commits;
        volatile int abandons;
        volatile String lastAbandon;
        /**
         * 需要"停在 COMMITTING 里"的用例把它置真。
         *
         * <p>否则 {@link #onSegmentEndRequested} 会在同一次 {@code handle()} 里
         * 立刻投递 REFINE_DONE —— 状态机一瞬间就走完 COMMITTING，
         * 而"提交中按了暂停"这件事**必须发生在精化返回之前**才复现得出来。
         */
        volatile boolean deferRefine;

        PipelineHarness(FakeInjector injector, PreviewText preview, CommitPolicy policy,
                TextRefiner refiner, PunctuationProcessor post) {
            this.injector = injector;
            this.preview = preview;
            this.policy = policy;
            this.refiner = refiner;
            this.post = post;
        }

        @Override
        public void onSegmentStartRequested() {
            session = new DictationSession(sm.generation(), 0x1234, "自检目标", 60);
            preview.reset();
        }

        @Override
        public void onSegmentEndRequested(StateMachine.EndReason reason) {
            // 模拟音频缓存：1 秒 16kHz 单声道静音
            session.appendPcm(new byte[32000]);
            String finalPreview = preview.finish();
            session.setPreviewText(finalPreview);
            // ★ 顺序很重要：先放好结果再投递 REFINE_DONE——
            //   状态机是同步回调的，onCommitReady 会在 handle() 内部立刻被调用。
            lastResult = refiner.refine(session.pcmSnapshot(), finalPreview, false);
            committed = true;
            if (deferRefine) {
                return;   // 精化"还在跑"：需要 COMMITTING 窗口的用例自己投递 REFINE_DONE
            }
            sm.handle(StateMachine.Event.REFINE_DONE);
        }

        @Override
        public void onSegmentAbandoned(String why) {
            abandons++;
            lastAbandon = why;
        }

        @Override
        public void onCommitReady(StateMachine.CommitContext ctx) {
            commits++;
            // ctx.inject()==false 表示「目标不是当前前台」（切窗口路径）。
            // 真实实现（App + WindowsTextInjector）在这种情况下**仍然注入**：
            // 先尝试把焦点还原到目标，还原不了就注入到当前焦点并明确提示。
            // 日志里真实出现过「说了 3.32 秒被丢弃」，所以这里必须反映新行为，
            // 而不是像早期那样直接 return。
            String text = post.process(session.resolveFinalText(lastResult.text()));
            if (text.isEmpty()) {
                sm.handle(StateMachine.Event.INJECTED);
                return;
            }
            CommitPolicy.CommitPlan plan = policy.plan(session.injectedText(), text);
            TextInjector.Result r = injector.inject(plan.backspaces(), plan.text());
            if (r.ok()) {
                session.appendInjected(plan.text());
            }
            sm.handle(StateMachine.Event.INJECTED);
        }
    }

    private void add(String category, String name, boolean ok, String detail) {
        items.add(new Item(category, name, ok, detail));
    }

    // ============================================================ 真实路径

    /**
     * 通过 {@link Env} 走 App 的**真实**链路（而不是替身）。
     *
     * <p>这一节专门盯住"改了没反应"这一类缺陷 —— 它们的共同特征是
     * **界面说生效了、状态机/替身测试也都过了，只有真实路径没接上**：
     * <ul>
     *   <li>改唤醒词/结束词：原来因为配置对象是同一个引用，判断恒为 false，
     *       {@code rebuildKeywordDetector()} 永远不会被调用；</li>
     *   <li>改静音秒数：原来 {@code timeoutSeconds} 是 final，只有开关生效；</li>
     *   <li>按 Esc：{@code Event.CANCEL} 在产品里根本没有生产者；</li>
     *   <li>提示：原来用 {@code JOptionPane}，会抢焦点并把本段判成"前台已变"而丢弃。</li>
     * </ul>
     */
    private void runRealPath(Env env) {
        AppConfig live = env.config();
        if (live == null) {
            add("真实路径", "App 探针可用", false,
                    "没有拿到 App 探针，配置热更新与 Esc 的真实链路未被验证");
            return;
        }
        // applyConfig 会落盘，这里把 home 临时指到临时目录 ——
        // 自检不该动用户真实的 config.json（runCoreLogic 里也是同一套做法）。
        String oldHome = System.getProperty(com.talkinglive.core.AppPaths.HOME_PROPERTY);
        java.nio.file.Path tmp = null;
        try {
            tmp = java.nio.file.Files.createTempDirectory("tl-reality");
            System.setProperty(com.talkinglive.core.AppPaths.HOME_PROPERTY, tmp.toString());
            com.talkinglive.core.AppPaths.ensureDirectories();

            checkConfigHotUpdate(env, live);
            checkSilenceHotUpdate(env);
            checkEscapeWiring(env);
            checkNoticeDoesNotStealFocus(env);
        } catch (Exception e) {
            add("真实路径", "真实链路自检", false, e.toString());
        } finally {
            if (oldHome == null) {
                System.clearProperty(com.talkinglive.core.AppPaths.HOME_PROPERTY);
            } else {
                System.setProperty(com.talkinglive.core.AppPaths.HOME_PROPERTY, oldHome);
            }
            deleteRecursively(tmp);
        }
    }

    /** 改唤醒词 → 运行中的检测器必须真的换成新词（这条守的是"改了没反应"）。 */
    private void checkConfigHotUpdate(Env env, AppConfig live) {
        if (env.wakeDetectorDescription() == null) {
            add("真实路径", "改唤醒词后检测器真的重建", false,
                    "唤醒检测器不可用（模型未加载？），热更新链路无法验证");
            return;
        }
        String originalWake = live.wakeWord();
        String candidate = alternativeWakeWord(originalWake);

        AppConfig draft = live.copy();
        draft.setWakeWord(candidate);
        String err = env.applyConfig(draft);
        String after = env.wakeDetectorDescription();
        add("真实路径", "改唤醒词：检测器真的换成了新词（不必重启）",
                err == null && after != null && after.contains(candidate),
                err != null ? ("被拒绝：" + err)
                        : ("wake " + originalWake + " → " + candidate + "；detector=" + after));

        // 必须走一次真正的 apply 才能复原：只改内存里的配置不会重建检测器，
        // 那会把运行中的程序留在"在听测试词"的状态上。
        AppConfig back = env.config().copy();
        back.setWakeWord(originalWake);
        String backErr = env.applyConfig(back);
        String restored = env.wakeDetectorDescription();
        add("真实路径", "唤醒词复原（自检不留后遗症）",
                backErr == null && restored != null && restored.contains(originalWake),
                backErr != null ? backErr : restored);
    }

    /** 改静音秒数 → 计时器必须真的按新值算（原来只有"开/关"生效）。 */
    private void checkSilenceHotUpdate(Env env) {
        SilenceDetector sd = env.silenceDetector();
        if (sd == null) {
            add("真实路径", "改静音秒数真的生效", false, "拿不到静音计时器探针");
            return;
        }
        double before = sd.timeoutSeconds();
        // 换一个与当前不同的合法值（范围 0–15，0 表示关闭，所以别选 0）
        int target = Math.abs(before - 7) < 0.001 ? 4 : 7;

        AppConfig draft = env.config().copy();
        draft.setSilenceSeconds(target);
        String err = env.applyConfig(draft);
        double after = sd.timeoutSeconds();
        add("真实路径", "改静音超时秒数：计时器真的按新值算（原来只切开关）",
                err == null && Math.abs(after - target) < 1e-9,
                err != null ? ("被拒绝：" + err)
                        : String.format("timeout %.0f 秒 → %.0f 秒（期望 %d）", before, after, target));

        AppConfig back = env.config().copy();
        back.setSilenceSeconds((int) Math.round(before));
        String backErr = env.applyConfig(back);
        add("真实路径", "静音秒数复原",
                backErr == null && Math.abs(sd.timeoutSeconds() - before) < 1e-9,
                backErr != null ? backErr : String.format("timeout=%.0f 秒", sd.timeoutSeconds()));
    }

    /** Esc 必须真的接线：先断言轮询在跑，再**真的按一次 Esc** 看事件有没有到状态机。 */
    private void checkEscapeWiring(Env env) {
        if (!env.uiStarted()) {
            return;   // --doctor 不启动 UI，这一项无从验证：不添加即不声称
        }
        boolean running = env.escapeWatcherRunning();
        add("真实路径", "Esc 取消已接线（全局轮询在跑）", running,
                running ? "escape-watcher 线程在跑" : "没接线！按 Esc 不会有任何反应");

        StateMachine sm = env.stateMachine();
        if (!running || sm == null || !sm.idle()) {
            // 状态机不空闲时不按：真的按下去会把用户正在说的一段取消掉。
            // 不添加断言 = 不声称验证过，而不是伪造一个"通过"。
            return;
        }
        long before = sm.ignoredTotal();
        try {
            Robot robot = new Robot();
            robot.keyPress(KeyEvent.VK_ESCAPE);
            robot.keyRelease(KeyEvent.VK_ESCAPE);
            // 轮询间隔 50ms，留足余量
            robot.delay(600);
        } catch (Exception e) {
            add("真实路径", "按一次真实 Esc → CANCEL 送到状态机", false, e.toString());
            return;
        }
        long after = sm.ignoredTotal();
        add("真实路径", "按一次真实 Esc → CANCEL 真的送到了状态机", after > before,
                "ignoredTotal " + before + " → " + after
                        + "（待唤醒状态下 CANCEL 按设计被忽略并计数，所以计数增加就是送达的证据）");
    }

    /** 提示条必须不抢焦点 —— 否则"提示"本身会触发「前台已变」并把本段话弄丢。 */
    private void checkNoticeDoesNotStealFocus(Env env) {
        if (!env.uiStarted()) {
            return;
        }
        env.showNotice("自检：提示条", "这条提示由自检触发，用来确认它不抢焦点、也不遮挡悬浮球。");
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        java.awt.Window w = env.lastNoticeWindow();
        if (w == null) {
            add("真实路径", "提示条不抢焦点", false, "提示条没有出现（showNotice 没有生效）");
            return;
        }
        boolean noActivate = Win32WindowStyles.isNoActivate(w);
        long hwnd = Win32WindowStyles.hwndOf(w);
        long foreground = com.talkinglive.system.Win32.hwndValue(
                com.talkinglive.system.Win32.User32.INSTANCE.GetForegroundWindow());
        add("真实路径", "提示条不抢焦点（WS_EX_NOACTIVATE，且没成为前台窗口）",
                noActivate && hwnd != 0 && hwnd != foreground,
                "hwnd=0x" + Long.toHexString(hwnd) + "、前台=0x" + Long.toHexString(foreground)
                        + "、exStyle=0x" + Integer.toHexString(Win32WindowStyles.extendedStyles(w)));
        env.hideNotice();
    }

    /** 挑一个与当前不同、且确认可用的唤醒词备选（来自附录 B.1 的实测列表）。 */
    private static String alternativeWakeWord(String current) {
        for (String w : com.talkinglive.core.WordSuggestions.forField(
                com.talkinglive.core.WordSuggestions.FIELD_WAKE)) {
            if (!w.equals(current)) {
                return w;
            }
        }
        return "子曰";
    }

    // ============================================================ 纯逻辑补充

    /**
     * 这里只放 JUnit 里不适合放的两类：需要真实引擎的（词表查询），
     * 以及需要真实文件系统的（配置落盘往返）。
     */
    private void runCoreLogic() {
        // --- 配置落盘往返（真实文件系统，含原子替换） ---
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("tl-selftest");
            String old = System.getProperty(com.talkinglive.core.AppPaths.HOME_PROPERTY);
            System.setProperty(com.talkinglive.core.AppPaths.HOME_PROPERTY, dir.toString());
            try {
                com.talkinglive.core.AppPaths.ensureDirectories();
                var loaded = com.talkinglive.core.AppPaths.loadOrCreateConfig();
                add("config", "首次生成默认配置", loaded.created() && java.nio.file.Files.exists(
                        com.talkinglive.core.AppPaths.configFile()), "config.json 已写出");

                AppConfig c = loaded.config();
                c.setWakeWord("小助手");
                c.setEndWord("完毕");
                c.setSilenceSeconds(7);
                c.setAutoSend(true);
                c.setSendKey(AppConfig.SendKey.CTRL_ENTER);
                c.ball().setPosition(1234, 567);
                c.ball().setDock(AppConfig.DockSide.RIGHT);
                com.talkinglive.core.AppPaths.saveConfig(c);

                AppConfig back = com.talkinglive.core.AppPaths.loadOrCreateConfig().config();
                boolean same = back.wakeWord().equals("小助手")
                        && back.endWord().equals("完毕")
                        && back.silenceSeconds() == 7
                        && back.autoSend()
                        && back.sendKey() == AppConfig.SendKey.CTRL_ENTER
                        && back.ball().x() == 1234
                        && back.ball().y() == 567
                        && back.ball().dock() == AppConfig.DockSide.RIGHT;
                add("config", "保存后重新读取内容一致", same,
                        "含悬浮球位置与贴边状态（§12 #4）");

                // 非法配置必须被拒绝，而不是静默回退
                boolean rejected = false;
                String reason = null;
                try {
                    AppConfig bad = new AppConfig();
                    bad.setSilenceSeconds(99);
                    bad.validate();
                } catch (AppConfig.ConfigException e) {
                    rejected = true;
                    reason = e.getMessage();
                }
                add("config", "非法配置被拒绝（不静默回退）", rejected, reason);
            } finally {
                if (old == null) {
                    System.clearProperty(com.talkinglive.core.AppPaths.HOME_PROPERTY);
                } else {
                    System.setProperty(com.talkinglive.core.AppPaths.HOME_PROPERTY, old);
                }
                deleteRecursively(dir);
            }
        } catch (Exception e) {
            add("config", "配置落盘往返", false, e.toString());
        }

        // --- 真实引擎：模型加载 + 词表校验（附录 C 的静默失效） ---
        try (var model = com.talkinglive.engine.VoskModel.load(
                com.talkinglive.core.AppPaths.voskModelDir())) {
            add("engine", "Vosk 模型加载", true, model.path().toString());
            int wakeId = model.findWordId("子曰");
            int endId = model.findWordId("到此为止");
            int badId = model.findWordId("本段结束");
            add("engine", "词表查询：子曰在表内", wakeId >= 0, "wordId=" + wakeId + "（附录 B.1 记录 98204）");
            add("engine", "词表查询：到此为止在表内", endId >= 0, "wordId=" + endId + "（附录 B.1 记录 87877）");
            add("engine", "词表查询：本段结束不在表内（附录 C 的静默失效被拦住）",
                    badId < 0, "wordId=" + badId);

            MicValidator.Result ok = MicValidator.validate(model::findWord, "子曰", "到此为止");
            add("engine", "词表校验通过：子曰 / 到此为止", ok.ok(), null);
            MicValidator.Result bad = MicValidator.validate(model::findWord, "子曰", "本段结束");
            add("engine", "词表校验拦住「本段结束」", !bad.ok(),
                    bad.ok() ? "没拦住！" : "已拦截并给出备选词");

            // 语法构建（受限语法的形状可在无麦克风下验证）
            String g = com.talkinglive.engine.VoskKeywordDetector.buildGrammar("子曰", "到此为止");
            boolean grammarOk = g.equals("[\"子曰\",\"到此为止\",\"[unk]\"]");
            add("engine", "受限语法形状正确（纯数组，非 phrase_list 对象）", grammarOk, g);
            add("engine", "模型支持运行时词表（附录 B.2 的关键前提）", model.supportsRuntimeGrammar(),
                    "HCLr.fst + Gr.fst 均存在 —— 只有小模型能改运行时词表");

            // --- 英语唤醒词路线（实测结论，见 docs/ENGINE-EXPERIMENT.md §7.5）---
            //
            // 中文模型的词表里**拉丁 token 为 0 个**，所以 Firay 这类英文词永远不可能
            // 被识别到；但「飞瑞」这两个字各自都在表内，受限语法用单字序列
            // ["飞","瑞"] 就能匹配上说出口的 "Firay"（实测稳定命中）。
            // 这一段把它固化成每次自检都跑一遍的断言 —— 否则哪天词表/模型换了，
            // 用户只会看到"唤醒词没反应"，而程序毫无察觉。
            var spelling = com.talkinglive.engine.WakePhrase.resolve(model::findWord, "飞瑞");
            add("engine", "「飞瑞」可逐字拆开（英语唤醒词的落地方式）",
                    spelling.isPresent() && spelling.get().spelled(),
                    spelling.map(p -> "tokens=" + p.describeTokens()
                                    + "、语法=" + com.talkinglive.engine.VoskKeywordDetector
                                            .buildGrammar(p.tokens(), java.util.List.of("到此为止")))
                            .orElse("拆不开！英语唤醒词这条路断了"));
            add("engine", "整词在表内时不拆（「小助手」应保持整词）",
                    com.talkinglive.engine.WakePhrase.resolve(model::findWord, "小助手")
                            .map(p -> !p.spelled()).orElse(false),
                    "拆字只在整词不在表内时才用");
            add("engine", "「本段结束」不被拆字绕过（附录 B.1 的结论仍然有效）",
                    com.talkinglive.engine.WakePhrase.resolve(model::findWord, "本段结束").isEmpty(),
                    "它的四个字虽在表内，但拆开后真实语音里无法稳定命中");
            MicValidator.Result spelledCheck = MicValidator.validate(model::findWord,
                    "飞瑞", "到此为止",
                    word -> com.talkinglive.engine.WakePhrase.resolve(model::findWord, word)
                            .map(com.talkinglive.engine.WakePhrase::tokens)
                            .orElse(java.util.List.of()));
            add("engine", "词表校验放行逐字可用的唤醒词", spelledCheck.ok(),
                    spelledCheck.problems().isEmpty() ? "已放行并留下提示" : spelledCheck.message());
            add("engine", "拆字提示里带着**真实 token**（界面据此显示拆成了哪几个字）",
                    spelledCheck.spelled().size() == 1
                            && spelledCheck.spelled().get(0).tokens().equals(
                                    java.util.List.of("飞", "瑞")),
                    spelledCheck.spelled().isEmpty()
                            ? "没有拆字提示！"
                            : spelledCheck.spelled().get(0).brief());

            // --- 结束词是**可选项**：留空就该能用 ---
            //
            // 用户指出「可以有结束词，也意味着可以没有结束词，现在的限制是不能没有」。
            // 结束词只是三种收尾方式之一（另两种是静音超时、切换窗口），设成必填
            // 等于强迫用户接受一种不需要的收尾方式。这一段把"留空可用"焊死成断言，
            // 避免哪天又被哪一层单独拦回去（配置层、词表层、检测器是三处独立判断）。
            try {
                AppConfig blank = new AppConfig();
                blank.setEndWord("");
                blank.validate(); // 不抛异常才算通过
                add("engine", "结束词留空能通过配置校验", true, "结束词 = 「」（未启用）");
            } catch (Exception e) {
                add("engine", "结束词留空能通过配置校验", false, e.getMessage());
            }
            add("engine", "结束词留空时语法只剩唤醒词 + [unk]",
                    com.talkinglive.engine.VoskKeywordDetector
                            .buildGrammar("子曰", "").equals("[\"子曰\",\"[unk]\"]"),
                    com.talkinglive.engine.VoskKeywordDetector.buildGrammar("子曰", ""));
            MicValidator.Result noEnd = MicValidator.validate(model::findWord, "子曰", "");
            add("engine", "结束词留空不被词表校验报错", noEnd.ok(),
                    noEnd.ok() ? "已跳过（空 = 不用它，不是漏填）" : noEnd.message());
            try (var d = new com.talkinglive.engine.VoskKeywordDetector(
                    model, "子曰", "", hit -> { })) {
                add("engine", "结束词留空时检测器仍可用（只报唤醒，不报结束）",
                        d.available(), d.describe());
            } catch (Exception e) {
                add("engine", "结束词留空时检测器仍可用（只报唤醒，不报结束）", false, e.toString());
            }
        } catch (Exception e) {
            add("engine", "Vosk 模型加载", false,
                    e.getMessage() + "（模型缺失时产品仍应常驻并明确提示）");
        }

        // --- 注入器可用性 ---
        try {
            TextInjector inj = new com.talkinglive.system.WindowsTextInjector();
            add("system", "SendInput 注入器", inj.available(),
                    inj.available() ? inj.describe() : inj.unavailableReason());
        } catch (Throwable e) {
            add("system", "SendInput 注入器", false, e.toString());
        }

        // --- WAV 往返（M1 的验收路径） ---
        try {
            java.nio.file.Path f = java.nio.file.Files.createTempDirectory("tl-wav").resolve("t.wav");
            byte[] pcm = new byte[16000 * 2];
            for (int i = 0; i < pcm.length; i += 2) {
                short s = (short) (Math.sin(i / 20.0) * 8000);
                pcm[i] = (byte) (s & 0xFF);
                pcm[i + 1] = (byte) (s >> 8);
            }
            WavFile.writePcm16Mono16k(f, pcm);
            byte[] back = WavFile.readPcm16Mono16k(f);
            add("audio", "WAV 写入/读回一致", java.util.Arrays.equals(pcm, back),
                    f.getFileName() + " " + pcm.length + " 字节");
        } catch (Exception e) {
            add("audio", "WAV 写入/读回一致", false, e.toString());
        }
    }

    // ============================================================ 端到端管线

    /**
     * 用真实的 {@code StateMachine} / {@code PreviewText} / {@code CommitPolicy} /
     * {@code PunctuationProcessor} 串一遍完整听写流程，注入器换成假的。
     *
     * <p>这样验证的是**真实的状态机与文本管线**，只有「麦克风」与「Win32 注入」
     * 这两端被替身替代——它们在 §9.1 里属于「需要麦克风的手工集成验证」。
     */
    private void runPipeline() {
        FakeInjector injector = new FakeInjector();
        PreviewText preview = new PreviewText();
        CommitPolicy policy = new WholeSegmentPolicy();
        TextRefiner refiner = new TextRefiner() {
            @Override
            public Result refine(byte[] pcm, String previewText, boolean previewEnding) {
                // 模拟「精化比预览更准」：把预览的错误改对，并补上标点
                return Result.ok(previewText.replace("需球", "需求"), 42, "FakeRefiner");
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String unavailableReason() {
                return null;
            }

            @Override
            public String engineName() {
                return "FakeRefiner";
            }

            @Override
            public String describe() {
                return "FakeRefiner（自检用）";
            }

            @Override
            public void close() {}
        };

        AppConfig cfg = new AppConfig();
        PunctuationProcessor post = PunctuationProcessor.forWakeAndEndWords(
                cfg.wakeWord(), cfg.endWord());

        PipelineHarness h = new PipelineHarness(injector, preview, policy, refiner, post);
        StateMachine sm = h.sm;

        // --- 正常流程：预览 → 结束词 → 精化 → 注入 ---
        sm.handle(StateMachine.Event.WAKE_WORD);
        preview.setPartial("这个需球很明确");
        add("pipeline", "唤醒后进入 LISTENING", sm.listening(), "state=" + sm.state());
        preview.setPartial("这个需球很明确，我们做吧");
        sm.handle(StateMachine.Event.END_WORD);
        add("pipeline", "结束词后进入 COMMITTING 并回到 IDLE", sm.idle() && h.committed,
                "state=" + sm.state());
        add("pipeline", "精化纠正了预览的错误（需球→需求）",
                injector.lastText().contains("需求") && !injector.lastText().contains("需球"),
                "注入内容长度=" + TextUtils.codePointCount(injector.lastText()));
        add("pipeline", "注入前剔除了唤醒词/结束词",
                !injector.lastText().contains(cfg.wakeWord())
                        && !injector.lastText().contains(cfg.endWord()),
                "正文不含「" + cfg.wakeWord() + "」「" + cfg.endWord() + "」");
        add("pipeline", "最终文本带句末标点", TextUtils.endsWithSentencePunctuation(injector.lastText()),
                "末字=" + (injector.lastText().isEmpty() ? "无"
                        : injector.lastText().substring(injector.lastText().length() - 1)));
        add("pipeline", "整段注入调用次数", injector.injections() == 1,
                "injections=" + injector.injections());

        // --- Esc 取消：一个字都不注入 ---
        int before = injector.injections();
        sm.handle(StateMachine.Event.WAKE_WORD);
        preview.setPartial("这段不要");
        sm.handle(StateMachine.Event.CANCEL);
        add("pipeline", "Esc 取消后一个字都没注入", injector.injections() == before,
                "injections 仍为 " + injector.injections());

        // --- 切窗口：**照常提交**（原 §7 的「放弃注入」实测会丢掉整段话，已改） ---
        before = injector.injections();
        sm.handle(StateMachine.Event.WAKE_WORD);
        preview.setPartial("这段在切窗口后应当照常提交");
        sm.handle(StateMachine.Event.FOREGROUND_CHANGED);
        add("pipeline", "切窗口后仍照常提交（不再丢弃整段）",
                injector.injections() == before + 1 && sm.idle(),
                "state=" + sm.state() + " injections=" + injector.injections()
                        + "（原行为是丢弃，实测会让用户白说一段）");

        // --- 重复唤醒：忽略而不是重启段落 ---
        sm.handle(StateMachine.Event.WAKE_WORD);
        long gen = sm.generation();
        sm.handle(StateMachine.Event.WAKE_WORD);
        add("pipeline", "重复唤醒被忽略（不重启段落）",
                sm.generation() == gen && sm.ignoredCounts().getOrDefault(StateMachine.Event.WAKE_WORD, 0) > 0,
                "ignored WAKE_WORD=" + sm.ignoredCounts().getOrDefault(StateMachine.Event.WAKE_WORD, 0));

        // --- 提交中迟到事件 ---
        sm.handle(StateMachine.Event.END_WORD);
        int ignoredBefore = sm.ignoredTotal();
        sm.handle(StateMachine.Event.SILENCE_TIMEOUT);
        sm.handle(StateMachine.Event.END_WORD);
        add("pipeline", "提交中迟到事件被吃掉（§2.1）", sm.ignoredTotal() > ignoredBefore,
                "新增忽略 " + (sm.ignoredTotal() - ignoredBefore) + " 条");

        // --- 采集中断：本段必须收尾（原来会永远停在"听写中"） ---
        //
        //     所有收尾条件（静音超时、单段时长上限）都由 PCM 驱动；音频一断就再没有帧，
        //     而 App.onStreamError 原来只做 UI、**不给状态机任何事件**，于是段落永远
        //     停在 LISTENING、倒计时冻住，用户对着空气说话。
        int abandonsBefore = h.abandons;
        sm.handle(StateMachine.Event.WAKE_WORD);
        preview.setPartial("这段会因为麦克风中断而取消");
        sm.handle(StateMachine.Event.AUDIO_LOST);
        add("pipeline", "采集中断后本段被收尾（不再永远停在听写中）",
                sm.idle() && h.abandons == abandonsBefore + 1,
                "state=" + sm.state() + " abandons=" + h.abandons);

        // --- 提交中暂停：本段仍要落字（原来放行被吃掉 → 永久 COMMITTING） ---
        //
        //     必须让暂停发生在**精化返回之前**才复现得出来，所以这里让替身把
        //     REFINE_DONE 挂起（deferRefine），手动走完"暂停 → 精化回来"。
        int commitsBefore = h.commits;
        before = injector.injections();
        h.deferRefine = true;
        sm.handle(StateMachine.Event.WAKE_WORD);
        preview.setPartial("提交中暂停也不该把这一段丢掉");
        sm.handle(StateMachine.Event.END_WORD);
        sm.handle(StateMachine.Event.PAUSE);
        add("pipeline", "提交中暂停：状态仍是 COMMITTING（段落没被掐死）",
                sm.committing() && sm.paused(),
                "state=" + sm.state() + " paused=" + sm.paused());
        h.deferRefine = false;
        sm.handle(StateMachine.Event.REFINE_DONE);
        add("pipeline", "提交中暂停后精化完成：仍照常落字",
                sm.idle() && h.commits == commitsBefore + 1 && injector.injections() == before + 1,
                "state=" + sm.state() + " commits=" + h.commits
                        + " injections=" + injector.injections());
        sm.handle(StateMachine.Event.RESUME);   // 复原，别影响后面的用例

        // --- 提交超时：最后一道网（否则精化线程一卡住就永久 COMMITTING） ---
        h.deferRefine = true;
        sm.handle(StateMachine.Event.WAKE_WORD);
        preview.setPartial("这一段会等到看门狗超时");
        sm.handle(StateMachine.Event.END_WORD);
        abandonsBefore = h.abandons;
        sm.handle(StateMachine.Event.COMMIT_TIMEOUT);
        h.deferRefine = false;
        add("pipeline", "提交超时后本段被取消并回到待唤醒（不再永久卡住）",
                sm.idle() && h.abandons == abandonsBefore + 1,
                "state=" + sm.state() + " abandons=" + h.abandons);

        // --- 全局兜底：未捕获异常不能无声消失（javaw 下没有控制台） ---
        add("pipeline", "全局未捕获异常处理器已安装",
                Thread.getDefaultUncaughtExceptionHandler() != null,
                Thread.getDefaultUncaughtExceptionHandler() == null
                        ? "没有安装：线程里的异常会连日志都不留"
                        : "异常栈会写进日志文件");

        // --- 暂停 ---
        sm.handle(StateMachine.Event.PAUSE);
        sm.handle(StateMachine.Event.WAKE_WORD);
        add("pipeline", "暂停时忽略唤醒词", sm.idle() && sm.paused(), "state=" + sm.state());
        sm.handle(StateMachine.Event.RESUME);
        sm.handle(StateMachine.Event.WAKE_WORD);
        add("pipeline", "恢复后能再次唤醒", sm.listening(), "state=" + sm.state());
        sm.handle(StateMachine.Event.CANCEL);

        // --- 注入失败必须可见 ---
        injector.failNext("模拟：目标程序以管理员运行，UIPI 隔离");
        sm.handle(StateMachine.Event.WAKE_WORD);
        preview.setPartial("这次注入会失败");
        sm.handle(StateMachine.Event.END_WORD);
        add("pipeline", "注入失败被显式回报（不静默）", injector.lastFailure() != null,
                injector.lastFailure());

        // --- 静音超时路径 ---
        SilenceDetector sd = new SilenceDetector(5);
        sd.reset();
        boolean fired = false;
        for (int i = 0; i < 200 && !fired; i++) {
            // 先给 0.5 秒有效语音，再持续静音
            fired = sd.accept(i < 5 ? 0.2 : 0.0001, 0.1);
        }
        add("pipeline", "静音满 5 秒触发一次（§8）", fired, "silentSeconds=" + sd.silentSeconds());
        add("pipeline", "静音计时只触发一次", !sd.accept(0.0001, 1.0), "latched=" + sd.latched());

        // --- 码点账本（§4.3） ---
        DictationSession cp = new DictationSession(1, 0, "t", 1);
        cp.appendInjected("a\ud83d\ude00b");   // a + 😀（代理对）+ b
        add("pipeline", "注入账本按码点计数（代理对不数错）", cp.injectedCodePointCount() == 3,
                "codePoints=" + cp.injectedCodePointCount() + "（UTF-16 长度=" + cp.injectedText().length() + "）");
    }

    // ============================================================ UI 自检

    private void runUi() {
        Robot robot;
        try {
            robot = new Robot();
        } catch (Exception e) {
            add("UI", "Robot 可用", false, "无法创建 Robot：" + e);
            return;
        }
        Point home = mouseLocation();

        FloatingBall ball = null;
        PreviewBar bar = null;
        try {
            final FloatingBall[] ballRef = new FloatingBall[1];
            final PreviewBar[] barRef = new PreviewBar[1];
            onEdt(() -> {
                ballRef[0] = new FloatingBall(null, new FloatingBall.Listener() {
                    @Override
                    public void onLeftClick() {}

                    @Override
                    public void onTogglePause() {}

                    @Override
                    public void onOpenSettings() {}

                    @Override
                    public void onOpenLog() {}

                    // 自检里的替身不接"重置位置"：它只验"窗口属性/菜单能否弹出/贴边收起"，
                    // 不验"重置真的执行了"—— 那需要真 App（BallActions）。
                    // 关掉/挪动球会让后面的断言失去对象，所以这里刻意什么也不做。
                    @Override
                    public void onResetPosition() {}

                    @Override
                    public void onQuit() {}
                });
                // 扩展样式必须在窗口第一次显示之前设置（§4.4）
                ballRef[0].addNotify();
                Win32WindowStyles.applyNoActivateToolWindow(ballRef[0]);
                ballRef[0].setLocation(120, 120);
                ballRef[0].setVisible(true);

                barRef[0] = new PreviewBar(null);
                barRef[0].addNotify();
                Win32WindowStyles.applyNoActivateToolWindow(barRef[0]);
            });
            ball = ballRef[0];
            bar = barRef[0];
            // lambda 只能捕获 effectively-final 的量，因此在自检主体开头固定引用
            final FloatingBall uiBall = ball;
            final PreviewBar uiBar = bar;
            robot.delay(500);

            // A) 不抢焦点：AWT 层与 Win32 层都要成立
            add("UI", "悬浮球 AWT 层不参与焦点", !uiBall.isFocusableWindow(), "isFocusableWindow=false");
            add("UI", "悬浮球带 WS_EX_NOACTIVATE", Win32WindowStyles.isNoActivate(uiBall),
                    "exStyle=0x" + Integer.toHexString(Win32WindowStyles.extendedStyles(uiBall)));
            add("UI", "悬浮球带 WS_EX_TOOLWINDOW（不进 Alt+Tab）", Win32WindowStyles.isToolWindow(uiBall),
                    "exStyle=0x" + Integer.toHexString(Win32WindowStyles.extendedStyles(uiBall)));
            add("UI", "浮窗预览条带 WS_EX_NOACTIVATE", Win32WindowStyles.isNoActivate(uiBar),
                    "exStyle=0x" + Integer.toHexString(Win32WindowStyles.extendedStyles(uiBar)));
            add("UI", "设置窗口不设 NOACTIVATE（它本来就该能聚焦）",
                    !Win32WindowStyles.isNoActivate(new SettingsWindow(new StubHost())), null);

            // B) 菜单能否从「不抢焦点」的窗口弹出——曾经的真实风险点（§4.4）
            final boolean[] direct = {false};
            onEdt(() -> uiBall.showMenuAt(uiBall.getWidth() / 2, uiBall.getHeight() / 2));
            robot.delay(700);
            direct[0] = menuOpen();
            add("UI", "A. 菜单可直接从不抢焦点窗口弹出", direct[0],
                    direct[0] ? "弹出成功" : "没有弹出（需改用自绘弹层）");
            dismiss(robot);

            // C) Robot 真实右键：鼠标事件能否投递到不抢焦点的窗口
            //    用 MouseInfo 核对鼠标**真的**移到了目标点——若坐标空间对不上
            //    （例如手动乘了一次 DPI 缩放），这里会立刻暴露，而不是表现为「菜单没弹」。
            final Point pos = locationOnScreen(uiBall);
            final int cx = pos.x + uiBall.getWidth() / 2;
            final int cy = pos.y + uiBall.getHeight() / 2;
            boolean clicked;
            String clickDetail;
            Point actualForTest;
            try {
                robot.mouseMove(cx, cy);
                robot.delay(350);
                Point actual = mouseLocation();
                actualForTest = actual;
                boolean atSpot = Math.abs(actual.x - cx) <= 2 && Math.abs(actual.y - cy) <= 2;
                robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
                robot.delay(120);
                robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
                robot.delay(700);
                clicked = menuOpen();
                clickDetail = "目标=" + cx + "," + cy + " 实际=" + actual.x + "," + actual.y
                        + (atSpot ? "（到位）" : "（★未到位，坐标空间可能对不上）")
                        + " 窗口位置=" + pos.x + "," + pos.y;
            } catch (Exception e) {
                clicked = false;
                actualForTest = new Point(0, 0);
                clickDetail = "异常：" + e;
            }
            add("UI", "B. Robot 真实右键能投递到悬浮球", clicked,
                    (clicked ? "弹出成功  " : "没有弹出  ") + clickDetail
                            + "  ballVisible=" + uiBall.isVisible()
                            + " showing=" + uiBall.isShowing()
                            + " " + windowUnderPoint(actualForTest)
                            + " foreground='"
                            + com.talkinglive.system.ForegroundWatcher.title(
                                    com.talkinglive.system.CaretTracker.foregroundWindow()) + "'");
            dismiss(robot);

            // D) 贴边收起与滑出
            final Rectangle screen = graphicsBounds(uiBall);
            onEdt(() -> uiBall.setLocation(screen.x + screen.width - 10, pos.y));
            onEdt(uiBall::dockForTest);
            robot.delay(700);
            boolean docked = uiBall.isDockedHidden();
            int hiddenX = uiBall.getX();
            add("UI", "D1. 拖到屏幕边缘后贴边收起", docked,
                    "已收起  窗口 x=" + hiddenX + "（露出 20px 窗口 ≈ 12px 球体）");

            // 把鼠标移到露出的一小条上（逻辑坐标直接用，不再乘缩放）
            robot.mouseMove(screen.x + screen.width - 5, uiBall.getY() + uiBall.getHeight() / 2);
            robot.delay(900);
            boolean revealed = uiBall.revealed();
            int nowX = uiBall.getX();
            add("UI", "D2. 鼠标移到露出部分后滑出", revealed && nowX != hiddenX,
                    "revealed=" + revealed + " 窗口 x=" + nowX
                            + "（mouseEntered 共 " + uiBall.enterCountForTest() + " 次"
                            + "—— 实测这个计数常常是 0，所以必须轮询）");

            // 移开鼠标后应由轮询收回
            robot.mouseMove(Math.max(screen.x + 20, pos.x - 200), Math.max(screen.y + 20, pos.y - 200));
            robot.delay(1100);
            add("UI", "D3. 鼠标移开后自动收回（轮询，非 mouseEntered）", uiBall.isDockedHidden(),
                    "勾选状态=" + uiBall.isDockedHidden());

            // E) 浮窗两级文字样式
            final String[] texts = new String[2];
            onEdt(() -> {
                uiBar.render("这个需求", "很明确", "听写中", new Point(300, 300));
                texts[0] = uiBar.textForTest();
                texts[1] = uiBar.statusForTest();
            });
            robot.delay(300);
            // 断言**行为**（两段用了不同的颜色）而不是写死色值。
            // 上一版这里写死了 "#eef1f8" / "#717890" —— 那是深色主题的值，
            // 换白色主题时这条自检立刻失败。它失败得对（说明断言过期了），
            // 但它测错了东西：§4.4 要求的是"已稳定部分与仍在变动的尾部**可区分**"，
            // 而不是"必须是这两个色号"。现在改成拿 Theme 的实际色值去比。
            String stableHex = hex(Theme.TEXT_STABLE);
            String volatileHex = hex(Theme.TEXT_VOLATILE);
            boolean twoTier = texts[0] != null
                    && texts[0].contains(stableHex) && texts[0].contains(volatileHex)
                    && !stableHex.equalsIgnoreCase(volatileHex);
            add("UI", "E. 浮窗两级文字样式（已稳定/仍在变）", twoTier,
                    "稳定=" + stableHex + " 变动=" + volatileHex + " 两段都在=" + twoTier);
            add("UI", "E. 浮窗显示状态行", texts[1] != null && texts[1].contains("听写中"),
                    "status=" + texts[1]);

            // F) 设置窗口能打开（关掉不等于退出由 HIDE_ON_CLOSE 保证）
            final boolean[] settingsOk = {false};
            final String[] settingsDetail = {""};
            onEdt(() -> {
                SettingsWindow w = new SettingsWindow(new StubHost());
                w.setVisible(true);
                settingsOk[0] = w.isVisible() && w.getDefaultCloseOperation()
                        == javax.swing.WindowConstants.HIDE_ON_CLOSE;
                // 精简后的契约：只有 5 个可改项、没有页签。用户第 4 次反馈「太繁琐」，
                // 这条断言是防止界面又长回去的闸门。
                settingsDetail[0] = "HIDE_ON_CLOSE, 尺寸=" + w.getWidth() + "x" + w.getHeight()
                        + ", 可改项=" + w.editableControlCount();
                w.dispose();
            });
            robot.delay(300);
            add("UI", "F. 设置窗口可打开且关闭 != 退出", settingsOk[0], settingsDetail[0]);

            // F1) 悬浮球的声浪柱确实随电平变化
            //     本轮的核心外观需求：「柱子跟随收音的声浪大小变化」。
            //     用离屏渲染把画面变成像素来比对 —— 不依赖截图，也不要求当时真有人说话。
            final double[] levels = new double[2];
            final long[] ink = new long[2];       // 声浪柱的像素数（只数蓝色柱子）
            final boolean[] differs = {false};
            onEdt(() -> {
                uiBall.setState("LISTENING");
                // 安静：电平 0
                uiBall.setLevel(0);
                uiBall.settleLevelForTest();
                levels[0] = uiBall.displayLevelForTest();
                java.awt.image.BufferedImage quiet = uiBall.renderForTest();
                ink[0] = barPixels(quiet);
                // 大声：明显超过满量程的一部分
                uiBall.setLevel(0.15);
                uiBall.settleLevelForTest();
                levels[1] = uiBall.displayLevelForTest();
                java.awt.image.BufferedImage loud = uiBall.renderForTest();
                ink[1] = barPixels(loud);
                differs[0] = !sameImage(quiet, loud);
                uiBall.setLevel(0);
                uiBall.setState("IDLE");
            });
            robot.delay(200);
            // F0) 字体覆盖中文
            //     来自真实反馈「日志还是乱码」：日志**内容**没问题（文件是带 BOM 的
            //     UTF-8），出问题的是**画不出来** —— Theme.mono() 当时选到 Consolas，
            //     而它没有中文字形，每个汉字都成了带问号的方块（截图确认）。
            //     这里对所有会被渲染的字体做一次 canDisplayUpTo 检查（-1 = 全有字形）。
            String cjkSample = "日志测试状态诊断唤醒词结束词静音麦克风模型配置";
            java.awt.Font bodyFont = Theme.font(12);
            java.awt.Font monoFont = Theme.mono(11);
            StringBuilder fontDetail = new StringBuilder();
            boolean fontsOk = true;
            for (String[] pair : new String[][] {
                    {"正文", null}, {"等宽", null}}) {
                java.awt.Font f = "等宽".equals(pair[0]) ? monoFont : bodyFont;
                int bad = f.canDisplayUpTo(cjkSample);
                fontDetail.append(pair[0]).append('=').append(f.getFamily())
                        .append(bad < 0 ? "(OK) " : "(**缺字形**) ");
                if (bad >= 0) {
                    fontsOk = false;
                }
            }
            add("UI", "F0. 界面字体覆盖中文（日志方块事故）", fontsOk, fontDetail.toString());

            // 大声时柱子更高 → 柱子像素必须明显更多；且两张图必须不同
            add("UI", "F1. 声浪柱随电平变化（安静 vs 大声）",
                    levels[1] > levels[0] && differs[0] && ink[1] > ink[0],
                    "电平 " + String.format("%.2f→%.2f", levels[0], levels[1])
                            + "、柱子像素 " + ink[0] + "→" + ink[1]
                            + "、画面不同=" + differs[0]);

            // F2) 精简契约：设置页只应有 5 个可改项（唤醒词/结束词/静音/自动发送/发送键），
            //     且没有页签 —— 日志/状态/自检已搬到独立的诊断窗口。
            //     数字是**从界面真数出来的**，不是一个可能漂移的常量。
            final int[] editable = {-1};
            final int[] tabs = {-1};
            final String[] labels = {""};
            onEdt(() -> {
                SettingsWindow w = new SettingsWindow(new StubHost());
                editable[0] = w.editableControlCount();
                tabs[0] = countTabs(w);
                labels[0] = String.join("/", w.visibleLabels());
                w.dispose();
            });
            robot.delay(200);
            add("UI", "F2. 设置页只留必需项（5 项，无页签）",
                    editable[0] == 5 && tabs[0] == 0,
                    "可改项=" + editable[0] + "、页签=" + tabs[0] + "、标签=" + labels[0]);

            // F2b) 必需项本身必须还在（精简不等于把功能删掉）
            add("UI", "F2b. 必需项标签齐全",
                    labels[0].contains("唤醒词") && labels[0].contains("结束词")
                            && labels[0].contains("静音超时") && labels[0].contains("发送键"),
                    labels[0]);

            // F3) 诊断窗口（状态/日志/自检）已从设置里搬出来，能独立打开
            final boolean[] diagOk = {false};
            final int[] diagTabs = {-1};
            onEdt(() -> {
                DiagnosticsWindow w = new DiagnosticsWindow(new StubDiagnosticsHost());
                w.setVisible(true);
                diagTabs[0] = countTabs(w);
                diagOk[0] = w.isVisible() && w.getDefaultCloseOperation()
                        == javax.swing.WindowConstants.HIDE_ON_CLOSE && diagTabs[0] == 3;
                w.dispose();
            });
            robot.delay(300);
            add("UI", "F3. 诊断窗口独立（状态/日志/自检 3 页）", diagOk[0],
                    "页签=" + diagTabs[0]);

            // F4) 状态提示**放不下时换行、而不是打省略号**。
            //
            // 用户两次发来截图，看到的都是「唤醒词「飞瑞」已拆成单字「飞」…」——
            // 省略号恰好落在"拆成了哪几个字"上。第一次我改短文案、第二次我加宽标签，
            // 都没解决，因为错的是**"截断"这个做法本身**。现在换成换行，并用这一条
            // 断言把它焊死：状态文字在可见部分里丢掉任何内容就算失败。
            //
            // 用**真实配置生成的真实警告**（而不是写死一段样例）：文案改了、标签尺寸改了，
            // 这条断言都会跟着变。曾经写死样例，结果真警告被改短后断言还在检查旧文本。
            com.talkinglive.core.AppConfig warnCfg = new com.talkinglive.core.AppConfig();
            warnCfg.setEndWord("");
            warnCfg.setSilenceSeconds(0);
            String warnText = String.join(" ", warnCfg.warnings());
            if (warnText.isEmpty()) {
                add("UI", "F4. 状态提示换行不截断（省略号会丢掉关键词）", false,
                        "触发警告的配置没有产生警告 —— 先修 warnings()");
                warnText = "（无）";
            }
            final String statusSample = warnText;
            final boolean[] fits = {false};
            final String[] detail = {""};
            onEdt(() -> {
                SettingsWindow w = new SettingsWindow(new StubHost());
                javax.swing.JLabel label = findStatusLabel(w);
                if (label == null) {
                    detail[0] = "找不到状态标签";
                } else {
                    java.awt.FontMetrics fm = label.getFontMetrics(label.getFont());
                    java.awt.Insets in = label.getInsets();
                    int width = label.getPreferredSize().width - in.left - in.right - 6;
                    int lines = Math.max(1, (label.getPreferredSize().height - in.top - in.bottom)
                            / fm.getHeight());
                    String wrapped = com.talkinglive.ui.SettingsWindow
                            .wrapToFit(statusSample, width, lines, fm);
                    boolean allFit = true;
                    int widest = 0;
                    for (String line : wrapped.split("\n")) {
                        int lw = fm.stringWidth(line);
                        widest = Math.max(widest, lw);
                        if (lw > width) {
                            allFit = false;
                        }
                    }
                    fits[0] = allFit && !wrapped.contains(
                            com.talkinglive.ui.SettingsWindow.ELISION_MARK);
                    detail[0] = "标签 " + label.getPreferredSize().width + "×"
                            + label.getPreferredSize().height + "px、行高=" + fm.getHeight()
                            + "、可用行=" + lines + "、宽=" + width + "px、最宽一行 " + widest + "px"
                            + "｜allFit=" + allFit
                            + "、含省略标记=" + wrapped.contains(
                                    com.talkinglive.ui.SettingsWindow.ELISION_MARK)
                            + "｜折成 " + wrapped.split("\n").length + " 行"
                            + "｜实际=" + wrapped.replace("\n", "⏎");
                }
                w.dispose();
            });
            robot.delay(200);
            add("UI", "F4. 状态提示换行不截断（省略号会丢掉关键词）", fits[0], detail[0]);

            // G) 多显示器虚拟屏幕（§4.4 夹在屏幕范围内 / 边缘翻转都依赖它）
            Rectangle vb = com.talkinglive.system.DpiScale.virtualBounds();
            add("UI", "G. 虚拟屏幕范围可得（多显示器）", vb.width > 0 && vb.height > 0,
                    "virtualBounds=" + vb.x + "," + vb.y + " " + vb.width + "x" + vb.height);

            // H) 拖动夹紧：把球放到屏幕外应被夹回
            onEdt(() -> {
                uiBall.setLocationForTest(vb.x + vb.width + 5000, vb.y + vb.height + 5000);
                uiBall.clampIntoPlace();
            });
            add("UI", "H. 悬浮球位置被夹在屏幕内（§4.4）", vb.contains(uiBall.getLocation()),
                    "位置=" + uiBall.getX() + "," + uiBall.getY());
        } catch (Exception e) {
            add("UI", "UI 自检执行", false, e.toString());
        } finally {
            if (ball != null) {
                FloatingBall b = ball;
                onEdt(b::dispose);
            }
            if (bar != null) {
                PreviewBar p = bar;
                onEdt(p::dispose);
            }
            try {
                robot.mouseMove(home.x, home.y);
            } catch (RuntimeException ignored) {
                // 还原鼠标位置失败无需处理
            }
        }
    }

    // ============================================================ UI 工具

    /** 在 EDT 上执行。**只用来触碰 Swing 状态，不要在里面 delay。** */
    private static void onEdt(Runnable action) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
            } else {
                SwingUtilities.invokeAndWait(action);
            }
        } catch (Exception ignored) {
            // 自检不因单点失败中断
        }
    }

    private static <T> T get(Supplier<T> supplier, T fallback) {
        final Object[] box = {fallback};
        onEdt(() -> box[0] = supplier.get());
        @SuppressWarnings("unchecked")
        T value = (T) box[0];
        return value;
    }

    private static boolean menuOpen() {
        return get(() -> {
            MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
            return path != null && path.length > 0;
        }, false);
    }

    private static void dismiss(Robot robot) {
        robot.keyPress(KeyEvent.VK_ESCAPE);
        robot.keyRelease(KeyEvent.VK_ESCAPE);
        robot.delay(250);
    }

    private static Point mouseLocation() {
        try {
            return java.awt.MouseInfo.getPointerInfo().getLocation();
        } catch (Exception e) {
            return new Point(0, 0);
        }
    }

    private static Point locationOnScreen(FloatingBall ball) {
        return get(() -> {
            try {
                return ball.getLocationOnScreen();
            } catch (Exception e) {
                return new Point(ball.getX(), ball.getY());
            }
        }, new Point(ball.getX(), ball.getY()));
    }

    private static Rectangle graphicsBounds(java.awt.Window w) {
        return get(() -> w.getGraphicsConfiguration().getBounds(), new Rectangle(0, 0, 1920, 1080));
    }

    /**
     * 问 Windows：这个屏幕点上是谁。
     *
     * <p>这是排查「点了没反应」最快的一条路——如果返回的句柄不是悬浮球，
     * 就说明有别的窗口盖在上面（实测遇到过一次：无麦克风时反复弹出的提示对话框
     * 正好盖在悬浮球上，导致右键永远点不到）。
     *
     * @return 可直接写进报告的一行诊断文本
     */
    private static String windowUnderPoint(Point p) {
        try {
            com.sun.jna.platform.win32.WinDef.POINT pt =
                    new com.sun.jna.platform.win32.WinDef.POINT();
            pt.x = p.x;
            pt.y = p.y;
            com.sun.jna.platform.win32.WinDef.HWND h =
                    com.talkinglive.system.Win32.User32.INSTANCE.WindowFromPoint(pt);
            long hwnd = com.talkinglive.system.Win32.hwndValue(h);
            return "pointUnder=0x" + Long.toHexString(hwnd)
                    + "('" + com.talkinglive.system.ForegroundWatcher.title(hwnd) + "')";
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return "pointUnder=（查询失败 " + e + "）";
        }
    }

    private static void deleteRecursively(java.nio.file.Path dir) {
        try (var walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理失败不影响自检结论
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }

    // ============================================================ 替身

    /** 记录调用的假注入器：替掉 Win32 那一端，其余全是真代码。 */
    static final class FakeInjector implements TextInjector {
        private final List<String> calls = new ArrayList<>();
        private String lastFailure;
        private String failNext;
        private int injections;

        @Override
        public Result inject(int backspaces, String text) {
            if (failNext != null) {
                lastFailure = failNext;
                String f = failNext;
                failNext = null;
                return Result.fail(Result.Failure.UIPI_BLOCKED, f);
            }
            calls.add(text);
            injections++;
            return Result.ok(text.length() * 2);
        }

        @Override
        public Result press(KeyCombo combo) {
            calls.add("<" + combo.display() + ">");
            return Result.ok(2);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String unavailableReason() {
            return null;
        }

        String lastText() {
            return calls.isEmpty() ? "" : calls.get(calls.size() - 1);
        }

        String lastFailure() {
            return lastFailure;
        }

        int injections() {
            return injections;
        }

        void failNext(String reason) {
            this.failNext = reason;
        }
    }

    /** AWT 颜色 → HTML 用的 {@code #rrggbb}（与 PreviewBar 里的渲染保持一致）。 */
    private static String hex(java.awt.Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /**
     * 统计**声浪柱**的像素数（只数偏蓝的像素）。
     *
     * <p>不能数"不透明像素"：白球本身就有两千多个不透明像素，柱子高矮完全被淹没了
     * —— 第一版就是数错了量，结果电平 0.00→0.68 而数字一动不动（2314→2314）。
     * 这里用"蓝通道明显高于红通道"来认柱子：听写中的柱子是系统蓝 #007aff，
     * 而球体是白的、描边是灰的、投影是黑的，三者都满足 R≳B。
     */
    private static long barPixels(java.awt.image.BufferedImage img) {
        long n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = argb >>> 24;
                int r = (argb >> 16) & 0xFF;
                int b = argb & 0xFF;
                if (a > 40 && b > r + 40) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 两张离屏渲染图是否逐像素相同。 */
    private static boolean sameImage(java.awt.image.BufferedImage a,
            java.awt.image.BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 数页签数量（没有 JTabbedPane 就是 0 —— 设置窗口精简后应当如此）。 */
    private static int countTabs(java.awt.Container c) {
        int n = 0;
        for (java.awt.Component comp : c.getComponents()) {
            if (comp instanceof javax.swing.JTabbedPane t) {
                n += t.getTabCount();
            }
            if (comp instanceof java.awt.Container inner) {
                n += countTabs(inner);
            }
        }
        return n;
    }

    /**
     * 找出设置窗口的状态提示标签（见 F4）。
     *
     * <p>按"文本是单个空格"定位：那是 {@code clearStatus()} 设的空占位，
     * 这个文本在整个窗口里唯一。找不到就返回 null（调用方会报"找不到状态标签"，
     * 而不是悄悄通过 —— 静默通过的断言等于没有断言）。
     */
    private static javax.swing.JLabel findStatusLabel(java.awt.Container c) {
        for (java.awt.Component comp : c.getComponents()) {
            if (comp instanceof javax.swing.JLabel l && " ".equals(l.getText())) {
                return l;
            }
            if (comp instanceof java.awt.Container inner) {
                javax.swing.JLabel found = findStatusLabel(inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 设置窗口自检用的最小 Host。 */
    static final class StubHost implements SettingsWindow.Host {
        private final AppConfig cfg = new AppConfig();

        @Override
        public AppConfig config() {
            return cfg;
        }

        @Override
        public String applyConfig(AppConfig candidate) {
            return null;
        }

        @Override
        public Boolean wordInVocabulary(String word) {
            return true;
        }
    }

    /** 诊断窗口自检用的最小 Host。 */
    static final class StubDiagnosticsHost implements DiagnosticsWindow.Host {

        @Override
        public List<StatusLine> status() {
            return List.of(new StatusLine("词表:唤醒词", "子曰", true, "在词表内"));
        }

        @Override
        public String diagnosticsReport() {
            return "（自检占位）";
        }
    }
}
