package com.talkinglive.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 长时语音门（挡外部音频误唤醒）的测试。
 *
 * <p>它来自用户实测反馈：<b>「我在放视频声音时软件自动开启录制」</b>。
 * 查清的事实链写在 {@link WakeGate} 的类注释里，一句话概括：
 * 受限语法模式不给置信度、关键词模式在这版原生库里不支持，所以"看着像但不是"的
 * 假命中**无法从引擎层过滤**，只能靠上层启发式。
 *
 * <p>这里测的就是那条启发式：<b>说话人不会在连续说话 3 秒之后才喊唤醒词。</b>
 * 正常用法是「安静 → 说唤醒词」，唤醒词前面必有停顿；视频/音乐是连续音频，
 * 假命中总在长时间连续有声的中间。
 */
class WakeGateTest {

    /** 喂若干秒的持续有声。 */
    private static void feedSpeech(WakeGate g, double seconds) {
        for (double t = 0; t < seconds; t += 0.05) {
            g.accept(0.05, 0.05);   // 明显高于 SPEECH_RMS
        }
    }

    /** 喂若干秒的安静。 */
    private static void feedSilence(WakeGate g, double seconds) {
        for (double t = 0; t < seconds; t += 0.05) {
            g.accept(0.001, 0.05);
        }
    }

    @Nested
    @DisplayName("正常用法：唤醒词前面有停顿 → 放行")
    class Normal {

        @Test
        @DisplayName("安静之后立刻喊唤醒词 → 放行（最常见的用法）")
        void silenceThenWakeIsAllowed() {
            WakeGate g = new WakeGate();
            feedSilence(g, 2.0);
            assertTrue(g.allowWake(), "安静环境里喊唤醒词必须放行");
        }

        @Test
        @DisplayName("说了一句短话再喊唤醒词 → 放行（连续有声没超过阈值）")
        void shortSpeechThenWakeIsAllowed() {
            WakeGate g = new WakeGate();
            feedSilence(g, 1.0);
            feedSpeech(g, 1.5);       // 短句
            assertTrue(g.allowWake(), "连续有声 1.5s 还在阈值内，必须放行");
        }

        @Test
        @DisplayName("刚说完一句话（中间有停顿）紧接着喊 → 放行")
        void pauseResetsTheTimer() {
            WakeGate g = new WakeGate();
            feedSpeech(g, 2.5);
            feedSilence(g, 0.5);      // 换气停顿，超过 PAUSE_RESET_SECONDS
            feedSpeech(g, 0.3);
            assertTrue(g.allowWake(),
                    "停顿必须重置计时，否则正常用户说完一句再喊唤醒词会被误挡");
        }
    }

    @Nested
    @DisplayName("外部音频：长时间连续有声 → 挡下")
    class ExternalAudio {

        @Test
        @DisplayName("连续有声超过 3 秒后才匹配到唤醒词 → 挡下（视频/音乐）")
        void longContinuousSpeechIsBlocked() {
            WakeGate g = new WakeGate();
            feedSpeech(g, 5.0);
            assertFalse(g.allowWake(),
                    "连续有声 5s 之后的『唤醒词』应判为视频里的语音，不能开始录音");
        }

        @Test
        @DisplayName("连续有声刚好超过阈值 → 挡下（边界）")
        void justOverThresholdIsBlocked() {
            WakeGate g = new WakeGate();
            feedSpeech(g, WakeGate.MAX_SPEECH_RUN_SECONDS + 0.2);
            assertFalse(g.allowWake());
        }

        @Test
        @DisplayName("连续有声刚好没到阈值 → 放行（边界）")
        void justUnderThresholdIsAllowed() {
            WakeGate g = new WakeGate();
            feedSpeech(g, WakeGate.MAX_SPEECH_RUN_SECONDS - 0.2);
            assertTrue(g.allowWake());
        }
    }

    @Nested
    @DisplayName("被挡下之后必须能恢复（不能让一次误命中把软件锁死）")
    class Recovery {

        @Test
        @DisplayName("挡下一次之后，安静一会儿再喊 → 可以正常唤醒")
        void recoversAfterSilence() {
            WakeGate g = new WakeGate();
            feedSpeech(g, 5.0);
            assertFalse(g.allowWake(), "第一次（外部音频）应被挡下");
            g.resetAfterWake();

            // 用户关掉视频，安静下来再喊唤醒词
            feedSilence(g, 1.0);
            assertTrue(g.allowWake(), """
                    被挡下之后必须恢复。若这里失败，说明一次误命中会让软件
                    再也唤不醒 —— 那比误唤醒严重得多。
                    """);
        }

        @Test
        @DisplayName("连续两次外部音频命中都各自被挡（计时不累加成『永久锁死』）")
        void repeatedExternalHitsAreEachBlocked() {
            WakeGate g = new WakeGate();
            feedSpeech(g, 5.0);
            assertFalse(g.allowWake());
            g.resetAfterWake();
            feedSpeech(g, 5.0);
            assertFalse(g.allowWake());

            // 但安静之后仍然能唤醒
            feedSilence(g, 1.0);
            assertTrue(g.allowWake());
        }
    }

    @Nested
    @DisplayName("帧时长的处理")
    class Frames {

        @Test
        @DisplayName("时长为 0 的帧不影响计时")
        void zeroLengthFrameIsIgnored() {
            WakeGate g = new WakeGate();
            feedSpeech(g, 1.0);
            double before = g.speechRunSeconds();
            g.accept(0.5, 0);
            assertTrue(Math.abs(g.speechRunSeconds() - before) < 1e-9);
        }

        @Test
        @DisplayName("字与字之间的短暂低能量不算停顿（否则这条门形同虚设）")
        void briefDipsDoNotReset() {
            WakeGate g = new WakeGate();
            // 模拟"连续说话"：有声 0.25s + 极短间隙 0.1s，反复
            for (int i = 0; i < 20; i++) {
                feedSpeech(g, 0.25);
                feedSilence(g, 0.1);   // 短于 PAUSE_RESET_SECONDS
            }
            assertFalse(g.allowWake(), """
                    连续说话时字与字之间本来就有几十毫秒的低能量间隙。
                    若一有间隙就重置计时，这条门永远到不了阈值、等于没有。
                    """);
        }
    }
}
