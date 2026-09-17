package com.talkinglive.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 全局 Esc 监听测试 —— 这一层守的是 {@code DESIGN.md} §2.2/§2.3 那句
 * 「任何时刻按 Esc 可取消整段」。
 *
 * <p><b>为什么这些用例必须存在：</b>在此之前，产品里**没有任何代码产生
 * {@code StateMachine.Event.CANCEL}**。自检里那条「Esc 取消后一个字都没注入」
 * 是直接给状态机发事件的 —— 它证明的是状态机能处理 CANCEL，而不是"按 Esc 真的会取消"。
 * 这两件事的差别就是这个类存在的原因，所以它必须有独立测试，
 * 而且测的是**按键状态的解释**（按住只算一次、短点按不能漏、松开再按要再算一次），
 * 这部分逻辑不需要真实桌面。
 *
 * <p>靠 {@link EscapeWatcher.KeyState} 注入人造按键状态，因此可以精确构造
 * "两次轮询之间的点按"这种真实场景，而不必真的去按键盘。
 */
class EscapeWatcherTest {

    private static final int VK_ESCAPE = Win32.VK_ESCAPE;

    /** GetAsyncKeyState 的位含义（与 EscapeWatcher 内部一致）。 */
    private static final int DOWN = 0x8000;
    private static final int PRESSED_SINCE_LAST_POLL = 0x0001;

    /** 假的按键状态源：按顺序吐出预设的状态，用完之后一律"没按"。 */
    private static final class FakeKeys implements EscapeWatcher.KeyState {
        private final short[] script;
        private int at;
        int queries;

        FakeKeys(short... script) {
            this.script = script;
        }

        @Override
        public short poll(int vk) {
            queries++;
            assertEquals(VK_ESCAPE, vk, "监听的必须是 Esc");
            return at < script.length ? script[at++] : 0;
        }
    }

    @Nested
    @DisplayName("按下的解释")
    class Edge {

        @Test
        @DisplayName("一次按下只触发一次（按住不放不会连续取消）")
        void holdingFiresOnce() {
            AtomicInteger fired = new AtomicInteger();
            FakeKeys keys = new FakeKeys((short) DOWN, (short) DOWN, (short) DOWN);
            EscapeWatcher w = new EscapeWatcher(keys, fired::incrementAndGet);

            assertTrue(w.poll(), "第一次应当触发");
            assertFalse(w.poll(), "按住不放的第二次不该触发");
            assertFalse(w.poll(), "第三次也不该触发");
            assertEquals(1, fired.get());
        }

        @Test
        @DisplayName("松开后再按要再触发一次")
        void pressReleasePressFiresTwice() {
            AtomicInteger fired = new AtomicInteger();
            FakeKeys keys = new FakeKeys((short) DOWN, (short) 0, (short) DOWN);
            EscapeWatcher w = new EscapeWatcher(keys, fired::incrementAndGet);

            w.poll();   // 按下
            w.poll();   // 松开
            w.poll();   // 再按下
            assertEquals(2, fired.get());
        }

        @Test
        @DisplayName("两次轮询之间的短点按也要触发（只判「当前按下」会整段漏掉）")
        void tapBetweenPollsIsCaught() {
            // 低位标志 = 「自上次查询以来被按过」，而当前并不处于按下状态：
            // 这正是"用户很快敲了一下 Esc"在 50ms 轮询里看到的样子。
            AtomicInteger fired = new AtomicInteger();
            FakeKeys keys = new FakeKeys((short) PRESSED_SINCE_LAST_POLL);
            EscapeWatcher w = new EscapeWatcher(keys, fired::incrementAndGet);

            assertTrue(w.poll(), "短点按必须被捞回来，否则用户会觉得 Esc 时灵时不灵");
            assertEquals(1, fired.get());
        }

        @Test
        @DisplayName("短点按不会被算两次（低位标志只算一次新按下）")
        void tapIsNotCountedTwice() {
            AtomicInteger fired = new AtomicInteger();
            FakeKeys keys = new FakeKeys((short) PRESSED_SINCE_LAST_POLL, (short) 0);
            EscapeWatcher w = new EscapeWatcher(keys, fired::incrementAndGet);

            w.poll();
            w.poll();
            assertEquals(1, fired.get());
        }

        @Test
        @DisplayName("一直没按就不触发，但每次轮询都真的去问了键盘")
        void idleDoesNotFire() {
            AtomicInteger fired = new AtomicInteger();
            FakeKeys keys = new FakeKeys((short) 0, (short) 0, (short) 0);
            EscapeWatcher w = new EscapeWatcher(keys, fired::incrementAndGet);

            w.poll();
            w.poll();
            w.poll();
            assertEquals(0, fired.get());
            assertEquals(3, keys.queries, "没在轮询就等于没接线");
        }
    }

    @Nested
    @DisplayName("健壮性")
    class Robustness {

        @Test
        @DisplayName("回调抛异常不能把监听打断（一次失败之后仍要能继续触发）")
        void callbackFailureIsContained() {
            AtomicInteger calls = new AtomicInteger();
            FakeKeys keys = new FakeKeys((short) DOWN, (short) 0, (short) DOWN);
            EscapeWatcher w = new EscapeWatcher(keys, () -> {
                calls.incrementAndGet();
                throw new IllegalStateException("模拟回调内部出错");
            });

            w.poll();
            w.poll();
            w.poll();
            assertEquals(2, calls.get(), "第一次抛异常后，第二次按下仍应被处理");
        }

        @Test
        @DisplayName("close 之后不再触发（退出过程中不该再来新事件）")
        void closedStopsFiring() {
            AtomicInteger fired = new AtomicInteger();
            FakeKeys keys = new FakeKeys((short) DOWN, (short) 0, (short) DOWN);
            EscapeWatcher w = new EscapeWatcher(keys, fired::incrementAndGet);

            w.poll();
            w.close();
            w.poll();
            w.poll();
            assertEquals(1, fired.get());
        }

        @Test
        @DisplayName("没 start 时 running() 为假，start 之后为真，close 之后又为假")
        void runningReflectsLifecycle() {
            EscapeWatcher w = new EscapeWatcher(vk -> 0, () -> { });
            assertFalse(w.running());
            w.start();
            assertTrue(w.running(), "自检靠这一条确认 Esc 真的接线了");
            w.close();
            assertFalse(w.running());
        }

        @Test
        @DisplayName("start 重复调用是安全的（不会多起一个轮询线程）")
        void doubleStartIsSafe() {
            EscapeWatcher w = new EscapeWatcher(vk -> 0, () -> { });
            w.start();
            w.start();
            assertTrue(w.running());
            w.close();
        }

        @Test
        @DisplayName("fires() 记录累计触发次数（诊断与自检用）")
        void countsFires() {
            FakeKeys keys = new FakeKeys((short) DOWN, (short) 0, (short) DOWN);
            EscapeWatcher w = new EscapeWatcher(keys, () -> { });
            w.poll();
            w.poll();
            w.poll();
            assertEquals(2, w.fires());
        }
    }
}
