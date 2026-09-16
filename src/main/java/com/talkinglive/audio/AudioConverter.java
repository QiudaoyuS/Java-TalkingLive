package com.talkinglive.audio;

import javax.sound.sampled.AudioFormat;

/**
 * 音频格式转换：真实麦克风格式 → 16kHz / 16bit / 单声道。
 *
 * <p><b>这是当前设计里最大的空白</b>（{@code TECH-PLAN} §7 第 2 项）：
 * {@code DESIGN.md} §3.1 只写了「16kHz」，但真实麦克风设备原生格式通常是 48kHz，
 * 且 {@code TargetDataLine} **不保证按请求格式打开**。写错的症状是「识别不准」——
 * 最难查。因此这里显式处理并单测。
 *
 * <p>下混 + 线性插值重采样，带一个廉价的抗混叠处理（对相邻源样本做三点哈明窗平均）。
 * 语音识别对重采样质量不敏感（识别引擎内部还会再做一次特征提取），
 * 但对**采样率数值错误**极其敏感：48k 的音频当成 16k 喂进去，听起来就是三倍速的胡话。
 */
public final class AudioConverter {

    /** 目标格式：16kHz / 16bit / 单声道（DESIGN.md §3.1 第 3 项）。 */
    public static final float TARGET_RATE = 16_000f;
    public static final int TARGET_SAMPLE_BITS = 16;
    public static final int TARGET_CHANNELS = 1;

