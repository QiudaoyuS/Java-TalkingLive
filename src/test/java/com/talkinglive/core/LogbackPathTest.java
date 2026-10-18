package com.talkinglive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 日志必须写在 {@link AppPaths#home()} 底下 —— 这条曾经被写死的路径悄悄破坏过三次。
 *
 * <p>实测到的三个后果（安装包真装一次才暴露）：
 * <ol>
 *   <li>程序自己报告 {@code home=C:\...\TalkingLiveData}，日志却在
 *       {@code C:\...\TalkingLive\logs\talkinglive.log} —— 日志与数据分居两处，
 *       数据目录下的 {@code logs} 一直是**空目录**；</li>
 *   <li>{@code %LOCALAPPDATA%\TalkingLive} 是 jpackage 每用户安装的默认安装目录，
 *       而卸载会递归删除该目录 → 日志陪葬；</li>
 *   <li>跑一次 {@code mvnw test} 就往用户真实日志里写（实测 90KB → 1.7MB）。</li>
 * </ol>
 *
 * <p>所以这里同时钉住两件事：{@code logback.xml} 里**不许再出现写死的 Windows 路径**，
 * 且真正决定路径的那个 definer 必须跟着 {@link AppPaths} 走（含 {@code -Dtalkinglive.home}
 * 的重定向）。
 */
class LogbackPathTest {

    private static final Path LOGBACK_XML = Path.of("src/main/resources/logback.xml");

    private static String logback() throws IOException {
        return Files.readString(LOGBACK_XML, StandardCharsets.UTF_8);
    }

    /**
     * 去掉 XML 注释后的内容。
     *
     * <p>必须去注释：注释里**刻意保留**了旧写法（{@code ${TALKINGLIVE_HOME:-...}}）作为反面记录，
     * 而这条断言要守的是"配置实际怎么取值"，不是"文档里提没提过旧路径"。
     */
    private static String logbackConfigOnly() throws IOException {
        return logback().replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    @DisplayName("logback.xml 的配置里没有写死的 AppData 路径")
    void noHardCodedWindowsPath() throws IOException {
        String xml = logbackConfigOnly();
        assertFalse(xml.contains("AppData/Local/TalkingLive"),
                "logback.xml 里又出现写死的 %LOCALAPPDATA%\\TalkingLive 了 —— "
                        + "那正是「日志不在数据目录、还会被卸载删掉」的根因，应当回到 AppPaths 来决定");
        assertFalse(xml.contains("TALKINGLIVE_HOME"),
                "TALKINGLIVE_HOME 从来没被赋值过，写了也只会取默认值（历史缺陷）");
        assertTrue(xml.contains("${talkinglive.home}/logs/talkinglive.log"),
                "日志路径应当由 <define name=\"talkinglive.home\"> 提供的根目录拼出来");
        assertTrue(xml.contains("com.talkinglive.core.LogHomePropertyDefiner"),
                "根目录必须来自 LogHomePropertyDefiner（它读 AppPaths.home()）");
    }

    @Test
    @DisplayName("definer 的取值跟着 AppPaths.home()（含 -Dtalkinglive.home 重定向）")
    void definerFollowsAppPaths(@TempDir Path tmp) {
        String saved = System.getProperty(AppPaths.HOME_PROPERTY);
        System.setProperty(AppPaths.HOME_PROPERTY, tmp.toString());
        try {
            String value = new LogHomePropertyDefiner().getPropertyValue();
            assertEquals(AppPaths.home().toString(), value);
            assertEquals(tmp.toAbsolutePath().normalize().toString(), value,
                    "重定向之后 definer 必须跟着走，否则测试又会往真实日志里写");
        } finally {
            if (saved == null) {
                System.clearProperty(AppPaths.HOME_PROPERTY);
            } else {
                System.setProperty(AppPaths.HOME_PROPERTY, saved);
            }
        }
    }

    @Test
    @DisplayName("日志最终落在数据根目录的 logs/ 下，而不是安装目录")
    void logFileLivesUnderDataHome(@TempDir Path tmp) {
        String saved = System.getProperty(AppPaths.HOME_PROPERTY);
        System.setProperty(AppPaths.HOME_PROPERTY, tmp.toString());
        try {
            Path expected = tmp.toAbsolutePath().normalize().resolve("logs").resolve("talkinglive.log");
            Path actual = Path.of(new LogHomePropertyDefiner().getPropertyValue())
                    .resolve("logs").resolve("talkinglive.log");
            assertEquals(expected, actual);
            assertTrue(actual.startsWith(tmp.toAbsolutePath().normalize()));
        } finally {
            if (saved == null) {
                System.clearProperty(AppPaths.HOME_PROPERTY);
            } else {
                System.setProperty(AppPaths.HOME_PROPERTY, saved);
            }
        }
    }
}
