package com.talkinglive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 安装向导的许可页：中文说明必须真的能显示出中文。
 *
 * <p>背景（实测出来的坑）：安装向导的界面文案是中文（jpackage 会传
 * {@code MsiInstallerStrings_zh_CN.wxl -cultures:zh-cn}），但**协议正文**是 Apache-2.0
 * 英文原文，用户看到的就是一大段英文。想加一段中文说明，第一次的做法是把
 * "中文说明 + Apache 全文"的**纯文本**交给 {@code --license-file} ——
 * jpackage 会自己把它转成 RTF，而它给非 ASCII 字符生成的转义是畸形的
 * （反斜杠 + 字母 u + 字母 d + 码点 + 问号，RTF 里 u 后面只允许数字和一个替代字符），
 * 实测在 RichEdit 里渲染成**一串问号**。也就是说：纯文本这条路**静默地把中文弄坏了**。
 *
 * <p>而 {@code .rtf} 文件是**原样复制**的（实测 config 目录里那份与输入逐字节相同），
 * 所以改由 {@code tools\make-license-rtf.ps1} 生成正确的 Unicode 转义。
 *
 * <p>这条测试守三件事：① pom 指向那份 RTF（而不是退回 LICENSE 或 .txt）；
 * ② 提交进仓库的 .rtf 与 .txt **严格同步**（改了 .txt 忘了重跑脚本 → 红）；
 * ③ .rtf 里没有上面那种畸形转义。
 *
 * <p>注意：本文件里凡是出现反斜杠的地方都写成两个（Java 源码的注释里也一样 ——
 * 单个反斜杠紧跟 u 会被 javac 当成 Unicode 转义，直接编译失败，这一条踩过）。
 */
class InstallerLicenseTest {

    private static final Path TXT = Path.of("packaging/license-installer.txt");
    private static final Path RTF = Path.of("packaging/license-installer.rtf");
    private static final Path POM = Path.of("pom.xml");

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 与 tools\\make-license-rtf.ps1 完全相同的转换规则（改一处就要改两处，测试会立刻发现）。 */
    private static String toRtf(String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\\rtf1\\ansi\\ansicpg936\\deff0{\\fonttbl{\\f0\\fmodern\\fcharset134 Courier New;}}");
        sb.append("\\viewkind4\\uc1\\pard\\f0\\fs18 ");
        for (char ch : text.toCharArray()) {
            int code = ch;
            if (ch == '\\') {
                sb.append("\\\\");
            } else if (ch == '{') {
                sb.append("\\{");
            } else if (ch == '}') {
                sb.append("\\}");
            } else if (ch == '\r') {
                // CRLF 只认 LF
            } else if (ch == '\n') {
                sb.append("\\par\r\n");
            } else if (code < 128) {
                sb.append(ch);
            } else {
                if (code > 32767) {
                    code -= 65536;          // RTF 的 u 转义是**有符号** 16 位
                }
                sb.append("\\u").append(code).append('?');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    @Test
    @DisplayName("pom 的许可页指向包装好的 RTF（不是 LICENSE、也不是 .txt）")
    void pomPointsAtThePreparedRtf() throws IOException {
        String pom = read(POM);
        assertTrue(pom.contains("<licenseFile>${project.basedir}/packaging/license-installer.rtf</licenseFile>"),
                "许可页必须是那份已转义的 RTF：纯文本会被 jpackage 转成畸形转义，中文变问号");
        assertFalse(pom.contains("<licenseFile>${project.basedir}/LICENSE</licenseFile>"),
                "退回 LICENSE 就没有中文说明了；而且纯文本路径会把中文弄成问号");
    }

    @Test
    @DisplayName("提交进仓库的 .rtf 与 .txt 严格同步（忘了重跑脚本就会红）")
    void rtfMatchesTheSourceText() throws IOException {
        String expected = toRtf(read(TXT));
        String actual = read(RTF);
        assertEquals(expected, actual,
                "packaging\\license-installer.rtf 与 .txt 不一致 —— "
                        + "改完 .txt 后请运行 tools\\make-license-rtf.ps1");
    }

    @Test
    @DisplayName("中文说明真的在，且没有 jpackage 那种畸形转义")
    void chinesePrefaceIsPresentAndWellFormed() throws IOException {
        String rtf = read(RTF);
        assertTrue(rtf.startsWith("{\\rtf1"), "应当是 RTF");
        assertTrue(rtf.contains("\\u-29768?") || rtf.contains("\\u35768?"),
                "中文必须转成正确的 Unicode 转义（这里是「许」字）");
        assertFalse(Pattern.compile("\\\\ud\\p{Digit}").matcher(rtf).find(),
                "出现了 jpackage 那种畸形转义（反斜杠 u d 数字）—— 渲染出来是一串问号");
        assertTrue(rtf.contains("Apache License"), "Apache 正文不能丢");
        assertTrue(rtf.contains("TERMS AND CONDITIONS FOR USE"), "Apache 正文不能丢");
    }
}
