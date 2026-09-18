package com.talkinglive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code --doctor} 与 {@code --self-check} 的**分工**必须被测试守住。
 *
 * <p>背景（{@code docs/DECISIONS.md} D5）：{@code --doctor} 的承诺是「环境自检后退出」，
 * 但它此前调的是完整自检 —— 只要桌面可用就会**造一颗悬浮球并用 {@code Robot} 真的移动鼠标**。
 * 文档与注释都写着「不碰鼠标」，与真实行为相反，而且用户可能正在用鼠标做别的事。
 * 于是给 {@code SelfTest.run} 加了 {@code includeUi} 开关，由调用方决定。
 *
 * <p>为什么值得一条测试：这个开关失效的方式是**静默的** —— 一旦有人把
 * {@code --doctor} 又改回完整自检，界面上看不出异常，只有用户在鼠标被抢时才会察觉。
 *
 * <p>注意这里**不**断言「整份报告 0 失败」：{@code SelfTest} 有一条 pipeline 项检查
 * 「全局未捕获异常处理器是否已安装」，而那是 {@code App.main} 的职责 —— 在纯 JVM
 * （例如本测试）里跑它本来就会失败。这条测试只关心 UI 段的门有没有关好。
 */
class SelfTestUiGateTest {

    private static List<SelfTest.Item> uiItems(SelfTest.Result r) {
        return r.items().stream().filter(i -> "UI".equals(i.category())).toList();
    }

    @Test
    @DisplayName("includeUi=false：UI 段只剩一条「按参数跳过」，且如实标注（跳过 ≠ 通过）")
    void uiSectionIsSkippedAndReported() {
        SelfTest.Result r = SelfTest.run(null, false);
        List<SelfTest.Item> ui = uiItems(r);

        assertEquals(1, ui.size(),
                "关掉 UI 段后，「UI」分类下应当只有那一条跳过说明，实际：" + describe(ui));
        assertTrue(ui.get(0).name().contains("按参数跳过"),
                "那一条必须明说「按参数跳过」，否则读者会以为 UI 那几项也验过了：" + ui.get(0).line());
        assertTrue(ui.get(0).ok(), "「已按参数跳过」是如实交代，不该被算成失败项");
        assertTrue(r.report().contains("按参数跳过"), "报告里必须能读到这句话");
    }

    @Test
    @DisplayName("includeUi=false：核心逻辑与管线仍然照跑（跳过的是 UI，不是整条自检）")
    void coreSectionsStillRun() {
        SelfTest.Result r = SelfTest.run(null, false);
        assertTrue(r.items().size() > 5,
                "关掉 UI 段之后仍应有多项结果（实际 " + r.items().size() + " 项）—— "
                        + "若接近 0，说明开关把整条自检都关掉了");
        assertTrue(r.passed() > 0, "核心逻辑与管线段应当照常执行并产出通过项");
        for (SelfTest.Item i : r.items()) {
            if (!i.ok()) {
                assertNotEquals("UI", i.category(),
                        "失败项不该出现在「UI」分类里（那一段已经被明确跳过）：" + i.line());
            }
        }
    }

    private static String describe(List<SelfTest.Item> items) {
        return items.stream().map(SelfTest.Item::line).toList().toString();
    }
}
