package com.talkinglive.system;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Win32 互操作集中定义。
 *
 * <p>只放**本产品真正用到**的那十几个函数，不做一个通用的 Win32 包装——
 * {repos}里已有的 jna-platform 覆盖了结构体与常量，这里只补缺口。
 *
 * <p>本类属于 {@code system} 层。{@code core} / {@code text} 不得引用它（§4.5）。
 */
public final class Win32 {

    private Win32() {}

    public static final int INPUT_KEYBOARD = 1;
    public static final int KEYEVENTF_EXTENDEDKEY = 0x0001;
    public static final int KEYEVENTF_KEYUP = 0x0002;
    public static final int KEYEVENTF_UNICODE = 0x0004;
    public static final int KEYEVENTF_SCANCODE = 0x0008;

    public static final int VK_RETURN = 0x0D;
    public static final int VK_CONTROL = 0x11;
    public static final int VK_ESCAPE = 0x1B;
    public static final int VK_BACK = 0x08;

    public static final int GWL_EXSTYLE = -20;
    public static final int WS_EX_TOOLWINDOW = 0x00000080;
    public static final int WS_EX_NOACTIVATE = 0x08000000;
    public static final int WS_EX_TOPMOST = 0x00000008;

    /** SendInput 的结构大小（64 位下为 40）。用于 dwSize 字段。 */
    public static final int INPUT_SIZE = 40;

    /** 键盘输入事件。 */
    @Structure.FieldOrder({"wVk", "wScan", "dwFlags", "time", "dwExtraInfo"})
    public static class KEYBDINPUT extends Structure {
        public short wVk;
        public short wScan;
        public int dwFlags;
        public int time;
        public Pointer dwExtraInfo;

        public KEYBDINPUT() {
            super();
        }

        public KEYBDINPUT(Pointer p) {
            super(p);
            read();
        }

        public static class ByReference extends KEYBDINPUT implements Structure.ByReference {
            public ByReference() {
                super();
            }

            public ByReference(Pointer p) {
                super(p);
            }
        }

        /**
         * 字段在结构体内的偏移（fieldOffset 是 protected，这里开个出口）。
         *
         * <p>暴露出来是为了**可测**：这块偏移算错过一次，而算错的后果是
         * 「SendInput 报告成功、但字符全部丢失」——必须有断言把它按住。
         */
        public int offsetOf(String fieldName) {
            return fieldOffset(fieldName);
        }
    }

    /**
     * 鼠标输入事件。
     *
     * <p><b>它在本类里的唯一作用是「把 union 撑到正确大小」</b>——产品本身不用鼠标注入。
     * 这不是多余的：Windows 的 {@code INPUT} 里 union 的最大成员是 {@code MOUSEINPUT}
     * （24 字节，因为 {@code dwExtraInfo} 在 x64 下要对齐到 8 字节边界），
     * 于是 {@code KEYBDINPUT} 实际落在**偏移 32**。
     *
     * <p>如果 union 里只声明 {@code KEYBDINPUT}（16 字节），JNA 算出的 {@code INPUT}
     * 只有 32 字节、并把字符写到偏移 24 —— 实测症状极隐蔽：
     * <b>{@code SendInput} 报告事件全部写入成功，但字符全部丢失，
     * 目标程序把同一个字重复 N 遍</b>（用户看到「今今今今今今今」）。
     *
     * <p>所以这里把 {@code MOUSEINPUT} 也声明出来，并且用
     * {@code Win32WindowStyles} 里那条「Java 计算的大小必须等于传给 SendInput 的
     * dwSize」的自检守着它。
     */
    @Structure.FieldOrder({"dx", "dy", "mouseData", "dwFlags", "time", "dwExtraInfo"})
    public static class MOUSEINPUT extends Structure {
        public int dx;
        public int dy;
        public int mouseData;
        public int dwFlags;
        public int time;
        public Pointer dwExtraInfo;

        public MOUSEINPUT() {
            super();
        }

        public MOUSEINPUT(Pointer p) {
            super(p);
            read();
        }
    }

