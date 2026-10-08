package com.talkinglive;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 许可与第三方声明必须**跟着依赖一起走**。
 *
 * <p>背景：这个项目要分发给陌生人（GitHub 下载 + app image 里带着
 * {@code app/lib/*.jar}），所以第三方组件的许可必须被声明 —— 这是许可本身的要求，
 * 不是"文档做得好不好"的问题。
 *
 * <p>为什么值得一条测试：声明文件最容易**悄悄过期** —— 升级一个依赖，没人记得回来改表格，
 * 于是发布出去的交付物里写着错误的版本与许可。本项目在别处（`<appVersion>` 停在 0.1.0、
 * 文档里的测试数量）已经为"手写常量必然过期"栽过好几次，这里用扫描把它按住。
 */
class ThirdPartyNoticesTest {

    private static final Path POM = Path.of("pom.xml");
    private static final Path NOTICES = Path.of("THIRD-PARTY-NOTICES.md");
    private static final Path LICENSE = Path.of("LICENSE");

    @Test
    @DisplayName("pom 里每个依赖的版本号都必须出现在 THIRD-PARTY-NOTICES.md 里")
    void everyDependencyVersionIsDeclared() throws IOException {
        String pom = read(POM);
        String notices = read(NOTICES);
        Map<String, String> props = properties(pom);

        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (String block : blocks(section(pom, "<dependencies>", "</dependencies>"), "<dependency>", "</dependency>")) {
            String artifact = tag(block, "artifactId");
            String version = tag(block, "version");
            if (artifact == null || version == null) {
                continue;
            }
            String resolved = version.startsWith("${")
                    ? props.getOrDefault(version.substring(2, version.length() - 1), version)
                    : version;
            checked++;
            if (!notices.contains(resolved)) {
                missing.add(artifact + " " + resolved);
            }
        }

        assertTrue(checked >= 5,
                "只解析到 " + checked + " 个依赖 —— pom 结构或本测试的解析逻辑变了，先修它再谈声明");
        assertTrue(missing.isEmpty(),
                "这些依赖的版本没写进 THIRD-PARTY-NOTICES.md：" + missing
                        + "（升级依赖时请同步那张表，否则交付物里的声明是错的）");
    }

    @Test
    @DisplayName("LICENSE 必须是 Apache-2.0 的完整正文，不是节选或占位")
    void licenseIsComplete() throws IOException {
        String text = read(LICENSE);
        for (String must : List.of(
                "Apache License",
                "Version 2.0, January 2004",
                "TERMS AND CONDITIONS FOR USE",
                "9. Accepting Warranty",
                "APPENDIX: How to apply")) {
            assertTrue(text.contains(must), "LICENSE 里缺少这一段：" + must);
        }
        assertTrue(text.length() > 9000,
                "LICENSE 只有 " + text.length() + " 字符，像是节选 —— 完整正文约 11KB");
    }

    // ------------------------------------------------------------ 解析小工具

    private static String read(Path p) throws IOException {
        assertTrue(Files.exists(p), p + " 不存在 —— 它是交付物的一部分，不能删");
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 取 markers 之间的第一段（找不到就返回空串）。 */
    private static String section(String text, String from, String to) {
        int a = text.indexOf(from);
        if (a < 0) {
            return "";
        }
        int b = text.indexOf(to, a + from.length());
        return b < 0 ? "" : text.substring(a + from.length(), b);
    }

    private static List<String> blocks(String text, String open, String close) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (true) {
            int a = text.indexOf(open, i);
            if (a < 0) {
                return out;
            }
            int b = text.indexOf(close, a + open.length());
            if (b < 0) {
                return out;
            }
            out.add(text.substring(a + open.length(), b));
            i = b + close.length();
        }
    }

    /** pom 的 &lt;properties&gt;：名字 -&gt; 值。 */
    private static Map<String, String> properties(String pom) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher m = Pattern.compile("<([a-zA-Z0-9.]+)>([^<]+)</\\1>")
                .matcher(section(pom, "<properties>", "</properties>"));
        while (m.find()) {
            map.put(m.group(1), m.group(2).trim());
        }
        return map;
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">([^<]+)</" + name + ">").matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }
}
