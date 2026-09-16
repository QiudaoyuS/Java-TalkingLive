import javax.sound.sampled.*;

/**
 * 定位「设备其实可用，但枚举不到」的原因。
 *
 * <p>现象：{@code AudioSystem.getSourceLineInfo(new DataLine.Info(TargetDataLine.class, null))}
 * 返回空数组，但 {@code AudioSystem.getLine(同一个 Info)} 却能成功打开。
 * 如果确认如此，那么产品里「用 getSourceLineInfo 去找设备」的做法就是错的。
 */
public class ProbeMixer {
    public static void main(String[] args) throws Exception {
        DataLine.Info probe = new DataLine.Info(TargetDataLine.class, null);

        System.out.println("=== A. AudioSystem.getSourceLineInfo(probe) ===");
        Line.Info[] a = AudioSystem.getSourceLineInfo(probe);
        System.out.println("  返回 " + a.length + " 项");
        for (Line.Info li : a) {
            System.out.println("    " + li + "  formats="
                    + (li instanceof DataLine.Info d ? d.getFormats().length : -1));
        }

        System.out.println();
        System.out.println("=== B. AudioSystem.getTargetLineInfo(probe) ===");
        Line.Info[] b = AudioSystem.getTargetLineInfo(probe);
        System.out.println("  返回 " + b.length + " 项");
        for (Line.Info li : b) {
            System.out.println("    " + li + "  formats="
                    + (li instanceof DataLine.Info d ? d.getFormats().length : -1));
        }

        System.out.println();
        System.out.println("=== C. 逐混音器 getTargetLineInfo / getSourceLineInfo ===");
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            Mixer m = AudioSystem.getMixer(mi);
            int t = m.getTargetLineInfo(probe).length;
            int s = m.getSourceLineInfo(probe).length;
            if (t > 0 || s > 0) {
                System.out.printf("  %-45s target=%d source=%d%n", mi.getName(), t, s);
                for (Line.Info li : m.getTargetLineInfo(probe)) {
                    System.out.println("      target: " + li);
                }
            }
        }

        System.out.println();
        System.out.println("=== D. getMixerInfo 的完整信息（看是否有 TargetDataLine 支持）===");
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            Mixer m = AudioSystem.getMixer(mi);
            boolean supportsTarget = m.isLineSupported(probe);
            Line.Info[] all = m.getTargetLineInfo();
            System.out.printf("  %-45s isLineSupported=%s  getTargetLineInfo()=%d%n",
                    mi.getName(), supportsTarget, all.length);
        }

        System.out.println();
        System.out.println("=== E. 关键结论 ===");
        boolean enumEmpty = a.length == 0 && b.length == 0;
        boolean canOpen;
        try (TargetDataLine l = (TargetDataLine) AudioSystem.getLine(probe)) {
            l.open();
            canOpen = true;
            System.out.println("  getLine() 直接打开：成功，格式 = " + l.getFormat());
        } catch (Exception e) {
            canOpen = false;
            System.out.println("  getLine() 直接打开：失败 " + e);
        }
        System.out.println();
        if (enumEmpty && canOpen) {
            System.out.println("  ⇒ 确认：**枚举返回空，但 getLine() 可用**。");
            System.out.println("     产品用 getSourceLineInfo 找设备是错的，必须直接走 getLine 回退。");
        } else if (!enumEmpty) {
            System.out.println("  ⇒ 枚举非空，问题不在这里。");
        } else {
            System.out.println("  ⇒ 枚举与打开都失败，属「真的没有麦克风」。");
        }
    }
}
