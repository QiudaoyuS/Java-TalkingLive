package com.talkinglive.system;

import com.sun.jna.Library;
import com.sun.jna.Native;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vosk 原生库的**显式**加载。
 *
 * <p>为什么需要这个类，而不是直接用 {@code org.vosk.LibVosk}：
 * <ol>
 *   <li>{@code org.vosk.LibVosk} 的静态块<strong>只把 DLL 解包到临时目录</strong>
 *       （解 {@code win32-x86-64/empty} 得到目录，再把 {@code libvosk.dll} 等拷进去），
 *       并设置 {@code jna.library.path}。<strong>它并不调用 {@code Native.load}</strong>——
 *       真正的加载是第一次调 {@code vosk_model_new} 时由 JNA 惰性完成的。</li>
 *   <li>而 JNA 在 Windows 上按 {@code <name>.dll} 找库，jar 里的文件名却是
 *       <strong>{@code libvosk.dll}</strong>。于是 {@code Native.load("vosk")} 会直接失败：
 *       <pre>Native library (win32-x86-64/vosk.dll) not found in resource path</pre>
 *       本项目需要用 JNA 调一个 Vosk Java 绑定没暴露的函数
 *       （{@code vosk_model_find_word}，见 {@link VoskNative}），
 *       所以不能等惰性加载，必须自己把库加载起来。</li>
 * </ol>
 *
 * <p>做法：把 jar 内 {@code win32-x86-64/} 下的四个 DLL **一起**解到用户目录下的
 * 一个稳定位置，再用**绝对路径**加载 {@code libvosk.dll}。
 * 之所以要一起解：{@code libvosk.dll} 依赖同目录的 {@code libstdc++-6.dll}、
 * {@code libgcc_s_seh-1.dll}、{@code libwinpthread-1.dll}，
 * 而 Windows 在加载绝对路径的 DLL 时会先搜索该 DLL 自身所在目录，依赖因此能被解析。
 *
 * <p><b>为什么不用临时目录：</b>{@code java.io.tmpdir} 会被清理工具删除，
 * 导致「昨天还能用、今天启动就崩」这种极难查的问题。放到
 * {@code %LOCALAPPDATA%\TalkingLive\native\} 下，位置稳定、用户可自行删除。
 */
public final class VoskNativeLoader {

    private static final Logger log = LoggerFactory.getLogger(VoskNativeLoader.class);

    /** 平台目录名（与 vosk jar 内的布局一致）。 */
    private static final String WIN_DIR = "win32-x86-64";

    /** 需要一起解包的文件。缺一个，libvosk.dll 都会加载失败。 */
    private static final String[] DLLS = {
        "libvosk.dll",
        "libstdc++-6.dll",
        "libgcc_s_seh-1.dll",
        "libwinpthread-1.dll",
    };

    private static final Object LOCK = new Object();
    private static volatile VoskNative instance;
    private static volatile Path nativeDir;
    private static volatile String failure;

    private VoskNativeLoader() {}

    /** 是否已成功加载。 */
    public static boolean available() {
        return instance != null;
    }

    /** 加载失败的原因；成功时为 null。 */
    public static String failureReason() {
        return failure;
    }

    /** 原生库所在目录（成功解包后）。 */
    public static Path nativeDir() {
        return nativeDir;
    }

