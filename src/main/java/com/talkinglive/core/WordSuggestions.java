package com.talkinglive.core;

import java.util.List;
import java.util.Map;

/**
 * 唤醒词 / 结束词的候选词与备选建议。
 *
 * <p>数据来源是 {@code DESIGN.md} 附录 B.1 的**实测结论**——用一个脚本对
 * {@code vosk-model-small-cn-0.22} 的词表逐词查询得来。把它固化成代码里的常量，
 * 是因为 Step 0 的验证脚本留在 {@code %TEMP%} 里已经丢了，而这两个结论**不该再被重新发现**：
 *
 * <ul>
 *   <li>「本段结束」<b>不在</b>词表内——这是当初把结束词改成「到此为止」的原因。</li>
 *   <li>「小秘书」「小听写」「语音助手」也不在，所以它们出现在备选列表里会误导用户。</li>
 * </ul>
 *
 * <p>放在 {@code core} 而不是 {@code system}/{@code engine}：它是**领域常量**，
 * 与「怎么查词表」无关。{@code MicValidator}（system 层）与
 * {@code VoskKeywordDetector}（engine 层）都要用它，放在 core 才不会造成
 * 「引擎依赖 system」这种别扭的依赖方向。
 */
public final class WordSuggestions {

    /** 字段名常量，避免各处手写字符串不一致。 */
    public static final String FIELD_WAKE = "唤醒词";
    public static final String FIELD_END = "结束词";

    /**
     * 字段 → 可用备选（全部经附录 B.1 实测在词表内）。
     *
     * <p>只放**确认在表内**的词。给用户一个同样不在表内的备选，比不给更糟。
     */
    /**
     * 字段 → 可用备选。
     *
     * <p>只放**确认可用**的词。给用户一个同样用不了的备选，比不给更糟。
     *
     * <p>「飞瑞」是**逐字可用**的例子（不是整词）：这两个字各自都在词表内，
     * 所以能被拆成单字序列 {@code ["飞","瑞"]} 使用——实测说「飞瑞」能稳定命中
     * （{@code docs/ENGINE-EXPERIMENT.md} §7.5）。它给英语唤醒词铺路：
     * 中文模型发不出 {@code Firay}（词表内拉丁 token 为 0 个），
     * 但「飞瑞」听起来就是那个音。摆在候选里，用户才知道有这条路。
     */
    public static final Map<String, List<String>> BY_FIELD = Map.of(
            FIELD_WAKE, List.of("子曰", "小助手", "飞瑞", "子", "曰"),
            FIELD_END, List.of("到此为止", "结束", "完毕", "输入"));

    /**
     * 实测**不在**词表内、且**拆成单字也不该用**的词。
     *
     * <p>后一条容易漏：这些词的每个字单独都在表内，逐字拆看起来可行，
     * 但拆成单字后要连着说对每一个字才算命中，真实语音里几乎不会稳定触发。
     * 拿它当唤醒词等于给用户一个"看起来能用、永远不会响"的配置 —— 这正是当初
     * 逐词实测后把结束词改成「到此为止」的理由，不该因为新增了拆字能力就绕过去
     * （{@code engine.WakePhrase.resolve} 会显式拦住）。
     */
    public static final List<String> KNOWN_ABSENT = List.of(
            "本段结束", "小秘书", "小听写", "语音助手", "说完了", "好了", "结束输入");

    private WordSuggestions() {}

    /** 某字段的备选列表；未知字段返回空列表。 */
    public static List<String> forField(String field) {
        return BY_FIELD.getOrDefault(field, List.of());
    }

    /** 某字段备选的可读串，例如 {@code 到此为止 / 结束 / 完毕 / 输入}。 */
    public static String describe(String field) {
        List<String> list = forField(field);
        return list.isEmpty() ? "" : String.join(" / ", list);
    }

    /**
     * 依据**哪些词不合法**给出一句建议。
     *
     * <p>必须按字段分别给建议：给「唤醒词」问题推荐「到此为止」是误导
     * （单测里就是这么发现这个 bug 的）。
     */
    public static String adviceFor(Map<String, String> unknownFields) {
        if (unknownFields == null || unknownFields.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String field : unknownFields.keySet()) {
            String options = describe(field);
            if (options.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(field).append("可改用：").append(options);
        }
        return sb.toString();
    }
}
