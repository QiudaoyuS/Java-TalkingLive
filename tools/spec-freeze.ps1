# 规格基线（specs/SPEC.sha256）的冻结与核对。
#
# 为什么需要它：规格（specs/**）与"解释规格的测试"（SpecTest.java）是**已验收行为**的固化基线。
# 谁都可以改它们 —— 问题在于**改了不能被看见**。所以这里给它们算一份哈希清单：
#
#   · 改了 specs/** 或 SpecTest.java 而不跑本脚本 → `-Check` 立刻失败（CI 里也会红）
#   · 跑了本脚本更新基线 → 那次改动**必然出现在 git diff 里**，瞒不住
#
# 用法：
#   tools\spec-freeze.ps1          # 更新基线（改完规格必须跑这一步）
#   tools\spec-freeze.ps1 -Check   # 只核对（CI 里跑这条）
#
# 本文件含中文，因此必须存成 **UTF-8 BOM**（见 tools/README.md 的「两个操作上的坑」）。

param([switch]$Check)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$manifest = Join-Path $root 'specs\SPEC.sha256'

# 被冻结的东西：specs/ 下所有文件（除清单自己）+ 解释规格的那个测试类
$targets = @()
$targets += Get-ChildItem (Join-Path $root 'specs') -Recurse -File |
    Where-Object { $_.Name -ne 'SPEC.sha256' }
$targets += Get-Item (Join-Path $root 'src\test\java\com\talkinglive\SpecTest.java')

function Read-Normalized([string]$path) {
    # 行尾可能被 autocrlf 变成 CRLF，比对前统一成 LF（清单本身在 .gitattributes 里标了 -text）
    return ([System.IO.File]::ReadAllText($path)) -replace "`r`n", "`n"
}

function Get-NormalizedHash([string]$path) {
    # ⚠️ 必须对**规范化后的内容**（CRLF → LF）算哈希，不能直接对字节算（Get-FileHash）：
    # 否则同一份规格在本机（autocrlf=true，签出 CRLF）与 CI runner（LF）会得出不同哈希，
    # 表现是"什么都没改却报规格被改过"—— 这个假红实测复现过。
    $text = ([System.IO.File]::ReadAllText($path)) -replace "`r`n", "`n"
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($text)
        return ([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').ToLower()
    } finally {
        $sha.Dispose()
    }
}

$lines = @()
foreach ($f in ($targets | Sort-Object FullName)) {
    $rel = $f.FullName.Substring($root.Length + 1).Replace('\', '/')
    $lines += "$(Get-NormalizedHash $f.FullName)  $rel"
}
$new = ($lines -join "`n") + "`n"

if ($Check) {
    if (-not (Test-Path $manifest)) {
        Write-Host "FAIL: 没有 specs\SPEC.sha256 —— 先跑 tools\spec-freeze.ps1" -ForegroundColor Red
        exit 1
    }
    $old = Read-Normalized $manifest
    if ($old -ne $new) {
        Write-Host "FAIL: 规格或 SpecTest 与基线不一致 —— 改了规格就必须更新基线（并走 [SPEC] 提交）" -ForegroundColor Red
        Write-Host "`n  基线里有、现在对不上的行："
        foreach ($l in ($old -split "`n")) {
            if ($l.Trim() -and ($new -split "`n") -notcontains $l) { Write-Host "    - $l" }
        }
        Write-Host "`n  现在有、基线里没有的行："
        foreach ($l in ($new -split "`n")) {
            if ($l.Trim() -and ($old -split "`n") -notcontains $l) { Write-Host "    + $l" }
        }
        Write-Host "`n  确认这是有意的行为变更后：tools\spec-freeze.ps1  （再提交，首行带 [SPEC]）"
        exit 1
    }
    Write-Host "OK: 规格与基线一致（$($lines.Count) 个文件）" -ForegroundColor Green
    exit 0
}

[System.IO.File]::WriteAllText($manifest, $new, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "已冻结 $($lines.Count) 个文件到 specs\SPEC.sha256："
$lines | ForEach-Object { Write-Host "  $_" }
Write-Host "`n提醒：如果这次改动是有意的行为变更，提交首行请带 [SPEC]，并在 docs/DECISIONS.md 记一条。"
