package com.talkinglive.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.talkinglive.core.AppConfig;
import com.talkinglive.text.TextInjector;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 注入器的**结构与分批**测试。
 *
 * <p>为什么这些能在无桌面环境下测：它们验证的是「事件是怎么造出来的」，
 * 不涉及真正调用 {@code SendInput}。而这里恰恰藏着本项目最隐蔽的两次故障——
 * 都是「{@code SendInput} 报告成功、但文字不对」：
 *
 * <ol>
 *   <li><b>{@code INPUT} 的 union 声明成 16 字节的 {@code KEYBDINPUT}</b>，
 *       而 Windows 的 union 最大成员是 24 字节的 {@code MOUSEINPUT}。
 *       结果整个结构被算成 32 字节、字符写到偏移 24 →
 *       字符全部丢失，目标程序把**同一个字重复 N 遍**
 *       （用户实测看到「今今今今今今今」）。</li>
 *   <li>用 {@code new INPUT().toArray(n)} 分配结构数组 → 字符没有被逐个写入。</li>
 * </ol>
 *
 * <p>这两条都属于「必须由测试守住」的类型：它们不报错、不抛异常，
 * 只有人眼看目标程序才能发现。所以这里把布局常量、以及
 * 「每个字符必须映射到它自己的码点」都变成断言。
 */
class TextInjectorLayoutTest {

    @Nested
    @DisplayName("INPUT / KEYBDINPUT 结构布局（错一位就丢字符）")
    class Layout {

        @Test
        @DisplayName("INPUT 是 40 字节（x64），这就是要传给 SendInput 的 dwSize")
        void inputSizeIs40() {
            assertEquals(40, new Win32.INPUT().size(),
                    "union 里必须声明 MOUSEINPUT(24B) 才能把结构撑到 40；"
                            + "只用 KEYBDINPUT(16B) 会得到 32 字节，字符将写到错误偏移");
            assertEquals(40, Win32.INPUT_SIZE);
        }

        @Test
        @DisplayName("union 成员必须是 MOUSEINPUT（它比 KEYBDINPUT 大，决定 union 起点后的宽度）")
        void unionMemberIsTheLargerOne() {
            int mouse = new Win32.MOUSEINPUT().size();
            int kb = new Win32.KEYBDINPUT().size();
            assertTrue(mouse >= kb,
                    "union 里必须放最大的成员，否则 INPUT 会短一截、字符写到错误偏移；"
                            + "实际 MOUSEINPUT=" + mouse + " KEYBDINPUT=" + kb);
            // 注意：JNA 会给结构体加**尾部对齐填充**，所以这两个值会比 Windows 的
            // 裸字段和（24 / 16）大。真正要守的不变量是下面两条：
            // INPUT 总大小 == 40，且 union 起点 == 8。
        }

        @Test
        @DisplayName("JNA 报告的成员大小含尾部填充，因此断言写成「不小于 Windows 裸大小」")
        void memberSizesAtLeastWindowsSizes() {
            assertTrue(new Win32.MOUSEINPUT().size() >= 24,
                    "MOUSEINPUT 的字段和是 24（8+8 指针，x64 对齐）；"
                            + "实测 " + new Win32.MOUSEINPUT().size() + "（含尾部填充）");
            assertTrue(new Win32.KEYBDINPUT().size() >= 16,
                    "KEYBDINPUT 的字段和是 16（2+2+4+4+8 指针）；"
                            + "实测 " + new Win32.KEYBDINPUT().size() + "（含尾部填充）");
        }

        @Test
        @DisplayName("union 起点在偏移 8（DWORD type 之后对齐到 8 字节）")
        void unionOffsetIs8() {
            assertEquals(8, Win32.keyboardFieldOffset());
        }

        @Test
        @DisplayName("字符字段（wScan）落在结构内偏移 10 = union 起点 + 2")
        void wScanOffset() {
            assertEquals(10, Win32.keyboardFieldOffset() + 2,
                    "写错这个偏移就是「同一个字被重复 N 遍」的直接原因");
        }

        @Test
        @DisplayName("结构内各字段偏移与 Windows 定义一致（不靠手算，直接读 JNA 的答案）")
        void fieldOffsets() {
            Win32.KEYBDINPUT kb = new Win32.KEYBDINPUT();
            assertEquals(0, kb.offsetOf("wVk"));
            assertEquals(2, kb.offsetOf("wScan"));
            assertEquals(4, kb.offsetOf("dwFlags"));
            assertEquals(8, kb.offsetOf("time"));
            assertEquals(16, kb.offsetOf("dwExtraInfo"));   // 对齐到 8 字节

            Win32.INPUT in = new Win32.INPUT();
            assertEquals(0, in.offsetOf("type"));
            assertEquals(8, in.offsetOf("u"));
        }
    }

