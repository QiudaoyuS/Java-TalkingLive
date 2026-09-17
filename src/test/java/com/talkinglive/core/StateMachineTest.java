package com.talkinglive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.talkinglive.core.StateMachine.EndReason;
import com.talkinglive.core.StateMachine.Event;
import com.talkinglive.core.StateMachine.State;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 状态机测试 —— {@code DESIGN.md} §9.1 的第一层（纯逻辑单测，不需要麦克风/桌面）。
 *
 * <p>重点不在「正常流程能跑通」（那太容易），而在**真实竞态**：
 * 重复唤醒、提交中迟到事件、静音与结束词同时到达、Esc 在提交阶段按下。
 * §2.1 明确要求这些迟到事件被「吃掉而不是抛异常」，所以每一条都要有用例。
 */
class StateMachineTest {

    private StateMachine sm;
    private Recorder rec;

    /** 记录回调顺序，用于断言「谁先谁后」。 */
    private static final class Recorder implements StateMachine.Listener {
        final List<String> events = new ArrayList<>();
        EndReason lastEndReason;
        StateMachine.CommitContext lastCommit;
        String lastAbandon;
        int starts;
        int commits;
        int ignored;

        @Override
        public void onSegmentStartRequested() {
            starts++;
            events.add("start");
        }

        @Override
        public void onSegmentEndRequested(EndReason reason) {
            lastEndReason = reason;
            events.add("end:" + reason);
        }

        @Override
        public void onCommitReady(StateMachine.CommitContext ctx) {
            commits++;
            lastCommit = ctx;
            events.add("commit");
        }

        @Override
        public void onSegmentAbandoned(String why) {
            lastAbandon = why;
            events.add("abandon");
        }

        @Override
        public void onEventIgnored(Event event, State state) {
            ignored++;
        }
    }

    @BeforeEach
    void setUp() {
        rec = new Recorder();
        sm = new StateMachine(rec);
    }

    // ============================================================ 初始状态

    @Test
    @DisplayName("启动时处于 IDLE，且未暂停")
    void startsIdle() {
        assertEquals(State.IDLE, sm.state());
        assertFalse(sm.paused());
        assertTrue(sm.idle());
        assertFalse(sm.listening());
        assertFalse(sm.committing());
    }

    // ============================================================ 正常流程

    @Nested
    @DisplayName("正常流程")
    class HappyPath {

        @Test
        @DisplayName("唤醒词：IDLE → LISTENING，并请求开始段落")
        void wakeWordStartsSegment() {
            sm.handle(Event.WAKE_WORD);
            assertEquals(State.LISTENING, sm.state());
            assertEquals(1, rec.starts);
            assertEquals(List.of("start"), rec.events);
        }

