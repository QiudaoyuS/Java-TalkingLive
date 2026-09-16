package com.talkinglive.engine;

/**
 * 唤醒词 / 结束词检测（{@code DESIGN.md} §4.5 接口）。
 *
 * <p>MVP 实现是 {@link VoskKeywordDetector}：小 Vosk + 受限语法。
 * 它是**唯一支持运行时动态词表**的引擎，而唤醒词必须可自定义（§4.2 附录 B.2）。
 *
 * <p>本接口不依赖引擎原生库，只有实现依赖。这样 App 装配层可以在模型缺失时
 * 换一个明确的「不可用」实现，而不是让程序崩在 {@code UnsatisfiedLinkError} 上。
 */
public interface WakeWordDetector extends AutoCloseable {

    /** 命中了哪个词。 */
    enum Kind {
        /** 唤醒词。 */
        WAKE,
        /** 结束词：只结束**当前这一段**，随后继续聆听（连续模式）或回待唤醒。 */
        END,
        /**
         * 退出词：结束**整个聆听会话**，回到待唤醒（连续模式）。
         *
         * <p>存在的理由：本产品要做到**全程语音操控**——有些用户无法按按钮或使用鼠标，
         * 所以「停止听写」必须有语音通路，不能只放在悬浮球菜单里。
         */
        STOP
    }

    /** 检测结果。 */
    record Hit(Kind kind, String word, int endFrameOffset) {}

    /** 命中回调。实现可能在音频线程上调用它——回调必须快速返回。 */
    @FunctionalInterface
    interface HitListener {
        void onHit(Hit hit);
    }

    /**
     * 喂一段 16kHz / 16bit / 单声道 PCM。
     *
     * @param pcm    PCM 字节（小端）
     * @param offset 起始偏移
     * @param length 长度
     */
    void accept(byte[] pcm, int offset, int length);

    default void accept(byte[] pcm) {
        accept(pcm, 0, pcm == null ? 0 : pcm.length);
    }

    /** 丢弃当前识别状态（检测到唤醒词后调用，避免同一段音频重复命中）。 */
    void reset();

    /** 是否可用（模型已加载且词表校验通过）。 */
    boolean available();

    /** 不可用的原因，直接面向用户；可用时返回 null。 */
    String unavailableReason();

    /** 引擎标识，用于日志与设置窗口展示。 */
    default String engineName() {
        return getClass().getSimpleName();
    }

    String describe();

    @Override
    void close();
}
