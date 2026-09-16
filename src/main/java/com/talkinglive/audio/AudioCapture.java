package com.talkinglive.audio;

import com.talkinglive.core.Logging;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 音频采集：**单路** {@code TargetDataLine} + 分发（{@code DESIGN.md} §3.1 第 3 项 / §4.3）。
 *
 * <p><b>麦克风只开一路。</b>唤醒检测与预览识别共用同一个 {@code TargetDataLine}，
 * 由这里分发。禁止各自开设备——Windows 上会互相抢设备，且时序对不齐。
 *
 * <p><b>真实设备格式与请求格式常常不一致</b>（{@code TECH-PLAN} §7 第 2 项）：
 * 设备原生通常是 48kHz 立体声，且 {@code TargetDataLine} 不保证按请求格式打开。
 * 因此这里**不指定格式**，而是问设备要它自己的默认格式（{@link AudioSystem#getLine} 用
 * 设备自带 format 构建 info），再用 {@link AudioConverter} 显式转成 16kHz 单声道。
 * 写错的症状是「识别不准」——最难查，所以宁可显式转换。
 *
 * <p>采集中断（设备被抢占，§7）会触发 {@link Listener#onStreamError}，
 * 并按 {@code retryMillis} 自动重连，每次重连成功再通知 {@link Listener#onStreamRecovered}。
 */
public final class AudioCapture implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AudioCapture.class);

    /** 读块大小（源格式帧数）。2048 帧在 48kHz 下约 43ms，兼顾延迟与系统调用次数。 */
    private static final int READ_FRAMES = 2048;

    /** 重连间隔。 */
    private static final long RETRY_MILLIS = 3000;

    /** 采集回调。在采集线程上被调用，必须快速返回。 */
    public interface Listener {
        /**
         * 一段已经转成 16kHz/16bit/单声道 的 PCM。
         *
         * @param pcm  PCM 字节（小端）
         * @param rms  这段音频的归一化 RMS（0..1），供静音检测用
         */
        void onPcm(byte[] pcm, double rms);

        /** 采集失败/中断。{@code reason} 直接面向用户。 */
        default void onStreamError(String reason) {}

        /** 采集中断后重连成功。 */
        default void onStreamRecovered() {}

        /** 设备实际格式与请求格式不同（记日志与提示用）。 */
        default void onFormat(AudioFormat actual) {}
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closing = new AtomicBoolean();

    private volatile TargetDataLine line;
    private volatile AudioFormat actualFormat;
    private volatile String deviceName = "(未打开)";
    private volatile String lastError;
    private volatile Thread thread;
    private volatile long lastFrameAt;

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** 是否可用（已成功打开过设备）。 */
    public boolean available() {
        return line != null && line.isOpen();
    }

    public AudioFormat actualFormat() {
        return actualFormat;
    }

    public String deviceName() {
        return deviceName;
    }

    public String lastError() {
        return lastError;
    }

    /** 距上一帧音频的毫秒数；没有音频时返回一个很大的值。用于「采集中断」判定。 */
    public long millisSinceLastFrame() {
        long t = lastFrameAt;
        return t == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - t;
    }

    /**
     * 打开设备并开始采集。**失败不抛异常**——§7 要求「明确提示，程序仍常驻但置为不可用；
     * 不静默降级」。
     *
     * @return 是否成功打开
     */
    public synchronized boolean start() {
        if (!openLine()) {
            // 打开失败也要启动重连线程，用户插上麦克风后能自动恢复。
            startThread();
            return false;
        }
        startThread();
        return true;
    }

    private boolean openLine() {
        String err = tryOpen(null);
        if (err == null) {
            return true;
        }
        // 回退：逐个混音器试。默认设备被独占时，另一个混音器（例如麦克风阵列）往往可用。
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (Mixer.Info mi : mixers) {
            String e2 = tryOpen(mi);
            if (e2 == null) {
                log.info("默认设备不可用（{}），已回退到混音器：{}", err, mi.getName());
                return true;
            }
        }
        lastError = err;
        log.error("找不到可用麦克风：{}", err);
        notifyError(err);
        return false;
    }

    /** @return null 表示成功，否则是失败原因 */
    private String tryOpen(Mixer.Info mixerInfo) {
        TargetDataLine candidate = null;
        try {
            Mixer mixer = mixerInfo == null ? null : AudioSystem.getMixer(mixerInfo);
            DataLine.Info info = defaultInputInfo(mixer);
            if (info == null) {
                return mixerInfo == null ? "系统没有可用的录音设备" : mixerInfo.getName() + " 没有录音设备";
            }
            candidate = mixer == null
                    ? (TargetDataLine) AudioSystem.getLine(info)
                    : (TargetDataLine) mixer.getLine(info);
            candidate.open(info.getFormats()[0], candidate.getBufferSize());
            candidate.start();

            TargetDataLine old = this.line;
            this.line = candidate;
            this.actualFormat = candidate.getFormat();
            this.deviceName = mixerInfo == null ? "系统默认录音设备" : mixerInfo.getName();
            this.lastError = null;
            if (old != null && old != candidate) {
                closeQuietly(old);
            }
            log.info("麦克风已打开：{}  设备格式={}Hz/{}bit/{}声道  → 目标格式={}Hz/16bit/单声道",
                    deviceName, (int) actualFormat.getSampleRate(), actualFormat.getSampleSizeInBits(),
                    actualFormat.getChannels(), (int) AudioConverter.TARGET_RATE);
            for (Listener l : listeners) {
                try {
                    l.onFormat(actualFormat);
                } catch (RuntimeException ignored) {
                    // 监听器出错不影响采集
                }
            }
            return null;
        } catch (LineUnavailableException | IllegalArgumentException | ClassCastException e) {
            closeQuietly(candidate);
            return (mixerInfo == null ? "默认录音设备" : mixerInfo.getName()) + " 打开失败：" + e.getMessage();
        }
    }

    /**
     * 取设备的**默认输入格式**，而不是请求 16kHz。
     *
     * <p>真实声卡很少直接支持 16kHz；硬要 16kHz 会导致打开失败或系统内部做一次
     * 质量不可控的重采样。这里拿设备自己的格式，转换由我们自己显式完成。
     */
    private static DataLine.Info defaultInputInfo(Mixer mixer) {
        DataLine.Info probe = new DataLine.Info(TargetDataLine.class, null);
        javax.sound.sampled.Line.Info[] infos = mixer == null
                ? AudioSystem.getSourceLineInfo(probe)
                : mixer.getSourceLineInfo(probe);
        for (javax.sound.sampled.Line.Info li : infos) {
            if (li instanceof DataLine.Info dli && TargetDataLine.class.isAssignableFrom(dli.getLineClass())
                    && dli.getFormats().length > 0) {
                return dli;
            }
        }
        return null;
    }

    private void startThread() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        thread = new Thread(this::loop, "audio-capture");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        byte[] raw = new byte[READ_FRAMES * 8];
        while (!closing.get()) {
            TargetDataLine l = this.line;
            if (l == null || !l.isOpen()) {
                if (!reconnect()) {
                    sleep(RETRY_MILLIS);
                }
                continue;
            }
            AudioFormat fmt = actualFormat;
            int frameBytes = Math.max(1, fmt.getFrameSize());
            // 用源格式的 1/4 缓冲以免 48kHz 立体声下溢出；转换后的目标块约 40ms。
            int want = Math.min(raw.length, Math.max(frameBytes * 256, (int) (fmt.getSampleRate() / 25) * frameBytes));
            int n;
            try {
                n = l.read(raw, 0, want - (want % frameBytes));
            } catch (RuntimeException e) {
                log.warn("读取麦克风失败：{}", e.toString());
                handleStreamLoss("读取麦克风中失败：" + e.getMessage());
                continue;
            }
            if (n <= 0) {
                handleStreamLoss("麦克风音频流已中断（设备可能被其他程序独占）");
                continue;
            }
            lastFrameAt = System.currentTimeMillis();
            byte[] pcm;
            try {
                pcm = AudioConverter.toTarget(java.util.Arrays.copyOf(raw, n), fmt);
            } catch (RuntimeException e) {
                log.warn("音频格式转换失败：{}", e.toString());
                continue;
            }
            if (pcm.length == 0) {
                continue;
            }
            double rms = SilenceDetector.rms16le(pcm);
            for (Listener lis : listeners) {
                try {
                    lis.onPcm(pcm, rms);
                } catch (RuntimeException e) {
                    log.warn("音频监听器出错（忽略本次）：{}", e.toString());
                }
            }
        }
    }

    private void handleStreamLoss(String reason) {
        lastError = reason;
        log.error("{}", reason);
        notifyError(reason);
        closeQuietly(line);
        line = null;
    }

    private boolean reconnect() {
        log.info("尝试重连麦克风…");
        if (openLine()) {
            log.info("麦克风已恢复：{}", deviceName);
            for (Listener l : listeners) {
                try {
                    l.onStreamRecovered();
                } catch (RuntimeException ignored) {
                    // 忽略
                }
            }
            return true;
        }
        return false;
    }

    private void notifyError(String reason) {
        for (Listener l : listeners) {
            try {
                l.onStreamError(reason);
            } catch (RuntimeException ignored) {
                // 忽略
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(TargetDataLine l) {
        if (l == null) {
            return;
        }
        try {
            l.stop();
            l.close();
        } catch (RuntimeException ignored) {
            // 关闭失败无需处理
        }
    }

    /** 列出可用录音设备，供设置窗口的「模型状态」页与 --doctor 使用。 */
    public static List<String> listDevices() {
        List<String> out = new java.util.ArrayList<>();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            Mixer m = AudioSystem.getMixer(mi);
            javax.sound.sampled.Line.Info[] infos = m.getSourceLineInfo(new DataLine.Info(TargetDataLine.class, null));
            if (infos.length > 0) {
                out.add(mi.getName());
            }
        }
        return out;
    }

    @Override
    public synchronized void close() {
        closing.set(true);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeQuietly(line);
        line = null;
        log.info("音频采集已停止（设备 {}）", deviceName);
    }

    /** 采集状态的可读描述。 */
    public String describe() {
        if (!available()) {
            return "不可用" + (lastError == null ? "" : "：" + lastError);
        }
        AudioFormat f = actualFormat;
        return String.format("%s（设备 %dHz/%dbit/%d声道 → 16kHz/16bit/单声道）",
                deviceName, (int) f.getSampleRate(), f.getSampleSizeInBits(), f.getChannels());
    }

    @Override
    public String toString() {
        return "AudioCapture{" + describe() + ", lastFrame=" + Logging.describe(null, millisSinceLastFrame() + "ms ago") + "}";
    }
}
