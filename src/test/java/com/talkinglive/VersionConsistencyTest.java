package com.talkinglive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 三处版本号必须一致 —— {@code AGENTS.md}「发布版本跟随提交序号」的守卫。
 *
 * <p>背景：版本号散在三处（README 顶部的「当前版本」、{@code pom.xml} 的 {@code <version>}、
 * jpackage 的 {@code <appVersion>}）。少改一处就会互相说反，而**这个坑真的踩过**：
 * {@code <appVersion>} 曾长期停在骨架时代的 {@code 0.1.0}，于是
 * {@code mvnw -Pdist package} 产出的程序自称另一个版本，谁也没发现。
 *
 * <p>为什么值得一条测试：三处不一致时**没有任何东西会出声** —— 构建照过、测试照绿、
 * 程序照跑，只有下载安装包的人会看到错误的版本号。
 *
 * <p>这里只断言「三处彼此一致 + 格式是 N.n.m」，**刻意不去比对 HEAD 首行的
 * {@code [N.n.m]}**：提交之前那个号还不存在（先写文件、再提交），比 HEAD 会让
 * 「改完还没提交」的正常状态下必红。比对 HEAD 的检查放在
 * {@code tools\set-version.ps1 -Check}（CI 里跑，那时提交已经存在）。
 */
class VersionConsistencyTest {

    private static final Path README = Path.of("README.md");
    private static final Path POM = Path.of("pom.xml");
    private static final Pattern README_VERSION =
            Pattern.compile("(?m)^当前版本 \\*\\*(\\d+\\.\\d+\\.\\d+)\\*\\*");
    private static final Pattern POM_VERSION = Pattern.compile(
            "(?s)<artifactId>talkinglive</artifactId>\\s*<version>(\\d+\\.\\d+\\.\\d+)</version>");
    private static final Pattern APP_VERSION =
            Pattern.compile("<appVersion>(\\d+\\.\\d+\\.\\d+)</appVersion>");

    @Test
    @DisplayName("README / pom <version> / pom <appVersion> 三处版本号必须一致")
    void threePlacesAgree() throws IOException {
        String readme = firstGroup(README_VERSION, Files.readString(README, StandardCharsets.UTF_8),
                "README.md 顶部的「当前版本 **N.n.m**」那一行");
        String pomText = Files.readString(POM, StandardCharsets.UTF_8);
        String pomVersion = firstGroup(POM_VERSION, pomText, "pom.xml 的 <version>");
        String appVersion = firstGroup(APP_VERSION, pomText, "pom.xml 里 jpackage 的 <appVersion>");

        assertEquals(pomVersion, readme,
                "README 的版本与 pom 的 <version> 不一致 —— 定版/改号时三处必须一起改（AGENTS.md）");
        assertEquals(pomVersion, appVersion,
                "<appVersion> 与 <version> 不一致：它是最容易漏的那一处，"
                        + "不改的话 mvnw -Pdist package 产出的程序会自称另一个版本");
        assertTrue(pomVersion.matches("\\d+\\.\\d+\\.\\d+"), "版本号格式应为 N.n.m：" + pomVersion);
    }

    private static String firstGroup(Pattern p, String text, String what) {
        Matcher m = p.matcher(text);
        assertTrue(m.find(), "找不到 " + what + " —— 它被改名/改格式了？版本守卫会因此失效");
        return m.group(1);
    }
}
