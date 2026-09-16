package com.talkinglive.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 运行时目录布局（{@code DESIGN.md} §10.3）。
 *
 * <pre>
 * %LOCALAPPDATA%\TalkingLive\
 * ├── config.json
 * ├── logs\talkinglive.log
 * └── models\
 *     └── vosk-model-small-cn-0.22\
 * </pre>
 *
 * <p>可用系统属性 {@code talkinglive.home} 覆盖根目录——测试与「换个目录跑一遍」
 * 都靠它，避免污染真实用户目录。
 */
public final class AppPaths {

    public static final String HOME_PROPERTY = "talkinglive.home";

    private AppPaths() {}

    /** 根目录：{@code -Dtalkinglive.home=...} > {@code %LOCALAPPDATA%\TalkingLive} > {@code ~/.talkinglive}。 */
    public static Path home() {
        String override = System.getProperty(HOME_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            return Paths.get(local, "TalkingLive").toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".talkinglive").toAbsolutePath().normalize();
    }

    public static Path configFile() {
        return home().resolve("config.json");
    }

    public static Path logDir() {
        return home().resolve("logs");
    }

    public static Path modelsDir() {
        return home().resolve("models");
    }

    /** Vosk 小模型目录（唤醒/结束词 + 实时预览）。 */
    public static Path voskModelDir() {
        return modelsDir().resolve("vosk-model-small-cn-0.22");
    }

    /**
     * **听写用**的 Vosk 模型目录（预览 + 精化）。
     *
     * <p>默认与 {@link #voskModelDir()} 相同（小模型）。可以用
     * 系统属性 {@code -Dtalkinglive.model.asr=<目录>} 或环境变量
     * {@code TALKINGLIVE_ASR_MODEL=<目录>} 指向更大的模型来提升准确率。
     *
     * <p><b>为什么要分开配置：</b>
     * <ul>
     *   <li><b>唤醒/结束词检测必须用小模型</b>——只有它支持运行时动态词表
     *       （{@code DESIGN.md} 附录 B.2，实测 {@code graph/} 下没有 {@code Hclg.fst}，
     *       所以能按语法重建解码图）。大模型词表静态，改不了。</li>
     *   <li><b>预览与精化用越大越好</b>——它们不需要动态词表，只需要准确率。
     *       小模型 CER 17.15%，大模型（{@code vosk-model-cn-0.22}）CER 7.43%，
     *       差一倍以上。</li>
     * </ul>
     *
     * <p>实测的准确率问题正是这么来的：预览与精化都用 17% CER 的小模型，
     * 于是两者**错得一样**、精化无从纠正（诊断日志里
     * 「预览『…』精化『…』与预览差异=0码点」就是这么出现的）。
     */
    public static Path asrModelDir() {
        Path override = configuredAsrOverride();
        return override != null ? override : voskModelDir();
    }

    /**
     * **仅**取显式配置的听写模型目录（系统属性 / 环境变量），不做任何回退。
     *
     * <p>与 {@link #asrModelDir()} 的区别：后者会在未配置时回退到小模型，
     * 而调用方（识别大模型的自动探测）需要区分「用户明确指定了」与「什么都没配」——
     * 只有后者才应该去自动探测已安装的大模型。
     *
     * @return 配置的目录；未配置或目录不存在时返回 null
     */
    public static Path configuredAsrOverride() {
        return configuredModelDir("talkinglive.model.asr", "TALKINGLIVE_ASR_MODEL");
    }

    /** 精化引擎模型目录（SenseVoice，见 TECH-PLAN §6.7 第 3 项）。 */
    public static Path refinerModelDir() {
        return modelsDir().resolve("sense-voice");
    }

