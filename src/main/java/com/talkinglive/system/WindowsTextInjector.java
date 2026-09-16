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

    /** 每批的码点数。一个码点最多 2 个 INPUT（代理对），故一次最多 512 个事件。 */
    private static final int BATCH_CODE_POINTS = 256;

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
     * 带目标窗口校验的注入。
     *
     * @param expectedWindow 段落开始时的前台窗口句柄；非 0 时先校验它仍是前台窗口
     */
    public Result inject(int backspaces, String text, long expectedWindow) {
        if (!available) {
            return Result.fail(Result.Failure.UNAVAILABLE, unavailableReason);
        }
        if (expectedWindow != 0) {
            long now = Win32.hwndValue(Win32.User32.INSTANCE.GetForegroundWindow());
            if (now != expectedWindow) {
                String msg = "提交时前台窗口已从 0x" + Long.toHexString(expectedWindow)
                        + " 变为 0x" + Long.toHexString(now) + "，为避免把文字误发到别的程序，本段已放弃";
                log.warn("{}", msg);
                return Result.fail(Result.Failure.FOREGROUND_CHANGED, msg);
            }
            if (targetElevated(expectedWindow)) {
                String msg = "目标程序正在以管理员身份运行，Windows 的 UIPI 隔离会静默丢弃注入的文字。"
                        + "请以管理员身份重新启动 TalkingLive 后再试。";
                log.warn("{}（目标窗口 0x{}）", msg, Long.toHexString(expectedWindow));
                return Result.fail(Result.Failure.UIPI_BLOCKED, msg);
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
        log.info("注入完成：退格={} 文本={} 事件数={}", backspaces, Logging.describeWithFingerprint(body), events);
        return Result.ok(events);
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
            List<Win32.KeyEvent> keys = new ArrayList<>(batch.length() * 2);
            int i = 0;
            while (i < batch.length()) {
                int cp = batch.codePointAt(i);
                i += Character.charCount(cp);
                if (Character.charCount(cp) == 1) {
                    keys.add(Win32.KeyEvent.unicodeDown((char) cp));
                    keys.add(Win32.KeyEvent.unicodeUp((char) cp));
                } else {
                    // 代理对：按 UTF-16 的两个 code unit 分别发送。
                    // Windows 会在目标程序侧把高低代理合成一个字符（WM_CHAR 各发一次，
                    // 支持 Unicode 的控件会正确组合）。
                    char hi = Character.highSurrogate(cp);
                    char lo = Character.lowSurrogate(cp);
                    keys.add(Win32.KeyEvent.unicodeDown(hi));
                    keys.add(Win32.KeyEvent.unicodeUp(hi));
                    keys.add(Win32.KeyEvent.unicodeDown(lo));
                    keys.add(Win32.KeyEvent.unicodeUp(lo));
                }
            }
            int n = send(keys);
            if (n < 0) {
                return -1;
            }
            total += n;
        }
        return total;
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
