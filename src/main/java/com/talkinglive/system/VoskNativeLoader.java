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
                // ★ 必须显式指定 UTF-8：Vosk 的 C API 收 const char* + strlen，字符串是 UTF-8。
                //   JNA 默认用平台编码（本机是 GBK），会把「子曰」编成 GBK 字节交给 Vosk，
                //   而 Vosk 按 UTF-8 解释——查出来的结果毫无意义（实测表现为
                //   **任何词都返回「在词表内」**，于是附录 C 的静默失效完全拦不住）。
                java.util.Map<String, Object> opts = new java.util.HashMap<>();
                opts.put(Library.OPTION_STRING_ENCODING, "UTF-8");
                VoskNative loaded = Native.load(lib.toAbsolutePath().toString(), VoskNative.class, opts);
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

    /** 把需要的 DLL 从 jar 解到稳定目录；已存在且大小一致则跳过。 */
    private static Path extractAll() throws IOException {
        Path dir = com.talkinglive.core.AppPaths.home().resolve("native").resolve("vosk");
        Files.createDirectories(dir);
        ClassLoader cl = VoskNativeLoader.class.getClassLoader();
        for (String name : DLLS) {
            String resource = "/" + WIN_DIR + "/" + name;
            Path target = dir.resolve(name);
            try (InputStream in = VoskNativeLoader.class.getResourceAsStream(resource)) {
                if (in == null) {
                    // 非 Windows 或 jar 布局不同：跳过（Linux/macOS 不支持本产品，这里只报错）
                    throw new IOException("资源不存在：" + resource + "（当前仅支持 Windows x64）");
                }
                // 写临时文件再原子替换，避免上一次异常退出留下半个 DLL
                Path tmp = dir.resolve(name + ".tmp");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new IOException("解包 " + name + " 失败：" + e.getMessage(), e);
            }
        }
        return dir;
    }
}
