package com.talkinglive.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配置读写（{@code DESIGN.md} §4.5：纯逻辑，可单测）。
 *
 * <p>配置项与默认值取自 {@code DESIGN.md} 附录 A。这里刻意**不依赖任何 AWT / JNA**，
 * 因此可以在无桌面环境下完整单测——包括启动时的强制校验（§4.3）。
 */
public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

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
    // 注：曾经有一个 `itn`（数字规整）开关，已于 0.9.0 的清理中移除 ——
    // 它是为 SenseVoice 准备的（TECH-PLAN §5.4），而 SenseVoice 从未接入，
    // 当前精化引擎是 Vosk 离线重跑、没有 ITN 能力，于是它只被存/读/写/打日志，
    // 没有任何地方读它来做事。一个"改了没反应"的开关比没有更糟：用户会以为软件坏了。

    /**
     * 注入时**每个字符之间的间隔（毫秒）**。
     *
     * <p>为什么它是配置项而不是硬编码常量：不同目标程序对合成输入的耐受度差别很大。
     * 实测微信/QQ 这类自绘输入框在灌太快时会**主动丢掉后面的 WM_CHAR**
     * （日志表现为「14 个事件全部写入成功，但输入框里只有 2 个字」）。
     * 需要的间隔随微信版本、输入法、机器负载而变，因此必须让用户能调。
     *
     * <p>默认 {@value #DEFAULT_CHAR_GAP_MILLIS}ms；范围 {@value #MIN_CHAR_GAP_MILLIS}–{@value #MAX_CHAR_GAP_MILLIS}。
     * 调大更稳（代价是注入变慢）。
     *
     * <p><b>下限为什么不是 0</b>：0 曾经是合法值，而它是**已知的丢字值** —— 灌得太快时
     * 自绘输入框会主动丢掉后面的 {@code WM_CHAR}，症状是"输入框里只出现第一个字"，
     * 而 {@code SendInput} 会如实报告"全部写入成功"。一个已知会出错的取值不该留在可行域里
     * （{@code PENDING-ISSUES} P1.6）。老配置里残留的 0 会在读入时被抬到下限并记一条警告，
     * 而不是让程序起不来。
     */
    private int charGapMillis = DEFAULT_CHAR_GAP_MILLIS;

    /** 每字之间的默认间隔：见 {@link #charGapMillis}。 */
    public static final int DEFAULT_CHAR_GAP_MILLIS = 20;
    /** 下限 5ms：0 是已知会丢字的值，不再允许（见 {@link #charGapMillis}）。 */
    public static final int MIN_CHAR_GAP_MILLIS = 5;
    public static final int MAX_CHAR_GAP_MILLIS = 200;

    /**
     * 热词纠正表：{@code "说的词=想要的写法"}，多项用逗号或换行分隔。
     *
     * <p><b>为什么需要它</b>：实测 {@code vosk-model-small-cn-0.22} 的中文词表里
     * **没有任何英文**（A–Z、AI、APP、CPU 全部不在表内），所以它永远输出不了「AI」；
     * 而流式预览用的正是小模型。大模型认识 AI/APP/CPU/PDF 这些，但仍可能按字母
     * 拆成「A I」。拆开的字母串会被 {@code HotwordCorrector} 自动拼回去，
     * 剩下的情况（说的词与想要的写法之间没有字符级关系）就需要用户显式指出。
     *
     * <p>例：{@code "诶爱=AI, 皮迪艾夫=PDF"}。
     *
     * <p><b>为什么不做内置近音词表</b>：猜错会**改掉用户本来正确的正文**，
     * 比少一个字严重。所以只做「用户说了算」的映射。
     *
     * <p>它是文本类配置而非数值类，因此对它的校验只有「解析出至少一项」——
     * 配置串格式不对的项目**静默跳过**，不让热词把整个配置校验带崩。
     */
    private String hotwords = "";

    /**
     * 是否用**大模型**做识别（实时预览 / 落字 / 段落精化三者共用）。
     *
     * <p>默认 {@code true}：装好大模型即自动生效，这是刻意的 —— 用户抱怨的
     * 「识别不准 / 漏英文」根因就是小模型的精度上限（CER 17.15% 对 7.43%），
     * 而且小模型词表里**没有任何英文**（AI / APP / CPU / PDF 全部发不出来）。
     *
     * <p>那为什么还要给这个开关（决策见 {@code docs/DECISIONS.md} 的 D1）：
     * 大模型的代价是**实打实的**，实测（16GB 机器、启动 20 秒后稳定值）——
     * <b>工作集约 3.6GB、私有提交约 4.6GB</b>，且启动时要同步加载 16–18 秒；
     * 它还是**原生内存**，不在 Java 堆里（堆内仅 3MB），{@code -Xmx} 管不到。
     * 在 8GB 机器上这笔开销会挤压整机：提交量不足 → 换页 → 别的程序变慢，
     * 而归因不到这颗不到 1cm² 的小球上。
     *
     * <p><b>所以给的是"用户自己说了算"的出口，而不是程序替他悄悄降级</b> ——
     * 悄悄降级等于违背已经拍板的"准确率优先"，而用户还不知道自己为什么忽好忽坏
     * （§7「任何失败都必须可见」）。
     *
     * <p>它是「改一次就不再动」的参数，因此只放配置文件（与 {@link #charGapMillis} 同理），
     * 界面上不出现；诊断窗口的状态页会**如实显示**当前在用哪个模型、以及是不是被这个开关关掉的。
     * <b>改完需重启</b>（模型只在启动时加载一次）。
     */
    private boolean useLargeModel = true;

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

    /** 注入时每个字符之间的间隔（毫秒）。见 {@link #charGapMillis}。 */
    public int charGapMillis() {
        return charGapMillis;
    }

    public void setCharGapMillis(int v) {
        this.charGapMillis = v;
    }

    /** 热词纠正表原文（{@code 说的词=想要的写法}，逗号或换行分隔）。 */
    public String hotwords() {
        return hotwords;
    }

    public void setHotwords(String v) {
        this.hotwords = v == null ? "" : v.strip();
    }

    /** 是否用大模型做识别。见 {@link #useLargeModel}（改完需重启）。 */
    public boolean useLargeModel() {
        return useLargeModel;
    }

    public void setUseLargeModel(boolean v) {
        this.useLargeModel = v;
    }

    /**
     * 解析后的热词表。
     *
     * <p>解析放在这里而不是复用 {@code text.HotwordCorrector.parse}：
     * {@code core} **不得依赖 {@code text}**（{@code ArchitectureTest} 会强制拦住，
     * §4.5 的测试策略前提）。两处各有一个解析器是刻意接受的小重复 ——
     * 替代方案是让 core 依赖 text 或把解析器提到共享层，都比一份 5 行的解析更贵。
     */
    public java.util.Map<String, String> hotwordMap() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (hotwords == null || hotwords.isBlank()) {
            return out;
        }
        for (String item : hotwords.split("[,，;；\\n\\r]+")) {
            String s = item.strip();
            int i = s.indexOf('=');
            if (i <= 0 || i == s.length() - 1) {
                continue;   // 格式不对的项跳过：热词是锦上添花，不该让配置整体失败
            }
            String from = s.substring(0, i).strip();
            String to = s.substring(i + 1).strip();
            if (!from.isEmpty() && !to.isEmpty() && !from.equals(to)) {
                out.put(from, to);
            }
        }
        return out;
    }

    public Ball ball() {
        return ball;
    }

    // ------------------------------------------------------------ 拷贝

    /**
     * 深拷贝。
     *
     * <p><b>为什么必须有它：</b>设置窗口原先直接持有 {@code host.config()} 返回的
     * <b>活配置对象</b>，用户每敲一个字都改在"正在生效的那份配置"上。由此产生两个
     * 真实缺陷（都是静态审查抓出来的，见 §4.3 的「配置必须显式校验」）：
     *
     * <ol>
     *   <li>被 {@link #validate()} 拒绝的非法值<b>其实已经写进运行中的配置</b>了 ——
     *       界面却承诺「失败时不破坏当前生效值」；</li>
     *   <li>{@code App.applyConfig} 里那句「唤醒词 / 结束词是否变了」变成了
     *       <b>同一个对象和自己比</b>，恒为 {@code false} —— 于是改唤醒词
     *       <b>永远不会重建检测器</b>，而界面还写着「修改已保存并立即生效」。</li>
     * </ol>
     *
     * <p>所以设置窗口只在副本上改，改完整份交给 {@code applyConfig} 校验；
     * 校验不过就整份丢弃，生效中的配置一个字都没被碰过。
     */
    public AppConfig copy() {
        AppConfig c = new AppConfig();
        c.wakeWord = wakeWord;
        c.endWord = endWord;
        c.silenceSeconds = silenceSeconds;
        c.autoSend = autoSend;
        c.sendKey = sendKey;
        c.sendOnSilenceTimeout = sendOnSilenceTimeout;
        c.maxSegmentSeconds = maxSegmentSeconds;
        c.charGapMillis = charGapMillis;
        c.hotwords = hotwords;
        c.useLargeModel = useLargeModel;
        // 位置没保存过时 x/y 是 MIN_VALUE，这里必须原样带过去，
        // 否则 hasPosition() 会从 false 变成 true，悬浮球位置凭空"有了"。
        c.ball.setPosition(ball.x(), ball.y());
        c.ball.setDock(ball.dock());
        c.ball.setDockEnabled(ball.dockEnabled());
        return c;
    }

    // ------------------------------------------------------------ 校验

    /**
     * 合法但可能让用户意外的组合，用一句话说明白。
     *
     * <p>与 {@link #validate()} 的区别：那些是**不合法**（必须拒绝），这些是
     * **能用但结果可能不是用户想要的**（要说清楚，但绝不能拦）。混在一起会让
     * 用户以为配置错了。
     *
     * <p>目前只有一条：结束词留空 + 静音超时关闭。这是完全合法的选择
     * （本产品的定位就是"全程语音操控"，用户有权不要这两种收尾方式），
     * 但此时**只剩单段时长上限**兜底 —— 长了会被从中间截断落字。
     * 不说的话，用户会以为是软件把话吃了。
     */
    public List<String> warnings() {
        List<String> out = new ArrayList<>();
        if (endWord.isBlank() && silenceSeconds == 0) {
            // 刻意写得**短**：状态标签只有两行（约 560px），而这条曾经写到 694px ——
            // 结果被省略号吃掉"60 秒上限"和"截断落字"这两个真正的后果，
            // 等于提醒了却没说清。能用一句说清就不要用三句。
            out.add("结束词留空、静音也关着 —— 只剩 " + maxSegmentSeconds
                    + " 秒上限收尾，长句会被从中间截断。");
        }
        return out;
    }

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
        // 结束词**允许为空** —— 空值表示"不用结束词"，段落改由静音超时或切换窗口收尾。
        //
        // 早期这里要求非空，逻辑上站不住：结束词是三种收尾方式之一，把它设成必填
        // 就等于强迫用户接受一种自己不需要的收尾方式（而且他没法用按钮，见 §2.2
        // 「全程语音操控」）。配套的三处放开：VoskKeywordDetector 不再因空结束词
        // 抛错、MicValidator 跳过它、预览条不再显示「说「」或静音」。
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
        if (charGapMillis < MIN_CHAR_GAP_MILLIS || charGapMillis > MAX_CHAR_GAP_MILLIS) {
            problems.add("字符注入间隔必须在 " + MIN_CHAR_GAP_MILLIS + "–" + MAX_CHAR_GAP_MILLIS
                    + " 毫秒之间，实际是 " + charGapMillis);
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
        m.put("charGapMillis", charGapMillis);
        // 空字符串也写出去：让用户能在配置文件里看到「有热词这个功能」，
        // 否则一个从没配过热词的人根本不知道它存在。
        m.put("hotwords", hotwords);
        // 与 hotwords 同理写出去：让用户能在配置文件里看到「有这个大模型开关」，
        // 否则从没关过大模型的人根本不知道它存在（D1 的出口必须是**看得见**的）。
        m.put("useLargeModel", useLargeModel);
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
        // 注入间隔：老配置里可能有 0（它曾经是合法值，而灌太快会让自绘输入框丢字）。
        // 这里**抬到下限并记警告**，而不是让程序起不来 —— "非法配置=起不来"本身
        // 就是 PENDING 5.9 记着的坑，而这一条纯粹是历史遗留值的迁移。
        int gap = JsonCodec.intVal(m, "charGapMillis", DEFAULT_CHAR_GAP_MILLIS);
        if (gap < MIN_CHAR_GAP_MILLIS) {
            log.warn("配置里的 charGapMillis={} 低于下限 {}ms（0 会让微信这类自绘输入框丢字），"
                    + "已抬到下限；下次保存时写回", gap, MIN_CHAR_GAP_MILLIS);
            gap = MIN_CHAR_GAP_MILLIS;
        }
        c.charGapMillis = gap;
        c.hotwords = JsonCodec.str(m, "hotwords", "").strip();
        // 缺失时默认 true（老配置里没有这个键 → 行为与以前完全一致）
        c.useLargeModel = JsonCodec.bool(m, "useLargeModel", true);
        // 连续输入模式（continuousMode / stopWord / continuousIdleSeconds）已被移除：
        // 实测用起来比单段模式更繁琐 —— 说完结束词还要等静音超时才收尾，
        // 而单段模式里「到此为止」本身就立刻停止录音。旧配置里残留的这三个键
        // 在这里被**静默忽略**（不报错地正常起来），写回时自然消失。
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
