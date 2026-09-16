package com.talkinglive.engine;

import com.sun.jna.Pointer;
import com.talkinglive.system.VoskNative;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 小 Vosk 模型的加载、持有与**词表查询**。
 *
 * <p>加载模型是全局昂贵操作（42MB 模型 + 声学模型常驻），唤醒检测与实时预览
 * 共用同一个 {@code Model} 实例，只各自持有自己的 {@code Recognizer}——
 * 这与「麦克风只开一路」（§4.3）是同一个思路：稀缺资源只有一份。
 *
 * <p>{@link #findWord} 是附录 C 要求的显式词表校验：<b>Vosk 对词表外的词静默忽略</b>，
 * 不能依赖引擎报错。注意 {@code graph/} 下没有 {@code words.txt}，词表被编译进
 * {@code Gr.fst} 二进制里，所以只能用 {@code vosk_model_find_word()} 查询。
 */
public final class VoskModel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VoskModel.class);

    private final org.vosk.Model model;
    private final Path path;

    private VoskModel(org.vosk.Model model, Path path) {
        this.model = model;
        this.path = path;
    }

    /**
     * 加载模型。
     *
     * @throws IOException 目录不存在或不完整
     */
    public static VoskModel load(Path dir) throws IOException {
        if (dir == null) {
            throw new IOException("未指定 Vosk 模型目录");
        }
        if (!Files.isDirectory(dir)) {
            throw new IOException("Vosk 模型目录不存在：" + dir);
        }
        // 目录结构的完整性检查：这几项缺任何一个，vosk_model_new 都会崩在原生层，
        // 给出一个没有上下文的 native 报错。这里提前把它变成可读的中文提示。
        for (String required : new String[] {"am", "conf", "graph", "ivector"}) {
            if (!Files.isDirectory(dir.resolve(required))) {
                throw new IOException("Vosk 模型不完整：缺少 " + required + "/ 目录（" + dir + "）");
            }
        }
        // Vosk 引擎自己的日志走 stderr，会污染控制台；只留 WARNING。
        org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS);
        long t0 = System.nanoTime();
        org.vosk.Model m = new org.vosk.Model(dir.toAbsolutePath().toString());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("Vosk 模型已加载：{}（耗时 {}ms）", dir, ms);
        return new VoskModel(m, dir.toAbsolutePath());
    }

    /** 底层模型；{@code KaldiRecognizer} 需要它。 */
    public org.vosk.Model raw() {
        return model;
    }

    /** 原生指针，供 {@code vosk_model_find_word} 使用。 */
    public Pointer pointer() {
        return model.getPointer();
    }

    public Path path() {
        return path;
    }

    /**
     * 词是否在模型词表内（附录 C.2 第 1 条：**必须显式查询**）。
     *
     * @return true 表示在词表内
     */
    public boolean findWord(String word) {
        return findWordId(word) >= 0;
    }

    /**
     * 查询词并返回**词表 id**（不在表内为 -1；空词返回 -1）。
     *
     * <p>单独暴露是为了可诊断：本产品对「词表外」的判断完全压在这一个原生调用上，
     * 一旦它的语义与预期不符（附录 C 的那种静默失效就会出现），必须有办法
     * 直接看到原始返回值，而不是只看到一个 boolean。
     *
     * <p>注意 C 侧返回的是 {@code int}。早期把这里写成「返回指针」时表现为
     * **任何词都返回「在词表内」**——因为 id 数值被 JNA 当成了地址。
     */
    public int findWordId(String word) {
        if (word == null || word.isBlank()) {
            return -1;
        }
        try {
            return VoskNative.get().vosk_model_find_word(model.getPointer(), word);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            // 这是**必须可见**的失败：词表查不了，就意味着「改了配置没反应」这类
            // 静默失效无法被拦住。抛出而不是装作查过了。
            throw new WordLookupUnavailable("无法查询 Vosk 词表（vosk_model_find_word 不可用）："
                    + e.getMessage(), e);
        }
    }

    /** 词表查询能力不可用。 */
    public static final class WordLookupUnavailable extends RuntimeException {
        public WordLookupUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Override
    public void close() {
        try {
            model.close();
        } catch (RuntimeException e) {
            log.warn("关闭 Vosk 模型时出错：{}", e.toString());
        }
    }

    @Override
    public String toString() {
        return "VoskModel{" + path + "}";
    }
}
