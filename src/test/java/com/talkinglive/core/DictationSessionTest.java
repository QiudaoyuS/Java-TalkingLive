package com.talkinglive.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 一次听写的上下文测试（{@code DESIGN.md} §4.5：纯逻辑，可单测）。
 *
 * <p>它承载三件事，每件都有明确的文档要求：
 * 目标窗口锁定（§7 的注入前校验依据）、段落 PCM 缓存（给精化引擎）、
 * 已注入文本的**码点**账本（§4.3）。
 */
class DictationSessionTest {

    @Nested
    @DisplayName("目标窗口锁定")
    class TargetWindow {

        @Test
        @DisplayName("开始时的目标窗口与标题被保存下来")
        void storesTarget() {
            DictationSession s = new DictationSession(7, 0x1234ABCDL, "记事本", 60);
            assertEquals(0x1234ABCDL, s.targetWindow());
            assertEquals("记事本", s.targetWindowTitle());
            assertEquals(7, s.generation());
        }

        @Test
        @DisplayName("标题为 null 时退化为空串（不抛异常）")
        void nullTitleBecomesEmpty() {
            assertEquals("", new DictationSession(1, 0, null, 60).targetWindowTitle());
        }

        @Test
        @DisplayName("toString 不含转写内容（日志安全）")
        void toStringIsLogSafe() {
            DictationSession s = new DictationSession(1, 0x2A, "记事本", 60);
            s.setPreviewText("这是机密内容");
            String t = s.toString();
            assertFalse(t.contains("机密"), t);
            assertTrue(t.contains("0x2a"), t);
        }
    }

    @Nested
    @DisplayName("段落 PCM 缓存与时长上限（§7 防止长录音内存增长）")
    class AudioBuffer {

        @Test
        @DisplayName("追加的音频能完整读回")
        void appendAndSnapshot() {
            DictationSession s = new DictationSession(1, 0, "t", 1);
            byte[] a = {1, 2, 3, 4};
            byte[] b = {5, 6};
            s.appendPcm(a);
            s.appendPcm(b);
            assertEquals(6, s.pcmLength());
            assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, s.pcmSnapshot());
        }

        @Test
        @DisplayName("快照是拷贝：改它不影响内部缓存")
        void snapshotIsCopy() {
            DictationSession s = new DictationSession(1, 0, "t", 1);
            s.appendPcm(new byte[] {1, 2});
            byte[] snap = s.pcmSnapshot();
            snap[0] = 99;
            assertArrayEquals(new byte[] {1, 2}, s.pcmSnapshot());
        }

        @Test
        @DisplayName("带偏移追加只取指定范围")
        void appendWithOffset() {
            DictationSession s = new DictationSession(1, 0, "t", 1);
            s.appendPcm(new byte[] {1, 2, 3, 4, 5, 6}, 2, 3);
            assertArrayEquals(new byte[] {3, 4, 5}, s.pcmSnapshot());
        }

        @Test
        @DisplayName("超过时长上限时截断并置满标志（调用方据此结束本段）")
        void capsAtMaxSeconds() {
            // 1 秒上限 = 16000 样本 = 32000 字节
            DictationSession s = new DictationSession(1, 0, "t", 1);
            boolean full = s.appendPcm(new byte[32000]);
            assertTrue(full, "正好填满应返回 true");
            assertTrue(s.segmentFull());
            assertEquals(32000, s.pcmLength());

            // 再追加不会增长，仍返回 true
            boolean stillFull = s.appendPcm(new byte[1000]);
            assertTrue(stillFull);
            assertEquals(32000, s.pcmLength(), "超过上限后不能再增长（内存保护）");
        }

        @Test
        @DisplayName("一次追加就超过上限时也只截断到上限")
        void singleOversizedAppendTruncated() {
            DictationSession s = new DictationSession(1, 0, "t", 1);
            s.appendPcm(new byte[100000]);
            assertEquals(32000, s.pcmLength());
        }

        @Test
        @DisplayName("recordedSeconds 按 16kHz/16bit 折算")
        void recordedSeconds() {
            DictationSession s = new DictationSession(1, 0, "t", 10);
            s.appendPcm(new byte[32000]);       // 1 秒
            assertEquals(1.0, s.recordedSeconds(), 1e-9);
            s.appendPcm(new byte[16000]);       // 再 0.5 秒
            assertEquals(1.5, s.recordedSeconds(), 1e-9);
        }

