package com.talkinglive.engine;

/**
 * 转写精化（{@code DESIGN.md} §3.4 接口伏笔，TECH-PLAN §5.1 的**唯一替换点**）。
 *
 * <p>精化为什么存在：预览引擎（小 Vosk，CER 17%）不够准，所以段末要用一个更准的引擎
 * 把这**一段音频**重跑一遍，拿到更准且带标点的最终文本。
 *
 * <p>TECH-PLAN §5.3 把 MVP 实现由 {@code WhisperRefiner}（whisper.cpp）换成了
 * SenseVoice——接口本身一行都不用改，这正是当初留这个接口的价值体现。
 *
 * <p><b>失败是可接受的，静默失败不可接受。</b>§7 规定：「whisper 推理失败/超时 →
 * 段落未精化 → 退回用 Vosk 预览文本注入，并记日志」。因此实现里任何失败都返回
 * {@link Result#fallback(String)}，而不是抛异常。
 */
public interface TextRefiner extends AutoCloseable {

    /**
     * 一次精化的结果。
     *
     * @param text        最终文本（已过后处理与剔除唤醒词）
     * @param refined     是否真的精化成功；false 表示调用方拿到的是预览文本兜底
     * @param millis      耗时（毫秒）；未精化时为 0
     * @param engine      实际使用的引擎名
     * @param note        未精化时的原因；成功时为 null
     */
    record Result(String text, boolean refined, long millis, String engine, String note) {

        /** 精化成功。 */
        public static Result ok(String text, long millis, String engine) {
            return new Result(text, true, millis, engine, null);
        }

        /** 精化不可用/失败，退回预览文本兜底（§7）。 */
        public static Result fallback(String previewText, String engine, String note) {
            return new Result(previewText == null ? "" : previewText, false, 0, engine, note);
        }

        /** 实时倍率 = 耗时 / 音频时长；未精化时返回 0。 */
        public double rtf(double audioSeconds) {
            if (!refined || audioSeconds <= 0) {
                return 0;
            }
            return millis / 1000.0 / audioSeconds;
        }
    }

    /**
     * 精化一段音频。
     *
     * @param pcm           16kHz / 16bit / 单声道 PCM
     * @param previewText   预览文本；精化不可用时由调用方兜底使用
     * @param previewEnding 预览是否已以句读结尾（供 ITN 关闭时决定要不要补标点）
     * @return 结果；**实现不应抛异常**，失败请返回 {@link Result#fallback}
     */
    Result refine(byte[] pcm, String previewText, boolean previewEnding);

    boolean available();

    String unavailableReason();

    /** 引擎名，用于日志与设置窗口的「模型状态」页。 */
    String engineName();

    String describe();

    @Override
    void close();
}