    /**
     * {@code SendInput} 的输入事件。
     *
     * <p>布局（x64，共 40 字节）：
     * <pre>
     *   +0   DWORD type
     *   +8   union { MOUSEINPUT(24) | KEYBDINPUT(16) | HARDWAREINPUT(8) }  ← 按最大成员算
     *   +32    KEYBDINPUT 的字段起点
     * </pre>
     */
    @Structure.FieldOrder({"type", "u"})
    public static class INPUT extends Structure {
        public int type;
        /**
         * union 的占位成员。
         *
         * <p><b>必须是 {@link MOUSEINPUT}（24 字节），不能是 {@link KEYBDINPUT}（16 字节）</b>：
         * 取 union 里最大的那个才能让 {@code KEYBDINPUT} 落在偏移 32。
         * 用小的会让整个结构少 8 字节、字符写到错误偏移、目标程序重复上一个字符。
         */
        public MOUSEINPUT u = new MOUSEINPUT();

        public INPUT() {
            super();
            type = INPUT_KEYBOARD;
        }

        public INPUT(Pointer p) {
            super(p);
            read();
        }

        /** 以 KEYBDINPUT 视角解释 union 里的数据。 */
        public KEYBDINPUT keyboard() {
            return new KEYBDINPUT(getPointer().share(keyboardFieldOffset()));
        }

        /** union 字段在本结构体内的偏移；fieldOffset 是 protected，这里开个出口。 */
        public int keyboardFieldOffset() {
            return fieldOffset("u");
        }

        /** 任意字段在结构体内的偏移（可测性）。 */
        public int offsetOf(String fieldName) {
            return fieldOffset(fieldName);
        }

        public static class ByReference extends INPUT implements Structure.ByReference {}
    }

    /**
     * {@code KEYBDINPUT} 字段在 {@link INPUT} 里的偏移。
     *
     * <p>由 JNA 的 {@code fieldOffset} 实测得出（x64 下为 32），**不靠手算**——
     * 手算已经把这块算错过一次（少算了 8 字节，导致字符全部丢失、
     * 目标程序把同一个字重复 N 遍）。
     *
     * <p>注意这个偏移是**结构体内**偏移，而 {@code KEYBDINPUT} 本身又在 union 内，
     * 因此不能拿它当 union 大小用；union 大小由 {@link MOUSEINPUT} 决定。
     */
    public static int keyboardFieldOffset() {
        return new INPUT().keyboardFieldOffset();
    }

    /** win32 函数。 */
    public interface User32 extends StdCallLibrary {

        User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);

        /**
         * 注入输入事件。
         *
         * <p>参数写成 {@link Pointer} 而不是 {@code INPUT[]}：实测用结构数组调用时
         * {@code SendInput} 返回 0（一个事件都没写入），而同样的数据放进一块连续的
         * 原生内存再传指针就正常。JNA 对「含 union 的结构体数组」的编组不可靠，
         * 所以由调用方自己准备缓冲区（见 {@code WindowsTextInjector.send}）。
         *
         * @param nInputs 事件个数
         * @param pInputs 连续存放 {@code nInputs} 个 INPUT 的内存块
         * @param cbSize  单个 INPUT 的字节数（x64 为 40）
         * @return 成功写入的事件数
         */
        int SendInput(int nInputs, Pointer pInputs, int cbSize);

        HWND GetForegroundWindow();

        HWND GetAncestor(HWND hwnd, int gaFlags);

        int GetWindowTextW(HWND hwnd, char[] lpString, int nMaxCount);

        int GetWindowThreadProcessId(HWND hwnd, com.sun.jna.ptr.IntByReference lpdwProcessId);

        int GetWindowLongW(HWND hwnd, int nIndex);

        int SetWindowLongW(HWND hwnd, int nIndex, int dwNewLong);

        boolean SetWindowPos(HWND hwnd, HWND hwndInsertAfter, int x, int y, int cx, int cy, int flags);

        boolean IsWindowVisible(HWND hwnd);

        boolean IsWindow(HWND hwnd);

        /** 物理像素的屏幕坐标（DESIGN.md §4.4：Win32 用物理像素）。 */
        boolean GetCursorPos(com.sun.jna.platform.win32.WinDef.POINT lpPoint);

        boolean GetCaretPos(com.sun.jna.platform.win32.WinDef.POINT lpPoint);

