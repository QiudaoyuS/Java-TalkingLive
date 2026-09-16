package com.talkinglive.core;

import java.util.Objects;

/**
 * 转写内容的日志安全表示。
 *
 * <p>{@code DESIGN.md} §3.1 第 12 项：日志**不记转写内容**。但只记「有一段文本」
 * 又无法排查问题，所以折中为：长度 + 短哈希。哈希不可逆，能用来回答
 * 「预览文本与最终文本是不是同一段」，又不泄露说了什么。
 *
 * <p>约定：任何面向日志的文本都必须过这里，禁止直接 {@code log.info("text={}", text)}。
 */
public final class Logging {

    private Logging() {}

    /** 形如 {@code len=12（内容不记录）}。 */
    public static String describe(String text) {
        return describe(text, "");
    }

    /** 形如 {@code len=12 rev=3（内容不记录）}；{@code tag} 为空的项会被省略。 */
    public static String describe(String text, String tag) {
        int len = text == null ? 0 : text.codePointCount(0, text.length());
        String t = (tag == null || tag.isBlank()) ? "" : " " + tag;
        return "len=" + len + t + "（内容不记录）";
    }

    /**
     * 文本的稳定短指纹（8 位十六进制），用于跨日志行比对同一段文本，
     * 不泄露内容。与 {@code String.hashCode} 不同，这里用 FNV-1a 以便跨进程/JVM 稳定。
     */
    public static String fingerprint(String text) {
        if (text == null || text.isEmpty()) {
            return "00000000";
        }
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            h ^= text.charAt(i);
            h *= 0x100000001b3L;
        }
        return String.format("%08x", h & 0xFFFFFFFFL);
    }

    /** 组合描述：长度 + 指纹，最常用的一种。 */
    public static String describeWithFingerprint(String text) {
        return describe(text, "fp=" + fingerprint(Objects.requireNonNullElse(text, "")));
    }

    /** 形如 {@code len=6 fp=abcd1234} —— 不含「内容不记录」那段文字，便于并排对比。 */
    public static String stamp(String text) {
        return "len=" + codePointCount(text) + " fp=" + fingerprint(text);
    }

    /**
     * 文本的**前若干码点**，用于诊断「这一段是不是上一段的文本」。
     *
     * <p>这是对「不记转写内容」原则的一次**受控例外**，必须清楚为什么值得：
     * 实测遇到过一个只有对比前缀才能定位的故障——第二段注入的文字里
     * **混着上一段的句子**（用户说「在进行麦克风测试」，注入的是
     * 「。在进行麦克风测试。」，前半截来自上一段）。光看长度与指纹无法判断
     * 「哪一部分是旧的」，只有把前缀并排看才能立刻认出来。
     *
     * <p>因此：只在**转写内容确认串段**这类排查场景下使用，默认关闭，
     * 由 {@code -Dtalkinglive.log.text=true} 显式打开。日常运行仍然不记内容。
     */
    public static String prefix(String text, int codePoints) {
        if (text == null || text.isEmpty() || codePoints <= 0) {
            return "";
        }
        int total = text.codePointCount(0, text.length());
        if (codePoints >= total) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, codePoints));
    }

    /** 诊断行里用的默认前缀长度。 */
    public static final int DIAG_PREFIX_CODE_POINTS = 16;

    private static int codePointCount(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }
}
