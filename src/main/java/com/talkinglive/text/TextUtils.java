package com.talkinglive.text;

/**
 * 文本工具（{@code DESIGN.md} §4.5：纯逻辑，可单测）。
 *
 * <p><b>码点约定</b>：§4.3 明确「涉及『第几个字符』的下标一律用 {@code codePointCount}，
 * 避免代理对导致退格数算错」。本类所有涉及字符下标的方法都以**码点**为单位，
 * 与 {@code String} 的 UTF-16 下标区分开，方法名统一带 {@code CodePoints} 后缀以示区别。
 */
public final class TextUtils {

    private TextUtils() {}

    /**
     * 两个字符串的最长公共**码点**前缀长度。
     *
     * <p>用途：判断流式预览里「哪些字已经稳定」。返回的是码点数，可直接用于
     * {@link #prefixByCodePoints} 与将来的增量注入退格计算。
     */
    public static int commonPrefixCodePoints(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int ia = 0;
        int ib = 0;
        int n = 0;
        while (ia < a.length() && ib < b.length()) {
            int ca = a.codePointAt(ia);
            int cb = b.codePointAt(ib);
            if (ca != cb) {
                break;
            }
            ia += Character.charCount(ca);
            ib += Character.charCount(cb);
            n++;
        }
        return n;
    }

    /** 取前 n 个**码点**。 */
    public static String prefixByCodePoints(String s, int codePoints) {
        if (s == null || s.isEmpty() || codePoints <= 0) {
            return "";
        }
        int total = s.codePointCount(0, s.length());
        if (codePoints >= total) {
            return s;
        }
        int end = s.offsetByCodePoints(0, codePoints);
        return s.substring(0, end);
    }

    /** 去掉前 n 个**码点**。 */
    public static String dropCodePoints(String s, int codePoints) {
        if (s == null || s.isEmpty() || codePoints <= 0) {
            return s == null ? "" : s;
        }
        int total = s.codePointCount(0, s.length());
        if (codePoints >= total) {
            return "";
        }
        int start = s.offsetByCodePoints(0, codePoints);
        return s.substring(start);
    }

    /** 码点数。 */
    public static int codePointCount(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    /** 取最后 n 个码点（用于「仍在变」的尾巴）。 */
    public static String suffixByCodePoints(String s, int codePoints) {
        if (s == null || s.isEmpty() || codePoints <= 0) {
            return "";
        }
        int total = codePointCount(s);
        return dropCodePoints(s, Math.max(0, total - codePoints));
    }

    /**
     * 把 Vosk 流式输出拼成可读文本。
     *
     * <p>Vosk 中文结果的词之间带空格，直接显示会变成「今天 天气 不错」。
     * 中文之间不加空格；拉丁字母/数字之间保留一个空格，避免把英文词粘成一个。
     */
    public static String joinStreamTokens(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (String raw : text.trim().split("\\s+")) {
            if (raw.isEmpty()) {
                continue;
            }
            if (sb.length() > 0 && needsSpace(sb.charAt(sb.length() - 1), raw.charAt(0))) {
                sb.append(' ');
            }
            sb.append(raw);
        }
        return sb.toString();
    }

    private static boolean needsSpace(char prev, char next) {
        return isLatinOrDigit(prev) && isLatinOrDigit(next);
    }

    private static boolean isLatinOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /**
     * 判断一段文字是不是「以句读结尾」，用于预览阶段的停顿启发式标点
     * （§3.1 第 11 项：whisper/SenseVoice 原生标点为主力，预览阶段辅以停顿启发式）。
     */
    public static boolean endsWithSentencePunctuation(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        char c = s.charAt(s.length() - 1);
        return "。！？!?；;…".indexOf(c) >= 0;
    }

    /** 归一化空白：连续空白压成一个空格，首尾去空白。但不动全角标点。 */
    public static String collapseWhitespace(String s) {
        if (s == null) {
            return "";
        }
        return s.strip().replaceAll("\\s+", " ");
    }

    /**
     * 从文本中移除指定的**词**（用于把唤醒词/结束词从正文里剔掉）。
     *
     * <p>{@code TECH-PLAN} §6.3 明确列为需验证项：「段落音频以唤醒词开头，
     * 若被转出则文字会多出『子曰』」。这里提供兜底：无论引擎有没有把唤醒词转出来，
     * 注入前都按**精确子串**移除一次。
     */
    public static String removeWord(String text, String word) {
        if (text == null || text.isEmpty() || word == null || word.isEmpty()) {
            return text == null ? "" : text;
        }
        return text.replace(word, "");
    }
}
