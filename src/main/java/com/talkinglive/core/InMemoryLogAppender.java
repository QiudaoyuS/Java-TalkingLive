package com.talkinglive.core;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 把日志行留在内存环形缓冲里，供设置窗口的「日志」页签直接显示。
 *
 * <p>为什么不直接读日志文件：文件是带滚动策略的，页签还得处理「滚到一半」；
 * 而且用户点「查看日志」想看的是**刚刚发生的事**，内存缓冲最直接。
 *
 * <p>缓冲上限 {@value #CAPACITY} 行，超出丢最旧的。行内容与文件一致，
 * 因此同样受「不记转写内容」的约束（{@link Logging}）。
 */
public class InMemoryLogAppender extends AppenderBase<ILoggingEvent> {

    public static final int CAPACITY = 2000;

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final Object LOCK = new Object();
    private static final ArrayDequeBuffer BUFFER = new ArrayDequeBuffer(CAPACITY);
    private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();

    /** 单例实例，logback 反射创建后注册到这里，供 UI 取用。 */
    private static volatile InMemoryLogAppender instance;

    public InMemoryLogAppender() {
        // logback 通过无参构造反射创建
    }

    public static InMemoryLogAppender get() {
        return instance;
    }

    @Override
    public void start() {
        instance = this;
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        String line = format(event);
        synchronized (LOCK) {
            BUFFER.add(line);
        }
        for (Consumer<String> l : LISTENERS) {
            try {
                l.accept(line);
            } catch (RuntimeException ignored) {
                // UI 监听器出错不能影响日志本身
            }
        }
    }

    private static String format(ILoggingEvent e) {
        return TS.format(Instant.ofEpochMilli(e.getTimeStamp()))
                + " " + pad(e.getLevel().toString())
                + " " + shortName(e.getLoggerName())
                + " - " + e.getFormattedMessage();
    }

    private static String pad(String level) {
        return switch (level) {
            case "INFO" -> "INFO ";
            case "WARN" -> "WARN ";
            case "DEBUG" -> "DEBUG";
            case "ERROR" -> "ERROR";
            default -> level;
        };
    }

    private static String shortName(String logger) {
        if (logger == null) {
            return "?";
        }
        int i = logger.lastIndexOf('.');
        return i < 0 ? logger : logger.substring(i + 1);
    }

    // ------------------------------------------------------------ 读取

    /** 当前缓冲的全部行（最旧在前）。 */
    public static List<String> lines() {
        synchronized (LOCK) {
            return BUFFER.toList();
        }
    }

    /** 最近 n 行。 */
    public static List<String> tail(int n) {
        synchronized (LOCK) {
            return BUFFER.tail(n);
        }
    }

    public static int size() {
        synchronized (LOCK) {
            return BUFFER.size();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            BUFFER.clear();
        }
    }

    /** 订阅新行。返回取消订阅的句柄。 */
    public static AutoCloseable subscribe(Consumer<String> listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    /** 极简环形缓冲：满时丢最旧，读取不分配多余结构。 */
    private static final class ArrayDequeBuffer {
        private final ArrayList<String> items;
        private final int capacity;

        ArrayDequeBuffer(int capacity) {
            this.capacity = capacity;
            this.items = new ArrayList<>(capacity);
        }

        void add(String s) {
            if (items.size() == capacity) {
                items.remove(0);
            }
            items.add(s);
        }

        List<String> toList() {
            return new ArrayList<>(items);
        }

        List<String> tail(int n) {
            int from = Math.max(0, items.size() - n);
            return new ArrayList<>(items.subList(from, items.size()));
        }

        int size() {
            return items.size();
        }

        void clear() {
            items.clear();
        }
    }
}
