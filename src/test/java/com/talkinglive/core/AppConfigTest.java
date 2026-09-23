package com.talkinglive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.talkinglive.core.AppConfig.ConfigException;
import com.talkinglive.core.AppConfig.DockSide;
import com.talkinglive.core.AppConfig.SendKey;
import com.talkinglive.core.JsonCodec.JsonException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 配置读写与 JSON 编解码测试（{@code DESIGN.md} §4.5：纯逻辑，可单测）。
 *
 * <p>重点是 §4.3「配置必须显式校验」：非法值必须**报错**而不是静默回退成默认值，
 * 否则用户会遇到「改了没反应」这类最难查的问题。
 */
class AppConfigTest {

    // ============================================================ 默认值

    @Nested
    @DisplayName("默认值（附录 A）")
    class Defaults {

        @Test
        @DisplayName("默认唤醒词是「子曰」")
        void defaultWakeWord() {
            assertEquals("子曰", new AppConfig().wakeWord());
        }

        @Test
        @DisplayName("默认结束词是「到此为止」（不是因为「本段结束」不在词表内）")
        void defaultEndWord() {
            assertEquals("到此为止", new AppConfig().endWord());
        }

        @Test
        @DisplayName("静音默认 5 秒，自动发送默认关闭（附录 A：默认关闭）")
        void defaultSilenceAndAutoSend() {
            AppConfig c = new AppConfig();
            assertEquals(5, c.silenceSeconds());
            assertFalse(c.autoSend());
            assertEquals(SendKey.ENTER, c.sendKey());
            assertFalse(c.sendOnSilenceTimeout());
        }

        @Test
        @DisplayName("默认值本身必须通过校验")
        void defaultsAreValid() {
            new AppConfig().validate();
        }

        @Test
        @DisplayName("默认配置序列化后能读回同样的值")
        void roundTripDefaults() {
            AppConfig c = new AppConfig();
            AppConfig back = AppConfig.fromJsonText(c.toJsonText());
            assertEquals(c.wakeWord(), back.wakeWord());
            assertEquals(c.endWord(), back.endWord());
            assertEquals(c.silenceSeconds(), back.silenceSeconds());
            assertEquals(c.autoSend(), back.autoSend());
            assertEquals(c.sendKey(), back.sendKey());
            assertEquals(c.maxSegmentSeconds(), back.maxSegmentSeconds());
            assertEquals(c.ball().dock(), back.ball().dock());
        }
    }

    // ============================================================ 校验

    @Nested
    @DisplayName("强制校验（§4.3）")
    class Validation {

        @Test
        @DisplayName("空唤醒词被拒绝")
        void emptyWakeWordRejected() {
            AppConfig c = new AppConfig();
            c.setWakeWord("   ");
            ConfigException e = assertThrows(ConfigException.class, c::validate);
            assertTrue(e.getMessage().contains("唤醒词"));
        }

        @Test
        @DisplayName("空结束词**被接受** —— 结束词是选项，不是必填")
        void emptyEndWordAccepted() {
            // 用户指出：「可以有结束词，也意味着可以没有结束词，现在的限制是不能没有」。
            // 结束词只是三种收尾方式之一（另两种是静音超时、切换窗口），设成必填
            // 等于强迫用户接受一种自己不需要的收尾方式。
            // 旧行为（要求非空）的测试就在这里，已按新行为反转。
            AppConfig c = new AppConfig();
            c.setEndWord("");
            c.validate(); // 不抛异常
            assertEquals("", c.endWord());
            // 空白也算空（setEndWord 里 trim 过），同样接受
            c.setEndWord("   ");
            c.validate();
            assertEquals("", c.endWord());
        }

        @Test
        @DisplayName("空结束词与空唤醒词不同：唤醒词仍然必填")
        void emptyWakeWordStillRejectedWhenEndBlank() {
            // 两者别一起放开：没有唤醒词就没有任何方式"开始"听写，
            // 而悬浮球虽然能手动开始，但本产品的定位是全程语音操控。
            AppConfig c = new AppConfig();
            c.setWakeWord("");
            c.setEndWord("");
            ConfigException e = assertThrows(ConfigException.class, c::validate);
            assertTrue(e.getMessage().contains("唤醒词"), e.getMessage());
            assertFalse(e.getMessage().contains("结束词"), e.getMessage());
        }

