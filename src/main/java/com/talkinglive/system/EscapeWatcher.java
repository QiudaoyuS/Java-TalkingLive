package com.talkinglive.system;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局 Esc 监听 —— 把「任何时刻按 Esc 可取消整段」这条承诺**真的接上**。
 *
 * <p><b>为什么需要这个类：</b>{@code DESIGN.md} §2.2 / §2.3 两处都写了「任何时刻按 Esc
 * 可取消整段，一个字都不会打出去」，{@code StateMachine.Event.CANCEL} 也早就实现了，
 * 但产品代码里**从来没有产生过这个事件**：没有 {@code RegisterHotKey}，没有键盘钩子，
 * 而悬浮球与浮窗都是 {@code WS_EX_NOACTIVATE}（不抢焦点）——它们根本收不到键盘事件。
 * 于是那句承诺只存在于状态机的注释和自检里（自检是直接给状态机发事件的，
 * 测的是状态机，不是这条输入路径）。
 *
 * <p>后果不只是"少一个功能"：误唤醒/环境噪声触发之后，用户没有任何"不注入"的手段，
 * 只能等它自己结束；而取消本来还是另外两个死局（提交中暂停、麦克风被抢占）唯一的逃生口。
 *
 * <p><b>为什么用轮询而不是 {@code RegisterHotKey}：</b>后者要求注册线程有消息循环，
 * 而本程序没有任何 Win32 消息泵。{@code GetAsyncKeyState} 是全局的（不需要焦点），
 * 正是"任意应用在前台时也要能听到 Esc"所需要的。
 *
 * <p><b>会不会误伤：</b>会观察到用户为别的目的按的 Esc（比如关掉某个对话框）。
 * 这是刻意的：产品承诺就是"任何时刻"。真正的护栏在状态机那一侧 ——
 * 待唤醒（IDLE）状态下收到 CANCEL 是被忽略的，所以空闲时按 Esc 没有任何副作用；
 * 只有正在听写/提交时才真的取消。另外本类**只观察不拦截**，
 * Esc 该有的原有作用一点不受影响。
 *
 * <p><b>能力边界（如实说明）：</b>判定同时看"当前是否按下"（高位）与
 * "自上次查询以来被按过"（低位）。高位那一路能覆盖一切长于一个轮询间隔
 * （50ms）的按下，也就是真实使用中的全部情况；低位那一路用来兜住更短的敲击，
 * 但该位是**全局**的，别的程序若也调 {@code GetAsyncKeyState} 可能把它提前读走 ——
 * 这种情况下一次极短的点按会被漏掉一次，而不是产生错误的行为（不会误取消）。
 */
public final class EscapeWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EscapeWatcher.class);

    /** 轮询间隔。50ms 足够跟手，开销可忽略；短于点按的按下靠低位标志兜住。 */
    public static final long DEFAULT_POLL_MILLIS = 50;

    /** {@code GetAsyncKeyState} 的"当前按下"位。 */
    private static final int DOWN_BIT = 0x8000;
    /** {@code GetAsyncKeyState} 的"自上次查询以来被按过"位。 */
    private static final int PRESSED_SINCE_LAST_POLL_BIT = 0x0001;

    /**
     * 按键状态源。
     *
     * <p>做成接口是为了**可单测**：单测喂一串人造的按键状态就能覆盖
     * "按住只触发一次""两次轮询之间的短点按也要触发""松开后再按要再触发"，
     * 不需要真实桌面，也不需要真的按键盘。
     */
    @FunctionalInterface
    public interface KeyState {
        /** @return Win32 的按键状态位（高字节"当前是否按下"，低位"自上次查询以来按过"） */
        short poll(int vk);
    }

    private final KeyState keys;
    private final Runnable onEscape;
    private final long pollMillis;
    private final AtomicLong fires = new AtomicLong();

    /** 上一次轮询时 Esc 是否处于按下状态（用于把"按住"折成一次触发）。 */
    private volatile boolean escDown;
    private volatile boolean closed;
    private ScheduledExecutorService scheduler;

    public EscapeWatcher(KeyState keys, Runnable onEscape) {
        this(keys, onEscape, DEFAULT_POLL_MILLIS);
    }

    public EscapeWatcher(KeyState keys, Runnable onEscape, long pollMillis) {
        this.keys = keys;
        this.onEscape = onEscape;
        this.pollMillis = pollMillis <= 0 ? DEFAULT_POLL_MILLIS : pollMillis;
    }

    /** 用真实的 Win32 按键状态。非 Windows 或原生库不可用时抛异常，由调用方决定怎么办。 */
    public static EscapeWatcher system(Runnable onEscape) {
        return new EscapeWatcher(vk -> Win32.User32.INSTANCE.GetAsyncKeyState(vk), onEscape);
    }

    /** 开始轮询。守护线程，不阻止 JVM 退出。重复调用是安全的。 */
    public void start() {
        if (closed || scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "escape-watcher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::pollSafely, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
        log.info("Esc 取消已接线：每 {}ms 轮询一次 GetAsyncKeyState（待唤醒状态下按 Esc 无副作用）",
                pollMillis);
    }

    public boolean running() {
        ScheduledExecutorService s = scheduler;
        return s != null && !s.isShutdown();
    }

    /** 累计触发次数，供自检/诊断确认"确实在工作"。 */
    public long fires() {
        return fires.get();
    }

    /** 轮询里不抛：一次异常不应该让整个调度停掉。 */
    private void pollSafely() {
        try {
            poll();
        } catch (Throwable t) {
            log.warn("Esc 轮询出错（继续轮询）：{}", t.toString());
        }
    }

    /**
     * 单次轮询。
     *
     * @return 本次是否构成"一次新的按下"（即是否触发了取消）
     */
    boolean poll() {
        if (closed) {
            return false;
        }
        short state = keys.poll(Win32.VK_ESCAPE);
        boolean down = (state & DOWN_BIT) != 0;
        // 低位 =「自上次查询以来被按过」。只判 down 的话，一次短于点按间隔的
        // 敲击会被整个漏掉；有了这一位，两次轮询之间的点按也能被捞回来。
        boolean pressedSinceLastPoll = (state & PRESSED_SINCE_LAST_POLL_BIT) != 0;

        boolean fresh = (down || pressedSinceLastPoll) && !escDown;
        escDown = down;
        if (!fresh) {
            return false;
        }
        fires.incrementAndGet();
        if (onEscape != null) {
            try {
                onEscape.run();
            } catch (RuntimeException e) {
                log.warn("Esc 取消回调出错：{}", e.toString());
            }
        }
        return true;
    }

    @Override
    public void close() {
        closed = true;
        ScheduledExecutorService s = scheduler;
        scheduler = null;
        if (s != null) {
            s.shutdownNow();
        }
    }
}