        @Test
        @DisplayName("太短的音频不算可用（误触发保护）")
        void tooShortIsNotUsable() {
            DictationSession s = new DictationSession(1, 0, "t", 10);
            assertFalse(s.hasUsableAudio(), "零长度不可用");
            s.appendPcm(new byte[1600]);        // 0.05 秒
            assertFalse(s.hasUsableAudio(), "0.05 秒太短");
            s.appendPcm(new byte[8000]);        // 累计 0.3 秒
            assertTrue(s.hasUsableAudio());
        }

        @Test
        @DisplayName("空追加与 null 追加不产生副作用")
        void emptyAppendsIgnored() {
            DictationSession s = new DictationSession(1, 0, "t", 10);
            s.appendPcm(new byte[0]);
            s.appendPcm(null);
            assertEquals(0, s.pcmLength());
            assertFalse(s.segmentFull());
        }
    }

    @Nested
    @DisplayName("最终文本定稿（§7 的降级路径）")
    class FinalText {

        @Test
        @DisplayName("有精化结果时优先用精化结果")
        void refinedWins() {
            DictationSession s = new DictationSession(1, 0, "t", 10);
            s.setPreviewText("这个需球很明确");
            assertEquals("这个需求很明确。", s.resolveFinalText("这个需求很明确。"));
        }

        @Test
        @DisplayName("精化为空时退回预览文本（§7：whisper 失败就退回 Vosk 预览）")
        void fallsBackToPreview() {
            DictationSession s = new DictationSession(1, 0, "t", 10);
            s.setPreviewText("这个需球很明确");
            assertEquals("这个需球很明确", s.resolveFinalText(""));
            assertEquals("这个需球很明确", s.resolveFinalText(null));
            assertEquals("这个需球很明确", s.resolveFinalText("   "));
        }

        @Test
        @DisplayName("两边都空时返回空串（调用方据此不注入）")
        void bothEmpty() {
            DictationSession s = new DictationSession(1, 0, "t", 10);
            assertEquals("", s.resolveFinalText(null));
            s.setPreviewText("   ");
            assertEquals("", s.resolveFinalText(null));
        }

        @Test
        @DisplayName("首尾空白被裁掉")
        void trims() {
            DictationSession s = new DictationSession(1, 0, "t", 10);
            assertEquals("内容", s.resolveFinalText("  内容  "));
        }
    }

    @Nested
    @DisplayName("已注入文本账本（§4.3：一律用码点）")
    class InjectedLedger {

        @Test
        @DisplayName("记录并累计已注入文本")
        void recordsInjected() {
            DictationSession s = new DictationSession(1, 0, "t", 10);
            s.appendInjected("今天");
            s.appendInjected("天气不错");
            assertEquals("今天天气不错", s.injectedText());
        }

        @Test
        @DisplayName("码点计数不数错代理对")
        void codePointsNotUtf16() {
            DictationSession s = new DictationSession(1, 0, "t", 10);
            s.appendInjected("a😀b");
            assertEquals(3, s.injectedCodePointCount());
            assertEquals(4, s.injectedText().length(), "UTF-16 长度是 4——用它会多退一格");
        }

        @Test
        @DisplayName("空串与 null 不记账")
        void ignoresEmpty() {
            DictationSession s = new DictationSession(1, 0, "t", 10);
            s.appendInjected("");
            s.appendInjected(null);
            assertEquals(0, s.injectedCodePointCount());
        }

        @Test
        @DisplayName("增量注入多次调用账本也正确（为 StablePrefixPolicy 预留）")
        void multipleIncrements() {
            DictationSession s = new DictationSession(1, 0, "t", 10);
            s.appendInjected("第一段");
            s.appendInjected("第二段");
            s.appendInjected("第三段");
            assertEquals(9, s.injectedCodePointCount());
        }
    }

    @Test
    @DisplayName("PCM 缓存按上限预分配，不会因为长录音无限增长（§7 / §12 #5）")
    void bufferIsPreallocated() {
        // 上限 2 秒 → 内部数组固定 2 秒大小；追加 100 秒也不会增长
        DictationSession s = new DictationSession(1, 0, "t", 2);
        for (int i = 0; i < 100; i++) {
            s.appendPcm(new byte[32000]);
        }
        assertEquals(2 * 32000, s.pcmLength());
        assertEquals(2.0, s.recordedSeconds(), 1e-9);
    }
}
