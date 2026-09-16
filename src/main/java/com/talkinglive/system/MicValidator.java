package com.talkinglive.system;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 词表校验（{@code DESIGN.md} §4.5：纯逻辑，可单测）。
 *
 * <p>这一层存在的**唯一理由**是附录 C 记录的静默失效：Vosk 对词表外的词
 * 构建语法成功、不抛异常，只在 stderr 打一行 WARNING，之后那个词永远不会被识别到。
 * 用户的表现是「改了配置没反应」，而程序毫无察觉。
 *
 * <p>因为「查词」这个动作本身要调原生库，所以这里把**判断逻辑**（哪些词不合格、
 * 消息怎么写、有哪些备选）与**查询能力**（{@link WordLookup}）分开——
 * 前者纯逻辑、可单测；后者由 {@code engine.VoskModel} 提供。
 */
public final class MicValidator {

    /** 词表查询能力。真实实现走 {@code vosk_model_find_word}。 */
    @FunctionalInterface
    public interface WordLookup {
        boolean inVocabulary(String word);
    }

    /**
     * 「这个词能拆成哪几个 token」的判定能力（可选）。
     *
     * <p>受限语法接受的是一串 token，而 token 的粒度是**词表条目**。所以一个词
     * 整体不在词表内，只要它的**每个字**都在，就能拆成单字序列用——这正是
     * 「用汉字拼出英语发音」的解法（{@code Firay ≈ 飞瑞}，见 {@code DESIGN.md} 附录 B.4）。
     * 真实的解析逻辑在 {@code engine.WakePhrase}，这里只保留一个函数式接口，
     * 好让本类（{@code system} 层，纯逻辑、可单测）不依赖引擎层。
     *
     * <p>返回的是**词序列**而不是布尔值：界面要如实显示"被拆成了哪几个字"，
     * 而拆法（哪些字、什么顺序）由引擎决定。让界面自己再拆一遍就会有两份实现，
     * 迟早不一致。
     *
     * <p>**只有唤醒词走这条路。** 结束词不拆字：它的后果是"立刻停止录音"，
     * 而单字在正常说话里出现得太频繁，代价太大（判断在
     * {@code VoskKeywordDetector} 的构造函数里）。
     *
     * @return 拆开后的 token 序列；不可用（或只有 1 个 token）时返回空列表
     */
    @FunctionalInterface
    public interface SpellCheck {
        java.util.List<String> spell(String word);
    }

    /**
     * 词表外的常用备选（{@code DESIGN.md} 附录 B.1 的实测结论，见
     * {@link com.talkinglive.core.WordSuggestions}）。
     *
     * <p>写进提示里是因为用户第一次配置时**不可能知道**模型的词表里有什么——
     * 而「本段结束」不在表内这件事恰恰是 Step 0 踩出来的。
     */
    public static final Map<String, List<String>> SUGGESTIONS =
            com.talkinglive.core.WordSuggestions.BY_FIELD;

    private MicValidator() {}

