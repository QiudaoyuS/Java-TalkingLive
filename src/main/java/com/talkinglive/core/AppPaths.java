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

    /** 精化引擎模型目录（SenseVoice，见 TECH-PLAN §6.7 第 3 项）。 */
    public static Path refinerModelDir() {
        return modelsDir().resolve("sense-voice");
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
