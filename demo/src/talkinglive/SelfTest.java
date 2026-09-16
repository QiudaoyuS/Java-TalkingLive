package talkinglive;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static talkinglive.StateMachine.Event.CANCEL;
import static talkinglive.StateMachine.Event.COMMITTED;
import static talkinglive.StateMachine.Event.END_WORD;
import static talkinglive.StateMachine.Event.SILENCE;
import static talkinglive.StateMachine.Event.WAKE;
import static talkinglive.StateMachine.Event.WINDOW_CHANGE;
import static talkinglive.StateMachine.State.COMMITTING;
import static talkinglive.StateMachine.State.IDLE;
import static talkinglive.StateMachine.State.LISTENING;

/**
 * StateMachine 的自检。
 *
 * <p>项目尚未引入 Maven/JUnit，先用零依赖的自检脚本顶上，
 * 保证 AGENTS.md 要求的「交付前测试通过」是可执行的。
 * 骨架建立后应迁移为 JUnit 5 测试。
 *
 * <p><b>编码约定</b>：详细报告写入 UTF-8 文件 {@code selftest-report.txt}，
 * 控制台只输出 ASCII 摘要。这是刻意的 —— Windows 控制台默认代码页是 GBK，
 * Java 8 写中文会乱码，而这类乱码会掩盖真正的失败信息。
 *
 * <p>运行：{@code java -cp out talkinglive.SelfTest}，失败时退出码为 1。
 */
public class SelfTest {

    private static final String REPORT = "selftest-report.txt";

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> lines = new ArrayList<String>();

    public static void main(String[] args) {
        testHappyPath();
        testEndWord();
        testSilenceFallback();
        testWindowChangeEndsSegment();
        testCancelFromListening();
        testCancelFromCommitting();
        testIllegalEventsAreIgnored();
        testLateEventsDuringCommit();
        testListenerFires();
        testTransitionCount();

        lines.add("");
        lines.add("========================================");
        lines.add("通过 " + passed + " 项，失败 " + failed + " 项");
        lines.add("========================================");
        writeReport();

        // 控制台只用 ASCII，避免 Windows 代码页问题
        System.out.println("StateMachine self-test");
        System.out.println("  passed = " + passed);
        System.out.println("  failed = " + failed);
        System.out.println("  report = " + REPORT + "  (UTF-8)");
        System.out.println("RESULT: " + (failed == 0 ? "PASS" : "FAIL"));

        System.exit(failed == 0 ? 0 : 1);
    }

    private static void writeReport() {
        PrintWriter w = null;
        try {
            w = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(REPORT), StandardCharsets.UTF_8));
            for (String line : lines) {
                w.println(line);
            }
        } catch (Exception e) {
            System.out.println("WARN: 无法写入报告文件: " + e.getMessage());
        } finally {
            if (w != null) {
                w.close();
            }
        }
    }

    // ---------- 用例 ----------

    private static void testHappyPath() {
        StateMachine sm = new StateMachine();
        check("初始状态为 IDLE", sm.state() == IDLE);
        check("唤醒 -> LISTENING", sm.fire(WAKE) && sm.state() == LISTENING);
        check("结束词 -> COMMITTING", sm.fire(END_WORD) && sm.state() == COMMITTING);
        check("提交完成 -> IDLE", sm.fire(COMMITTED) && sm.state() == IDLE);
    }

    private static void testEndWord() {
        StateMachine sm = new StateMachine();
        sm.fire(WAKE);
        check("结束词可结束听写", sm.fire(END_WORD));
    }

    private static void testSilenceFallback() {
        StateMachine sm = new StateMachine();
        sm.fire(WAKE);
        check("静音兜底可结束听写", sm.fire(SILENCE) && sm.state() == COMMITTING);
    }

    private static void testWindowChangeEndsSegment() {
        StateMachine sm = new StateMachine();
        sm.fire(WAKE);
        check("切窗口视为一段结束", sm.fire(WINDOW_CHANGE) && sm.state() == COMMITTING);
    }

    private static void testCancelFromListening() {
        StateMachine sm = new StateMachine();
        sm.fire(WAKE);
        check("Esc 可从听写中直接回到待唤醒",
                sm.fire(CANCEL) && sm.state() == IDLE);
    }

    private static void testCancelFromCommitting() {
        StateMachine sm = new StateMachine();
        sm.fire(WAKE);
        sm.fire(END_WORD);
        check("提交中也可取消", sm.fire(CANCEL) && sm.state() == IDLE);
    }

    private static void testIllegalEventsAreIgnored() {
        StateMachine sm = new StateMachine();
        check("待唤醒时结束词无效", !sm.fire(END_WORD));
        check("待唤醒时静音无效", !sm.fire(SILENCE));
        check("待唤醒时提交完成无效", !sm.fire(COMMITTED));
        check("非法事件后状态不变", sm.state() == IDLE);
        check("非法事件被记录", sm.ignoredEvents().size() == 3);
    }

    /** 真实竞态：唤醒词会被重复触发（用户连说两遍）。 */
    private static void testLateEventsDuringCommit() {
        StateMachine sm = new StateMachine();
        sm.fire(WAKE);
        check("听写中重复唤醒被忽略", !sm.fire(WAKE));

        sm.fire(END_WORD);
        check("提交中迟到的静音被忽略", !sm.fire(SILENCE));
        check("提交中迟到的结束词被忽略", !sm.fire(END_WORD));
        check("提交中迟到的窗口变化被忽略", !sm.fire(WINDOW_CHANGE));
        check("忽略后仍在 COMMITTING", sm.state() == COMMITTING);
        check("仍可正常完成", sm.fire(COMMITTED) && sm.state() == IDLE);
    }

    private static void testListenerFires() {
        StateMachine sm = new StateMachine();
        final int[] count = {0};
        final String[] seen = {"", ""};
        sm.onTransition((from, to) -> {
            count[0]++;
            seen[0] = from.name();
            seen[1] = to.name();
        });
        sm.fire(WAKE);
        check("监听器被调用一次", count[0] == 1);
        check("监听器收到正确的前后状态",
                "IDLE".equals(seen[0]) && "LISTENING".equals(seen[1]));

        sm.fire(COMMITTED); // LISTENING 状态下这是无效事件
        check("无效事件不触发监听器", count[0] == 1);
    }

    /** 覆盖全部合法转移，防止后续改动悄悄放开非法路径。 */
    private static void testTransitionCount() {
        List<StateMachine.State> all = Arrays.asList(IDLE, LISTENING, COMMITTING);
        int legal = 0;
        for (StateMachine.State from : all) {
            for (StateMachine.Event e : StateMachine.Event.values()) {
                StateMachine sm = new StateMachine();
                legal += walk(sm, from) && sm.fire(e) ? 1 : 0;
            }
        }
        // IDLE:WAKE ／ LISTENING:END_WORD/SILENCE/WINDOW_CHANGE/CANCEL
        // ／ COMMITTING:COMMITTED/CANCEL
        check("合法转移共 7 条，实际 " + legal, legal == 7);
    }

    /** 把状态机推到指定状态。 */
    private static boolean walk(StateMachine sm, StateMachine.State target) {
        switch (target) {
            case IDLE:       return true;
            case LISTENING:  return sm.fire(WAKE);
            case COMMITTING: return sm.fire(WAKE) && sm.fire(END_WORD);
        }
        return false;
    }

    // ---------- 断言 ----------

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            lines.add("[通过] " + name);
        } else {
            failed++;
            lines.add("[失败] " + name);
        }
    }
}
