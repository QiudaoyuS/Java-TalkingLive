package com.talkinglive.engine;

/**
 * 实时预览识别（{@code DESIGN.md} §4.5 接口）。
 *
 * <p>MVP 实现是 {@link VoskSpeechRecognizer}：小 Vosk 流式识别，让字边说边长。
 * 预览必然糙（小模型 CER 17%），它存在的意义是**即时反馈**；
 * 准确率由 {@link TextRefiner} 在段落结束时补上（§4.2 双引擎分工）。
 */
public interface SpeechRecognizer extends AutoCloseable {

    /** 识别结果类型。 */
    enum Kind {
        /** 中间结果：随时可能被引擎改写。 */
        PARTIAL,
        /** 最终结果：引擎在端点处定的稿。 */
        FINAL
    }

    /**
     * 结果回调。
     *
     * <p>{@code fullText} 是**整段到目前为止**的完整文本（不是增量），
     * 因为流式引擎自己就在改写已经吐出的部分，拼接增量没有意义。
     * 谁负责区分「已稳定 / 仍在变」？由 {@code text.PreviewText} 负责——
     * 这是刻意的分工：接口只说事实，样式与账本在纯逻辑层。
     */
    @FunctionalInterface
    interface TextListener {
        void onText(Kind kind, String fullText);
    }

    /** 喂一段 16kHz / 16bit / 单声道 PCM。 */
    void accept(byte[] pcm, int offset, int length);

    default void accept(byte[] pcm) {
        accept(pcm, 0, pcm == null ? 0 : pcm.length);
    }

    /** 段落结束：让引擎把尾部吐完。返回最终文本（可能为空）。 */
    String finish();

    /** 开始新的一段。 */
    void reset();

    boolean available();

    String unavailableReason();

    default String engineName() {
        return getClass().getSimpleName();
    }

    String describe();

    @Override
    void close();
}
