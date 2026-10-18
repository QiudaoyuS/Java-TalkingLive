package com.talkinglive.core;

import ch.qos.logback.core.PropertyDefinerBase;

/**
 * 把**日志根目录**交给 {@link AppPaths} 决定，供 {@code logback.xml} 的
 * {@code <define>} 调用。
 *
 * <p><b>为什么需要这个类</b>（安装包实测踩过）：{@code logback.xml} 原本把路径写死成
 * <pre>{@code ${TALKINGLIVE_HOME:-${user.home}/AppData/Local/TalkingLive}/logs/talkinglive.log}</pre>
 * 而**全仓没有一处给 {@code TALKINGLIVE_HOME} 赋值**，于是那个默认值永远生效 ——
 * 实测后果：
 * <ul>
 *   <li>程序自己报告的 {@code home} 是 {@code %LOCALAPPDATA%\TalkingLiveData}，
 *       日志却写在 {@code %LOCALAPPDATA%\TalkingLive\logs}：**日志和数据不在一处**，
 *       数据目录下那个 {@code logs} 始终是个空壳；</li>
 *   <li>{@code %LOCALAPPDATA%\TalkingLive} 正是 jpackage 每用户安装的**默认安装目录**，
 *       而卸载会把它**整棵删掉** → 日志跟着没；</li>
 *   <li>跑 {@code mvnw test} 也会往**用户真实的日志**里写（实测把 90KB 写成了 1.7MB）。</li>
 * </ul>
 *
 * <p>修法是让路径只有一个来源：{@link AppPaths#home()}。它在 {@code logback.xml}
 * 被解析时就求值，所以不依赖"main 里有没有先设系统属性"这种时序。
 *
 * <p><b>为什么不用 {@code System.setProperty} 在 main 里补</b>：任何一条更早的日志语句
 * （例如某个类的静态初始化）都会让 logback 先用自己的默认值把 appender 建好，
 * 之后就再也不改了 —— 那是个只在特定启动顺序下才暴露的偶发缺陷。
 *
 * <p>**绝不抛异常**：日志初始化失败会让整个程序起不来，而"日志写不出来"不该有这种权力。
 * 兜底退到 {@code java.io.tmpdir}。
 */
public class LogHomePropertyDefiner extends PropertyDefinerBase {

    @Override
    public String getPropertyValue() {
        try {
            return AppPaths.home().toString();
        } catch (RuntimeException e) {
            return System.getProperty("java.io.tmpdir", ".");
        }
    }
}
