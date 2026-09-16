package com.talkinglive.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 文本工具测试（{@code DESIGN.md} §4.5：纯逻辑，可单测）。
 *
 * <p>码点相关的方法全部按**码点**而不是 UTF-16 长度工作——§4.3 明确要求这样做，
 * 理由是「避免代理对导致退格数算错」。这类 bug 在中文+emoji 混排时才会暴露，
 * 所以用例里专门放了代理对。
 */
class TextUtilsTest {

    @Nested
    @DisplayName("最长公共码点前缀")
    class CommonPrefix {

        @Test
        @DisplayName("相同字符串的公共前缀是全长")
        void identical() {
            // 「这个需求很明确」是 7 个字符
            assertEquals(7, TextUtils.commonPrefixCodePoints("这个需求很明确", "这个需求很明确"));
            assertEquals(4, TextUtils.commonPrefixCodePoints("今天天气", "今天天气"));
        }

        @Test
        @DisplayName("引擎改写尾部：公共前缀停在分歧处")
        void engineRevisesTail() {
            // demo README 里的真实例子：流式引擎会把已吐出的字改回来
            assertEquals(3, TextUtils.commonPrefixCodePoints("这个需球", "这个需求"));
        }

        @Test
        @DisplayName("完全不相关时为 0")
        void disjoint() {
            assertEquals(0, TextUtils.commonPrefixCodePoints("abc", "xyz"));
        }

        @Test
        @DisplayName("空串与 null 都返回 0")
        void empties() {
            assertEquals(0, TextUtils.commonPrefixCodePoints("", "abc"));
            assertEquals(0, TextUtils.commonPrefixCodePoints("abc", ""));
            assertEquals(0, TextUtils.commonPrefixCodePoints(null, "abc"));
            assertEquals(0, TextUtils.commonPrefixCodePoints("abc", null));
        }

        @Test
        @DisplayName("前缀是另一个串时取较短的")
        void prefixOfLonger() {
            assertEquals(3, TextUtils.commonPrefixCodePoints("这个需", "这个需求很明确"));
        }

        @Test
        @DisplayName("代理对按 1 个码点计（不是 2）")
        void surrogatePairsCountAsOne() {
            String a = "a😀b";
            String b = "a😀c";
            assertEquals(2, TextUtils.commonPrefixCodePoints(a, b),
                    "a + 😀 是两个码点，UTF-16 长度是 3——这里必须按码点算");
        }

        @Test
        @DisplayName("代理对拆开时也能正确停止（不会把半个代理当相同）")
        void halfSurrogateHandled() {
            String a = "a😀";
            String b = "a\ud83d";   // 只有高代理
            assertEquals(1, TextUtils.commonPrefixCodePoints(a, b));
        }
    }

    @Nested
    @DisplayName("码点切分")
    class CodePointSlicing {

        @Test
        @DisplayName("prefixByCodePoints 不会切在代理对中间")
        void prefix() {
            String s = "a😀b";
            assertEquals("a", TextUtils.prefixByCodePoints(s, 1));
            assertEquals("a😀", TextUtils.prefixByCodePoints(s, 2));
            assertEquals("a😀b", TextUtils.prefixByCodePoints(s, 3));
            assertEquals("a😀b", TextUtils.prefixByCodePoints(s, 99));
            assertEquals("", TextUtils.prefixByCodePoints(s, 0));
            assertEquals("", TextUtils.prefixByCodePoints(s, -1));
        }

        @Test
        @DisplayName("dropCodePoints 不会切在代理对中间")
        void drop() {
            String s = "a😀b";
            assertEquals("😀b", TextUtils.dropCodePoints(s, 1));
            assertEquals("b", TextUtils.dropCodePoints(s, 2));
            assertEquals("", TextUtils.dropCodePoints(s, 3));
            assertEquals("", TextUtils.dropCodePoints(s, 99));
            assertEquals(s, TextUtils.dropCodePoints(s, 0));
        }

        @Test
        @DisplayName("suffixByCodePoints 取尾部")
        void suffix() {
            assertEquals("b", TextUtils.suffixByCodePoints("a😀b", 1));
            assertEquals("😀b", TextUtils.suffixByCodePoints("a😀b", 2));
            assertEquals("", TextUtils.suffixByCodePoints("a😀b", 0));
        }