    /**
     * 一个不合法的词。
     *
     * @param spelled 该项整体不在词表内，但**逐字都在**——即它其实能用（被拆成单字序列）。
     *                留着它是因为消息完全不同：不该说"永远不会被识别到"，
     *                而该告诉用户"要用它就得逐字说清楚"。
     * @param tokens  拆开后的单字序列（仅 {@code spelled} 时有意义）。带着真实 token
     *                而不是让界面自己去拆一遍：拆法（哪些字、什么顺序）由
     *                {@code engine.WakePhrase} 决定，界面重算就会有两份实现。
     */
    public record Problem(String field, String word, String suggestion, boolean spelled,
            List<String> tokens) {

        public Problem {
            tokens = tokens == null ? List.of() : List.copyOf(tokens);
        }

        public Problem(String field, String word, String suggestion) {
            this(field, word, suggestion, false, List.of());
        }

        public Problem(String field, String word, String suggestion, boolean spelled) {
            this(field, word, suggestion, spelled, List.of());
        }

        /**
         * 一行放得下的短版本，供设置窗口的固定高度状态标签使用。
         *
         * <p>**必须存在**：状态标签是按固定尺寸排版的（让文字撑开布局会让窗口跳动），
         * 而完整说明有 555px 宽、标签只有约 292px —— 实测被 Swing 截成
         * 「唤醒词「飞瑞」整体不在词表内，已...」，用户恰恰看不到"被拆成了哪几个字"，
         * 而那正是这条提示唯一要说的事。完整说明走 tooltip。
         */
        public String brief() {
            if (spelled) {
                return tokens.isEmpty()
                        ? field + "「" + word + "」已按单字拆开使用"
                        : field + "「" + word + "」已拆成单字 " + tokens.stream()
                                .map(t -> "「" + t + "」")
                                .collect(java.util.stream.Collectors.joining());
            }
            return field + "「" + word + "」不在词表内，不会被识别到";
        }

        public String describe() {
            if (spelled) {
                return field + "「" + word + "」整体不在词表内，已按单字拆开使用"
                        + "（每个字都在词表内）—— 说这个词时请把每个字都说清楚";
            }
            String s = field + "「" + word + "」不在 Vosk 模型词表内，永远不会被识别到";
            return suggestion == null || suggestion.isBlank() ? s : s + "；可改用：" + suggestion;
        }
    }

    /**
     * 校验结果。
     *
     * @param problems 真正**不合法**的词（功能一定失效）
     * @param checked  真正查过词表的词数（空词不算查过）
     * @param spelled  可用但被拆成单字序列的唤醒词——不是问题，但要点出来
     */
    public record Result(List<Problem> problems, int checked, List<Problem> spelled) {

        public Result {
            spelled = spelled == null ? List.of() : List.copyOf(spelled);
        }

        public Result(List<Problem> problems, int checked) {
            this(problems, checked, List.of());
        }

        public boolean ok() {
            return problems.isEmpty();
        }

        /** 面向用户的完整消息；没有任何要说的时返回 null。 */
        public String message() {
            StringBuilder sb = new StringBuilder();
            if (!problems.isEmpty()) {
                sb.append("Vosk 对词表外的词是静默忽略的，以下配置永远不会生效：");
                for (Problem p : problems) {
                    sb.append("\n  · ").append(p.describe());
                }
            }
            // 拆字不是错误，但用户必须知道自己被听成的是哪几个字——否则
            // 「说了没反应」时无从排查。
            for (Problem p : spelled) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("· ").append(p.describe());
            }
            return sb.length() == 0 ? null : sb.toString();
        }

