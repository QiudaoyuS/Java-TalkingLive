# 把「安装向导用的许可文本」转成 RTF。
#
# 为什么要自己转：jpackage 会把**纯文本**许可文件自动转成 RTF，但它的转换对非 ASCII
# 字符生成的是 `\ud8212?` 这种畸形转义，实测在 RichEdit 里渲染成问号 ——
# 也就是"中文许可说明在安装向导里会变成一串 ?"。而 **RTF 文件是原样复制**的
# （实测：传 .rtf 进去，config 目录里那份与输入逐字节相同），所以自己生成正确的
# `\uNNNN?` 就能让中文正常显示。
#
# 用法（在仓库根执行）：
#   powershell -ExecutionPolicy Bypass -File tools\make-license-rtf.ps1
# 输入：packaging\license-installer.txt （UTF-8，中文说明 + Apache 英文全文）
# 输出：packaging\license-installer.rtf （ASCII，正确转义）
# pom.xml 里 jpackage 的 licenseFile 指向**输出的 .rtf**，不要指向 .txt。

param(
    [string]$In  = (Join-Path $PSScriptRoot '..\packaging\license-installer.txt'),
    [string]$Out = (Join-Path $PSScriptRoot '..\packaging\license-installer.rtf')
)

$ErrorActionPreference = 'Stop'

$inFull  = [System.IO.Path]::GetFullPath($In)
$outFull = [System.IO.Path]::GetFullPath($Out)

$text = [System.IO.File]::ReadAllText($inFull, [System.Text.Encoding]::UTF8)
$sb = New-Object System.Text.StringBuilder

# \ansicpg936 + fcharset134：给不支持 \u 的老控件一个合理的中文字体
[void]$sb.Append('{\rtf1\ansi\ansicpg936\deff0{\fonttbl{\f0\fmodern\fcharset134 Courier New;}}')
[void]$sb.Append('\viewkind4\uc1\pard\f0\fs18 ')

foreach ($ch in $text.ToCharArray()) {
    $code = [int]$ch
    if ($ch -eq '\') { [void]$sb.Append('\\') }
    elseif ($ch -eq '{') { [void]$sb.Append('\{') }
    elseif ($ch -eq '}') { [void]$sb.Append('\}') }
    elseif ($ch -eq [char]13) { }
    elseif ($ch -eq [char]10) { [void]$sb.Append('\par' + [char]13 + [char]10) }
    elseif ($code -lt 128) { [void]$sb.Append($ch) }
    else {
        if ($code -gt 32767) { $code -= 65536 }
        [void]$sb.Append('\u' + $code + '?')
    }
}

[void]$sb.Append('}')
[System.IO.File]::WriteAllText($outFull, $sb.ToString(), [System.Text.Encoding]::ASCII)

$outItem = Get-Item $outFull
Write-Host ('已生成 ' + $outItem.FullName + '（' + [math]::Round($outItem.Length / 1KB, 1) + ' KB）')