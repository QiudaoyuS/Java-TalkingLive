package com.talkinglive.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 音频层测试（{@code DESIGN.md} §4.5 / §9.1：不需要麦克风）。
 *
 * <p>这一层最重要的不是「转换能跑」，而是 {@code TECH-PLAN} §7 第 2 项点名的空白：
 * <b>真实麦克风原生格式通常是 48kHz，而识别引擎要吃 16kHz</b>。
 * 转换写错的症状是「识别不准」——最难查的一类问题，所以这里把能测的都测到。
 */
class AudioTest {

    private static AudioFormat fmt(float rate, int bits, int channels, boolean bigEndian) {
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, bits, channels,
                channels * bits / 8, rate, bigEndian);
    }

    /** 生成一个纯正弦波（16bit 小端单声道）的分帧数据。 */
    private static byte[] sine16le(double freq, double seconds, int sampleRate) {
        int n = (int) (seconds * sampleRate);
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            short s = (short) (Math.sin(2 * Math.PI * freq * i / sampleRate) * 20000);
            out[i * 2] = (byte) (s & 0xFF);
            out[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }

    /** 数过零点，用来验证重采样后的频率是否正确。 */
    private static int zeroCrossings(byte[] pcm) {
        int count = 0;
        int prev = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            if (i > 0 && ((s >= 0) != (prev >= 0))) {
                count++;
            }
            prev = s;
        }
        return count;
    }

    // ============================================================ 目标格式

    @Nested
    @DisplayName("目标格式（§3.1 第 3 项：16kHz/16bit/单声道）")
    class Target {

        @Test
        @DisplayName("目标格式就是 16000Hz / 16bit / 单声道 / 小端")
        void targetIsAsSpecified() {
            AudioFormat f = AudioConverter.targetFormat();
            assertEquals(16000f, f.getSampleRate());
            assertEquals(16, f.getSampleSizeInBits());
            assertEquals(1, f.getChannels());
            assertFalse(f.isBigEndian());
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, f.getEncoding());
        }

        @Test
        @DisplayName("已是目标格式时走零拷贝判定")
        void alreadyTargetDetected() {
            assertTrue(AudioConverter.isAlreadyTarget(AudioConverter.targetFormat()));
            assertFalse(AudioConverter.isAlreadyTarget(fmt(48000, 16, 1, false)));
            assertFalse(AudioConverter.isAlreadyTarget(fmt(16000, 16, 2, false)));
            assertFalse(AudioConverter.isAlreadyTarget(fmt(16000, 16, 1, true)));
            assertFalse(AudioConverter.isAlreadyTarget(fmt(16000, 8, 1, false)));
        }
    }

    // ============================================================ 重采样

    @Nested
    @DisplayName("重采样（TECH-PLAN §7 第 2 项的设计空白）")
    class Resampling {

        @Test
        @DisplayName("48kHz → 16kHz：样本数正好是三分之一")
        void downsampleLength() {
            byte[] src = sine16le(440, 1.0, 48000);
            byte[] out = AudioConverter.toTarget(src, fmt(48000, 16, 1, false));
            // 1 秒 16kHz = 16000 样本 = 32000 字节；允许少量取整误差
            assertTrue(Math.abs(out.length - 32000) <= 4,
                    "期望约 32000 字节，实际 " + out.length);
        }

        @Test
        @DisplayName("48kHz → 16kHz 后**频率不变**（这是最关键的一条）")
        void downsamplePreservesFrequency() {
            // 440Hz 的 1 秒音频：64800 采样点里过零约 880 次（每周期 2 次）
            byte[] src = sine16le(440, 1.0, 48000);
            byte[] out = AudioConverter.toTarget(src, fmt(48000, 16, 1, false));
            int zc = zeroCrossings(out);
            assertTrue(zc >= 860 && zc <= 900,
                    "过零数应约为 880（440Hz × 2），实际 " + zc
                            + "——若接近 2640 说明采样率写错，音频被当成三倍速");
        }

        @Test
        @DisplayName("44.1kHz → 16kHz 也能处理（不是整数倍）")
        void nonIntegerRatio() {
            byte[] src = sine16le(440, 1.0, 44100);
            byte[] out = AudioConverter.toTarget(src, fmt(44100, 16, 1, false));
            assertTrue(Math.abs(out.length - 32000) <= 8, "实际 " + out.length);
            int zc = zeroCrossings(out);
            assertTrue(zc >= 855 && zc <= 905, "过零数应约为 880，实际 " + zc);
        }

        @Test
        @DisplayName("已是 16kHz 时内容原样保留（不做多余处理）")
        void identityWhenAlreadyTarget() {
            byte[] src = sine16le(440, 0.5, 16000);
            byte[] out = AudioConverter.toTarget(src, AudioConverter.targetFormat());
            assertArrayEquals(src, out, "已经是目标格式就不该改动样本");
        }

        @Test
        @DisplayName("升采样也能工作（某些设备是 8kHz）")
        void upsample() {
            byte[] src = sine16le(300, 1.0, 8000);
            byte[] out = AudioConverter.toTarget(src, fmt(8000, 16, 1, false));
            assertTrue(Math.abs(out.length - 32000) <= 4, "实际 " + out.length);
            int zc = zeroCrossings(out);
            assertTrue(zc >= 580 && zc <= 620, "300Hz 应为约 600 次过零，实际 " + zc);
        }

        @Test
        @DisplayName("抗混叠滤波存在且归一（不会改变直流增益）")
        void antiAliasIsNormalized() {
            double[] ones = new double[64];
            java.util.Arrays.fill(ones, 1.0);
            double[] out = AudioConverter.antiAlias(ones, 3.0);
            // 中间部分应保持约 1.0（窗系数和为 1）
            assertTrue(Math.abs(out[32] - 1.0) < 0.02, "直流增益应约为 1，实际 " + out[32]);
        }

        @Test
        @DisplayName("整段重采样：ratio=1 时返回副本且不共享数组")
        void ratioOneReturnsCopy() {
            double[] src = {1, 2, 3};
            double[] out = AudioConverter.resample(src, 1.0);
            assertArrayEquals(src, out);
            assertFalse(out == src, "必须返回副本，避免调用方误改原数据");
        }

        @Test
        @DisplayName("空输入不炸，返回空")
        void emptyInput() {
            assertArrayEquals(new byte[0], AudioConverter.toTarget(new byte[0], fmt(48000, 16, 1, false)));
            assertArrayEquals(new byte[0], AudioConverter.toTarget(null, fmt(48000, 16, 1, false)));
            assertEquals(0, AudioConverter.resample(new double[0], 3.0).length);
        }
    }

    // ============================================================ 流式重采样

    @Nested
    @DisplayName("流式重采样（跨块必须连续，不能每块丢余数）")
    class Streaming {

        @Test
        @DisplayName("分块喂与整段喂的样本数一致（不丢样、不重复）")
        void chunkedMatchesWhole() {
            double[] whole = new double[48000];
            for (int i = 0; i < whole.length; i++) {
                whole[i] = Math.sin(2 * Math.PI * 440 * i / 48000.0);
            }
            double[] wholeOut = AudioConverter.resample(whole, 3.0);

            AudioConverter.StreamResampler sr = new AudioConverter.StreamResampler(3.0);
            int total = 0;
            int chunk = 1024;
            for (int off = 0; off < whole.length; off += chunk) {
                int len = Math.min(chunk, whole.length - off);
                double[] part = java.util.Arrays.copyOfRange(whole, off, off + len);
                total += sr.accept(part).length;
            }
            total += sr.drain().length;

            // 流式与整段在**块边界**上不可能逐样本一致：抗混叠窗的上下边距不同
            // （整段能取到全局前后文，分块只能取到块内前后文），因此每块最多会差
            // 一个目标样本的进出。这里只约束「差异不随块数累积」——真实的不变量
            // 由下面那条过零数用例守（相位漂移会让过零数明显偏离）。
            assertTrue(Math.abs(total - wholeOut.length) <= 4,
                    "整段 " + wholeOut.length + " 个样本，流式得到 " + total
                            + "（差 " + Math.abs(total - wholeOut.length) + "，上限 4）");
        }

        @Test
        @DisplayName("流式重采样不产生块边界跳变（连续喂一段正弦，检查过零数）")
        void noPhaseJumpAtBoundaries() {
            double[] whole = new double[48000];
            for (int i = 0; i < whole.length; i++) {
                whole[i] = Math.sin(2 * Math.PI * 440 * i / 48000.0);
            }
            AudioConverter.StreamResampler sr = new AudioConverter.StreamResampler(3.0);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int chunk = 480;   // 故意取一个不能整除的块长，暴露相位处理问题
            for (int off = 0; off < whole.length; off += chunk) {
                int len = Math.min(chunk, whole.length - off);
                double[] part = java.util.Arrays.copyOfRange(whole, off, off + len);
                byte[] pcm = AudioConverter.toPcm16le(sr.accept(part));
                bos.write(pcm, 0, pcm.length);
            }
            byte[] pcm = AudioConverter.toPcm16le(sr.drain());
            bos.write(pcm, 0, pcm.length);

            int zc = zeroCrossings(bos.toByteArray());
            assertTrue(zc >= 855 && zc <= 905,
                    "跨块相位跳变会让过零数明显偏离 880，实际 " + zc);
        }

        @Test
        @DisplayName("ratio=1 的流式路径原样返回")
        void identityStream() {
            AudioConverter.StreamResampler sr = new AudioConverter.StreamResampler(1.0);
            double[] in = {0.1, 0.2, 0.3};
            assertArrayEquals(in, sr.accept(in));
            assertEquals(0, sr.drain().length);
        }

        @Test
        @DisplayName("足够长的音频流式总量正确（48k→16k 十秒）")
        void longStreamLength() {
            AudioConverter.StreamResampler sr = new AudioConverter.StreamResampler(3.0);
            int total = 0;
            double[] chunk = new double[4800];
            for (int c = 0; c < 100; c++) {   // 100 × 4800 = 480000 = 10 秒 @48k
                java.util.Arrays.fill(chunk, 0.1);
                total += sr.accept(chunk).length;
            }
            total += sr.drain().length;
            assertTrue(Math.abs(total - 160000) <= 2,
                    "10 秒 48k 应得到约 160000 个 16k 样本，实际 " + total);
        }
    }

    // ============================================================ 位深与声道

    @Nested
    @DisplayName("位深与下混")
    class BitsAndChannels {

        @Test
        @DisplayName("立体声下混成单声道（取平均）")
        void stereoToMono() {
            // 左声道 +10000，右声道 -10000，平均应为 0
            byte[] stereo = new byte[4];
            short l = 10000;
            short r = -10000;
            stereo[0] = (byte) (l & 0xFF);
            stereo[1] = (byte) ((l >> 8) & 0xFF);
            stereo[2] = (byte) (r & 0xFF);
            stereo[3] = (byte) ((r >> 8) & 0xFF);
            byte[] out = AudioConverter.toTarget(stereo, fmt(16000, 16, 2, false));
            assertEquals(2, out.length);
            short s = (short) ((out[0] & 0xFF) | (out[1] << 8));
            assertTrue(Math.abs(s) <= 1, "左右抵消后应接近 0，实际 " + s);
        }

        @Test
        @DisplayName("立体声同相时内容保留")
        void stereoInPhase() {
            byte[] stereo = new byte[4];
            short v = 8000;
            for (int i = 0; i < 2; i++) {
                stereo[i * 2] = (byte) (v & 0xFF);
                stereo[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
            }
            byte[] out = AudioConverter.toTarget(stereo, fmt(16000, 16, 2, false));
            short s = (short) ((out[0] & 0xFF) | (out[1] << 8));
            assertEquals(v, s);
        }

        @Test
        @DisplayName("大端格式被正确解码（Windows 上少见但不能算错）")
        void bigEndianDecoded() {
            byte[] src = new byte[2];
            src[0] = (byte) 0x1F;   // 大端：0x1F40 = 8000
            src[1] = (byte) 0x40;
            byte[] out = AudioConverter.toTarget(src, fmt(16000, 16, 1, true));
            short s = (short) ((out[0] & 0xFF) | (out[1] << 8));
            assertEquals(8000, s);
        }

        @Test
        @DisplayName("不支持的位深会明确报错，而不是算错")
        void unsupportedBitDepthRejected() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> AudioConverter.toMonoDoubles(new byte[8], fmt(16000, 12, 1, false)));
            assertTrue(e.getMessage().contains("12"));
        }

        @Test
        @DisplayName("削顶保护：超出范围的样本被夹住而不是回绕")
        void clipping() {
            byte[] out = AudioConverter.toPcm16le(new double[] {2.0, -2.0, 0.5});
            short max = (short) ((out[0] & 0xFF) | (out[1] << 8));
            short min = (short) ((out[2] & 0xFF) | (out[3] << 8));
            assertEquals(32767, max);
            assertEquals(-32767, min);
        }
    }

    // ============================================================ RMS 与静音

    @Nested
    @DisplayName("RMS 与静音计时（§4.5）")
    class Silence {

        @Test
        @DisplayName("全零 PCM 的 RMS 为 0")
        void silenceRms() {
            assertEquals(0.0, SilenceDetector.rms16le(new byte[3200]), 1e-9);
        }

        @Test
        @DisplayName("满幅方波的 RMS 接近 1")
        void fullScaleRms() {
            byte[] pcm = new byte[3200];
            for (int i = 0; i < pcm.length; i += 2) {
                pcm[i] = (byte) 0xFF;
                pcm[i + 1] = (byte) 0x7F;   // 32767
            }
            assertEquals(1.0, SilenceDetector.rms16le(pcm), 0.01);
        }

        @Test
        @DisplayName("RMS 在静音阈值上下的区分正确")
        void rmsAboveAndBelowThreshold() {
            byte[] quiet = new byte[3200];
            for (int i = 0; i < quiet.length; i += 2) {
                quiet[i] = 0x10;   // 约 16 → RMS ≈ 0.0005
            }
            byte[] loud = new byte[3200];
            for (int i = 0; i < loud.length; i += 2) {
                loud[i] = (byte) 0x00;
                loud[i + 1] = (byte) 0x10;   // 4096 → RMS ≈ 0.125
            }
            assertTrue(SilenceDetector.rms16le(quiet) < SilenceDetector.DEFAULT_THRESHOLD);
            assertTrue(SilenceDetector.rms16le(loud) > SilenceDetector.DEFAULT_THRESHOLD);
        }

        @Test
        @DisplayName("空 PCM 的 RMS 为 0，不抛异常")
        void emptyRms() {
            assertEquals(0.0, SilenceDetector.rms16le(new byte[0]));
            assertEquals(0.0, SilenceDetector.rms16le(null));
            assertEquals(0.0, SilenceDetector.rms16le(new byte[1]));
        }

        @Test
        @DisplayName("静音满 N 秒触发一次，且只触发一次")
        void firesOnceAfterTimeout() {
            SilenceDetector d = new SilenceDetector(5);
            // 先说 1 秒有效语音
            for (int i = 0; i < 10; i++) {
                assertFalse(d.accept(0.2, 0.1));
            }
            // 再持续静音
            boolean fired = false;
            for (int i = 0; i < 40; i++) {
                if (d.accept(0.0001, 0.2)) {
                    fired = true;
                    break;
                }
            }
            assertTrue(fired, "静音满 5 秒应触发");
            assertFalse(d.accept(0.0001, 5.0), "触发后必须锁住，不能反复触发");
            assertTrue(d.latched());
        }

        @Test
        @DisplayName("没说够话之前不计时（避免点了悬浮球就被静音结束）")
        void doesNotFireBeforeSpeech() {
            SilenceDetector d = new SilenceDetector(2);
            for (int i = 0; i < 100; i++) {
                assertFalse(d.accept(0.0001, 0.1), "没有任何语音时不该触发静音结束");
            }
        }

        @Test
        @DisplayName("中途有语音就重新计时")
        void speechResetsTimer() {
            SilenceDetector d = new SilenceDetector(5);
            d.accept(0.2, 0.5);           // 说够话
            for (int i = 0; i < 20; i++) {
                d.accept(0.0001, 0.1);    // 静音 2 秒
            }
            assertTrue(d.silentSeconds() > 1.9);
            d.accept(0.3, 0.1);           // 又说话了
            assertEquals(0.0, d.silentSeconds(), 1e-9);
            assertFalse(d.latched());
        }

        @Test
        @DisplayName("remainsSeconds 递减到 0")
        void remainingCountsDown() {
            SilenceDetector d = new SilenceDetector(5);
            d.accept(0.2, 0.5);
            assertEquals(5.0, d.remainingSeconds(), 1e-9);
            d.accept(0.0001, 2.0);
            assertEquals(3.0, d.remainingSeconds(), 1e-6);
        }

        @Test
        @DisplayName("静音秒数配成 0 表示关闭（附录 A）")
        void zeroDisables() {
            SilenceDetector d = new SilenceDetector(0);
            assertFalse(d.enabled());
            d.accept(0.2, 1.0);
            for (int i = 0; i < 100; i++) {
                assertFalse(d.accept(0.0001, 1.0), "关闭时永远不该触发");
            }
        }

        @Test
        @DisplayName("reset 之后可以重新计时（新段落）")
        void resetRestarts() {
            SilenceDetector d = new SilenceDetector(1);
            d.accept(0.2, 0.5);
            d.accept(0.0001, 2.0);
            assertTrue(d.latched());
            d.reset();
            assertFalse(d.latched());
            assertEquals(0.0, d.silentSeconds(), 1e-9);
        }

        @Test
        @DisplayName("帧时长为 0 或负数不产生副作用")
        void zeroDurationIgnored() {
            SilenceDetector d = new SilenceDetector(1);
            assertFalse(d.accept(0.0001, 0));
            assertFalse(d.accept(0.0001, -1));
        }

        /**
         * 运行期改静音秒数 —— 守的是一个**真实的"改了没反应"**。
         *
         * <p>{@code timeoutSeconds} 原先是 {@code final}，而 {@code setTimeoutSeconds}
         * 只切了 {@code enabled} 开关。于是设置窗口里把 5 秒改成 8 秒，
         * **只有"开/关"生效，秒数永远是构造时那个值** ——
         * 而状态行是按配置渲染的（「静音 8 秒后自动结束」），显示与行为不一致。
         */
        @Test
        @DisplayName("运行期改秒数真的生效（原来只切开关，秒数还是构造时的值）")
        void timeoutFollowsRuntimeChange() {
            SilenceDetector d = new SilenceDetector(5);
            assertEquals(5.0, d.timeoutSeconds(), 1e-9);

            d.setTimeoutSeconds(8);
            assertEquals(8.0, d.timeoutSeconds(), 1e-9, "配置里的秒数必须真的改了");

            // 说够话，然后静音 5 秒：按 8 秒的设定**不该**触发
            d.accept(0.2, 0.5);
            boolean firedEarly = false;
            for (int i = 0; i < 25; i++) {      // 累计 5.0 秒静音
                firedEarly |= d.accept(0.0001, 0.2);
            }
            assertFalse(firedEarly, "刚改成 8 秒却按 5 秒结束了 —— 说明秒数没生效");
            assertTrue(d.silentSeconds() >= 5.0);

            // 继续到 8 秒才触发
            boolean fired = false;
            for (int i = 0; i < 20 && !fired; i++) {
                fired = d.accept(0.0001, 0.2);
            }
            assertTrue(fired, "到了 8 秒应当触发");
        }

        @Test
        @DisplayName("运行期把秒数改成 0 = 关闭，改成非 0 = 重新启用")
        void runtimeChangeAlsoTogglesEnabled() {
            SilenceDetector d = new SilenceDetector(5);
            assertTrue(d.enabled());

            d.setTimeoutSeconds(0);
            assertFalse(d.enabled(), "0 表示关闭");
            d.accept(0.2, 1.0);
            for (int i = 0; i < 50; i++) {
                assertFalse(d.accept(0.0001, 1.0), "关闭后永远不该触发");
            }

            d.setTimeoutSeconds(3);
            assertTrue(d.enabled(), "非 0 表示重新启用");
            d.reset();
            d.accept(0.2, 0.5);
            boolean fired = false;
            for (int i = 0; i < 30 && !fired; i++) {
                fired = d.accept(0.0001, 0.2);
            }
            assertTrue(fired, "重新启用后应按 3 秒触发");
        }

        @Test
        @DisplayName("调大秒数时不清空已累计的静音（改完就按新值算，而不是从头再等）")
        void shrinkingTimeoutFiresOnNextFrame() {
            SilenceDetector d = new SilenceDetector(10);
            d.accept(0.2, 0.5);
            for (int i = 0; i < 20; i++) {      // 累计 4 秒静音
                d.accept(0.0001, 0.2);
            }
            d.setTimeoutSeconds(3);             // 调到比已累计的还短
            assertTrue(d.accept(0.0001, 0.2), "已经静音 4 秒，改成 3 秒后应当立刻触发");
        }
    }

    // ============================================================ WAV

    @Nested
    @DisplayName("WAV 读写（M1 的验收路径）")
    class Wav {

        @Test
        @DisplayName("写入后读回内容完全一致")
        void roundTrip(@TempDir Path dir) throws Exception {
            byte[] pcm = sine16le(440, 0.5, 16000);
            Path f = dir.resolve("t.wav");
            WavFile.writePcm16Mono16k(f, pcm);
            assertArrayEquals(pcm, WavFile.readPcm16Mono16k(f));
        }

        @Test
        @DisplayName("文件头符合 44 字节 RIFF/WAVE 规范")
        void headerIsStandard(@TempDir Path dir) throws Exception {
            byte[] pcm = new byte[3200];
            Path f = dir.resolve("h.wav");
            WavFile.writePcm16Mono16k(f, pcm);
            byte[] all = Files.readAllBytes(f);
            assertEquals(44 + pcm.length, all.length);
            assertEquals("RIFF", new String(all, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals("WAVE", new String(all, 8, 4, java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals("fmt ", new String(all, 12, 4, java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals("data", new String(all, 36, 4, java.nio.charset.StandardCharsets.US_ASCII));
            // 采样率 16000 小端
            int rate = (all[24] & 0xFF) | ((all[25] & 0xFF) << 8) | ((all[26] & 0xFF) << 16)
                    | ((all[27] & 0xFF) << 24);
            assertEquals(16000, rate);
        }

        @Test
        @DisplayName("时长计算正确")
        void duration(@TempDir Path dir) throws Exception {
            Path f = dir.resolve("d.wav");
            WavFile.writePcm16Mono16k(f, sine16le(440, 2.0, 16000));
            WavFile.Pcm16 p = WavFile.readPcm16(f);
            assertEquals(2.0, p.seconds(), 0.001);
            assertEquals(16000, p.sampleRate());
            assertEquals(1, p.channels());
        }

        @Test
        @DisplayName("额外 chunk（录音软件常插 LIST）不会让解析失败")
        void extraChunksSkipped(@TempDir Path dir) throws Exception {
            Path f = dir.resolve("extra.wav");
            byte[] pcm = sine16le(440, 0.1, 16000);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bos.write(WavFile.header(pcm.length, 16000, 1));
            // 在 fmt 与 data 之间插一个 LIST chunk
            bos.write("LIST".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            bos.write(new byte[] {4, 0, 0, 0});
            bos.write(new byte[] {1, 2, 3, 4});
            // 重新拼一个含 LIST 的完整文件：手工组装
            byte[] full = new byte[44 + 12 + pcm.length];
            byte[] h = WavFile.header(pcm.length, 16000, 1);
            // data chunk 的位置改到 LIST 之后：把 header 的 fmt 部分保留，data 头后移
            System.arraycopy(h, 0, full, 0, 36);
            System.arraycopy("LIST".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, full, 36, 4);
            full[40] = 4;
            System.arraycopy(new byte[] {1, 2, 3, 4}, 0, full, 44, 4);
            System.arraycopy("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, full, 48, 4);
            int size = pcm.length;
            full[52] = (byte) (size & 0xFF);
            full[53] = (byte) ((size >> 8) & 0xFF);
            full[54] = (byte) ((size >> 16) & 0xFF);
            full[55] = (byte) ((size >> 24) & 0xFF);
            System.arraycopy(pcm, 0, full, 56, pcm.length);
            Files.write(f, full);

            assertArrayEquals(pcm, WavFile.readPcm16Mono16k(f));
        }

        @Test
        @DisplayName("非 wav 文件明确报错")
        void notWavRejected(@TempDir Path dir) throws Exception {
            Path f = dir.resolve("bad.wav");
            Files.write(f, "this is not a wav file at all".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.io.IOException e = assertThrows(java.io.IOException.class, () -> WavFile.readPcm16(f));
            assertTrue(e.getMessage().contains("wav"));
        }

        @Test
        @DisplayName("采样率不对时明确提示（引导用户重录，而不是拿错音频去识别）")
        void wrongRateRejected(@TempDir Path dir) throws Exception {
            Path f = dir.resolve("48k.wav");
            WavFile.writePcm16(f, new byte[3200], 48000, 1);
            java.io.IOException e = assertThrows(java.io.IOException.class,
                    () -> WavFile.readPcm16Mono16k(f));
            assertTrue(e.getMessage().contains("48000"), e.getMessage());
        }

        @Test
        @DisplayName("立体声文件被拒绝为「非单声道」")
        void stereoRejected(@TempDir Path dir) throws Exception {
            Path f = dir.resolve("st.wav");
            WavFile.writePcm16(f, new byte[6400], 16000, 2);
            assertThrows(java.io.IOException.class, () -> WavFile.readPcm16Mono16k(f));
        }

        @Test
        @DisplayName("自动创建父目录")
        void createsParentDirs(@TempDir Path dir) throws Exception {
            Path f = dir.resolve("a/b/c/t.wav");
            WavFile.writePcm16Mono16k(f, new byte[320]);
            assertTrue(Files.exists(f));
        }
    }
}
