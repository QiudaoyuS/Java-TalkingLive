package com.talkinglive.engine;

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
        if (recognizer.accept(data, data.length)) {
            String t = recognizer.result();
            if (!t.isEmpty()) {
                finalized.append(t);
            }
            listener.onText(Kind.FINAL, finalized.toString());
        } else {
            listener.onText(Kind.PARTIAL, finalized + recognizer.partialResult());
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
        recognizer.finalResult();
        recognizer.reset();
        listener.onText(Kind.FINAL, out);
        log.debug("预览段落定稿：{}", com.talkinglive.core.Logging.describeWithFingerprint(out));
        return out;
    }

    @Override
    public synchronized void reset() {
        finalized.setLength(0);
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
