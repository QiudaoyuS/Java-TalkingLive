package com.talkinglive.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 悬浮球「点击 vs 拖动」的判定（{@code PENDING-ISSUES} P2.1 的回归测试）。
 *
 * <p>背景：这个判定曾经用**组件坐标**比较按下点与松开点，而贴边收起状态下按下时
 * 会先把球滑出来 —— 窗口在静止的光标下平移约 48px，于是同一个屏幕位置对应的组件坐标
 * 变了 ≥48px，判据 {@code distance < 5} 对**任何真实点击**恒为 false。
 * 结果是：点一下球什么都没发生，球弹出→缩回→再弹出。
 *
 * <p>这条缺陷特别值得有测试：球是**唯一入口**，而"球被收起"正是最需要"点一下就说话"
 * 的状态；而它的表现（"点了没反应"）在日志里几乎不留痕迹 —— 只有状态机收到 0 次 TOGGLE。
 */
class FloatingBallClickTest {

    @Nested
    @DisplayName("点击（按下与松开在同一屏幕位置）")
    class Clicks {

        @Test
        @DisplayName("同一屏幕位置算点击 —— 窗口在期间平移过也不影响")
        void sameScreenPointIsClick() {
            Point p = new Point(1700, 500);
            // 关键场景：贴边收起时按下，球在静止光标下滑出 48px。
            // 屏幕坐标不变 → 仍然是点击（组件坐标在这个场景下会差 48px，正是旧实现失败的地方）
            assertTrue(FloatingBall.isClick(p, new Point(1700, 500), false));
        }

        @Test
        @DisplayName("手抖 2px 仍算点击，4px 也算（阈值 5px 以内）")
        void smallJitterIsStillClick() {
            assertTrue(FloatingBall.isClick(new Point(100, 100), new Point(102, 101), false));
            assertTrue(FloatingBall.isClick(new Point(100, 100), new Point(103, 102), false));
        }
    }

    @Nested
    @DisplayName("拖动（不该被当成点击）")
    class Drags {

        @Test
        @DisplayName("屏幕位移超过阈值 → 不是点击")
        void movedTooFarIsNotClick() {
            assertFalse(FloatingBall.isClick(new Point(100, 100), new Point(110, 100), false));
            assertFalse(FloatingBall.isClick(new Point(100, 100), new Point(100, 112), false));
        }

        @Test
        @DisplayName("收到过 mouseDragged 就一律不是点击（哪怕最后回到原点）")
        void draggedFlagWins() {
            Point p = new Point(100, 100);
            assertFalse(FloatingBall.isClick(p, new Point(100, 100), true),
                    "拖出去又拖回来仍算拖动，否则会误触一次听写");
        }
    }

    @Nested
    @DisplayName("边界：拿不到坐标时不抛异常，也绝不误判成点击")
    class Edges {

        @Test
        @DisplayName("null 坐标 → 不是点击（旧实现会在这里 NPE）")
        void nullsAreSafe() {
            assertFalse(FloatingBall.isClick(null, new Point(1, 1), false));
            assertFalse(FloatingBall.isClick(new Point(1, 1), null, false));
            assertFalse(FloatingBall.isClick(null, null, false));
        }
    }
}
