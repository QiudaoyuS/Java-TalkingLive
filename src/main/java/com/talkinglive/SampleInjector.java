package com.talkinglive;

import com.talkinglive.core.Logging;
import com.talkinglive.system.WindowsTextInjector;
import com.talkinglive.text.TextInjector;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 注入链路的手工验证入口（{@code DESIGN.md} §9.3 手工清单第 4 条：
 * 「注入目标覆盖：记事本、浏览器输入框、IDE 编辑器、微信/QQ 输入框」）。
 *
 * <p>为什么必须是手工的：注入的成功与否取决于**目标程序怎么接收键盘输入**，
 * 自动化测试做不到「把文字打到微信输入框里然后确认它真的收到了」——
 * 那需要一个真实的人去看。所以这个工具只做一件事：给一个倒计时，
 * 让人把光标放到目标位置，然后把文字真的注入进去。
 *
 * <p>它同时会打印**注入前的环境事实**（前台窗口、是否管理员、注入器是否可用），
 * 因为这些正是失败时最需要的信息：UIPI 隔离与「前台窗口已变」是两条最容易被
 * 误判成「程序坏了」的失败路径。
 *
 * <p><b>调字符间隔</b>：微信/QQ 这类自绘输入框在灌太快时会主动丢掉后面的字符
 * （实测：14 个事件全部写入成功，但输入框里只有 2 个字）。
 * 用第三个参数可以现场试不同的间隔，找到「不再丢字」的最小值：
 * <pre>
 *   java -cp "..." com.talkinglive.SampleInjector "今天天气不错" 5 40
 *                                                              ↑ 倒计时 5 秒，每字间隔 40ms
 * </pre>
 *
 * <p>用法：
 * <pre>
 * java -cp "target\classes;target\lib\*" com.talkinglive.SampleInjector ["文字"] [倒计时秒] [字符间隔ms]
 * </pre>
 * 不带参数时注入一段包含中文、英文、数字与 emoji 的默认样例——
 * 这样能一次覆盖「中文注入」「代理对」「ASCII 混排」三种情况。
 */
public final class SampleInjector {

    /** 默认样例：刻意混入代理对（emoji）与中英数混排。 */
    private static final String DEFAULT_SAMPLE =
            "TalkingLive 注入自检：中英混排 mixed 123，标点。以及一个 emoji 😀 与生僻字 𠮷。";

    private SampleInjector() {}

    public static void main(String[] args) throws Exception {
        String text = args.length > 0 ? args[0] : DEFAULT_SAMPLE;
        int countdown = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        Integer gap = args.length > 2 ? Integer.parseInt(args[2]) : null;

        System.out.println("=== TalkingLive 注入链路手工验证 ===");
        System.out.println("注入内容长度 : " + Logging.describeWithFingerprint(text) + "（内容不在此打印）");
        System.out.println("码点数       : " + text.codePointCount(0, text.length())
                + "（UTF-16 长度 " + text.length() + "，两者不等说明含代理对）");

        WindowsTextInjector injector = new WindowsTextInjector();
        if (gap != null) {
            injector.setCharGapMillis(gap);
            System.out.println("字符间隔     : 已设为 " + injector.charGapMillis()
                    + "ms（用于现场试出目标程序不丢字的最小值）");
        } else {
            System.out.println("字符间隔     : 用默认值 " + injector.charGapMillis() + "ms");
        }
        System.out.println("注入器可用   : " + injector.available()
                + (injector.available() ? "  " + injector.describe() : "  " + injector.unavailableReason()));
        if (!injector.available()) {
            System.out.println("结论：注入器不可用，无法继续。");
            System.exit(2);
        }

        long before = com.talkinglive.system.CaretTracker.foregroundWindow();
        System.out.println("当前前台窗口 : 0x" + Long.toHexString(before)
                + "  '" + com.talkinglive.system.ForegroundWatcher.title(before) + "'");
        System.out.println("目标是否提权 : " + injector.targetElevated(before)
                + "（true 表示 UIPI 会静默丢弃注入，需以管理员运行本程序）");
        System.out.println();
        System.out.println("请把光标放到目标程序的输入框里（记事本 / 浏览器 / IDE / 微信…）");
        for (int i = countdown; i > 0; i--) {
            System.out.print("\r" + i + " 秒后注入… ");
            System.out.flush();
            TimeUnit.SECONDS.sleep(1);
        }
        System.out.println();

        long target = com.talkinglive.system.CaretTracker.foregroundWindow();
        System.out.println("注入时的前台窗口 : 0x" + Long.toHexString(target)
                + "  '" + com.talkinglive.system.ForegroundWatcher.title(target) + "'");

        TextInjector.Result r = injector.inject(0, text, target);
        System.out.println();
        if (!r.ok()) {
            System.out.println("结果：注入失败 [" + r.failure() + "]");
            System.out.println("原因：" + r.message());
            System.out.println();
            System.out.println("常见原因：");
            System.out.println("  · 目标程序以管理员运行 → UIPI 隔离，需以管理员运行本工具");
            System.out.println("  · 前台窗口在倒计时里变了 → 重跑一次并把光标放好");
            System.exit(1);
        }
        System.out.println("注入调用成功：写入 " + r.eventsSent() + " 个事件"
                + "（每字 2 事件，可据此判断是否发全）");
        System.out.println();
        System.out.println("请**肉眼确认**目标程序里出现的文字与预期一致：");
        System.out.println("  ① 中文没有变成问号或方块（编码问题）");
        System.out.println("  ② emoji 与生僻字正常显示（代理对问题）");
        System.out.println("  ③ 没有缺字（缺字说明该程序需要更大的字符间隔）");
        System.out.println("     —— 若缺字，请加大第 3 个参数重试，例如 60 → 100 → 150");
        System.out.println();
        // 故意**不自动按 Enter**：这个工具是用来调间隔的，而被测程序往往是个输入框，
        // 按 Enter 会把内容直接发出去（实测在 cmd 窗口里就是「执行了刚注入的那行命令」）。
        // 需要验证自动发送时请用主程序（受「自动发送」开关控制）。
        System.out.println("（本工具不会自动按 Enter —— 它只用来校验注入的完整性。）");

        System.exit(0);
    }

    static {
        // 让 System.out 用 UTF-8 输出，避免 Windows 控制台 GBK 下中文乱码
        try {
            System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(
                    java.io.FileDescriptor.out), true, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // 忽略：退化成默认编码
        }
    }

    /** 便于将来扩展成交互式（读一行注入一行）。当前未使用，保留接口。 */
    @SuppressWarnings("unused")
    private static String readLine() throws Exception {
        return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
    }
}
