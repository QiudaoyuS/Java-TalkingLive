package com.talkinglive.core;

import java.util.Arrays;

/**
 * 一次听写的上下文（{@code DESIGN.md} §4.5）。
 *
 * <p>纯逻辑，不依赖 AWT / JNA / 引擎原生库，可单测。
 *
 * <p>它承载三件事：
 * <ol>
 *   <li><b>目标窗口</b>——在段落开始时就锁定。提交时用它校验前台窗口是否还是它
 *       （§7「提交时前台窗口已变」）。这里只保存 {@code hwnd} 与标题，不碰 Win32。</li>
 *   <li><b>当前段 PCM 缓存</b>——给精化引擎用。</li>
 *   <li><b>已注入文本的账</b>——{@code DESIGN.md} §4.3 要求「字符计数用码点」。
 *       这是为将来的稳定前缀增量注入（{@code StablePrefixPolicy}）留的账本；
 *       MVP 的整段注入只用一次。</li>
 * </ol>
 */
public final class DictationSession {

    /** 单个采样（16kHz 16bit 单声道）的字节数。 */
    public static final int BYTES_PER_SAMPLE = 2;
    public static final int SAMPLE_RATE = 16000;

    private final long generation;
    private final long targetWindow;
    private final String targetWindowTitle;
    private final int maxSegmentSeconds;
    /** 段落开始时目标窗口里**有键盘焦点的控件**（子窗口）；0 表示没取到。 */
    private long targetFocus;

    private final byte[] pcm;
    private int pcmLength;
    private boolean full;

    private String previewText = "";
    private String injectedText = "";

    public DictationSession(long generation, long targetWindow, String targetWindowTitle, int maxSegmentSeconds) {
        this.generation = generation;
        this.targetWindow = targetWindow;
        this.targetWindowTitle = targetWindowTitle == null ? "" : targetWindowTitle;
        this.maxSegmentSeconds = maxSegmentSeconds;
        this.pcm = new byte[Math.max(1, maxSegmentSeconds) * SAMPLE_RATE * BYTES_PER_SAMPLE];
    }

    public long generation() {
        return generation;
    }

    /** 段落开始时的前台窗口句柄（Win32 HWND 的数值形式）。 */
    public long targetWindow() {
        return targetWindow;
    }

    public String targetWindowTitle() {
        return targetWindowTitle;
    }

    /**
     * 段落开始时目标窗口里有键盘焦点的控件句柄。
     *
     * <p>用途见 {@code WindowsTextInjector.inject}：如果提交时前台窗口已经不是目标
     * （用户中途切走了、通知弹过、输入法切换过），需要**把焦点还原回去**再注入。
     * 只还原顶层窗口不够——浏览器的地址栏、编辑框都是子窗口，只把窗口切到前台，
     * 文字可能落到窗口本身而不是输入框里，用户的感受仍然是「打不进去」。
     */
    public long targetFocus() {
        return targetFocus;
    }

    /** 由 App 在段落开始时设置（需要 Win32 调用，因此不在 core 里取）。 */
    public void setTargetFocus(long hwnd) {
        this.targetFocus = hwnd;
    }

    public int maxSegmentSeconds() {
        return maxSegmentSeconds;
    }

    // ------------------------------------------------------------ 音频缓存

    /**
     * 追加一段 16kHz/16bit/单声道 PCM。
     *
     * @return true 表示已满（达到单段上限），调用方应投递 {@code Event.MAX_SEGMENT_REACHED}
     */
    public boolean appendPcm(byte[] data, int offset, int length) {
        if (length <= 0) {
            return full;
        }
        int room = pcm.length - pcmLength;
        if (length >= room) {
            System.arraycopy(data, offset, pcm, pcmLength, room);
            pcmLength = pcm.length;
            full = true;
        } else {
            System.arraycopy(data, offset, pcm, pcmLength, length);
            pcmLength += length;
        }
        return full;
    }

    public boolean appendPcm(byte[] data) {
        return appendPcm(data, 0, data == null ? 0 : data.length);
    }

    /** 已缓存的 PCM 拷贝。 */
    public byte[] pcmSnapshot() {
        return Arrays.copyOf(pcm, pcmLength);
    }

    public int pcmLength() {
        return pcmLength;
    }

    public boolean segmentFull() {
        return full;
    }

    /** 已录时长（秒，近似到采样）。 */
    public double recordedSeconds() {
        return pcmLength / (double) (SAMPLE_RATE * BYTES_PER_SAMPLE);
    }

    /** 是否有足够音频值得提交。太短的段落（< 0.2s）通常是误触发。 */
    public boolean hasUsableAudio() {
        return recordedSeconds() >= 0.2;
    }

    // ------------------------------------------------------------ 文本账本

    public String previewText() {
        return previewText;
    }

    public void setPreviewText(String t) {
        this.previewText = t == null ? "" : t;
    }

    public String injectedText() {
        return injectedText;
    }

    /** 记录已注入的文本（整段注入用一次；增量注入会多次调用）。 */
    public void appendInjected(String t) {
        if (t == null || t.isEmpty()) {
            return;
        }
        injectedText += t;
    }

    /** 已注入文本的码点数（§4.3：涉及「第几个字符」一律用码点）。 */
    public int injectedCodePointCount() {
        return injectedText.codePointCount(0, injectedText.length());
    }

    /**
     * 定出本段的最终文本。
     *
     * <p>优先级：精化结果（更准 + 带标点）> 预览文本
     * （§7「推理失败/超时 → 退回用 Vosk 预览文本注入」）。
     *
     * <p><b>两边都空时返回空串</b>，调用方据此不注入并明确提示。
     * 这里刻意**不做**「用一个去补另一个」的聪明事：两路识别是独立的，
     * 一路空而另一路全有，说明该段音频有问题（极短、纯噪声、设备异常），
     * 猜着注入只会让用户更困惑。
     *
     * <p>但要如实回报**哪一路成功了**，因为这意味着完全不同的排查方向：
     * <ul>
     *   <li>预览有、精化空 → 精化路径的问题（模型/参数），音频是好的；</li>
     *   <li>预览空、精化有（实测出现过）→ **流式预览这条路有问题**，音频也是好的；</li>
     *   <li>两边都空 → 音频或设备的问题。</li>
     * </ul>
     */
    public String resolveFinalText(String refined) {
        if (refined != null && !refined.isBlank()) {
            return refined.strip();
        }
        return previewText == null ? "" : previewText.strip();
    }

    /**
     * 本段的文本来源诊断，用于日志与排查。
     *
     * <p>正是这个诊断把「预览空、精化有」这种反常情况显式化——
     * 在它被加进来之前，日志只会显示「注入了 N 个字」，
     * 看不出这条路本来一个字都没出。
     */
    public String textSource(String refined) {
        boolean hasRefined = refined != null && !refined.isBlank();
        boolean hasPreview = previewText != null && !previewText.isBlank();
        if (hasRefined && hasPreview) {
            return "精化";
        }
        if (hasRefined) {
            return "仅精化（★ 流式预览为空，需排查预览链路）";
        }
        if (hasPreview) {
            return "仅预览（精化未产出，已按 §7 兜底）";
        }
        return "两者皆空";
    }

    @Override
    public String toString() {
        return "DictationSession{gen=" + generation
                + ", target=0x" + Long.toHexString(targetWindow)
                + ", audio=" + Logging.describe(null, String.format("%.2fs", recordedSeconds()))
                + "}";
    }
}
