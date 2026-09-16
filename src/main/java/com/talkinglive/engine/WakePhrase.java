package com.talkinglive.engine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 一个用户输入的唤醒/结束短语，被解析成**受限语法里真正要放进去的 token 序列**。
 *
 * <h2>为什么需要这一层</h2>
 *
 * <p>Vosk 的受限语法可以接受**多个 token**，而 token 的粒度是"词表里的条目"。
 * 实测（{@code target/p7/GrammarSeqProbe}，见 {@code docs/ENGINE-EXPERIMENT.md} §7.5）：
 *
 * <pre>
 * 语法 ["飞瑞","[unk]"]        说「飞瑞」→ [unk]   ← 「飞瑞」这个词不在词表内
 * 语法 ["飞","瑞","[unk]"]     说「飞瑞」→ 飞瑞    ← 「飞」和「瑞」各自都在词表内
 * </pre>
 *
 * <p>也就是说：**只要每个字单独在词表内，就能拼出词表里不存在的短语**。
 * 这正是"英语唤醒词"的解法——中文模型永远发不出 {@code Firay}（词表内实测
 * 拉丁 token 为 0 个），但可以用汉字把这个发音拼出来：{@code Firay ≈ 飞瑞}。
 * 用户输入的仍然是自己想要的发音，只是写成汉字。
 *
 * <h2>拆字的代价</h2>
 *
 * <p>拆成单字后误触发率高于整词（单字在正常说话里出现得太频繁），所以：
 * <ul>
 *   <li>**只在整词不在词表内时才拆**，绝不主动拆一个词表里有的词；</li>
 *   <li>命中判定用**整体相等**而不是包含（见 {@code VoskKeywordDetector.match}），
 *       少一个字不算命中；</li>
 *   <li>拆了几段、每段是哪个字都要写进日志，用户能看出自己被听成了什么。</li>
 * </ul>
 *
 * @param display 用户原来输入的形式（日志、提示、结果里显示的都是它）
 * @param tokens  放进受限语法里的 token 序列；长度 ≥ 1
 */
public record WakePhrase(String display, List<String> tokens) {

    /**
     * 一个短语最多允许拆成几个字。
     *
     * <p>两个理由：语法里 token 越多，解码搜索空间越大（受限语法的准确率优势会被吃掉）；
     * 而且单字序列越长，"每说一个字都可能撞上" 的概率越高。
     * 8 个字远超任何唤醒词的合理长度，到了这个长度说明用户输错了。
     */
    public static final int MAX_TOKENS = 8;

    /**
     * 受限语法里"语法外语音"的符号。
     *
     * <p>定义在这里而不是 {@code VoskKeywordDetector}：匹配时要去掉它，
     * 而匹配逻辑在本类里，两处写同一个字面量迟早会不一致。
     */
    public static final String UNK = "[unk]";

    public WakePhrase {
        Objects.requireNonNull(display, "display");
        if (tokens == null || tokens.isEmpty()) {
            throw new IllegalArgumentException("token 序列不能为空");
        }
        tokens = List.copyOf(tokens);
    }

    /**
     * 词表查询能力。
     *
     * <p>刻意**不收 {@link VoskModel}**：解析逻辑全是纯字符串判断，绑到具体模型上
     * 就没法在不装模型、不开原生库的情况下测。{@code VoskModel} 又是 final 且构造函数
     * 私有，测试里连假实现都造不出来（实测：写成 {@code extends VoskModel} 直接编译失败），
     * 所以这里收一个函数式接口，调用方传 {@code model::findWord} 即可。
     * 与 {@code system.MicValidator.WordLookup} 是同一个形状，理由也一样。
     */
    @FunctionalInterface
    public interface Vocabulary {
        boolean contains(String word);
    }

