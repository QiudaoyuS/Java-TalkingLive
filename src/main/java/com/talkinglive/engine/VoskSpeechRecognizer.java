package com.talkinglive.engine;

import com.talkinglive.core.Logging;
import com.talkinglive.text.TextUtils;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实时预览：小 Vosk **流式**识别（{@code DESIGN.md} §4.2）。
 *
 * <p>只有 Vosk 提供流式输出，能让字边说边长；它的准确率（小模型 CER 17%）不够，
 * 但预览要的是**即时反馈**，准确率由 {@link TextRefiner} 在段末补上。
 *
 * <p>调用方通过 {@link TextListener} 拿到「整段到目前为止的完整文本」，其中
 * {@link Kind#FINAL} 表示引擎在端点处定的稿，{@link Kind#PARTIAL} 表示随时会被改写的中间结果。
 * 谁负责区分样式？{@code text.PreviewText}——接口只说事实，样式与账本在纯逻辑层。
 */
public final class VoskSpeechRecognizer implements SpeechRecognizer {

    private static final Logger log = LoggerFactory.getLogger(VoskSpeechRecognizer.class);

    private final VoskModel model;
    private final TextListener listener;

    /** 已经定稿的部分（来自各次 FINAL 结果，按顺序拼接）。 */
    private final StringBuilder finalized = new StringBuilder();

    /**
     * 本段累计喂进来的音频字节数。
     *
     * <p>用于排查「录了 4 秒却一个字都没预览出来」这种情况：有了它就能区分
     * <b>音频没到识别器</b>（计数为 0）与<b>识别器不吐字</b>（计数正常但输出为空）。
     * 实测遇到过后者——同一段音频离线精化能出 12 个字，流式预览却是空的。
     */
    private long fedBytes;
    /** 本段累计产生过多少次非空输出（FINAL + PARTIAL）。 */
    private int nonEmptyOutputs;
    /** 本段是否命中过端点（FINAL）。 */
    private int finalCount;

    private VoskModel.Recognizer recognizer;
    private volatile boolean closed;

    public VoskSpeechRecognizer(VoskModel model, TextListener listener) throws IOException {
        this.model = model;
        this.listener = listener;
        this.recognizer = model.createRecognizer(16000.0f);
    }

    @Override
    public synchronized void accept(byte[] pcm, int offset, int length) {
        if (closed || recognizer == null || pcm == null || length <= 0) {
            return;
        }
        byte[] data = (offset == 0 && length == pcm.length)
                ? pcm : java.util.Arrays.copyOfRange(pcm, offset, offset + length);
        fedBytes += data.length;
        if (recognizer.accept(data, data.length)) {
            finalCount++;
            String t = recognizer.result();
            if (!t.isEmpty()) {
                nonEmptyOutputs++;
                finalized.append(t);
            }
            listener.onText(Kind.FINAL, finalized.toString());
        } else {
            String partial = recognizer.partialResult();
            if (!partial.isEmpty()) {
                nonEmptyOutputs++;
            }
            listener.onText(Kind.PARTIAL, finalized + partial);
        }
    }

    @Override
    public synchronized String finish() {
        if (closed || recognizer == null) {
            return finalized.toString();
        }
        // getFinalResult() 是一次性的：它把剩余音频吐出来并清空状态。
        // 这里把预览的全程文本记为 out，再 reset 识别器——
        // 避免 Vosk 把已经计入 finalized 的内容再吐一遍造成重复。
        String out = TextUtils.collapseWhitespace(finalized.toString());
        String tail = recognizer.finalResult();
        if (out.isEmpty() && tail != null && !tail.isBlank()) {
            // 兜底：流式期间一个字都没定稿，但收尾时 Vosk 吐出了内容。
            //
            // ★ 这条兜底长期掩盖了一个真 bug：partial 结果被用 "text" 键解析（它实际是
            //   "partial" 键），于是**整段说话期间预览一个字都不出**，只有在端点或这里
            //   才一次性出现全文。当时的注释把它写成"引擎的脾气"，还据此加了兜底 ——
            //   症状被盖住，根因没被找到（见 VoskModel.partialResult 的注释与待处理问题的 P0）。
            //   键名现在已改对；这条兜底**保留**：它守的是另一件事（段末定稿），
            //   去掉它会丢掉"端点没命中但收尾有字"的段落。
            out = TextUtils.collapseWhitespace(tail);
            nonEmptyOutputs++;
        }
        recognizer.reset();
        listener.onText(Kind.FINAL, out);
        log.info("预览段落收尾：喂入 {} 字节（≈{}s），端点命中 {} 次，非空输出 {} 次，{}",
                fedBytes, String.format("%.2f", fedBytes / 32000.0), finalCount, nonEmptyOutputs,
                out.isEmpty() ? "★ 最终文本为空" : Logging.describeWithFingerprint(out));
        if (out.isEmpty() && fedBytes > 32000) {
            // 有音频却一个字都没有：这是流式预览链路的故障，不是「用户没说话」。
            // 明确记 ERROR，避免被当成正常情况忽略。
            log.error("流式预览在 {} 秒音频上未产出任何文本——预览链路异常"
                    + "（同一段音频离线精化通常能出字，可据此对比排查）",
                    String.format("%.2f", fedBytes / 32000.0));
        }
        return out;
    }

    @Override
    public synchronized void reset() {
        finalized.setLength(0);
        fedBytes = 0;
        nonEmptyOutputs = 0;
        finalCount = 0;
        if (closed) {
            return;
        }
        if (recognizer == null) {
            try {
                recognizer = model.createRecognizer(16000.0f);
            } catch (IOException e) {
                log.warn("重建预览识别器失败：{}", e.toString());
            }
        } else {
            recognizer.reset();
        }
    }

    @Override
    public boolean available() {
        return !closed && recognizer != null;
    }

    @Override
    public String unavailableReason() {
        if (closed) {
            return "识别器已关闭";
        }
        return recognizer == null ? "识别器未就绪" : null;
    }

    @Override
    public String describe() {
        return "小 Vosk · 流式预览";
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
    }
}
