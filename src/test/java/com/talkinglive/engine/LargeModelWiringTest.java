package com.talkinglive.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.talkinglive.core.AppPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 大模型（准确率的那一半）的接线测试。
 *
 * <p>背景：用户反馈「识别出来的字不对」。根因是小模型的精度上限
 * （CER 17.15% 对 7.43%，见 {@code docs/ENGINE-EXPERIMENT.md}），
 * 而修复必须同时满足两个约束：
 * <ol>
 *   <li><b>模型一旦装好就必须自动生效</b> —— 让用户为了「修好识别」去改环境变量
 *       或 JSON，等于把这件事推回给用户。</li>
 *   <li><b>不能拖慢启动</b> —— 大模型同步加载实测 21.5 秒，而 §6 的冷启动预算是 3 秒。</li>
 * </ol>
 *
 * <p>这两条正是 {@link LazyVoskModel} 与 {@link AppPaths#detectLargeModelDir()} 存在的理由，
 * 所以它们必须有测试兜着。
 *
 * <p><b>不测什么：</b>这里不加载真模型。真模型是 2GB 的外部资产，
 * 单元测试不该依赖它；「能不能加载」由 {@code EngineSmoke} / {@code EngineBench} 在真机上验证。
 * 这里测的是**接线逻辑**：探测、就绪判定、失败降级、不阻塞。
 */
class LargeModelWiringTest {

    private Path tmp;

    @BeforeEach
    void setUp() throws IOException {
        tmp = Files.createTempDirectory("tl-large-model-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmp != null && Files.exists(tmp)) {
            try (var s = Files.walk(tmp)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /** 造一个「看起来完整」的 Vosk 模型目录。 */
    private Path fakeModel(String name) throws IOException {
        Path dir = tmp.resolve(name);
        for (String sub : new String[] {"am", "conf", "graph", "ivector"}) {
            Files.createDirectories(dir.resolve(sub));
        }
        return dir;
    }

    @Nested
    @DisplayName("模型目录探测")
    class Detection {

        @Test
        @DisplayName("结构完整的目录被认作 Vosk 模型")
        void completeDirIsRecognized() throws IOException {
            assertTrue(AppPaths.looksLikeVoskModel(fakeModel("vosk-model-small-cn-0.22")));
        }

        @Test
        @DisplayName("缺少任一必需子目录都不算（解压到一半的目录不能当模型用）")
        void incompleteDirIsRejected() throws IOException {
            Path dir = tmp.resolve("half-extracted");
            Files.createDirectories(dir.resolve("am"));
            Files.createDirectories(dir.resolve("graph"));
            // 故意不建 conf / ivector —— 这正是「下载了但没解压完」的现场
            assertFalse(AppPaths.looksLikeVoskModel(dir),
                    "缺 conf/ivector 时必须拒绝：否则 vosk_model_new 会崩在原生层");
        }

        @Test
        @DisplayName("null 与不存在的目录都安全返回 false")
        void missingDirIsSafe() {
            assertFalse(AppPaths.looksLikeVoskModel(null));
            assertFalse(AppPaths.looksLikeVoskModel(tmp.resolve("does-not-exist")));
        }

        @Test
        @DisplayName("两个可接受的目录名都在探测范围内（兼容官方名与我们的解压名）")
        void bothDirNamesAreAccepted() {
            assertTrue(AppPaths.LARGE_MODEL_DIR_NAMES.contains("vosk-model-cn-0.22"),
                    "必须认官方解压出来的目录名");
            assertTrue(AppPaths.LARGE_MODEL_DIR_NAMES.contains("model-cn"),
                    "必须认我们下载脚本解出来的目录名");
        }
    }

    @Nested
    @DisplayName("未装大模型时的降级")
    class Fallback {

        @Test
        @DisplayName("目录不存在：不启线程、立刻可判定为「不可用」，且 get() 返回 null")
        void missingDirFailsFast() {
            LazyVoskModel lazy = new LazyVoskModel(tmp.resolve("nope"));
            assertTrue(lazy.settled(), "不存在的目录必须立刻判定完成，不能让调用方等 60 秒");
            assertFalse(lazy.ready());
            assertNull(lazy.get(), "拿不到模型时必须返回 null，由调用方按 §7 降级");
            assertNull(lazy.dir());
            assertTrue(lazy.describe().contains("目录不存在"), lazy.describe());
        }

        @Test
        @DisplayName("目录结构不完整：同样立刻失败，不进入加载线程")
        void incompleteDirFailsFast() throws IOException {
            Path dir = tmp.resolve("incomplete");
            Files.createDirectories(dir.resolve("am"));   // 只有一半
            LazyVoskModel lazy = new LazyVoskModel(dir);
            assertTrue(lazy.settled());
            assertFalse(lazy.ready());
            assertNull(lazy.get());
            assertTrue(lazy.describe().contains("不完整"), lazy.describe());
        }

        @Test
        @DisplayName("三种「用不了」的原因要能区分（用户的下一步动作不同）")
        void reasonsAreDistinguishable() {
            // 1) 没装：没给路径
            assertTrue(new LazyVoskModel(null).describe().contains("未安装"));
            // 2) 路径写错：目录不存在
            assertTrue(new LazyVoskModel(tmp.resolve("typo-path")).describe().contains("不存在"));
            // 3) 解压未完成：目录在但结构不全
            try {
                Path half = tmp.resolve("half");
                Files.createDirectories(half.resolve("am"));
                assertTrue(new LazyVoskModel(half).describe().contains("不完整"));
            } catch (IOException e) {
                throw new AssertionError("准备目录失败", e);
            }
        }

        @Test
        @DisplayName("getOr 在未就绪时返回兜底模型（预览路径用）")
        void getOrFallsBack() {
            LazyVoskModel lazy = new LazyVoskModel(null);
            VoskModel small = null;   // 不真的加载模型：这里只验证「null 兜底也是 null」
            assertNull(lazy.getOr(small));
        }

        @Test
        @DisplayName("close 在从未加载过的情况下不抛异常")
        void closeIsSafeWhenNeverLoaded() {
            LazyVoskModel lazy = new LazyVoskModel(tmp.resolve("nope"));
            lazy.close();
            lazy.close();   // 幂等
            assertNull(lazy.get());
        }
    }
}
