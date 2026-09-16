package com.talkinglive.engine;

import com.talkinglive.core.AppPaths;
import com.talkinglive.system.MicValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 引擎冒烟自检：验证 Vosk 原生库能加载、模型能打开、词表能查询。
 *
 * <p>为什么要有它：{@code DESIGN.md} 附录 C 的静默失效只能靠
 * {@code vosk_model_find_word} 拦住，而这个函数**不在 Vosk 的 Java 绑定里**，
 * 是本项目用 JNA 直接声明的（见 {@code system.VoskNative}）。
 * 「声明得对不对」无法靠单测断言（需要真模型），所以做成一个可执行的冒烟自检。
 *
 * <p>用法：{@code java -cp ... com.talkinglive.engine.EngineSmoke}
 */
public final class EngineSmoke {

    private EngineSmoke() {}

    public static void main(String[] args) throws IOException {
        Path dir = args.length > 0 ? Path.of(args[0]) : AppPaths.voskModelDir();
        System.out.println("[smoke] model dir = " + dir);
        if (!Files.isDirectory(dir)) {
            System.out.println("[smoke] SKIP: model not present");
            System.exit(2);
            return;
        }
        long t0 = System.currentTimeMillis();
        try (VoskModel model = VoskModel.load(dir)) {
            System.out.println("[smoke] model loaded in " + (System.currentTimeMillis() - t0) + " ms");            String[][] probes = {
                {"子曰", "唤醒词（DESIGN.md 附录 B.1，应采用）"},
                {"小助手", "唤醒词备选，应在表内"},
                {"到此为止", "结束词（附录 B.1，应采用）"},
                {"结束", "结束词备选，应在表内"},
                {"完毕", "结束词备选，应在表内"},
                {"输入", "结束词备选，应在表内"},
                {"本段结束", "附录 B.1 明确记录为『不在表内』——应当被查出来"},
                {"小秘书", "附录 B.1 明确记录为『不在表内』——应当被查出来"},
                {"好了", "附录 B.1 记录为『不在表内』"},
                {"结束输入", "附录 B.1 记录为『不在表内』"},
            };
            int failures = 0;
            System.out.println("[smoke] --- vocabulary lookup (vosk_model_find_word via JNA) ---");
            for (String[] p : probes) {
                boolean in = model.findWord(p[0]);
                // 打印词表 id，便于判断「在表内」这个结论的依据
                int id = model.findWordId(p[0]);
                boolean expected = !p[0].equals("本段结束") && !p[0].equals("小秘书")
                        && !p[0].equals("好了") && !p[0].equals("结束输入");
                String mark = in == expected ? "ok  " : "BAD ";
                if (in != expected) {
                    failures++;
                }
                System.out.printf("[smoke] %s %-10s inVocabulary=%-5s wordId=%-8d (%s)%n",
                        mark, p[0], in, id, p[1]);
            }

            System.out.println("[smoke] --- grammar construction ---");
            String grammar = VoskKeywordDetector.buildGrammar("子曰", "到此为止");
            System.out.println("[smoke] grammar = " + grammar);
            boolean grammarShapeOk = grammar.equals("[\"子曰\",\"到此为止\",\"[unk]\"]");
            System.out.println("[smoke] " + (grammarShapeOk ? "ok  " : "BAD ")
                    + "grammar is a plain JSON array (C API format, not phrase_list object)");
            if (!grammarShapeOk) {
                failures++;
            }
            System.out.println("[smoke] runtime grammar supported by model = "
                    + model.supportsRuntimeGrammar()
                    + "  (HCLr.fst + Gr.fst present -> small model, DESIGN.md Appendix B.2)");

            System.out.println("[smoke] --- grammar recognizer creation (was: Invalid memory access) ---");
            try (VoskModel.Recognizer grm = model.createGrammarRecognizer(16000.0f, grammar)) {
                // 喂 1 秒静音：能走到这里就说明受限语法被 Vosk 接受了
                grm.accept(new byte[32000]);
                System.out.println("[smoke] ok   restricted-grammar recognizer created and fed 1s silence");
            } catch (Exception e) {
                System.out.println("[smoke] BAD  grammar recognizer failed: " + e);
                failures++;
            }

            System.out.println("[smoke] --- plain streaming recognizer ---");
            try (VoskModel.Recognizer rec = model.createRecognizer(16000.0f)) {
                rec.accept(new byte[32000]);
                System.out.println("[smoke] ok   streaming recognizer created, partial='"
                        + rec.partialResult() + "'");
            } catch (Exception e) {
                System.out.println("[smoke] BAD  streaming recognizer failed: " + e);
                failures++;
            }

            System.out.println("[smoke] --- MicValidator wiring ---");
            MicValidator.Result r = MicValidator.validate(model::findWord, "子曰", "到此为止");
            System.out.println("[smoke] validate(子曰, 到此为止).ok = " + r.ok());
            MicValidator.Result bad = MicValidator.validate(model::findWord, "子曰", "本段结束");
            System.out.println("[smoke] validate(子曰, 本段结束).ok = " + bad.ok());
            if (bad.ok()) {
                System.out.println("[smoke] BAD: 词表外的结束词没有被拦住 —— 附录 C 的静默失效没有被防住");
                failures++;
            } else {
                System.out.println("[smoke] 拦截消息：" + bad.message().replace("\n", " | "));
            }

            System.out.println("[smoke] failures = " + failures);
            System.exit(failures == 0 ? 0 : 1);
        } catch (RuntimeException e) {
            System.out.println("[smoke] ERROR: " + e);
            e.printStackTrace(System.out);
            System.exit(3);
        }
    }
}