        @Test
        @DisplayName("codePointCount 与 String.length 在代理对上不同")
        void countDiffersFromLength() {
            String s = "😀";
            assertEquals(1, TextUtils.codePointCount(s));
            assertEquals(2, s.length());
        }
    }

    @Nested
    @DisplayName("流式 token 拼接")
    class StreamTokens {

        @Test
        @DisplayName("中文词之间的空格被去掉（Vosk 按词输出带空格）")
        void chineseSpacesRemoved() {
            assertEquals("今天天气不错", TextUtils.joinStreamTokens("今天 天气 不错"));
        }

        @Test
        @DisplayName("英文词之间的空格保留")
        void englishSpacesKept() {
            assertEquals("hello world", TextUtils.joinStreamTokens("hello world"));
        }

        @Test
        @DisplayName("中英混排：英文之间留空格，中文之间不留")
        void mixed() {
            assertEquals("用Java写代码", TextUtils.joinStreamTokens("用 Java 写 代码"));
        }

        @Test
        @DisplayName("数字与字母之间的空格保留")
        void digitsAndLetters() {
            assertEquals("abc 123", TextUtils.joinStreamTokens("abc 123"));
            assertEquals("第3章", TextUtils.joinStreamTokens("第 3 章"));
        }

        @Test
        @DisplayName("多余空白被压掉，首尾去空白")
        void extraWhitespaceCollapsed() {
            assertEquals("今天天气", TextUtils.joinStreamTokens("  今天   天气  "));
        }

        @Test
        @DisplayName("空串与 null 返回空串")
        void empties() {
            assertEquals("", TextUtils.joinStreamTokens(""));
            assertEquals("", TextUtils.joinStreamTokens(null));
            assertEquals("", TextUtils.joinStreamTokens("   "));
        }
    }

    @Nested
    @DisplayName("句末标点判断与清理")
    class PunctuationHelpers {

        @Test
        @DisplayName("中文与英文句末标点都识别")
        void sentenceEnds() {
            assertTrue(TextUtils.endsWithSentencePunctuation("你好。"));
            assertTrue(TextUtils.endsWithSentencePunctuation("真的吗？"));
            assertTrue(TextUtils.endsWithSentencePunctuation("太棒了！"));
            assertTrue(TextUtils.endsWithSentencePunctuation("hello."));
            assertTrue(TextUtils.endsWithSentencePunctuation("真的假的?"));
        }

        @Test
        @DisplayName("逗号不算句子结束")
        void commaIsNotSentenceEnd() {
            assertFalse(TextUtils.endsWithSentencePunctuation("你好，"));
            assertFalse(TextUtils.endsWithSentencePunctuation("没有标点"));
            assertFalse(TextUtils.endsWithSentencePunctuation(""));
            assertFalse(TextUtils.endsWithSentencePunctuation(null));
        }

        @Test
        @DisplayName("collapseWhitespace 压空白但不动全角标点")
        void collapse() {
            assertEquals("a b", TextUtils.collapseWhitespace("  a   b  "));
            assertEquals("你好，世界。", TextUtils.collapseWhitespace("你好，世界。"));
            assertEquals("", TextUtils.collapseWhitespace(null));
        }

        @Test
        @DisplayName("removeWord 按精确子串移除（唤醒词兜底）")
        void removeWord() {
            assertEquals("今天天气不错", TextUtils.removeWord("子曰今天天气不错", "子曰"));
            assertEquals("今天天气不错到此为止", TextUtils.removeWord("今天天气不错到此为止", "子曰"));
            assertEquals("今天天气不错", TextUtils.removeWord("今天天气不错到此为止", "到此为止"));
            assertEquals("今天天气不错", TextUtils.removeWord("今天天气不错", ""));
            assertEquals("", TextUtils.removeWord("", "子曰"));
        }

        @Test
        @DisplayName("英文句点也算句子结尾（不该在 hello. 后面再补一个。）")
        void englishPeriodIsSentenceEnd() {
            assertTrue(TextUtils.endsWithSentencePunctuation("hello."));
            assertTrue(TextUtils.endsWithSentencePunctuation("Done."));
            assertFalse(TextUtils.endsWithSentencePunctuation("hello"));
        }

