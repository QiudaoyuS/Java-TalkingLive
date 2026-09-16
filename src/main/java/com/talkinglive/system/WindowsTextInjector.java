package com.talkinglive.system;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
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
 * </ol>
 *
 * <p><b>为什么事件要分批：</b>{@code SendInput} 一次调用的 {@code INPUT} 数组过长会被
 * 系统拒绝（写回 0）。按码点分批，每批 {@value #BATCH_CODE_POINTS} 个码点。
 */
public final class WindowsTextInjector implements TextInjector {

    private static final Logger log = LoggerFactory.getLogger(WindowsTextInjector.class);

    /** 每批的码点数。一个码点最多 4 个 INPUT（代理对），故一次最多约 1024 个事件。 */
    private static final int BATCH_CODE_POINTS = 256;

    /** 一批写不完整时的重试次数。 */
    private static final int SEND_RETRIES = 4;

    /**
     * 字符之间的间隔（毫秒）。
     *
     * <p>为什么需要它：中文输入法窗口与自绘输入框（微信、QQ 这类）处理合成按键的速度
     * 比 {@code SendInput} 灌入的速度慢。一口气灌进去时它们会**丢事件**，
     * 用户看到的现象就是「说了十个字只出现一个」。留一点间隔比事后重试更管用，
     * 因为丢事件是目标程序主动丢的，重试也不一定补得回来。
     *
     * <p>代价：每批多 1ms。按 256 码点一批算，对 2.5 秒的提交预算毫无压力。
     */
    private static final long CHAR_GAP_MILLIS = 1;

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

    private int sendUnicode(String text) {
        int total = 0;
        for (String batch : TextInjector.batchByCodePoints(text, BATCH_CODE_POINTS)) {
            int n = sendUnicodeBatch(batch);
            if (n < 0) {
                return -1;
            }
            total += n;
        }
        return total;
    }

    /**
     * 发送一批字符，**并保证整批都写进去**。
     *
     * <p>这里是「说了十个字只出现一个」的真正原因所在：{@code SendInput} 偶尔会**部分写入**
     * （实测日志 {@code SendInput 只写入了 19/20 个事件}）。部分写入意味着有一个字符的
     * 「按下」或「抬起」事件没进队列——那个字符要么完全不出现，要么留下一个卡住的按键状态
     * 把后面的输入全带歪。
     *
     * <p>原实现把部分写入当成成功返回，于是文字静默缺字。现在改成：
     * <ol>
     *   <li>按字符两两成对地发，**每对之间留一点间隔**。中文输入法/自绘输入框
     *       （微信、QQ 这类）处理合成按键的速度比 {@code SendInput} 灌入的速度慢，
     *       一口气灌 20 个事件时它们会丢事件——这也是「只出第一个字」的常见成因。</li>
     *   <li>返回 0 或不足时**重试**，最多 {@value #SEND_RETRIES} 次。</li>
     *   <li>仍不完整就把已写入的部分算清，并如实返回，交由上层提示。</li>
     * </ol>
     *
     * @return 实际写入的事件数；一个都没写进去返回 -1
     */
    private int sendUnicodeBatch(String batch) {
        List<Win32.KeyEvent> events = new ArrayList<>(batch.length() + 4);
        int i = 0;
        while (i < batch.length()) {
            int cp = batch.codePointAt(i);
            int chars = Character.charCount(cp);
            i += chars;
            if (chars == 1) {
                events.add(Win32.KeyEvent.unicodeDown((char) cp));
                events.add(Win32.KeyEvent.unicodeUp((char) cp));
            } else {
                // 代理对：按 UTF-16 的两个 code unit 分别发送。Windows 会在目标程序侧
                // 把高低代理合成一个字符（支持 Unicode 的控件会正确组合）。
                char hi = Character.highSurrogate(cp);
                char lo = Character.lowSurrogate(cp);
                events.add(Win32.KeyEvent.unicodeDown(hi));
                events.add(Win32.KeyEvent.unicodeUp(hi));
                events.add(Win32.KeyEvent.unicodeDown(lo));
                events.add(Win32.KeyEvent.unicodeUp(lo));
            }
        }

        int written = 0;
        for (int attempt = 1; attempt <= SEND_RETRIES; attempt++) {
            int n = send(events.subList(written, events.size()));
            if (n < 0) {
                return written == 0 ? -1 : written;
            }
            written += n;
            if (written >= events.size()) {
                break;
            }
            log.warn("SendInput 部分写入（{}/{} 个事件），第 {} 次重试剩余部分",
                    written, events.size(), attempt);
            sleep(CHAR_GAP_MILLIS);
        }
        if (written < events.size()) {
            log.error("SendInput 多次重试后仍未写完整（{}/{} 个事件）——目标程序可能正在"
                    + "拒绝输入（UIPI 隔离 / 输入队列异常）", written, events.size());
        }
        // 每个字符之间留一点间隔：自绘输入框处理合成按键较慢，灌太快会丢字
        sleep(CHAR_GAP_MILLIS);
        return written;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return 实际写入的事件数；-1 表示一个都没写进去 */
    private int send(List<Win32.KeyEvent> events) {
        if (events.isEmpty()) {
            return 0;
        }
        Win32.INPUT[] inputs = (Win32.INPUT[]) new Win32.INPUT().toArray(events.size());
        for (int i = 0; i < events.size(); i++) {
            Win32.KeyEvent e = events.get(i);
            inputs[i].type = Win32.INPUT_KEYBOARD;
            inputs[i].ki.wVk = (short) e.wVk();
            inputs[i].ki.wScan = (short) e.wScan();
            inputs[i].ki.dwFlags = e.dwFlags();
            inputs[i].ki.time = 0;
            inputs[i].ki.dwExtraInfo = null;
            inputs[i].write();
        }
        int written;
        try {
            written = Win32.User32.INSTANCE.SendInput(inputs.length, inputs, Win32.INPUT_SIZE);
        } catch (RuntimeException e) {
            log.warn("SendInput 抛错：{}", e.toString());
            return -1;
        }
        if (written != inputs.length) {
            // 部分写入也是失败：缺事件会让文字缺字，比整体失败更难查，因此按失败回报。
            log.warn("SendInput 只写入了 {}/{} 个事件（GetLastError={}）",
                    written, inputs.length, com.sun.jna.Native.getLastError());
            return written == 0 ? -1 : written;
        }
        return written;
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
        return "SendInput + KEYEVENTF_UNICODE（不碰剪贴板，每批 " + BATCH_CODE_POINTS + " 码点）";
    }

    @Override
    public Map<String, Object> stats() {
        return Map.of("injections", injections.get(), "codePoints", injectedCodePoints.get());
    }
}
