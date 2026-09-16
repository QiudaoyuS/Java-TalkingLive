package com.talkinglive.text;

/**
 * 出字策略（{@code DESIGN.md} §3.4 接口伏笔）。
 *
 * <p>MVP 实现是 {@link WholeSegmentPolicy}（整段注入）。将来若要做
 * {@code StablePrefixPolicy}（稳定前缀增量注入），只需在这里加一个实现——
 * {@code TextInjector} 早已按「批」设计（§4.3），调用方无感。
 *
 * <p><b>接口为什么带 {@code alreadyInjected}：</b>增量注入必须知道「光标处已经有哪些字」，
 * 才能算出要退格几个码点。整段注入传空串即可。这个参数现在看似多余，
 * 但它正是让将来那次扩展成为纯增量扩展的关键——§3.4 要求的「先留位置」。
 */
public interface CommitPolicy {

    /**
     * 算出把光标处文本变成 {@code target} 需要做什么。
     *
     * @param alreadyInjected 本段已经注入到光标处的文本（码点为单位处理）
     * @param target          本段的最终文本
     */
    CommitPlan plan(String alreadyInjected, String target);

    /** 一次注入动作：退格 {@code backspaces} 个**码点**，然后输入 {@code text}。 */
    record CommitPlan(int backspaces, String text) {

        public static CommitPlan none() {
            return new CommitPlan(0, "");
        }

        /** 什么都不用做？ */
        public boolean isEmpty() {
            return backspaces == 0 && (text == null || text.isEmpty());
        }

        /** 是否包含退格。 */
        public boolean hasBackspaces() {
            return backspaces > 0;
        }
    }
}
