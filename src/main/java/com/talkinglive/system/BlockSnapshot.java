package com.talkinglive.system;

import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 「屏幕被抢占 / 点不动」的**现场快照**。
 *
 * <p>为什么需要它：这类问题的现场只存在几秒（重启就没了），事后靠用户描述
 * 几乎无法定位。这个工具在**问题正在发生时**一条命令抓下所有相关事实：
 *
 * <ol>
 *   <li><b>有没有大窗口盖住了屏幕</b>——列出所有顶层窗口的矩形与样式，
 *       单独标出「置顶」「不抢焦点」「覆盖大面积」的窗口。</li>
 *   <li><b>修饰键有没有卡住</b>——Ctrl/Alt/Shift/Win 若被按下不放，
 *       表现正是「点任务栏、点关闭按钮都没反应」，而屏幕上看不出任何异常。
 *       这是「点不动」类问题里最容易被忽略的一条。</li>
 *   <li><b>前台窗口是谁</b>——判断是不是某个进程占着前台不放。</li>
 *   <li><b>光标下的窗口是谁</b>——直接看出那个位置被谁接管。</li>
 * </ol>
 *
 * <p>输出：控制台只打 ASCII 摘要（Windows 控制台默认 GBK，中文会乱码并掩盖真问题），
 * 完整 UTF-8 报告写到 {@code %LOCALAPPDATA%\TalkingLive\block-snapshot.txt}。
 *
 * <p>用法：双击 {@code tools\snapshot.cmd}，或
 * {@code java -cp "target\talkinglive.jar;target\lib\*" com.talkinglive.system.BlockSnapshot}
 *
 * <p>⚠️ 必须 DPI 感知地读坐标，否则物理/逻辑像素混淆会把结论带偏
 * （这个坑在 {@link DpiProbe} 里记录过）。
 */
public final class BlockSnapshot {

    /** 一个顶层窗口的关键信息。 */
    private record Win(long hwnd, String cls, String title, RECT rect, long ex, boolean visible) {

        boolean topmost() {
            return (ex & 0x8) != 0;
        }

        boolean noActivate() {
            return (ex & 0x08000000) != 0;
        }

        boolean toolWindow() {
            return (ex & 0x80) != 0;
        }

        boolean layered() {
            return (ex & 0x80000) != 0;
        }

        boolean transparent() {
            return (ex & 0x20) != 0;
        }

        int width() {
            return rect.right - rect.left;
        }

        int height() {
            return rect.bottom - rect.top;
        }

        long area() {
            return (long) Math.max(0, width()) * Math.max(0, height());
        }
    }

    private BlockSnapshot() {}