    /**
     * 大模型目录名。
     *
     * <p>与官方解压出来的目录名一致，也兼容我们自己的下载脚本（把
     * {@code model-cn.zip} 解成 {@code model-cn}）。两个名字都认，是因为
     * 用户完全可能自己按官方文档解压并改名 —— 让他为了「被认出来」而重命名目录
     * 是没必要的摩擦。
     */
    public static final java.util.List<String> LARGE_MODEL_DIR_NAMES =
            java.util.List.of("vosk-model-cn-0.22", "model-cn");

    /**
     * 探测**已安装的大模型**（用于自动提升识别准确率）。
     *
     * <p>为什么要自动探测而不是只留一个配置项：用户反馈的核心问题是
     * 「识别出来的字不对」，而根因就是小模型的精度上限（CER 17.15% 对 7.43%，
     * 见 {@code docs/ENGINE-EXPERIMENT.md}）。让用户为了用上更准的模型去改
     * 环境变量或 JSON，等于把「修好这个问题」变成用户自己的活。
     * 只要模型在，就自动用它做识别。
     *
     * @return 大模型目录；未安装时返回 null
     */
    public static Path detectLargeModelDir() {
        for (String name : LARGE_MODEL_DIR_NAMES) {
            Path p = modelsDir().resolve(name);
            if (looksLikeVoskModel(p)) {
                return p;
            }
        }
        return null;
    }

    /** 目录结构是否像一个完整的 Vosk 模型（缺一项就说明解压没完成）。 */
    public static boolean looksLikeVoskModel(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        for (String required : new String[] {"am", "conf", "graph", "ivector"}) {
            if (!Files.isDirectory(dir.resolve(required))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 读一个「模型目录」配置：系统属性优先，其次环境变量。
     *
     * @return 配置的目录；未配置或目录不存在时返回 null
     */
    private static Path configuredModelDir(String property, String envVar) {
        String v = System.getProperty(property);
        if (v == null || v.isBlank()) {
            v = System.getenv(envVar);
        }
        if (v == null || v.isBlank()) {
            return null;
        }
        Path p = Paths.get(v);
        if (!Files.isDirectory(p)) {
            // 配了但不存在：不静默回退到默认模型，否则用户会以为大模型生效了、
            // 却仍在抱怨准确率。明确记日志（调用方负责提示）。
            org.slf4j.LoggerFactory.getLogger(AppPaths.class)
                    .warn("配置的模型目录不存在，已回退到默认小模型：{}（配置值 {}）", p, v);
            return null;
        }
        return p;
    }

    public static Path recordingsDir() {
        return home().resolve("recordings");
    }

    public static void ensureDirectories() throws IOException {
        Files.createDirectories(home());
        Files.createDirectories(logDir());
        Files.createDirectories(modelsDir());
    }

    // ------------------------------------------------------------ 配置读写

    /**
     * 读取配置。
     *
     * <p>文件不存在时**写出默认配置并返回默认值**（首次启动的正常路径）。
     * 文件存在但非法时**抛出**——由调用方明确提示用户，绝不静默用默认值覆盖
     * （§4.3 / §7「任何失败都必须可见」）。
     *
     * @return 读到的配置，以及是否为「首次生成」
     */
    public static Loaded loadOrCreateConfig() throws IOException {
        Path f = configFile();
        if (!Files.exists(f)) {
            AppConfig c = new AppConfig();
            c.validate();
            saveConfig(c);
            return new Loaded(c, true);
        }
        String text = Files.readString(f, StandardCharsets.UTF_8);
        AppConfig c = AppConfig.fromJsonText(text);
        return new Loaded(c, false);
    }

    public static void saveConfig(AppConfig config) throws IOException {
        config.validate();
        Path f = configFile();
        Files.createDirectories(f.getParent());
        // 先写临时文件再原子替换：避免在写入过程中崩溃留下半截配置
        Path tmp = f.resolveSibling("config.json.tmp");
        Files.writeString(tmp, config.toJsonText(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 配置读取结果。 */
    public record Loaded(AppConfig config, boolean created) {}
}
