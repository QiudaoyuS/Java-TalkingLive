package com.talkinglive.engine;

import com.talkinglive.core.Logging;
import com.talkinglive.text.TextUtils;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TextRefiner} 的实现集合。
 *
 * <p><b>关于精化引擎的现状（如实记录，见 {@code docs/ENGINE-EXPERIMENT.md}）：</b>
 * {@code TECH-PLAN} §1.1 定的方案 C 是把精化引擎换成 SenseVoice（sherpa-onnx）。
 * 但 sherpa-onnx **没有发布到 Maven Central 的 Java 绑定**（附录 A.5 最后一项列的
 * 「❌ 未确认，需在 M3 前确认」现已确认为「不可直接依赖」）。因此精化侧采用
 * 「可插拔 + 明确降级」的策略：
 *
 * <ul>
 *   <li>{@link Unavailable}——引擎不可用时的**显式**占位。它不假装成功，
 *       而是把原因说清楚，并让调用方回退到预览文本（§7 规定的降级路径）。</li>
 *   <li>{@link VoskOffline}——用 Vosk 对**整段音频**做一次离线重跑。
 *       它不是「更准的引擎」，但确实是**同一段音频的第二次、不同路径的识别**，
 *       因此能纠正一部分流式预览的错误，且零新增原生依赖。</li>
 *   <li>SenseVoice——待落地。接入时只需新增一个 {@link TextRefiner} 实现并让
 *       {@code App.createRefiner} 指向它；{@code TextRefiner} 接口、状态机、UI、
 *       注入路径**一行都不用改**（TECH-PLAN §5.1「替换点只有一处」）。</li>
 * </ul>
 */
public final class TextRefiners {

    private TextRefiners() {}

    // ------------------------------------------------------------ 不可用

    /**
     * 精化引擎不可用的显式占位。
     *
     * <p>{@code DESIGN.md} §7：「whisper 推理失败/超时 → 段落未精化 → 退回用 Vosk
     * 预览文本注入，并记日志」。这就是那条降级路径的载体。
     */
    public static final class Unavailable implements TextRefiner {

        private final String engine;
        private final String reason;

        public Unavailable(String engine, String reason) {
            this.engine = engine;
            this.reason = reason;
        }

        @Override
        public Result refine(byte[] pcm, String previewText, boolean previewEnding) {
            return Result.fallback(previewText, engine, reason);
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String unavailableReason() {
            return reason;
        }

        @Override
        public String engineName() {
            return engine;
        }

        @Override
        public String describe() {
            return engine + "（不可用：" + reason + "）";
        }

        @Override
        public void close() {
            // 无资源
        }
    }

    // ------------------------------------------------------------ Vosk 离线重跑

    /**
     * 用 Vosk 对整段音频做一次**离线**识别，并启用词级信息与 lattice 重打分。
     *
     * <p>它与流式预览的差别是**实打实的两处**，不是简单重跑：
     *
     * <ol>
     *   <li><b>看得到完整上下文。</b>流式预览必须在每块音频到达时立刻出字，
     *       只能对「已听到的部分」做局部最优；离线重跑一次喂完整段，
     *       解码器能用上整段的前后文，句尾的字不会再被草率定论。</li>
     *   <li><b>打开词级信息后走的是词对齐 + lattice 重打分路径</b>
     *       （{@code vosk_recognizer_set_words(true)}）。Vosk 在 {@code max_alternatives=0}
     *       时会用 Minimum Bayes Risk 对整条 lattice 重打分，而不是只取单条最优路径——
     *       这一步的纠错能力明显强于流式输出，也正是「精化」该有的收益。</li>
     * </ol>
     *
     * <p><b>为什么必须开词级信息：</b>不开的话，这一步退化成「同一个引擎、同一段音频、
     * 大致同一条路径再跑一遍」，几乎纠不了错——那就等于精化白做了。
     *
     * <p>产出**不带标点**（Vosk 中文没有标点恢复模型，见 DESIGN.md 附录 B.2），
     * 因此仍依赖 {@code PunctuationProcessor} 补句末标点。
     */
    public static final class VoskOffline implements TextRefiner {

        private static final Logger log = LoggerFactory.getLogger(VoskOffline.class);

        private final VoskModel model;
        private final String engine;
        private volatile boolean closed;
        /** 词级信息（lattice 重打分）开关；默认开，因为它才是精化收益的来源。 */
        private final boolean wordLevel;

        public VoskOffline(VoskModel model) {
            this(model, "Vosk 离线重跑", true);
        }

        public VoskOffline(VoskModel model, String engine) {
            this(model, engine, true);
        }

        public VoskOffline(VoskModel model, String engine, boolean wordLevel) {
            this.model = model;
            this.engine = engine;
            this.wordLevel = wordLevel;
        }

        @Override
        public Result refine(byte[] pcm, String previewText, boolean previewEnding) {
            if (closed || model == null) {
                return Result.fallback(previewText, engine, "精化器已关闭");
            }
            double seconds = pcm == null ? 0 : pcm.length / 32000.0;
            if (pcm == null || pcm.length < 32000 / 5) {
                // 音频太短（< 0.2s），重跑没有意义
                return Result.fallback(previewText, engine, "音频过短，跳过精化");
            }
            long t0 = System.nanoTime();
            try (VoskModel.Recognizer rec = model.createRecognizer(16000.0f)) {
                // ★ 打开词级信息：这是这一步真正能纠错的原因（见类注释）。
                //   关掉它时 Vosk 只取单条最优路径，与流式预览几乎同质。
                rec.setWords(wordLevel);
                rec.accept(pcm, pcm.length);
                String text = TextUtils.collapseWhitespace(rec.finalResult());
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (text.isEmpty()) {
                    // 离线重跑没出字：这不是「精化成功但结果为空」，而是失败。
                    // 若当成成功，用户会看到预览有字但一个都没注入。
                    log.info("离线重跑未产出文本，退回预览文本；音频={}s 耗时={}ms",
                            String.format("%.2f", seconds), ms);
                    return Result.fallback(previewText, engine, "离线重跑没有产出文本（耗时 " + ms + "ms）");
                }
                // 对比预览与精化结果，记录**实际纠正了多少**。
                // 这是「精化到底有没有用」的可观测判据（TECH-PLAN §6.3 的核心验证项）。
                int common = TextUtils.commonPrefixCodePoints(text, previewText == null ? "" : previewText);
                int changed = Math.max(
                        TextUtils.codePointCount(text) - common,
                        TextUtils.codePointCount(previewText) - common);
                log.info("精化完成：音频={}s 耗时={}ms RTF={} 词级={} 与预览差异={}码点 结果={}",
                        String.format("%.2f", seconds), ms,
                        String.format("%.3f", ms / 1000.0 / Math.max(seconds, 0.001)),
                        wordLevel, changed, Logging.describeWithFingerprint(text));
                return Result.ok(text, ms, engine);
            } catch (IOException | RuntimeException e) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                log.warn("精化失败（耗时 {}ms）：{}", ms, e.toString());
                return Result.fallback(previewText, engine, "精化失败：" + e.getMessage());
            }
        }

        @Override
        public boolean available() {
            return !closed && model != null;
        }

        @Override
        public String unavailableReason() {
            return closed ? "精化器已关闭" : null;
        }

        @Override
        public String engineName() {
            return engine;
        }

        @Override
        public String describe() {
            return engine + "（Vosk 整段重跑 + 词级重打分"
                    + (wordLevel ? "" : "（词级已关闭）") + "，非 SenseVoice）";
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
