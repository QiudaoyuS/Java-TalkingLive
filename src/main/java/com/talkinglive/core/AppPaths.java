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
     * **识别用**模型目录 —— 实时预览、落字、段落精化**三者共用这一个**。
     *
     * <p>优先级：显式配置（{@code -Dtalkinglive.model.asr=<目录>} 或
     * {@code TALKINGLIVE_ASR_MODEL}）> 自动探测已安装的大模型 > 小模型。
     * 也就是**装好大模型即自动生效**，用户不需要改任何配置。
     *
     * <p><b>为什么三者必须共用同一个模型</b>：不同模型会给出不同的文本。
     * 实测拿同一段合成语音（「现在是人工智能输入测试，今天天气不错」）对比：
     * 小模型输出「人工智能<b>收入</b>测试」，大模型输出「人工智能<b>输入</b>测试」；
     * 而小模型的**词表里没有任何英文**（A–Z、AI、APP、CPU 全不在表内），
     * 于是它会把「AI」整个漏掉。两个模型分开时，用户在预览里看到的和最终落进去的
     * 不是同一句话，而且是**悄悄**不一样 —— 这类预期差比功能缺失更让人困惑。
     *
     * <p><b>为什么可以共用</b>：实测流式识别速度两者几乎一致 ——
     * 5.2 秒音频 RTF 0.253（小）对 0.256（大），13.6 秒音频 0.175 对 0.189。
     * 所以共用只增加加载时间（约 17–21 秒，启动时一次），不增加识别时的 CPU。
     *
     * <p><b>唤醒/结束词检测仍然是例外，只能用那个小模型</b>（见 {@link #voskModelDir()}）：
     * 只有它支持运行时动态词表。大模型词表静态、改不了，实测 C 层会直接输出
     * {@code Runtime graphs are not supported by this model}。
     */
    public static Path asrModelDir() {
        Path override = configuredModelDir("talkinglive.model.asr", "TALKINGLIVE_ASR_MODEL");
        if (override != null) {
            return override;
        }
        // 未显式配置时自动探测已安装的大模型 —— 「装好即自动生效」，
        // 不需要用户为了用上更准的模型去改环境变量或 JSON。
        Path large = detectLargeModelDir();
        return large != null ? large : voskModelDir();
    }

    /**
     * **仅**取显式配置的识别模型目录（系统属性 / 环境变量），不做任何回退。
     *
     * <p>与 {@link #asrModelDir()} 的区别：后者会在未配置时自动探测大模型、
     * 再回退小模型；而调用方有时需要区分「用户明确指定了」与「什么都没配」。
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
