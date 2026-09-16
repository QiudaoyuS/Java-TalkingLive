package com.talkinglive.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置读写（{@code DESIGN.md} §4.5：纯逻辑，可单测）。
 *
 * <p>配置项与默认值取自 {@code DESIGN.md} 附录 A。这里刻意**不依赖任何 AWT / JNA**，
 * 因此可以在无桌面环境下完整单测——包括启动时的强制校验（§4.3）。
 */
public final class AppConfig {

    public static final String DEFAULT_WAKE_WORD = "子曰";
    public static final String DEFAULT_END_WORD = "到此为止";
    public static final int DEFAULT_SILENCE_SECONDS = 5;
    public static final int MIN_SILENCE_SECONDS = 0;
    public static final int MAX_SILENCE_SECONDS = 15;

    /** 发送按键（附录 A）。 */
    public enum SendKey {
        ENTER("Enter"),
        CTRL_ENTER("Ctrl+Enter");

        private final String display;

        SendKey(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }

        public static SendKey fromDisplay(String s) {
            if (s == null) {
                throw new ConfigException("发送按键不能为空");
            }
            String t = s.trim().replace(" ", "");
            for (SendKey k : values()) {
                if (k.display.replace(" ", "").equalsIgnoreCase(t) || k.name().equalsIgnoreCase(t)) {
                    return k;
                }
            }
            throw new ConfigException("发送按键只能是 " + ENTER.display() + " 或 " + CTRL_ENTER.display()
                    + "，实际是 '" + s + "'");
        }
    }

    /** 贴边收起的方向。 */
    public enum DockSide {
        NONE, LEFT, RIGHT;

        public static DockSide fromName(String s) {
            if (s == null || s.isBlank()) {
                return NONE;
            }
            try {
                return valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ConfigException("贴边状态只能是 NONE/LEFT/RIGHT，实际是 '" + s + "'");
            }
        }
    }

    /** 悬浮球的位置与贴边状态（DESIGN.md §12 #4：一并保存）。 */
    public static final class Ball {
        private int x = Integer.MIN_VALUE;
        private int y = Integer.MIN_VALUE;
        private DockSide dock = DockSide.NONE;
        private boolean dockEnabled = true;

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public DockSide dock() {
            return dock;
        }

        public boolean dockEnabled() {
            return dockEnabled;
        }

        /** 是否已有保存过的位置；没有则由 UI 按「屏幕右侧中部」给默认值。 */
        public boolean hasPosition() {
            return x != Integer.MIN_VALUE && y != Integer.MIN_VALUE;
        }

        public void setPosition(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public void setDock(DockSide dock) {
            this.dock = dock == null ? DockSide.NONE : dock;
        }

        public void setDockEnabled(boolean dockEnabled) {
            this.dockEnabled = dockEnabled;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (hasPosition()) {
                m.put("x", x);
                m.put("y", y);
            }
            m.put("dock", dock.name());
            m.put("dockEnabled", dockEnabled);
            return m;
        }
    }

    /**
     * 一次听写的时长上限（DESIGN.md §12 #5 / §7「单段录音过长」）。
     *
     * <p>取值理由：段落结束条件本就是静音超时（默认 5s），正常段落几乎不可能超过 60s；
     * 上限的作用只是防止「忘记说结束词 + 环境持续有噪声导致静音检测永不触发」时内存无限增长。
     * 16kHz 单声道 16bit 每秒钟 32KB，60s 约 1.9MB PCM，对 §6「听写中内存 < 1.5GB」
     * 的预算无压力；再长也没有识别价值（SenseVoice/whisper 都是短段模型）。
     */
    public static final int DEFAULT_MAX_SEGMENT_SECONDS = 60;

    private String wakeWord = DEFAULT_WAKE_WORD;
    private String endWord = DEFAULT_END_WORD;
    private int silenceSeconds = DEFAULT_SILENCE_SECONDS;
    private boolean autoSend = false;
    private SendKey sendKey = SendKey.ENTER;
    private boolean sendOnSilenceTimeout = false;
    private int maxSegmentSeconds = DEFAULT_MAX_SEGMENT_SECONDS;
    private boolean itn = true;
    private final Ball ball = new Ball();

    // ------------------------------------------------------------ 访问器

    public String wakeWord() {
        return wakeWord;
    }

    public void setWakeWord(String v) {
        this.wakeWord = v == null ? "" : v.trim();
    }

    public String endWord() {
        return endWord;
    }

    public void setEndWord(String v) {
        this.endWord = v == null ? "" : v.trim();
    }

    public int silenceSeconds() {
        return silenceSeconds;
    }

    public void setSilenceSeconds(int v) {
        this.silenceSeconds = v;
    }

    /** 静音超时是否生效（附录 A：填 0 表示关闭）。 */
    public boolean silenceTimeoutEnabled() {
        return silenceSeconds > 0;
    }

    /** 静音结束录制「之后」是否还要自动发送；仅当 autoSend 打开时有意义。 */
    public boolean sendOnSilenceTimeout() {
        return sendOnSilenceTimeout;
    }

