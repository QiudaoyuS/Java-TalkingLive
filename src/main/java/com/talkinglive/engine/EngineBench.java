package com.talkinglive.engine;

import com.talkinglive.core.AppPaths;
import com.talkinglive.core.Logging;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 引擎基准测量：把 {@code DESIGN.md} §6「性能与资源预算」里那些**必须实测回填**的数字量出来。
 *
 * <p>为什么需要它：§6 的表格里写着「下表中只有已实测的项标了具体数字来源，其余是目标值，
 * 需在 M1–M3 阶段实测后回填」。这个类就是那个回填工具——
 * 每次改引擎/换模型后重跑一次，把结果抄进 {@code docs/ENGINE-EXPERIMENT.md}。
 *
 * <p>能测与不能测要分清楚：
 * <ul>
 *   <li><b>能测</b>：模型加载耗时（冷启动预算）、常驻内存、识别器创建耗时、整段离线识别的
 *       实时倍率（用合成音频，只反映解码开销，不反映真实语音的质量）。</li>
 *   <li><b>不能测</b>：唤醒命中率、误触发率、真实语音的 CER、首字延迟、注入成功率。
 *       这些需要**麦克风与真人说话**，属于 {@code DESIGN.md} §9.3 的「手工集成验证」，
 *       只能在真实设备上跑。</li>
 * </ul>
 * 报告里必须把这两类分开写，否则会把「解码跑得动」误读成「产品能用了」。
 *
 * <p>用法：{@code java -cp ... com.talkinglive.engine.EngineBench [模型目录] [音频wav...]}
 */
public final class EngineBench {

    private EngineBench() {}

    private record Metric(String name, String value, String note) {}