    public static AudioFormat targetFormat() {
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, TARGET_RATE, TARGET_SAMPLE_BITS,
                TARGET_CHANNELS, TARGET_CHANNELS * TARGET_SAMPLE_BITS / 8, TARGET_RATE, false);
    }

    private AudioConverter() {}

    /**
     * 把一段设备原生 PCM 转成目标格式。
     *
     * @param src    源 PCM 字节（PCM_SIGNED，小端）
     * @param format 源格式
     * @return 目标格式的字节；格式已经是目标格式时原样返回
     */
    public static byte[] toTarget(byte[] src, AudioFormat format) {
        if (src == null || src.length == 0) {
            return new byte[0];
        }
        double[] mono = toMonoDoubles(src, format);
        float ratio = monoRatio(format);
        double[] resampled = ratio == 1.0 ? mono : resample(mono, ratio);
        return toPcm16le(resampled);
    }

    /** 采样率倍率 = 源采样率 / 目标采样率。 */
    public static float monoRatio(AudioFormat format) {
        return format.getSampleRate() / TARGET_RATE;
    }

    /** 源格式是否已经是目标格式（此时无需任何转换，走零拷贝路径）。 */
    public static boolean isAlreadyTarget(AudioFormat format) {
        return format.getSampleSizeInBits() == TARGET_SAMPLE_BITS
                && format.getChannels() == TARGET_CHANNELS
                && Math.abs(format.getSampleRate() - TARGET_RATE) < 0.5f
                && !format.isBigEndian()
                && format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED;
    }

    // ------------------------------------------------------------ 解码

    /** 任意 PCM_SIGNED 格式 → 归一化 double（已下混为单声道）。 */
    public static double[] toMonoDoubles(byte[] src, AudioFormat format) {
        int channels = Math.max(1, format.getChannels());
        int bits = format.getSampleSizeInBits();
        if (bits != 8 && bits != 16 && bits != 24 && bits != 32) {
            throw new IllegalArgumentException("不支持的位深：" + bits + "（需要 8/16/24/32）");
        }
        int bytesPerSample = bits / 8;
        int frameBytes = bytesPerSample * channels;
        boolean big = format.isBigEndian();
        int frames = src.length / frameBytes;
        double[] out = new double[frames];
        for (int f = 0; f < frames; f++) {
            double sum = 0;
            for (int c = 0; c < channels; c++) {
                int off = f * frameBytes + c * bytesPerSample;
                sum += decodeSample(src, off, bits, big);
            }
            out[f] = sum / channels;
        }
        return out;
    }

    private static double decodeSample(byte[] b, int off, int bits, boolean big) {
        long v;
        if (big) {
            v = 0;
            for (int i = 0; i < bits / 8; i++) {
                v = (v << 8) | (b[off + i] & 0xFFL);
            }
        } else {
            v = 0;
            for (int i = bits / 8 - 1; i >= 0; i--) {
                v = (v << 8) | (b[off + i] & 0xFFL);
            }
        }
        // 符号扩展到 32 位
        int shift = 64 - bits;
        v = (v << shift) >> shift;
        double scale = (1L << (bits - 1));
        return v / scale;
    }

    /** 归一化 double → 16bit 小端 PCM。超出 [-1,1) 的样本被削顶。 */
    public static byte[] toPcm16le(double[] samples) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            double v = samples[i];
            if (v > 1.0) {
                v = 1.0;
            } else if (v < -1.0) {
                v = -1.0;
            }
            int s = (int) Math.round(v * 32767.0);
            out[i * 2] = (byte) (s & 0xFF);
            out[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }

    // ------------------------------------------------------------ 重采样（无状态，整段用）

    /**
     * 整段重采样。ratio = 源采样率 / 目标采样率。
     *
     * <p>带一个三点哈明窗预滤波：降采样时抑制混叠（48k→16k 属 3 倍降采样，
     * 不做抗混叠会把 8kHz 以上的能量折叠回语音频带，表现为识别率下降）。
     */
    public static double[] resample(double[] src, double ratio) {
        if (src.length == 0 || ratio <= 0) {
            return new double[0];
        }
        if (Math.abs(ratio - 1.0) < 1e-9) {
            return src.clone();
        }
        double[] filtered = ratio > 1.0 ? antiAlias(src, ratio) : src;
        int outLen = (int) Math.floor(filtered.length / ratio);
        double[] out = new double[Math.max(0, outLen)];
        for (int i = 0; i < outLen; i++) {
            double pos = i * ratio;
            int i0 = (int) Math.floor(pos);
            double frac = pos - i0;
            int i1 = Math.min(i0 + 1, filtered.length - 1);
            double a = filtered[Math.min(i0, filtered.length - 1)];
            out[i] = a + (filtered[i1] - a) * frac;
        }
        return out;
    }

    /**
     * 三点哈明窗低通（截止约等于目标奈奎斯特）。
     *
     * <p>窗长取 {@code round(ratio)} 个源样本，即一个目标样本对应的源跨度。
     * 对 3 倍降采样是一个 3 抽头窗，计算量可忽略而收益明显。
     */
    static double[] antiAlias(double[] src, double ratio) {
        int w = Math.max(2, (int) Math.round(ratio));
        double[] kernel = new double[w];
        double sum = 0;
        for (int k = 0; k < w; k++) {
            double x = 2.0 * Math.PI * k / (w - 1);
            kernel[k] = 0.54 - 0.46 * Math.cos(x);
            sum += kernel[k];
        }
        for (int k = 0; k < w; k++) {
            kernel[k] /= sum;
        }
        double[] out = new double[src.length];
        int half = w / 2;
        for (int i = 0; i < src.length; i++) {
            double acc = 0;
            for (int k = 0; k < w; k++) {
                int idx = i + k - half;
                if (idx >= 0 && idx < src.length) {
                    acc += src[idx] * kernel[k];
                }
            }
            out[i] = acc;
        }
        return out;
    }

    // ------------------------------------------------------------ 流式重采样（有状态）

    /**
     * 跨块保持连续的流式重采样器。
     *
     * <p>采集线程每次读到一块（例如 2048 字节）就要转一次，直接对每块独立调用
     * {@link #resample} 会在块边界产生相位跳变（每块都会丢掉不足一个目标样本的余数），
     * 对 48k→16k 就是每块丢掉最多 2 个源样本 —— 长时间累积会让音频变短并引入周期性咔哒。
     * 因此必须保留小数相位与上一块尾部样本。
     */
    public static final class StreamResampler {
        private final double ratio;
        private double[] carry = new double[0];
        private double pos;

        /** @param ratio 源采样率 / 目标采样率 */
        public StreamResampler(double ratio) {
            this.ratio = ratio <= 0 ? 1.0 : ratio;
        }

        public double ratio() {
            return ratio;
        }

        /** 源格式已变（用户换了麦克风）时重建。 */
        public static StreamResampler of(AudioFormat format) {
            return new StreamResampler(monoRatio(format));
        }

        /**
         * 喂一块归一化单声道样本，返回本次可确定的目标样本。
         *
         * <p>不足一个目标样本的尾部留到下次调用，保证整体不丢样、不重复。
         */
        public double[] accept(double[] mono) {
            if (mono == null || mono.length == 0) {
                return new double[0];
            }
            if (Math.abs(ratio - 1.0) < 1e-9) {
                return mono;
            }
            double[] buf = new double[carry.length + mono.length];
            System.arraycopy(carry, 0, buf, 0, carry.length);
            System.arraycopy(mono, 0, buf, carry.length, mono.length);

            double[] filtered = ratio > 1.0 ? antiAlias(buf, ratio) : buf;
            int maxStart = filtered.length - 2;
            int count = 0;
            if (maxStart >= 0) {
                count = (int) Math.floor((maxStart - pos) / ratio) + 1;
            }
            if (count <= 0) {
                carry = buf;
                return new double[0];
            }
            double[] out = new double[count];
            double p = pos;
            for (int i = 0; i < count; i++) {
                int i0 = (int) Math.floor(p);
                double frac = p - i0;
                out[i] = filtered[i0] + (filtered[i0 + 1] - filtered[i0]) * frac;
                p += ratio;
            }
            int consumed = (int) Math.floor(p);
            pos = p - consumed;
            int remaining = buf.length - Math.min(consumed, buf.length);
            carry = new double[Math.max(0, remaining)];
            System.arraycopy(buf, Math.min(consumed, buf.length), carry, 0, carry.length);
            return out;
        }

        /** 整段流结束时把余量吐出来（避免尾部丢音）。 */
        public double[] drain() {
            if (carry.length < 2) {
                carry = new double[0];
                return new double[0];
            }
            double[] filtered = carry;
            int count = (int) Math.floor((filtered.length - 2 - pos) / ratio) + 1;
            if (count <= 0) {
                carry = new double[0];
                pos = 0;
                return new double[0];
            }
            double[] out = new double[count];
            double p = pos;
            for (int i = 0; i < count; i++) {
                int i0 = Math.min((int) Math.floor(p), filtered.length - 2);
                double frac = p - i0;
                out[i] = filtered[i0] + (filtered[i0 + 1] - filtered[i0]) * frac;
                p += ratio;
            }
            carry = new double[0];
            pos = 0;
            return out;
        }
    }
}
