# 发布版本 = 提交序号（AGENTS.md「发布版本跟随提交序号」）
#
# 一个提交首行写 [1.0.4]，它携带的文件里「当前版本」就必须是 1.0.4。三处必须一起改：
#   README.md 顶部的「当前版本 **N.n.m**」
#   pom.xml 的 <version>
#   pom.xml 里 jpackage 的 <appVersion>（最容易漏 —— 它曾长期停在 0.1.0）
#
# 用法：
#   tools\set-version.ps1              # 取 HEAD 的 [N.n.m]，m+1，写入三处（普通提交）
#   tools\set-version.ps1 1.1.0        # 用指定号（用户说「版本 +1」/「版本号 +1」时）
#   tools\set-version.ps1 -Check       # 只核对：三处是否一致、且等于 HEAD 首行的号
#   tools\set-version.ps1 -Tag         # 核对通过后给 HEAD 打 v<号>（标记某一版）
#
# 本文件含中文，因此必须以 **UTF-8 BOM** 存盘（见 tools/README.md 的「两个操作上的坑」）：
# 无 BOM 时 Windows PowerShell 5.1 会按 ANSI/GBK 解码，中文乱码并可能报解析错误。

param(
    [Parameter(Position = 0)][string]$Version,
    [switch]$Check,
    [switch]$Tag
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot          # tools 的上一级 = 仓库根
$readme = Join-Path $root 'README.md'
$pom = Join-Path $root 'pom.xml'

function Get-Versions {
    $found = [ordered]@{}
    # 注意：Select-String 是**逐行**匹配的，跨行正则匹配不到 —— 先把整个文件读成字符串再 Match
    $readmeText = [System.IO.File]::ReadAllText($readme)
    $pomText = [System.IO.File]::ReadAllText($pom)

    $m = [regex]::Match($readmeText, '(?m)^当前版本 \*\*(\d+\.\d+\.\d+)\*\*')
    if (-not $m.Success) { throw "README.md 里找不到「当前版本 **N.n.m**」那一行" }
    $found['README.md 当前版本'] = $m.Groups[1].Value

    $m = [regex]::Match($pomText, '(?s)<artifactId>talkinglive</artifactId>\s*<version>(\d+\.\d+\.\d+)</version>')
    if (-not $m.Success) { throw "pom.xml 里找不到 <artifactId>talkinglive</artifactId> 后面的 <version>N.n.m</version>" }
    $found['pom.xml <version>'] = $m.Groups[1].Value

    $m = [regex]::Match($pomText, '<appVersion>(\d+\.\d+\.\d+)</appVersion>')
    if (-not $m.Success) { throw "pom.xml 里找不到 <appVersion>N.n.m</appVersion>" }
    $found['pom.xml <appVersion>'] = $m.Groups[1].Value
    return $found
}

function Get-HeadVersion {
    $subject = (& git -C $root log -1 --format=%s) -join ''
    if ($subject -notmatch '\[(\d+\.\d+\.\d+)\]') {
        throw "HEAD 的首行没有 [N.n.m]：「$subject」"
    }
    return $Matches[1]
}

function Set-Version([string]$new) {
    $txt = [System.IO.File]::ReadAllText($readme)
    $rx = New-Object regex '(?m)^当前版本 \*\*\d+\.\d+\.\d+\*\*'
    $out = $rx.Replace($txt, "当前版本 **$new**", 1)
    if ($out -eq $txt) { throw "README.md 的版本行没被替换（格式变了？）" }
    [System.IO.File]::WriteAllText($readme, $out, (New-Object System.Text.UTF8Encoding($false)))

    $txt = [System.IO.File]::ReadAllText($pom)
    $rx = New-Object regex '(?s)(<artifactId>talkinglive</artifactId>\s*<version>)\d+\.\d+\.\d+(</version>)'
    $out = $rx.Replace($txt, "`${1}$new`${2}", 1)
    if ($out -eq $txt) { throw "pom.xml 的 <version> 没被替换" }
    $rx = New-Object regex '<appVersion>\d+\.\d+\.\d+</appVersion>'
    $out = $rx.Replace($out, "<appVersion>$new</appVersion>", 1)
    [System.IO.File]::WriteAllText($pom, $out, (New-Object System.Text.UTF8Encoding($false)))
}

function Show-Versions($v) {
    foreach ($k in $v.Keys) { Write-Host ("  {0,-22} {1}" -f $k, $v[$k]) }
}

# ---------------------------------------------------------------- -Check
if ($Check) {
    $v = Get-Versions
    Show-Versions $v
    # 注意：必须用 @(...) 强制成数组 —— PowerShell 单元素结果会退化成标量，
    # 此时 $x[0] 取到的是**字符串的第一个字符**（曾经因此报出"文件里是 1"这种假失败）
    $distinct = @($v.Values | Sort-Object -Unique)
    if ($distinct.Count -ne 1) {
        Write-Host "`nFAIL: 三处版本号不一致 —— 少改一处就会互相说反" -ForegroundColor Red
        exit 1
    }
    $files = $distinct[0]
    try { $head = Get-HeadVersion } catch { Write-Host "`nSKIP: $($_.Exception.Message)（没法比对 HEAD，仅确认三处一致）"; exit 0 }
    if ($head -ne $files) {
        Write-Host "`nFAIL: 文件里是 $files，而 HEAD 首行是 [$head] —— 两者必须相等（AGENTS.md）" -ForegroundColor Red
        exit 1
    }
    Write-Host "`nOK: 三处一致，且等于 HEAD 的 [$head]" -ForegroundColor Green
    exit 0
}

# ---------------------------------------------------------------- -Tag
if ($Tag) {
    $v = Get-Versions
    $distinct = @($v.Values | Sort-Object -Unique)
    if ($distinct.Count -ne 1) { throw "三处版本号不一致，先修好再打 tag" }
    $files = $distinct[0]
    $head = Get-HeadVersion
    if ($head -ne $files) { throw "文件里是 $files 而 HEAD 是 [$head]：tag 会打在一个与内容不符的提交上" }
    $name = "v$files"
    if (& git -C $root tag -l $name) { throw "$name 已存在" }
    & git -C $root tag -a $name -m "标记 $files"
    if ($LASTEXITCODE -ne 0) { throw "打 tag 失败" }
    Write-Host "OK: 已在 HEAD 上打 $name" -ForegroundColor Green
    exit 0
}

# ---------------------------------------------------------------- 写入
if (-not $Version) {
    $head = Get-HeadVersion
    $parts = $head.Split('.')
    $Version = "{0}.{1}.{2}" -f $parts[0], $parts[1], ([int]$parts[2] + 1)
    Write-Host "HEAD 是 [$head] → 普通提交 m+1 → $Version"
}
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "版本号格式应为 N.n.m：$Version" }

$before = Get-Versions
Set-Version $Version
$after = Get-Versions
Write-Host "`n改前："; Show-Versions $before
Write-Host "`n改后："; Show-Versions $after
Write-Host "`n下一步：用 [$Version] 作为提交首行（版本号要先进文件、再提交）。"
