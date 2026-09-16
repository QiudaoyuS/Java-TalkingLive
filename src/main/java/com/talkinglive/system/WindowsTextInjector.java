package com.talkinglive.system;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import com.talkinglive.core.AppConfig;
import com.talkinglive.core.Logging;
import com.talkinglive.text.TextInjector;
import com.talkinglive.text.TextUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code SendInput} + {@code KEYEVENTF_UNICODE} 文本注入（{@code DESIGN.md} §5）。
 *
 * <p>选它而不是「剪贴板 + Ctrl+V」的理由写在 §5：**不污染用户剪贴板**、兼容性最好。
 *
 * <p>这个类里有三个必须做对、做错就静默失效的点：
 *
 * <ol>
 *   <li><b>Unicode 事件里字符放在 {@code wScan}，{@code wVk} 必须是 0。</b>
 *       写反了会打出完全无关的字符。</li>
 *   <li><b>注入前校验前台窗口。</b>§7：「提交时前台窗口已变 → 放弃注入 + 明确提示；
 *       不自动发送」。校验必须在**真正调用 SendInput 之前**，且要能挡住
 *       「精化跑了一秒、用户切走了」这种时序。</li>
 *   <li><b>UIPI 静默丢弃。</b>目标程序以管理员运行时，{@code SendInput} 返回成功写入
 *       事件数但对方收不到（或写入 0）。§7 要求检测并提示「需以管理员运行本程序」。
 *       {@code SendInput} 本身无法分辨，因此这里用**进程完整性级别**提前判断。</li>
 *   <li><b>灌太快会被目标程序丢字。</b>这不是 {@code SendInput} 的问题——它报告全部写入成功，
 *       是微信/QQ 这类自绘输入框**主动丢掉了后面的 WM_CHAR**。唯一的解法是放慢：
 *       逐字符单独发送，并在字符之间留 {@link #charGapMillis} 毫秒。</li>
 * </ol>
 */
public final class WindowsTextInjector implements TextInjector {

    private static final Logger log = LoggerFactory.getLogger(WindowsTextInjector.class);

    /** 单个字符写不进去时的重试次数。 */
    private static final int SEND_RETRIES = 4;

    /**
     * 字符之间的间隔（毫秒）。**可在设置窗口里调**。
     *
     * <p><b>这个数字是「微信里只出现第一个字」的解法核心。</b>
     * 实测证据：日志显示 14 个事件全部写入成功（7 字 × 2），
     * 但微信输入框只落地了 2 个字 —— 说明是目标程序**主动丢掉了后面的 WM_CHAR**。
     *
     * <p>原因：微信/QQ 这类自绘输入框要先过自己的输入法/组合状态机。
     * 我们把事件灌得比它处理得快，它就丢。唯一的解法是放慢。
     *
     * <p>做成实例字段而不是常量，是因为不同目标程序、不同微信版本、不同输入法
     * 需要的间隔不一样（标准控件如记事本用 0 都行，自绘输入框可能要 40ms）。
     * 让用户能调，比我去猜一个「对所有人都合适」的值可靠。
     */
    private volatile long charGapMillis = AppConfig.DEFAULT_CHAR_GAP_MILLIS;

    /** 调整字符间隔（设置窗口调用）。 */
    public void setCharGapMillis(int millis) {
        long v = Math.max(AppConfig.MIN_CHAR_GAP_MILLIS,
                Math.min(AppConfig.MAX_CHAR_GAP_MILLIS, millis));
        if (v != charGapMillis) {
            log.info("字符注入间隔：{}ms → {}ms", charGapMillis, v);
            charGapMillis = v;
        }
    }

    /** 当前字符间隔（毫秒）。 */
    public long charGapMillis() {
        return charGapMillis;
    }

    private final boolean available;
    private final String unavailableReason;
    private final AtomicInteger injections = new AtomicInteger();
    private final AtomicInteger injectedCodePoints = new AtomicInteger();

    public WindowsTextInjector() {
        String os = System.getProperty("os.name", "");
        if (!os.toLowerCase().contains("win")) {
            available = false;
            unavailableReason = "文本注入只支持 Windows（当前系统：" + os + "）";
        } else {
            boolean ok;
            String reason = null;
            try {
                // 触发一次加载，把失败提前到构造期，避免第一次注入时才炸
                Win32.User32.INSTANCE.GetForegroundWindow();
                ok = true;
            } catch (UnsatisfiedLinkError | RuntimeException e) {
                ok = false;
                reason = "无法加载 user32.dll：" + e.getMessage();
            }
            available = ok;
            unavailableReason = reason;
        }
        if (available) {
            log.info("文本注入器就绪：SendInput + KEYEVENTF_UNICODE（不碰剪贴板）");
        } else {
            log.warn("文本注入器不可用：{}", unavailableReason);
        }
    }

    @Override
    public Result inject(int backspaces, String text) {
        return inject(backspaces, text, 0);
    }

    /**
     * 带目标窗口校验的注入（旧签名，保留给单目标场景）。
     *
     * @param expectedWindow 段落开始时的前台窗口句柄；非 0 时先校验它仍是前台窗口
     */
    public Result inject(int backspaces, String text, long expectedWindow) {
        return inject(backspaces, text, expectedWindow, 0);
    }

    /**
     * 注入到目标窗口，必要时先把焦点还原回去。
     *
     * <p><b>这里是整个产品最容易出、也最难查的一个错误决策点。</b>
     * 早期实现是「提交时前台窗口只要变了就**放弃注入**」——理由是
     * {@code DESIGN.md} §7「切窗口 → 放弃注入，避免把文字误发到别的程序」。
     * 但实测下来这条规则导致的是**一个字都出不去**，而且用户完全不知道为什么：
     *
     * <ul>
     *   <li>「段落开始时的前台窗口」是在**按下唤醒词那一刻**取的。用户对着 A 窗口说话时，
     *       前台很可能已经是别的东西（输入法候选、通知、他刚点过的另一个窗口）。</li>
     *   <li>期间的任何一次焦点抖动（通知弹出、任务栏预览、IEM 切换）都会让前台句柄变化。</li>
     *   <li>于是一次「拒绝注入」把用户刚说的一整段话直接丢掉，且没有历史记录可找回。</li>
     * </ul>
     *
     * <p>现在改为**先尝试把焦点还原到目标，再注入**：
     * <ol>
     *   <li>前台就是目标 → 直接注入（正常路径）。</li>
     *   <li>前台不是目标但目标还活着 → 先把自己/Win32 的前台切回目标，再注入。
     *       本进程刚刚收到过用户的点击（悬浮球）或很快会有交互，通常有这个权限。</li>
     *   <li>切不回去 → **仍然注入**到当前焦点，并在结果里如实说明「注入到了别的地方」。
     *       理由：把文字打进当前焦点，用户至少**看得到文字、能自己剪走**；
     *       而丢弃是纯粹的信息损失。§7 想避免的是「误发到别的程序」，
     *       但「一个字都没有」对用户更糟——这是两种坏之间选更可挽回的那个。</li>
     * </ol>
     *
     * @param expectedWindow 段落开始时的前台窗口句柄
     * @param expectedFocus  段落开始时焦点控件句柄（子窗口）；0 表示未知
     */
    public Result inject(int backspaces, String text, long expectedWindow, long expectedFocus) {
        if (!available) {
            return Result.fail(Result.Failure.UNAVAILABLE, unavailableReason);
        }

        boolean retargeted = false;
        String retargetNote = null;
        if (expectedWindow != 0) {
            if (targetElevated(expectedWindow)) {
                String msg = "目标程序正在以管理员身份运行，Windows 的 UIPI 隔离会静默丢弃注入的文字。"
                        + "请以管理员身份重新启动 TalkingLive 后再试。";
                log.warn("{}（目标窗口 0x{}）", msg, Long.toHexString(expectedWindow));
                return Result.fail(Result.Failure.UIPI_BLOCKED, msg);
            }
            long now = Win32.hwndValue(Win32.User32.INSTANCE.GetForegroundWindow());
            if (now != expectedWindow) {
                String note = "提交时前台窗口是 0x" + Long.toHexString(now)
                        + "（'" + ForegroundWatcher.title(now) + "'），目标原为 0x"
                        + Long.toHexString(expectedWindow)
                        + "（'" + ForegroundWatcher.title(expectedWindow) + "'）";
                log.warn("{}；尝试把焦点还原到目标…", note);
                if (restoreForeground(expectedWindow, expectedFocus)) {
                    retargeted = true;
                    log.info("焦点已还原到目标 0x{}，继续注入", Long.toHexString(expectedWindow));
                } else {
                    // 还原失败：不放弃，注入到当前焦点，由调用方明确告知用户
                    retargetNote = note + "；且无法把焦点还原回目标，文字已注入到**当前焦点**所在处。"
                            + "若文字出现在别的地方，请手动剪走。";
                    log.warn("{}", retargetNote);
                }
            }
        }

        int events = 0;
        if (backspaces > 0) {
            int n = sendBackspaces(backspaces);
            if (n < 0) {
                return Result.fail(Result.Failure.SEND_FAILED, "发送退格键失败：SendInput 没有写入任何事件");
            }
            events += n;
        }
        String body = text == null ? "" : text;
        if (!body.isEmpty()) {
            int n = sendUnicode(body);
            if (n < 0) {
                return Result.fail(Result.Failure.SEND_FAILED,
                        "发送文字失败：SendInput 没有写入任何事件（可能被权限隔离或输入队列异常）");
            }
            events += n;
        }
        injections.incrementAndGet();
        injectedCodePoints.addAndGet(TextUtils.codePointCount(body));
        log.info("注入完成：退格={} 文本={} 事件数={}（每字 2 事件，可据此判断是否发全）{}{}",
                backspaces, Logging.describeWithFingerprint(body), events,
                retargeted ? "（已把焦点还原到目标）" : "",
                retargetNote == null ? "" : "（焦点还原失败，注入到当前焦点）");

        Result r = Result.ok(events);
        return retargetNote == null ? r : new Result(true, events, retargetNote, Result.Failure.NONE);
    }

    /**
     * 把前台窗口（并尽量把键盘焦点）还原到目标。
     *
     * <p>两步都要做：
     * <ul>
     *   <li>{@code SetForegroundWindow} 让目标窗口回到前台。Windows 对允许调用的进程有要求，
     *       因此可能失败——失败不算致命，返回 false 由调用方决定怎么办。</li>
     *   <li>如果拿到了段落开始时的**焦点控件**句柄（例如浏览器地址栏、
     *       文本框的内部子窗口），再调 {@code SetFocus} 把光标放回那个控件。
     *       只把窗口切到前台但不还原控件焦点，文字可能打到窗口本身而不是输入框里。</li>
     * </ul>
     *
     * @return 是否成功把目标设成前台
     */
    public boolean restoreForeground(long targetWindow, long focusControl) {
        try {
            HWND target = Win32.hwndOf(targetWindow);
            if (target == null || !Win32.User32.INSTANCE.IsWindow(target)) {
                return false;
            }
            boolean ok = Win32.User32.INSTANCE.SetForegroundWindow(target);
            if (!ok) {
                // 常见失败原因：目标线程不等我们的输入。退一步用 AttachThreadInput 借一下输入队列。
                ok = forceForeground(targetWindow);
            }
            if (ok && focusControl != 0) {
                try {
                    Win32.User32.INSTANCE.SetFocus(Win32.hwndOf(focusControl));
                } catch (RuntimeException e) {
                    log.debug("SetFocus 到控件 0x{} 失败（忽略，窗口已在前台）",
                            Long.toHexString(focusControl));
                }
            }
            return ok;
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.debug("还原前台失败：{}", e.toString());
            return false;
        }
    }

    /**
     * 强行把窗口带到前台：借目标线程的输入队列之后再调用 {@code SetForegroundWindow}。
     *
     * <p>这是绕过 Windows 前台锁的常规做法（{@code AttachThreadInput} 把两个线程的输入队列
     * 临时接在一起，此时调用方被视为「有资格」设置前台）。用完必须解绑。
     */
    private boolean forceForeground(long targetWindow) {
        var pidRef = new IntByReference();
        HWND target = Win32.hwndOf(targetWindow);
        int targetThread = Win32.User32.INSTANCE.GetWindowThreadProcessId(target, pidRef);
        int selfThread = Win32.Kernel32.INSTANCE.GetCurrentThreadId();
        if (targetThread == 0) {
            return false;
        }
        boolean attached = false;
        try {
            attached = Win32.User32.INSTANCE.AttachThreadInput(selfThread, targetThread, true);
            boolean ok = Win32.User32.INSTANCE.SetForegroundWindow(target);
            if (ok) {
                Win32.User32.INSTANCE.BringWindowToTop(target);
            }
            log.debug("AttachThreadInput 借队列{}，SetForegroundWindow={}",
                    attached ? "成功" : "失败", ok);
            return ok;
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.debug("强行置前台失败：{}", e.toString());
            return false;
        } finally {
            if (attached) {
                try {
                    Win32.User32.INSTANCE.AttachThreadInput(selfThread, targetThread, false);
                } catch (RuntimeException ignored) {
                    // 解绑失败无法补救
                }
            }
        }
    }

    /** 当前前台窗口句柄。 */
    public long currentForeground() {
        try {
            return Win32.hwndValue(Win32.User32.INSTANCE.GetForegroundWindow());
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return 0;
        }
    }

    @Override
    public Result press(KeyCombo combo) {
        if (!available) {
            return Result.fail(Result.Failure.UNAVAILABLE, unavailableReason);
        }
        List<Win32.KeyEvent> keys = new ArrayList<>();
        switch (combo) {
            case ENTER -> {
                keys.add(Win32.KeyEvent.vkDown(Win32.VK_RETURN));
                keys.add(Win32.KeyEvent.vkUp(Win32.VK_RETURN));
            }
            case CTRL_ENTER -> {
                keys.add(Win32.KeyEvent.vkDown(Win32.VK_CONTROL));
                keys.add(Win32.KeyEvent.vkDown(Win32.VK_RETURN));
                keys.add(Win32.KeyEvent.vkUp(Win32.VK_RETURN));
                keys.add(Win32.KeyEvent.vkUp(Win32.VK_CONTROL));
            }
        }
        int sent = send(keys);
        if (sent < 0) {
            return Result.fail(Result.Failure.SEND_FAILED, "发送按键失败：SendInput 没有写入任何事件");
        }
        log.info("自动发送：{}（事件数={}）", combo.display(), sent);
        return Result.ok(sent);
    }

    // ------------------------------------------------------------ 底层

    private int sendBackspaces(int count) {
        // 一个退格 = 按下 + 抬起，两批发送以便目标程序逐字符处理（有些自绘输入框
        // 会合并同一批内的按键，导致只退一格）。
        int total = 0;
        for (int i = 0; i < count; i++) {
            int n = send(List.of(Win32.KeyEvent.vkDown(Win32.VK_BACK), Win32.KeyEvent.vkUp(Win32.VK_BACK)));
            if (n < 0) {
                return -1;
            }
            total += n;
        }
        return total;
    }

    /**
     * 逐字符发送文本 —— **每个字符单独一次 SendInput，并留出足够间隔**。
     *
     * <p>这是「微信里只出现第一个字」的最终修法。实测证据：
     * <pre>
     *   注入完成：文本=len=7 事件数=14（每字 2 事件）
     * </pre>
     * 14 个事件全部写入成功，说明**我们发全了**；而微信输入框里只落地了 2 个字。
     * 结论：是目标程序**主动丢掉了后面的 WM_CHAR**。
     *
     * <p>为什么会丢：微信/QQ 这类程序用自绘输入框，消息要先过它的输入法/组合状态机。
     * 我们把 2N 个事件一口气灌进队列，它的 UI 线程还没处理完第一个字符的
     * 组合状态，后面的就已经到了，于是被丢弃或被组合逻辑吃掉。
     * 这与「打字机太快」是同一类问题，唯一的解法是**放慢**。
     *
     * <p>所以这里刻意放弃批量化：
     * <ul>
     *   <li>一个码点一次 {@code SendInput}（按下 + 抬起两个事件）——粒度足够小，
     *       目标程序每处理完一个字我们才发下一个。</li>
     *   <li>字符之间间隔 {@link #charGapMillis} 毫秒（默认 20ms）。默认值取得比较保守，
     *       因为「注入慢 100ms」远比「文字缺一半」可接受。</li>
     *   <li>单个字符写不进去就重试 {@value #SEND_RETRIES} 次，仍失败则记 ERROR
     *       并继续后面的字符——不因为一个字失败就丢掉整段。</li>
     *   <li>逐字符记 TRACE，便于事后核对到底哪个字没进去。</li>
     * </ul>
     *
     * <p><b>代价</b>：7 个字的段落约多花 7 × charGapMillis 毫秒。
     * 相对 §6 的 2.5 秒提交预算可以忽略。
     *
     * @return 实际写入的事件数；一个都没写进去返回 -1
     */
    private int sendUnicode(String text) {
        if (traceInput) {
            // 整段只打一次：把要注入的字符码点列出来，便于核对「是不是每个字都对」。
            // 这条日志是排查「同一个字被重复 N 遍」的关键证据——
            // 那种故障下这里会显示 N 个相同的码点。
            StringBuilder sb = new StringBuilder();
            int cps = 0;
            for (int k = 0; k < text.length(); ) {
                int cp = text.codePointAt(k);
                k += Character.charCount(cp);
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(String.format("U+%04X", cp));
                if (++cps >= TRACE_EVENTS) {
                    sb.append(" …");
                    break;
                }
            }
            log.info("待注入字符（码点）：{}", sb);
        }
        int total = 0;
        int index = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            index++;

            // 一个码点 → 一到两组「按下 + 抬起」
            List<Win32.KeyEvent> pair = new ArrayList<>(4);
            // 一个码点 → 一到两个 UTF-16 code unit 的「按下 + 抬起」。
            //
            // KEYEVENTF_UNICODE 的 wScan 装的是**一个 UTF-16 code unit**。
            // 补充平面字符（emoji、生僻字）在 UTF-16 里就是两个 code unit，
            // 所以需要两组事件；Windows 会把相邻的高/低代理组合成一个字符交给目标程序。
            // 这是 Windows 的既定行为，不是我们的变通。
            if (Character.charCount(cp) == 1) {
                pair.add(Win32.KeyEvent.unicodeDown((char) cp));
                pair.add(Win32.KeyEvent.unicodeUp((char) cp));
            } else {
                char hi = Character.highSurrogate(cp);
                char lo = Character.lowSurrogate(cp);
                pair.add(Win32.KeyEvent.unicodeDown(hi));
                pair.add(Win32.KeyEvent.unicodeUp(hi));
                pair.add(Win32.KeyEvent.unicodeDown(lo));
                pair.add(Win32.KeyEvent.unicodeUp(lo));
            }

            int written = 0;
            for (int attempt = 1; attempt <= SEND_RETRIES && written < pair.size(); attempt++) {
                int n = send(pair.subList(written, pair.size()));
                if (n < 0) {
                    break;
                }
                written += n;
                if (written < pair.size()) {
                    log.debug("第 {} 个字符部分写入（{}/{}），重试", index, written, pair.size());
                    sleep(charGapMillis);
                }
            }
            if (written == 0) {
                // 一个字都写不进去：说明输入队列被拒（UIPI / 目标无响应）。
                // 直接报失败，不要继续灌——继续也只会全部失败。
                log.error("第 {} 个字符完全无法写入（共 {} 个字符），中止注入", index, index);
                return total == 0 ? -1 : total;
            }
            if (written < pair.size()) {
                log.error("第 {} 个字符只写入了 {}/{} 个事件——这个字可能缺失",
                        index, written, pair.size());
            }
            total += written;

            // 字符之间留间隔：让目标程序的输入法/组合状态机处理完这一个字
            if (i < text.length()) {
                sleep(charGapMillis);
            }
        }
        return total;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return 实际写入的事件数；-1 表示一个都没写进去 */
    /**
     * 把一组按键事件写进**显式分配的原生内存**并交给 {@code SendInput}。
     *
     * <p><b>为什么不用 JNA 的 {@code Structure.toArray()}：</b>实测出现过一个极隐蔽的故障——
     * 用户说「今天天气不错啊」（7 字），微信里出现的是「**今今今今今今今**」：
     * 事件数完全正确（14 = 7×2，{@code SendInput} 报告全部写入成功），
     * 但每个 {@code INPUT} 里的字符字段都被写成了**同一个值**，
     * 于是同一个字被发了 7 遍。
     *
     * <p>{@code new INPUT().toArray(n)} 这种写法依赖 JNA 内部的数组元素分配与
     * {@code write()} 语义，在含 union 的 {@code INPUT} 上踩了坑。
     * 现在改为**自己算偏移、自己写字节**：布局是
     * <pre>
     *   INPUT (40 字节, 8 字节对齐)
     *     +0  DWORD type
     *     +8  KEYBDINPUT（union 的最大成员 MOUSEINPUT 是 24 字节）
     *           +0  WORD  wVk
     *           +2  WORD  wScan      ← KEYEVENTF_UNICODE 时字符放这里
     *           +4  DWORD dwFlags
     *           +8  DWORD time
     *           +16 ULONG_PTR dwExtraInfo   （32 位平台是 +12；本产品只支持 x64）
     * </pre>
     * 完全不依赖 JNA 的结构体写回机制，并且**写完立刻回读校验**——
     * 与其相信它写对了，不如读出来看一眼。
     */
    /**
     * 把一组按键事件交给 {@code SendInput}。
     *
     * <p><b>不手算偏移。</b>偏移和结构大小都交给 JNA 算（{@link Win32#keyboardFieldOffset()}、
     * {@code new INPUT().size()}），并且**写完立刻回读校验**。
     *
     * <p>为什么这么谨慎——这块已经错过两次，而且两次的故障都极其隐蔽：
     * <ol>
     *   <li>第一次：用 {@code new INPUT().toArray(n)} 分配数组，字符没有被逐个写入。</li>
     *   <li>第二次（更根本）：{@link Win32.INPUT} 的 union 里只声明了 16 字节的
     *       {@code KEYBDINPUT}，但 Windows 的 union 最大成员是 24 字节的 {@code MOUSEINPUT}，
     *       于是 JNA 把整个 {@code INPUT} 算成 32 字节、把字符写到了偏移 24。
     *       症状是：{@code SendInput} 报告事件**全部写入成功**，
     *       但字符全丢，目标程序把**同一个字重复 N 遍**（用户看到「今今今今今今今」）。</li>
     * </ol>
     * 所以现在：把 {@code MOUSEINPUT} 也声明进 union 撑到正确大小，
     * 并且启动时打印一次「Java 计算的大小 == 传给 SendInput 的 dwSize」的校验结果。
     */
    /**
     * 把一组按键事件交给 {@code SendInput}。
     *
     * <p><b>自己准备连续内存，不依赖 JNA 编组结构体数组。</b>这块踩过三次坑，
     * 每次都表现成「事件数看起来对，但文字不对」：
     * <ol>
     *   <li>{@code new INPUT().toArray(n)} 分配数组 → 字符没有被逐个写入；</li>
     *   <li>{@code Win32.INPUT} 的 union 只声明 16 字节的 {@code KEYBDINPUT}，
     *       而 Windows 的 union 最大成员是 24 字节的 {@code MOUSEINPUT} →
     *       结构被算成 32 字节、字符写到偏移 24 → <b>同一个字被重复 N 遍</b>；</li>
     *   <li>改对布局后用 {@code INPUT[]} 传参 → {@code SendInput} 直接返回 0。</li>
     * </ol>
     * 所以现在：结构体布局由 JNA 从字段定义算出（{@code MOUSEINPUT} 撑大 union），
     * 但**缓冲区自己分配、偏移自己按字段写入**，最后传 {@code Pointer} 进去。
     * 这样既拿到正确的 40 字节布局，又绕开 JNA 对结构体数组的编组。
     *
     * <p>写入后再**回读校验**：确认每个事件的字符确实是它自己那个字。
     * 与其相信写对了，不如读出来看一眼——前面三次故障都发生在「以为写对了」的时刻。
     */
    private int send(List<Win32.KeyEvent> events) {
        if (events.isEmpty()) {
            return 0;
        }
        logInputLayoutOnce();

        int n = events.size();
        int size = Win32.INPUT_SIZE;
        // 每个 INPUT：+0 type(DWORD)，union 起点在 +8；KEYBDINPUT 内 wVk@0 / wScan@2 / dwFlags@4
        int unionAt = Win32.keyboardFieldOffset();
        int wScanAt = unionAt + 2;
        int flagsAt = unionAt + 4;

        Memory block = new Memory((long) n * size);
        for (int i = 0; i < n; i++) {
            Win32.KeyEvent ev = events.get(i);
            long base = (long) i * size;
            // 先清零，避免残留字节被当成有效字段
            block.setMemory(base, size, (byte) 0);
            block.setInt(base, Win32.INPUT_KEYBOARD);
            block.setShort(base + unionAt, (short) ev.wVk());
            block.setShort(base + wScanAt, (short) ev.wScan());
            block.setInt(base + flagsAt, ev.dwFlags());
        }

        // 回读校验：每个 Unicode 事件的字符必须是它自己
        int checked = 0;
        for (int i = 0; i < n && checked < TRACE_EVENTS; i++) {
            Win32.KeyEvent ev = events.get(i);
            if ((ev.dwFlags() & Win32.KEYEVENTF_UNICODE) == 0) {
                continue;
            }
            long base = (long) i * size;
            short wScan = block.getShort(base + wScanAt);
            short wVk = block.getShort(base + unionAt);
            int flags = block.getInt(base + flagsAt);
            if (wScan != (short) ev.wScan() || wVk != (short) ev.wVk() || flags != ev.dwFlags()) {
                log.error("事件 {} 写入校验失败：wVk={} wScan={} flags=0x{}（期望 {} / {} / 0x{}）",
                        i, wVk, wScan, Integer.toHexString(flags),
                        ev.wVk(), ev.wScan(), Integer.toHexString(ev.dwFlags()));
                block.close();
                return -1;
            }
            checked++;
        }

        int written;
        try {
            written = Win32.User32.INSTANCE.SendInput(n, block, size);
        } catch (RuntimeException e) {
            log.warn("SendInput 抛错：{}", e.toString());
            return -1;
        } finally {
            block.close();
        }
        if (written != n) {
            log.warn("SendInput 只写入了 {}/{} 个事件（GetLastError={}）",
                    written, n, com.sun.jna.Native.getLastError());
            return written == 0 ? -1 : written;
        }
        return written;
    }

    /** 布局校验只打一次日志，避免刷屏。 */
    private static volatile boolean layoutVerified;

    /** 是否打印每个事件的字符明细（{@code -Dtalkinglive.inject.trace=true} 打开）。 */
    private final boolean traceInput = Boolean.getBoolean("talkinglive.inject.trace");

    private static final int TRACE_EVENTS = 64;

    /**
     * 校验并记录 INPUT 的布局。
     *
     * <p>这一行日志是「字符写错位置」那类故障的唯一早期信号：
     * 结构大小必须是 40，union 起点必须是 8（于是字符落在偏移 10）。
     */
    private static void logInputLayoutOnce() {
        if (layoutVerified) {
            return;
        }
        layoutVerified = true;
        int size = new Win32.INPUT().size();
        int unionAt = Win32.keyboardFieldOffset();
        // 正确布局：union 起点 8、KEYBDINPUT 的 wScan 在 union 内 +2 → 结构内偏移 10
        boolean ok = size == Win32.INPUT_SIZE && unionAt == 8;
        log.info("INPUT 布局：结构 {} 字节（应 {}），union 起点 {}（应 8），字符字段偏移 {}：{}",
                size, Win32.INPUT_SIZE, unionAt, unionAt + 2,
                ok ? "正确" : "★ 不正确，字符可能写错位置");
        if (!ok) {
            log.error("INPUT 布局校验失败！union 成员必须声明成 MOUSEINPUT(24B)——"
                    + "用 KEYBDINPUT(16B) 会让整个结构少 8 字节，字符写到错误偏移，"
                    + "表现为「同一个字被重复 N 遍」。");
        }
    }



    // ------------------------------------------------------------ UIPI

    /**
     * 目标窗口所属进程是否比本进程「更特权」（通常意味着以管理员运行）。
     *
     * <p>{@code DESIGN.md} §7 要求「检测注入失败 → 提示需以管理员运行本程序」。
     * {@code SendInput} 在 UIPI 拦截时不报错，所以只能提前判断：
     * 取两边进程令牌的完整性级别（Integrity Level RID）比较。
     *
     * <p>取不到令牌时**保守返回 false**：宁可注入失败后再提示，也不要因为
     * 读不到令牌就拒绝一次本来能成功的注入。
     */
    @Override
    public boolean targetElevated(long hwnd) {
        if (!available || hwnd == 0) {
            return false;
        }
        try {
            HWND h = Win32.hwndOf(hwnd);
            IntByReference pid = new IntByReference();
            Win32.User32.INSTANCE.GetWindowThreadProcessId(h, pid);
            if (pid.getValue() <= 0) {
                return false;
            }
            int targetRid = integrityRid(pid.getValue());
            int selfRid = integrityRid(Win32.Kernel32.INSTANCE.GetCurrentProcessId());
            if (targetRid == Integer.MIN_VALUE || selfRid == Integer.MIN_VALUE) {
                return false;
            }
            return targetRid > selfRid;
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.debug("读取进程完整性级别失败（忽略）：{}", e.toString());
            return false;
        }
    }

    /** @return 完整性级别 RID；读不到返回 {@link Integer#MIN_VALUE} */
    private static int integrityRid(int pid) {
        final int processQueryLimitedInformation = 0x1000;
        final int tokenQuery = 0x0008;
        final int tokenIntegrityLevel = 25;
        Pointer process = null;
        Pointer token = null;
        try {
            process = Win32.Kernel32.INSTANCE.OpenProcess(processQueryLimitedInformation, false, pid);
            if (process == null) {
                return Integer.MIN_VALUE;
            }
            Pointer[] tokenOut = new Pointer[1];
            if (!Win32.Advapi32.INSTANCE.OpenProcessToken(process, tokenQuery, tokenOut)) {
                return Integer.MIN_VALUE;
            }
            token = tokenOut[0];
            if (token == null) {
                return Integer.MIN_VALUE;
            }
            // TOKEN_MANDATORY_LABEL 的布局：SID_AND_ATTRIBUTES{ PSID Sid; DWORD Attributes; }
            // 取前 8 字节里的指针，再读该 SID 的最后一个子权限（RID）。
            int[] retLen = new int[1];
            int size = Native.POINTER_SIZE + 4 + 8; // 宽松一点，多给几个字节
            Pointer buf = new Memory(size);
            if (!Win32.Advapi32.INSTANCE.GetTokenInformation(token, tokenIntegrityLevel, buf, size, retLen)) {
                return Integer.MIN_VALUE;
            }
            Pointer sid = buf.getPointer(0);
            if (sid == null) {
                return Integer.MIN_VALUE;
            }
            // SID 结构：Revision(1) SubAuthorityCount(1) IdentifierAuthority(6) SubAuthority[]
            int subAuthorityCount = sid.getByte(1) & 0xFF;
            if (subAuthorityCount <= 0) {
                return Integer.MIN_VALUE;
            }
            int offset = 8 + (subAuthorityCount - 1) * 4;
            return sid.getInt(offset);
        } finally {
            if (token != null) {
                Win32.Kernel32.INSTANCE.CloseHandle(token);
            }
            if (process != null) {
                Win32.Kernel32.INSTANCE.CloseHandle(process);
            }
        }
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String unavailableReason() {
        return unavailableReason;
    }

    @Override
    public String describe() {
        return "SendInput + KEYEVENTF_UNICODE（不碰剪贴板，逐字符发送，间隔 " + charGapMillis + "ms）";
    }

    @Override
    public Map<String, Object> stats() {
        return Map.of("injections", injections.get(), "codePoints", injectedCodePoints.get());
    }
}

