package com.talkinglive.system;

import com.talkinglive.core.AppPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

/**
 * 日志查看器：把日志文件的**尾部**正确解码后打印出来。
 *
 * <p><b>为什么需要它（用户实测反馈：「日志中存在乱码」）：</b>
 * 日志文件本来就是 UTF-8，但**没有 BOM**，于是记事本和 PowerShell 的
 * {@code Get-Content}（不带 {@code -Encoding}）会按系统 ANSI(GBK) 去解，
 * 中文全变成「蹇界暐」这种乱码。用户以为日志坏了，其实只是读的人猜错了编码。
 *
 * <p>两处一起修：
 * <ol>
 *   <li>{@code logback.xml} 的 FILE appender 加 {@code <withBom>true</withBom>}，
 *       让文件自己声明"我是 UTF-8"（只在**新建**文件时写 BOM，已存在的旧文件不会补）。</li>
 *   <li>本入口：用明确的 UTF-8 读取并打印，**不经过控制台默认代码页**。
 *       这是"永远不用再碰编码问题"的那条路。</li>
 * </ol>
 *
 * <p>用法：{@code java -cp ... com.talkinglive.system.LogViewer [行数]}
 * （默认 200 行）。{@code --all} 打印全文。
 *
 * <p>注意它**只读不改**，也不会被单实例保护拦住（它不启动 App）。
 */
public final class LogViewer {

    private static final int DEFAULT_LINES = 200;

    private LogViewer() {}

    public static void main(String[] args) throws IOException {
        Path log = AppPaths.logDir().resolve("talkinglive.log");
        boolean all = false;
        int lines = DEFAULT_LINES;
        for (String a : args) {
            if ("--all".equals(a)) {
                all = true;
            } else {
                try {
                    lines = Integer.parseInt(a);
                } catch (NumberFormatException ignored) {
                    // 不是数字就当没给
                }
            }
        }

        if (!Files.isRegularFile(log)) {
            print("日志文件还不存在：" + log);
            print("（程序还没运行过，或者日志目录被清掉了）");
            listRolled();
            return;
        }

        print("日志文件：" + log);
        print("大小：" + Files.size(log) + " 字节；按 UTF-8 解码；"
                + (all ? "全部" : "最后 " + lines + " 行"));
        print("=".repeat(78));

        // 显式 UTF-8：不依赖「控制台默认代码页」这个不可靠的东西。
        // 用 Scanner 逐行读，避免一次把 5MB 全读进内存。
        try (Scanner sc = new Scanner(Files.newInputStream(log), StandardCharsets.UTF_8)) {
            List<String> keep = all ? null : new java.util.ArrayList<>();
            while (sc.hasNextLine()) {
                String line = stripBom(sc.nextLine());
                if (keep == null) {
                    print(line);
                } else {
                    keep.add(line);
                    if (keep.size() > lines) {
                        keep.remove(0);
                    }
                }
            }
            if (keep != null) {
                keep.forEach(LogViewer::print);
            }
        }

        listRolled();
    }

    /** 顺带列出滚动文件，方便用户知道还有 talkinglive.1.log / .2.log。 */
    private static void listRolled() throws IOException {
        Path dir = AppPaths.logDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var s = Files.list(dir)) {
            List<Path> rolled = s.filter(p -> p.getFileName().toString().matches("talkinglive\\.\\d+\\.log"))
                    .sorted()
                    .toList();
            if (!rolled.isEmpty()) {
                print("=".repeat(78));
                print("滚动归档（较旧）：");
                for (Path p : rolled) {
                    print("  " + p + "  （" + Files.size(p) + " 字节）");
                }
            }
        }
    }

    /** 去掉可能存在的 UTF-8 BOM（U+FEFF），否则第一行会多一个看不见的字符。 */
    private static String stripBom(String s) {
        return s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    /**
     * 打印。
     *
     * <p>刻意用 {@code System.out} + 明确的换行，不做任何转码 ——
     * 这个类的整个存在意义就是"不要再多经一层编码猜测"。
     * 控制台若不是 UTF-8 仍可能显示为乱码，那是控制台的问题，
     * 解决办法是 {@code chcp 65001} 或改用 {@code tools\run-console.cmd}（它会设好编码）。
     */
    private static void print(String s) {
        System.out.println(s);
    }
}
