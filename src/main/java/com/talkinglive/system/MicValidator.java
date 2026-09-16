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
     * 词表外的常用备选（{@code DESIGN.md} 附录 B.1 的实测结论）。
     *
     * <p>写进提示里是因为用户第一次配置时**不可能知道**模型的词表里有什么——
     * 而「本段结束」不在表内这件事恰恰是 Step 0 踩出来的。
     */
    public static final Map<String, List<String>> SUGGESTIONS = Map.of(
            "唤醒词", List.of("子曰", "小助手", "子", "曰"),
            "结束词", List.of("到此为止", "结束", "完毕", "输入"));

    private MicValidator() {}

    /** 一个不合法的词。 */
    public record Problem(String field, String word, String suggestion) {

        public String describe() {
            String s = field + "「" + word + "」不在 Vosk 模型词表内，永远不会被识别到";
            return suggestion == null || suggestion.isBlank() ? s : s + "；可改用：" + suggestion;
        }
    }

    /** 校验结果。 */
    public record Result(List<Problem> problems, int checked) {

        public boolean ok() {
            return problems.isEmpty();
        }

        /** 面向用户的完整消息；通过时返回 null。 */
        public String message() {
            if (ok()) {
                return null;
            }
            StringBuilder sb = new StringBuilder("Vosk 对词表外的词是静默忽略的，以下配置永远不会生效：");
            for (Problem p : problems) {
                sb.append("\n  · ").append(p.describe());
            }
            return sb.toString();
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
        List<Problem> problems = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            String field = e.getKey();
            String word = e.getValue() == null ? "" : e.getValue().trim();
            if (word.isEmpty()) {
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
            if (!in) {
                problems.add(new Problem(field, word, firstSuggestion(field)));
            }
        }
        // 两个词相同时单独提示——它不会静默失效，但会立刻自我结束，同样属于
        // 「改了没反应」这一类困惑，放在同一个提示里一起说清楚。
        String wake = pairs.getOrDefault("唤醒词", "");
        String end = pairs.getOrDefault("结束词", "");
        if (!wake.isBlank() && wake.trim().equals(end.trim())) {
            problems.add(new Problem("结束词", end, "不能与唤醒词相同"));
        }
        return new Result(problems, checked);
    }

    /** 便捷：直接校验唤醒词与结束词。 */
    public static Result validate(WordLookup lookup, String wakeWord, String endWord) {
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("唤醒词", wakeWord);
        pairs.put("结束词", endWord);
        return validate(lookup, pairs);
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
