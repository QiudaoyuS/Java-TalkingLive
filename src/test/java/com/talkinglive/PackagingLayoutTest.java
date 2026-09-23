package com.talkinglive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * jpackage 打包链路的**目录形状**必须被测试守住。
 *
 * <p>背景：{@code mvnw -Pdist package} 曾经一直是坏的 —— jpackage 的 input 被写成
 * {@code target/lib}，而主 jar 生成在 {@code target/talkinglive.jar}，于是 jpackage 报
 * 「找不到 talkinglive.jar」。这个故障的坏处是它**只在打包时**出现：日常的
 * {@code mvnw test} / {@code mvnw package} 全绿，谁也不去跑 {@code -Pdist}，
 * 于是它一直坏着（{@code docs/IMPLEMENTATION-STATUS.md} 的 M5 长期写着"未产出安装包验证"）。
 *
 * <p>为什么值得一条测试：jpackage 对 input 的要求是**反直觉**的 —— 它把 input 里的内容
 * **平铺**进 app 目录，并要求 mainJar **就在 input 里**；而主 jar 的清单写的是
 * {@code Class-Path: lib/xxx.jar}，所以 app 目录必须是「jar 在根 + 依赖在 lib/」这个形状。
 * 于是"随便指一个目录当 input"看起来完全合理，做出来却是坏的。这里把三件事一起钉住：
 * input 是那个专用暂存目录、主 jar 会被暂存进去、依赖会被暂存到它下面的 {@code lib/}。
 *
 * <p>顺带守一个 Maven 的**执行顺序**陷阱：暂存与 jpackage 都绑在 {@code package} 阶段，
 * 同一阶段内按声明顺序执行，所以暂存那两个 execution 必须写在 jpackage 之前 ——
 * 顺序反了会在打包时报"找不到 jar"，而原因藏在 pom 的行序里，很难一眼看出来。
 *
 * <p>它读的是 {@code pom.xml} 的**文本**（与 {@code ArchitectureTest} 扫源码同一套路数）：
 * 这里要守的是"配置写成什么形状"，不是运行期行为，而打包本身要 jpackage、跑不进单测。
 */
class PackagingLayoutTest {

    private static final Path POM = Path.of("pom.xml");
    private static final String STAGING = "${project.build.directory}/app-input";

    private static String pom() throws IOException {
        return Files.readString(POM, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("jpackage 的 input 是专用暂存目录，不是 target/lib（那正是原故障）")
    void inputIsTheStagingDirectory() throws IOException {
        String pom = pom();
        assertTrue(pom.contains("<input>" + STAGING + "</input>"),
                "jpackage 的 input 应当是 target/app-input（形状：jar 在根 + 依赖在 lib/）");
        assertFalse(pom.contains("<input>${project.build.directory}/lib</input>"),
                "把 target/lib 当 input 就是历史上的原故障：jpackage 要求 mainJar 就在 input 里，"
                        + "而主 jar 生成在 target/ 下 —— 这么写会在 jpackage 那一步报"
                        + "「找不到 talkinglive.jar」");
    }

    @Test
    @DisplayName("暂存目录里有主 jar，且名字与 mainJar 一致")
    void mainJarIsStagedUnderTheNameJpackageLooksFor() throws IOException {
        String pom = pom();
        assertTrue(pom.contains("<mainJar>${project.build.finalName}.jar</mainJar>"),
                "mainJar 必须就是 jar 插件产出的那个名字");
        assertTrue(pom.contains("<include>${project.build.finalName}.jar</include>"),
                "暂存步骤必须把主 jar 复制进 input 目录（两处用的都是 finalName，"
                        + "写死成别的名字就会让 jpackage 去找一个不存在的 jar）");
    }

    @Test
    @DisplayName("依赖被暂存到 input 下的 lib/，与 jar 清单的 Class-Path 前缀一致")
    void dependenciesAreStagedIntoLib() throws IOException {
        String pom = pom();
        assertTrue(pom.contains("<outputDirectory>" + STAGING + "/lib</outputDirectory>"),
                "依赖必须落到 app-input/lib —— 在 app 目录里它们就在主 jar 旁边，不能平铺到根上");
        assertTrue(pom.contains("<classpathPrefix>lib/</classpathPrefix>"),
                "jar 清单的 Class-Path 前缀是 lib/，暂存出来的形状必须与它一致");
        assertTrue(pom.contains("<directory>${project.build.directory}/lib</directory>"),
                "暂存的来源是 dependency 插件产出的 target/lib（即开发期 java -jar 用的那一份）");
    }

    @Test
    @DisplayName("暂存步骤写在 jpackage 之前（同一 phase 内按声明顺序执行）")
    void stagingIsDeclaredBeforeJpackage() throws IOException {
        String pom = pom();
        int distProfile = pom.indexOf("<id>dist</id>");
        assertTrue(distProfile > 0, "找不到 dist profile —— 打包入口就是它");
        String profile = pom.substring(distProfile);

        int stageJar = profile.indexOf("stage-main-jar");
        int stageLib = profile.indexOf("stage-lib");
        int jpackage = profile.indexOf("<id>jpackage</id>");
        assertTrue(stageJar > 0 && stageLib > 0,
                "dist profile 里应当有 stage-main-jar 与 stage-lib 两步暂存");
        assertTrue(jpackage > 0, "dist profile 里应当有 jpackage");
        assertTrue(stageJar < jpackage && stageLib < jpackage,
                "暂存必须声明在 jpackage 之前：同一个 phase（package）内按声明顺序执行，"
                        + "顺序反了 jpackage 会在 input 里找不到 jar");
    }
}
