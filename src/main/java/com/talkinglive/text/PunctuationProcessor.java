package com.talkinglive.text;

import java.util.List;

/**
 * 清理与数字规整。
 *
 * <p><b>它不是标点主力</b>（TECH-PLAN §5.3）：标点由精化引擎的原生能力提供
 * （SenseVoice {@code use_itn=1} 输出「开放时间早上9点至下午5点。」这样的带标点文本）。
 * 这里只做四件引擎与注入器都不该管的事：
 *
 * <ol>
 *   <li><b>剔除唤醒词与结束词。</b>TECH-PLAN §6.3 把它列为需验证项：
 *       「段落音频以唤醒词开头，若被转出则文字会多出『子曰』」。
 *       无论引擎有没有转出来，注入前都按精确子串移除一次——这是**兜底**，
 *       因为用户最不能接受的就是正文里混进一句「子曰」。</li>
 *   <li><b>空白归一化。</b>Vosk 流式输出词间带空格，中文里这些空格必须去掉
 *       （由 {@link TextUtils#joinStreamTokens} 负责），这里再压一遍连续空白。</li>
 *   <li><b>补齐句末标点。</b>仅在文本末尾完全没有句读时补一个「。」——
 *       对应 §3.1 第 11 项「预览阶段辅以停顿启发式」。不做句内标点，那是引擎的活。</li>
 *   <li><b>全角空格等不可见字符清理。</b></li>
 * </ol>
 */
public final class PunctuationProcessor implements TextPostProcessor {

    private static final String SENTENCE_END = "。！？!?…；;";

    private final List<String> stripWords;
    private final boolean ensureEndPunctuation;
    private final boolean collapseWhitespace;

    public PunctuationProcessor(List<String> stripWords) {
        this(stripWords, true, true);
    }

    public PunctuationProcessor(List<String> stripWords, boolean ensureEndPunctuation, boolean collapseWhitespace) {
        this.stripWords = stripWords == null ? List.of() : List.copyOf(stripWords);
        this.ensureEndPunctuation = ensureEndPunctuation;
        this.collapseWhitespace = collapseWhitespace;
    }

    /** 默认实现：剔除唤醒词与结束词。 */
    public static PunctuationProcessor forWakeAndEndWords(String wakeWord, String endWord) {
        return new PunctuationProcessor(List.of(wakeWord == null ? "" : wakeWord,
                endWord == null ? "" : endWord));
    }

    @Override
    public String process(String text) {
        if (text == null) {
            return "";
        }
        String s = text;
        for (String w : stripWords) {
            if (w != null && !w.isEmpty()) {
                s = TextUtils.removeWord(s, w);
            }
        }
        s = s.replace('\u3000', ' ').replace("\uFEFF", "");
        s = s.replaceAll("[\\p{Cntrl}&&[^\n\t]]", "");
        if (collapseWhitespace) {
            s = TextUtils.collapseWhitespace(s);
        }
        s = s.strip();
        if (s.isEmpty()) {
            return "";
        }
        if (ensureEndPunctuation && !TextUtils.endsWithSentencePunctuation(s)) {
            s = s + "。";
        }
        return s;
    }
}
