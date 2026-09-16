package com.talkinglive.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.talkinglive.core.JsonCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 引擎侧**不需要模型**的那部分逻辑测试（{@code DESIGN.md} §9.1 的纯逻辑层）。
 *
 * <p>受限语法的构建是这一层里最值得测的东西：它的**形状**（是纯数组还是
 * {@code phrase_list} 对象、词的顺序、有没有 {@code [unk]}）直接决定唤醒功能是否可用，
 * 而这三件事都能在无模型、无麦克风的环境下验证。实测已经在这里踩过两次坑：
 * 传对象进去会让 Vosk 报 {@code Expecting array of strings} 并崩在原生层。
 */
class VoskGrammarTest {

    @Nested
    @DisplayName("受限语法构建（唤醒词检测的前提）")
    class Grammar {

        @Test
        @DisplayName("格式是**纯 JSON 数组**，不是 {\"phrase_list\": [...]} 对象")
        void isPlainArray() {
            String g = VoskKeywordDetector.buildGrammar("子曰", "到此为止");
            assertEquals("[\"子曰\",\"到此为止\",\"[unk]\"]", g);
            assertFalse(g.contains("phrase_list"),
                    "Vosk 的 C API 只接受纯数组；Python 绑定的 phrase_list 是它自己拆的壳");
        }

        @Test
        @DisplayName("能被自带的 JsonCodec 解析成字符串数组（语法本身是合法 JSON）")
        void parsesAsJsonArray() {
            String g = VoskKeywordDetector.buildGrammar("子曰", "到此为止");
            Object parsed = JsonCodec.parse(g);
            assertTrue(parsed instanceof List, "应为数组，实际 " + parsed.getClass());
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) parsed;
            assertEquals(3, list.size());
            assertEquals("子曰", list.get(0));
            assertEquals("到此为止", list.get(1));
            assertEquals("[unk]", list.get(2));
        }

        @Test
        @DisplayName("唤醒词排在第一位（受限语法下引擎偏向靠前的词）")
        void wakeWordFirst() {
            String g = VoskKeywordDetector.buildGrammar("小助手", "完毕");
            assertTrue(g.indexOf("小助手") < g.indexOf("完毕"), g);
        }

        @Test
        @DisplayName("必须包含 [unk]（语法外的语音要有去处）")
        void includesUnk() {
            assertTrue(VoskKeywordDetector.buildGrammar("子曰", "到此为止").contains("[unk]"));
            assertTrue(VoskKeywordDetector.buildGrammar("子曰", "").contains("[unk]"));
        }

        @Test
        @DisplayName("结束词为空时只放唤醒词与 [unk]")
        void emptyEndWord() {
            assertEquals("[\"子曰\",\"[unk]\"]", VoskKeywordDetector.buildGrammar("子曰", ""));
            assertEquals("[\"子曰\",\"[unk]\"]", VoskKeywordDetector.buildGrammar("子曰", null));
        }

        @Test
        @DisplayName("唤醒词为空时只放结束词与 [unk]")
        void emptyWakeWord() {
            assertEquals("[\"到此为止\",\"[unk]\"]", VoskKeywordDetector.buildGrammar("", "到此为止"));
        }

        @Test
        @DisplayName("两个词相同时只放一次（重复词条会污染语言模型估计）")
        void duplicateWordsDeduped() {
            String g = VoskKeywordDetector.buildGrammar("结束", "结束");
            assertEquals("[\"结束\",\"[unk]\"]", g);
        }

        @Test
        @DisplayName("两端空白被裁掉")
        void trims() {
            assertEquals("[\"子曰\",\"到此为止\",\"[unk]\"]",
                    VoskKeywordDetector.buildGrammar("  子曰  ", " 到此为止 "));
        }

