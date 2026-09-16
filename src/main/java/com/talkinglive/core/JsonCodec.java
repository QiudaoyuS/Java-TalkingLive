package com.talkinglive.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极小 JSON 编解码器。
 *
 * <p>为什么不用第三方库：配置读写被 {@code DESIGN.md} §4.5 划为「纯逻辑，可单测」，
 * 引入 Jackson/Gson 会把一个可测的纯逻辑模块变成依赖模块。本产品只需要读写
 * 一个扁平的对象，这里 200 行足够，且能给出精确到行列的错误信息——
 * 「配置必须显式校验」（§4.3）依赖的正是可读的失败原因。
 *
 * <p>支持：对象、数组、字符串（含 {@code \\uXXXX} 转义）、数字、true/false/null。
 * 不支持：注释、尾随逗号、NaN/Infinity。这些都不该出现在配置里，出现即报错。
 */
public final class JsonCodec {

    /** UTF-8 BOM，写文件时不写它，读文件时容忍它。 */
    private static final char BOM = '\uFEFF';

    private JsonCodec() {}

    // ---------------------------------------------------------------- 解析

    /** 把 JSON 文本解析为 {@code Map<String,Object>}（对象根）。 */
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map<?, ?> m)) {
            throw new JsonException("配置文件的根必须是 JSON 对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) m;
        return out;
    }

    /** 把 JSON 文本解析为 Java 值：Map / List / String / Double / Boolean / null。 */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("内容是 null");
        }
        var p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (!p.eof()) {
            throw new JsonException("第 " + p.line() + " 行附近有多余内容");
        }
        return v;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s.startsWith(String.valueOf(BOM)) ? s.substring(1) : s;
        }

        boolean eof() {
            return i >= s.length();
        }

        int line() {
            int n = 1;
            for (int k = 0; k < Math.min(i, s.length()); k++) {
                if (s.charAt(k) == '\n') {
                    n++;
                }
            }
            return n;
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        char peek() {
            if (eof()) {
                throw new JsonException("内容意外结束（第 " + line() + " 行）");
            }
            return s.charAt(i);
        }

        void expect(char c) {
            if (eof() || s.charAt(i) != c) {
                throw new JsonException("第 " + line() + " 行：期望 '" + c + "'");
            }
            i++;
        }

        Object value() {
            char c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, i)) {
                throw new JsonException("第 " + line() + " 行：非法字面量，期望 " + word);
            }
            i += word.length();
            return v;
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                String k = string();
                skipWs();
                expect(':');
                skipWs();
                m.put(k, value());
                skipWs();
                char c = peek();
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == '}') {
                    i++;
                    return m;
                }
                throw new JsonException("第 " + line() + " 行：对象里期望 ',' 或 '}'");
            }
        }

        List<Object> array() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(value());
                skipWs();
                char c = peek();
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == ']') {
                    i++;
                    return list;
                }
                throw new JsonException("第 " + line() + " 行：数组里期望 ',' 或 ']'");
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw new JsonException("字符串没有闭合（第 " + line() + " 行）");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (eof()) {
                    throw new JsonException("转义没有结束（第 " + line() + " 行）");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw new JsonException("\\u 转义不完整（第 " + line() + " 行）");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException nfe) {
                            throw new JsonException("\\u 转义不是十六进制（第 " + line() + " 行）");
                        }
                        i += 4;
                    }
                    default -> throw new JsonException("无法识别的转义 '\\" + e + "'（第 " + line() + " 行）");
                }
            }
        }

        Double number() {
            int start = i;
            if (!eof() && (peek() == '-' || peek() == '+')) {
                i++;
            }
            while (!eof() && (Character.isDigit(peek()) || peek() == '.' || peek() == 'e' || peek() == 'E'
                    || peek() == '-' || peek() == '+')) {
                i++;
            }
            String raw = s.substring(start, i);
            if (raw.isEmpty()) {
                throw new JsonException("第 " + line() + " 行：不是合法的值");
            }
            try {
                return Double.valueOf(raw);
            } catch (NumberFormatException nfe) {
                throw new JsonException("第 " + line() + " 行：不是合法的数字 '" + raw + "'");
            }
        }
    }

    // ---------------------------------------------------------------- 写出

    /** 把 Java 值序列化为 JSON 文本。Map 的键按插入顺序输出。 */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v, int indent) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> writeString(sb, s);
            case Boolean b -> sb.append(b);
            case Number n -> sb.append(number(n));
            case Map<?, ?> m -> writeObject(sb, m, indent);
            case Iterable<?> it -> writeArray(sb, it, indent);
            default -> writeString(sb, String.valueOf(v));
        }
    }

    private static String number(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
            return n.toString();
        }
        double d = n.doubleValue();
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> m, int indent) {
        if (m.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int n = 0;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            pad(sb, indent + 1);
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(": ");
            writeValue(sb, e.getValue(), indent + 1);
            if (++n < m.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        pad(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Iterable<?> it, int indent) {
        List<Object> items = new ArrayList<>();
        it.forEach(items::add);
        if (items.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int k = 0; k < items.size(); k++) {
            pad(sb, indent + 1);
            writeValue(sb, items.get(k), indent + 1);
            if (k < items.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        pad(sb, indent);
        sb.append(']');
    }

    private static void pad(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- 取值助手

    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        if ("true".equalsIgnoreCase(s)) {
            return true;
        }
        if ("false".equalsIgnoreCase(s)) {
            return false;
        }
        throw new JsonException("配置项 " + key + " 应为 true/false，实际是 '" + s + "'");
    }

    public static double num(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new JsonException("配置项 " + key + " 应为数字，实际是 '" + v + "'");
        }
    }

    public static int intVal(Map<String, Object> m, String key, int def) {
        double d = num(m, key, def);
        if (d != Math.rint(d)) {
            throw new JsonException("配置项 " + key + " 应为整数，实际是 " + d);
        }
        return (int) d;
    }

    /** JSON 语法或取值错误。调用方负责把它变成用户可见的提示。 */
    public static final class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }
}
