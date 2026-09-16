package com.talkinglive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * 「装好大模型即自动生效」这条接线的测试。
 *
 * <p>背景：用户反馈「识别出来的字不对」与「说 AI 识别不出」，根因都是小模型 ——
 * 精度 CER 17.15%（对大模型 7.43%），且它的**词表里没有任何英文**
 * （实测 A–Z、AI、APP、CPU 全部不在表内）。而修复的前提是：
 * <b>用户把模型解压到正确位置之后，程序必须自己认出来</b> ——
 * 让他为了「修好识别」去改环境变量或 JSON，等于把这件事推回给用户。
 *
 * <p>所以这里测的是**探测逻辑**，不是「能不能加载真模型」：
 * 真模型是 2GB 的外部资产，单元测试不该依赖它；能否加载由
 * {@code EngineSmoke} / {@code EngineBench} 在真机上验证。
 *
 * <p>用 {@code -Dtalkinglive.home=<临时目录>} 重定向根目录来构造各种安装形态
 * （没装 / 装了但解压未完成 / 装了两个可接受的名字之一）。
 */
class LargeModelDetectionTest {

    private String savedHome;
    private Path tmp;

    @BeforeEach
    void setUp() throws IOException {
        tmp = Files.createTempDirectory("tl-model-detect");
        savedHome = System.getProperty("talkinglive.home");
        System.setProperty("talkinglive.home", tmp.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (savedHome == null) {
            System.clearProperty("talkinglive.home");
        } else {
            System.setProperty("talkinglive.home", savedHome);
        }
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
        Path dir = AppPaths.modelsDir().resolve(name);
        for (String sub : new String[] {"am", "conf", "graph", "ivector"}) {
            Files.createDirectories(dir.resolve(sub));
        }
        return dir;
    }

    @Nested
    @DisplayName("目录结构判定")
    class Structure {

        @Test
        @DisplayName("结构完整的目录被认作 Vosk 模型")
        void completeDirIsRecognized() throws IOException {
            assertTrue(AppPaths.looksLikeVoskModel(fakeModel("vosk-model-small-cn-0.22")));
        }

        @Test
        @DisplayName("缺少任一必需子目录都不算（解压到一半的目录不能当模型用）")
        void incompleteDirIsRejected() throws IOException {
            Path dir = AppPaths.modelsDir().resolve("half-extracted");
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
    }

    @Nested
    @DisplayName("自动探测已安装的大模型")
    class Detection {

        @Test
        @DisplayName("两个可接受的目录名都认（官方解压名 + 我们下载脚本的解压名）")
        void bothNamesAreAccepted() throws IOException {
            assertTrue(AppPaths.LARGE_MODEL_DIR_NAMES.contains("vosk-model-cn-0.22"));
            assertTrue(AppPaths.LARGE_MODEL_DIR_NAMES.contains("model-cn"));
            assertEquals(null, AppPaths.detectLargeModelDir(), "还没装时应当探测不到");
            Path a = fakeModel("model-cn");
            assertEquals(a, AppPaths.detectLargeModelDir(), "我们的下载脚本解出的是 model-cn/");
        }

        @Test
        @DisplayName("另一个可接受的名字（官方解压名）同样被认出来")
        void officialNameIsAccepted() throws IOException {
            Path b = fakeModel("vosk-model-cn-0.22");
            assertEquals(b, AppPaths.detectLargeModelDir(), "官方文档解出的是 vosk-model-cn-0.22/");
        }

        @Test
        @DisplayName("解压未完成时探测不到 —— 不能拿半成品去加载")
        void halfExtractedIsNotDetected() throws IOException {
            Path dir = AppPaths.modelsDir().resolve("model-cn");
            Files.createDirectories(dir.resolve("am"));
            assertNull(AppPaths.detectLargeModelDir());
        }

        @Test
        @DisplayName("小模型存在不会让探测误判成大模型")
        void smallModelIsNotLarge() throws IOException {
            fakeModel("vosk-model-small-cn-0.22");
            assertNull(AppPaths.detectLargeModelDir(),
                    "小模型名字里也有 cn-0.22，但目录名不同，不该被当成大模型");
        }
    }

    @Nested
    @DisplayName("识别模型的选择（预览与落字共用这一个）")
    class ModelChoice {

        @Test
        @DisplayName("没装大模型时，识别模型就是小模型（不能返回不存在的路径）")
        void fallsBackToSmall() throws IOException {
            Path small = fakeModel("vosk-model-small-cn-0.22");
            assertEquals(small, AppPaths.asrModelDir());
        }

        @Test
        @DisplayName("装了大模型时，识别模型自动切到大模型 —— 用户不需要改任何配置")
        void autoSwitchesToLarge() throws IOException {
            fakeModel("vosk-model-small-cn-0.22");
            Path large = fakeModel("model-cn");
            assertEquals(large, AppPaths.asrModelDir(), """
                    「装好即自动生效」是这条接线的全部意义。
                    若这里失败，说明用户把 2GB 的模型解压到位了、程序却还在用小模型 ——
                    那正是他抱怨「识别不准、漏英文」的根因没被修掉。
                    """);
        }

        @Test
        @DisplayName("识别模型目录必须是一个结构完整的 Vosk 模型，而不是随便一个路径")
        void chosenDirIsUsable() throws IOException {
            fakeModel("vosk-model-small-cn-0.22");
            assertTrue(AppPaths.looksLikeVoskModel(AppPaths.asrModelDir()),
                    "asrModelDir() 的结果会直接交给 VoskModel.load，结构不完整会崩在原生层");
        }

        @Test
        @DisplayName("显式配置（系统属性）优先于自动探测")
        void explicitOverrideWins() throws IOException {
            fakeModel("model-cn");
            Path custom = fakeModel("my-own-model");
            System.setProperty("talkinglive.model.asr", custom.toString());
            try {
                assertEquals(custom, AppPaths.asrModelDir());
                assertEquals(custom, AppPaths.configuredAsrOverride());
            } finally {
                System.clearProperty("talkinglive.model.asr");
            }
        }

        @Test
        @DisplayName("配置了不存在的目录时回退（不返回坏路径）")
        void bogusOverrideFallsBack() throws IOException {
            Path small = fakeModel("vosk-model-small-cn-0.22");
            System.setProperty("talkinglive.model.asr", tmp.resolve("nope").toString());
            try {
                assertNull(AppPaths.configuredAsrOverride());
                assertEquals(small, AppPaths.asrModelDir());
            } finally {
                System.clearProperty("talkinglive.model.asr");
            }
        }
    }
}
