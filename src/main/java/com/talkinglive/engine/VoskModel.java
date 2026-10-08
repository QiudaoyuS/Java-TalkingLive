package com.talkinglive.engine;

import com.sun.jna.Pointer;
import com.talkinglive.system.VoskNative;
import com.talkinglive.text.TextUtils;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 小 Vosk 模型的加载、持有、**词表查询**与识别器创建。
 *
 * <p>本类直接走自己声明的 {@link VoskNative}，不用 {@code org.vosk.Model}：
 * 原因（词表查询缺失 + 中文语法被按 GBK 编码）写在 {@code VoskNative} 的类注释里。
 *
 * <p>加载模型是全局昂贵操作（模型 + 声学模型常驻），唤醒检测与实时预览
 * **共用同一个模型句柄**，只各自持有自己的识别器——与「麦克风只开一路」（§4.3）
 * 是同一个思路：稀缺资源只有一份。
 *
 * <p>注意 {@code graph/} 下没有 {@code words.txt}，词表被编译进 {@code Gr.fst} 二进制
 * 里，所以只能用 {@link #findWordId} 查询（附录 D.1 的坑）。
 */
public final class VoskModel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VoskModel.class);

    private final Pointer handle;
    private final Path path;
    private volatile boolean closed;
    /** 防重复释放。 */
    private final java.util.concurrent.atomic.AtomicBoolean freed =
            new java.util.concurrent.atomic.AtomicBoolean();

    private VoskModel(Pointer handle, Path path) {
        this.handle = handle;
        this.path = path;
    }

    /**
     * 加载模型。
     *
     * @throws IOException 目录不存在、不完整，或原生层拒绝加载
     */
    public static VoskModel load(Path dir) throws IOException {
        if (dir == null) {
            throw new IOException("未指定 Vosk 模型目录");
        }
        if (!Files.isDirectory(dir)) {
            throw new IOException("Vosk 模型目录不存在：" + dir);
        }
        // 目录结构完整性检查：这几项缺任何一个，vosk_model_new 都会崩在原生层并给出
        // 一个没有上下文的 native 报错。这里提前把它变成可读的中文提示。
        for (String required : new String[] {"am", "conf", "graph", "ivector"}) {
            if (!Files.isDirectory(dir.resolve(required))) {
                throw new IOException("Vosk 模型不完整：缺少 " + required + "/ 目录（" + dir + "）");
            }
        }
        // Vosk 自己的日志走 stderr（GBK 控制台下还会乱码），只留 WARNING。
        VoskNative vosk = VoskNative.get();
        try {
            vosk.vosk_set_log_level(0);
        } catch (RuntimeException e) {
            log.debug("设置 Vosk 日志级别失败（忽略）：{}", e.toString());
        }

        long t0 = System.nanoTime();
        Pointer h;
        try {
            h = vosk.vosk_model_new(dir.toAbsolutePath().toString());
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            throw new IOException("Vosk 模型加载失败（" + dir + "）：" + e.getMessage(), e);
        }
        if (h == null) {
            throw new IOException("Vosk 模型加载失败（返回空句柄）：" + dir
                    + "；请确认模型完整，获取方式见 docs/DESIGN.md 附录 D。");
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("Vosk 模型已加载：{}（耗时 {}ms）", dir, ms);
        return new VoskModel(h, dir.toAbsolutePath());
    }

    /** 原生模型句柄；识别器由它创建。 */
    public Pointer pointer() {
        return handle;
    }

    public Path path() {
        return path;
    }

    /**
     * 该模型是否支持**运行时动态词表**（即受限语法）。
     *
     * <p>这是 {@code DESIGN.md} 附录 B.2 的关键结论：大模型词表静态、运行时不可修改，
     * 只有小模型能胜任唤醒词检测。C 侧的判据是「模型是否加载了 HCL 与 G 两个 FST」——
     * 只有它们都存在时，{@code KaldiRecognizer(model, rate, grammar)} 才会真正
     * 用语法重建解码图；否则它只打一行
     * {@code WARNING: Runtime graphs are not supported by this model}
     * 然后**忽略语法**继续跑完整词表。那种情况下唤醒词检测会悄悄失效。
     */
    public boolean supportsRuntimeGrammar() {
        return Files.isRegularFile(path.resolve("graph").resolve("HCLr.fst"))
                && Files.isRegularFile(path.resolve("graph").resolve("Gr.fst"));
    }

    /**
     * 查询词在词表内的 id（附录 C.2 第 1 条：**必须显式查询**）。
     *
     * @return 词 id；{@code -1} 表示不在词表内；空词也返回 -1
     * @throws WordLookupUnavailable 词表查询能力不可用（原生库问题）——
     *         **必须抛出**：查不了就意味着「改了配置没反应」这类静默失效拦不住
     */
    public int findWordId(String word) {
        if (word == null || word.isBlank()) {
            return -1;
        }
        ensureOpen();
        try {
            return VoskNative.get().vosk_model_find_word(handle, word);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            throw new WordLookupUnavailable("无法查询 Vosk 词表（vosk_model_find_word 不可用）："
                    + e.getMessage(), e);
        }
    }

    /** 词是否在词表内。 */
    public boolean findWord(String word) {
        return findWordId(word) >= 0;
    }

    /** 创建一个普通流式识别器（实时预览用）。 */
    public Recognizer createRecognizer(float sampleRate) throws IOException {
        return create(sampleRate, null);
    }

    /** 创建一个受限语法识别器（唤醒/结束词检测用）。 */
    public Recognizer createGrammarRecognizer(float sampleRate, String grammar) throws IOException {
        if (grammar == null || grammar.isBlank()) {
            throw new IOException("受限语法不能为空");
        }
        return create(sampleRate, grammar);
    }

    /**
     * 用一个**原始 config JSON** 创建识别器（关键词检测模式）。
     *
     * <p>与 {@link #createGrammarRecognizer} 走同一个 C API（{@code vosk_recognizer_new_grm}），
     * 区别只在第二个参数的内容：
     * <ul>
     *   <li>受限语法：{@code ["子曰","到此为止","[unk]"]} —— 一个纯字符串数组。</li>
     *   <li>关键词：{@code {"config":[{"phrase":"子曰"},...],"keywords_threshold":0.5}}
     *       —— 明确指定每条短语是**关键词**并给出置信度阈值。</li>
     * </ul>
     *
     * <p><b>为什么要用关键词模式</b>：受限语法**不给置信度**（实测：即使
     * {@code setWords(true)}，结果里也只有文本没有分数），于是没法把
     * "听着像但不是" 的误命中过滤掉。实测症状是用户放视频时软件自动开始录音 ——
     * 视频里的语音被语法解码器强行套成唤醒词。关键词模式会给出 confidence，
     * 可以用阈值拦住这种弱匹配。
     *
     * @throws IOException 句柄创建失败或 config 不被接受
     */
    public Recognizer createKeywordRecognizer(float sampleRate, String configJson) throws IOException {
        if (configJson == null || configJson.isBlank()) {
            throw new IOException("关键词配置不能为空");
        }
        return create(sampleRate, configJson);
    }

    private Recognizer create(float sampleRate, String grammar) throws IOException {
        ensureOpen();
        try {
            Pointer h = grammar == null
                    ? VoskNative.get().vosk_recognizer_new(handle, sampleRate)
                    : VoskNative.get().vosk_recognizer_new_grm(handle, sampleRate, grammar);
            if (h == null) {
                throw new IOException("创建 Vosk 识别器失败（返回空句柄）"
                        + (grammar == null ? "" : "；语法=" + grammar));
            }
            return new Recognizer(h, grammar);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            throw new IOException("创建 Vosk 识别器失败："
                    + (grammar == null ? "" : "语法 " + grammar + " —— ") + e.getMessage(), e);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Vosk 模型已关闭");
        }
    }

    @Override
    public void close() {
        closed = true;
        if (freed.compareAndSet(false, true)) {
            try {
                VoskNative.get().vosk_model_free(handle);
                log.info("Vosk 模型已释放：{}", path);
            } catch (RuntimeException | UnsatisfiedLinkError e) {
                log.warn("释放 Vosk 模型时出错：{}", e.toString());
            }
        }
    }

    @Override
    public String toString() {
        return "VoskModel{" + path + "}";
    }

    /** 词表查询能力不可用。 */
    public static final class WordLookupUnavailable extends RuntimeException {
        public WordLookupUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 识别器句柄包装。
     *
     * <p>结果 JSON 的解析统一走 {@link #textOf(String)}，把 Vosk 的中文词间空格去掉
     * （它按词输出并带空格，直接显示会变成「今天 天气 不错」）。
     */
    public static final class Recognizer implements AutoCloseable {

        private final Pointer handle;
        private final String grammar;
        private final java.util.concurrent.atomic.AtomicBoolean freed =
                new java.util.concurrent.atomic.AtomicBoolean();
        private volatile boolean closed;

        Recognizer(Pointer handle, String grammar) {
            this.handle = handle;
            this.grammar = grammar;
        }

        public String grammar() {
            return grammar;
        }

        /**
         * 喂一段 16kHz / 16bit / 单声道 PCM。
         *
         * @return true 表示检测到端点，结果已定稿（此时应读 {@link #result()}）
         */
        public boolean accept(byte[] pcm, int length) {
            if (closed || pcm == null || length <= 0) {
                return false;
            }
            return VoskNative.get().vosk_recognizer_accept_waveform(handle, pcm, length) != 0;
        }

        public boolean accept(byte[] pcm) {
            return accept(pcm, pcm == null ? 0 : pcm.length);
        }

        /** 定稿结果文本（端点触发后调用）。 */
        public String result() {
            return closed ? "" : textOf(VoskNative.get().vosk_recognizer_result(handle));
        }

        /**
         * 中间结果文本（随时可能被改写）。
         *
         * <p><b>它读的是 {@code "partial"} 键，不是 {@code "text"}。</b>原生层实测
         * （直接调 C API 打原始 JSON）：
         * <pre>
         *   vosk_recognizer_partial_result  →  {"partial" : "…"}
         *   vosk_recognizer_result          →  {"text" : "…"}
         *   vosk_recognizer_final_result    →  {"text" : "…"}
         * </pre>
         * 这里**曾经**错误地复用了 {@link #textOf}（只读 {@code "text"}），于是恒返回空串 ——
         * 后果是整段说话期间预览浮窗一个字都不出，只在端点命中或段末收尾时**一次性出现全文**
         * （用户看到的正是"最后几秒才整体出现"）。连带失效的还有两级文字样式、
         * "引擎回头改字"的可观测性，以及 `DESIGN.md` §2.2 第 4 步那个主交互。
         * 详见 {@code docs/DECISIONS.md} 与 {@code 待处理问题.md} 的 P0。
         */
        public String partialResult() {
            return closed ? "" : partialOf(VoskNative.get().vosk_recognizer_partial_result(handle));
        }

        /** 吐出剩余音频并定稿。**注意**：它会同时清空识别状态。 */
        public String finalResult() {
            return closed ? "" : textOf(VoskNative.get().vosk_recognizer_final_result(handle));
        }

        /**
         * 同 {@link #finalResult()}，但返回**原始 JSON**。
         *
         * <p>需要原始 JSON 是因为词级时间戳在 {@code result[]} 数组里，
         * 而 {@code finalResult()} 已经把它压成了纯文本（丢了时间信息）。
         * 精化用它定位唤醒词在音频里的位置。
         */
        public String finalResultJson() {
            return closed ? "" : VoskNative.get().vosk_recognizer_final_result(handle);
        }

        /**
         * 打开「输出词级信息」。
         *
         * <p>打开后结果里会带上 {@code result[]} 数组（每个词的起止时间与置信度）。
         * 对本产品的用处：Vosk 在这个模式下走的是**带词对齐的 MBR / lattice 重打分**路径，
         * 比只取最优路径的普通输出更准，而且能按词拿到置信度——可以用来做
         * 「这段里哪些词不太可靠」的标记。
         *
         * <p>只对**段落级离线重跑**有意义：流式预览每次都要即时出字，打开它只会增加开销。
         */
        public void setWords(boolean enabled) {
            if (!closed) {
                VoskNative.get().vosk_recognizer_set_words(handle, enabled);
            }
        }

        /** 打开「中间结果也带词级信息」。与 {@link #setWords} 配套。 */
        public void setPartialWords(boolean enabled) {
            if (!closed) {
                VoskNative.get().vosk_recognizer_set_partial_words(handle, enabled);
            }
        }

        /** 设置 N-best 候选数（>1 时结果为 {@code alternatives[]}）。 */
        public void setMaxAlternatives(int n) {
            if (!closed && n >= 0) {
                VoskNative.get().vosk_recognizer_set_max_alternatives(handle, n);
            }
        }

        /** 清空识别状态，保留模型。 */
        public void reset() {
            if (!closed) {
                VoskNative.get().vosk_recognizer_reset(handle);
            }
        }

        @Override
        public void close() {
            closed = true;
            if (freed.compareAndSet(false, true)) {
                try {
                    VoskNative.get().vosk_recognizer_free(handle);
                } catch (RuntimeException | UnsatisfiedLinkError e) {
                    log.warn("释放 Vosk 识别器时出错：{}", e.toString());
                }
            }
        }

        /** Vosk 结果 JSON → 去空格的可读文本；解析失败返回空串。 */
        public static String textOf(String json) {
            return fieldOf(json, "text");
        }

        /**
         * 同上，但读 {@code "partial"} 键（**中间结果专用**）。
         *
         * <p>两个键**必须分开解析**：把它们合成一个"两个键都试"的解析器，只会把
         * "调用点用错"重新变成静默行为 —— 那正是这个 bug 藏了几个月的原因
         * （{@code textOf} 与它那条测试本身都没错，错的是 partial 复用了它）。
         */
        public static String partialOf(String json) {
            return fieldOf(json, "partial");
        }

        /** Vosk 结果 JSON → 指定字段 → 去掉词间空格；解析失败返回空串（音频线程不能死）。 */
        private static String fieldOf(String json, String key) {
            if (json == null || json.isBlank()) {
                return "";
            }
            String raw;
            try {
                raw = com.talkinglive.core.JsonCodec.str(
                        com.talkinglive.core.JsonCodec.parseObject(json), key, "");
            } catch (RuntimeException e) {
                // 引擎给出了非预期内容：不改写、不崩溃，只当没有结果。
                // 音频线程不能因为一行 JSON 死掉。
                return "";
            }
            return TextUtils.joinStreamTokens(raw);
        }

        /**
         * 从结果 JSON 里取出**词级时间戳**。
         *
         * <p>需要先 {@link #setWords(boolean) setWords(true)}，否则结果里没有
         * {@code result[]} 数组。
         *
         * <p>用途是解决一个文本层解决不了的问题：**唤醒词被识别成别的字**。
         * 实测用户说「子曰现在进行麦克风测试到此为止」，精化把开头的「子曰」
         * 听成了「在」——文本层无法判断「在」是唤醒词还是正文（删了可能吃掉正文，
         * 不删就多一个字）。但**音频层面能定位**：知道「子曰」这声说话结束在
         * 第几秒，就能把那段音频裁掉，让精化根本看不到它。
         *
         * @return 按出现顺序的词级时间；解析失败返回空列表
         */
        public static List<WordTime> wordsOf(String json) {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            List<WordTime> out = new java.util.ArrayList<>();
            try {
                var root = com.talkinglive.core.JsonCodec.parseObject(json);
                Object arr = root.get("result");
                if (!(arr instanceof List<?> list)) {
                    return List.of();
                }
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> m)) {
                        continue;
                    }
                    Map<String, Object> w = new java.util.LinkedHashMap<>();
                    m.forEach((k, v) -> w.put(String.valueOf(k), v));
                    String word = com.talkinglive.core.JsonCodec.str(w, "word", "");
                    double start = com.talkinglive.core.JsonCodec.num(w, "start", -1);
                    double end = com.talkinglive.core.JsonCodec.num(w, "end", -1);
                    if (!word.isEmpty() && start >= 0 && end >= 0) {
                        out.add(new WordTime(word, start, end));
                    }
                }
            } catch (RuntimeException e) {
                return List.of();
            }
            return out;
        }
    }

    /**
     * 一个词的识别结果与时间（秒，相对该段音频起点）。
     *
     * @param word  识别出的词（可能被分词，例如「子曰」可能拆成两个词）
     * @param start 起始秒
     * @param end   结束秒
     */
    public record WordTime(String word, double start, double end) {}
}
