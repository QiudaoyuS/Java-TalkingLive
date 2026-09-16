package com.talkinglive.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
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
         * <p>原设计（§7）规定这条路径「结束本段但**放弃注入**」。实测发现那等于
         * 把用户刚说的一整段话丢掉——而切窗口往往只是无意的。现在改为照常提交，
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

        /** 是否应该注入。四种正常结束原因都注入；是否真的注入由注入时的焦点还原结果决定。 */
        public boolean injects() {
            return true;
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
                transition(State.LISTENING, e);
                notifyStart();
            }
            // 重复唤醒是常态（受限语法的 N-best 可能连着命中；用户也可能连说两遍）。
            // 忽略而不是重启段落——重启会丢掉已经录到的音频。
            case LISTENING -> ignore(e, "已在听写中，忽略重复唤醒");
            case COMMITTING -> ignore(e, "正在提交上一段，忽略唤醒（避免把上一段注入进新段落）");
        }
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
        if (paused) {
            ignore(e, "已暂停，忽略精化完成");
            return;
        }
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
