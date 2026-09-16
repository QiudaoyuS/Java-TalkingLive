package com.talkinglive.system;

import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import java.nio.charset.StandardCharsets;

/**
 * 探测屏幕各关键位置**实际归属哪个窗口**。
 *
 * <p>用途：排查「某个位置点不动」这类问题。{@code WindowFromPoint} 会直接告诉我们在
 * 那个坐标上最上层的窗口是谁——如果有不该在那里的窗口盖住了任务栏/标题栏，
 * 一眼就能看出来。
 *
 * <p>⚠️ <b>必须在 DPI 感知的进程里跑</b>，而且坐标用**物理像素**。
 * 用不感知 DPI 的进程（例如默认的 PowerShell）调用 {@code WindowFromPoint}，
 * Windows 会先做坐标虚拟化，探测结果完全不可信——这个坑在
 * {@code DpiProbe} 里已经踩过一次。
 *
 * <p>用法：{@code java -cp "target\talkinglive.jar;target\lib\*" com.talkinglive.system.WindowProbe}
 */
public final class WindowProbe {

    private WindowProbe() {}

    public static void main(String[] args) {
        DpiScale.initProcessAwareness();
        int dpi = DpiScale.systemDpi();
        java.awt.Rectangle logical = DpiScale.virtualBounds();
        int physW = DpiScale.logicalToPhysical(logical.width, dpi);
        int physH = DpiScale.logicalToPhysical(logical.height, dpi);

        System.out.println("=== 屏幕探测（DPI 感知进程，坐标为物理像素）===");
        System.out.println("逻辑屏幕 = " + logical.width + "x" + logical.height
                + "   DPI=" + dpi + "   物理 = " + physW + "x" + physH);
        System.out.println();

        // 关键位置：把所有可能被"挡"的地方都探一遍
        int[][] points = {
            {physW / 2, 8, 0},                    // 屏幕最顶端（标题栏区域）
            {physW - 60, 30, 0},                  // 右上角：最小化/最大化/关闭按钮
            {physW / 2, physH - 20, 0},           // 任务栏中部
            {40, physH - 20, 0},                  // 任务栏开始按钮
            {physW - 20, physH / 2, 0},           // 右边缘中部（悬浮球默认贴边位置）
            {physW - 200, physH / 2, 0},          // 悬浮球左邻
            {physW / 2, physH / 2, 0},            // 屏幕正中
            {physW / 2, 60, 0},                   // 标题栏
        };
        String[] labels = {
            "屏幕顶端", "右上角按钮", "任务栏中部", "开始按钮",
            "右边缘中部", "悬浮球左邻", "屏幕正中", "标题栏",
        };

        System.out.printf("%-12s %-14s %-8s %-26s %-22s %s%n",
                "位置", "物理坐标", "pid", "窗口类", "标题", "矩形(物理, 推断)");
        System.out.println("-".repeat(120));
        for (int i = 0; i < points.length; i++) {
            probe(labels[i], points[i][0], points[i][1], dpi);
        }

        System.out.println();
        System.out.println("提示：若「任务栏中部 / 开始按钮」上出现了本产品的 pid，说明悬浮球窗口");
        System.out.println("      盖住了任务栏——那正是「点不动菜单」的原因。");
        System.exit(0);
    }

    private static void probe(String label, int x, int y, int dpi) {
        POINT p = new POINT();
        p.x = x;
        p.y = y;
        HWND h = Win32.User32.INSTANCE.WindowFromPoint(p);
        long hwnd = Win32.hwndValue(h);
        if (hwnd == 0) {
            System.out.printf("%-12s (%d,%d) -> 没有窗口（桌面或系统保留区）%n", label, x, y);
            return;
        }
        var pidRef = new com.sun.jna.ptr.IntByReference();
        Win32.User32.INSTANCE.GetWindowThreadProcessId(h, pidRef);
        RECT r = new RECT();
        Win32.User32.INSTANCE.GetWindowRect(h, r);
        String cls = className(h);
        String title = ForegroundWatcher.title(hwnd);
        if (title.length() > 20) {
            title = title.substring(0, 20) + "…";
        }
        System.out.printf("%-12s (%d,%d) pid=%-7d %-26s %-22s %d,%d %dx%d%n",
                label, x, y, pidRef.getValue(), cls, title, r.left, r.top,
                r.right - r.left, r.bottom - r.top);
    }

    private static String className(HWND h) {
        try {
            char[] buf = new char[256];
            // 用 GetClassNameW：Win32.User32 是 UNICODE 映射
            int n = com.sun.jna.platform.win32.User32.INSTANCE
                    .GetClassName(h, buf, buf.length);
            return n <= 0 ? "?" : new String(buf, 0, n);
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
