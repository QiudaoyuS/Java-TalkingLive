package com.talkinglive.engine;

import com.talkinglive.core.AppConfig;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 唤醒词 / 结束词检测：小 Vosk + **受限语法**（{@code DESIGN.md} §4.2）。
 *
 * <p>为什么用受限语法而不是普通识别：语法把搜索空间限制到两三个词，
 * 准确率劣势不影响（§4.2），而误触发率显著下降。这也是「小模型是唯一支持运行时
 * 动态词表」这条结论的落点——大模型词表静态，改不了。
 *
 * <p>语法里两个词的**顺序有讲究**：唤醒词在前。受限语法下引擎会偏向匹配靠前的词，
 * 而用户说「子曰」的频率远高于「到此为止」。
 *
 * <p>词表之外的词是**静默忽略**的（附录 C），所以这里在构造时显式校验并抛错，
 * 由 App 转成用户可见的提示。
 */
public final class VoskKeywordDetector implements WakeWordDetector {

    private static final Logger log = LoggerFactory.getLogger(VoskKeywordDetector.class);

    /** 受限语法必须显式包含这个符号，否则语法外的语音会让引擎行为不可预期。 */
    private static final String UNK = "[unk]";

    /**
     * 每积累这么多秒音频就重置一次识别器。
     *
     * <p>受限语法下没有端点时结果会一直累积，长会议音频会让内存与延迟线性增长。
     * 15 秒重置一次对唤醒词检测没有影响（语音识别在 15 秒窗口内早已出结果）。
     */
    private static final double RESET_AFTER_SECONDS = 15.0;

    private static final double BYTES_PER_SECOND = 16000 * 2;

    private final VoskModel.Recognizer recognizer;
    private final String wakeWord;
    private final String endWord;
    private final HitListener listener;

    private double secondsSinceReset;
    private volatile boolean closed;

    /**
     * 构造。
     *
     * @param model    已加载的模型
     * @param wakeWord 唤醒词
     * @param endWord  结束词
     * @param listener 命中回调（可能在音频线程上被调用）
     * @throws IOException               识别器创建失败
     * @throws VocabularyException       词不在模型词表内（附录 C）
     */
    public VoskKeywordDetector(VoskModel model, String wakeWord, String endWord, HitListener listener)
            throws IOException {
        this.wakeWord = wakeWord == null ? "" : wakeWord.trim();
        this.endWord = endWord == null ? "" : endWord.trim();
        this.listener = listener;

        Map<String, String> unknown = new LinkedHashMap<>();
        if (this.wakeWord.isEmpty()) {
            unknown.put("唤醒词", "（空）");
        } else if (!model.findWord(this.wakeWord)) {
            unknown.put("唤醒词", this.wakeWord);
        }
        if (this.endWord.isEmpty()) {
            unknown.put("结束词", "（空）");
        } else if (!model.findWord(this.endWord)) {
            unknown.put("结束词", this.endWord);
        }
        if (!unknown.isEmpty()) {
            throw new VocabularyException(unknown);
        }

        String grammar = buildGrammar(this.wakeWord, this.endWord);
        if (!model.supportsRuntimeGrammar()) {
            // 受限语法是唤醒词可自定义的前提（附录 B.2：只有小模型支持运行时改词表）。
            // 走到这里说明装的是大模型或词表静态的模型 —— 必须明确拒绝，不能假装能用。
            throw new VocabularyException(Map.of(
                    "模型能力",
                    "该模型不支持运行时词表（缺少 HCLr.fst/Gr.fst），无法用受限语法检测自定义唤醒词。"
                            + "请换用 vosk-model-small-cn-0.22。"));
        }
        log.info("唤醒/结束词受限语法已建立：wake={} end={} grammar={}",
                this.wakeWord, this.endWord, grammar);
        this.recognizer = model.createGrammarRecognizer(16000.0f, grammar);
    }