        boolean ClientToScreen(HWND hwnd, com.sun.jna.platform.win32.WinDef.POINT lpPoint);

        HWND GetFocus();

        /**
         * 查某个键/鼠标键是否处于按下状态（自检与现场快照用）。
         *
         * <p>修饰键若被卡在按下状态，表现正是「点任务栏、关闭按钮都没反应」
         * 而屏幕上看不出任何异常——这是「点不动」类问题里最容易被忽略的一条。
         */
        short GetAsyncKeyState(int vKey);

        boolean GetWindowRect(HWND hwnd, com.sun.jna.platform.win32.WinDef.RECT lpRect);

        /** 进程/窗口 DPI。 */
        int GetDpiForWindow(HWND hwnd);

        boolean SetProcessDpiAwarenessContext(Pointer value);

        /**
         * 把窗口带到前台。
         *
         * <p>用于「提交时前台窗口已变」的补救：先试着把焦点还原到用户原本的目标窗口。
         * 可能被 Windows 的前台锁拒绝——失败时用 {@code AttachThreadInput} 借输入队列再试
         * （见 {@code WindowsTextInjector.forceForeground}）。
         */
        boolean SetForegroundWindow(HWND hwnd);

        int GA_ROOT = 2;

        /** 某屏幕点（物理像素）上最上层的窗口。用于自检诊断点击为何没送达。 */
        HWND WindowFromPoint(com.sun.jna.platform.win32.WinDef.POINT p);

        /** 按类名（与可选窗口名）查找顶层窗口。诊断用，也是找 Shell_TrayWnd 的唯一途径。 */
        HWND FindWindow(String lpClassName, String lpWindowName);

        /** 取窗口类名。诊断用。 */
        int GetClassNameW(HWND hwnd, char[] lpClassName, int nMaxCount);

        /** 把窗口提到 Z 序顶端（不激活）。配合 AttachThreadInput 使用。 */
        boolean BringWindowToTop(HWND hwnd);

        /** 把键盘焦点设到某个控件（子窗口）。 */
        HWND SetFocus(HWND hwnd);

        /**
         * 把两个线程的输入队列临时接在一起。
         *
         * <p>这是绕过 Windows 前台锁的常规手段：接上之后调用方被视为「有资格」设置前台。
         * **用完必须解开**（{@code fAttach=false}）。
         */
        boolean AttachThreadInput(int idAttach, int idAttachTo, boolean fAttach);

        /** 枚举顶层窗口。诊断用（回调返回 false 表示停止枚举）。 */
        boolean EnumWindows(EnumWindowsProc lpEnumFunc, com.sun.jna.Pointer lParam);

