package com.talkinglive.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 听写状态机（{@code DESIGN.md} §2.1）。**整个程序的骨架。**
 *
 * <p>设计要点，逐条对应文档：
 * <ul>
 *   <li>任何时刻只处于且仅处于一个状态。</li>
 *   <li><b>非法事件一律忽略并记录，不抛异常。</b>真实场景里迟到事件是常态：引擎在
 *       COMMITTING 阶段还会吐出最后一个中间结果、静音计时可能与结束词同时到达、
 *       窗口监听可能重复触发。状态机的职责是吃掉这些。</li>
 *   <li>纯逻辑，不依赖 AWT / JNA / 引擎原生库，因此可完整单测（§4.5 / §9.1）。</li>
 * </ul>
 *
 * <p>使用方式：调用 {@link #handle(Event)} 投递事件，通过 {@link Listener} 接收状态变化
 * 与「本段结束」的指令。状态机自己做任何 IO 或识别——它只做决策。
 *
 * <p><b>线程安全</b>：所有方法 {@code synchronized}。音频线程、UI 线程、注入线程都会投递事件。
 */
public final class StateMachine {

    private static final Logger log = LoggerFactory.getLogger(StateMachine.class);

    /** 三个状态，不多不少。 */
    public enum State {
        /** 待唤醒。 */
        IDLE("待唤醒"),
        /** 听写中。 */
        LISTENING("听写中"),
        /** 提交中。 */
        COMMITTING("提交中");

        private final String display;

        State(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    /** 状态机的输入事件。 */
    public enum Event {
        /** 听到唤醒词。 */
        WAKE_WORD,
        /** 听到结束词。 */
        END_WORD,
        /** 静音满 N 秒。 */
        SILENCE_TIMEOUT,
        /** 前台窗口变化（§7：结束本段但放弃注入）。 */
        FOREGROUND_CHANGED,
        /** 单段录音达到时长上限（§7：自动结束本段）。 */
        MAX_SEGMENT_REACHED,
        /**
         * 采集中断（设备被抢占 / 被拔掉）。
         *
         * <p><b>为什么必须有这个事件：</b>所有收尾条件（静音超时、单段时长上限）
         * 都是在**收到 PCM 时**才投递的（{@code App.CaptureBridge.onPcm}）。
         * 音频一断就再也没有帧，于是静音计时冻结、时长上限也永远不到 ——
         * 状态机会停在 LISTENING 不动，而悬浮球还在显示"听写中 · 静音 N 秒后结束"
         * （倒计时已经冻住）。用户对着空气说话，且没有任何提示。
         *
         * <p>这条路径原来只做 UI（悬浮球变暗 + 提示），**不给状态机任何事件**。
         */
        AUDIO_LOST,
        /**
         * 提交超时：进入 COMMITTING 后久等不到 {@link #INJECTED}。
         *
         * <p>由调用方的看门狗投递（状态机自己不依赖时钟，见类注释）。
         * 存在的理由：{@code REFINE_DONE} 的**唯一生产者**是应用层的精化线程，
         * 它一旦异常退出或卡住，状态机就永久停在 COMMITTING ——
         * 此后唤醒词/悬浮球/结束词/静音全被忽略，只能重启进程。
         */
        COMMIT_TIMEOUT,
        /** Esc：整段取消，一个字都不注入。 */
        CANCEL,
        /** 悬浮球左键：IDLE→开始，LISTENING→结束。 */
        TOGGLE,
        /** 菜单「暂停监听」。 */
        PAUSE,
        /** 菜单「恢复监听」。 */
        RESUME,
        /** 内部：精化完成，可以注入。 */
        REFINE_DONE,
        /** 内部：注入与自动发送都已完成。 */
        INJECTED
    }

    /** 本段结束的原因，决定「要不要真的注入」。 */
    public enum EndReason {
        /** 听到结束词。 */
        END_WORD("听到结束词"),
        /** 静音超时。 */
        SILENCE_TIMEOUT("静音超时"),
        /** 单段达到时长上限。 */
        MAX_SEGMENT("达到单段时长上限"),
        /** 悬浮球手动结束。 */
        MANUAL("手动结束"),
        /**
         * 前台窗口在录音期间变了。
         *
         * <p>原设计（§7）规定这条路径「结束本段但**放弃注入**」。
         * 实测发现那等于把用户刚说的一整段话丢掉，而切窗口往往只是无意的
         * （输入法候选框、通知、或手滑点别处）。现在改为**照常提交**，
         * 由注入层尝试把焦点还原回目标（{@code WindowsTextInjector.inject}）。
         */
        FOREGROUND_CHANGED("前台窗口变化");

        private final String display;

        EndReason(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }

    }

    /**
     * 提交阶段的上下文，随 {@link Listener#onCommitReady} 一起交给调用方。
     *
     * @param generation 该段落的代数；与 {@link #generation()} 不符说明用户已经取消并开了新段
     * @param inject     是否应该注入（§7：提交时前台窗口已变则放弃注入）
     * @param reason     本段结束的原因
     */
    public record CommitContext(long generation, boolean inject, EndReason reason) {}

    /**
     * 状态机对外的事件回调。
     *
     * <p>一次听写的完整回调顺序（这是唯一的流程定义，调用方按它实现即可）：
     * <pre>
     *   onSegmentStartRequested()                 进入 LISTENING，开始录音
     *   onSegmentEndRequested(reason)             停止录音；同步定出最终文本（可用预览文本兜底）
     *   onCommitReady(ctx)                        精化完成，可以注入；ctx.inject() 决定是否真注入
     *   （调用方完成注入后投递 Event.INJECTED）
     * </pre>
     * 任何一步之后都可能收到 {@code onSegmentAbandoned}（切窗口 / Esc / 暂停），
     * 此时后续回调作废，一个字都不注入。
     */
    public interface Listener {
        /** 状态发生变化（只在真正变化时触发，同状态重复请求不触发）。 */
        default void onStateChanged(State from, State to, Event cause) {}

        /** 开始一段新的听写：清空音频缓存与预览。 */
        default void onSegmentStartRequested() {}

        /**
         * 本段结束，请停止录音并准备最终文本。
         *
         * <p>实现必须**同步**返回——状态机需要立刻拿到「最终文本」以便随后发起精化；
         * 精化本身是异步的，完成后投递 {@link Event#REFINE_DONE}。
         */
        default void onSegmentEndRequested(EndReason reason) {}

        /**
         * 精化完成，可以注入。
         *
         * <p>由 {@link Event#REFINE_DONE} 触发。实现应当：若 {@code ctx.inject()} 为真则注入
         * （并按配置自动发送），否则明确提示用户本段未注入；两种情况下最终都要投递
         * {@link Event#INJECTED} 让状态机回到 IDLE。
         */
        default void onCommitReady(CommitContext ctx) {}

        /** 放弃本段（切窗口 / Esc / 暂停）；一个字都不注入。{@code why} 直接面向用户。 */
        default void onSegmentAbandoned(String why) {}

        /** 非法/迟到事件被忽略，仅用于观测。 */
        default void onEventIgnored(Event event, State state) {}
    }

    private State state = State.IDLE;
    private boolean paused = false;

    private boolean foregroundChanged = false;
    private long generation = 0;
    /** 当前提交中的段落代数与结束原因；{@link Event#REFINE_DONE} 时用来组装 CommitContext。 */
    private long commitGeneration = 0;
    private EndReason commitReason = EndReason.MANUAL;
    /**
     * 本段的 {@link Event#REFINE_DONE} 是否已经放行过。
     *
     * <p>必须有这个闸门：精化是**异步**的（App 在后台线程跑完再投递事件），
     * 而超时兜底、重试、以及「精化失败后又按预览兜底再投一次」这类路径都可能让
     * REFINE_DONE 到两次。放行两次的后果是**文字被注入两遍**——用户会看到
     * 内容重复，而且撤销栈里也多一笔。§2.1 说的「迟到事件是常态」正是这个意思。
     */
    private boolean commitReadyFired = false;

    private final List<Listener> listeners = new ArrayList<>();
    private final Map<Event, Integer> ignoredCounts = new EnumMap<>(Event.class);
    private final List<String> ignoredLog = new ArrayList<>();

    public StateMachine() {}

    public StateMachine(Listener... ls) {
        for (Listener l : ls) {
            addListener(l);
        }
    }

    // ------------------------------------------------------------ 查询

    public synchronized State state() {
        return state;
    }

    public synchronized boolean paused() {
        return paused;
    }

    public synchronized boolean listening() {
        return state == State.LISTENING;
    }

    public synchronized boolean committing() {
        return state == State.COMMITTING;
    }

    public synchronized boolean idle() {
        return state == State.IDLE;
    }

    /** 提交完成后是否应该注入（§7：切窗口的段落放弃注入）。 */
    public synchronized boolean shouldInject() {
        return !foregroundChanged;
    }

    /**
     * 提交完成后**是否允许自动发送**。
     *
     * <p>这条规则原来散在 {@code App} 里，只判「静音超时 + 开关」，漏掉了最关键的一条：
     * 提交时前台窗口已经变了就绝不能按发送键 —— 文字注入到别的窗口顶多是位置不对，
     * 而一个回车落在聊天工具里就是**把还没写完的消息发出去**，不可挽回。
     *
     * <p>放在状态机里是因为它是纯规则：{@link CommitContext#inject()} 就是
     * 「目标窗口还是当前前台吗」，与 {@link #shouldInject()} 同源。这样它可以被单测
     * 直接钉住，而不是只能靠一个跑不起来的集成场景来"证明"。
     *
     * @param sendOnSilenceTimeout 静音超时结束时是否也发送（附录 A 的开关）
     */
    public static boolean autoSendAllowed(CommitContext ctx, boolean sendOnSilenceTimeout) {
        if (!ctx.inject()) {
            return false;
        }
        if (ctx.reason() == EndReason.SILENCE_TIMEOUT) {
            return sendOnSilenceTimeout;
        }
        return true;
    }

    /** 当前段落的代数；每次进入 LISTENING 递增。用于丢弃上一段的迟到回调（§2.1）。 */
    public synchronized long generation() {
        return generation;
    }

    /** 被忽略事件的计数与最近记录，供自检与排查用。 */
    public synchronized Map<Event, Integer> ignoredCounts() {
        return new EnumMap<>(ignoredCounts);
    }

    public synchronized List<String> ignoredLog() {
        return List.copyOf(ignoredLog);
    }

    public synchronized int ignoredTotal() {
        return ignoredCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public synchronized void addListener(Listener l) {
        listeners.add(Objects.requireNonNull(l));
    }

    // ------------------------------------------------------------ 事件入口

    /**
     * 投递一个事件。
     *
     * <p><b>不抛异常</b>：任何当前状态处理不了的事件都被忽略并记录。
     */
    public void handle(Event event) {
        Objects.requireNonNull(event, "event");
        synchronized (this) {
            switch (event) {
                case WAKE_WORD -> onWakeWord(event);
                case TOGGLE -> onToggle(event);
                case END_WORD -> onEndWord(event);
                case SILENCE_TIMEOUT -> onSilenceTimeout(event);
                case MAX_SEGMENT_REACHED -> onMaxSegment(event);
                case FOREGROUND_CHANGED -> onForegroundChanged(event);
                case AUDIO_LOST -> onAudioLost(event);
                case COMMIT_TIMEOUT -> onCommitTimeout(event);
                case CANCEL -> onCancel(event);
                case PAUSE -> onPause(event);
                case RESUME -> onResume(event);
                case REFINE_DONE -> onRefineDone(event);
                case INJECTED -> onInjected(event);
            }
        }
    }

    // ------------------------------------------------------------ 各事件的处理

    private void onWakeWord(Event e) {
        if (paused) {
            ignore(e, "已暂停监听，忽略唤醒词");
            return;
        }
        switch (state) {
            case IDLE -> {
                foregroundChanged = false;
                generation++;
                resetSessionState();
                transition(State.LISTENING, e);
                notifyStart();
            }
            // 重复唤醒是常态（受限语法的 N-best 可能连着命中；用户也可能连说两遍）。
            // 忽略而不是重启段落——重启会丢掉已经录到的音频。
            case LISTENING -> ignore(e, "已在听写中，忽略重复唤醒");
            case COMMITTING -> ignore(e, "正在提交上一段，忽略唤醒（避免把上一段注入进新段落）");
        }
    }

    /**
     * 复位「一次聆听会话」的标志。
     *
     * <p>唤醒进入会话时、以及会话结束时都要调，这样任何一次会话都不会带上一次会话的残留
     * ——「本段期间前台窗口变过」这个标志若被带进下一段，会让下一段明明该注入却被判成
     * 「用户切走了」。
     */
    private void resetSessionState() {
        foregroundChanged = false;
    }

    private void onToggle(Event e) {
        if (paused) {
            ignore(e, "已暂停监听，忽略手动听写");
            return;
        }
        switch (state) {
            case IDLE -> {
                foregroundChanged = false;
                generation++;
                transition(State.LISTENING, e);
                notifyStart();
            }
            case LISTENING -> endSegment(EndReason.MANUAL, e);
            case COMMITTING -> ignore(e, "正在提交中，忽略手动结束");
        }
    }

    private void onEndWord(Event e) {
        if (paused) {
            ignore(e, "已暂停监听，忽略结束词");
            return;
        }
        switch (state) {
            case IDLE -> ignore(e, "待唤醒状态下听到结束词，忽略");
            case LISTENING -> endSegment(EndReason.END_WORD, e);
            // 结束词与静音计时同时到达是设计文档点名的竞态（§2.1）。先到者赢，后到者被吃掉。
            case COMMITTING -> ignore(e, "已在提交中，忽略迟到的结束词");
        }
    }

    private void onSilenceTimeout(Event e) {
        if (paused) {
            ignore(e, "已暂停监听，忽略静音超时");
            return;
        }
        switch (state) {
            case IDLE -> ignore(e, "待唤醒状态下的静音计时，忽略");
            case LISTENING -> endSegment(EndReason.SILENCE_TIMEOUT, e);
            case COMMITTING -> ignore(e, "已在提交中，忽略迟到的静音超时");
        }
    }

    private void onMaxSegment(Event e) {
        switch (state) {
            case IDLE -> ignore(e, "待唤醒状态下的时长上限，忽略");
            case LISTENING -> endSegment(EndReason.MAX_SEGMENT, e);
            case COMMITTING -> ignore(e, "已在提交中，忽略迟到的时长上限");
        }
    }

    /**
     * 采集中断（设备被抢占 / 被拔掉）。
     *
     * <p>LISTENING 下**必须收尾**而不是干等：音频断了就再也没有 PCM，
     * 静音超时与单段上限都不会再被投递，段落会永远停在那里（见 {@link Event#AUDIO_LOST}）。
     *
     * <p>为什么是"取消本段"而不是"把已经录到的部分落字"：
     * 采集中断意味着这一段的**后半部分根本不存在**，落字只会得到半句话 ——
     * 而"少半句"比"少一段"更难被发现，用户不知道缺了什么。
     * 取消 + 明确告知（{@code why} 直接面向用户）让他重说一遍，是可控的；
     * 悄悄打进半句话不是。（§7：任何失败都必须可见）
     *
     * <p>COMMITTING 下忽略：那一段的音频**已经录完并定稿**，麦克风此时断开
     * 不影响既有的 PCM 与精化，收尾应当照常走完。
     */
    private void onAudioLost(Event e) {
        switch (state) {
            case IDLE -> ignore(e, "待唤醒状态下采集中断，忽略（设备恢复前本来就唤不醒）");
            case LISTENING -> {
                transition(State.IDLE, e);
                notifyAbandoned("麦克风中断，本段已取消（音频已断，继续等下去也不会有结果）。\n"
                        + "设备恢复后可以重新说一遍。");
            }
            case COMMITTING -> ignore(e, "提交中采集中断，忽略：本段音频已经录完，收尾照常进行");
        }
    }

    /**
     * 提交超时：进入 COMMITTING 后久等不到 {@code INJECTED}。
     *
     * <p>由应用层的看门狗投递（状态机不依赖时钟，因此可以用单测直接喂这个事件）。
     * 它把"永久 COMMITTING"从**结构上**变成不可能：无论精化线程是异常退出、
     * 卡死、还是回调链断了，最坏情况都是"本段被取消并明确提示"，而不是
     * "整程序从此不响应任何操作、只能杀进程"。
     */
    private void onCommitTimeout(Event e) {
        switch (state) {
            case IDLE -> ignore(e, "待唤醒状态下的提交超时，忽略（上一段早已收尾）");
            case LISTENING -> ignore(e, "听写中收到提交超时，忽略（那是上一段的看门狗，迟到了）");
            case COMMITTING -> {
                transition(State.IDLE, e);
                notifyAbandoned("本段提交超时，已取消并回到待唤醒。\n"
                        + "识别引擎或注入链路可能卡住了，日志里有详细记录；可以重新说一遍。");
            }
        }
    }

    private void onForegroundChanged(Event e) {
        switch (state) {
            case IDLE -> ignore(e, "待唤醒状态下切换窗口，忽略");
            case LISTENING -> {
                // §7 原规则：结束本段，但放弃注入。
                //
                // ⚠️ 实测下来这条规则太狠：它把用户刚说的一整段话直接丢掉。
                //    日志里真实出现过「说了 3.32 秒、因为前台变了被丢弃」——
                //    而用户切窗口往往只是想看看别的东西，或者干脆是被
                //    系统托盘/输入法候选之类的抖动带偏的。
                //    现在改为**照常提交**，并把「前台变了」作为信息交给上层：
                //    注入时会尝试把焦点还原回目标（WindowsTextInjector.inject），
                //    还原不了就注入到当前焦点并明确提示。文字因此不会凭空消失。
                commitGeneration = generation;
                commitReason = EndReason.FOREGROUND_CHANGED;
                foregroundChanged = true;
                commitReadyFired = false;
                transition(State.COMMITTING, e);
                for (Listener l : List.copyOf(listeners)) {
                    l.onSegmentEndRequested(EndReason.FOREGROUND_CHANGED);
                }
            }
            case COMMITTING -> ignore(e, "正在提交中，忽略窗口变化（注入目标已锁定）");
        }
    }

    private void onCancel(Event e) {
        // 取消是「结束整个聆听会话」，不只是丢掉当前段：
        // 连续模式下的段计数与「提交后退出」标志都必须复位，
        // 否则会在下一次会话里带出上一次的残留状态。
        resetSessionState();
        switch (state) {
            case IDLE -> ignore(e, "待唤醒状态下按 Esc，忽略");
            case LISTENING -> {
                transition(State.IDLE, e);
                notifyAbandoned("已取消本段，一个字都没有注入");
            }
            case COMMITTING -> {
                // 提交中也要能取消：精化可能正在跑（§2.3「LISTENING / COMMITTING」）。
                // 递减代数，让在途的 REFINE_DONE / 注入回调作废。
                transition(State.IDLE, e);
                notifyAbandoned("已取消本段（提交阶段），一个字都没有注入");
            }
        }
    }

    private void onPause(Event e) {
        if (paused) {
            ignore(e, "已经处于暂停状态");
            return;
        }
        paused = true;
        if (state == State.LISTENING) {
            transition(State.IDLE, e);
            notifyAbandoned("已暂停监听，本段已丢弃");
        } else {
            // 暂停本身不改变「状态」，只熄灭事件入口；但为了让 UI 有统一的状态来源，
            // 这里仍然广播一次（from==to 时 notify 会跳过，故显式通知监听者）。
            //
            // ★ COMMITTING 下走的就是这一支，**刻意不取消本段**：段落已经录完，
            //   掐死它等于丢话。见 onRefineDone 里的详细说明 —— 要取消请用 Esc。
            //   （这里曾经因为 onRefineDone 里那句 `if (paused) ignore` 而变成永久卡死。）
            notifyStateChanged(state, state, e);
        }
    }

    private void onResume(Event e) {
        if (!paused) {
            ignore(e, "并未处于暂停状态");
            return;
        }
        paused = false;
        notifyStateChanged(state, state, e);
    }

    private void onRefineDone(Event e) {
        if (state != State.COMMITTING) {
            ignore(e, "不在提交中，忽略精化完成");
            return;
        }
        // ★ 这里**刻意不判 paused**。
        //
        //   原来有一句 `if (paused) { ignore(...); return; }`，后果是**永久卡死**：
        //   用户在"提交中"按一下「暂停监听」，段落的放行就被吃掉，而 commitReadyFired
        //   仍是 false、精化线程已经跑完退出 —— 再也不会有人投递 REFINE_DONE。
        //   此后唤醒/悬浮球/结束词/静音全被 muted，只能杀进程。
        //
        //   为什么正确做法是"照常放行"而不是"暂停也取消本段"：
        //   §2.3 给「暂停监听」的定义是**忽略唤醒词与结束词**（别再听新的），
        //   而不是"把已经录完的段落掐死"。段落一旦结束（onSegmentEndRequested 已发出），
        //   音频已定稿，此刻掐死它等于丢话 —— 与切窗口那次修正（§7，原为"放弃注入"）
        //   是同一个道理：可挽回的错误优于不可挽回的丢失。
        //   真要取消本段，产品里有专门的入口：Esc → CANCEL（§2.2/§2.3）。
        //
        //   兜底另有两层：App 的提交看门狗（COMMIT_TIMEOUT）保证不会永久 COMMITTING；
        //   暂停期间仍会照常落字这件事由 App 明确提示给用户。
        if (commitReadyFired) {
            // 幂等闸门：第二次放行会让文字注入两遍。
            ignore(e, "本段已经放行过注入，忽略重复的精化完成");
            return;
        }
        commitReadyFired = true;
        CommitContext ctx = new CommitContext(commitGeneration, !foregroundChanged, commitReason);
        for (Listener l : List.copyOf(listeners)) {
            l.onCommitReady(ctx);
        }
    }

    private void onInjected(Event e) {
        if (state != State.COMMITTING) {
            ignore(e, "不在提交中，忽略注入完成");
            return;
        }
        resetSessionState();
        transition(State.IDLE, e);
    }

    // ------------------------------------------------------------ 内部

    private void endSegment(EndReason reason, Event cause) {
        commitGeneration = generation;
        commitReason = reason;
        commitReadyFired = false;   // 新的一段提交：重新开闸
        transition(State.COMMITTING, cause);
        for (Listener l : List.copyOf(listeners)) {
            l.onSegmentEndRequested(reason);
        }
    }

    private void transition(State to, Event cause) {
        State from = state;
        if (from == to) {
            return;
        }
        if (to == State.IDLE) {
            foregroundChanged = false;
        }
        state = to;
        log.info("状态流转 {} -> {}（因 {}）", from, to, cause);
        notifyStateChanged(from, to, cause);
    }

    private void notifyStateChanged(State from, State to, Event cause) {
        for (Listener l : List.copyOf(listeners)) {
            try {
                l.onStateChanged(from, to, cause);
            } catch (RuntimeException ex) {
                log.warn("监听器处理状态变化时出错：{}", ex.toString(), ex);
            }
        }
    }

    private void notifyStart() {
        for (Listener l : List.copyOf(listeners)) {
            try {
                l.onSegmentStartRequested();
            } catch (RuntimeException ex) {
                log.warn("监听器处理开始段落时出错：{}", ex.toString(), ex);
            }
        }
    }

    private void notifyAbandoned(String why) {
        for (Listener l : List.copyOf(listeners)) {
            try {
                l.onSegmentAbandoned(why);
            } catch (RuntimeException ex) {
                log.warn("监听器处理放弃段落时出错：{}", ex.toString(), ex);
            }
        }
    }

    /**
     * 记录一个被忽略的事件。
     *
     * <p>这是 §2.1「非法事件一律忽略并记录」里的「记录」。用 INFO 而不是 WARN：
     * 迟到事件在真实场景里是常态，打成 WARN 会让日志全是噪声，反而掩盖真问题。
     */
    private void ignore(Event e, String why) {
        ignoredCounts.merge(e, 1, Integer::sum);
        String line = state + " 忽略 " + e + "：" + why;
        if (ignoredLog.size() >= 64) {
            ignoredLog.remove(0);
        }
        ignoredLog.add(line);
        log.info("{}", line);
        for (Listener l : List.copyOf(listeners)) {
            try {
                l.onEventIgnored(e, state);
            } catch (RuntimeException ex) {
                log.warn("监听器处理忽略事件时出错：{}", ex.toString(), ex);
            }
        }
    }
}
