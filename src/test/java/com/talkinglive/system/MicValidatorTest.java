package com.talkinglive.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 词表校验测试 —— {@code DESIGN.md} 附录 C 要求「词表校验逻辑以单元测试形式固化进仓库」。
 *
 * <p>这一层存在的唯一理由是 Vosk 对词表外的词**静默忽略**：语法能建起来、不抛异常，
 * 但那个词永远不会被识别到。用户的感受是「改了配置没反应」。
 * 因此这里的用例重点不是「通过」，而是**失败路径要说清楚**。
 *
 * <p>查询能力通过 {@link MicValidator.WordLookup} 注入，所以整组测试不需要模型与麦克风。
 */
class MicValidatorTest {

    /** 模拟附录 B.1 实测过的词表。 */
    private static final Set<String> VOCAB = Set.of(
            "子曰", "小助手", "子", "曰",
            "到此为止", "结束", "完毕", "输入");

    private static MicValidator.WordLookup vocab() {
        return MicValidator.ofVocabulary(VOCAB);
    }

    @Nested
    @DisplayName("通过的情况")
    class Passing {

        @Test
        @DisplayName("附录 B.1 采用的唤醒词与结束词都通过")
        void adoptedWordsPass() {
            MicValidator.Result r = MicValidator.validate(vocab(), "子曰", "到此为止");
            assertTrue(r.ok(), r.message());
            assertEquals(2, r.checked());
            assertNull(r.message(), "通过时不该有消息");
            assertTrue(r.problems().isEmpty());
        }

        @Test
        @DisplayName("附录 B.1 的备选词也都通过")
        void alternativesPass() {
            assertTrue(MicValidator.validate(vocab(), "小助手", "完毕").ok());
            assertTrue(MicValidator.validate(vocab(), "子", "结束").ok());
        }

        @Test
        @DisplayName("acceptAll 永远通过（非 Vosk 引擎 / 无法校验时的保守行为）")
        void acceptAll() {
            assertTrue(MicValidator.validate(MicValidator.acceptAll(), "任意词", "随便什么").ok());
        }

        @Test
        @DisplayName("词表为 null 时保守判定通过（不把「查不了」说成「词不合法」）")
        void nullVocabularyIsPermissive() {
            assertTrue(MicValidator.validate(MicValidator.ofVocabulary(null), "任意词", "随便").ok());
        }
    }

    @Nested
    @DisplayName("失败的情况（附录 C 的静默失效必须被拦住）")
    class Failing {

        @Test
        @DisplayName("附录 B.1 明确记录为「不在表内」的结束词被拦住")
        void outOfVocabularyEndWordCaught() {
            MicValidator.Result r = MicValidator.validate(vocab(), "子曰", "本段结束");
            assertFalse(r.ok());
            assertEquals(1, r.problems().size());
            MicValidator.Problem p = r.problems().get(0);
            assertEquals("结束词", p.field());
            assertEquals("本段结束", p.word());
        }

        @Test
        @DisplayName("附录 B.1 的其它表外词也都被拦住")
        void allOutOfVocabularyWordsCaught() {
            for (String w : List.of("小秘书", "小听写", "语音助手", "好了", "说完了", "结束输入")) {
                MicValidator.Result r = MicValidator.validate(vocab(), w, "到此为止");
                assertFalse(r.ok(), w + " 应被判为不在词表内");
            }
        }

        @Test
        @DisplayName("消息是面向用户的：说明后果 + 给出备选词")
        void messageIsActionable() {
            String msg = MicValidator.validate(vocab(), "子曰", "本段结束").message();
            assertNotNull(msg);
            assertTrue(msg.contains("静默忽略"), msg);
            assertTrue(msg.contains("本段结束"), msg);
            assertTrue(msg.contains("到此为止"), "必须给出可用的备选词：" + msg);
        }

        @Test
        @DisplayName("两个词都不合格时两条都报")
        void bothProblematicReported() {
            MicValidator.Result r = MicValidator.validate(vocab(), "小秘书", "本段结束");
            assertFalse(r.ok());
            assertEquals(2, r.problems().size());
            assertEquals(List.of("唤醒词", "结束词"), r.problemFields());
        }

