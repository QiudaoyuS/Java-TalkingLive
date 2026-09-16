package com.talkinglive.engine;

import com.talkinglive.audio.SilenceDetector;
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
    private static final String UNK = WakePhrase.UNK;

    /**
     * 每积累这么多秒音频就重置一次识别器。
     *
     * <p>受限语法下没有端点时结果会一直累积，长会议音频会让内存与延迟线性增长。
     * 15 秒重置一次对唤醒词检测没有影响（语音识别在 15 秒窗口内早已出结果）。
     */
    private static final double RESET_AFTER_SECONDS = 15.0;

    private static final double BYTES_PER_SECOND = 16000 * 2;

    private final VoskModel.Recognizer recognizer;
    /**
     * 唤醒词解析结果：整词在词表内就是它本身，否则是逐字拆开后的单字序列。
     * 拆字让「用汉字拼出英语发音」成为可能，见 {@link WakePhrase}。
     */
    private final WakePhrase wakePhrase;
    /** 结束词解析结果；**只允许整词**，不拆字（理由见构造函数）。 */
    private final WakePhrase endPhrase;
    private final String wakeWord;
    private final String endWord;
    private final HitListener listener;
    /**
     * 长时语音门：挡住视频/音乐等**外部音频**造成的误唤醒。
     * 判据与实测背景见 {@link WakeGate} 的类注释。
     */
    private final WakeGate gate = new WakeGate();

    private double secondsSinceReset;
    private volatile boolean closed;

    /**
     * 构造。
     *
     * @param model    已加载的模型
     * @param wakeWord 唤醒词；整词不在词表内时允许逐字拆（英语唤醒词靠这个）
     * @param endWord  结束词；**必须整词在词表内**，不拆字
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

        // 唤醒词：先整词，不在表内再逐字拆。拆开是为了支持「用汉字拼出英语发音」——
        // 实测中文模型永远发不出 Firay（词表内拉丁 token 为 0 个），但「飞瑞」
        // 这两个字各自都在表内，只要拆成 ["飞","瑞"] 就能匹配上说出口的 "Firay"。
        this.wakePhrase = this.wakeWord.isEmpty()
                ? null
                : WakePhrase.resolve(model::findWord, this.wakeWord).orElse(null);
        if (this.wakePhrase == null) {
            unknown.put("唤醒词", this.wakeWord.isEmpty()
                    ? "（空）"
                    : this.wakeWord + wakeWordHint(this.wakeWord));
        } else if (this.wakePhrase.spelled()) {
            log.info("唤醒词「{}」整体不在词表内，已按单字拆开：{}（{} 个 token）"
                    + "—— 说这个词时每个字都要说清楚",
                    this.wakePhrase.display(), this.wakePhrase.describeTokens(),
                    this.wakePhrase.tokenCount());
        }

        // 结束词：**故意不拆字**。拆字会放宽匹配（每个字都常见），而结束词命中的
        // 后果是"立刻停止录音"——一个常见字（比如「结」「束」）被随口说出来就会
        // 误停止，代价比"结束词得换个说法"大得多。整词严格匹配。
        //
        // **空结束词是合法的**：表示用户不想用结束词，段落改由静音超时或切换窗口
        // 收尾。这时语法里就只有唤醒词 + [unk]，检测器永远不会报 END 命中 ——
        // 这是一条明确的降级路径，不是漏配。
        if (this.endWord.isEmpty()) {
            this.endPhrase = null;
            log.info("未配置结束词 —— 本段只能由静音超时或切换窗口收尾（不是降级失败，是用户的选择）");
        } else {
            this.endPhrase = model.findWord(this.endWord)
                    ? new WakePhrase(this.endWord, java.util.List.of(this.endWord))
                    : null;
            if (this.endPhrase == null) {
                unknown.put("结束词", this.endWord);
            }
        }

        if (!unknown.isEmpty()) {
            throw new VocabularyException(unknown);
        }

        String grammar = buildGrammar(
                this.wakePhrase.tokens(),
                this.endPhrase == null ? java.util.List.of() : this.endPhrase.tokens());
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
     * 唤醒词用不了时的附加提示。
     *
     * <p>按**为什么用不了**分开给，因为两种情况用户该做的事完全不同：
     * <ul>
     *   <li><b>含拉丁字母</b>：中文模型的词表里拉丁 token 数量为 0（实测），
     *       所以任何英文单词都不可能被识别到。用户输入 {@code Firay} 时最需要
     *       知道的不是"不在词表内"，而是"请写它的**汉字发音**"。</li>
     *   <li><b>实测已知不可用</b>：这些词每个字单独都在表内，逐字拆看似可行，
     *       但真实语音里几乎不可能稳定命中（见 {@code WordSuggestions.KNOWN_ABSENT}）。
     *       不点破的话，用户会以为是自己的问题。</li>
     * </ul>
     */
    private static String wakeWordHint(String word) {
        if (HAS_LATIN.matcher(word).find()) {
            return "（中文模型认不出英语单词，词表里没有任何拉丁词；"
                    + "请写它的**汉字发音**，例如 Firay 写成「飞瑞」——"
                    + "只要每个字都在词表内就能用）";
        }
        if (com.talkinglive.core.WordSuggestions.KNOWN_ABSENT.contains(word)) {
            return "（这个词即使逐字拆开也不可用：拆成单字后要连着说对每个字才算命中，"
                    + "真实语音里几乎不会稳定触发。请换成上面列出的词）";
        }
        return "";
    }

    private static final java.util.regex.Pattern HAS_LATIN =
            java.util.regex.Pattern.compile("[A-Za-z]");

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
        String w = wakeWord == null ? "" : wakeWord.trim();
        String e = endWord == null ? "" : endWord.trim();
        return buildGrammar(
                w.isEmpty() ? java.util.List.of() : java.util.List.of(w),
                e.isEmpty() ? java.util.List.of() : java.util.List.of(e));
    }

    /**
     * 构造受限语法（token 序列版本）。
     *
     * <p>唤醒词可能被拆成多个单字 token（见 {@link WakePhrase}），所以这里收的是
     * **序列**而不是单个词：
     * <pre>
     * buildGrammar(List.of("飞","瑞"), List.of("到此为止"))
     *   → ["飞","瑞","到此为止","[unk]"]
     * </pre>
     *
     * <p>唤醒词的 token 一定排在最前面（受限语法下引擎偏向靠前的词）。
     *
     * @param wakeTokens 唤醒词的 token 序列；可为空
     * @param endTokens  结束词的 token 序列；可为空
     */
    public static String buildGrammar(
            java.util.List<String> wakeTokens, java.util.List<String> endTokens) {
        java.util.List<String> phrases = new java.util.ArrayList<>();
        // 唤醒词的 token 原样按顺序放进去，**组内不去重**：唤醒词被拆成单字时
        // （见 WakePhrase），同一个字重复出现是有意义的 —— 「飞飞飞」必须是
        // ["飞","飞","飞"]，压成一个 "飞" 会让这个唤醒词彻底失效
        // （实测：语法塌成 ["飞","[unk]"] 后说「飞飞飞」不命中）。
        for (String raw : nullToEmpty(wakeTokens)) {
            String tok = trim(raw);
            if (!tok.isEmpty()) {
                phrases.add(tok);
            }
        }
        // 结束词与唤醒词共用词表时只放一次：重复词条会污染语言模型估计。
        // 实测的撞车例子：唤醒词「飞瑞」拆出「瑞」，结束词正好也是「瑞」。
        java.util.Set<String> seen = new java.util.LinkedHashSet<>(phrases);
        for (String raw : nullToEmpty(endTokens)) {
            String tok = trim(raw);
            if (!tok.isEmpty() && seen.add(tok)) {
                phrases.add(tok);
            }
        }
        phrases.add(UNK);
        return phrases.stream()
                .map(VoskKeywordDetector::quote)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static java.util.List<String> nullToEmpty(java.util.List<String> list) {
        return list == null ? java.util.List.of() : list;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
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

        // 先喂"长时语音门"：它需要看到每一帧的能量，包括识别器没出结果的那些。
        // 每秒 16kHz×2 字节 = 32000 字节，据此换算本帧时长。
        gate.accept(SilenceDetector.rms16le(data, 0, data.length), data.length / 32000.0);

        if (recognizer.accept(data, data.length)) {
            Hit hit = match(recognizer.result());
            if (hit != null) {
                fire(hit);
            }
            secondsSinceReset = 0;
            return;
        }
        Hit hit = match(recognizer.partialResult());
        if (hit != null) {
            fire(hit);
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
     * 放行一次命中 —— 但要先过"长时语音门"。
     *
     * <p>门的判据与理由写在 {@link WakeGate} 的类注释里（一句话：说话人不会在连续
     * 说话 3 秒之后才喊唤醒词，而视频/音乐是连续的音频）。这里补充两点实现约束：
     *
     * <ul>
     *   <li><b>只挡唤醒词，不挡结束词。</b>正文可能长达几十秒，结束词本来就该在
     *       长时间说话之后被接受 —— 用它去挡会把正常功能一起挡掉。</li>
     *   <li><b>被挡下也要重置计时。</b>否则一次误命中会让计时继续增长，
     *       把随后真实用户的唤醒也一起挡掉（一个误命中把软件锁死，比误唤醒更糟）。</li>
     * </ul>
     */
    private void fire(Hit hit) {
        if (hit.kind() == Kind.WAKE) {
            boolean allow = gate.allowWake();
            double run = gate.speechRunSeconds();
            gate.resetAfterWake();
            if (!allow) {
                log.info("挡下一次疑似外部音频的唤醒：麦克风已连续有声 {}s（阈值 {}s）"
                        + "—— 判为视频/音乐等外部声音，不开始录音",
                        String.format("%.1f", run), WakeGate.MAX_SPEECH_RUN_SECONDS);
                return;
            }
        }
        listener.onHit(hit);
    }

    /**
     * 结果文本 → 命中。
     *
     * <p>用包含判断而不是相等：受限语法下引擎可能把一个词切成两段，或者结果里
     * 夹着 {@code [unk]}。**单字序列走严格相等**，细节见 {@link WakePhrase#matches}。
     * 先查结束词再查唤醒词——两者的字不重叠，但真出现
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
        // 判定交给 WakePhrase：整词用包含判断（引擎可能把词切成两段，或结果里夹
        // [unk]），单字序列用整体相等判断（拆字后每个字都常见，放宽成包含会明显
        // 抬高误唤醒率）。先查结束词再查唤醒词——两者的字不重叠，但真出现同时
        // 包含的情况时，结束词在语义上更紧急（避免把已经说完的段落继续录下去）。
        if (endPhrase != null && endPhrase.matches(t)) {
            return new Hit(Kind.END, endWord, 0);
        }
        if (wakePhrase != null && wakePhrase.matches(t)) {
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
        StringBuilder sb = new StringBuilder("小 Vosk · 受限语法（wake=");
        sb.append(wakePhrase == null ? wakeWord : wakePhrase);
        sb.append(", end=").append(endPhrase == null ? endWord : endPhrase);
        sb.append("；长时语音门 ").append(WakeGate.MAX_SPEECH_RUN_SECONDS).append("s 挡外部音频）");
        return sb.toString();
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