    public static void main(String[] args) {
        DpiScale.initProcessAwareness();
        StringBuilder sb = new StringBuilder();

        int dpi = DpiScale.systemDpi();
        java.awt.Rectangle logical = DpiScale.virtualBounds();
        long screenArea = (long) logical.width * logical.height;
        long selfPid = ProcessHandle.current().pid();

        sb.append("=== 屏幕现场快照（排查「点不动」） ===\n");
        sb.append("时间          : ").append(java.time.LocalDateTime.now()).append('\n');
        sb.append("逻辑屏幕      : ").append(logical.width).append('x').append(logical.height)
                .append("   DPI=").append(dpi).append('\n');
        sb.append("快照进程 pid  : ").append(selfPid).append('\n');
        sb.append("说明          : 本工具本身不影响被观察的状态，只读。\n\n");

        List<Win> all = listTopLevelWindows();
        sb.append("顶层窗口总数  : ").append(all.size()).append("\n\n");

        // ---- ① 可能挡住输入的窗口 ----
        sb.append("--- ① 可能挡住输入的窗口（置顶，或面积 > 屏幕 25%）---\n");
        List<Win> suspicious = new ArrayList<>();
        for (Win w : all) {
            if (w.visible() && (w.topmost() || w.area() * 4 > screenArea)) {
                suspicious.add(w);
            }
        }
        if (suspicious.isEmpty()) {
            sb.append("  （没有）→ 不存在「大窗口或置顶窗口盖住屏幕」这种情况。\n");
        } else {
            sb.append(String.format("  %-12s %-24s %-30s %-11s %s%n",
                    "hwnd", "类名", "标题", "尺寸", "样式标记"));
            for (Win w : suspicious) {
                sb.append(String.format("  0x%-10x %-24s %-30s %-11s %s%n",
                        w.hwnd(), cut(w.cls(), 24), cut(w.title(), 30),
                        w.width() + "x" + w.height(), flags(w)));
            }
            sb.append("  提示：若其中有尺寸接近全屏的普通窗口，它就是挡住输入的那个。\n");
        }
        sb.append('\n');

        // ---- ② 不抢焦点的窗口 ----
        sb.append("--- ② 不抢焦点的窗口（WS_EX_NOACTIVATE；本产品的悬浮球/浮窗属此类）---\n");
        boolean any = false;
        for (Win w : all) {
            if (w.visible() && w.noActivate()) {
                any = true;
                sb.append(String.format("  0x%-10x %-22s %-28s %-11s %s%n",
                        w.hwnd(), cut(w.cls(), 22), cut(w.title(), 28),
                        w.width() + "x" + w.height(), flags(w)));
            }
        }
        if (!any) {
            sb.append("  （没有）\n");
        }
        sb.append('\n');

        // ---- ③ 修饰键与鼠标键 ----
        sb.append("--- ③ 修饰键 / 鼠标键状态（卡住就会表现成「什么都点不动」）---\n");
        String[][] keys = {
            {"Shift", "16"}, {"Ctrl", "17"}, {"Alt", "18"}, {"Win", "91"},
            {"左Shift", "160"}, {"右Shift", "161"}, {"左Ctrl", "162"}, {"右Ctrl", "163"},
            {"左Alt", "164"}, {"右Alt", "165"}, {"CapsLock", "20"},
            {"鼠标左键", "1"}, {"鼠标右键", "2"}, {"鼠标中键", "4"},
        };
        List<String> stuck = new ArrayList<>();
        StringBuilder keyLine = new StringBuilder("  ");
        for (String[] k : keys) {
            short state = Win32.User32.INSTANCE.GetAsyncKeyState(Integer.parseInt(k[1]));
            boolean down = (state & 0x8000) != 0;
            if (down) {
                stuck.add(k[0]);
            }
            keyLine.append(k[0]).append('=').append(down ? "按下" : "抬起").append("  ");
        }
        sb.append(keyLine).append('\n');
        if (stuck.isEmpty()) {
            sb.append("  ✓ 全部抬起 —— 不是「按键卡住」导致的点不动。\n");
        } else {
            sb.append("  ★★★ 以下按键处于【按下】状态：").append(String.join("、", stuck)).append('\n');
            sb.append("      这会直接导致「点任务栏 / 关闭按钮都没反应」，而屏幕上看不出异常。\n");
            sb.append("      恢复办法：按一下该键再松开（或直接重新插拔键盘）。\n");
        }
        sb.append('\n');

        // ---- ④ 前台窗口 ----
        sb.append("--- ④ 前台窗口 ---\n");
        long fg = Win32.hwndValue(Win32.User32.INSTANCE.GetForegroundWindow());
        if (fg == 0) {
            sb.append("  ★ GetForegroundWindow() 返回 0：当前系统没有前台窗口。\n");
            sb.append("    若持续如此，说明有进程「占着前台但没有窗口」，需要结束它才能恢复。\n");
        } else {
            var pid = new com.sun.jna.ptr.IntByReference();
            Win32.User32.INSTANCE.GetWindowThreadProcessId(Win32.hwndOf(fg), pid);
            sb.append(String.format("  0x%x  pid=%d  '%s'%n", fg, pid.getValue(),
                    ForegroundWatcher.title(fg)));
        }
        sb.append('\n');

        // ---- ⑤ 光标下的窗口 ----
        sb.append("--- ⑤ 光标下的窗口 ---\n");
        POINT pt = new POINT();
        if (Win32.User32.INSTANCE.GetCursorPos(pt)) {
            sb.append(String.format("  物理 %d,%d   逻辑 %d,%d%n", pt.x, pt.y,
                    DpiScale.physicalToLogical(pt.x, dpi), DpiScale.physicalToLogical(pt.y, dpi)));
            HWND under = Win32.User32.INSTANCE.WindowFromPoint(pt);
            long underHwnd = Win32.hwndValue(under);
            if (underHwnd == 0) {
                sb.append("  该点没有窗口返回（可能是桌面，或跨会话查询被拒）\n");
            } else {
                var pid = new com.sun.jna.ptr.IntByReference();
                Win32.User32.INSTANCE.GetWindowThreadProcessId(under, pid);
                sb.append(String.format("  最上层窗口 = 0x%x  pid=%d  '%s'%n",
                        underHwnd, pid.getValue(), ForegroundWatcher.title(underHwnd)));
            }
        }
        sb.append('\n');

        // ---- ⑥ 其它进程里可能相关的大窗口（便于按 pid 定位）----
        sb.append("--- ⑥ 判读指引 ---\n");
        sb.append("  · ① 里有接近全屏的普通窗口 → 就是它挡住了输入；按上面的 pid 结束该进程。\n");
        sb.append("  · ③ 里出现 ★★★              → 按住那个键（或按一下再松开）即可立即恢复。\n");
        sb.append("  · ④ 返回 0 且持续如此       → 有进程占着前台不放，结束它。\n");
        sb.append("  · 以上全部正常               → 不是「窗口挡住输入」；请附上截图与完整日志。\n");
        sb.append("\n--- 附：本产品自己的窗口（对照用）---\n");
        sb.append("  悬浮球与浮窗应当是 68x68 / 约 471x80 的小窗口，且带 NoActivate+ToolWindow。\n");
        sb.append("  若看到本产品的窗口尺寸异常大（例如上千像素），那就是问题所在。\n");

        String report = sb.toString();
        System.out.println(toAscii(report));

        try {
            Path out = com.talkinglive.core.AppPaths.home().resolve("block-snapshot.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, report, StandardCharsets.UTF_8);
            System.out.println("\n[snapshot] full UTF-8 report -> " + out);
        } catch (IOException e) {
            System.out.println("[snapshot] cannot write report: " + e);
        }
        System.exit(0);
    }

    private static List<Win> listTopLevelWindows() {
        List<Win> out = new ArrayList<>();
        Win32.User32.INSTANCE.EnumWindows((h, l) -> {
            RECT r = new RECT();
            Win32.User32.INSTANCE.GetWindowRect(h, r);
            char[] buf = new char[256];
            int n = Win32.User32.INSTANCE.GetClassNameW(h, buf, buf.length);
            String cls = n <= 0 ? "" : new String(buf, 0, n);
            long hwnd = com.sun.jna.Pointer.nativeValue(h.getPointer());
            out.add(new Win(hwnd, cls, ForegroundWatcher.title(hwnd), r,
                    Win32.WinStyle.INSTANCE.GetWindowLongPtrW(h, Win32.GWL_EXSTYLE).longValue(),
                    Win32.User32.INSTANCE.IsWindowVisible(h)));
            return true;
        }, null);
        return out;
    }

    private static String flags(Win w) {
        List<String> f = new ArrayList<>();
        if (w.topmost()) {
            f.add("置顶");
        }
        if (w.noActivate()) {
            f.add("不抢焦点");
        }
        if (w.toolWindow()) {
            f.add("工具窗口");
        }
        if (w.layered()) {
            f.add("分层");
        }
        if (w.transparent()) {
            f.add("点击穿透");
        }
        return String.join(",", f);
    }

    private static String cut(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    /** 控制台只输出 ASCII：Windows 控制台默认 GBK，中文会乱码并掩盖真正的失败信息。 */
    private static String toAscii(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            b.append(c < 128 ? c : '?');
        }
        return b.toString();
    }
}
