package com.talkinglive.core;

/**
 * 一行状态：名称 / 值 / 是否正常 / 说明。
 *
 * <p><b>为什么单独一个文件</b>：它最早是 {@code ui.SettingsWindow} 的内部 record，
 * 结果被 {@code --doctor} 报告、结构化自检、以及 App 的状态汇总都依赖上了 ——
 * 三处都要 import 一个**界面**类才能拿到一个纯数据 record。现在设置窗口已经精简成
 * 「只放必需项」，状态展示搬到了独立的诊断窗口，这个 record 更不该继续挂在界面上。
 *
 * <p>放在 {@code core}：它是纯数据，不依赖 AWT/JNA，符合 §4.5 对纯逻辑层的要求，
 * 也让 {@code system} / {@code ui} / 诊断入口都能直接用。
 *
 * @param name   行名，例如「麦克风」
 * @param value  简短结论，例如「就绪」
 * @param ok     true = 正常（绿），false = 异常（红）
 * @param detail 可选的补充说明（可能较长，界面上按小字显示）
 */
public record StatusLine(String name, String value, boolean ok, String detail) {

    /** 正常、无补充说明。 */
    public static StatusLine ok(String name, String value) {
        return new StatusLine(name, value, true, null);
    }

    /** 异常，带原因。 */
    public static StatusLine bad(String name, String value, String detail) {
        return new StatusLine(name, value, false, detail);
    }
}
