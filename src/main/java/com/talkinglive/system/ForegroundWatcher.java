package com.talkinglive.system;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 前台窗口监听（{@code DESIGN.md} §4.5）。
 *
 * <p>用途只有一个：段落进行中用户切走了窗口 → 结束本段并**放弃注入**（§7）。
 *
 * <p><b>为什么是轮询而不是 {@code SetWinEventHook}：</b>钩子需要一条消息循环线程，
 * 且在有窗口被销毁/重建时会收到成串的瞬时事件（切窗口时前台窗口可能先变成 0
 * 再变成目标，钩子要额外做去抖）。本产品只需要「变了没有」，150ms 轮询的 CPU
 * 开销可以忽略，而行为完全可预期。设计文档在贴边收起那里已经为同类问题选过轮询
 * （§4.4「收起状态必须用轮询」），这里沿用同一取向。
 *
 * <p><b>必须显式忽略自己的窗口句柄</b>（§4.3）：浮窗预览条弹出会改变前台窗口，
 * 若不忽略就会被判定成「用户切窗口」，当场结束这一段——这是整套交互里最容易
 * 自我触发的一处，单独做成 {@link #ignore} 机制。
 */
public final class ForegroundWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ForegroundWatcher.class);

    /** 轮询间隔。150ms 与贴边收起的轮询一致。 */
    public static final long POLL_MILLIS = 150;

    /** 前台窗口变化回调。 */
    @FunctionalInterface
    public interface Listener {
        /**
         * @param from 变化前的句柄
         * @param to   变化后的句柄
         */
        void onForegroundChanged(long from, long to);
    }

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "foreground-watcher");
                t.setDaemon(true);
                return t;
            });

    private final Listener listener;
    private final java.util.Set<Long> ignored = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile long last = 0;
    /** 去抖：上一次读到但与基线不同的句柄（连续两次相同才认定变化）。 */
    private volatile long pending = 0;
    private volatile boolean enabled = true;
    private ScheduledFuture<?> task;

    public ForegroundWatcher(Listener listener) {
        this.listener = listener;
    }

    /** 开始监听。启动时的当前前台窗口只作为基线，不触发回调。 */
    public synchronized void start() {
        if (task != null) {
            return;
        }
        last = current();
        task = scheduler.scheduleWithFixedDelay(this::poll, POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS);
        log.info("前台窗口监听已启动（轮询 {}ms，基线 0x{}）", POLL_MILLIS, Long.toHexString(last));
    }

    /** 把当前窗口记为新的基线，且不触发回调（段落开始时调用）。 */
    public synchronized void rebase() {
        last = current();
        log.debug("前台窗口基线重置为 0x{}", Long.toHexString(last));
    }

    public long current() {
        try {
            return Win32.hwndValue(Win32.User32.INSTANCE.GetForegroundWindow());
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return 0;
        }
    }

    /**
     * 忽略某个窗口句柄（悬浮球、浮窗预览条、设置窗口）。
     *
     * <p>§4.3：浮窗必须 {@code WS_EX_NOACTIVATE}，但**光有样式不够**——
     * 右键菜单、设置窗口都会真的抢焦点，所以必须在事件层再挡一道。
     */
    public void ignore(long hwnd) {
        if (hwnd != 0) {
            ignored.add(hwnd);
        }
    }

    public void unignore(long hwnd) {
        ignored.remove(hwnd);
    }

    /** 暂停判定（设置窗口打开期间不该结束听写）。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    private void poll() {
        if (!enabled) {
            return;
        }
        try {
            long now = current();
            long prev = last;

            // ★ foreground == 0 时**不要更新基线**。
            //   GetForegroundWindow() 在窗口切换的瞬间、前台窗口被销毁时、以及
            //   任务栏/开始菜单交互期间会短暂返回 0（实测日志里 0x0 出现得非常频繁）。
            //   早期实现把 0 也当成「新的基线」，于是随后的真实窗口看起来像
            //   「从 0 变过来的」——每次都判定成用户切了窗口，日志被刷爆，
            //   段落也会被无谓地中断。基线只应该跟着**真实的窗口句柄**走。
            if (now == 0) {
                log.debug("前台窗口短暂为空，忽略且不改基线（保持 0x{}）", Long.toHexString(prev));
                return;
            }
            if (now == prev) {
                pending = 0;
                return;
            }

            // 去抖：要求同一个新句柄连续两次读到才认定变化。
            // 快速切换时前台会在几个句柄间抖动（任务切换、托盘预览），
            // 一次抖动就中断一段录音对用户来说是纯粹的损失。
            if (now != pending) {
                pending = now;
                return;
            }
            pending = 0;
            last = now;

            // 自身窗口的切换：更新基线但不回调（§4.3 明确要求）。
            if (ignored.contains(now) || ignored.contains(prev)) {
                log.debug("忽略自身窗口的前台变化：0x{} -> 0x{}", Long.toHexString(prev), Long.toHexString(now));
                return;
            }
            log.info("前台窗口变化：0x{} -> 0x{}（{}）", Long.toHexString(prev), Long.toHexString(now), title(now));
            listener.onForegroundChanged(prev, now);
        } catch (RuntimeException e) {
            log.debug("轮询前台窗口时出错（忽略本次）：{}", e.toString());
        }
    }

    /** 窗口标题，用于日志与提示。取不到返回空串。 */
    public static String title(long hwnd) {
        if (hwnd == 0) {
            return "";
        }
        try {
            char[] buf = new char[512];
            int n = Win32.User32.INSTANCE.GetWindowTextW(Win32.hwndOf(hwnd), buf, buf.length);
            return n <= 0 ? "" : new String(buf, 0, n);
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public synchronized void close() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        scheduler.shutdownNow();
        log.info("前台窗口监听已停止");
    }
}
