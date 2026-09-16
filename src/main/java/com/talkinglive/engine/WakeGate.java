package com.talkinglive.engine;

import com.talkinglive.audio.SilenceDetector;

/**
 * 唤醒词的"长时语音门"—— 用来挡住**外部音频**造成的误唤醒。
 *
 * <p><b>它解决的具体问题</b>：用户实测「我在放视频声音时软件自动开启录制」。
 * 视频里的语音会被受限语法解码器强行套成唤醒词（语法只有两三个词，任何相近的
 * 音都会被"最接近的那个"接住），于是软件莫名其妙开始录音、甚至把视频里的话
 * 打进正文。
 *
 * <p><b>为什么不能从引擎层过滤</b>（这两条都是实测结论，别再试了）：
 * <ul>
 *   <li>受限语法模式**不给置信度**——即使 {@code setWords(true)}，
 *       结果里也只有文本、没有分数，所以没有阈值可设。</li>
 *   <li>关键词模式（{@code {"config":[{"phrase":...}]}}）在这版原生库里
 *       **不支持**，传对象进去直接 {@code Invalid memory access}
 *       （C 侧只认纯字符串数组，和当初踩过的语法格式坑同源）。</li>
 * </ul>
 * 所以只能在上层做启发式。
 *
 * <p><b>判据：说话人不会在连续说话 3 秒之后才喊唤醒词。</b>
 * 正常用法是「安静 → 说唤醒词 → 开始说正文」，唤醒词前面必然有一段安静；
 * 而视频/音乐是**连续**的音频，误命中总是出现在长时间连续有声的中间。
 * 于是：麦克风连续有声超过 {@link #MAX_SPEECH_RUN_SECONDS} 秒之后才匹配到唤醒词，
 * 就判为外部音频、不唤醒。
 *
 * <p>这条门只作用于**唤醒词**，不作用于结束词 —— 正文可能长达几十秒，
 * 结束词本来就该在长时间说话之后被接受。
 *
 * <p>纯逻辑（只吃 RMS 与帧时长），因此与设备无关、可单测。
 */
public final class WakeGate {

    /**
     * 连续有声超过这么多秒之后匹配到的唤醒词，判为外部音频。
     *
     * <p>取值理由：正常说话人在喊唤醒词之前会有一段安静（换气、想说什么），
     * 而连续说满 3 秒不断句、且第 3 秒之后才"喊唤醒词"是不自然的。
     * 反过来 3 秒也足够长，不会误伤"刚说完一句话紧接着喊唤醒词"的正常用法
     * （那种情况中间一定有停顿，计时会被重置）。
     */
    public static final double MAX_SPEECH_RUN_SECONDS = 3.0;

    /**
     * 判定为"有声"的 RMS 阈值。
     *
     * <p>比 {@link SilenceDetector#DEFAULT_THRESHOLD} 低一点（0.010 对 0.012）：
     * 这里要抓的是"麦克风里一直有东西在响"，宁可稍微敏感一些 ——
     * 判错的代价只是"这一次唤醒被挡下、需要再说一遍"，比"视频一响就开始录音"轻得多。
     */
    public static final double SPEECH_RMS = 0.010;

    /**
     * 低于这个时长（秒）的"安静段"不算真正的停顿、不重置计时。
     *
     * <p>必须有它：连续说话时字与字之间本来就有几十毫秒的低能量间隙，
     * 若一有间隙就重置，计时永远到不了 3 秒，这条门就形同虚设。
     */
    public static final double PAUSE_RESET_SECONDS = 0.35;

    private double speechRunSeconds;
    private double silenceRunSeconds;

    /** 本段连续有声已经持续了多少秒（供日志与自检观察）。 */
    public double speechRunSeconds() {
        return speechRunSeconds;
    }

    /**
     * 喂一帧音频的 RMS 与时长，维护"连续有声时长"。
     *
     * @param rms          该帧归一化 RMS（0..1）
     * @param frameSeconds 该帧时长（秒）
     */
    public void accept(double rms, double frameSeconds) {
        if (frameSeconds <= 0) {
            return;
        }
        if (rms >= SPEECH_RMS) {
            speechRunSeconds += frameSeconds;
            silenceRunSeconds = 0;
        } else {
            silenceRunSeconds += frameSeconds;
            if (silenceRunSeconds >= PAUSE_RESET_SECONDS) {
                speechRunSeconds = 0;   // 真正的停顿：重新开始计时
            }
        }
    }

    /**
     * 现在匹配到唤醒词，是否应当接受。
     *
     * @return true = 接受（前方没有长时间连续有声）；false = 判为外部音频，挡下
     */
    public boolean allowWake() {
        return speechRunSeconds <= MAX_SPEECH_RUN_SECONDS;
    }

    /**
     * 匹配到唤醒词之后调用。
     *
     * <p>把计时清零：无论这次唤醒是被接受还是被挡下，都从头开始。
     * 否则被挡下的那一次会让计时继续增长，后面真实用户的唤醒也一起被挡
     * （一个误命中把软件锁死，比误唤醒更糟）。
     */
    public void resetAfterWake() {
        speechRunSeconds = 0;
        silenceRunSeconds = 0;
    }
}