        @Test
        @DisplayName("含引号或反斜杠的词被正确转义（语法仍是合法 JSON）")
        void escapesSpecialChars() {
            String g = VoskKeywordDetector.buildGrammar("a\"b", "c\\d");
            Object parsed = JsonCodec.parse(g);
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) parsed;
            assertEquals("a\"b", list.get(0));
            assertEquals("c\\d", list.get(1));
        }

        @Test
        @DisplayName("含控制字符的词也不会破坏 JSON")
        void escapesControlChars() {
            String g = VoskKeywordDetector.buildGrammar("a\nb", "c");
            Object parsed = JsonCodec.parse(g);
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) parsed;
            assertEquals("a\nb", list.get(0));
        }

        @Test
        @DisplayName("两个词都为空时只有 [unk]（调用方应先被配置校验拦住）")
        void bothEmpty() {
            assertEquals("[\"[unk]\"]", VoskKeywordDetector.buildGrammar("", ""));
        }
    }

    @Nested
    @DisplayName("单字序列语法（英语唤醒词靠这一段）")
    class SequencedGrammar {

        @Test
        @DisplayName("唤醒词被拆成单字时按顺序逐个放进语法")
        void spelledWakeWordBecomesTokenSequence() {
            // 实测（docs/ENGINE-EXPERIMENT.md §7.5）：["飞瑞",...] 说「飞瑞」得到 [unk]，
            // 而 ["飞","瑞",...] 得到「飞瑞」——整词不在词表内时只能逐字拆。
            assertEquals("[\"飞\",\"瑞\",\"到此为止\",\"[unk]\"]",
                    VoskKeywordDetector.buildGrammar(List.of("飞", "瑞"), List.of("到此为止")));
        }

        @Test
        @DisplayName("重复的单字**必须保留**（「飞飞飞」压成一个就是唤醒词失效）")
        void repeatedTokensKept() {
            // 实测抓出的 bug：早期按 token 值去重，把 ["飞","飞","飞"] 压成 ["飞"]，
            // 语法塌成 ["飞","[unk]"] 后说「飞飞飞」永远不命中——静默失效。
            String g = VoskKeywordDetector.buildGrammar(List.of("飞", "飞", "飞"), List.of());
            assertEquals("[\"飞\",\"飞\",\"飞\",\"[unk]\"]", g);
        }

        @Test
        @DisplayName("结束词与唤醒词撞同一个 token 时只放一次（跨组去重仍要保留）")
        void crossGroupDeduped() {
            // 唤醒词「飞瑞」拆出「瑞」，结束词正好也是「瑞」：重复词条会污染
            // 语言模型估计，所以跨组要去重。与组内保留并不矛盾——
            // 组内重复是"用户要说两次"，跨组重复是"同一个词条写了两遍"。
            assertEquals("[\"飞\",\"瑞\",\"[unk]\"]",
                    VoskKeywordDetector.buildGrammar(List.of("飞", "瑞"), List.of("瑞")));
        }

        @Test
        @DisplayName("token 序列里的空白与空串被清理")
        void tokenSequenceSanitised() {
            assertEquals("[\"飞\",\"瑞\",\"[unk]\"]",
                    VoskKeywordDetector.buildGrammar(
                            java.util.Arrays.asList(" 飞 ", null, "", "瑞"), null));
        }

        @Test
        @DisplayName("null 序列等价于空序列（不抛异常）")
        void nullSequences() {
            assertEquals("[\"飞\",\"[unk]\"]",
                    VoskKeywordDetector.buildGrammar(List.of("飞"), (List<String>) null));
            assertEquals("[\"[unk]\"]",
                    VoskKeywordDetector.buildGrammar((List<String>) null, null));
        }

        @Test
        @DisplayName("token 里的引号仍被转义（语法必须是合法 JSON）")
        void tokenEscaping() {
            String g = VoskKeywordDetector.buildGrammar(List.of("飞"), List.of("a\"b"));
            Object parsed = JsonCodec.parse(g);
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) parsed;
            assertEquals("飞", list.get(0));
            assertEquals("a\"b", list.get(1));
        }
    }

    @Nested
    @DisplayName("词表校验异常（附录 C 的静默失效）")
    class VocabularyException {

        @Test
        @DisplayName("消息里列出所有不合法的词与**按字段区分**的备选建议")
        void messageListsAllProblems() {
            var e = new VoskKeywordDetector.VocabularyException(Map.of("结束词", "本段结束"));
            assertTrue(e.getMessage().contains("本段结束"), e.getMessage());
            assertTrue(e.getMessage().contains("静默忽略"), e.getMessage());
            assertTrue(e.getMessage().contains("到此为止"), "应给出结束词的备选：" + e.getMessage());
            assertFalse(e.getMessage().contains("小助手"),
                    "结束词出问题不该推荐唤醒词的备选（曾经就是这么写错的）：" + e.getMessage());
            assertEquals(1, e.unknownWords().size());
            assertEquals("本段结束", e.unknownWords().get("结束词"));
        }

        @Test
        @DisplayName("唤醒词出问题时推荐的是唤醒词备选，不是结束词备选")
        void adviceIsFieldSpecific() {
            var e = new VoskKeywordDetector.VocabularyException(Map.of("唤醒词", "小秘书"));
            assertTrue(e.getMessage().contains("小助手"), "唤醒词应推荐唤醒词备选：" + e.getMessage());
            assertFalse(e.getMessage().contains("到此为止"),
                    "唤醒词出问题不该推荐结束词的备选：" + e.getMessage());
        }

        @Test
        @DisplayName("两个字段都不合格时两条建议都给出")
        void adviceForBothFields() {
            var e = new VoskKeywordDetector.VocabularyException(
                    new java.util.LinkedHashMap<>(Map.of("唤醒词", "小秘书", "结束词", "本段结束")));
            assertTrue(e.getMessage().contains("小助手"), e.getMessage());
            assertTrue(e.getMessage().contains("到此为止"), e.getMessage());
        }

        @Test
        @DisplayName("unknownWords 不可被外部修改")
        void unknownWordsImmutable() {
            var e = new VoskKeywordDetector.VocabularyException(Map.of("唤醒词", "小秘书"));
            // 注意用 ofEntries：写 Map.of("结束词", "x") 会被 javac 解析成 Map<String, String[]>，
            // 编译期就报错（实测踩过）
            org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                    () -> e.unknownWords().put("结束词", "x"));
        }
    }

    @Nested
    @DisplayName("结果 JSON 解析（不依赖引擎）")
    class ResultParsing {

        @Test
        @DisplayName("取出 text 字段并去掉中文词间空格")
        void extractsAndJoins() {
            assertEquals("今天天气不错", VoskModel.Recognizer.textOf("{\"text\" : \"今天 天气 不错\"}"));
        }

        @Test
        @DisplayName("缺少 text 字段返回空串")
        void missingTextField() {
            assertEquals("", VoskModel.Recognizer.textOf("{\"partial\" : \"abc\"}"));
            assertEquals("", VoskModel.Recognizer.textOf("{}"));
        }

        @Test
        @DisplayName("空文本、null、空串、非法 JSON 都返回空串而不抛异常（音频线程不能死）")
        void malformedInputsAreSafe() {
            assertEquals("", VoskModel.Recognizer.textOf("{\"text\":\"\"}"));
            assertEquals("", VoskModel.Recognizer.textOf(null));
            assertEquals("", VoskModel.Recognizer.textOf(""));
            assertEquals("", VoskModel.Recognizer.textOf("not json at all"));
            assertEquals("", VoskModel.Recognizer.textOf("{unterminated"));
        }

        @Test
        @DisplayName("英文结果里词间空格保留（不能把 hello world 粘成一个词）")
        void englishSpacesKept() {
            assertEquals("hello world", VoskModel.Recognizer.textOf("{\"text\":\"hello world\"}"));
        }
    }

    @Nested
    @DisplayName("精化结果封装（§7 的降级路径）")
    class RefinerResult {

        @Test
        @DisplayName("精化成功时带耗时与引擎名，refined=true")
        void ok() {
            TextRefiner.Result r = TextRefiner.Result.ok("今天天气不错。", 480, "SenseVoice");
            assertTrue(r.refined());
            assertEquals("今天天气不错。", r.text());
            assertEquals(480, r.millis());
            assertEquals("SenseVoice", r.engine());
            assertEquals(null, r.note());
        }

        @Test
        @DisplayName("降级时 refined=false、带原因，文本来自预览")
        void fallback() {
            TextRefiner.Result r = TextRefiner.Result.fallback("预览文本", "whisper.cpp", "推理超时");
            assertFalse(r.refined());
            assertEquals("预览文本", r.text());
            assertEquals(0, r.millis());
            assertEquals("推理超时", r.note());
        }

        @Test
        @DisplayName("降级时 null 预览文本变成空串")
        void fallbackNull() {
            assertEquals("", TextRefiner.Result.fallback(null, "e", "原因").text());
        }

        @Test
        @DisplayName("RTF 计算：5 秒音频用 500ms → 0.1（TECH-PLAN §4.3 的 SenseVoice 目标）")
        void rtf() {
            assertEquals(0.1, TextRefiner.Result.ok("x", 500, "e").rtf(5.0), 1e-9);
        }

        @Test
        @DisplayName("未精化或音频时长为 0 时 RTF 为 0，不产生除零")
        void rtfEdgeCases() {
            assertEquals(0.0, TextRefiner.Result.fallback("x", "e", "r").rtf(5.0));
            assertEquals(0.0, TextRefiner.Result.ok("x", 500, "e").rtf(0));
            assertEquals(0.0, TextRefiner.Result.ok("x", 500, "e").rtf(-1));
        }
    }

    @Nested
    @DisplayName("不可用精化引擎（显式降级，不假装成功）")
    class UnavailableRefiner {

        @Test
        @DisplayName("不可用时如实报告并退回预览文本")
        void reportsUnavailable() {
            TextRefiners.Unavailable u = new TextRefiners.Unavailable("SenseVoice", "模型缺失");
            assertFalse(u.available());
            assertEquals("模型缺失", u.unavailableReason());
            TextRefiner.Result r = u.refine(new byte[32000], "预览文本", false);
            assertFalse(r.refined());
            assertEquals("预览文本", r.text());
            assertEquals("模型缺失", r.note());
            assertTrue(u.describe().contains("不可用"), u.describe());
        }

        @Test
        @DisplayName("关闭后仍是可用性 false 而不是抛异常")
        void closeIsSafe() {
            TextRefiners.Unavailable u = new TextRefiners.Unavailable("e", "r");
            u.close();
            assertFalse(u.available());
        }
    }
}