    public void setSendOnSilenceTimeout(boolean v) {
        this.sendOnSilenceTimeout = v;
    }

    public boolean autoSend() {
        return autoSend;
    }

    public void setAutoSend(boolean v) {
        this.autoSend = v;
    }

    public SendKey sendKey() {
        return sendKey;
    }

    public void setSendKey(SendKey v) {
        this.sendKey = v == null ? SendKey.ENTER : v;
    }

    public int maxSegmentSeconds() {
        return maxSegmentSeconds;
    }

    public void setMaxSegmentSeconds(int v) {
        this.maxSegmentSeconds = v;
    }

    /** SenseVoice 的 ITN 开关（TECH-PLAN §5.4：会改字，因此必须可关）。 */
    public boolean itn() {
        return itn;
    }

    public void setItn(boolean v) {
        this.itn = v;
    }

    public Ball ball() {
        return ball;
    }

    // ------------------------------------------------------------ 校验

    /**
     * 配置合法性校验（不含词表校验——那需要引擎，见 {@code system.MicValidator}）。
     *
     * @throws ConfigException 第一条不通过的规则
     */
    public void validate() {
        List<String> problems = new ArrayList<>();
        if (wakeWord.isBlank()) {
            problems.add("唤醒词不能为空");
        }
        if (endWord.isBlank()) {
            problems.add("结束词不能为空");
        }
        if (!wakeWord.isBlank() && wakeWord.equals(endWord)) {
            problems.add("唤醒词与结束词不能相同（会立刻自我结束）");
        }
        if (silenceSeconds < MIN_SILENCE_SECONDS || silenceSeconds > MAX_SILENCE_SECONDS) {
            problems.add("静音结束录制时长必须在 " + MIN_SILENCE_SECONDS + "–" + MAX_SILENCE_SECONDS
                    + " 秒之间（0 表示关闭），实际是 " + silenceSeconds);
        }
        if (maxSegmentSeconds < 5 || maxSegmentSeconds > 600) {
            problems.add("单段最长时长必须在 5–600 秒之间，实际是 " + maxSegmentSeconds);
        }
        if (!problems.isEmpty()) {
            throw new ConfigException(String.join("；", problems));
        }
    }

    // ------------------------------------------------------------ 序列化

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("wakeWord", wakeWord);
        m.put("endWord", endWord);
        m.put("silenceSeconds", silenceSeconds);
        m.put("autoSend", autoSend);
        m.put("sendKey", sendKey.display());
        m.put("sendOnSilenceTimeout", sendOnSilenceTimeout);
        m.put("maxSegmentSeconds", maxSegmentSeconds);
        m.put("itn", itn);
        m.put("ball", ball.toJson());
        return m;
    }

    public String toJsonText() {
        return JsonCodec.write(toJson());
    }

    /**
     * 从 JSON 文本读取配置并**校验**。
     *
     * <p>缺失的键取默认值——这样用户手写的残缺配置也能起来；但写出非法值时
     * 一律报错而不是静默回退（§4.3「配置必须显式校验」的同一原则）。
     */
    public static AppConfig fromJsonText(String text) {
        Map<String, Object> m = JsonCodec.parseObject(text);
        AppConfig c = new AppConfig();
        c.wakeWord = JsonCodec.str(m, "wakeWord", DEFAULT_WAKE_WORD).trim();
        c.endWord = JsonCodec.str(m, "endWord", DEFAULT_END_WORD).trim();
        c.silenceSeconds = JsonCodec.intVal(m, "silenceSeconds", DEFAULT_SILENCE_SECONDS);
        c.autoSend = JsonCodec.bool(m, "autoSend", false);
        c.sendKey = SendKey.fromDisplay(JsonCodec.str(m, "sendKey", SendKey.ENTER.display()));
        c.sendOnSilenceTimeout = JsonCodec.bool(m, "sendOnSilenceTimeout", false);
        c.maxSegmentSeconds = JsonCodec.intVal(m, "maxSegmentSeconds", DEFAULT_MAX_SEGMENT_SECONDS);
        c.itn = JsonCodec.bool(m, "itn", true);
        Object ballObj = m.get("ball");
        if (ballObj instanceof Map<?, ?> bm) {
            Map<String, Object> b = new LinkedHashMap<>();
            bm.forEach((k, v) -> b.put(String.valueOf(k), v));
            if (b.containsKey("x") && b.containsKey("y")) {
                c.ball.setPosition(JsonCodec.intVal(b, "x", 0), JsonCodec.intVal(b, "y", 0));
            }
            c.ball.setDock(DockSide.fromName(JsonCodec.str(b, "dock", "NONE")));
            c.ball.setDockEnabled(JsonCodec.bool(b, "dockEnabled", true));
        }
        c.validate();
        return c;
    }

    /** 配置不合法，或读取失败。消息直接面向用户。 */
    public static final class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }

        public ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