        @Test
        @DisplayName("removeWord 只删完整的词，不误伤「词 + 别的字」")
        void removeWordIsExact() {
            // 「小助手们」里没有独立的「小助手」这个词——但它**是**子串，
            // 所以按精确子串移除会留下「们」。这是刻意的：注入前剔除唤醒词时
            // 宁可多删一个子串，也不能让正文出现「子曰」。
            assertEquals("们", TextUtils.removeWord("小助手们", "小助手"));
            // 而不含该子串的文本完全不动
            assertEquals("小助手们", TextUtils.removeWord("小助手们", "小秘书"));
            assertEquals("今天天气不错", TextUtils.removeWord("今天天气不错", "子曰"));
        }
    }

    @Nested
    @DisplayName("后处理：清理与补标点")
    class Processor {

        @Test
        @DisplayName("剔除唤醒词与结束词（TECH-PLAN §6.3 的正确性风险）")
        void stripsWakeAndEndWords() {
            PunctuationProcessor p = PunctuationProcessor.forWakeAndEndWords("子曰", "到此为止");
            assertEquals("今天天气不错。", p.process("子曰今天天气不错到此为止"));
        }

        @Test
        @DisplayName("引擎把唤醒词转成正文时也能清掉")
        void stripsWordsAnywhere() {
            PunctuationProcessor p = PunctuationProcessor.forWakeAndEndWords("子曰", "到此为止");
            assertEquals("开门见山说重点。", p.process("子曰开门见山到此为止说重点"));
        }

        @Test
        @DisplayName("末尾没标点时补一个句号")
        void addsEndPunctuation() {
            PunctuationProcessor p = PunctuationProcessor.forWakeAndEndWords("子曰", "到此为止");
            assertEquals("今天天气不错。", p.process("今天天气不错"));
        }

        @Test
        @DisplayName("已有标点时不重复添加")
        void doesNotDoublePunctuate() {
            PunctuationProcessor p = PunctuationProcessor.forWakeAndEndWords("子曰", "到此为止");
            assertEquals("真的吗？", p.process("真的吗？"));
            assertEquals("太好了！", p.process("太好了！"));
        }

        @Test
        @DisplayName("全角空格与不可见字符被清掉")
        void cleansInvisibleChars() {
            PunctuationProcessor p = PunctuationProcessor.forWakeAndEndWords("子曰", "到此为止");
            assertEquals("今天天气。", p.process("今天\u3000天气"));
            assertEquals("今天天气。", p.process("今天\uFEFF天气"));
        }

        @Test
        @DisplayName("空输入返回空串（调用方据此决定不注入）")
        void emptyInput() {
            PunctuationProcessor p = PunctuationProcessor.forWakeAndEndWords("子曰", "到此为止");
            assertEquals("", p.process(""));
            assertEquals("", p.process(null));
            assertEquals("", p.process("   "));
            assertEquals("", p.process("子曰"));
        }

        @Test
        @DisplayName("后处理链可以串联（将来的 HotwordCorrector 走同一条链）")
        void chainable() {
            TextPostProcessor upper = s -> s.toUpperCase();
            TextPostProcessor chain = upper.andThen(s -> s + "!");
            assertEquals("ABC!", chain.process("abc"));
        }

        @Test
        @DisplayName("identity 不改变内容")
        void identity() {
            assertEquals("abc", TextPostProcessor.identity().process("abc"));
            assertEquals("", TextPostProcessor.identity().process(null));
        }
    }

    @Nested
    @DisplayName("整段注入策略")
    class Policy {

        private final CommitPolicy policy = new WholeSegmentPolicy();

        @Test
        @DisplayName("首次注入：不退格，直接打整段")
        void firstInjection() {
            CommitPolicy.CommitPlan plan = policy.plan("", "今天天气不错。");
            assertEquals(0, plan.backspaces());
            assertEquals("今天天气不错。", plan.text());
            assertFalse(plan.isEmpty());
        }

        @Test
        @DisplayName("重复提交同一段：什么都不做（幂等）")
        void repeatedCommitIsIdempotent() {
            CommitPolicy.CommitPlan plan = policy.plan("今天天气不错。", "今天天气不错。");
            assertTrue(plan.isEmpty());
            assertEquals(0, plan.backspaces());
        }

        @Test
        @DisplayName("已注入过别的内容：按**码点**退格后再打新内容")
        void replacesPreviousInjection() {
            CommitPolicy.CommitPlan plan = policy.plan("旧内容", "新内容");
            assertEquals(3, plan.backspaces());
            assertEquals("新内容", plan.text());
            assertTrue(plan.hasBackspaces());
        }