        public List<String> problemFields() {
            List<String> out = new ArrayList<>();
            for (Problem p : problems) {
                out.add(p.field());
            }
            return out;
        }
    }

    /**
     * 校验一组词。
     *
     * @param lookup 查询能力
     * @param pairs  field -> word，例如 {"唤醒词" -> "子曰", "结束词" -> "到此为止"}
     */
    public static Result validate(WordLookup lookup, Map<String, String> pairs) {
        return validate(lookup, pairs, null);
    }

    /**
     * 校验一组词，并允许**唤醒词**走「逐字拆」这条路。
     *
     * @param spellCheck 判定"这个词能不能逐字拼出来"；{@code null} 表示不支持拆字
     *                   （调用方没接引擎时的保守行为：按整词严格校验）。
     *                   **只对唤醒词生效**，结束词永远要求整词在表内。
     */
    public static Result validate(WordLookup lookup, Map<String, String> pairs,
            SpellCheck spellCheck) {
        List<Problem> problems = new ArrayList<>();
        List<Problem> spelled = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            String field = e.getKey();
            String word = e.getValue() == null ? "" : e.getValue().trim();
            if (word.isEmpty()) {
                // **结束词空着是合法的**（表示不用结束词），不是"漏填"。
                // 把它报成问题会与 AppConfig.validate() 放开的那条自相矛盾：
                // 配置层说可以，词表层说不行，用户看到的是"改了没反应"。
                if (com.talkinglive.core.WordSuggestions.FIELD_END.equals(field)) {
                    continue;
                }
                problems.add(new Problem(field, "(空)", firstSuggestion(field)));
                continue;
            }
            checked++;
            boolean in;
            try {
                in = lookup.inVocabulary(word);
            } catch (RuntimeException ex) {
                // 查询本身失败（原生库不可用）不能当成「词不合法」，否则会把用户
                // 引向错误的修改方向。重新抛出，让上层报「模型/原生库有问题」。
                throw ex;
            }
            if (in) {
                continue;
            }
            // 整体不在表内，但可以逐字拼（只有唤醒词允许）。这类词**能用**，
            // 所以不进 problems——只在 spelled 里留一条用法提示。
            if (spellCheck != null
                    && com.talkinglive.core.WordSuggestions.FIELD_WAKE.equals(field)) {
                List<String> tokens = safeSpellCheck(spellCheck, word);
                if (tokens.size() > 1) {
                    spelled.add(new Problem(field, word, null, true, tokens));
                    continue;
                }
            }
            problems.add(new Problem(field, word, firstSuggestion(field)));
        }
        // 两个词相同时单独提示——它不会静默失效，但会立刻自我结束，同样属于
        // 「改了没反应」这一类困惑，放在同一个提示里一起说清楚。
        String wake = pairs.getOrDefault(com.talkinglive.core.WordSuggestions.FIELD_WAKE, "");
        String end = pairs.getOrDefault(com.talkinglive.core.WordSuggestions.FIELD_END, "");
        if (!wake.isBlank() && wake.trim().equals(end.trim())) {
            problems.add(new Problem("结束词", end, "不能与唤醒词相同"));
        }
        return new Result(problems, checked, spelled);
    }

    /**
     * 拆字判定失败不能让校验整个崩掉。
     *
     * <p>「能不能拆」是**附加**判断：它抛异常说明引擎出了问题，而这个词本身
     * 已经确知不在表内了，按不合法处理是正确且安全的方向（比放行一个
     * 可能失效的配置好）。
     */
    private static List<String> safeSpellCheck(SpellCheck spellCheck, String word) {
        try {
            List<String> tokens = spellCheck.spell(word);
            return tokens == null ? List.of() : tokens;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 便捷：直接校验唤醒词与结束词。 */
    public static Result validate(WordLookup lookup, String wakeWord, String endWord) {
        return validate(lookup, wakeWord, endWord, null);
    }

    /** 便捷：直接校验唤醒词与结束词，并允许唤醒词逐字拆。 */
    public static Result validate(WordLookup lookup, String wakeWord, String endWord,
            SpellCheck spellCheck) {
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put(com.talkinglive.core.WordSuggestions.FIELD_WAKE, wakeWord);
        pairs.put(com.talkinglive.core.WordSuggestions.FIELD_END, endWord);
        return validate(lookup, pairs, spellCheck);
    }

    private static String firstSuggestion(String field) {
        List<String> s = SUGGESTIONS.get(field);
        return s == null || s.isEmpty() ? null : String.join(" / ", s);
    }
    /** 一个总是通过的查询器，用于离线测试与非 Vosk 引擎。 */
    public static WordLookup acceptAll() {
        return word -> true;
    }

    /**
     * 按给定词表判定的查询器，用于单测与「引擎不可用」时的保守行为。
     *
     * <p>词表为 null 表示「无法校验」——此时**保守地判定为通过**，
     * 因为把「查不了」说成「词不合法」会把用户引向错误的方向。
     */
    public static WordLookup ofVocabulary(java.util.Collection<String> vocabulary) {
        if (vocabulary == null) {
            return acceptAll();
        }
        var set = java.util.Set.copyOf(vocabulary);
        return set::contains;
    }
}
