package com.talkinglive.engine;

import com.talkinglive.text.TextUtils;

/**
 * Vosk 结果 JSON 的解析。
 *
 * <p>Vosk 的 {@code getResult() / getPartialResult()} 返回形如
 * <pre>{"text" : "今天 天气 不错"}</pre>
 * 这里只取 {@code text} 字段。解析用自带的 {@code JsonCodec}（零依赖），
 * 而不是为了一个字段引入 JSON 库。
 *
 * <p>另外做一件必要的事：{@link TextUtils#joinStreamTokens} 把词间空格去掉。
 * Vosk 中文结果按词输出并带空格，直接显示会变成「今天 天气 不错」。
 */
final class VoskJson {

    private VoskJson() {}

    /** 从 Vosk 的结果 JSON 里取出并规整文本；解析失败返回空串。 */
    static String text(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        String raw;
        try {
            var m = com.talkinglive.core.JsonCodec.parseObject(json);
            raw = com.talkinglive.core.JsonCodec.str(m, "text", "");
        } catch (RuntimeException e) {
            // 引擎给出了非预期内容：不改写、不崩溃，只当没有结果。
            // 静默丢弃在这里是可接受的——音频线程不能因为一行 JSON 死掉。
            return "";
        }
        return TextUtils.joinStreamTokens(raw);
    }
}
