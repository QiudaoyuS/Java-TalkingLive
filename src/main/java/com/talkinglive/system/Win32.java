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
    }

    /** INPUT 的 union。用 {@code write()} 时以 KEYBDINPUT 解释。 */
    @Structure.FieldOrder({"type", "ki"})
    public static class INPUT extends Structure {
        public int type;
        public KEYBDINPUT ki = new KEYBDINPUT();

        public INPUT() {
            super();
            type = INPUT_KEYBOARD;
        }

        public INPUT(Pointer p) {
            super(p);
            read();
        }

        public static class ByReference extends INPUT implements Structure.ByReference {}
    }

    /** win32 函数。 */
    public interface User32 extends StdCallLibrary {

        User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);

        /** 注入键盘输入。返回成功写入的事件数。 */
        int SendInput(int nInputs, INPUT[] pInputs, int cbSize);

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

        boolean GetWindowRect(HWND hwnd, com.sun.jna.platform.win32.WinDef.RECT lpRect);

        /** 进程/窗口 DPI。 */
        int GetDpiForWindow(HWND hwnd);

        boolean SetProcessDpiAwarenessContext(Pointer value);

        boolean SetForegroundWindow(HWND hwnd);

        int GA_ROOT = 2;
    }

    /** kernel32。 */
    public interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class, W32APIOptions.DEFAULT_OPTIONS);

        int GetCurrentProcessId();

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
}