    /**
     * 把用户输入的短语解析成 token 序列。
     *
     * <p>解析顺序（**先整词，后拆字**，顺序不能反）：
     * <ol>
     *   <li>整个短语就在词表内 → 单个 token。这是最可靠的情况，也是老配置走的路。</li>
     *   <li>整个短语不在词表内、但**每个字符都单独在词表内** → 逐字拆开成多个 token。</li>
     *   <li>其余情况 → 无法解析，返回 {@link Optional#empty()}。调用方必须把它变成
     *       用户可见的错误（Vosk 对词表外的词是**静默忽略**的，不报错就等于功能悄悄失效）。</li>
     * </ol>
     *
     * @param vocabulary 词表查询
     * @param text       用户输入的短语；空白、null 都返回 empty
     */
    public static Optional<WakePhrase> resolve(Vocabulary vocabulary, String text) {
        if (vocabulary == null || text == null) {
            return Optional.empty();
        }
        String s = text.trim();
        if (s.isEmpty()) {
            return Optional.empty();
        }
        if (vocabulary.contains(s)) {
            return Optional.of(new WakePhrase(s, List.of(s)));
        }
        // 实测**已知不可用**的词直接拒绝，即使逐字拆能凑出来。
        //
        // 理由：拆字会绕过一个来之不易的结论。附录 B.1 当初逐词实测，就是因为
        // 「本段结束」不在词表内才把结束词改成「到此为止」——但它的四个字
        // （本/段/结/束）各自都在表内，从"每个 token 都在表内"这个判据看它是合法的。
        // 判据没错，只是不充分：拆成单字后，声学上要连着说对四个字才算命中，
        // 这种短语在真实语音里几乎不可能稳定命中，等于给用户一个看起来能用、
        // 实际上永远不会响的唤醒词。所以这里显式拦住，而不是让用户自己去踩。
        if (com.talkinglive.core.WordSuggestions.KNOWN_ABSENT.contains(s)) {
            return Optional.empty();
        }
        // 逐字拆。用 codePoint 而不是 char：超出 BMP 的汉字（𠀀 等）是代理对，
        // 按 char 拆会切出半个字符，查词表必然失败。
        int[] cps = s.codePoints().toArray();
        if (cps.length < 2 || cps.length > MAX_TOKENS) {
            return Optional.empty();
        }
        List<String> tokens = new java.util.ArrayList<>(cps.length);
        for (int cp : cps) {
            String one = new String(Character.toChars(cp));
            if (!vocabulary.contains(one)) {
                return Optional.empty();
            }
            tokens.add(one);
        }
        return Optional.of(new WakePhrase(s, tokens));
    }

    /** 是否被拆成了单字序列（整词命中时为 false）。 */
    public boolean spelled() {
        return tokens.size() > 1;
    }

    /** 语法里要放的 token 个数。 */
    public int tokenCount() {
        return tokens.size();
    }

    /**
     * 识别结果文本是否命中本短语。
     *
     * <p>整词用**包含**判断：受限语法下引擎可能把一个词切成两段，或结果里夹着
     * {@code [unk]}，包含判断更耐用（这是原有行为，保持不变）。
     *
     * <p>单字序列用**整体相等**判断：拆字后每个字都很常见，再放宽成包含就会
     * "说到一半的字"也算命中，误唤醒率会明显上升。严格相等把代价换成"少说一个字不认"，
     * 对唤醒词来说是对的方向。
     *
     * <p>归一化两步（都不是想当然的，是实测出来的）：
     * <ol>
     *   <li>去掉 {@code [unk]} —— 语法里这个符号是"语法外语音"的去处，结果文本里
     *       会真的出现它（实测 {@code ["小助手","[unk]"]} 说「飞瑞」得到 {@code [unk]}）。</li>
     *   <li>去掉**所有空白** —— {@code VoskModel.Recognizer.textOf} 会把 token 之间的
     *       空格去掉，但只在"没有拉丁字符"时去（它要靠空格判断英文词边界）。
     *       语法输出正好是 token 序列，所以 "飞 瑞" 是可能出现的形态，
     *       不去空格就会漏掉一次真实命中。</li>
     * </ol>
     */
    public boolean matches(String recognizedText) {
        if (recognizedText == null) {
            return false;
        }
        String t = recognizedText.replace(UNK, "").replaceAll("\\s+", "");
        if (t.isEmpty()) {
            return false;
        }
        return spelled() ? t.equals(display.replaceAll("\\s+", "")) : t.contains(display);
    }

    /** 日志用：整词直接给词，单字序列给出「飞+瑞」这种拆法。 */
    public String describeTokens() {
        return tokens.stream().collect(Collectors.joining("+"));
    }

    @Override
    public String toString() {
        return spelled() ? display + "（拆为 " + describeTokens() + "）" : display;
    }
}