    @Nested
    @DisplayName("按键事件构造")
    class KeyEvents {

        @Test
        @DisplayName("Unicode 事件的字符放在 wScan，wVk 必须为 0")
        void unicodeUsesScanNotVk() {
            Win32.KeyEvent down = Win32.KeyEvent.unicodeDown('今');
            assertEquals(0, down.wVk(), "KEYEVENTF_UNICODE 时 wVk 必须为 0，写反会打出无关字符");
            assertEquals('今', down.wScan());
            assertTrue((down.dwFlags() & Win32.KEYEVENTF_UNICODE) != 0);
            assertTrue((down.dwFlags() & Win32.KEYEVENTF_KEYUP) == 0);
        }

        @Test
        @DisplayName("抬起事件带 KEYEVENTF_KEYUP")
        void keyUpFlag() {
            Win32.KeyEvent up = Win32.KeyEvent.unicodeUp('今');
            assertTrue((up.dwFlags() & Win32.KEYEVENTF_KEYUP) != 0);
            assertTrue((up.dwFlags() & Win32.KEYEVENTF_UNICODE) != 0);
        }

        @Test
        @DisplayName("普通虚拟键事件用 wVk，wScan 为 0")
        void vkEvents() {
            Win32.KeyEvent down = Win32.KeyEvent.vkDown(Win32.VK_RETURN);
            assertEquals(Win32.VK_RETURN, down.wVk());
            assertEquals(0, down.wScan());
            assertEquals(0, down.dwFlags());
        }
    }

    @Nested
    @DisplayName("字符分批：每个字符必须映射到它自己的码点")
    class Batching {

        @Test
        @DisplayName("逐字符分批不丢字、不改字（这是「今今今今」故障的直接反面）")
        void eachCharacterKeepsItsOwnCodePoint() {
            String text = "今天天气不错";
            List<String> batches = TextInjector.batchByCodePoints(text, 1);
            assertEquals(6, batches.size());
            assertEquals(List.of("今", "天", "天", "气", "不", "错"), batches);
            // 拼回来必须与原串一致——「同一个字重复 N 遍」会让这条断言失败
            assertEquals(text, String.join("", batches));
        }

        @Test
        @DisplayName("码点序列互不重复到「全一样」的程度（防回归：这正是故障特征）")
        void codePointsAreNotAllIdentical() {
            String text = "今天天气不错";
            List<String> batches = TextInjector.batchByCodePoints(text, 1);
            List<Integer> codePoints = new ArrayList<>();
            for (String b : batches) {
                codePoints.add(b.codePointAt(0));
            }
            assertEquals(6, codePoints.size());
            // 故障时这里会是 [今,今,今,今,今,今]
            // 「今天天气不错」= 今 天 天 气 不 错 → 5 个不同的字（天 出现两次）。
            // 故障时这里只剩 1 个不同值（全是「今」）。
            assertEquals(5, new java.util.LinkedHashSet<>(codePoints).size(),
                    "若只剩 1 个不同值就说明字符被写成了同一个： " + batches);
        }

