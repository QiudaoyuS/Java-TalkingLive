package com.talkinglive.text;

/**
 * 整段注入策略：不做任何增量，直接把最终文本整段打出去。
 *
 * <p>选它的理由是 {@code DESIGN.md} §5 写明的：浮窗预览 + 整段注入可以
 * 「避免文字闪烁、撤销栈污染与中间态副作用；支持 Esc 取消」。
 *
 * <p>但「整段」不等于「无脑打」。这里做一件必要的事：把光标处**本段已经注入过**的文本
 * 先退格删掉，再打新的。原因：
 * <ul>
 *   <li>段落可能被重复提交（例如静音超时与结束词几乎同时到达、或用户手动点了一次）；
 *       此时不退格就会打两遍。</li>
 *   <li>将来切到稳定前缀增量注入时，这条路径是同一份代码。</li>
 * </ul>
 * 退格数按**码点**算（§4.3），不是 UTF-16 长度。
 */
public final class WholeSegmentPolicy implements CommitPolicy {

    @Override
    public CommitPlan plan(String alreadyInjected, String target) {
        String already = alreadyInjected == null ? "" : alreadyInjected;
        String next = target == null ? "" : target;
        if (already.equals(next)) {
            // 幂等：内容完全一致，不做任何动作，避免多余退格。
            return CommitPlan.none();
        }
        int backspaces = TextUtils.codePointCount(already);
        return new CommitPlan(backspaces, next);
    }
}