        @Test
        @DisplayName("退格数按码点算，代理对只算一个（§4.3）")
        void backspacesUseCodePoints() {
            // 「a😀」是 2 个码点，但 UTF-16 长度是 3
            CommitPolicy.CommitPlan plan = policy.plan("a😀", "新内容");
            assertEquals(2, plan.backspaces(), "若按 UTF-16 长度算会退 3 格，多删掉一个字符");
        }

        @Test
        @DisplayName("空目标：只退格不打字")
        void emptyTargetStillClears() {
            CommitPolicy.CommitPlan plan = policy.plan("旧内容", "");
            assertEquals(3, plan.backspaces());
            assertEquals("", plan.text());
        }

        @Test
        @DisplayName("null 一律当空串处理")
        void nullsTreatedAsEmpty() {
            assertTrue(policy.plan(null, null).isEmpty());
            assertEquals(0, policy.plan(null, "内容").backspaces());
        }

        @Test
        @DisplayName("CommitPlan.none 是空动作")
        void nonePlan() {
            assertTrue(CommitPolicy.CommitPlan.none().isEmpty());
            assertFalse(CommitPolicy.CommitPlan.none().hasBackspaces());
        }
    }

    @Nested
    @DisplayName("预览两级文本模型（§4.4）")
    class Preview {

        @Test
        @DisplayName("新段落为空")
        void startsEmpty() {
            PreviewText p = new PreviewText();
            assertTrue(p.isEmpty());
            assertEquals("", p.committedText());
            assertEquals("", p.volatileSuffix());
            assertFalse(p.hasContent(1));
        }

        @Test
        @DisplayName("中间结果全部落在「仍在变」的一侧")
        void partialIsVolatile() {
            PreviewText p = new PreviewText();
            p.setPartial("今天天气");
            assertEquals("", p.committedText(), "还没有最终结果，一个字都不该标成已稳定");
            assertEquals("今天天气", p.volatileSuffix());
            assertTrue(p.hasContent(4));
        }

        @Test
        @DisplayName("最终结果把内容移入「已稳定」一侧")
        void finalCommitsText() {
            PreviewText p = new PreviewText();
            p.setPartial("今天天气不错");
            p.commitFinal("今天天气不错");
            assertEquals("今天天气不错", p.committedText());
            assertEquals("", p.volatileSuffix());
        }

        @Test
        @DisplayName("定稿后新出的中间结果只加在尾部")
        void partialAfterFinalAppends() {
            PreviewText p = new PreviewText();
            p.commitFinal("今天天气不错");
            p.setPartial("今天天气不错我们出去走走");
            assertEquals("今天天气不错", p.committedText());
            assertEquals("我们出去走走", p.volatileSuffix());
        }

        @Test
        @DisplayName("引擎改写已稳定部分：记录修订并接受（§8 的真实风险）")
        void engineRevisionIsRecorded() {
            PreviewText p = new PreviewText();
            p.commitFinal("这个需球");
            p.setPartial("这个需求");
            assertEquals("这个需求", p.fullText());
            assertEquals(1, p.revisionCount());
            assertEquals("这个需球", p.lastRevision().from());
            assertEquals("这个需求", p.lastRevision().to());
            assertEquals(1, p.lastRevision().codePointsBackspaced(),
                    "「需球」→「需求」只有一个码点不同（球→求）");
        }

        @Test
        @DisplayName("反复修订累计计数（可观测性）")
        void revisionCountAccumulates() {
            PreviewText p = new PreviewText();
            p.commitFinal("abc");
            p.setPartial("abd");
            p.setPartial("abe");
            assertTrue(p.revisionCount() >= 1);
        }

        @Test
        @DisplayName("finish 把尾部并入稳定部分，不丢最后几个字")
        void finishFoldsTail() {
            PreviewText p = new PreviewText();
            p.commitFinal("今天");
            p.setPartial("今天天气不错");
            String out = p.finish();
            assertEquals("今天天气不错", out);
            assertEquals("今天天气不错", p.committedText());
            assertEquals("", p.volatileSuffix());
        }

        @Test
        @DisplayName("reset 清空一切（含修订记录）")
        void reset() {
            PreviewText p = new PreviewText();
            p.setPartial("abc");
            p.reset();
            assertTrue(p.isEmpty());
            assertEquals("", p.fullText());
        }

