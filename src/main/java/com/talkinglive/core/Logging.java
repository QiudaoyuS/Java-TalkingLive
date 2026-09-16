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
}
