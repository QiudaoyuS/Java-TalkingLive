package com.talkinglive.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 悬浮球拖动落点的回归测试。
 *
 * <p>背景（安装包实测时发现的真缺陷）：旧实现是
 *
 * <pre>{@code setLocation(p.x - pressPoint.x, p.y - pressPoint.y);}</pre>
 *
 * <p>它把「鼠标相对按下点的位移」（几十像素）当成了「窗口的绝对坐标」交给
 * {@code setLocation}，于是鼠标一拖，球就被拽到屏幕左上角，松手时 {@code getX()≈0}
 * 又被判成"贴着左边缘"而收进去。用户看到的是"拖一下球就回到原位，还跟着鼠标走一点"。
 *
 * <p>这里断言的是<strong>定性</strong>关系，不是像素巧合：没收到位移就必须原地不动，
 * 位移多少窗口就走多少，且第二次拖动以第二次按下时的位置为锚点。
 */
class FloatingBallDragTest {

    @Nested
    @DisplayName("正常拖动：新位置 = 按下时的位置 + 鼠标位移")
    class FollowsMouse {

        @Test
        @DisplayName("按住右侧的球往左拖 460px → 球跟着走 460px，球没飞到左上角")
        void movesByTheSameDeltaAsTheMouse() {
            Point windowAtPress = new Point(1850, 500);
            Point press = new Point(1860, 510);
            Point now = new Point(1400, 500);

            Point target = FloatingBall.draggedLocation(windowAtPress, press, now);

            // 鼠标往左走 460、往上走 10
            assertEquals(new Point(1390, 490), target);
            // 抓取点始终在鼠标底下：鼠标 x - 窗口 x == 按下时的那 10px 偏移
            assertEquals(10, now.x - target.x);
            assertEquals(10, now.y - target.y);
            // 旧实现会算出 (-460, -10)，也就是"屏幕左上角"——这一条专门钉住它
            assertNotEquals(new Point(now.x - press.x, now.y - press.y), target);
        }

        @Test
        @DisplayName("没有位移 → 位置一点不变（拖动起手那一帧不能抖）")
        void zeroDeltaKeepsPosition() {
            Point windowAtPress = new Point(1850, 500);
            Point press = new Point(1860, 510);

            assertEquals(windowAtPress, FloatingBall.draggedLocation(windowAtPress, press, press));
        }

        @Test
        @DisplayName("第二次拖动以第二次按下时的位置为锚点，与第一次无关")
        void secondDragUsesItsOwnAnchor() {
            Point press1 = new Point(1860, 510);
            Point window1 = new Point(1850, 500);
            Point release1 = new Point(1400, 500);
            Point afterFirst = FloatingBall.draggedLocation(window1, press1, release1);
            assertEquals(new Point(1390, 490), afterFirst);

            // 第二次在球的新位置按下（抓的是同一个 10px 偏移），再往右下拖 100/60
            Point press2 = new Point(afterFirst.x + 10, afterFirst.y + 10);
            Point now2 = new Point(press2.x + 100, press2.y + 60);

            Point target2 = FloatingBall.draggedLocation(afterFirst, press2, now2);

            assertEquals(new Point(afterFirst.x + 100, afterFirst.y + 60), target2);
            // 关键：第二次拖动绝不回到第一次按下时的位置
            assertNotEquals(new Point(window1.x + 100, window1.y + 60), target2);
        }
    }

    @Nested
    @DisplayName("边界：缺参数时不移动、不抛异常")
    class Edges {

        @Test
        @DisplayName("任一参数为 null → 返回 null（调用方据此放弃这一帧）")
        void nullsReturnNull() {
            Point p = new Point(1, 1);
            assertNull(FloatingBall.draggedLocation(null, p, p));
            assertNull(FloatingBall.draggedLocation(p, null, p));
            assertNull(FloatingBall.draggedLocation(p, p, null));
            assertNull(FloatingBall.draggedLocation(null, null, null));
        }
    }
}