    public static void main(String[] args) throws Exception {
        List<String> wavArgs = new ArrayList<>();
        Path modelDir = AppPaths.voskModelDir();
        for (String a : args) {
            if (a.endsWith(".wav")) {
                wavArgs.add(a);
            } else {
                modelDir = Path.of(a);
            }
        }

        List<Metric> metrics = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        out.append("TalkingLive 引擎基准测量\n").append("=".repeat(70)).append('\n');
        out.append("模型目录 : ").append(modelDir).append('\n');
        out.append("JVM      : ").append(System.getProperty("java.version")).append('\n');
        out.append("OS       : ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append('\n');
        out.append("CPU      : ").append(cpuName()).append('\n');
        out.append("可用核心 : ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        out.append("模块     : ").append(moduleInfo()).append('\n');

        if (!Files.isDirectory(modelDir)) {
            System.out.println("模型目录不存在：" + modelDir);
            System.exit(2);
        }

        report(out, metrics, "模型目录大小", dirSizeMb(modelDir) + " MB",
                "DESIGN.md §6 磁盘占用预算");

        // ---- 冷启动：模型加载 ----
        long t0 = System.nanoTime();
        try (VoskModel model = VoskModel.load(modelDir)) {
            long loadMs = (System.nanoTime() - t0) / 1_000_000;
            report(out, metrics, "模型加载耗时", loadMs + " ms",
                    "DESIGN.md §6 冷启动预算（含模型加载）< 3s");

            // 常驻内存（加载后）
            System.gc();
            Thread.sleep(300);
            long heapAfterLoad = usedHeapMb();
            report(out, metrics, "常驻堆内存（模型加载后）", heapAfterLoad + " MB",
                    "DESIGN.md §6 空闲内存预算 < 500MB");

            // ---- 词表查询开销 ----
            long q0 = System.nanoTime();
            int probes = 1000;
            for (int i = 0; i < probes; i++) {
                model.findWordId("子曰");
            }
            long qMs = (System.nanoTime() - q0) / 1_000_000;
            report(out, metrics, "词表查询 " + probes + " 次", qMs + " ms（"
                    + String.format("%.3f", qMs / (double) probes) + " ms/次）",
                    "仅启动时校验用，不在热路径上");

            report(out, metrics, "模型支持运行时词表", String.valueOf(model.supportsRuntimeGrammar()),
                    "附录 B.2：只有小模型能改运行时词表，唤醒词可自定义的前提");

            // ---- 识别器创建 ----
            long c0 = System.nanoTime();
            int creates = 20;
            for (int i = 0; i < creates; i++) {
                try (VoskModel.Recognizer r = model.createRecognizer(16000.0f)) {
                    r.accept(new byte[1600]);
                }
            }
            long createMs = (System.nanoTime() - c0) / 1_000_000;
            report(out, metrics, "创建流式识别器 " + creates + " 次", createMs + " ms（"
                    + String.format("%.2f", createMs / (double) creates) + " ms/次）",
                    "每段新建一次（精化路径），因此这个数字要小");

            // 受限语法识别器创建（会重建解码图，明显更贵）
            String grammar = VoskKeywordDetector.buildGrammar("子曰", "到此为止");
            long g0 = System.nanoTime();
            try (VoskModel.Recognizer r = model.createGrammarRecognizer(16000.0f, grammar)) {
                r.accept(new byte[1600]);
            }
            long gMs = (System.nanoTime() - g0) / 1_000_000;
            report(out, metrics, "创建受限语法识别器 1 次", gMs + " ms",
                    "含语言模型估计 + 解码图重建；只在启动与改配置时做一次");

            // ---- 离线识别吞吐（合成音频，只看解码开销） ----
            out.append('\n').append("--- 整段离线识别吞吐（合成音频）---\n");
            out.append("说明：这里用的是**合成音频**，只反映解码开销，不代表真实语音的准确率。\n");
            for (double seconds : new double[] {1.0, 5.0, 15.0}) {
                byte[] pcm = syntheticPcm(seconds);
                long r0 = System.nanoTime();
                try (VoskModel.Recognizer r = model.createRecognizer(16000.0f)) {
                    r.accept(pcm, pcm.length);
                    r.finalResult();
                }
                long rMs = (System.nanoTime() - r0) / 1_000_000;
                report(out, metrics,
                        String.format("离线重跑 %.0f 秒合成音频", seconds),
                        rMs + " ms（RTF=" + String.format("%.3f", rMs / 1000.0 / seconds) + "）",
                        "提交延迟预算 < 2.5s（§6）");
            }

            // ---- 真实音频（若给了 wav） ----
            if (!wavArgs.isEmpty()) {
                out.append('\n').append("--- 真实音频（16kHz 单声道 wav）---\n");
                for (String w : wavArgs) {
                    Path f = Path.of(w);
                    byte[] pcm;
                    try {
                        pcm = com.talkinglive.audio.WavFile.readPcm16Mono16k(f);
                    } catch (Exception e) {
                        out.append("跳过 ").append(f.getFileName()).append("：")
                                .append(e.getMessage()).append('\n');
                        continue;
                    }
                    double sec = pcm.length / 32000.0;
                    long r0 = System.nanoTime();
                    String text;
                    try (VoskModel.Recognizer r = model.createRecognizer(16000.0f)) {
                        r.accept(pcm, pcm.length);
                        text = r.finalResult();
                    }
                    long rMs = (System.nanoTime() - r0) / 1_000_000;
                    report(out, metrics,
                            String.format("%s（%.2fs）", f.getFileName(), sec),
                            rMs + " ms（RTF=" + String.format("%.3f", rMs / 1000.0 / sec) + "）",
                            "识别结果 " + Logging.describeWithFingerprint(text));
                }
            } else {
                out.append('\n').append("（未提供 wav；真实语音的 RTF 与 CER 需要真人录音，")
                        .append("见 DESIGN.md §9.3 手工验证清单）\n");
            }

            System.gc();
            Thread.sleep(300);
            report(out, metrics, "峰值堆内存（跑完所有测量后）", usedHeapMb() + " MB",
                    "DESIGN.md §6 听写中内存预算 < 1.5GB");
        }

        out.append('\n').append("=".repeat(70)).append('\n');
        out.append("测量项 ").append(metrics.size()).append(" 个\n");
        out.append("\n**无法在此测量的项**（需要麦克风与真人说话，属 §9.3 手工验证）：\n");
        out.append("  · 唤醒响应 < 300ms / 首字延迟 < 800ms\n");
        out.append("  · 唤醒命中率 ≥ 9/10、10 分钟误唤醒 = 0\n");
        out.append("  · 注入到真实应用的成功率（UIPI、Electron、自绘输入框）\n");
        out.append("  · 真实语音的识别质量与 CER\n");

        String report = out.toString();
        System.out.println(ascii(report));
        Path outFile = AppPaths.home().resolve("engine-bench.txt");
        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, report, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("\n[bench] full UTF-8 report -> " + outFile);
    }

    private static void report(StringBuilder sb, List<Metric> metrics, String name, String value,
            String note) {
        sb.append(String.format("%-30s : %-28s %s%n", name, value, note));
        metrics.add(new Metric(name, value, note));
    }

    private static String cpuName() {
        String v = System.getenv("PROCESSOR_IDENTIFIER");
        return v == null ? "(未知)" : v;
    }

    private static String moduleInfo() {
        try {
            Path p = AppPaths.home().resolve("native").resolve("vosk").resolve("libvosk.dll");
            return Files.exists(p) ? "libvosk.dll " + (Files.size(p) / 1024 / 1024) + " MB @" + p
                    : "libvosk.dll 未解包";
        } catch (Exception e) {
            return "(读取失败)";
        }
    }

    private static long dirSizeMb(Path dir) throws Exception {
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (Exception e) {
                    return 0;
                }
            }).sum() / 1024 / 1024;
        }
    }

    private static long usedHeapMb() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / 1024 / 1024;
    }

    /** 合成音频：一个缓慢起伏的正弦，让解码器有活干但没有真实语音内容。 */
    private static byte[] syntheticPcm(double seconds) {
        int n = (int) (seconds * 16000);
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double env = 0.5 + 0.5 * Math.sin(2 * Math.PI * i / (16000 * 0.4));
            short s = (short) (Math.sin(2 * Math.PI * 220 * i / 16000.0) * 12000 * env);
            pcm[i * 2] = (byte) (s & 0xFF);
            pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return pcm;
    }

    private static String ascii(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(c < 128 ? c : '?');
        }
        return sb.toString();
    }
}
