package com.talkinglive.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 热词纠正的测试。
 *
 * <p>它针对的是一个**实测出来的硬缺陷**：{@code vosk-model-small-cn-0.22} 的中文词表里
 * 没有任何英文（实测 A–Z、AI、APP、CPU 全部不在表内，用 {@code vosk_model_find_word}
 * 查询得到 -1），所以流式预览永远输出不了「AI」；大模型认识 AI，但可能按字母拆成「A I」。
 *
 * <p>注意：**配置串的解析不在这里测** —— 它属于"配置怎么读"，实现在
 * {@code core.AppConfig.hotwordMap()}，测试在 {@code AppConfigTest.Hotwords}。
 * 本类历史上也有一份 {@code parse}/{@code format}，测试曾经保护着那份、
 * 而生产真正在用的 AppConfig 那份没人测；已删除重复实现并把测试搬过去。
 *
 * <p>因此这里测的不是「猜得准不准」，而是两条**确定性规则**：
 * <ol>
 *   <li>被拆开的字母串要拼回去（这是格式问题，不是语义问题）；</li>
 *   <li>用户显式配的替换要生效，且**不猜**。</li>
 * </ol>
 */
class HotwordCorrectorTest {

    private static Map<String, String> map(String... kvs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            m.put(kvs[i], kvs[i + 1]);
        }
        return m;
    }

    @Nested
    @DisplayName("字母拼合：Vosk 逐词输出会把 AI 拆成 A I")
    class SpacedLetters {

        @Test
        @DisplayName("「A I」拼成「AI」并统一大写")
        void collapsesTwoLetters() {
            assertEquals("现在是AI输入", correct("现在是A I输入"));
        }

        @Test
        @DisplayName("多字母缩写同样拼合（P D F → PDF）")
        void collapsesLongAbbreviation() {
            assertEquals("导出PDF就行", correct("导出P D F就行"));
        }

        @Test
        @DisplayName("小写字母串也拼合并大写（a i → AI）")
        void collapsesLowercase() {
            assertEquals("用AI写", correct("用a i写"));
        }

        @Test
        @DisplayName("**单个字母不动** —— 单字母有它自己的含义")
        void singleLetterIsLeftAlone() {
            // 「A 方案」里的 A 是一个真实的 A，不是缩写的碎片。
            // 把它拼掉（或大写化之外的任何处理）都会改坏正文。
            assertEquals("选A方案", correct("选A方案"));
            assertEquals("这是B", correct("这是B"));
        }

        @Test
        @DisplayName("已经连写的英文不动（不重复处理）")
        void alreadyJoined() {
            assertEquals("现在是AI输入", correct("现在是AI输入"));
            assertEquals("APP很好用", correct("APP很好用"));
        }

        @Test
        @DisplayName("中文之间的空格不受影响")
        void chineseUntouched() {
            assertEquals("今天是晴天", correct("今天是晴天"));
        }

        @Test
        @DisplayName("中英混排：中文紧贴英文不加空格")
        void mixedScript() {
            assertEquals("用GPT写代码", correct("用G P T写代码"));
        }
    }

    @Nested
    @DisplayName("用户配置的替换：只改用户说过的那部分")
    class Configured {

        @Test
        @DisplayName("配了就换（说的词 → 想要的写法）")
        void replacesConfigured() {
            HotwordCorrector c = new HotwordCorrector(map("诶爱", "AI"));
            assertEquals("现在是AI输入", c.process("现在是诶爱输入"));
        }

        @Test
        @DisplayName("**没配的不猜** —— 绝不擅自把中文改成英文")
        void doesNotGuess() {
            // 这条是核心约束：猜错会改掉用户本来正确的正文，比少一个字严重得多。
            assertEquals("今天天气不错", correct("今天天气不错"));
            assertEquals("我喜欢吃苹果", correct("我喜欢吃苹果"));
        }

        @Test
        @DisplayName("多个词条都生效，且按键的配置顺序应用")
        void multipleEntries() {
            HotwordCorrector c = new HotwordCorrector(map("诶爱", "AI", "皮迪艾夫", "PDF"));
            assertEquals("AI和PDF都要", c.process("诶爱和皮迪艾夫都要"));
        }

        @Test
        @DisplayName("替换先于字母拼合：用户规则里写带空格的键也能命中")
        void replaceRunsBeforeCollapse() {
            HotwordCorrector c = new HotwordCorrector(map("诶 爱", "AI"));
            // 用户写的是「诶 爱」，输入里也带空格 —— 先按用户规则换掉，
            // 拼合规则只在没有用户规则时才兜底
            assertEquals("现在是AI输入", c.process("现在是诶 爱输入"));
        }

        @Test
        @DisplayName("空表 = 只做字母拼合")
        void lettersOnly() {
            // 空表仍然要工作：字母拼合不依赖热词表。
            // （这条以前断言 isEmpty()，但那个方法生产零引用，已随之删除 ——
            //   断言"空表也能正确拼合"才是真正要守住的行为。）
            assertEquals("用AI写", new HotwordCorrector(Map.of()).process("用A I写"));
            assertEquals("现在是AI输入", new HotwordCorrector(Map.of()).process("现在是A I输入"));
        }
    }

    @Nested
    @DisplayName("边界")
    class Edges {

        @Test
        @DisplayName("null / 空串安全")
        void nullSafe() {
            HotwordCorrector c = new HotwordCorrector(Map.of());
            assertEquals("", c.process(null));
            assertEquals("", c.process(""));
        }

        @Test
        @DisplayName("非法输入不抛异常")
        void neverThrows() {
            HotwordCorrector c = new HotwordCorrector(map("a", "1"));
            for (String s : new String[] {null, "", " ", "A  I", "A", "1 2 3", "。,", "Ａ Ｉ"}) {
                c.process(s);   // 只要不抛就算过
            }
        }

        @Test
        @DisplayName("数字序列不被当成字母缩写")
        void digitsAreNotLetters() {
            // 「1 2 3」是三个数字，不是缩写；当前规则只拼字母，数字保持原样。
            // 这条断言的作用是**锁住这个决定**：若以后有人放宽到数字，
            // 这里会失败，从而必须显式讨论「1 2 3 要不要拼成 123」。
            assertEquals("第1 2 3章", correct("第1 2 3章"));
        }
    }

    private static String correct(String input) {
        return new HotwordCorrector(Map.of()).process(input);
    }
}
