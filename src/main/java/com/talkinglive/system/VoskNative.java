package com.talkinglive.system;

import com.sun.jna.Library;
import com.sun.jna.Pointer;

/**
 * vosk 原生库中**Java 绑定没有暴露**的那一个函数。
 *
 * <p>为什么必须调它：{@code DESIGN.md} 附录 C 记录的坑——Vosk 对词表外的词是
 * <b>静默忽略</b>的（只在 stderr 打一行 WARNING，不抛异常）。传入「本段结束」时
 * {@code KaldiRecognizer} 构建成功，但那个词永远不会被识别到，用户与程序都毫无察觉。
 *
 * <p>Java 绑定只包了 {@code vosk_model_new/free} 等，没有 {@code vosk_model_find_word}，
 * 所以这里用 JNA 直接声明。库的加载不走 {@code Native.load("vosk")}——
 * 那会因为 jar 里的文件叫 {@code libvosk.dll} 而找不到，详见 {@link VoskNativeLoader}。
 *
 * <p>本类属于 {@code system} 层（允许依赖 JNA），core / text 不得引用它。
 */
public interface VoskNative extends Library {

    /** 惰性加载的实例；失败时抛出带原因的 {@link UnsatisfiedLinkError}。 */
    static VoskNative get() {
        return VoskNativeLoader.ensureLoaded();
    }

    /**
     * 查询词在模型词表内的 id。
     *
     * <p><b>返回值是 {@code int}，不是字符串指针</b>——C 侧签名是
     * {@code int vosk_model_find_word(VoskModel *model, const char *word)}，
     * 内部 {@code FindWord()} 返回词的 id，**不在词表内返回 -1**。
     * （这一点很容易搞错：把它声明成返回指针时，拿到的其实是 id 数值被当成地址，
     * 于是「任何词都像在词表内」，附录 C 的静默失效完全拦不住。）
     *
     * @return 词 id（≥ 0 表示在词表内）；-1 表示不在词表内
     */
    int vosk_model_find_word(Pointer model, String word);
}
