# check-prefix-stability.ps1 — 上下文宪法回归检查(冻结前缀 + 缓存对账)
#
# 解析一份客户端日志,验证:
#   1. prefixHash 稳定性:每个哈希的请求数分布。合法的哈希切换只有两种:
#      重启(新会话)与 async compaction splice(日志有 "spliced" 行)。
#      多伙伴会话天然多前缀(每伙伴一个 env),用 -MaxExpectedPrefixes 放宽。
#   2. 供应商对账:cache: hit N/M 行的聚合命中率(引擎 f60905a 起才有)。
#   3. 哨兵告警:cache regression 行数(前缀冻结但供应商命中坍塌)。
#
# 用法:
#   .\scripts\check-prefix-stability.ps1                       # 默认 dev 客户端日志
#   .\scripts\check-prefix-stability.ps1 -Log path\to\latest.log -MaxExpectedPrefixes 2
#
# 退出码:0 = 通过;1 = 前缀漂移超预期(CI 门槛)。

param(
    [string]$Log = "$PSScriptRoot\..\fabric\runs\client\logs\latest.log",
    [int]$MaxExpectedPrefixes = 1
)

if (-not (Test-Path $Log)) { Write-Error "log not found: $Log"; exit 2 }

$prefixLines = Select-String -Path $Log -Pattern "\[numen-llm\] lr-\d+ prefixHash=([0-9a-f]+)"
$splices     = Select-String -Path $Log -Pattern "async compaction spliced"
$regressions = Select-String -Path $Log -Pattern "cache regression"
$cacheHits   = Select-String -Path $Log -Pattern "cache: hit (\d+)/(\d+) prompt tokens"

$groups = $prefixLines | ForEach-Object { $_.Matches[0].Groups[1].Value } |
    Group-Object | Sort-Object Count -Descending

Write-Host "=== prefix stability ==="
Write-Host ("requests: " + $prefixLines.Count + "   distinct prefixes: " + @($groups).Count `
    + "   splices: " + $splices.Count)
foreach ($g in $groups) { Write-Host ("  " + $g.Count + "x " + $g.Name) }

if ($cacheHits.Count -gt 0) {
    $cached = 0L; $prompt = 0L
    foreach ($m in $cacheHits) {
        $cached += [long]$m.Matches[0].Groups[1].Value
        $prompt += [long]$m.Matches[0].Groups[2].Value
    }
    $rate = if ($prompt -gt 0) { [math]::Round($cached * 100.0 / $prompt) } else { 0 }
    Write-Host "=== provider cache audit ==="
    Write-Host ("metered requests: " + $cacheHits.Count + "   hit " + $cached + "/" + $prompt + " tok (" + $rate + "%)")
} else {
    Write-Host "=== provider cache audit: no metered lines (pre-f60905a build or provider silent) ==="
}
if ($regressions.Count -gt 0) {
    Write-Host ("!! cache regression warnings: " + $regressions.Count) -ForegroundColor Yellow
    $regressions | Select-Object -First 3 | ForEach-Object { Write-Host ("   " + $_.Line.Trim()) }
}

# CI gate: distinct prefixes beyond (expected companions + legal splices) = drift.
$allowed = $MaxExpectedPrefixes + $splices.Count
if (@($groups).Count -gt $allowed) {
    Write-Host ("FAIL: " + @($groups).Count + " distinct prefixes > allowed " + $allowed `
        + " (expected " + $MaxExpectedPrefixes + " + " + $splices.Count + " splice)") -ForegroundColor Red
    exit 1
}
Write-Host "PASS" -ForegroundColor Green
exit 0
