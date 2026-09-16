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
        @DisplayName("空结束词被拒绝")
        void emptyEndWordRejected() {
            AppConfig c = new AppConfig();
            c.setEndWord("");
            assertThrows(ConfigException.class, c::validate);
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
            assertTrue(e.getMessage().contains("结束词"));
            assertTrue(e.getMessage().contains("静音"));
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
}