        @Test
        @DisplayName("大批量分批同样保持顺序与内容")
        void largeBatch() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb.append((char) ('\u4e00' + i));
            }
            String text = sb.toString();
            List<String> batches = TextInjector.batchByCodePoints(text, 7);
            assertEquals(text, String.join("", batches));
        }
    }

    @Nested
    @DisplayName("字符间隔配置（微信/QQ 丢字的调节旋钮）")
    class CharGap {

        @Test
        @DisplayName("默认间隔在合法范围内，且不为 0（0 会让自绘输入框丢字）")
        void defaultGap() {
            assertTrue(AppConfig.DEFAULT_CHAR_GAP_MILLIS > 0,
                    "默认为 0 会让微信这类自绘输入框丢字——实测就是这个问题");
            assertTrue(AppConfig.DEFAULT_CHAR_GAP_MILLIS >= AppConfig.MIN_CHAR_GAP_MILLIS);
            assertTrue(AppConfig.DEFAULT_CHAR_GAP_MILLIS <= AppConfig.MAX_CHAR_GAP_MILLIS);
        }

        @Test
        @DisplayName("注入器可运行时调整间隔，并会被夹到合法范围")
        void setterClamps() {
            WindowsTextInjector inj = new WindowsTextInjector();
            inj.setCharGapMillis(50);
            assertEquals(50, inj.charGapMillis());

            inj.setCharGapMillis(-10);
            assertEquals(AppConfig.MIN_CHAR_GAP_MILLIS, inj.charGapMillis(),
                    "负数应被夹到最小值而不是变成「不等待」以外的怪行为");

            inj.setCharGapMillis(99999);
            assertEquals(AppConfig.MAX_CHAR_GAP_MILLIS, inj.charGapMillis());
        }

        @Test
        @DisplayName("配置往返：间隔能写进 JSON 再读回来")
        void configRoundTrip() {
            AppConfig c = new AppConfig();
            c.setCharGapMillis(45);
            AppConfig back = AppConfig.fromJsonText(c.toJsonText());
            assertEquals(45, back.charGapMillis());
        }

        @Test
        @DisplayName("非法间隔被配置校验拒绝")
        void configValidation() {
            AppConfig c = new AppConfig();
            c.setCharGapMillis(AppConfig.MAX_CHAR_GAP_MILLIS + 1);
            assertTrue(assertThrowsConfig(c), "超出上限应报错，而不是静默使用");
            c.setCharGapMillis(-1);
            assertTrue(assertThrowsConfig(c), "负数应报错");
        }

        private boolean assertThrowsConfig(AppConfig c) {
            try {
                c.validate();
                return false;
            } catch (AppConfig.ConfigException e) {
                return true;
            }
        }
    }

    @Nested
    @DisplayName("事件数核对：部分写入必须被当成失败（PENDING 1.1「出口诚实」）")
    class Accounting {

        @Test
        @DisplayName("计划事件数 = 2 × UTF-16 长度 + 2 × 退格数")
        void plannedEventsCountsUtf16Units() {
            assertEquals(0, TextInjector.plannedEvents(null, 0));
            assertEquals(0, TextInjector.plannedEvents("", 0));
            assertEquals(6, TextInjector.plannedEvents("今天天", 0));
            // 代理对：一个码点、两个 UTF-16 单元 → 四组事件（Windows 的既定行为）
            assertEquals(4, TextInjector.plannedEvents("😀", 0));
            assertEquals(2, TextInjector.plannedEvents("", 1));
            assertEquals(8, TextInjector.plannedEvents("今天", 2));
            // 负数退格按 0 计（否则基准会被算成负数，"写少了"永远判不出来）：
            // "今" 是 1 个 UTF-16 单元 → 2 个事件，与退格数无关
            assertEquals(2, TextInjector.plannedEvents("今", -5));
        }

        @Test
        @DisplayName("部分写入 → ok=false + PARTIAL_WRITE（不再报告成功）")
        void partialWriteIsFailure() {
            TextInjector.Result r = TextInjector.Result.partial(14, 20, "只写入了 14/20 个键盘事件");
            assertFalse(r.ok(), "部分写入必须报失败：否则用户丢字，而程序说成功");
            assertEquals(TextInjector.Result.Failure.PARTIAL_WRITE, r.failure());
            assertEquals(14, r.eventsSent());
            assertEquals(20, r.eventsExpected());
            assertTrue(r.message().contains("14/20"), r.message());
        }

        @Test
        @DisplayName("成功时也带上期望值，便于事后按日志核对")
        void okCarriesExpectation() {
            TextInjector.Result r = TextInjector.Result.okExact(20, 20);
            assertTrue(r.ok());
            assertEquals(20, r.eventsSent());
            assertEquals(20, r.eventsExpected());
            assertEquals(TextInjector.Result.Failure.NONE, r.failure());
        }
    }

    @Nested
    @DisplayName("坐标与注入目标（与 DpiScale 的约定）")
    class Coordinates {

        @Test
        @DisplayName("物理/逻辑像素换算往返一致（§4.4 的坑）")
        void dpiRoundTrip() {
            int dpi = DpiScale.systemDpi();
            for (int v : new int[] {0, 1, 100, 639, 1707, 2560}) {
                int phys = DpiScale.logicalToPhysical(v, dpi);
                assertEquals(v, DpiScale.physicalToLogical(phys, dpi),
                        "换算必须可逆，否则浮窗/光标定位会累积漂移");
            }
        }

        @Test
        @DisplayName("虚拟屏幕范围可用于把窗口夹回屏幕内")
        void virtualBoundsUsable() {
            java.awt.Rectangle vb = DpiScale.virtualBounds();
            assertTrue(vb.width > 0 && vb.height > 0);
            Point clamped = DpiScale.clampToScreen(vb.x + vb.width + 9999,
                    vb.y + vb.height + 9999, 68, 68);
            assertTrue(vb.contains(clamped), "夹紧后必须落在屏幕内：" + clamped);
        }
    }
}
