package com.talkinglive.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.talkinglive.system.MicValidator;
import java.awt.Font;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 状态标签的排版约束测试。
 *
 * <p>这一组的存在理由是一次**重复犯的错**：用户两次发来截图，看到的状态提示都是
 * 「唤醒词「飞瑞」已拆成单字「飞」…」——省略号恰好落在"拆成了哪几个字"上，
 * 而那正是那条提示唯一要说的事。
 *
 * <p>第一次我以为是文案太长，把提示写短了；第二次我以为是标签太窄，把标签加宽了。
 * 两次都还留着省略号，因为**真正错的是"截断"这个处理方式**：
 * 只要处理方式是截断，就一定会有一天把重点截掉。现在改成换行，
 * 并且用会失败的测试把它固定住。
 */
class StatusLayoutTest {

    /** 与生产同尺寸的度量环境：300px 宽、11pt。 */
    private static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 11);
    private static final int LABEL_WIDTH = 300;
    private static final int LABEL_HEIGHT = 30;

    private static java.awt.FontMetrics metrics() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        return img.createGraphics().getFontMetrics(FONT);
    }

    private static int usableWidth() {
        return LABEL_WIDTH - 6;
    }

    private static int maxLines(java.awt.FontMetrics fm) {
        return Math.max(1, LABEL_HEIGHT / fm.getHeight());
    }

    private static String wrap(String text) {
        java.awt.FontMetrics fm = metrics();
        return SettingsWindow.wrapToFit(text, usableWidth(), maxLines(fm), fm);
    }

    @Nested
    @DisplayName("拆字提示（用户两次看到省略号的那条）")
    class SpelledNotice {

        private MicValidator.Problem notice() {
            return new MicValidator.Problem("唤醒词", "飞瑞", null, true, List.of("飞", "瑞"));
        }

        @Test
        @DisplayName("brief() 完整显示，**一个字都不许截**")
        void briefIsFullyShown() {
            String out = wrap(notice().brief());
            assertFalse(out.contains("…"), "不该出现省略号：" + out);
            assertFalse(out.contains("悬停"), "内容没超长，不该提示看全文：" + out);
            // 把折行拼回去应当与原文完全一致 —— 一个字符都不能少
            assertEquals(notice().brief(), out.replace("\n", ""), "内容被改动了");
        }

        @Test
        @DisplayName("「拆成了哪几个字」必须出现在可见文字里（重点不能被截掉）")
        void bothCharactersVisible() {
            String out = wrap(notice().brief());
            assertTrue(out.contains("飞"), out);
            assertTrue(out.contains("瑞"), "「瑞」被截掉了 —— 这正是之前的问题：" + out);
        }

        @Test
        @DisplayName("每行都不超过可用宽度（否则 Swing 还是会切掉）")
        void everyLineFits() {
            java.awt.FontMetrics fm = metrics();
            String out = wrap(notice().describe());
            for (String line : out.split("\n")) {
                assertTrue(fm.stringWidth(line) <= usableWidth(),
                        "这一行超宽（" + fm.stringWidth(line) + "px）：" + line);
            }
            // describe() 实测 544px，折成 2 行正好装下 —— 装得下就不该出现任何省略
            assertFalse(out.contains("…（悬停看全文）"),
                    "2 行能装下就不该提示看全文：" + out);
            // 装得下就意味着**一个字都没少**
            assertEquals(notice().describe(), out.replace("\n", ""), "内容被截了");
        }

        @Test
        @DisplayName("真放不下时才提示「悬停看全文」，且提示本身完整")
        void trulyLongTextHints() {
            java.awt.FontMetrics fm = metrics();
            // 真实存在的超长文本：结束词留空的警告（实测 694px，2 行装不下）
            String longText = "结束词留空、静音超时也关着 —— 此时只能由「单段最长时长」（60 秒）收尾，"
                    + "超过就会被从中间截断并落字。建议至少保留一种收尾方式。";
            String out = SettingsWindow.wrapToFit(longText, usableWidth(), maxLines(fm), fm);
            assertTrue(out.contains(SettingsWindow.ELISION_MARK),
                    "放不下却没告诉用户还有内容可看：" + out);
            // 提示语必须**完整**出现：早先的 bug 就是它自己被切成了「…（悬停看全」
            assertTrue(out.endsWith(SettingsWindow.ELISION_MARK),
                    "提示语必须完整收尾，不能被切一半：" + out);
            for (String line : out.split("\n")) {
                assertTrue(fm.stringWidth(line) <= usableWidth(),
                        "这一行超宽（" + fm.stringWidth(line) + "px）：" + line);
            }
        }
    }

    @Nested
    @DisplayName("配置提醒（结束词留空这类警告）")
    class ConfigWarnings {

        /** **真实**警告：由真实配置生成，而不是抄一份到测试里 —— 抄的那份会过期。 */
        private static String warning() {
            com.talkinglive.core.AppConfig c = new com.talkinglive.core.AppConfig();
            c.setEndWord("");
            c.setSilenceSeconds(0);
            return String.join(" ", c.warnings());
        }

        @Test
        @DisplayName("警告完整显示，一个字都不省略")
        void warningFitsCompletely() {
            java.awt.FontMetrics fm = metrics();
            String text = warning();
            assertFalse(text.isEmpty(), "触发警告的配置没产生警告");
            String out = SettingsWindow.wrapToFit(text, usableWidth(), maxLines(fm), fm);
            for (String line : out.split("\n")) {
                assertTrue(fm.stringWidth(line) <= usableWidth(),
                        "这一行超宽（" + fm.stringWidth(line) + "px）：" + line);
            }
            // 这条是本次的核心：警告曾经 694px，两行装不下，"60 秒"和"截断"这些
            // 真正的后果都被省略号吃掉了 —— 等于提醒了却没说清。
            assertFalse(out.contains(SettingsWindow.ELISION_MARK),
                    "警告应当完整显示（放不下就该改短文案，而不是让它被截）：" + out);
            assertEquals(text, out.replace("\n", ""), "内容被截了");
        }

        @Test
        @DisplayName("关键后果（60 秒上限、会截断）必须在可见文字里")
        void keyConsequencesVisible() {
            String out = wrap(warning());
            assertTrue(out.contains("60"), out);
            assertTrue(out.contains("截断"), out);
        }

        @Test
        @DisplayName("断行优先落在标点/空格处，且不在数字与汉字之间断开")
        void breaksAtPunctuation() {
            String out = wrap(warning());
            for (String line : out.split("\n")) {
                // 「60 秒」是数量词，断开会让量词像换了个数
                assertFalse(line.endsWith("60"),
                        "「60 秒」被拆开了：「" + line + "」→ 下一行");
                // 标点不该出现在行首（句末标点被甩下来很难看）
                assertFalse(line.startsWith("。") || line.startsWith("，")
                                || line.startsWith("、") || line.startsWith("；"),
                        "标点掉到了行首：「" + line + "」");
                // 汉字行尾紧跟数字/字母也不行（「识别」+「 AI」）
                assertFalse(line.endsWith("识别"), "汉字与字母被拆开了：" + line);
            }
        }
    }

    @Nested
    @DisplayName("折行本身")
    class Wrapping {

        @Test
        @DisplayName("短文本原样返回，不加换行")
        void shortTextUntouched() {
            String s = "修改已保存并立即生效";
            assertEquals(s, wrap(s));
        }

        @Test
        @DisplayName("长文本折成多行，且不超过允许行数")
        void wrapsWithinLineBudget() {
            java.awt.FontMetrics fm = metrics();
            int budget = maxLines(fm);
            String longText = "结束词留空、静音超时也关着 —— 此时只能由「单段最长时长」（60 秒）收尾，"
                    + "超过就会被从中间截断并落字。建议至少保留一种收尾方式。";
            String out = SettingsWindow.wrapToFit(longText, usableWidth(), budget, fm);
            long lines = out.lines().count();
            assertTrue(lines <= budget, "行数 " + lines + " 超过预算 " + budget + "：\n" + out);
            for (String line : out.split("\n")) {
                assertTrue(fm.stringWidth(line) <= usableWidth(),
                        "这一行超宽（" + fm.stringWidth(line) + "px）：" + line);
            }
        }

        @Test
        @DisplayName("折行处不留下多余空格、也不以空格开头")
        void noLeadingSpaces() {
            java.awt.FontMetrics fm = metrics();
            String out = SettingsWindow.wrapToFit(
                    "唤醒词 与 结束词 都 很 长 很 长 很 长 很 长 很 长 很 长 很 长 很 长",
                    usableWidth(), maxLines(fm), fm);
            for (String line : out.split("\n")) {
                assertFalse(line.startsWith(" "), "行首不该有空格：「" + line + "」");
            }
        }

        @Test
        @DisplayName("空文本、null、无度量都不抛异常（音频/EDT 线程不能死）")
        void safeInputs() {
            java.awt.FontMetrics fm = metrics();
            assertEquals("", SettingsWindow.wrapToFit("", usableWidth(), 2, fm));
            assertEquals("", SettingsWindow.wrapToFit(null, usableWidth(), 2, fm));
            assertEquals("abc", SettingsWindow.wrapToFit("abc", usableWidth(), 2,
                    (java.awt.FontMetrics) null));
        }

        @Test
        @DisplayName("单行预算时超长内容也要给出「悬停看全文」而不是硬截")
        void singleLineBudgetStillHints() {
            java.awt.FontMetrics fm = metrics();
            String out = SettingsWindow.wrapToFit(noticeText(), usableWidth(), 1, fm);
            assertTrue(out.contains("悬停看全文"), out);
            assertEquals(1, out.lines().count(), "只允许一行就不该换行：" + out);
        }

        private String noticeText() {
            return new MicValidator.Problem("唤醒词", "飞瑞", null, true, List.of("飞", "瑞"))
                    .describe();
        }
    }
}
