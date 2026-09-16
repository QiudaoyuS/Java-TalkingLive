package com.talkinglive.engine;

import com.talkinglive.core.AppPaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * **后台异步加载**的 Vosk 模型，用于让「更准的大模型」可用而不拖慢启动。
 *
 * <p><b>为什么需要它（实测数据）：</b>大模型 {@code vosk-model-cn-0.22} 解压 2.0GB，
 * 同步加载要 <b>21.5 秒</b>（{@code vosk_model_new} 要读 533MB 的 {@code HCLG.fst}
 * 和 1.1GB 的 {@code G.carpa}），而 {@code DESIGN.md} §6 的冷启动预算是 3 秒。
 * 如果按「启动时同步加载」的方式接上去，就等于用一个可用性问题换一个准确性问题。
 *
 * <p><b>做法：</b>构造时**立刻**在守护线程上开始加载，主线程继续启动流程。
 * 第一次真正要用（{@link #get}）时再等它——而用户从「说出唤醒词」到「段落结束需要精化」
 * 之间至少隔几秒，21 秒的加载几乎总能在这段时间里跑完。
 *
 * <p><b>为什么可以等：</b>{@link #get} 只在精化路径上被调用，而精化本身是
 * 「段落已经录完、正在收尾」的阶段，用户此时已经在等结果了；那里多等几百毫秒
 * 远比「一开始就不够准」可接受。反过来，如果加载失败，{@link #get} 返回 null，
 * 调用方按 §7 的降级路径退回预览文本，绝不让应用起不来。
 *
 * <p>本类**不**解析状态机、**不**碰 UI：它只负责「什么时候有模型可用」。
 */
public final class LazyVoskModel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LazyVoskModel.class);

    private final Path dir;
    /** 真正会被加载的目录。{@link #dir} 为空或结构不完整时为 null。 */
    private final Path usableDir;
    private final AtomicReference<VoskModel> model = new AtomicReference<>();
    private final AtomicReference<String> error = new AtomicReference<>();
    private final CountDownLatch done = new CountDownLatch(1);
    private final Thread loader;

    /**
     * @param dir 模型目录；为 null 或结构不完整时本对象视为「不可用」，不启动线程
     */
    public LazyVoskModel(Path dir) {
        this.dir = dir;
        if (!AppPaths.looksLikeVoskModel(dir)) {
            usableDir = null;
            // 错误信息要区分三种「用不了」，因为用户的下一步动作完全不同：
            // 没装 -> 去下载；解压未完成 -> 重新解压；路径写错 -> 改配置。
            if (dir == null) {
                error.set("未配置模型目录");
            } else if (!java.nio.file.Files.exists(dir)) {
                error.set("目录不存在：" + dir);
            } else {
                error.set("目录结构不完整（需含 am/conf/graph/ivector）：" + dir);
            }
            done.countDown();
            this.loader = null;
            return;
        }
        this.usableDir = dir;
        // 守护线程：即使加载还没完，也不阻止 JVM 退出（用户可能立刻关掉应用）
        this.loader = new Thread(this::load, "vosk-model-preload");
        this.loader.setDaemon(true);
        this.loader.start();
        log.info("大模型将在后台加载（约 20 秒，不阻塞启动）：{}", dir);
    }

    private void load() {
        long t0 = System.nanoTime();
        try {
            VoskModel m = VoskModel.load(usableDir);
            model.set(m);
            log.info("大模型后台加载完成：{}（耗时 {}ms）",
                    usableDir, (System.nanoTime() - t0) / 1_000_000);
        } catch (IOException | RuntimeException e) {
            error.set(e.getMessage());
            log.error("大模型后台加载失败，将回退到小模型：{}", e.toString());
        } finally {
            done.countDown();
        }
    }

    /** 加载是否已经结束（成功或失败都算结束）。 */
    public boolean settled() {
        return done.getCount() == 0;
    }

    /** 是否已加载完成、可以直接使用。 */
    public boolean ready() {
        return model.get() != null;
    }

    /** 加载失败的原因；未失败时为 null。 */
    public String error() {
        return error.get();
    }

    /**
     * 会被加载的目录；**不可用时返回 null**。
     *
     * <p>返回 null 而不是「配置了但用不了的那个路径」是刻意的：调用方（App 的状态页）
     * 只需要一个问题「到底有没有大模型可用」，返回路径会让它写成
     * {@code if (dir != null)} —— 于是「下载了但没解压完」会被当成可用。
     */
    public Path dir() {
        return usableDir;
    }

    /**
     * 取模型，必要时等待加载完成。
     *
     * @return 模型；加载失败或目录不可用时返回 null（调用方按 §7 退回小模型或预览文本）
     */
    public VoskModel get() {
        VoskModel m = model.get();
        if (m != null) {
            return m;
        }
        try {
            // 上限 60 秒：比实测 21.5 秒留了充足余量；真超时说明磁盘/内存有问题，
            // 那时返回 null 让用户看到降级提示，比无限期卡住好得多。
            if (!done.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                error.compareAndSet(null, "模型加载超时（>60s）");
                log.error("等待大模型加载超时：{}", dir);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return model.get();
    }

    /**
     * 取模型，未加载好时用 {@code fallback} 顶替。
     *
     * <p>给「现在就要一个能用的模型、但可以接受先用小的」这条路径用（实时预览）。
     */
    public VoskModel getOr(VoskModel fallback) {
        VoskModel m = model.get();
        return m != null ? m : fallback;
    }

    /** 是否达到「值得在状态页里说清楚」的程度。 */
    public String describe() {
        if (usableDir == null) {
            return dir == null ? "未安装（用小模型识别）"
                    : "不可用：" + error.get();
        }
        if (ready()) {
            return "已加载：" + usableDir;
        }
        if (error.get() != null) {
            return "加载失败：" + error.get();
        }
        return "正在后台加载（约 20 秒）：" + usableDir;
    }

    /** 释放已加载的模型。加载线程若仍在跑，它加载完后会自行释放（见 {@link #load}）。 */
    @Override
    public void close() {
        VoskModel m = model.getAndSet(null);
        if (m != null) {
            m.close();
        }
    }

    // ------------------------------------------------------------ 静态工厂

    /**
     * 探测并创建一个「自动使用已安装大模型」的惰性加载器。
     *
     * @param override 显式配置的目录（系统属性 / 环境变量 / 命令行）；为 null 时自动探测
     * @return 惰性加载器；**任何情况下都不返回 null**，目录不可用时它是一个已失败的对象
     */
    public static LazyVoskModel detect(Path override) {
        Path dir = override != null ? override : AppPaths.detectLargeModelDir();
        if (dir == null) {
            log.info("未发现大模型 —— 识别将使用小模型（CER 17.15%）。"
                    + "想要更准请把 vosk-model-cn-0.22 解压到 {}",
                    AppPaths.modelsDir());
        }
        return new LazyVoskModel(dir);
    }
}