        @Test
        @DisplayName("唤醒词与结束词相同时被拒绝（否则一喊就自我结束）")
        void sameWordsRejected() {
            AppConfig c = new AppConfig();
            c.setWakeWord("结束");
            c.setEndWord("结束");
            ConfigException e = assertThrows(ConfigException.class, c::validate);
            assertTrue(e.getMessage().contains("相同"));
        }

        @Test
        @DisplayName("静音秒数超出 0–15 被拒绝，并给出范围")
        void silenceRangeEnforced() {
            AppConfig c = new AppConfig();
            c.setSilenceSeconds(16);
            ConfigException e = assertThrows(ConfigException.class, c::validate);
            assertTrue(e.getMessage().contains("0–15"), e.getMessage());

            c.setSilenceSeconds(-1);
            assertThrows(ConfigException.class, c::validate);
        }

        @Test
        @DisplayName("静音秒数 0 合法，表示关闭静音结束（附录 A）")
        void silenceZeroDisablesTimeout() {
            AppConfig c = new AppConfig();
            c.setSilenceSeconds(0);
            c.validate();
            assertFalse(c.silenceTimeoutEnabled());
        }

        @Test
        @DisplayName("单段时长上限超出范围被拒绝")
        void maxSegmentRangeEnforced() {
            AppConfig c = new AppConfig();
            c.setMaxSegmentSeconds(1);
            assertThrows(ConfigException.class, c::validate);
            c.setMaxSegmentSeconds(9999);
            assertThrows(ConfigException.class, c::validate);
        }

