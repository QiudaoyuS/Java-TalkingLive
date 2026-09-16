import javax.sound.sampled.*;
import java.util.*;

/**
 * 列出 Java 视角下所有可用的录音设备与格式。
 *
 * <p>用途：区分「机器没有麦克风」与「有设备但 Java 取不到（未插、被禁用、
 * 隐私设置拦住、被独占）」——这两种情况的处理方式完全不同。
 */
public class ListMics {
    public static void main(String[] args) {
        System.out.println("=== 系统混音器（Mixer）一览 ===");
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        int recordingCapable = 0;
        for (Mixer.Info mi : mixers) {
            Mixer m = AudioSystem.getMixer(mi);
            Line.Info[] srcs = m.getSourceLineInfo(new DataLine.Info(TargetDataLine.class, null));
            String mark = srcs.length > 0 ? "  <== 可录音" : "";
            System.out.printf("%-55s | 录音线路 %d%s%n", mi.getName(), srcs.length, mark);
            if (srcs.length > 0) {
                recordingCapable++;
                for (Line.Info li : srcs) {
                    if (li instanceof DataLine.Info dli) {
                        for (AudioFormat f : dli.getFormats()) {
                            System.out.printf("      %6.0f Hz / %2d bit / %d 声道%n",
                                    f.getSampleRate(), f.getSampleSizeInBits(), f.getChannels());
                        }
                    }
                }
            }
        }
        System.out.println();
        System.out.println("可录音的混音器数 = " + recordingCapable);

        System.out.println();
        System.out.println("=== 直接尝试打开系统默认 TargetDataLine ===");
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, null);
        if (!AudioSystem.isLineSupported(info)) {
            System.out.println("AudioSystem.isLineSupported(TargetDataLine) = false");
        } else {
            System.out.println("isLineSupported = true，尝试 getLine…");
        }
        try {
            TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
            line.open();
            System.out.println("  ✅ 打开成功！设备格式 = " + line.getFormat());
            line.start();
            byte[] buf = new byte[4096];
            int n = line.read(buf, 0, buf.length);
            double rms = 0;
            for (int i = 0; i + 1 < n; i += 2) {
                short s = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                rms += (double) s * s;
            }
            rms = n > 0 ? Math.sqrt(rms / (n / 2.0)) : 0;
            System.out.printf("  ✅ 读到 %d 字节，RMS = %.1f（>100 说明真的在采到声音）%n", n, rms);
            line.stop();
            line.close();
        } catch (LineUnavailableException e) {
            System.out.println("  ❌ 打开失败：LineUnavailableException — " + e.getMessage());
            System.out.println("     常见原因：没插麦克风 / 麦克风被禁用 / 隐私设置不允许 / 被其他程序独占");
        } catch (Exception e) {
            System.out.println("  ❌ 打开失败：" + e);
        }

        System.out.println();
        System.out.println("=== 作为对照：能否打开播放线路（扬声器）===");
        try {
            DataLine.Info out = new DataLine.Info(SourceDataLine.class, null);
            if (AudioSystem.isLineSupported(out)) {
                SourceDataLine sl = (SourceDataLine) AudioSystem.getLine(out);
                System.out.println("  扬声器可用：" + sl.getLineInfo());
                sl.close();
            } else {
                System.out.println("  没有可用播放设备");
            }
        } catch (Exception e) {
            System.out.println("  播放线路打开失败：" + e);
        }
    }
}
