package com.talkinglive.text;

/**
 * 文本后处理链（{@code DESIGN.md} §3.4 接口伏笔）。
 *
 * <p>MVP 实现是 {@link PunctuationProcessor}。将来的 {@code HotwordCorrector}
 * （热词纠错，阶段二）只需实现本接口并挂到同一条链上。
 *
 * <p><b>职责边界（TECH-PLAN §5.3 表第 3 行）：</b>标点的主力是精化引擎的原生标点
 * （{@code use_itn=1}），本接口**不是**标点主力，只负责「数字规整与清理」。
 * 实现里不要试图自己造标点。
 */
public interface TextPostProcessor {

    /**
     * 处理一段文本。
     *
     * @param text 输入（可能为空）
     * @return 处理后的文本；实现必须容忍 null 并返回非 null
     */
    String process(String text);

    /** 链式组合：先 this，再 next。 */
    default TextPostProcessor andThen(TextPostProcessor next) {
        TextPostProcessor self = this;
        return text -> next.process(self.process(text));
    }

    /** 什么都不做。 */
    static TextPostProcessor identity() {
        return text -> text == null ? "" : text;
    }
}
