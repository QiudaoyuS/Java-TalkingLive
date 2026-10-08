package com.talkinglive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.talkinglive.core.StateMachine;
import com.talkinglive.text.TextInjector;
import com.talkinglive.text.TextUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * **规格测试**：跑 {@code specs/} 下的规格文件，钉住"已经验收过的行为"。
 *
 * <p>它和普通单测的区别在**期望值放在哪儿**：这里的期望值在 {@code specs/**} 的数据文件里，
 * 而不是散在断言里。这样 {@code git diff specs/} 一眼就能看出"规格被改过没有"——
 * 而"新的 AI 悄悄改了行为、又顺手改了测试让它继续通过"正是要防的事
 * （见 {@code docs/DECISIONS.md} 里关于规格的讨论）。
 *
 * <p>三条设计上的取舍：
 * <ul>
 *   <li><b>只固化可观察行为</b>：输入 → 终态 / 回调 / 是否注入 / 结束原因。
 *       刻意**不**碰类名、方法名、调用顺序 —— 否则每次重构都要改规格，人就开始
 *       "为了让测试过而改期望值"，规格随即作废。</li>
 *   <li><b>能单独跑</b>：整类带 {@code @Tag("spec")}，所以
 *       {@code mvnw test -Dgroups=spec} 只跑规格，{@code -DexcludedGroups=spec} 跑其余。
 *       负责人自己一条命令就能验证"已验收的功能还好不好用"，不必相信别人的转述。</li>
 *   <li><b>失败信息要人能读</b>：每条断言都带上规格文件里的「意图」那一行与用例名。</li>
 * </ul>
 *
 * <p>规格文件的改动规程写在每个文件头部：**改规格必须走 {@code [SPEC]} 提交**，
 * 并由 {@code tools\spec-freeze.ps1 -Check}（CI 里跑）与 {@code specs/SPEC.sha256}
 * 一起把它变成"必然留下可见痕迹"的动作。
 */
@Tag("spec")
class SpecTest {

    private static final Path LIFECYCLE_SPEC = Path.of("specs", "状态机", "段落生命周期.txt");
    private static final Path EVENTS_SPEC = Path.of("specs", "文本注入", "字符到事件.txt");

    @Nested
    @DisplayName("规格：段落生命周期（core.StateMachine）")
    class SegmentLifecycle {

        @Test
        void everyRowHolds() throws IOException {
            SpecFile spec = SpecFile.read(LIFECYCLE_SPEC);
            assertTrue(spec.rows.size() >= 15,
                    "规格行太少（" + spec.rows.size() + " 行）—— 文件被截断了？");

            List<String> failures = new ArrayList<>();
            for (String row : spec.rows) {
                String[] c = row.split("\\|", -1);
                assertEquals(8, c.length,
                        "规格行必须 8 列（用例名 | 事件 | 终态 | start | commitReady | inject | 原因 | 被忽略）：" + row);
                String name = c[0].trim();
                try {
                    String[] events = c[1].trim().split(">");
                    StateMachine.State wantState = StateMachine.State.valueOf(c[2].trim());
                    Integer wantStart = number(c[3]);
                    Integer wantReady = number(c[4]);
                    Boolean wantInject = yesNo(c[5]);
                    StateMachine.EndReason wantReason = c[6].trim().equals("-")
                            ? null : StateMachine.EndReason.valueOf(c[6].trim());
                    Integer wantIgnored = number(c[7]);

                    Recorder rec = new Recorder();
                    StateMachine sm = new StateMachine(rec);
                    for (String e : events) {
                        sm.handle(StateMachine.Event.valueOf(e.trim()));
                    }

                    assertEquals(wantState, sm.state(), row + spec.why());
                    if (wantStart != null) {
                        assertEquals(wantStart.intValue(), rec.starts, row + spec.why());
                    }
                    if (wantReady != null) {
                        assertEquals(wantReady.intValue(), rec.commits.size(), row + spec.why());
                    }
                    if (wantInject != null) {
                        assertTrue(!rec.commits.isEmpty(), "该行期望有一次提交上下文：" + row);
                        assertEquals(wantInject.booleanValue(),
                                rec.commits.get(rec.commits.size() - 1).inject(), row + spec.why());
                    }
                    if (wantReason != null) {
                        assertTrue(!rec.commits.isEmpty(), "该行期望有一次提交上下文：" + row);
                        assertEquals(wantReason, rec.commits.get(rec.commits.size() - 1).reason(),
                                row + spec.why());
                    }
                    if (wantIgnored != null) {
                        assertEquals(wantIgnored.intValue(), sm.ignoredTotal(), row + spec.why());
                    }
                } catch (AssertionError | RuntimeException ex) {
                    failures.add("・" + name + " → " + ex.getMessage());
                }
            }
            assertTrue(failures.isEmpty(),
                    "有 " + failures.size() + " 条规格不成立（改动是不是没走 [SPEC]？）：\n"
                            + String.join("\n", failures));
        }
    }

    @Nested
    @DisplayName("规格：注入分账（TextInjector.plannedEvents）")
    class InjectionAccounting {

        @Test
        void everyRowHolds() throws IOException {
            SpecFile spec = SpecFile.read(EVENTS_SPEC);
            assertTrue(spec.rows.size() >= 8,
                    "规格行太少（" + spec.rows.size() + " 行）—— 文件被截断了？");

            List<String> failures = new ArrayList<>();
            for (String row : spec.rows) {
                String[] c = row.split("\\|", -1);
                assertEquals(5, c.length,
                        "规格行必须 5 列（用例名 | 文本 | 退格数 | 先过滤 | 期望事件数）：" + row);
                String name = c[0].trim();
                try {
                    String text = unescape(c[1].trim());
                    int backspaces = Integer.parseInt(c[2].trim());
                    boolean filter = c[3].trim().equalsIgnoreCase("yes");
                    int want = Integer.parseInt(c[4].trim());
                    if (filter) {
                        text = TextUtils.withoutControlChars(text);
                    }
                    assertEquals(want, TextInjector.plannedEvents(text, backspaces), row + spec.why());
                } catch (AssertionError | RuntimeException ex) {
                    failures.add("・" + name + " → " + ex.getMessage());
                }
            }
            assertTrue(failures.isEmpty(),
                    "有 " + failures.size() + " 条规格不成立（改动是不是没走 [SPEC]？）：\n"
                            + String.join("\n", failures));
        }
    }

    // ------------------------------------------------------------ 零件

    /** 监听器替身：只记"规格关心的可观察行为"。 */
    private static final class Recorder implements StateMachine.Listener {
        int starts;
        final List<StateMachine.CommitContext> commits = new ArrayList<>();

        @Override
        public void onSegmentStartRequested() {
            starts++;
        }

        @Override
        public void onCommitReady(StateMachine.CommitContext ctx) {
            commits.add(ctx);
        }
    }

    /** 规格文件：头部「意图」+ 数据行。 */
    private static final class SpecFile {
        String intent = "（规格文件里没写「意图」）";
        final List<String> rows = new ArrayList<>();

        static SpecFile read(Path p) throws IOException {
            assertTrue(Files.exists(p), p + " 不存在 —— 规格文件是交付物的一部分，不能删");
            SpecFile spec = new SpecFile();
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.isEmpty()) {
                    continue;
                }
                if (t.startsWith("#")) {
                    if (t.startsWith("# 意图：")) {
                        spec.intent = t.substring("# 意图：".length()).strip();
                    }
                    continue;
                }
                spec.rows.add(t);
            }
            return spec;
        }

        /** 拼进断言消息，让失败能自己说清"这条规格是为了什么"。 */
        String why() {
            return "\n  规格意图：" + intent;
        }
    }

    private static Integer number(String s) {
        String t = s.trim();
        return t.equals("-") ? null : Integer.valueOf(t);
    }

    private static Boolean yesNo(String s) {
        String t = s.trim();
        if (t.equals("-")) {
            return null;
        }
        return t.equals("yes");
    }

    /** 规格文件里写 \n 表示换行；(空) 表示空串。 */
    private static String unescape(String s) {
        if (s.equals("(空)")) {
            return "";
        }
        return s.replace("\\n", "\n").replace("\\t", "\t");
    }
}