    /**
     * 构造受限语法。
     *
     * <p><b>格式是纯字符串数组，不是 {@code {"phrase_list":[...]}}！</b>
     * 这一点很容易搞错：Vosk 的 Python 绑定确实用 {@code phrase_list}，但它自己会
     * 把里面的列表取出来再交给 C API；C 侧（{@code kaldi_recognizer.cc}）拿到
     * 字符串后直接 {@code json::JSON::Load} 然后 {@code obj.length() / obj[i]}。
     * 传对象进去的实测症状是：
     * <pre>WARNING (VoskAPI:UpdateGrammarFst():recognizer.cc:283)
     * Expecting array of strings, got: '{"phrase_list":[...]}'
     * java.lang.Error: Invalid memory access</pre>
     *
     * <p>公开为静态方法是为了**可单测**：语法的形状（词的顺序、是否含 {@code [unk]}）
     * 是这个类里唯一能在无麦克风环境下验证的逻辑。
     *
     * <p>词表顺序有讲究：**唤醒词在前**。受限语法下引擎会偏向匹配靠前的词，
     * 而用户说唤醒词的频率远高于结束词。
     */
    public static String buildGrammar(String wakeWord, String endWord) {
        java.util.List<String> phrases = new java.util.ArrayList<>();
        String w = wakeWord == null ? "" : wakeWord.trim();
        String e = endWord == null ? "" : endWord.trim();
        if (!w.isEmpty()) {
            phrases.add(w);
        }
        if (!e.isEmpty() && !e.equals(w)) {
            phrases.add(e);
        }
        phrases.add(UNK);
        return phrases.stream()
                .map(VoskKeywordDetector::quote)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /** JSON 字符串字面量转义。语法里会出现中文（无需转义）但也要挡住引号与反斜杠。 */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    @Override
    public void accept(byte[] pcm, int offset, int length) {
        if (closed || pcm == null || length <= 0) {
            return;
        }
        // 识别器按「数组 + 长度」工作；偏移不为 0 时先切一份。
        // 采集线程每次给的都是独立数组，实际不会走到这里。
        byte[] data = (offset == 0 && length == pcm.length)
                ? pcm : java.util.Arrays.copyOfRange(pcm, offset, offset + length);
        if (recognizer.accept(data, data.length)) {
            Hit hit = match(recognizer.result());
            if (hit != null) {
                listener.onHit(hit);
            }
            secondsSinceReset = 0;
            return;
        }
        Hit hit = match(recognizer.partialResult());
        if (hit != null) {
            listener.onHit(hit);
            // 命中后立刻清空，避免同一句话在随后的若干块里反复命中。
            recognizer.reset();
            secondsSinceReset = 0;
            return;
        }
        secondsSinceReset += data.length / BYTES_PER_SECOND;
        if (secondsSinceReset >= RESET_AFTER_SECONDS) {
            recognizer.reset();
            secondsSinceReset = 0;
        }
    }

    /**
     * 结果文本 → 命中。
     *
     * <p>用包含判断而不是相等：受限语法下引擎可能把一个词切成两段，或者结果里
     * 夹着 {@code [unk]}。先查结束词再查唤醒词——两者的字不重叠，但真出现
     * 同时包含的情况时，结束词在语义上更紧急（避免把已经说完的段落继续录下去）。
     */
    private Hit match(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String t = text.replace(UNK, "").trim();
        if (t.isEmpty()) {
            return null;
        }
        // 先查结束词再查唤醒词——两者的字不重叠，但真出现同时包含的情况时，
        // 结束词在语义上更紧急（避免把已经说完的段落继续录下去）。
        if (!endWord.isEmpty() && t.contains(endWord)) {
            return new Hit(Kind.END, endWord, 0);
        }
        if (!wakeWord.isEmpty() && t.contains(wakeWord)) {
            return new Hit(Kind.WAKE, wakeWord, 0);
        }
        return null;
    }

    @Override
    public void reset() {
        if (!closed) {
            recognizer.reset();
            secondsSinceReset = 0;
        }
    }

    @Override
    public boolean available() {
        return !closed;
    }

    @Override
    public String unavailableReason() {
        return closed ? "检测器已关闭" : null;
    }

    @Override
    public String describe() {
        return "小 Vosk · 受限语法（wake=" + wakeWord + ", end=" + endWord + "）";
    }

    @Override
    public void close() {
        closed = true;
        try {
            recognizer.close();
        } catch (RuntimeException e) {
            log.warn("关闭唤醒词识别器时出错：{}", e.toString());
        }
    }

    /** 词不在模型词表内（附录 C 的静默失效）。消息直接面向用户。 */
    public static final class VocabularyException extends RuntimeException {
        private final Map<String, String> unknown;

        VocabularyException(Map<String, String> unknown) {
            super(buildMessage(unknown));
            this.unknown = Map.copyOf(unknown);
        }

        /** 哪些词不在表内：{"唤醒词" -> "本段结束"}。 */
        public Map<String, String> unknownWords() {
            return unknown;
        }

        private static String buildMessage(Map<String, String> unknown) {
            StringBuilder sb = new StringBuilder(
                    "这些词不在 Vosk 模型词表内，永远不会被识别到（Vosk 对词表外的词静默忽略）：");
            unknown.forEach((k, v) -> sb.append("\n  · ").append(k).append("：").append(v));
            // 按**不合格的字段**分别给建议。早期这里写死了结束词的备选，
            // 结果唤醒词出问题时也会推荐「到此为止」——单测直接把它抓出来了。
            String advice = com.talkinglive.core.WordSuggestions.adviceFor(unknown);
            if (!advice.isEmpty()) {
                sb.append("\n请在设置窗口换成词表内的词：").append(advice).append("。");
            } else {
                sb.append("\n请在设置窗口换成模型词表内的词。");
            }
            return sb.toString();
        }
    }

    /** 便捷：给配置用的默认值构造。 */
    public static VoskKeywordDetector forConfig(VoskModel model, AppConfig config, HitListener listener)
            throws IOException {
        return new VoskKeywordDetector(model, config.wakeWord(), config.endWord(), listener);
    }
}
