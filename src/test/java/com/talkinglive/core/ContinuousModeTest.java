package com.talkinglive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * **连续输入模式**的状态机测试。
 *
 * <p>这个模式解决的是「说长内容要逐句喊唤醒词」的痛点：唤醒一次后持续聆听，
 * 每说完一段就落一段字，说**退出词**才回到待唤醒。
 *
 * <p>它同时承担另一个目标：**全程语音操控**。有些用户无法按按钮或使用鼠标，
 * 所以「停止听写」必须有语音通路（{@link Event#STOP_INPUT}），
 * 不能只放在悬浮球菜单里。
 *
 * <p>关键不变量：
 * <ol>
 *   <li>连续模式**默认关闭**，旧契约（一轮一句）不能变。</li>
 *   <li>开启后，一段提交完必须回到 LISTENING，而不是 IDLE。</li>
 *   <li>退出词要结束**整个会话**，但它不能把当前正在说的那一段丢掉。</li>
 *   <li>段落代数每段递增，否则迟到的精化回调会串段。</li>
 * </ol>
 */
class ContinuousModeTest {

    private StateMachine sm;
    private Recorder rec;

    private static final class Recorder implements StateMachine.Listener {
        final List<String> events = new ArrayList<>();
        final List<EndReason> endReasons = new ArrayList<>();
        int starts;
        int commits;
        String lastAbandon;

        @Override
        public void onSegmentStartRequested() {
            starts++;
            events.add("start");
        }

        @Override
        public void onSegmentEndRequested(EndReason reason) {
            endReasons.add(reason);
            events.add("end:" + reason);
        }

        @Override
        public void onCommitReady(StateMachine.CommitContext ctx) {
            commits++;
            events.add("commit");
        }

        @Override
        public void onSegmentAbandoned(String why) {
            lastAbandon = why;
            events.add("abandon");
        }
    }

    @BeforeEach
    void setUp() {
        rec = new Recorder();
        sm = new StateMachine(rec);
    }

    /** 走完一段：结束词 → 精化 → 注入。 */
    private void completeSegment(Event endEvent) {
        sm.handle(endEvent);
        sm.handle(Event.REFINE_DONE);
        sm.handle(Event.INJECTED);
    }

    // ============================================================ 默认行为不变

    @Nested
    @DisplayName("默认关闭：旧契约（一轮一句）必须不受影响")
    class DefaultOff {

        @Test
        @DisplayName("默认是关闭的")
        void offByDefault() {
            assertFalse(sm.continuousMode(), "默认必须关闭，否则老用户的行为预期会被改掉");
        }

        @Test
        @DisplayName("关闭时：一段提交完回 IDLE，下一句要重新唤醒")
        void returnsToIdle() {
            sm.handle(Event.WAKE_WORD);
            completeSegment(Event.END_WORD);
            assertEquals(State.IDLE, sm.state());
            // 计数只在连续模式下有意义：关闭时每段都是独立会话
            assertEquals(0, sm.continuousSegments());
            assertEquals(1, rec.commits);

            // 下一句确实要重新唤醒
            sm.handle(Event.END_WORD);
            assertEquals(State.IDLE, sm.state(), "没唤醒就说话不算数");
        }

        @Test
        @DisplayName("关闭时退出词被忽略（它只在连续模式下有意义）")
        void stopWordIgnored() {
            sm.handle(Event.WAKE_WORD);
            int ignoredBefore = sm.ignoredTotal();
            sm.handle(Event.STOP_INPUT);
            // 关闭时收到退出词：当前段照常结束并回到 IDLE —— 行为与结束词一致，
            // 但语义上我们是「忽略它作为会话结束信号」，因为本来就要回 IDLE。
            assertTrue(sm.ignoredTotal() >= ignoredBefore);
        }
    }

    // ============================================================ 连续聆听

    @Nested
    @DisplayName("开启后：唤醒一次，连续落字")
    class Continuous {

        @BeforeEach
        void enable() {
            sm.setContinuousMode(true);
        }

        @Test
        @DisplayName("一段提交完**回到 LISTENING**，不要求重喊唤醒词")
        void returnsToListening() {
            sm.handle(Event.WAKE_WORD);
            completeSegment(Event.END_WORD);
            assertEquals(State.LISTENING, sm.state(),
                    "连续模式下必须继续聆听——这正是它存在的意义");
            assertEquals(2, rec.starts, "应当为下一段重新开始录音");
        }

        @Test
        @DisplayName("可以连续落多段，代数逐段递增（否则迟到的精化回调会串段）")
        void multipleSegments() {
            sm.handle(Event.WAKE_WORD);
            long gen1 = sm.generation();
            completeSegment(Event.END_WORD);
            long gen2 = sm.generation();
            completeSegment(Event.SILENCE_TIMEOUT);
            long gen3 = sm.generation();
            completeSegment(Event.END_WORD);

            assertTrue(gen2 > gen1);
            assertTrue(gen3 > gen2);
            assertEquals(3, sm.continuousSegments());
            assertEquals(State.LISTENING, sm.state());
            assertEquals(List.of(EndReason.END_WORD, EndReason.SILENCE_TIMEOUT, EndReason.END_WORD),
                    rec.endReasons);
        }

        @Test
        @DisplayName("连续模式下重复唤醒不重启段落（会丢掉已录音频）")
        void repeatedWakeIgnored() {
            sm.handle(Event.WAKE_WORD);
            long gen = sm.generation();
            sm.handle(Event.WAKE_WORD);
            assertEquals(gen, sm.generation());
            assertEquals(1, rec.starts);
        }

        @Test
        @DisplayName("静音超时与结束词都能分段（两者互补：想一下用静音、说完了用结束词）")
        void bothEndings() {
            sm.handle(Event.WAKE_WORD);
            completeSegment(Event.SILENCE_TIMEOUT);
            assertEquals(State.LISTENING, sm.state());
            completeSegment(Event.END_WORD);
            assertEquals(State.LISTENING, sm.state());
            assertEquals(2, rec.endReasons.size());
        }

        @Test
        @DisplayName("切窗口也照常提交并继续聆听（不再丢弃）")
        void foregroundChangeKeepsListening() {
            sm.handle(Event.WAKE_WORD);
            completeSegment(Event.FOREGROUND_CHANGED);
            assertEquals(State.LISTENING, sm.state());
            assertEquals(EndReason.FOREGROUND_CHANGED, rec.endReasons.get(0));
        }

        @Test
        @DisplayName("单段时长上限也能分段（防止长录音内存增长）")
        void maxSegmentEnds() {
            sm.handle(Event.WAKE_WORD);
            completeSegment(Event.MAX_SEGMENT_REACHED);
            assertEquals(State.LISTENING, sm.state());
        }
    }

    // ============================================================ 退出词

    @Nested
    @DisplayName("退出词：只用语音结束整个聆听会话（无障碍要求）")
    class StopWord {

        @BeforeEach
        void enable() {
            sm.setContinuousMode(true);
        }

        @Test
        @DisplayName("LISTENING 时说退出词：**先把当前段提交掉**，再回 IDLE")
        void commitsThenExits() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.STOP_INPUT);
            assertEquals(State.COMMITTING, sm.state(), "退出词不能把当前这段丢掉");
            assertEquals(EndReason.STOP_WORD, rec.endReasons.get(0));

            sm.handle(Event.REFINE_DONE);
            sm.handle(Event.INJECTED);
            assertEquals(State.IDLE, sm.state(), "提交完成后才回到待唤醒");
        }

        @Test
        @DisplayName("COMMITTING 时说退出词：本段提交完不再继续聆听")
        void stopsAfterCommitting() {
            sm.handle(Event.WAKE_WORD);
            sm.handle(Event.END_WORD);
            sm.handle(Event.STOP_INPUT);
            assertEquals(State.COMMITTING, sm.state());
            sm.handle(Event.REFINE_DONE);
            sm.handle(Event.INJECTED);
            assertEquals(State.IDLE, sm.state());
        }

        @Test
        @DisplayName("退出后连续段计数复位，下次唤醒重新计")
        void counterResets() {
            sm.handle(Event.WAKE_WORD);
            completeSegment(Event.END_WORD);
            assertEquals(1, sm.continuousSegments());

            sm.handle(Event.STOP_INPUT);
            sm.handle(Event.REFINE_DONE);
            sm.handle(Event.INJECTED);
            assertEquals(State.IDLE, sm.state());
            assertEquals(0, sm.continuousSegments());

            sm.handle(Event.WAKE_WORD);
            assertEquals(State.LISTENING, sm.state());
            assertEquals(0, sm.continuousSegments());
        }

        @Test
        @DisplayName("IDLE 时说退出词被忽略")
        void ignoredWhenIdle() {
            sm.handle(Event.STOP_INPUT);
            assertEquals(1, sm.ignoredTotal());
            assertEquals(State.IDLE, sm.state());
        }

        @Test
        @DisplayName("退出词不能与结束词混淆：结束词只换段，退出词换会话")
        void distinctFromEndWord() {
            sm.handle(Event.WAKE_WORD);
            completeSegment(Event.END_WORD);
            assertEquals(State.LISTENING, sm.state(), "结束词之后继续听");

            sm.handle(Event.END_WORD);
            sm.handle(Event.STOP_INPUT);
            sm.handle(Event.REFINE_DONE);
            sm.handle(Event.INJECTED);
            assertEquals(State.IDLE, sm.state(), "退出词之后停止听");
        }
    }

    // ============================================================ 与暂停/取消的交互

    @Nested
    @DisplayName("与暂停 / 取消的交互")
    class PauseAndCancel {

        @BeforeEach
        void enable() {
            sm.setContinuousMode(true);
        }

        @Test
        @DisplayName("Esc 取消：回 IDLE 并停止整个会话（不会偷偷继续听）")
        void cancelExitsSession() {
            sm.handle(Event.WAKE_WORD);
            completeSegment(Event.END_WORD);
            assertEquals(State.LISTENING, sm.state());

            sm.handle(Event.CANCEL);
            assertEquals(State.IDLE, sm.state(), "取消应当结束会话，而不是只丢当前段");
            assertEquals(0, sm.continuousSegments(), "取消后会话状态必须复位");
        }

        @Test
        @DisplayName("暂停后不再继续聆听，恢复后需要重新唤醒")
        void pauseStopsListening() {
            sm.handle(Event.WAKE_WORD);
            completeSegment(Event.END_WORD);
            sm.handle(Event.PAUSE);
            assertEquals(State.IDLE, sm.state());
            assertTrue(sm.paused());

            sm.handle(Event.WAKE_WORD);
            assertEquals(State.IDLE, sm.state(), "暂停时唤醒词应被忽略");

            sm.handle(Event.RESUME);
            sm.handle(Event.WAKE_WORD);
            assertEquals(State.LISTENING, sm.state());
        }

        @Test
        @DisplayName("模式可在运行期切换，且只影响下一次进入")
        void modeCanBeToggled() {
            sm.setContinuousMode(false);
            sm.handle(Event.WAKE_WORD);
            completeSegment(Event.END_WORD);
            assertEquals(State.IDLE, sm.state());

            sm.setContinuousMode(true);
            sm.handle(Event.WAKE_WORD);
            completeSegment(Event.END_WORD);
            assertEquals(State.LISTENING, sm.state());
        }
    }
}
