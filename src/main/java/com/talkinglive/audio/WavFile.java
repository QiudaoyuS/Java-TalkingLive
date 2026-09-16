package com.talkinglive.audio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * 16kHz / 16bit / 单声道 WAV 读写。
 *
 * <p>用途有两个：
 * <ul>
 *   <li>M1 的验收目标：「音频采集落成 wav 文件」（{@code DESIGN.md} §11）。</li>
 *   <li>给精化引擎验证实验（{@code TECH-PLAN} §6.2）准备 16kHz 单声道 wav 测试集。</li>
 * </ul>
 * 纯逻辑（只依赖 java.nio），因此可以在无麦克风环境下做读写往返单测。
 */
public final class WavFile {

    public static final int HEADER_BYTES = 44;

    private WavFile() {}

    /** 写一个标准的 44 字节头 + PCM 数据的 wav 文件。 */
    public static void writePcm16(Path file, byte[] pcm, int sampleRate, int channels) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            out.write(header(pcm.length, sampleRate, channels));
            out.write(pcm);
        }
    }

    /** 16kHz 单声道快捷方法。 */
    public static void writePcm16Mono16k(Path file, byte[] pcm) throws IOException {
        writePcm16(file, pcm, (int) AudioConverter.TARGET_RATE, AudioConverter.TARGET_CHANNELS);
    }

    public static byte[] header(int dataBytes, int sampleRate, int channels) {
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        byte[] h = new byte[HEADER_BYTES];
        putAscii(h, 0, "RIFF");
        putIntLe(h, 4, 36 + dataBytes);
        putAscii(h, 8, "WAVE");
        putAscii(h, 12, "fmt ");
        putIntLe(h, 16, 16);
        putShortLe(h, 20, 1); // PCM
        putShortLe(h, 22, channels);
        putIntLe(h, 24, sampleRate);
        putIntLe(h, 28, byteRate);
        putShortLe(h, 32, blockAlign);
        putShortLe(h, 34, bitsPerSample);
        putAscii(h, 36, "data");
        putIntLe(h, 40, dataBytes);
        return h;
    }

    /**
     * 读取 wav 的 PCM 数据（只支持 16bit PCM，够用即可）。
     *
     * <p>按 chunk 遍历而不是假定 44 字节头——真实录音软件常插入 {@code LIST} 等块。
     */
    public static Pcm16 readPcm16(Path file) throws IOException {
        byte[] all = Files.readAllBytes(file);
        if (all.length < 12 || !isAscii(all, 0, "RIFF") || !isAscii(all, 8, "WAVE")) {
            throw new IOException("不是 wav 文件：" + file);
        }
        int sampleRate = 0;
        int channels = 1;
        int bits = 16;
        int pos = 12;
        while (pos + 8 <= all.length) {
            String id = ascii(all, pos, 4);
            int size = getIntLe(all, pos + 4);
            int body = pos + 8;
            if ("fmt ".equals(id)) {
                channels = getShortLe(all, body + 2);
                sampleRate = getIntLe(all, body + 4);
                bits = getShortLe(all, body + 14);
            } else if ("data".equals(id)) {
                int end = Math.min(all.length, body + size);
                byte[] pcm = Arrays.copyOfRange(all, body, end);
                if (bits != 16) {
                    throw new IOException("只支持 16bit PCM，实际是 " + bits + "bit：" + file);
                }
                return new Pcm16(pcm, sampleRate, channels);
            }
            pos = body + size + (size & 1);
        }
        throw new IOException("wav 里没有 data 块：" + file);
    }

    /** 16kHz 单声道快捷读取；不满足时抛错（调用方应据此提示用户重录）。 */
    public static byte[] readPcm16Mono16k(Path file) throws IOException {
        Pcm16 p = readPcm16(file);
        if (p.sampleRate() != (int) AudioConverter.TARGET_RATE || p.channels() != AudioConverter.TARGET_CHANNELS) {
            throw new IOException("需要 16kHz 单声道 wav，实际是 " + p.sampleRate() + "Hz / "
                    + p.channels() + " 声道：" + file);
        }
        return p.pcm();
    }

    /** 读到的 PCM 与其格式。 */
    public record Pcm16(byte[] pcm, int sampleRate, int channels) {
        public double seconds() {
            return pcm.length / (double) (sampleRate * channels * 2);
        }
    }

    // ------------------------------------------------------------ 小端工具

    private static void putAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[off + i] = (byte) s.charAt(i);
        }
    }

    private static void putIntLe(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void putShortLe(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static int getIntLe(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static int getShortLe(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static boolean isAscii(byte[] b, int off, String s) {
        return s.equals(ascii(b, off, s.length()));
    }

    private static String ascii(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len && off + i < b.length; i++) {
            sb.append((char) (b[off + i] & 0xFF));
        }
        return sb.toString();
    }
}
