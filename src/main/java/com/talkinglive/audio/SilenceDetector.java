package com.talkinglive.audio;

/**
 * 静音计时器（{@code DESIGN.md} §4.5：纯逻辑，可单测）。
 *
 * <p>不依赖音频设备：把它喂给「每帧的 RMS + 帧时长」即可，因此可以用合成数据
 * 精确测出「静音满 N 秒触发一次」这类时序行为。
 *
 * <p>两个容易做错的地方，都在这里显式处理：
 * <ul>
 *   <li><b>只在说够话之后才计时。</b>段落开始时的静音（用户点了悬浮球还没开口）
 *       不应该立刻把它结束掉——否则会退化成「一点就自动提交空段落」。
 *       因此要求累计有效语音超过 {@code minSpeechSeconds} 才开始计时。</li>
 *   <li><b>只触发一次。</b>触发后进入 latched 状态，直到 {@link #reset()}。</li>
 * </ul>
 */
public final class SilenceDetector {

    /** 低于该 RMS（归一化 0..1）视为静音。经验值：安静房间本底约 0.002–0.01。 */
    public static final double DEFAULT_THRESHOLD = 0.012;

    private final double threshold;
    /**
     * 静音多久算一段结束（秒）。
     *
     * <p><b>不是 final：</b>设置窗口允许运行期改这一项。它原先被写成 final，
     * 而 {@link #setTimeoutSeconds} 只切了 {@code enabled} 开关 —— 于是
     * 「0 / 非 0」这种开关切换是生效的，但**具体秒数永远是构造时那个值**。
     * 状态行却是按配置渲染的（"静音 8 秒后自动结束"），显示与行为不一致。
     */
    private volatile double timeoutSeconds;
    private final double minSpeechSeconds;

    private double silentSeconds;
    private double speechSeconds;
    private boolean latched;
    private boolean enabled;

    public SilenceDetector(int timeoutSeconds) {
        this(timeoutSeconds, DEFAULT_THRESHOLD);
    }

    public SilenceDetector(int timeoutSeconds, double threshold) {
        this(timeoutSeconds, threshold, 0.3);
    }

    public SilenceDetector(int timeoutSeconds, double threshold, double minSpeechSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        this.threshold = threshold;
        this.minSpeechSeconds = minSpeechSeconds;
        this.enabled = timeoutSeconds > 0;
    }

    public boolean enabled() {
        return enabled;
    }

    public double threshold() {
        return threshold;
    }

    public double timeoutSeconds() {
        return timeoutSeconds;
    }

    /** 连续静音的累计秒数。 */
    public double silentSeconds() {
        return silentSeconds;
    }

    /** 距触发还差多少秒；未启用或未开始计时时为 {@code timeoutSeconds}。 */
    public double remainingSeconds() {
        if (!enabled || latched) {
            return timeoutSeconds;
        }
        return Math.max(0, timeoutSeconds - silentSeconds);
    }

    public boolean latched() {
        return latched;
    }

    public void reset() {
        silentSeconds = 0;
        speechSeconds = 0;
        latched = false;
    }

    /**
     * 运行期改配置（设置窗口里改静音秒数）。
     *
     * <p>只改超时值与启用状态，**不动已经累计的静音秒数**：用户把 5 秒调到 8 秒时，
     * 当前这一段的静音计时继续累加，到 8 秒才触发 —— 这正是"改完就按新值算"的直觉。
     * 反过来从 8 秒调到 3 秒、而已累计 4 秒，下一次 {@link #accept} 就会立刻触发，
     * 同样符合直觉。
     */
    public void setTimeoutSeconds(int seconds) {
        this.timeoutSeconds = seconds;
        this.enabled = seconds > 0;
    }

    /**
     * 喂一帧。
     *
     * @param rms           该帧的归一化 RMS（0..1）
     * @param frameSeconds  该帧的时长（秒）
     * @return true 表示**本次调用**刚好触发静音超时（只会返回一次 true）
     */
    public boolean accept(double rms, double frameSeconds) {
        if (!enabled || latched || frameSeconds <= 0) {
            return false;
        }
        if (rms >= threshold) {
            speechSeconds += frameSeconds;
            silentSeconds = 0;
            return false;
        }
        if (speechSeconds < minSpeechSeconds) {
            // 还没说够话，静音不计时——避免「点了悬浮球立刻被静音结束」。
            return false;
        }
        silentSeconds += frameSeconds;
        if (silentSeconds >= timeoutSeconds) {
            latched = true;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------ RMS

    /** 16bit 小端单声道 PCM 的归一化 RMS（0..1）。 */
    public static double rms16le(byte[] pcm, int offset, int length) {
        if (pcm == null || length < 2) {
            return 0;
        }
        int end = Math.min(pcm.length, offset + length) & ~1;
        long sum = 0;
        int n = 0;
        for (int i = offset; i + 1 < end; i += 2) {
            int lo = pcm[i] & 0xFF;
            int hi = pcm[i + 1];
            int sample = (hi << 8) | lo;
            sum += (long) sample * sample;
            n++;
        }
        if (n == 0) {
            return 0;
        }
        double mean = sum / (double) n;
        return Math.sqrt(mean) / 32768.0;
    }

    public static double rms16le(byte[] pcm) {
        return rms16le(pcm, 0, pcm == null ? 0 : pcm.length);
    }
}