        @Test
        @DisplayName("空词被判为不合法，并提示备选")
        void emptyWordsRejected() {
            MicValidator.Result r = MicValidator.validate(vocab(), "", "");
            assertFalse(r.ok());
            assertEquals(2, r.problems().size());
            assertEquals("(空)", r.problems().get(0).word());
        }

        @Test
        @DisplayName("唤醒词与结束词相同时报错（不会静默失效，但会立刻自我结束）")
        void identicalWordsRejected() {
            MicValidator.Result r = MicValidator.validate(vocab(), "结束", "结束");
            assertFalse(r.ok());
            assertTrue(r.problems().stream().anyMatch(p -> p.describe().contains("不能与唤醒词相同")));
        }

        @Test
        @DisplayName("Problem.describe 把字段名、词、建议串成一句可读的话")
        void problemDescribe() {
            MicValidator.Problem p = MicValidator.validate(vocab(), "子曰", "本段结束")
                    .problems().get(0);
            String d = p.describe();
            assertTrue(d.contains("结束词"), d);
            assertTrue(d.contains("本段结束"), d);
            assertTrue(d.contains("到此为止"), d);
        }

        @Test
        @DisplayName("词前后的空白被裁掉后再校验")
        void whitespaceTrimmed() {
            assertTrue(MicValidator.validate(vocab(), "  子曰  ", " 到此为止 ").ok());
        }
    }

    @Nested
    @DisplayName("查询能力本身出问题时不能误导用户")
    class LookupFailures {

