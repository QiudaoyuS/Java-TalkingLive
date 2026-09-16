package com.talkinglive.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 唤醒词解析（{@link WakePhrase}）的纯逻辑测试。
 *
 * <p>这是「英语唤醒词」这条路的守门测试。背景（{@code docs/ENGINE-EXPERIMENT.md} §7.5）：
 * 中文模型的词表里**拉丁 token 为 0 个**，所以 {@code Firay} 永远不可能被识别到；
 * 但只要一个字一个字地写（{@code Firay ≈ 飞瑞}），受限语法就能用单字序列把它拼出来。
 * 实测说「飞瑞」能稳定命中，说别的话不会误命中。
 *
 * <p>用假词表而不是真模型：拆字逻辑全是纯字符串判断，真模型只会让测试变慢、
 * 还得依赖模型装没装。真实模型上的端到端验证在 {@code --doctor} 与手工验收里做。
 */
class WakePhraseTest {

    /**
     * 一个可控的假词表。
     *
     * <p>用 {@link WakePhrase.Vocabulary} 而不是假 {@code VoskModel}：后者是 final
     * 且构造函数私有，测试里连继承都编译不过（这是实测踩到的）。
     */
    private static WakePhrase.Vocabulary vocab(String... words) {
        Set<String> set = new HashSet<>(List.of(words));
        return set::contains;
    }

    @Nested
    @DisplayName("整词优先（绝不主动拆一个词表里有的词）")
    class WholeWordFirst {

        @Test
        @DisplayName("整词在词表内时不拆，即使它的每个字也都在表内")
        void wholeWordWins() {
            // 「小助手」整词在表内 —— 必须整词使用。拆成三个字会让误唤醒率大增，
            // 而整词匹配本来就够用。
            WakePhrase p = WakePhrase.resolve(vocab("小助手", "小", "助", "手"), "小助手")
                    .orElseThrow();
            assertEquals(List.of("小助手"), p.tokens());
            assertFalse(p.spelled(), "整词命中就不该拆");
        }

        @Test
        @DisplayName("单字也是合法整词（「子曰」之外的备选里就有单字）")
        void singleCharWordIsAWholeWord() {
            WakePhrase p = WakePhrase.resolve(vocab("飞"), "飞").orElseThrow();
            assertEquals(List.of("飞"), p.tokens());
            assertFalse(p.spelled());
        }
    }

    @Nested
    @DisplayName("逐字拆（英语唤醒词的实现基础）")
    class Spelling {

        @Test
        @DisplayName("整词不在表内、但每个字都在 → 拆成单字序列")
        void splitsWhenEveryCharIsKnown() {
            WakePhrase p = WakePhrase.resolve(vocab("飞", "瑞"), "飞瑞").orElseThrow();
            assertEquals(List.of("飞", "瑞"), p.tokens());
            assertTrue(p.spelled());
            assertEquals("飞+瑞", p.describeTokens());
        }

        @Test
        @DisplayName("任一个字不在表内就整体不可用（不能只认一半）")
        void anyUnknownCharFails() {
            // 「飞」在、「斐」不在 —— 不允许"部分可用"。半个词被识别出来
            // 只会让用户以为功能坏了。
            assertTrue(WakePhrase.resolve(vocab("飞"), "飞斐").isEmpty());
        }

        @Test
        @DisplayName("超过 MAX_TOKENS 个字不拆（搜索空间与误触发都会失控）")
        void tooLongFails() {
            String longWord = "飞".repeat(WakePhrase.MAX_TOKENS + 1);
            assertTrue(WakePhrase.resolve(vocab("飞"), longWord).isEmpty());
            // 正好等于上限仍然可以用
            assertTrue(WakePhrase.resolve(vocab("飞"),
                    "飞".repeat(WakePhrase.MAX_TOKENS)).isPresent());
        }

        @Test
        @DisplayName("重复字**必须保留**（「飞飞飞」不能压成一个「飞」）")
        void repeatedCharsKept() {
            // 这条是实测抓出来的 bug：早期按 token 值去重，把 ["飞","飞","飞"]
            // 压成了 ["飞"]，于是说「飞飞飞」永远不命中——唤醒词静默失效。
            WakePhrase p = WakePhrase.resolve(vocab("飞"), "飞飞飞").orElseThrow();
            assertEquals(List.of("飞", "飞", "飞"), p.tokens());
        }

        @Test
        @DisplayName("单个字不在表内、且拆不了 → 返回 empty（调用方要报错，不能静默）")
        void unknownWordFails() {
            assertTrue(WakePhrase.resolve(vocab("子曰"), "小秘书").isEmpty());
        }
    }

    @Nested
    @DisplayName("已知不可用的词不能被拆字绕过")
    class KnownAbsent {

        @Test
        @DisplayName("「本段结束」即使每个字都在表内也拒绝")
        void knownAbsentRejected() {
            // 附录 B.1 当初就是因为「本段结束」不在词表内才把结束词改成「到此为止」。
            // 它的四个字各自都在表内，从"每个 token 都在表内"看它是合法的——
            // 判据没错，但不充分：拆成单字后在真实语音里几乎不可能稳定命中。
            // 新增拆字能力不该让这个来之不易的结论失效。
            assertTrue(com.talkinglive.core.WordSuggestions.KNOWN_ABSENT.contains("本段结束"));
            WakePhrase p = WakePhrase.resolve(vocab("本", "段", "结", "束"), "本段结束")
                    .orElse(null);
            assertEquals(null, p, "KNOWN_ABSENT 的词不该被拆字绕过");
        }
    }

