package com.talkinglive.text;

import java.util.List;
import java.util.Map;

/**
 * 文本注入（{@code DESIGN.md} §4.5 接口）。
 *
 * <p><b>按「批」设计（§4.3）</b>：接口收整段字符串，内部决定如何分批调用 {@code SendInput}。
 * 整段注入用一次，未来增量注入用多次，调用方无感。
 *
 * <p>放在 {@code text} 层而不是 {@code system} 层是刻意的：这个接口是纯契约
 * （只出现 String / int），因此状态机与 App 装配层可以在**完全不接触 JNA** 的前提下
 * 引用它，单测里也能塞一个假的注入器把端到端流程跑通。真正的 Win32 实现
 * {@code system.WindowsTextInjector} 才依赖 JNA。
 */
public interface TextInjector {

    /** 自动发送用的按键组合（附录 A：Enter / Ctrl+Enter）。 */
    enum KeyCombo {
        ENTER("Enter"),
        CTRL_ENTER("Ctrl+Enter");

        private final String display;

        KeyCombo(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }

        public static KeyCombo fromConfig(com.talkinglive.core.AppConfig.SendKey k) {
            return k == com.talkinglive.core.AppConfig.SendKey.CTRL_ENTER ? CTRL_ENTER : ENTER;
        }
    }

    /**
     * 注入结果。
     *
     * <p>{@code ok=false} 时 {@code message} **必须**是能直接给用户看的中文说明——
     * §7「任何失败都必须可见」。UIPI 静默丢弃是最需要说清楚的一种：
     * 目标程序以管理员运行时，注入会被系统丢掉而不报错。
     *
     * <p><b>{@code eventsExpected} 存在的理由（PENDING 1.1「出口诚实」）</b>：
     * {@code SendInput} 会如实报告"写了多少个事件"，而此前**没有任何地方核对它是否等于
     * 我们应该写的数量** —— 于是"只写进去一部分"被当成成功：用户丢字，程序报告成功，
     * 诊断页里一条记录都没有；而会话账本以为整段都落地了，下一次退格会按**不存在的字数**退。
     * 现在期望值与实收值一起放在结果里，不匹配即 {@link Failure#PARTIAL_WRITE}。
     *
     * @param eventsSent     实际写入的事件数
     * @param eventsExpected 按文本与退格数**应当**写入的事件数（见 {@link #plannedEvents}）
     */
    record Result(boolean ok, int eventsSent, int eventsExpected, String message, Failure failure) {

        public enum Failure {
            NONE,
            /** SendInput 一个事件都没写进去。 */
            SEND_FAILED,
            /**
             * 只写入了一部分事件：文字**可能缺字**（PENDING 1.1）。
             *
             * <p>与 {@link #SEND_FAILED} 的区别：后者是"一个字都没进去"，
             * 前者是"进去了一部分" —— 后者本来就会被发现，前者此前会被当成成功。
             */
            PARTIAL_WRITE,
            /** 目标程序以管理员运行，UIPI 隔离（§7）。 */
            UIPI_BLOCKED,
            /** 前台窗口在注入前变了。 */
            FOREGROUND_CHANGED,
            /** 注入器不可用（非 Windows 或 Win32 调用失败）。 */
            UNAVAILABLE
        }

        /** 成功。期望值取实收值（用于"核对不适用"的场景，例如模拟按键）。 */
        public static Result ok(int eventsSent) {
            return new Result(true, eventsSent, eventsSent, null, Failure.NONE);
        }

        /** 成功，且带上期望值以便日志核对。 */
        public static Result okExact(int eventsSent, int eventsExpected) {
            return new Result(true, eventsSent, eventsExpected, null, Failure.NONE);
        }

        /** 成功，但要说一句（例如"焦点还原失败，文字注入到了当前焦点"）。 */
        public static Result okWithNote(int eventsSent, int eventsExpected, String note) {
            return new Result(true, eventsSent, eventsExpected, note, Failure.NONE);
        }

        public static Result fail(Failure failure, String message) {
            return new Result(false, 0, 0, message, failure);
        }

        /** 部分写入：**必须当成失败**，不能报告成功。 */
        public static Result partial(int eventsSent, int eventsExpected, String message) {
            return new Result(false, eventsSent, eventsExpected, message, Failure.PARTIAL_WRITE);
        }
    }

    /**
     * 应当写入的键盘事件数 —— 「出口诚实」的核对基准。
     *
     * <p>{@code KEYEVENTF_UNICODE} 下**一个 UTF-16 code unit 要 down + up 两个事件**，
     * 所以文本部分就是 {@code 2 × text.length()}：按 **UTF-16 长度**算，而不是码点 ——
     * 补充平面字符（emoji、生僻字）在 UTF-16 里是两个 code unit，正好对应四组事件。
     * 退格同理，一个退格两个事件。
     *
     * <p>刻意与实现**分开算**：只有独立算出的基准才能用来核对实现有没有少写。
     *
     * @param text       要注入的文本（null 视为空）
     * @param backspaces 退格数（码点为单位；负数按 0 处理）
     */
    static int plannedEvents(String text, int backspaces) {
        int textEvents = (text == null ? 0 : text.length()) * 2;
        return Math.max(0, backspaces) * 2 + textEvents;
    }

    /**
     * 把 {@code text} 打到当前光标处，先按 {@code backspaces} 个**码点**退格。
     *
     * @param backspaces 退格数，**码点**为单位（§4.3：避免代理对导致退格数算错）
     * @param text       要输入的文本
     */
    Result inject(int backspaces, String text);

    /** 模拟一次按键（自动发送）。 */
    Result press(KeyCombo combo);

    /** 注入器是否可用；不可用时 {@link #unavailableReason()} 给出原因。 */
    boolean available();

    String unavailableReason();

    /** 是否检测到目标程序以管理员运行（UIPI 隔离风险）。 */
    default boolean targetElevated(long hwnd) {
        return false;
    }

    /** 分批策略的可读描述，用于设置窗口展示。 */
    default String describe() {
        return getClass().getSimpleName();
    }

    /** 把一串字符切成批：{@code SendInput} 一次能收的事件数有上限。 */
    static List<String> batchByCodePoints(String text, int maxCodePointsPerBatch) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int per = Math.max(1, maxCodePointsPerBatch);
        List<String> out = new java.util.ArrayList<>();
        int start = 0;
        int count = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            count++;
            if (count == per) {
                out.add(text.substring(start, i));
                start = i;
                count = 0;
            }
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    /** 供 UI 显示用的注入统计。 */
    default Map<String, Object> stats() {
        return Map.of();
    }
}
