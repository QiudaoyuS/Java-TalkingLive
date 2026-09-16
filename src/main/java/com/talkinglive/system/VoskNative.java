package com.talkinglive.system;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import java.util.Map;

/**
 * Vosk C API 的 JNA 绑定 —— **本项目自己声明，不用 {@code org.vosk.LibVosk}**。
 *
 * <p>为什么不直接用官方的 Java 绑定，两个都是实测踩出来的原因：
 *
 * <ol>
 *   <li><b>缺少 {@code vosk_model_find_word}。</b>官方绑定只包了
 *       {@code vosk_model_new/free} 等，没有词表查询。而 {@code DESIGN.md} 附录 C
 *       要求「配置校验必须显式调用 {@code vosk_model_find_word}」——
 *       Vosk 对词表外的词是**静默忽略**的，不查就拦不住。</li>
 *   <li><b>字符串编码是错的（这个更致命）。</b>官方绑定用 JNA 默认的
 *       {@code Native.load(...)} 加载，字符串按**平台编码**编码。中文 Windows 上是 GBK，
 *       于是中文语法被编成 GBK 字节交给按 UTF-8 解释的 Vosk，实测症状是：
 *       <pre>WARNING (VoskAPI:UpdateGrammarFst():recognizer.cc:283)
 * Expecting array of strings, got: '{"phrase_list":["??","????","[unk]"]}'
 * Exception in thread "main" java.lang.Error: Invalid memory access
 *   at org.vosk.LibVosk.vosk_recognizer_new_grm(Native Method)</pre>
 *       唤醒词恰好是中文，所以这条路必然走不通。解决办法是加载时显式指定
 *       {@code OPTION_STRING_ENCODING=UTF-8}（见 {@link VoskNativeLoader}），
 *       并用自己声明的接口调用。</li>
 * </ol>
 *
 * <p>另外 {@code vosk_model_find_word} 的返回值是 <b>{@code int}</b>（词 id，
 * 不在词表内返回 -1），不是字符串指针——写成指针会导致「任何词都像在词表内」。
 *
 * <p>本类属于 {@code system} 层（允许依赖 JNA 与原生库）；{@code core} / {@code text} 不得引用它。
 */
public interface VoskNative extends Library {

    /** 惰性加载的实例；失败时抛出带可读原因的 {@link UnsatisfiedLinkError}。 */
    static VoskNative get() {
        return VoskNativeLoader.ensureLoaded();
    }

    // ------------------------------------------------------------ v1 模型 API

    /**
     * 加载模型。
     *
     * @param modelPath 模型目录路径
     * @return 模型句柄；失败时返回 null（**注意**：某些构建会直接崩在原生层）
     */
    Pointer vosk_model_new(String modelPath);

    void vosk_model_free(Pointer model);

    /**
     * 查询词在模型词表内的 id。
     *
     * @return 词 id（≥ 0 表示在词表内）；<b>-1 表示不在词表内</b>
     */
    int vosk_model_find_word(Pointer model, String word);

    // ------------------------------------------------------------ 识别器

    /** 普通流式识别器（用于实时预览）。 */
    Pointer vosk_recognizer_new(Pointer model, float sampleRate);

    /**
     * 受限语法识别器（用于唤醒词 / 结束词检测）。
     *
     * @param grammar JSON，形如 {@code {"phrase_list":["子曰","到此为止","[unk]"]}}
     */
    Pointer vosk_recognizer_new_grm(Pointer model, float sampleRate, String grammar);

    /**
     * 喂音频。
     *
     * @param data   PCM 字节（16bit 小端单声道）
     * @param length 字节长度
     * @return 非 0 表示检测到端点，结果已定稿，应调用 {@link #vosk_recognizer_result}
     */
    int vosk_recognizer_accept_waveform(Pointer recognizer, byte[] data, int length);

    /** 定稿结果（端点触发后调用）。 */
    String vosk_recognizer_result(Pointer recognizer);

    /** 中间结果（随时可能被改写）。 */
    String vosk_recognizer_partial_result(Pointer recognizer);

    /** 吐出剩余音频并定稿（段落结束时调用）。 */
    String vosk_recognizer_final_result(Pointer recognizer);

    /** 清空识别状态（保留模型）。 */
    void vosk_recognizer_reset(Pointer recognizer);

    void vosk_recognizer_free(Pointer recognizer);

    /** 设置日志级别（0 = 关闭，-1 = 只错误，…）。 */
    void vosk_set_log_level(int level);

    /**
     * 加载选项：显式指定 UTF-8。
     *
     * <p>{@code VoskNativeLoader} 用它；写成方法是为了让「为什么必须 UTF-8」
     * 这条结论和代码待在一起。
     */
    static Map<String, Object> utf8Options() {
        return Map.of(Library.OPTION_STRING_ENCODING, "UTF-8");
    }
}
