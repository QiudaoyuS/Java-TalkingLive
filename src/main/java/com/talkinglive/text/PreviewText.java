package com.talkinglive.text;

import com.talkinglive.core.Logging;

/**
 * 浮窗预览的文本模型，实现 {@code DESIGN.md} §4.4 要求的**两级文字样式**。
 *
 * <p>为什么必须分成两级：流式引擎（小 Vosk）会回头改写**已经吐出来的字**。
 * demo README 举的例子是 {@code 这个需球 → 这个需求}——引擎先给了错的，随后自己纠正。
 * 如果 UI 把两者显示成一样的样式，用户会以为文字已经定了；如果直接把文字打进光标，
 * 屏幕上就会看到字被删掉重打。所以：
 *
 * <ul>
 *   <li>{@link #committedText()}——引擎已给出**最终结果**的部分，UI 用实色显示。</li>
 *   <li>{@link #volatileSuffix()}——仍在变动的尾部，UI 用弱化样式显示（§4.4「已稳定 / 仍在变」）。</li>
 * </ul>
 *
 * <p>同时这里也维护「引擎改主意」的计数与最近一次修正，供日志与自检使用——
 * 这是 §8 记录的真实风险（预览与最终文本不一致），需要可观测。
 */
public final class PreviewText {

    /** 引擎改写已稳定部分的记录。 */
    public record Revision(String from, String to, int codePointsBackspaced) {}

    private String committed = "";
    private String volatileSuffix = "";
    private Revision lastRevision;
    private int revisionCount;

    /** 引擎给出的**最终**结果（endpoint 命中 / 段落结束）。 */
    public void commitFinal(String fullText) {
        String next = fullText == null ? "" : fullText;
        int common = TextUtils.commonPrefixCodePoints(committed, next);
        int committedCodePoints = TextUtils.codePointCount(committed);
        if (common < committedCodePoints) {
            // 引擎回头改了已经定稿的部分。正常流式引擎不该这样，但 Vosk 在长段落后
            // 重新对齐时确实会发生，所以不抛异常，记录并接受。
            lastRevision = new Revision(committed, next, committedCodePoints - common);
            revisionCount++;
        }
        committed = next;
        volatileSuffix = "";
    }

    /** 引擎给出的**中间**结果（partial，随时可能被改写）。 */
    public void setPartial(String fullText) {
        String next = fullText == null ? "" : fullText;
        int common = TextUtils.commonPrefixCodePoints(committed, next);
        int committedCodePoints = TextUtils.codePointCount(committed);
        if (common < committedCodePoints) {
            lastRevision = new Revision(committed, next, committedCodePoints - common);
            revisionCount++;
            committed = TextUtils.prefixByCodePoints(next, common);
        }
        volatileSuffix = TextUtils.dropCodePoints(next, common);
    }

    /** 段落结束：把尾部并进稳定部分，避免 UI 在提交瞬间丢掉最后几个字。 */
    public String finish() {
        committed = committed + volatileSuffix;
        volatileSuffix = "";
        return committed;
    }

    public void reset() {
        committed = "";
        volatileSuffix = "";
        lastRevision = null;
    }

    /** 已稳定的文本（UI 实色）。 */
    public String committedText() {
        return committed;
    }

    /** 仍在变动的尾部（UI 弱化样式）。 */
    public String volatileSuffix() {
        return volatileSuffix;
    }

    /** 全部文本。 */
    public String fullText() {
        return committed + volatileSuffix;
    }

    public boolean isEmpty() {
        return fullText().isEmpty();
    }

    /** 至少要显示这么多码点才值得弹浮窗（避免一个字都没识别出来就闪一个空窗）。 */
    public boolean hasContent(int minCodePoints) {
        return TextUtils.codePointCount(fullText()) >= minCodePoints;
    }

    public Revision lastRevision() {
        return lastRevision;
    }

    public int revisionCount() {
        return revisionCount;
    }

    /** 日志用：不记内容。 */
    public String describe() {
        return Logging.describe(fullText(), "stable=" + TextUtils.codePointCount(committed)
                + " revisions=" + revisionCount);
    }

    @Override
    public String toString() {
        return "PreviewText{" + describe() + "}";
    }
}
