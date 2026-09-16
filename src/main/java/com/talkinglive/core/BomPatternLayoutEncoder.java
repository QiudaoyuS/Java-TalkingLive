package com.talkinglive.core;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;

/**
 * 带 UTF-8 BOM 的日志编码器。
 *
 * <p><b>为什么需要它（用户实测反馈：「日志中存在乱码」）：</b>
 * 日志内容本来就是 UTF-8，但文件**没有 BOM**，于是记事本和 PowerShell 的
 * {@code Get-Content}（不带 {@code -Encoding}）会按系统 ANSI(GBK) 去解，
 * 中文全变成「閰嶇疆宸叉洿鏂」这样的乱码。用户以为日志坏了，其实只是读的人猜错了编码。
 *
 * <p><b>修法为什么是这个而不是"改编码"</b>：编码本来就是对的，缺的是**自我声明**。
 * 日志文件会被不认识我们的工具打开（记事本、VS Code、各种日志分析器），
 * 而 BOM 是文件里唯一能说明"我是 UTF-8"的东西 —— 不能指望每个读者都记得指定编码。
 *
 * <p><b>实现方式的重要细节</b>：logback 的 {@code PatternLayoutEncoder} <b>没有</b>
 * {@code withBom} 属性（第一版就是照着自己想当然的属性配的，结果静默无效 ——
 * 日志开头仍然是「202」，没有 BOM）。真正可用的钩子是
 * {@code EncoderBase.headerBytes()}：它在文件**首次创建**时被写入一次，
 * 正是 BOM 需要的位置。用 header 而不是覆写 {@code encode()}，是因为后者每写一行
 * 都会被调用，容易写成"每行一个 BOM"。
 *
 * <p>代价：文件开头多 3 个字节（EF BB BF）。任何正经工具都能正确处理；
 * 而 {@link com.talkinglive.system.LogViewer} 会主动剥掉它，避免第一行多个隐形字符。
 *
 * <p>注意：BOM 只在**新建**文件时写入。已经存在的旧日志不会被补上 ——
 * 想看旧日志请用 {@code LogViewer}（它显式按 UTF-8 解码）。
 */
public class BomPatternLayoutEncoder extends PatternLayoutEncoder {

    /** UTF-8 BOM：EF BB BF。 */
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Override
    public byte[] headerBytes() {
        return BOM;
    }
}
