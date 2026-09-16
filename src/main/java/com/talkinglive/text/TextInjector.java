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
     */
    record Result(boolean ok, int eventsSent, String message, Failure failure) {

        public enum Failure {
            NONE,
            /** SendInput 一个事件都没写进去。 */
            SEND_FAILED,
            /** 目标程序以管理员运行，UIPI 隔离（§7）。 */
            UIPI_BLOCKED,
            /** 前台窗口在注入前变了。 */
            FOREGROUND_CHANGED,
            /** 注入器不可用（非 Windows 或 Win32 调用失败）。 */
            UNAVAILABLE
        }

        public static Result ok(int eventsSent) {
            return new Result(true, eventsSent, null, Failure.NONE);
        }

        public static Result fail(Failure failure, String message) {
            return new Result(false, 0, message, failure);
        }
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
