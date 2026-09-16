package com.talkinglive.text;


/**
 * 清理与数字规整。
 *
 * <p><b>它不是标点主力</b>（TECH-PLAN §5.3）：标点由精化引擎的原生能力提供。
 * 这里只做引擎与注入器都不该管的几件事：剔除唤醒词/结束词、空白归一化、
 * 补句末标点、清理不可见字符。
 *
 * <h2>为什么剔除唤醒词必须「边界 + 容错」</h2>
 *
 * <p>段落音频天然是 {@code 唤醒词 + 正文 + 结束词}——录音从唤醒那一刻开始、到结束词为止
 * （{@code TECH-PLAN} §6.3 已把「会不会把唤醒词转进正文」列为正确性风险）。
 * 所以清理必须做，但**精确子串替换是不够的**，实测踩过：
 *
 * <pre>
 *   用户说：子曰    现在进行麦克风测试   到此为止
 *   精化得：在      现在进行卖封测四     到此为止
 *           ↑ 唤醒词被听成 1 个字（丢了一个字）
 *   精确替换「子曰」→ 匹配不到 → 注入『在现在进行卖封测四到此为止』
 * </pre>
 *
 * <p>改成两条规则，合起来才稳：
 *
 * <ol>
 *   <li><b>只在首/尾边界删。</b>唤醒词只可能在段落开头、结束词只可能在结尾。
 *       中间出现的相同文字是正文，<b>绝不能删</b>——早期实现是全串替换，
 *       会把正文里的「子曰」误删。</li>
 *   <li><b>容忍缺字。</b>识别常把词里的字吞掉（「子曰」→「在」，「到此为止」→「此为止」），
 *       所以匹配「该词的任意**连续片段**」。只放宽到这个程度是因为：
 *       再放宽（比如允许任意插入）会开始误伤正文，而边界窗口本身已经限制了范围。</li>
 * </ol>
 *
 * <p>边界窗口取 {@value #MAX_WORD_CODE_POINTS} 个码点：两个词都在 4 码点以内，
 * 留一倍余量给识别误差与多余标点。
 */
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PunctuationProcessor implements TextPostProcessor {

    /** 边界窗口（码点）。只在开头/结尾这么多码点内找唤醒词/结束词。 */
    private static final int MAX_WORD_CODE_POINTS = 8;

    private final List<String> stripWords;
    private final boolean ensureEndPunctuation;
    private final boolean collapseWhitespace;

    public PunctuationProcessor(List<String> stripWords) {
        this(stripWords, true, true);
    }

    public PunctuationProcessor(List<String> stripWords, boolean ensureEndPunctuation,
            boolean collapseWhitespace) {
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
            if (w != null && !w.isBlank()) {
                s = stripAtBoundaries(s, w);
            }
        }
        // 不可见字符直接**删除**，不要替换成半角空格：中文正文里 U+3000 通常是
        // 排版噪声（Vosk 分词、输入法、网页复制都可能带进来），换成空格会留下
        // 「今天 天气」这种多余空隙。BOM 同理。
        s = s.replace("\u3000", "").replace("\uFEFF", "");
        // 其它控制字符（保留换行与制表，它们可能是有意的段落分隔）
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

    /**
     * 在首/尾边界删除某个词（容忍**少字**，不容忍改字）。
     *
     * <p>这块换过三版，每一版都因为「判据选错」而失败，记录在此避免后人重走：
     *
     * <table border="1">
     *   <caption>三代实现与失败原因</caption>
     *   <tr><th>版本</th><th>判据</th><th>失败表现</th></tr>
     *   <tr><td>① 精确子串替换</td><td>文本包含该词</td>
     *       <td>识别把词尾吞掉（到此为止→此为止）时匹配不到，词留在正文里</td></tr>
     *   <tr><td>② 按字匹配（字属于该词）</td><td>逐字贪心吃</td>
     *       <td>唤醒词被听成「在」时，「在」不属于{子,曰}，仍然匹配不到</td></tr>
     *   <tr><td>③ 编辑距离（距离 ≤ 1~2 都算）</td><td>DP 编辑距离</td>
     *       <td><b>过度删除</b>：「子曰今天天气不错」把「子曰今天」当成距离 1 的词删掉，
     *           正文被吃掉一半（实测「今天天气不错」→「今天天气不」）</td></tr>
     *   <tr><td><b>④ 当前实现</b></td><td><b>只允许「少字」，不允许「改字」</b></td>
     *       <td>—</td></tr>
     * </table>
     *
     * <p>当前判据的依据：实测的识别误差形态主要是**丢字**（「到此为止」→「此为止」、
     * 「子曰」→「子」），而不是等长的乱改。所以只在「候选 = 目标词删掉 0 或 1 个字」
     * 时认定命中，**长度差超过 1 直接否掉**。这样：
     * <ul>
     *   <li>「此为止」命中「到此为止」（删 1 字）✓</li>
     *   <li>「子曰今天」不命中「子曰」（要删 4 字）✓ 正文保住</li>
     *   <li>「在」不命中「子曰」（不是删字能得到的形式）✗ 残留一个字，见下</li>
     * </ul>
     *
     * <p><b>已知残留及正解</b>：若识别把唤醒词听成了与目标词毫无关系的字
     * （「子曰」→「在」），文本层无法可靠区分它与正文，**故意不删**——
     * 多留一个字的代价，远小于删掉用户正文的代价。
     * 彻底解法是**在音频层面裁掉唤醒词那一段**（`App` 里的
     * {@code wakeWordAudioEnd} 标记 + {@code DictationSession} 的起点偏移），
     * 精化时根本不把唤醒词喂给识别器，从根上消除此类误识别。
     */
    public static String stripAtBoundaries(String text, String word) {
        if (text == null || text.isEmpty() || word == null || word.isBlank()) {
            return text == null ? "" : text;
        }
        int[] wordCp = word.codePoints().toArray();
        // 允许的候选：目标词本身，以及删掉任意 1 个字后的形态
        List<int[]> candidates = dropVariants(wordCp);

        String s = text;
        for (int round = 0; round < 4; round++) {
            String before = s;
            s = cutLeading(s, candidates);
            s = cutTrailing(s, candidates);
            if (s.equals(before)) {
                break;
            }
        }
        return s;
    }

    /** 目标词最多可删掉多少个字（给识别丢字留的余量）。 */
    private static final int MAX_DROPPED = 2;

    /**
     * 候选形态的**最短长度**。
     *
     * <p>这条限制是防止过度删除的关键：若允许把「到此为止」删到只剩一个字
     * （「到」「此」「为」「止」），那正文里出现这些常用字就会被误删。
     * 要求至少留 2 个字，于是 2 字词（如「子曰」）自然只能删 1 个字。
     */
    private static final int MIN_CANDIDATE = 2;

    /**
     * 生成「目标词本身 + 删掉 1~{@value #MAX_DROPPED} 个字」的所有形态，
     * 按长度降序（长的先匹配）。
     *
     * <p>例：「到此为止」→ 到此为止, 此为止, 到为止, 到此为, 到此止, 到此, 为止, …
     * <br>例：「子曰」  → 子曰, 子, 曰
     *
     * <p>取长度降序是为了「先试最长的」：否则「到此为止」可能先被「此」匹配掉，
     * 只删掉一个中间的字，留下「到为止」这种残渣。
     */
    private static List<int[]> dropVariants(int[] word) {
        List<int[]> out = new ArrayList<>();
        out.add(word);
        // 枚举所有「保留的子序列」（删除若干字后剩下的），长度在 [MIN_CANDIDATE, 词长-1]
        int n = word.length;
        for (int mask = 1; mask < (1 << n); mask++) {
            int kept = Integer.bitCount(mask);
            if (kept < MIN_CANDIDATE || kept >= n) {
                continue;
            }
            if (n - kept > MAX_DROPPED) {
                continue;
            }
            int[] v = new int[kept];
            int k = 0;
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    v[k++] = word[i];
                }
            }
            out.add(v);
        }
        out.sort((a, b) -> Integer.compare(b.length, a.length));
        return out;
    }

    /** 从开头删：跳过前导标点后，看有没有候选正好匹配前缀。 */
    private static String cutLeading(String s, List<int[]> candidates) {
        int[] bounds = codePointBounds(s);
        int start = 0;
        while (start < bounds.length - 1 && isPunctOrSpace(s.codePointAt(bounds[start]))) {
            start++;
        }
        int matchedEnd = -1;
        for (int[] cand : candidates) {
            int end = start + cand.length;
            if (end > bounds.length - 1) {
                continue;
            }
            int[] prefix = s.substring(bounds[start], bounds[end]).codePoints().toArray();
            if (java.util.Arrays.equals(prefix, cand)) {
                matchedEnd = end;
                break;
            }
        }
        if (matchedEnd < 0) {
            return s;
        }
        // 连同被删词后面的标点一起跳过，避免留下「，今天…」
        int next = matchedEnd;
        while (next < bounds.length - 1 && isPunctOrSpace(s.codePointAt(bounds[next]))) {
            next++;
        }
        return s.substring(bounds[next]);
    }

    /** 从结尾删：跳过尾部标点后，看有没有候选正好匹配后缀。 */
    private static String cutTrailing(String s, List<int[]> candidates) {
        int[] bounds = codePointBounds(s);
        int end = bounds.length - 1;
        while (end > 0 && isPunctOrSpace(s.codePointAt(bounds[end - 1]))) {
            end--;
        }
        int matchedStart = -1;
        for (int[] cand : candidates) {
            int begin = end - cand.length;
            if (begin < 0) {
                continue;
            }
            int[] suffix = s.substring(bounds[begin], bounds[end]).codePoints().toArray();
            if (java.util.Arrays.equals(suffix, cand)) {
                matchedStart = begin;
                break;
            }
        }
        if (matchedStart < 0) {
            return s;
        }
        int prev = matchedStart;
        while (prev > 0 && isPunctOrSpace(s.codePointAt(bounds[prev - 1]))) {
            prev--;
        }
        return s.substring(0, bounds[prev]);
    }

    /** 每个码点的起止下标（含末尾），避免切在代理对中间。 */
    private static int[] codePointBounds(String s) {
        List<Integer> idx = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            idx.add(i);
            i += Character.charCount(s.codePointAt(i));
        }
        idx.add(s.length());
        int[] out = new int[idx.size()];
        for (int k = 0; k < out.length; k++) {
            out[k] = idx.get(k);
        }
        return out;
    }

    private static boolean isPunctOrSpace(int cp) {
        return Character.isWhitespace(cp) || "，。！？、；：…,.!?;:()（）「」『』\"'“”‘’".indexOf(cp) >= 0;
    }
}