    /**
     * 确保原生库已加载。
     *
     * @return {@link VoskNative} 实例
     * @throws UnsatisfiedLinkError 解包或加载失败（消息里带上可读原因）
     */
    public static VoskNative ensureLoaded() {
        VoskNative local = instance;
        if (local != null) {
            return local;
        }
        synchronized (LOCK) {
            if (instance != null) {
                return instance;
            }
            try {
                Path dir = extractAll();
                Path lib = dir.resolve("libvosk.dll");
                if (!Files.isRegularFile(lib)) {
                    throw new IOException("解包后找不到 " + lib);
                }
                // 用绝对路径加载：JNA 的 Native.load 支持路径形式（含分隔符即按路径处理）。
                //
                // ★★ 必须显式指定 UTF-8，这是本项目最关键的一处修复 ★★
                //   原因一：Vosk 的 C API 收 const char* + strlen，字符串是 UTF-8。
                //     JNA 默认用**平台编码**（本机中文 Windows 是 GBK），于是
                //     「子曰」「到此为止」被编成 GBK 字节交给按 UTF-8 解释的 Vosk。
                //     实测症状：vosk_model_find_word 对任何词都返回「在词表内」
                //     （附录 C 的静默失效完全拦不住），且受限语法直接崩：
                //       WARNING (VoskAPI:UpdateGrammarFst():recognizer.cc:283)
                //       Expecting array of strings, got: '{"phrase_list":["??","????","[unk]"]}'
                //       java.lang.Error: Invalid memory access
                //         at org.vosk.LibVosk.vosk_recognizer_new_grm(Native Method)
                //   原因二：官方 Java 绑定 org.vosk.LibVosk 就是这样加载的，
                //     所以它必然带这个 bug —— 中西文混排场景下只能绕开它。
                VoskNative loaded = Native.load(lib.toAbsolutePath().toString(), VoskNative.class,
                        VoskNative.utf8Options());
                nativeDir = dir;
                instance = loaded;
                failure = null;
                log.info("Vosk 原生库已加载：{}", lib);
                return loaded;
            } catch (IOException | UnsatisfiedLinkError | RuntimeException e) {
                failure = e.getMessage();
                UnsatisfiedLinkError err = new UnsatisfiedLinkError(
                        "无法加载 Vosk 原生库：" + e.getMessage()
                                + "（Vosk 需要 Windows x64 的 libvosk.dll 及其依赖，随 vosk jar 提供）");
                err.initCause(e);
                throw err;
            }
        }
    }

    /**
     * 把需要的 DLL 从 jar 解到稳定目录。
     *
     * <p><b>已存在就跳过，这是必须的。</b>实测踩过一个会让程序**完全起不来**的坑：
     * 只要有一个进程已经加载了 {@code libvosk.dll}，Windows 就锁住该文件，
     * 于是「先写 .tmp 再 move 覆盖」会失败并抛
     * {@code AccessDeniedException: libvosk.dll.tmp -> libvosk.dll}。
     * 触发条件很常见：重复启动、上一份实例还没退干净、或同时跑诊断工具。
     *
     * <p>所以策略改成「**能用就不动**」：
     * <ol>
     *   <li>目标文件已存在 → 直接用，不碰它（文件被别的进程锁着也没关系，只读即可）。</li>
     *   <li>不存在 → 解包；解包时先写 .tmp 再 move，避免上次异常退出留下半个 DLL。</li>
     *   <li>move 仍失败（极端情况：刚好被别人锁住）→ 回退到「直接用已存在的那份」。</li>
     * </ol>
     */
    private static Path extractAll() throws IOException {
        Path dir = com.talkinglive.core.AppPaths.home().resolve("native").resolve("vosk");
        Files.createDirectories(dir);
        for (String name : DLLS) {
            Path target = dir.resolve(name);
            if (Files.isRegularFile(target) && Files.size(target) > 0) {
                // 已经解过一次就用现成的：不解包、不覆盖，避免与已加载它的进程抢文件
                continue;
            }
            String resource = "/" + WIN_DIR + "/" + name;
            try (InputStream in = VoskNativeLoader.class.getResourceAsStream(resource)) {
                if (in == null) {
                    // 非 Windows 或 jar 布局不同
                    throw new IOException("资源不存在：" + resource + "（当前仅支持 Windows x64）");
                }
                Path tmp = dir.resolve(name + ".tmp");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                // 走到这里说明解包失败。若目标文件其实已经存在（例如别的进程刚解出来），
                // 就接受现状——文件能用比「我们亲手写的」重要。
                if (Files.isRegularFile(target) && Files.size(target) > 0) {
                    log.info("解包 {} 失败（{}），但目标文件已存在，直接使用它", name, e.getMessage());
                    continue;
                }
                throw new IOException("解包 " + name + " 失败：" + e.getMessage(), e);
            }
        }
        return dir;
    }
}