    @Nested
    @DisplayName("边界输入")
    class Edges {

        @Test
        @DisplayName("null / 空 / 纯空白都返回 empty，不抛异常")
        void blankInputs() {
            WakePhrase.Vocabulary m = vocab("飞");
            assertTrue(WakePhrase.resolve(m, null).isEmpty());
            assertTrue(WakePhrase.resolve(m, "").isEmpty());
            assertTrue(WakePhrase.resolve(m, "   ").isEmpty());
            assertTrue(WakePhrase.resolve(null, "飞瑞").isEmpty());
        }

        @Test
        @DisplayName("两端空白被裁掉")
        void trims() {
            WakePhrase p = WakePhrase.resolve(vocab("飞", "瑞"), "  飞瑞  ").orElseThrow();
            assertEquals("飞瑞", p.display());
            assertEquals(List.of("飞", "瑞"), p.tokens());
        }

        @Test
        @DisplayName("代理对汉字按 codePoint 拆（按 char 拆会切出半个字符）")
        void surrogatePairs() {
            // 𠀀 是 U+20000，占两个 char。若按 char 拆，会得到两个非法半字符，
            // 查词表必然失败 → 功能静默失效。
            String surrogate = "\uD840\uDC00";
            // 整词命中
            assertEquals(List.of(surrogate),
                    WakePhrase.resolve(vocab(surrogate), surrogate).orElseThrow().tokens());
            // 拆字：与另一个在表内的字组合
            WakePhrase p = WakePhrase.resolve(vocab(surrogate, "瑞"), surrogate + "瑞")
                    .orElseThrow();
            assertEquals(List.of(surrogate, "瑞"), p.tokens());
        }

        @Test
        @DisplayName("token 序列为空时构造抛错（内部不变式，不该被外部触发）")
        void emptyTokensRejected() {
            assertThrows(IllegalArgumentException.class, () -> new WakePhrase("飞", List.of()));
            assertThrows(IllegalArgumentException.class, () -> new WakePhrase("飞", null));
        }

        @Test
        @DisplayName("tokens 不可被外部修改")
        void tokensImmutable() {
            WakePhrase p = WakePhrase.resolve(vocab("飞", "瑞"), "飞瑞").orElseThrow();
            assertThrows(UnsupportedOperationException.class, () -> p.tokens().add("x"));
        }
    }

    @Nested
    @DisplayName("命中判定（误唤醒率就靠这一段）")
    class Matching {

        /** 拆开的唤醒词「飞瑞」。 */
        private final WakePhrase spelled =
                WakePhrase.resolve(vocab("飞", "瑞"), "飞瑞").orElseThrow();

        /** 整词唤醒词「小助手」。 */
        private final WakePhrase whole =
                WakePhrase.resolve(vocab("小助手"), "小助手").orElseThrow();

        @Test
        @DisplayName("单字序列：完整的词才算命中")
        void spelledNeedsFullSequence() {
            assertTrue(spelled.matches("飞瑞"));
        }

        @Test
        @DisplayName("单字序列：只说一个字**不算**命中（这正是拆字的代价所在）")
        void spelledRejectsPartial() {
            assertFalse(spelled.matches("飞"), "只说「飞」不算命中");
            assertFalse(spelled.matches("瑞"), "只说「瑞」不算命中");
            assertFalse(spelled.matches("飞瑞飞瑞"), "多说的部分不该被忽略");
            assertFalse(spelled.matches("非为"), "听着像但不是同一个词");
        }

        @Test
        @DisplayName("单字序列：结果里的 [unk] 与词间空格都被忽略")
        void spelledNormalizesNoise() {
            // 实测两种真实形态：[unk] 是语法里"语法外语音"的符号，会真的出现在结果里；
            // 词间空格来自 textOf 对 token 序列的拼接（它只在含拉丁字符时才去空格）。
            assertTrue(spelled.matches("[unk]飞瑞"));
            assertTrue(spelled.matches("飞瑞[unk]"));
            assertTrue(spelled.matches("飞 瑞"));
            assertTrue(spelled.matches("[unk] 飞 瑞 [unk]"));
        }

        @Test
        @DisplayName("整词：包含判断保留（引擎可能把词切成两段）")
        void wholeUsesContains() {
            assertTrue(whole.matches("小助手"));
            assertTrue(whole.matches("喂小助手"), "前面多一个字仍应命中");
            assertFalse(whole.matches("小助"), "词被截断不算命中");
            assertFalse(whole.matches("助手"));
        }

        @Test
        @DisplayName("null / 空 / 只有 [unk] 都不命中，不抛异常（音频线程不能死）")
        void safeInputs() {
            assertFalse(spelled.matches(null));
            assertFalse(spelled.matches(""));
            assertFalse(spelled.matches("[unk]"));
            assertFalse(spelled.matches("   "));
        }
    }
}
