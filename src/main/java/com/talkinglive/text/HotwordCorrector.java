package com.talkinglive.text;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 热词纠正 —— 把识别结果里**用户实际想要的写法**替换回去。
 *
 * <p>它存在的直接原因是一个实测出来的硬缺陷：{@code vosk-model-small-cn-0.22}
 * 的中文词表里**没有任何英文**（实测 A–Z、AI、APP、CPU 全部不在表内），
 * 所以小模型永远输出不了「AI」这种词；而流式预览用的就是小模型。
 * 大模型（{@code vosk-model-cn-0.22}）虽然认识 AI/APP/CPU/GPU/USB/PDF/PPT/PC/IP/ID/UI/VR/OK，
 * 但它同样可能把「AI」按字母拆成「A I」两段输出。
 *
 * <p><b>本类做两件事，都为「确定性改写」而非「猜」：</b>
 * <ol>
 *   <li>{@link #collapseSpacedLetters}——把被拆开的字母串拼回去：
 *       {@code "A I"} → {@code "AI"}、{@code "P D F"} → {@code "PDF"}。
 *       判据是「连续的单字母 token」，这是 Vosk 逐词输出的**格式**特征，不是语义猜测，
 *       因此不会误伤中文（中文词不是单字母）。</li>
 *   <li>{@link #replaceHotwords}——用户自己配的替换表：「说的词 = 想要的写法」。
 *       例如 {@code 诶爱=AI}、{@code 皮迪艾夫=PDF}。
 *       这一层是**用户显式指定**的，不需要我猜；用户没说过的词一律不动。</li>
 * </ol>
 *
 * <p><b>为什么不做「自动把中文近音词改成英文」</b>：那需要一张我拍脑袋编的词表，
 * 而它一旦猜错就会**改掉用户本来正确的正文**（这是比少一个字严重得多的错误）。
 * 热词表的正确形态是「用户说过的映射」，所以本类只实现用户显式配置的那部分。
 *
 * <p>顺序：热词替换在字母拼合**之前**做。理由——热词表里用户写的键可能是
 * 「A I」这种带空格的形态，先让用户规则命中，拼合规则只负责收尾。
 */
public final class HotwordCorrector implements TextPostProcessor {

    /** 配置里一项替换的写法：{@code 说的词=想要的写法}。 */
    private static final String PAIR_SEPARATOR = "=";

    /**
     * 连续单字母序列：至少两个单字母 token，允许中间是空格。
     *
     * <p>要求「至少两个」是刻意的：单个字母有自己的含义（比如「A 方案」里的 A），
     * 把它当成缩写的碎片拼掉反而会改坏正文。
     */
    private static final Pattern SPACED_LETTERS =
            Pattern.compile("(?<![A-Za-z0-9])(?:[A-Za-z]\\s+)+[A-Za-z](?![A-Za-z0-9])");

    private final Map<String, String> hotwords;

    public HotwordCorrector(Map<String, String> hotwords) {
        // 保持插入顺序：多条规则命中同一段文本时，先配的先改（行为可预测）
        this.hotwords = hotwords == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(hotwords);
    }

    /** 空表：只做字母拼合，不做替换。 */
    public static HotwordCorrector lettersOnly() {
        return new HotwordCorrector(Map.of());
    }

    public Map<String, String> hotwords() {
        return Map.copyOf(hotwords);
    }

    public boolean isEmpty() {
        return hotwords.isEmpty();
    }

    @Override
    public String process(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = replaceHotwords(text);
        return collapseSpacedLetters(out);
    }

    // ------------------------------------------------------------ 两层规则

    /** 用户配置的替换。按插入顺序逐条应用；键为空或值相同则跳过。 */
    private String replaceHotwords(String text) {
        String out = text;
        for (Map.Entry<String, String> e : hotwords.entrySet()) {
            String from = e.getKey();
            String to = e.getValue();
            if (from == null || from.isBlank() || to == null || from.equals(to)) {
                continue;
            }
            out = out.replace(from, to);
        }
        return out;
    }

    /**
     * 把被拆开的字母串拼回去，并统一成大写。
     *
     * <p>为什么统一大写：语音识别对大小写没有概念，用户说「AI」「PDF」时期望的也是大写。
     * 小写英文专有名词（如 {@code app}）如果要保持小写，用户可以用热词表显式指定
     * —— 那是「用户说了算」的范围，不该由这里替用户决定。
     */
    private String collapseSpacedLetters(String text) {
        Matcher m = SPACED_LETTERS.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (m.find()) {
            String joined = m.group().replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
            // 两个字母以上的才拼：单字母的情况上面的正则已经排除了
            m.appendReplacement(sb, Matcher.quoteReplacement(joined));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------ 配置解析

    /**
     * 解析配置串：{@code "诶爱=AI, 皮迪艾夫=PDF"}。
     *
     * <p>容忍的分隔符：逗号、中文逗号、分号、换行——用户在 JSON 里写多行更自然。
     * 格式不对的项**跳过而不是报错**：热词是锦上添花，不该让配置整体校验失败
     * 而导致程序起不来（对比：唤醒词为空是致命错误，必须拒绝启动）。
     *
     * @return 可读的替换表；输入为空时返回空表
     */
    public static Map<String, String> parse(String spec) {
        Map<String, String> out = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        for (String item : spec.split("[,，;；\\n\\r]+")) {
            String s = item.strip();
            if (s.isEmpty()) {
                continue;
            }
            int i = s.indexOf(PAIR_SEPARATOR);
            if (i <= 0 || i == s.length() - 1) {
                continue;   // 缺 = 或缺一侧：跳过
            }
            String from = s.substring(0, i).strip();
            String to = s.substring(i + 1).strip();
            if (!from.isEmpty() && !to.isEmpty() && !from.equals(to)) {
                out.put(from, to);
            }
        }
        return out;
    }

    /** 反向：把替换表写成配置串（保存配置时用）。 */
    public static String format(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        map.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(k).append(PAIR_SEPARATOR).append(v);
        });
        return sb.toString();
    }
}