        @Test
        @DisplayName("查询抛异常时向上抛，而不是当成「词不合法」")
        void lookupErrorPropagates() {
            MicValidator.WordLookup broken = word -> {
                throw new IllegalStateException("原生库挂了");
            };
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> MicValidator.validate(broken, "子曰", "到此为止"));
            assertTrue(e.getMessage().contains("原生库"));
        }
    }

    @Nested
    @DisplayName("批量 Map 形式")
    class MapForm {

        @Test
        @DisplayName("按 field -> word 的 Map 校验并保留字段名")
        void mapFormKeepsFieldNames() {
            Map<String, String> pairs = new LinkedHashMap<>();
            pairs.put("唤醒词", "子曰");
            pairs.put("结束词", "本段结束");
            MicValidator.Result r = MicValidator.validate(vocab(), pairs);
            assertFalse(r.ok());
            assertEquals("结束词", r.problems().get(0).field());
        }

        @Test
        @DisplayName("checked 数出真正查过的词（空词不算查过）")
        void checkedCount() {
            Map<String, String> pairs = new LinkedHashMap<>();
            pairs.put("唤醒词", "子曰");
            pairs.put("结束词", "");
            MicValidator.Result r = MicValidator.validate(vocab(), pairs);
            assertEquals(1, r.checked());
        }
    }

    @Nested
    @DisplayName("备选词表（附录 B.1 的实测结论固化在这里）")
    class Suggestions {

        @Test
        @DisplayName("唤醒词备选包含「子曰」与「小助手」")
        void wakeSuggestions() {
            List<String> s = MicValidator.SUGGESTIONS.get("唤醒词");
            assertTrue(s.contains("子曰"));
            assertTrue(s.contains("小助手"));
        }

        @Test
        @DisplayName("结束词备选包含「到此为止」，且**不含**表外的「本段结束」")
        void endSuggestions() {
            List<String> s = MicValidator.SUGGESTIONS.get("结束词");
            assertTrue(s.contains("到此为止"));
            assertTrue(s.contains("结束"));
            assertTrue(s.contains("完毕"));
            assertFalse(s.contains("本段结束"), "备选里不能出现已知不在词表内的词");
        }

        @Test
        @DisplayName("唤醒词备选里有「飞瑞」——英语唤醒词的示范写法")
        void spelledWakeSuggestionPresent() {
            // 「飞瑞」整体不在词表内，但两个字各自都在，所以可拆成 ["飞","瑞"] 使用。
            // 它摆在候选里不是凑数：中文模型发不出 Firay（词表内拉丁 token 为 0 个），
            // 用户需要看到一个"英语词写成汉字"的实例才知道这条路存在。
            assertTrue(MicValidator.SUGGESTIONS.get("唤醒词").contains("飞瑞"));
        }
    }

    @Nested
    @DisplayName("逐字拆开的唤醒词（英语唤醒词的落地方式）")
    class SpelledWakeWord {

        /** 「飞」与「瑞」都在表内，但「飞瑞」这个整词不在。 */
        private static final Set<String> SPELLABLE_VOCAB = Set.of(
                "飞", "瑞", "子曰", "小助手", "到此为止", "结束");

        private static MicValidator.WordLookup lookup() {
            return MicValidator.ofVocabulary(SPELLABLE_VOCAB);
        }

        /** 与生产代码同形状的判定：整词不在表内、但每个字都在。 */
        private static MicValidator.SpellCheck speller() {
            return word -> word.codePoints()
                    .mapToObj(cp -> new String(Character.toChars(cp)))
                    .allMatch(SPELLABLE_VOCAB::contains);
        }

        @Test
        @DisplayName("拆得开的唤醒词**通过**校验（否则英语唤醒词根本存不下去）")
        void spelledWakeWordPasses() {
            MicValidator.Result r = MicValidator.validate(
                    lookup(), "飞瑞", "到此为止", speller());
            assertTrue(r.ok(), "逐字可用的唤醒词不该被拦：" + r.message());
            assertEquals(0, r.problems().size());
        }

        @Test
        @DisplayName("通过但**必须**留下提示：用户要知道自己被拆成了哪几个字")
        void spelledWakeWordLeavesNotice() {
            MicValidator.Result r = MicValidator.validate(
                    lookup(), "飞瑞", "到此为止", speller());
            assertNotNull(r.message(), "拆字是成功但有话要说，不能静默");
            assertEquals(1, r.spelled().size());
            assertTrue(r.spelled().get(0).describe().contains("飞瑞"), r.spelled().get(0).describe());
            assertTrue(r.spelled().get(0).describe().contains("单字拆开"),
                    r.spelled().get(0).describe());
            // 关键：不能被说成"永远不会被识别到"——它明明能用。
            assertFalse(r.spelled().get(0).describe().contains("永远不会被识别到"),
                    r.spelled().get(0).describe());
        }

        @Test
        @DisplayName("不传拆字能力时按整词严格校验（调用方没接引擎时的保守行为）")
        void withoutSpellCheckStaysStrict() {
            MicValidator.Result r = MicValidator.validate(lookup(), "飞瑞", "到此为止");
            assertFalse(r.ok());
            assertEquals("唤醒词", r.problems().get(0).field());
            assertTrue(r.spelled().isEmpty());
        }

        @Test
        @DisplayName("**结束词**即使逐字可用也不拆（后果是误停止录音，代价太大）")
        void endWordNeverSpelled() {
            // 「结束」是整词在表内；换成「完毕」也在。这里用一个整词不在、
            // 但字字都在的结束词来验证结束词不会走拆字通道。
            MicValidator.Result r = MicValidator.validate(
                    lookup(), "子曰", "飞瑞", speller());
            assertFalse(r.ok(), "结束词不该允许拆字");
            assertEquals("结束词", r.problems().get(0).field());
            assertTrue(r.spelled().isEmpty(), "spelled 只应包含唤醒词");
        }

        @Test
        @DisplayName("拆字判定抛异常时按不合法处理（不能放行一个可能失效的配置）")
        void spellCheckFailureIsSafe() {
            MicValidator.SpellCheck boom = word -> {
                throw new IllegalStateException("原生库挂了");
            };
            MicValidator.Result r = MicValidator.validate(lookup(), "飞瑞", "到此为止", boom);
            assertFalse(r.ok());
            assertEquals("唤醒词", r.problems().get(0).field());
        }

        @Test
        @DisplayName("拆不开的词仍然被拦住（逐字可用不是万能通行证）")
        void genuinelyBadWordStillRejected() {
            MicValidator.Result r = MicValidator.validate(
                    lookup(), "小秘书", "到此为止", speller());
            assertFalse(r.ok(), "「小秘书」的「秘」「书」不在表内，拆不开");
            assertEquals("唤醒词", r.problems().get(0).field());
        }

        @Test
        @DisplayName("没有拆字提示时 message 为 null（成功就是成功，不该有噪声）")
        void plainSuccessHasNoMessage() {
            MicValidator.Result r = MicValidator.validate(
                    lookup(), "小助手", "到此为止", speller());
            assertTrue(r.ok());
            assertNull(r.message());
            assertTrue(r.spelled().isEmpty());
        }
    }
}