        /** {@link #EnumWindows} 的回调。 */
        interface EnumWindowsProc extends com.sun.jna.win32.StdCallLibrary.StdCallCallback {
            boolean callback(HWND hwnd, com.sun.jna.Pointer lParam);
        }
    }

    /**
     * 只用来读写窗口扩展样式的 user32 视图。
     *
     * <p>刻意用 {@link W32APIOptions#DEFAULT_OPTIONS}（Unicode 类型映射）而不是
     * {@code W32APIOptions.UNICODE_OPTIONS}：后者的 {@code LPARAM} 被映射成
     * {@code Pointer}，而 {@code SetWindowLongPtr} 的第三个参数是 {@code LONG_PTR}（数值），
     * 两者对不上会编译失败。本接口只读位标志、不涉及字符串，用默认映射最省事。
     */
    public interface WinStyle extends StdCallLibrary {
        WinStyle INSTANCE = Native.load("user32", WinStyle.class, W32APIOptions.DEFAULT_OPTIONS);

        /** 读窗口样式。64 位下返回 LONG_PTR。 */
        com.sun.jna.platform.win32.BaseTSD.LONG_PTR GetWindowLongPtrW(HWND hwnd, int nIndex);

        /** 写窗口样式。 */
        com.sun.jna.platform.win32.BaseTSD.LONG_PTR SetWindowLongPtrW(HWND hwnd, int nIndex,
                com.sun.jna.platform.win32.BaseTSD.LONG_PTR dwNewLong);

        /**
         * 取线程的 GUI 状态（含该线程当前有键盘焦点的控件）。
         *
         * <p>用它而不是 {@code GetFocus()}：后者只对调用线程自己的窗口有效。
         */
        boolean GetGUIThreadInfo(int idThread, GUITHREADINFO pgui);
    }

    /** kernel32。 */
    public interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class, W32APIOptions.DEFAULT_OPTIONS);

        int GetCurrentProcessId();

        /** 当前线程 id（{@code AttachThreadInput} 需要）。 */
        int GetCurrentThreadId();

        Pointer OpenProcess(int dwDesiredAccess, boolean bInheritHandle, int dwProcessId);

        boolean CloseHandle(Pointer hObject);
    }

    /** advapi32：读进程令牌的完整性级别，用于判断目标程序是否以管理员运行。 */
    public interface Advapi32 extends StdCallLibrary {
        Advapi32 INSTANCE = Native.load("advapi32", Advapi32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean OpenProcessToken(Pointer processHandle, int desiredAccess, Pointer[] tokenHandle);

        boolean GetTokenInformation(Pointer tokenHandle, int tokenInformationClass, Pointer tokenInformation,
                int tokenInformationLength, int[] returnLength);
    }

    /**
     * 一个按键事件。
     *
     * <p>为什么需要 {@code scan}：{@link #KEYEVENTF_UNICODE} 时字符放在 {@code wScan}，
     * {@code wVk} 必须为 0。这是 SendInput 的约定，写反了会打出完全无关的字符。
     */
    public record KeyEvent(int wVk, int wScan, int dwFlags) {

        public static KeyEvent unicode(char c) {
            return new KeyEvent(0, c, KEYEVENTF_UNICODE);
        }

        public static KeyEvent unicodeDown(char c) {
            return new KeyEvent(0, c, KEYEVENTF_UNICODE);
        }

        public static KeyEvent unicodeUp(char c) {
            return new KeyEvent(0, c, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP);
        }

        public static KeyEvent vkDown(int vk) {
            return new KeyEvent(vk, 0, 0);
        }

        public static KeyEvent vkUp(int vk) {
            return new KeyEvent(vk, 0, KEYEVENTF_KEYUP);
        }
    }

    /** 把 JNA 平台类型转成我们自己的 HWND 包装，便于在 core 之外传递数值句柄。 */
    public static long hwndValue(HWND h) {
        return h == null ? 0L : Pointer.nativeValue(h.getPointer());
    }

    public static HWND hwndOf(long value) {
        return value == 0 ? null : new HWND(Pointer.createConstant(value));
    }

    /** 一个线程的 GUI 状态，用于取「那个线程里当前有键盘焦点的控件」。 */
    @Structure.FieldOrder({"cbSize", "flags", "hwndActive", "hwndFocus", "hwndCapture",
            "hwndMenuOwner", "hwndMoveSize", "hwndCaret", "rcCaret"})
    public static class GUITHREADINFO extends Structure {
        public int cbSize;
        public int flags;
        public HWND hwndActive;
        public HWND hwndFocus;
        public HWND hwndCapture;
        public HWND hwndMenuOwner;
        public HWND hwndMoveSize;
        public HWND hwndCaret;
        public com.sun.jna.platform.win32.WinDef.RECT rcCaret;

        public GUITHREADINFO() {
            super();
            cbSize = size();
        }
    }

    /**
     * 取某个线程的 GUI 状态。
     *
     * <p>为什么要它：{@code GetFocus()} 只对**调用线程自己**的窗口有效，拿别的进程的焦点
     * 必须走 {@code GetGUIThreadInfo}。而「把焦点还原到用户原本的输入框」正需要知道
     * 那个输入框（子窗口）的句柄——只知道顶层窗口是不够的，文字可能打到窗口本身
     * 而不是输入框里。
     *
     * @return 是否成功
     */
    public static boolean guiThreadInfo(int threadId, GUITHREADINFO out) {
        try {
            out.cbSize = out.size();
            boolean ok = Win32.WinStyle.INSTANCE.GetGUIThreadInfo(threadId, out);
            if (ok) {
                out.read();
            }
            return ok;
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return false;
        }
    }
}