        @Test
        @DisplayName("describe 不泄露内容（只报长度、稳定字数、修订次数）")
        void describeHidesContent() {
            PreviewText p = new PreviewText();
            p.setPartial("这是机密内容");
            String d = p.describe();
            assertFalse(d.contains("机密"), d);
            assertTrue(d.contains("len=6"), d);
        }

        @Test
        @DisplayName("代理对在预览里按码点计（hasContent 的阈值判断）")
        void surrogatePairsInPreview() {
            PreviewText p = new PreviewText();
            p.setPartial("😀😀");
            assertTrue(p.hasContent(2));
            assertFalse(p.hasContent(3));
        }

        @Test
        @DisplayName("空最终结果不破坏已有内容")
        void emptyFinalKeepsNothing() {
            PreviewText p = new PreviewText();
            p.commitFinal("");
            assertEquals("", p.committedText());
            p.commitFinal(null);
            assertEquals("", p.committedText());
        }

        @Test
        @DisplayName("中英混排的修订定位正确")
        void mixedScriptRevision() {
            PreviewText p = new PreviewText();
            p.commitFinal("用Java写");
            p.setPartial("用Java写代码");
            assertEquals("用Java写", p.committedText());
            assertEquals("代码", p.volatileSuffix());
        }
    }

    @Nested
    @DisplayName("日志安全表示（§3.1 第 12 项：不记转写内容）")
    class LogSafe {

        @Test
        @DisplayName("只输出长度，不输出内容")
        void noContent() {
            String s = com.talkinglive.core.Logging.describe("这是机密内容");
            assertFalse(s.contains("机密"), s);
            assertTrue(s.contains("len=6"), s);
            assertTrue(s.contains("内容不记录"), s);
        }

        @Test
        @DisplayName("长度按码点")
        void lengthByCodePoints() {
            assertTrue(com.talkinglive.core.Logging.describe("😀").contains("len=1"));
        }

        @Test
        @DisplayName("指纹稳定且可用来比对同一段文本")
        void fingerprintStable() {
            String a = com.talkinglive.core.Logging.fingerprint("同样的文本");
            String b = com.talkinglive.core.Logging.fingerprint("同样的文本");
            String c = com.talkinglive.core.Logging.fingerprint("不同的文本");
            assertEquals(a, b);
            assertFalse(a.equals(c));
            assertEquals(8, a.length());
        }

        @Test
        @DisplayName("指纹不泄露内容")
        void fingerprintHidesContent() {
            String f = com.talkinglive.core.Logging.describeWithFingerprint("绝密口令");
            assertFalse(f.contains("绝密"), f);
            assertTrue(f.contains("fp="), f);
        }

        @Test
        @DisplayName("null 与空串也被处理")
        void nullsHandled() {
            assertTrue(com.talkinglive.core.Logging.describe(null).contains("len=0"));
            assertTrue(com.talkinglive.core.Logging.describeWithFingerprint("").contains("len=0"));
        }
    }

    @Nested
    @DisplayName("注入分批（按码点切，不切坏代理对）")
    class Batching {

        @Test
        @DisplayName("按指定码点数切批")
        void splitsByCodePoints() {
            List<String> batches = TextInjector.batchByCodePoints("abcdefg", 3);
            assertEquals(List.of("abc", "def", "g"), batches);
        }

        @Test
        @DisplayName("代理对不会被切到两批之间")
        void neverSplitsSurrogatePair() {
            List<String> batches = TextInjector.batchByCodePoints("a😀b😀c", 2);
            String joined = String.join("", batches);
            assertEquals("a😀b😀c", joined);
            for (String b : batches) {
                assertFalse(b.isEmpty());
                char last = b.charAt(b.length() - 1);
                assertFalse(Character.isHighSurrogate(last), "批次不能以高代理结尾：" + b);
            }
        }

        @Test
        @DisplayName("空输入得到空批次列表")
        void emptyInput() {
            assertTrue(TextInjector.batchByCodePoints("", 10).isEmpty());
            assertTrue(TextInjector.batchByCodePoints(null, 10).isEmpty());
        }

        @Test
        @DisplayName("批大小非法时至少为 1，不会死循环")
        void invalidBatchSize() {
            assertEquals(List.of("a", "b"), TextInjector.batchByCodePoints("ab", 0));
        }
    }
}
