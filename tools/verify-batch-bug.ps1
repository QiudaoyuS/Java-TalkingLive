# 一次性验证脚本：证明 batchByCodePoints 存在死循环
#
# 这是一个"先证明 bug 存在、再修"的步骤。死循环会把调用它的线程永久卡住，
# 如果发生在 UI 线程上，整个界面就再也不响应——正是用户报告的"点不动任何东西"。
$ErrorActionPreference = 'Stop'

$tools = Join-Path $env:TEMP 'tl-batchtest'
New-Item -ItemType Directory -Force -Path $tools | Out-Null

$java = @'
import java.util.List;

/** 复制产品里的分批逻辑，验证它在代理对跨越批边界时是否会死循环。 */
public class BatchProbe {
    // 与 com.talkinglive.text.TextInjector.batchByCodePoints 完全一致的实现
    static List<String> batch(String text, int maxCodePointsPerBatch) {
        if (text == null || text.isEmpty()) return List.of();
        int per = Math.max(1, maxCodePointsPerBatch);
        List<String> out = new java.util.ArrayList<>();
        int start = 0, count = 0, i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            count++;
            if (count == per) { out.add(text.substring(start, i)); start = i; count = 0; }
        }
        if (start < text.length()) out.add(text.substring(start));
        return out;
    }

    public static void main(String[] a) throws Exception {
        // 用线程 + 超时来探测死循环（死循环本身不会自己结束）
        String[] cases = {
            "abcdef",      // 纯 ASCII
            "a\ud83d\ude00b", // a + emoji + b
            "\ud83d\ude00\ud83d\ude01", // 两个 emoji
            "中文😀混排",
        };
        int[] sizes = {1, 2, 3};

        for (String s : cases) {
            for (int per : sizes) {
                final String fs = s;
                final int fp = per;
                final Object[] result = new Object[2];
                Thread t = new Thread(() -> {
                    try {
                        result[0] = batch(fs, fp);
                    } catch (Throwable e) {
                        result[1] = e;
                    }
                });
                t.setDaemon(true);
                t.start();
                t.join(2000);   // 最多等 2 秒

                String label = String.format("文本=%s(码点%d,UTF16长度%d) 每批=%d",
                        esc(fs), fs.codePointCount(0, fs.length()), fs.length(), fp);
                if (t.isAlive()) {
                    System.out.println("  [死循环] " + label + "  → 线程 2 秒内未结束！");
                } else if (result[1] != null) {
                    System.out.println("  [异常]   " + label + "  → " + result[1]);
                } else {
                    @SuppressWarnings("unchecked")
                    List<String> r = (List<String>) result[0];
                    String joined = String.join("", r);
                    boolean roundTripOk = joined.equals(fs);
                    System.out.printf("  [%s] %s  批数=%d 拼回一致=%s%n",
                            roundTripOk ? "ok" : "错!", label, r.size(), roundTripOk);
                }
            }
        }
        System.out.println();
        System.out.println("判读：只要出现 [死循环]，就证明这个分批函数会永久卡住调用线程。");
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 128) b.append(c); else b.append(String.format("\\u%04x", (int) c));
        }
        return b.toString();
    }
}
'@

$src = Join-Path $tools 'BatchProbe.java'
[System.IO.File]::WriteAllText($src, $java, (New-Object System.Text.UTF8Encoding($false)))

$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
& $javac -encoding UTF-8 -d $tools $src
Write-Output "=== 验证 batchByCodePoints 是否存在死循环 ==="
& $javaExe "-Dstdout.encoding=UTF-8" "-Dfile.encoding=UTF-8" -cp $tools BatchProbe