        @Test
        @DisplayName("多个问题一次性报出（不是只报第一条）")
        void reportsAllProblems() {
            AppConfig c = new AppConfig();
            c.setWakeWord("");
            c.setEndWord("");
            c.setSilenceSeconds(99);
            ConfigException e = assertThrows(ConfigException.class, c::validate);
            assertTrue(e.getMessage().contains("唤醒词"));
            assertTrue(e.getMessage().contains("静音"));
            // 空结束词**不再是**问题（它是选项，见 emptyEndWordAccepted）
            assertFalse(e.getMessage().contains("结束词不能为空"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("合法但可能意外的组合（提醒，不拦）")
    class Warnings {

        @Test
        @DisplayName("默认配置没有提醒")
        void defaultsAreQuiet() {
            assertTrue(new AppConfig().warnings().isEmpty());
        }

        @Test
        @DisplayName("结束词留空**本身**不提醒（那是用户的选择）")
        void blankEndWordAloneIsQuiet() {
            AppConfig c = new AppConfig();
            c.setEndWord("");
            assertTrue(c.warnings().isEmpty(), "只留空结束词不该唠叨");
        }

        @Test
        @DisplayName("结束词留空 + 静音也关着 → 提醒只剩单段上限兜底")
        void noEndWordAndNoSilenceWarns() {
            // 这个组合会让 60 秒上限成为唯一收尾，长句被从中间截断落字。
            // 不说的话用户会以为软件把话吃了。
            AppConfig c = new AppConfig();
            c.setEndWord("");
            c.setSilenceSeconds(0);
            List<String> w = c.warnings();
            assertEquals(1, w.size(), w.toString());
            // 关键信息必须在**一句话之内**说清：状态标签只有两行（约 560px），
            // 而这条曾经写到 694px —— 结果被省略号吃掉"60 秒"和"截断"这两个后果。
            assertTrue(w.get(0).contains("60"), w.get(0));
            assertTrue(w.get(0).contains("截断"), w.get(0));
            assertTrue(w.get(0).length() <= 40, "提醒要短到两行放得下：" + w.get(0).length());
            // 关键：它是提醒不是拒绝
            c.validate();
        }

        @Test
        @DisplayName("只有静音关着也不提醒（结束词还能收尾）")
        void silenceOffAloneIsQuiet() {
            AppConfig c = new AppConfig();
            c.setSilenceSeconds(0);
            assertTrue(c.warnings().isEmpty());
        }
    }

    // ============================================================ 枚举解析

    @Nested
    @DisplayName("发送按键与贴边方向")
    class Enums {

        @Test
        @DisplayName("发送按键按显示名与枚举名都能解析")
        void sendKeyParsing() {
            assertEquals(SendKey.ENTER, SendKey.fromDisplay("Enter"));
            assertEquals(SendKey.CTRL_ENTER, SendKey.fromDisplay("Ctrl+Enter"));
            assertEquals(SendKey.CTRL_ENTER, SendKey.fromDisplay("ctrl + enter"));
            assertEquals(SendKey.CTRL_ENTER, SendKey.fromDisplay("CTRL_ENTER"));
        }

        @Test
        @DisplayName("非法发送按键报错，且消息里给出允许值")
        void badSendKeyRejected() {
            ConfigException e = assertThrows(ConfigException.class, () -> SendKey.fromDisplay("Shift+Enter"));
            assertTrue(e.getMessage().contains("Ctrl+Enter"), e.getMessage());
        }

        @Test
        @DisplayName("空 / null 发送按键报错")
        void nullSendKeyRejected() {
            assertThrows(ConfigException.class, () -> SendKey.fromDisplay(null));
        }

        @Test
        @DisplayName("贴边方向解析，空值视为 NONE")
        void dockSideParsing() {
            assertEquals(DockSide.NONE, DockSide.fromName(null));
            assertEquals(DockSide.NONE, DockSide.fromName(""));
            assertEquals(DockSide.LEFT, DockSide.fromName("left"));
            assertEquals(DockSide.RIGHT, DockSide.fromName("RIGHT"));
        }

        @Test
        @DisplayName("非法贴边方向报错")
        void badDockSideRejected() {
            assertThrows(ConfigException.class, () -> DockSide.fromName("TOP"));
        }
    }

    // ============================================================ 悬浮球位置

    @Nested
    @DisplayName("悬浮球位置与贴边状态（§12 #4：一并保存）")
    class BallGeometry {

        @Test
        @DisplayName("默认没有保存过位置")
        void noPositionByDefault() {
            assertFalse(new AppConfig().ball().hasPosition());
        }

        @Test
        @DisplayName("位置与贴边状态能一起往返")
        void positionAndDockRoundTrip() {
            AppConfig c = new AppConfig();
            c.ball().setPosition(1234, 567);
            c.ball().setDock(DockSide.RIGHT);
            AppConfig back = AppConfig.fromJsonText(c.toJsonText());
            assertTrue(back.ball().hasPosition());
            assertEquals(1234, back.ball().x());
            assertEquals(567, back.ball().y());
            assertEquals(DockSide.RIGHT, back.ball().dock());
        }

        @Test
        @DisplayName("负坐标（左侧显示器）也能保存")
        void negativeCoordinatesSupported() {
            AppConfig c = new AppConfig();
            c.ball().setPosition(-1920, -100);
            AppConfig back = AppConfig.fromJsonText(c.toJsonText());
            assertEquals(-1920, back.ball().x());
            assertEquals(-100, back.ball().y());
        }

        @Test
        @DisplayName("没有位置时不写出 x/y（避免写出 Integer.MIN_VALUE）")
        void absentPositionNotSerialized() {
            String json = new AppConfig().toJsonText();
            assertFalse(json.contains("MIN_VALUE"));
            assertFalse(json.contains("\"x\""), json);
        }
    }

    // ============================================================ 残缺与非法配置

    @Nested
    @DisplayName("从 JSON 读取")
    class FromJson {

        @Test
        @DisplayName("残缺配置缺项取默认值（用户手写配置也能起来）")
        void missingKeysUseDefaults() {
            AppConfig c = AppConfig.fromJsonText("{\"wakeWord\":\"小助手\"}");
            assertEquals("小助手", c.wakeWord());
            assertEquals(AppConfig.DEFAULT_END_WORD, c.endWord());
            assertEquals(5, c.silenceSeconds());
        }

        @Test
        @DisplayName("空对象得到全默认值")
        void emptyObjectAllDefaults() {
            AppConfig c = AppConfig.fromJsonText("{}");
            assertEquals(AppConfig.DEFAULT_WAKE_WORD, c.wakeWord());
        }

        @Test
        @DisplayName("读到时也强制校验：非法静音秒数直接报错")
        void validationOnRead() {
            assertThrows(ConfigException.class,
                    () -> AppConfig.fromJsonText("{\"silenceSeconds\":99}"));
        }

        @Test
        @DisplayName("类型不对时报错并指出配置项名")
        void typeMismatchReportsKey() {
            JsonException e = assertThrows(JsonException.class,
                    () -> AppConfig.fromJsonText("{\"silenceSeconds\":\"五秒\"}"));
            assertTrue(e.getMessage().contains("silenceSeconds"), e.getMessage());
        }

        @Test
        @DisplayName("布尔值写成字符串也接受")
        void booleanFromString() {
            AppConfig c = AppConfig.fromJsonText("{\"autoSend\":\"true\"}");
            assertTrue(c.autoSend());
            assertThrows(JsonException.class, () -> AppConfig.fromJsonText("{\"autoSend\":\"大概吧\"}"));
        }

        @Test
        @DisplayName("JSON 根不是对象时报错")
        void nonObjectRootRejected() {
            assertThrows(JsonException.class, () -> AppConfig.fromJsonText("[1,2,3]"));
        }

        @Test
        @DisplayName("写出的 JSON 能被人读懂（带缩进、键有序）")
        void humanReadable() {
            String json = new AppConfig().toJsonText();
            assertTrue(json.contains("\n  \"wakeWord\": \"子曰\""), json);
        }

        @Test
        @DisplayName("未知键被忽略而不是报错（向前兼容）")
        void unknownKeysIgnored() {
            AppConfig c = AppConfig.fromJsonText("{\"futureKey\":123,\"wakeWord\":\"子曰\"}");
            assertEquals("子曰", c.wakeWord());
        }
    }

    // ============================================================ JsonCodec

    @Nested
    @DisplayName("JsonCodec 本身")
    class Codec {

        @Test
        @DisplayName("中文与代理对能正确往返（不依赖平台编码）")
        void unicodeRoundTrip() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cn", "子曰：到此为止。");
            m.put("emoji", "😀🎤");
            String json = JsonCodec.write(m);
            Map<String, Object> back = JsonCodec.parseObject(json);
            assertEquals("子曰：到此为止。", back.get("cn"));
            assertEquals("😀🎤", back.get("emoji"));
        }

        @Test
        @DisplayName("转义字符正确解析")
        void escapes() {
            Map<String, Object> m = JsonCodec.parseObject(
                    "{\"a\":\"line1\\nline2\",\"b\":\"quote\\\"here\",\"c\":\"back\\\\slash\"}");
            assertEquals("line1\nline2", m.get("a"));
            assertEquals("quote\"here", m.get("b"));
            assertEquals("back\\slash", m.get("c"));
        }

        @Test
        @DisplayName("\\uXXXX 转义正确解析")
        void unicodeEscape() {
            Map<String, Object> m = JsonCodec.parseObject("{\"a\":\"\\u5b50\\u66f0\"}");
            assertEquals("子曰", m.get("a"));
        }

        @Test
        @DisplayName("UTF-8 BOM 被容忍（Windows 记事本另存会加）")
        void bomTolerated() {
            Map<String, Object> m = JsonCodec.parseObject("\uFEFF{\"a\":1}");
            assertEquals(1, JsonCodec.intVal(m, "a", 0));
        }

        @Test
        @DisplayName("数字、布尔、null、数组都能解析")
        void allValueKinds() {
            Object v = JsonCodec.parse("{\"i\":1,\"d\":1.5,\"b\":true,\"n\":null,\"a\":[1,2]}");
            assertTrue(v instanceof Map);
            Map<String, Object> m = JsonCodec.parseObject("{\"i\":1,\"d\":1.5,\"b\":true,\"n\":null,\"a\":[1,2]}");
            assertEquals(1, JsonCodec.intVal(m, "i", 0));
            assertEquals(1.5, JsonCodec.num(m, "d", 0));
            assertTrue(JsonCodec.bool(m, "b", false));
            assertEquals(2, ((List<?>) m.get("a")).size());
        }

        @Test
        @DisplayName("非法输入报错并给出**行号**（配置出错时要能定位）")
        void errorsCarryLineNumber() {
            JsonException e = assertThrows(JsonException.class,
                    () -> JsonCodec.parseObject("{\n  \"a\": 1,\n  \"b\": ,\n}"));
            assertTrue(e.getMessage().contains("行"), e.getMessage());
        }

        @Test
        @DisplayName("未闭合的字符串 / 括号报错而不是死循环")
        void unterminatedInputsRejected() {
            assertThrows(JsonException.class, () -> JsonCodec.parseObject("{\"a\":\"unterminated"));
            assertThrows(JsonException.class, () -> JsonCodec.parseObject("{\"a\":1"));
            assertThrows(JsonException.class, () -> JsonCodec.parseObject(""));
        }

        @Test
        @DisplayName("尾随逗号被拒绝（JSON 规范如此，配置里出现即错）")
        void trailingCommaRejected() {
            assertThrows(JsonException.class, () -> JsonCodec.parseObject("{\"a\":1,}"));
            assertThrows(JsonException.class, () -> JsonCodec.parseObject("{\"a\":[1,2,]}"));
        }

        @Test
        @DisplayName("多余内容被拒绝")
        void trailingContentRejected() {
            assertThrows(JsonException.class, () -> JsonCodec.parseObject("{\"a\":1} extra"));
        }

        @Test
        @DisplayName("整数写成 1.0 也能当 int 读（手写配置常见）")
        void integralDoubleAsInt() {
            Map<String, Object> m = JsonCodec.parseObject("{\"n\":5.0}");
            assertEquals(5, JsonCodec.intVal(m, "n", 0));
        }

        @Test
        @DisplayName("非整数当 int 读要报错")
        void nonIntegralAsIntRejected() {
            Map<String, Object> m = JsonCodec.parseObject("{\"n\":5.5}");
            assertThrows(JsonException.class, () -> JsonCodec.intVal(m, "n", 0));
        }

        @Test
        @DisplayName("浮点值与不规范写法都能读（JsonCodec 的宽松取值）")
        void looseNumberParsing() {
            assertEquals(1.5, JsonCodec.num(JsonCodec.parseObject("{}"), "d", 1.5));
            assertEquals(1.5, JsonCodec.num(JsonCodec.parseObject("{\"d\":1.5}"), "d", 0));
            assertEquals(2.0, JsonCodec.num(JsonCodec.parseObject("{\"d\":\"2.0\"}"), "d", 0));
        }

        @Test
        @DisplayName("取不存在的键时给默认值（缺失键必须走默认，不能变成 null）")
        void missingKeyUsesDefault() {
            Map<String, Object> m = JsonCodec.parseObject("{}");
            assertEquals("x", JsonCodec.str(m, "k", "x"));
            assertEquals("", JsonCodec.str(m, "k", ""));
            assertEquals(3, JsonCodec.intVal(m, "n", 3));
            assertTrue(JsonCodec.bool(m, "b", true));
        }

        @Test
        @DisplayName("显式 null 值让 str 返回默认值（配置里写 null 等同于不写）")
        void explicitNullUsesDefault() {
            Map<String, Object> m = JsonCodec.parseObject("{\"k\":null}");
            assertEquals("x", JsonCodec.str(m, "k", "x"));
        }
    }

    @Nested
    @DisplayName("热词表解析（生产真正用的那份：AppConfig.hotwordMap）")
    class Hotwords {

        private AppConfig cfgWith(String spec) {
            AppConfig c = new AppConfig();
            c.setHotwords(spec);
            return c;
        }

        @Test
        @DisplayName("逗号、中文逗号、分号、换行都能分隔")
        void separators() {
            for (String spec : new String[] {"a=1,b=2", "a=1，b=2", "a=1;b=2", "a=1\nb=2"}) {
                java.util.Map<String, String> m = cfgWith(spec).hotwordMap();
                assertEquals(2, m.size(), spec);
                assertEquals("1", m.get("a"), spec);
                assertEquals("2", m.get("b"), spec);
            }
        }

        @Test
        @DisplayName("格式不对的项**跳过而不是报错** —— 热词不该让配置整体校验失败")
        void malformedEntriesAreSkipped() {
            java.util.Map<String, String> m = cfgWith("a=1, 没有等号, =2, b=, a=1").hotwordMap();
            assertEquals(1, m.size());
            assertEquals("1", m.get("a"));
        }

        @Test
        @DisplayName("值里的等号保留（只在第一个等号处切分）")
        void valueMayContainEquals() {
            assertEquals("x=y", cfgWith("k=x=y").hotwordMap().get("k"));
        }

        @Test
        @DisplayName("空输入返回空表")
        void emptyInput() {
            assertTrue(cfgWith(null).hotwordMap().isEmpty());
            assertTrue(cfgWith("").hotwordMap().isEmpty());
            assertTrue(cfgWith("   ").hotwordMap().isEmpty());
        }

        @Test
        @DisplayName("自己映射到自己不算一条（避免无谓的替换）")
        void selfMappingIgnored() {
            assertTrue(cfgWith("AI=AI").hotwordMap().isEmpty());
        }

        @Test
        @DisplayName("配置往返：写出去再读回来，热词表不变")
        void survivesRoundTrip() {
            AppConfig out = cfgWith("诶爱=AI, 皮迪艾夫=PDF");
            AppConfig back = AppConfig.fromJsonText(out.toJsonText());
            assertEquals(out.hotwordMap(), back.hotwordMap());
        }
    }

    /**
     * 副本（{@code copy()}）—— 这一组守的是一个**真实缺陷**，不是接口洁癖。
     *
     * <p>设置窗口原先直接持有 {@code host.config()} 返回的活配置对象，于是：
     * ① {@code App.applyConfig} 里「唤醒词变了吗」变成同一对象自比，恒为 false，
     * 改唤醒词永远不会重建检测器（界面却写着「已保存并立即生效」）；
     * ② 被 {@code validate()} 拒绝的非法值其实已经写进了运行中的配置。
     * 只要 {@code copy()} 不再独立，这两条就会重新出现。
     */
    @Nested
    @DisplayName("副本 copy()（设置窗口只在副本上改）")
    class Copy {

        private AppConfig full() {
            AppConfig c = new AppConfig();
            c.setWakeWord("小助手");
            c.setEndWord("完毕");
            c.setSilenceSeconds(7);
            c.setAutoSend(true);
            c.setSendKey(SendKey.CTRL_ENTER);
            c.setSendOnSilenceTimeout(true);
            c.setMaxSegmentSeconds(90);
            c.setCharGapMillis(35);
            c.setHotwords("诶爱=AI");
            c.ball().setPosition(1234, 567);
            c.ball().setDock(DockSide.RIGHT);
            c.ball().setDockEnabled(false);
            return c;
        }

        @Test
        @DisplayName("改副本不影响原对象（否则「改了没反应」与「非法值已生效」会一起回来）")
        void mutatingCopyLeavesOriginalAlone() {
            AppConfig live = full();
            AppConfig draft = live.copy();

            draft.setWakeWord("你好");
            draft.setEndWord("");
            draft.setSilenceSeconds(2);
            draft.setAutoSend(false);
            draft.setSendKey(SendKey.ENTER);
            draft.setMaxSegmentSeconds(30);
            draft.setCharGapMillis(0);
            draft.setHotwords("");
            draft.ball().setPosition(1, 2);
            draft.ball().setDock(DockSide.LEFT);

            assertEquals("小助手", live.wakeWord(), "原对象的唤醒词被副本改掉了");
            assertEquals("完毕", live.endWord());
            assertEquals(7, live.silenceSeconds());
            assertTrue(live.autoSend());
            assertEquals(SendKey.CTRL_ENTER, live.sendKey());
            assertEquals(90, live.maxSegmentSeconds());
            assertEquals(35, live.charGapMillis());
            assertEquals("诶爱=AI", live.hotwords());
            assertEquals(1234, live.ball().x());
            assertEquals(567, live.ball().y());
            assertEquals(DockSide.RIGHT, live.ball().dock());
        }

        @Test
        @DisplayName("每个字段都真的被复制了（漏一个就是「改它没反应」）")
        void copyCarriesEveryField() {
            AppConfig live = full();
            AppConfig draft = live.copy();

            assertEquals("小助手", draft.wakeWord());
            assertEquals("完毕", draft.endWord());
            assertEquals(7, draft.silenceSeconds());
            assertTrue(draft.autoSend());
            assertEquals(SendKey.CTRL_ENTER, draft.sendKey());
            assertTrue(draft.sendOnSilenceTimeout());
            assertEquals(90, draft.maxSegmentSeconds());
            assertEquals(35, draft.charGapMillis());
            assertEquals("诶爱=AI", draft.hotwords());
            assertEquals(1234, draft.ball().x());
            assertEquals(567, draft.ball().y());
            assertEquals(DockSide.RIGHT, draft.ball().dock());
            assertFalse(draft.ball().dockEnabled());
        }

        @Test
        @DisplayName("没保存过位置时副本也「没有位置」（x/y 是哨兵值，不能被当成有效坐标）")
        void copyKeepsMissingPositionMissing() {
            AppConfig live = new AppConfig();
            assertFalse(live.ball().hasPosition());
            AppConfig draft = live.copy();
            assertFalse(draft.ball().hasPosition(),
                    "副本凭空多出了位置，悬浮球会跑到 (MIN_VALUE, MIN_VALUE)");
        }

        @Test
        @DisplayName("副本与原文各自独立校验：副本非法不影响原文")
        void invalidCopyDoesNotPoisonOriginal() {
            AppConfig live = full();
            AppConfig draft = live.copy();
            draft.setWakeWord("");          // 非法
            assertThrows(ConfigException.class, draft::validate);
            live.validate();                // 原文必须仍然合法（不抛）
        }
    }

    /**
     * 大模型开关（{@code docs/DECISIONS.md} D1）。
     *
     * <p>它是"接受大模型的内存/启动代价，但给用户一条**自己说了算**的出口"
     * 这条决策的载体：关掉之后必须真的按小模型走（{@code App.loadRecognitionModel} 读它），
     * 而老配置（没有这个键）的行为不能变。
     */
    @Nested
    @DisplayName("大模型开关 useLargeModel（D1 的出口）")
    class LargeModelSwitch {

        @Test
        @DisplayName("默认开启：装好大模型即自动生效这个行为不能被改掉")
        void defaultsToTrue() {
            assertTrue(new AppConfig().useLargeModel());
            assertTrue(AppConfig.fromJsonText("{\"wakeWord\":\"子曰\"}").useLargeModel(),
                    "老配置里没有这个键 → 必须默认 true，行为与以前完全一致");
        }

        @Test
        @DisplayName("写进 JSON 再读回来（出口必须是看得见的，不能只活在内存里）")
        void roundTrip() {
            AppConfig c = new AppConfig();
            c.setUseLargeModel(false);
            String json = c.toJsonText();
            assertTrue(json.contains("useLargeModel"),
                    "要写出去，否则用户不知道有这个开关：\n" + json);
            assertFalse(AppConfig.fromJsonText(json).useLargeModel());
        }

        @Test
        @DisplayName("copy() 带着它走（设置窗口在副本上改，漏了它就等于开关被静默重置）")
        void copyCarriesTheSwitch() {
            AppConfig c = new AppConfig();
            c.setUseLargeModel(false);
            assertFalse(c.copy().useLargeModel());
        }

        @Test
        @DisplayName("它不影响配置合法性（这是取舍，不是错误）")
        void neverRejectedByValidation() {
            AppConfig c = new AppConfig();
            c.setUseLargeModel(false);
            c.validate();   // 不抛
        }
    }

    /**
     * 注入间隔的下限（{@code PENDING-ISSUES} P1.6）。
     *
     * <p>0 曾经是合法值，而它是**已知的丢字值**（灌太快时微信这类自绘输入框会主动丢掉
     * 后面的字符，而 {@code SendInput} 会如实报告"全部写入成功"）。所以下限提到 5ms；
     * 但老配置里可能残留 0，读入时应当**抬到下限**，而不是让程序起不来。
     */
    @Nested
    @DisplayName("注入间隔下限 charGapMillis ≥ 5ms")
    class CharGapFloor {

        @Test
        @DisplayName("下限不再是 0（0 是已知的丢字值）")
        void floorIsNotZero() {
            assertTrue(AppConfig.MIN_CHAR_GAP_MILLIS >= 5,
                    "0 会让自绘输入框丢字，不该留在可行域里；实际下限 = "
                            + AppConfig.MIN_CHAR_GAP_MILLIS);
        }

        @Test
        @DisplayName("老配置里的 0 被抬到下限，而不是让程序起不来")
        void legacyZeroIsClampedOnRead() {
            AppConfig c = AppConfig.fromJsonText("{\"wakeWord\":\"子曰\",\"charGapMillis\":0}");
            assertEquals(AppConfig.MIN_CHAR_GAP_MILLIS, c.charGapMillis(),
                    "读入时必须抬到下限：直接拒绝会让老配置的机器再也起不来（P5.9 那个坑）");
            c.validate();   // 抬过之后是合法配置
        }

        @Test
        @DisplayName("程序内设成 0 仍算非法（迁移只针对读入的历史值）")
        void programmaticZeroIsStillRejected() {
            AppConfig c = new AppConfig();
            c.setCharGapMillis(0);
            assertThrows(ConfigException.class, c::validate);
        }

        @Test
        @DisplayName("正常值原样保留")
        void normalValueSurvives() {
            AppConfig c = AppConfig.fromJsonText("{\"wakeWord\":\"子曰\",\"charGapMillis\":40}");
            assertEquals(40, c.charGapMillis());
        }
    }
}