        @Test
        @DisplayName("结束词：LISTENING → COMMITTING，原因=END_WORD")
        void endWordCommits() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.END_WORD);
            assertEquals(State.COMMITTING, sm.state());
            assertEquals(EndReason.END_WORD, rec.lastEndReason);
        }

        @Test
        @DisplayName("精化完成后放行注入：onCommitReady 收到正确的上下文")
        void refineDoneAllowsCommit() {
            sm.handle(Event.WAKE_WORD);
            long gen = sm.generation();
            sm.handle(Event.END_WORD);
            sm.handle(Event.REFINE_DONE);
            assertEquals(1, rec.commits);
            assertNotNull(rec.lastCommit);
            assertEquals(gen, rec.lastCommit.generation());
            assertTrue(rec.lastCommit.inject(), "没切窗口就应该注入");
            assertEquals(EndReason.END_WORD, rec.lastCommit.reason());
        }

        @Test
        @DisplayName("注入完成后回到 IDLE")
        void injectedReturnsToIdle() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.END_WORD);
            sm.handle(Event.REFINE_DONE);
            sm.handle(Event.INJECTED);
            assertEquals(State.IDLE, sm.state());
        }

        @Test
        @DisplayName("完整回调顺序：start → end → commit")
        void callbackOrder() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.END_WORD);
            sm.handle(Event.REFINE_DONE);
            sm.handle(Event.INJECTED);
            assertEquals(List.of("start", "end:" + EndReason.END_WORD, "commit"), rec.events);
        }

        @Test
        @DisplayName("悬浮球左键：IDLE 开始、LISTENING 结束（§2.3 两条路径）")
        void toggleBothWays() {
            sm.handle(Event.TOGGLE);
            assertEquals(State.LISTENING, sm.state());
            sm.handle(Event.TOGGLE);
            assertEquals(State.COMMITTING, sm.state());
            assertEquals(EndReason.MANUAL, rec.lastEndReason);
        }

        @Test
        @DisplayName("静音超时结束本段，原因=SILENCE_TIMEOUT")
        void silenceTimeoutEnds() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.SILENCE_TIMEOUT);
            assertEquals(State.COMMITTING, sm.state());
            assertEquals(EndReason.SILENCE_TIMEOUT, rec.lastEndReason);
        }

        @Test
        @DisplayName("单段达到时长上限自动结束（§7）")
        void maxSegmentEnds() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.MAX_SEGMENT_REACHED);
            assertEquals(State.COMMITTING, sm.state());
            assertEquals(EndReason.MAX_SEGMENT, rec.lastEndReason);
        }

        @Test
        @DisplayName("每次进入 LISTENING 代数递增（用于丢弃上一段的迟到回调）")
        void generationIncrements() {
            long g0 = sm.generation();
            sm.handle(Event.WAKE_WORD);
            long g1 = sm.generation();
            sm.handle(Event.CANCEL);
            sm.handle(Event.WAKE_WORD);
            long g2 = sm.generation();
            assertTrue(g1 > g0);
            assertTrue(g2 > g1);
        }
    }

    // ============================================================ 取消

    @Nested
    @DisplayName("取消（Esc）")
    class Cancel {

        @Test
        @DisplayName("LISTENING 时 Esc：回 IDLE 且不注入")
        void cancelWhileListening() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.CANCEL);
            assertEquals(State.IDLE, sm.state());
            assertEquals(0, rec.commits, "取消后绝不能进入注入");
            assertNotNull(rec.lastAbandon);
        }

        @Test
        @DisplayName("COMMITTING 时 Esc 也要能取消（§2.3 明确包含提交阶段）")
        void cancelWhileCommitting() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.END_WORD);
            sm.handle(Event.CANCEL);
            assertEquals(State.IDLE, sm.state());
            assertEquals(0, rec.commits);
            assertNotNull(rec.lastAbandon);
        }

        @Test
        @DisplayName("取消后迟到的精化结果被忽略（代理在途推理）")
        void lateRefineAfterCancelIsIgnored() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.END_WORD);
            sm.handle(Event.CANCEL);
            int ignoredBefore = sm.ignoredTotal();
            sm.handle(Event.REFINE_DONE);
            assertEquals(0, rec.commits, "取消之后精化完成不能再触发注入");
            assertTrue(sm.ignoredTotal() > ignoredBefore);
        }

        @Test
        @DisplayName("IDLE 时按 Esc 被忽略")
        void cancelWhileIdleIgnored() {
            sm.handle(Event.CANCEL);
            assertEquals(State.IDLE, sm.state());
            assertEquals(1, sm.ignoredTotal());
        }
    }

    // ============================================================ 切窗口
    @Nested
    @DisplayName("切窗口（§7 原为「放弃注入」；实测后改为照常提交 + 注入时还原焦点）")
    class ForegroundChange {

        @Test
        @DisplayName("LISTENING 时切窗口：**照常提交**而不是丢弃整段")
        void changeCommitsInsteadOfAbandoning() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.FOREGROUND_CHANGED);
            assertEquals(State.COMMITTING, sm.state(),
                    "切窗口不再丢弃本段——用户刚说的话不该凭空消失");
            assertEquals(EndReason.FOREGROUND_CHANGED, rec.lastEndReason);
            assertNull(rec.lastAbandon, "不该走「放弃」路径");
        }

        @Test
        @DisplayName("切窗口后仍会放行注入，但 inject 上下文标为「前台已变」")
        void changeStillAllowsInjection() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.FOREGROUND_CHANGED);
            sm.handle(Event.REFINE_DONE);
            assertEquals(1, rec.commits);
            assertFalse(rec.lastCommit.inject(),
                    "inject=false 表示目标不是当前前台，由注入层尝试还原焦点");
        }

        @Test
        @DisplayName("切窗口提交后回到 IDLE，shouldInject 复位")
        void changeThenInjectedReturnsToIdle() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.FOREGROUND_CHANGED);
            sm.handle(Event.REFINE_DONE);
            sm.handle(Event.INJECTED);
            assertEquals(State.IDLE, sm.state());
            assertTrue(sm.shouldInject(), "新段落应恢复「可注入」");
        }

        @Test
        @DisplayName("切窗口之后重复的窗口变化事件被忽略（不重复提交）")
        void repeatedChangeIgnored() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.FOREGROUND_CHANGED);
            int before = sm.ignoredTotal();
            sm.handle(Event.FOREGROUND_CHANGED);
            assertTrue(sm.ignoredTotal() > before);
            assertEquals(1, rec.events.stream().filter(e -> e.startsWith("end:")).count(),
                    "只能触发一次段落结束");
        }

        @Test
        @DisplayName("IDLE 时切窗口被忽略（这是最常见的路径，不能有副作用）")
        void changeWhileIdleIgnored() {
            sm.handle(Event.FOREGROUND_CHANGED);
            assertEquals(1, sm.ignoredTotal());
            assertEquals(0, rec.commits);
            assertEquals(State.IDLE, sm.state());
        }

        @Test
        @DisplayName("COMMITTING 时切窗口被忽略（注入目标已锁定）")
        void changeWhileCommittingIgnored() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.END_WORD);
            int ignoredBefore = sm.ignoredTotal();
            sm.handle(Event.FOREGROUND_CHANGED);
            assertTrue(sm.ignoredTotal() > ignoredBefore);
            sm.handle(Event.REFINE_DONE);
            assertTrue(rec.lastCommit.inject(), "提交阶段的窗口变化不应取消注入");
        }
    }

    // ============================================================ 竞态

    @Nested
    @DisplayName("真实竞态（§2.1：迟到事件是常态）")
    class Races {

        @Test
        @DisplayName("重复唤醒被忽略，段落不重启")
        void duplicateWakeIgnored() {
            sm.handle(Event.WAKE_WORD);
            long gen = sm.generation();
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.WAKE_WORD);
            assertEquals(State.LISTENING, sm.state());
            assertEquals(gen, sm.generation(), "重新唤醒不应重启段落（会丢掉已录音频）");
            assertEquals(1, rec.starts);
            assertEquals(2, sm.ignoredCounts().get(Event.WAKE_WORD));
        }

        @Test
        @DisplayName("结束词与静音超时几乎同时到达：先到者赢，后到者被吃掉")
        void endWordAndSilenceRace() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.END_WORD);
            sm.handle(Event.SILENCE_TIMEOUT);
            assertEquals(State.COMMITTING, sm.state());
            assertEquals(EndReason.END_WORD, rec.lastEndReason);
            assertEquals(1, rec.events.stream().filter(e -> e.startsWith("end:")).count(),
                    "只能触发一次段落结束");
        }

        @Test
        @DisplayName("静音先到、结束词后到也是同理")
        void silenceThenEndWordRace() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.SILENCE_TIMEOUT);
            sm.handle(Event.END_WORD);
            assertEquals(EndReason.SILENCE_TIMEOUT, rec.lastEndReason);
            assertEquals(1, rec.events.stream().filter(e -> e.startsWith("end:")).count());
        }

        @Test
        @DisplayName("COMMITTING 阶段引擎还会吐中间结果 → 唤醒/结束词都被吃掉")
        void lateEventsDuringCommitting() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.END_WORD);
            int before = sm.ignoredTotal();
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.END_WORD);
            sm.handle(Event.SILENCE_TIMEOUT);
            sm.handle(Event.MAX_SEGMENT_REACHED);
            sm.handle(Event.TOGGLE);
            assertEquals(5, sm.ignoredTotal() - before);
            assertEquals(State.COMMITTING, sm.state());
        }

        @Test
        @DisplayName("COMMITTING 阶段重复的 REFINE_DONE 不应触发两次注入")
        void duplicateRefineDone() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.END_WORD);
            sm.handle(Event.REFINE_DONE);
            int commitsAfterFirst = rec.commits;
            sm.handle(Event.REFINE_DONE);
            assertEquals(commitsAfterFirst, rec.commits, "第二次精化完成必须被忽略");
        }

        @Test
        @DisplayName("COMMITTING 阶段重复的 INJECTED：第一次就回 IDLE，第二次被忽略")
        void duplicateInjected() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.END_WORD);
            sm.handle(Event.REFINE_DONE);
            sm.handle(Event.INJECTED);
            assertEquals(State.IDLE, sm.state());
            int before = sm.ignoredTotal();
            sm.handle(Event.INJECTED);
            assertTrue(sm.ignoredTotal() > before);
        }

        @Test
        @DisplayName("窗口监听重复触发：第二次在 IDLE 被忽略")
        void duplicateForegroundChange() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.FOREGROUND_CHANGED);
            int before = sm.ignoredTotal();
            sm.handle(Event.FOREGROUND_CHANGED);
            assertTrue(sm.ignoredTotal() > before);
        }

        @Test
        @DisplayName("任何非法事件都不抛异常（§2.1）")
        void neverThrows() {
            for (Event e : Event.values()) {
                for (int i = 0; i < 3; i++) {
                    sm.handle(e);
                }
            }
            for (Event e : Event.values()) {
                sm.handle(e);
            }
            assertNotNull(sm.state());
        }

        @Test
        @DisplayName("被忽略的事件有计数与可读记录（§2.1 的「记录」）")
        void ignoredEventsAreRecorded() {
            sm.handle(Event.END_WORD);
            sm.handle(Event.SILENCE_TIMEOUT);
            assertEquals(2, sm.ignoredTotal());
            assertFalse(sm.ignoredLog().isEmpty());
            assertTrue(sm.ignoredLog().get(0).contains("END_WORD"));
        }

        @Test
        @DisplayName("忽略记录不会无限增长")
        void ignoredLogIsBounded() {
            for (int i = 0; i < 500; i++) {
                sm.handle(Event.END_WORD);
            }
            assertTrue(sm.ignoredLog().size() <= 64, "忽略记录必须有上限：" + sm.ignoredLog().size());
        }
    }

    // ============================================================ 暂停

    @Nested
    @DisplayName("暂停 / 恢复监听（§2.3）")
    class Pause {

        @Test
        @DisplayName("暂停后忽略唤醒词与结束词")
        void pausedIgnoresKeywords() {
            sm.handle(Event.PAUSE);
            assertTrue(sm.paused());
            sm.handle(Event.WAKE_WORD);
            assertEquals(State.IDLE, sm.state());
            sm.handle(Event.END_WORD);
            assertEquals(State.IDLE, sm.state());
            assertTrue(sm.ignoredTotal() >= 2);
        }

        @Test
        @DisplayName("暂停时正在听写：丢弃本段")
        void pauseDuringListeningAbandons() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.PAUSE);
            assertEquals(State.IDLE, sm.state());
            assertNotNull(rec.lastAbandon);
            assertEquals(0, rec.commits);
        }

        @Test
        @DisplayName("恢复后能再次唤醒")
        void resumeAllowsWakeAgain() {
            sm.handle(Event.PAUSE);
            sm.handle(Event.RESUME);
            assertFalse(sm.paused());
            sm.handle(Event.WAKE_WORD);
            assertEquals(State.LISTENING, sm.state());
        }

        @Test
        @DisplayName("重复暂停 / 未暂停时恢复都被忽略")
        void idempotentPauseResume() {
            sm.handle(Event.PAUSE);
            int before = sm.ignoredTotal();
            sm.handle(Event.PAUSE);
            assertTrue(sm.ignoredTotal() > before);

            sm.handle(Event.RESUME);
            before = sm.ignoredTotal();
            sm.handle(Event.RESUME);
            assertTrue(sm.ignoredTotal() > before);
        }

        @Test
        @DisplayName("暂停时静音超时与时长上限都不结束段落")
        void pausedIgnoresTimeouts() {
            sm.handle(Event.PAUSE);
            int before = sm.ignoredTotal();
            sm.handle(Event.SILENCE_TIMEOUT);
            sm.handle(Event.MAX_SEGMENT_REACHED);
            assertTrue(sm.ignoredTotal() > before);
            assertEquals(0, rec.commits);
        }
    }

    // ============================================================ 监听器健壮性

    @Test
    @DisplayName("监听器抛异常不能把状态机带崩（音频线程上的回调尤其重要）")
    void listenerExceptionIsContained() {
        StateMachine s = new StateMachine(new StateMachine.Listener() {
            @Override
            public void onSegmentStartRequested() {
                throw new IllegalStateException("监听器炸了");
            }
        });
        s.handle(Event.WAKE_WORD);
        assertEquals(State.LISTENING, s.state(), "监听器异常不应阻止状态流转");
    }

    @Test
    @DisplayName("状态未变化时不重复广播")
    void noRedundantBroadcast() {
        List<String> broadcasts = new ArrayList<>();
        StateMachine s = new StateMachine(new StateMachine.Listener() {
            @Override
            public void onStateChanged(State from, State to, Event cause) {
                broadcasts.add(from + "->" + to);
            }
        });
        s.handle(Event.PAUSE);
        s.handle(Event.RESUME);
        // 暂停/恢复不改状态，但会显式通知一次（UI 需要刷新）；其余事件不应额外广播
        assertTrue(broadcasts.size() <= 2, "不应有冗余广播：" + broadcasts);
    }

    @Test
    @DisplayName("线程安全：多线程投递事件不丢不崩")
    void threadSafe() throws InterruptedException {
        StateMachine s = new StateMachine();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                for (int k = 0; k < 500; k++) {
                    s.handle(Event.SILENCE_TIMEOUT);
                    s.handle(Event.END_WORD);
                    s.handle(Event.INJECTED);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join(10_000);
        }
        assertNotNull(s.state());
    }

    // ============================================================ 自动发送规则

    /**
     * {@link StateMachine#autoSendAllowed} —— 这条规则原来散在 App 里，只判
     * 「静音超时 + 开关」，**漏掉了最关键的一维**：提交时前台窗口还是不是目标。
     *
     * <p>后果不对称，所以必须钉死：文字注入到别的窗口顶多是位置不对（还能剪走），
     * 而一个回车落在聊天工具里就是**把还没写完的消息发出去**，不可挽回。
     */
    @Nested
    @DisplayName("自动发送规则（前台已变时绝不按回车）")
    class AutoSend {

        private StateMachine.CommitContext ctx(boolean inject, EndReason reason) {
            return new StateMachine.CommitContext(1, inject, reason);
        }

        @Test
        @DisplayName("正常提交（前台没变）应当发送")
        void normalCommitSends() {
            assertTrue(StateMachine.autoSendAllowed(ctx(true, EndReason.END_WORD), false));
            assertTrue(StateMachine.autoSendAllowed(ctx(true, EndReason.MANUAL), false));
            assertTrue(StateMachine.autoSendAllowed(ctx(true, EndReason.MAX_SEGMENT), false));
        }

        @Test
        @DisplayName("提交时前台已变：无论什么结束原因都**不**发送")
        void foregroundChangedNeverSends() {
            for (EndReason reason : EndReason.values()) {
                assertFalse(StateMachine.autoSendAllowed(ctx(false, reason), true),
                        "前台已变却仍要发送：" + reason);
            }
        }

        @Test
        @DisplayName("静音超时结束要单独看「静音超时后发送」开关（附录 A，默认关）")
        void silenceTimeoutFollowsItsOwnSwitch() {
            assertFalse(StateMachine.autoSendAllowed(ctx(true, EndReason.SILENCE_TIMEOUT), false));
            assertTrue(StateMachine.autoSendAllowed(ctx(true, EndReason.SILENCE_TIMEOUT), true));
        }

        @Test
        @DisplayName("结束词/手动结束不看那个开关（它只管静音超时）")
        void otherReasonsIgnoreSilenceSwitch() {
            assertTrue(StateMachine.autoSendAllowed(ctx(true, EndReason.END_WORD), false));
            assertTrue(StateMachine.autoSendAllowed(ctx(true, EndReason.MANUAL), false));
        }

        @Test
        @DisplayName("切窗口后仍照常提交：ctx.inject() 为假但状态机确实放行了提交")
        void foregroundChangeStillCommits() {
            Recorder r = new Recorder();
            StateMachine s = new StateMachine(r);
            s.handle(Event.WAKE_WORD);
            s.handle(Event.FOREGROUND_CHANGED);
            assertEquals(State.COMMITTING, s.state(), "切窗口必须仍然结束本段（否则就是丢话）");
            s.handle(Event.REFINE_DONE);
            assertEquals(1, r.commits, "切窗口后必须仍然放行提交");
            assertNotNull(r.lastCommit);
            assertFalse(r.lastCommit.inject(), "前台已变 → inject 标志为假，供上层决定要不要自动发送");
            // 而「注入与否」与「要不要发送」是两件事：文字照注入（由 App 做，走焦点还原），
            // 发送则被上面那条规则拦住。
            assertFalse(StateMachine.autoSendAllowed(r.lastCommit, true));
        }
    }
}
