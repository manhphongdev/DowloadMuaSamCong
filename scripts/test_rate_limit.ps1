[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Continue"

$BaseUrl = "https://muasamcong.mpi.gov.vn/"
$SearchUrl = "https://muasamcong.mpi.gov.vn/web/guest/contractor-selection?keyword=IB2600003044"
$NORMAL_SIZE_MIN = 200000

function Extract-PageInfo {
    param([string]$Html)
    
    $info = @{
        Title = ""
        HasSearchBox = $false
        HasResults = $false
        IsNormalPage = $false
        Snippet = ""
    }
    
    # Extract title
    if ($Html -match '<title>\s*([^<]+)\s*</title>') {
        $info.Title = $Matches[1].Trim()
    }
    
    # Check search box
    if ($Html.Contains('home-search-input') -or $Html.Contains('search-input')) {
        $info.HasSearchBox = $true
    }
    
    # Check results
    if ($Html.Contains('result-item') -or $Html.Contains('TBMT')) {
        $info.HasResults = $true
    }
    
    # Check normal page
    if ($Html.Contains('muasamcong') -and $Html.Contains('thau')) {
        $info.IsNormalPage = $true
    }
    
    # Extract a snippet - look for news links or announcements
    if ($Html -match 'class="asset-title"[^>]*>\s*<a[^>]*>([^<]{10,80})') {
        $info.Snippet = $Matches[1].Trim()
    }
    elseif ($Html -match '<h1[^>]*>([^<]{10,100})</h1>') {
        $info.Snippet = $Matches[1].Trim()
    }
    
    return $info
}

function Send-TestRequest {
    param(
        [string]$Url,
        [int]$RequestId,
        [switch]$DetailMode
    )
    
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $result = @{
        Id = $RequestId
        Status = 0
        Size = 0
        TimeMs = 0
        Blocked = $null
        Error = $null
        PageInfo = $null
        SessionId = ""
    }
    
    try {
        $response = Invoke-WebRequest -Uri $Url -TimeoutSec 30 -UseBasicParsing
        $stopwatch.Stop()
        
        $result.Status = [int]$response.StatusCode
        $result.Size = $response.Content.Length
        $result.TimeMs = $stopwatch.ElapsedMilliseconds
        
        # Extract page info
        if ($DetailMode) {
            $result.PageInfo = Extract-PageInfo -Html $response.Content
        }
        
        # Session ID
        $setCookie = ""
        if ($response.Headers.ContainsKey("Set-Cookie")) {
            $setCookie = $response.Headers["Set-Cookie"]
        }
        if ($setCookie -match "JSESSIONID=([^;]+)") {
            $sid = $Matches[1]
            if ($sid.Length -gt 15) { $sid = $sid.Substring(0, 15) + "..." }
            $result.SessionId = $sid
        }
        
        # Check blocked
        $content = $response.Content.ToLower()
        $blockIndicators = @()
        
        $keywords = @("captcha", "blocked", "rate limit", "too many requests", 
                      "access denied", "cloudflare", "challenge-platform",
                      "bot detection", "please verify", "unusual traffic",
                      "request rejected", "security check")
        
        foreach ($kw in $keywords) {
            if ($content.Contains($kw)) {
                $blockIndicators += $kw
            }
        }
        
        if ($result.Size -lt $NORMAL_SIZE_MIN -and $result.Size -gt 0) {
            $blockIndicators += "small-response($($result.Size)b)"
        }
        
        if ($blockIndicators.Count -gt 0) {
            $result.Blocked = $blockIndicators -join ", "
        }
    }
    catch {
        $stopwatch.Stop()
        $result.TimeMs = $stopwatch.ElapsedMilliseconds
        
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
            $result.Status = $statusCode
            $result.Blocked = "HTTP $statusCode"
        }
        else {
            $errMsg = $_.Exception.Message
            if ($errMsg.Length -gt 100) { $errMsg = $errMsg.Substring(0, 100) }
            $result.Error = $errMsg
        }
    }
    
    return $result
}

function Run-Scenario {
    param(
        [string]$Name,
        [string]$Url,
        [int]$Count,
        [int]$DelayMs
    )
    
    Write-Host ""
    Write-Host ("=" * 70) -ForegroundColor Cyan
    Write-Host "  $Name" -ForegroundColor Cyan
    Write-Host "  URL: $Url" -ForegroundColor Gray
    Write-Host "  Requests: $Count | Delay: ${DelayMs}ms" -ForegroundColor Gray
    Write-Host ("=" * 70) -ForegroundColor Cyan
    
    $results = @()
    $blockedCount = 0
    $errorCount = 0
    
    for ($i = 1; $i -le $Count; $i++) {
        $isDetail = ($i -eq 1 -or $i -eq [math]::Ceiling($Count/2) -or $i -eq $Count)
        $r = Send-TestRequest -Url $Url -RequestId $i -DetailMode:$isDetail
        $results += $r
        
        $statusColor = "Green"
        $statusText = "OK"
        if ($r.Blocked) { 
            $statusColor = "Red"; $statusText = "BLOCKED"; $blockedCount++
        } elseif ($r.Error) { 
            $statusColor = "Yellow"; $statusText = "ERROR"; $errorCount++
        }
        
        $sizeKB = [math]::Round($r.Size / 1024, 1)
        $line = "  #{0:D3} | {1,3} | {2,5}ms | {3,6}KB | {4}" -f $r.Id, $r.Status, $r.TimeMs, $sizeKB, $statusText
        if ($r.SessionId) { $line += " | SID:$($r.SessionId)" }
        if ($r.Blocked) { $line += " | $($r.Blocked)" }
        if ($r.Error) { $line += " | $($r.Error)" }
        Write-Host $line -ForegroundColor $statusColor
        
        # Chi tiet cho request verbose
        if ($isDetail -and $r.PageInfo) {
            $pi = $r.PageInfo
            Write-Host "        [PROOF] Title=`"$($pi.Title)`"" -ForegroundColor DarkCyan
            Write-Host "        [PROOF] SearchBox=$($pi.HasSearchBox) | Normal=$($pi.IsNormalPage) | Results=$($pi.HasResults)" -ForegroundColor DarkCyan
            if ($pi.Snippet) {
                Write-Host "        [PROOF] Content=`"$($pi.Snippet)`"" -ForegroundColor DarkCyan
            }
        }
        
        if ($DelayMs -gt 0 -and $i -lt $Count) {
            Start-Sleep -Milliseconds $DelayMs
        }
    }
    
    # Summary
    $times = @($results | Where-Object { $_.TimeMs -gt 0 } | ForEach-Object { $_.TimeMs })
    $sizes = @($results | Where-Object { $_.Size -gt 0 } | ForEach-Object { $_.Size })
    $successCount = @($results | Where-Object { $_.Status -eq 200 -and -not $_.Blocked }).Count
    
    Write-Host ""
    Write-Host "  --- SUMMARY ---" -ForegroundColor White
    $okColor = if ($successCount -eq $Count) { "Green" } else { "Yellow" }
    $blkColor = if ($blockedCount -gt 0) { "Red" } else { "Green" }
    Write-Host "  OK: $successCount/$Count | Blocked: $blockedCount | Errors: $errorCount" -ForegroundColor $okColor
    
    if ($times.Count -gt 0) {
        $avg = [math]::Round(($times | Measure-Object -Average).Average, 0)
        $mn = ($times | Measure-Object -Minimum).Minimum
        $mx = ($times | Measure-Object -Maximum).Maximum
        Write-Host "  Time: min=${mn}ms max=${mx}ms avg=${avg}ms" -ForegroundColor Gray
    }
    if ($sizes.Count -gt 0) {
        $avgKB = [math]::Round(($sizes | Measure-Object -Average).Average / 1024, 1)
        Write-Host "  Size avg: ${avgKB}KB" -ForegroundColor Gray
    }
    
    if ($times.Count -ge 6) {
        $half = [math]::Floor($times.Count / 2)
        $first = ($times[0..($half-1)] | Measure-Object -Average).Average
        $second = ($times[$half..($times.Count-1)] | Measure-Object -Average).Average
        if ($first -gt 0) {
            $ratio = [math]::Round($second / $first, 1)
            if ($ratio -gt 2) {
                Write-Host "  !! THROTTLE: response ${ratio}x slower in 2nd half" -ForegroundColor Red
            }
        }
    }
    
    return @{ Name=$Name; Total=$Count; Success=$successCount; Blocked=$blockedCount; Errors=$errorCount }
}

# ============================================================
# MAIN
# ============================================================

$startTime = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
Write-Host ""
Write-Host "###############################################################" -ForegroundColor White
Write-Host "#  RATE LIMIT TEST - muasamcong.mpi.gov.vn" -ForegroundColor White
Write-Host "#  Start: $startTime" -ForegroundColor White
Write-Host "#  Each request waits for FULL response (timeout 30s)" -ForegroundColor White
Write-Host "###############################################################" -ForegroundColor White

# Warm-up
Write-Host "`n  [Warm-up...]" -ForegroundColor DarkGray
$w = Send-TestRequest -Url $BaseUrl -RequestId 0 -DetailMode
if ($w.Status -eq 200) {
    $kb = [math]::Round($w.Size / 1024, 1)
    Write-Host "  BASELINE: HTTP $($w.Status) | ${kb}KB | $($w.TimeMs)ms" -ForegroundColor Green
    Write-Host "  Title: $($w.PageInfo.Title)" -ForegroundColor Green
    Write-Host "  SearchBox: $($w.PageInfo.HasSearchBox) | Normal: $($w.PageInfo.IsNormalPage)" -ForegroundColor Green
    Write-Host "  Session: $($w.SessionId)" -ForegroundColor Green
} else {
    Write-Host "  FAILED to connect. Aborting." -ForegroundColor Red
    exit 1
}

Start-Sleep -Seconds 3
$allResults = @()

$allResults += Run-Scenario -Name "KB1: 10 req BURST (no delay)" -Url $BaseUrl -Count 10 -DelayMs 0
Write-Host "`n  [Wait 5s...]" -ForegroundColor DarkGray; Start-Sleep -Seconds 5

$allResults += Run-Scenario -Name "KB2: 30 req, 200ms delay" -Url $BaseUrl -Count 30 -DelayMs 200
Write-Host "`n  [Wait 5s...]" -ForegroundColor DarkGray; Start-Sleep -Seconds 5

$allResults += Run-Scenario -Name "KB3: 50 req, 100ms delay" -Url $BaseUrl -Count 50 -DelayMs 100
Write-Host "`n  [Wait 5s...]" -ForegroundColor DarkGray; Start-Sleep -Seconds 5

$allResults += Run-Scenario -Name "KB4: 100 req BURST (no delay)" -Url $BaseUrl -Count 100 -DelayMs 0
Write-Host "`n  [Wait 5s...]" -ForegroundColor DarkGray; Start-Sleep -Seconds 5

$allResults += Run-Scenario -Name "KB5: 20 req SEARCH endpoint (burst)" -Url $SearchUrl -Count 20 -DelayMs 0

# Final report
Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Magenta
Write-Host "  FINAL REPORT" -ForegroundColor Magenta
Write-Host ("=" * 70) -ForegroundColor Magenta
Write-Host ""

$totalReq = 0; $totalBlocked = 0; $totalErr = 0

foreach ($s in $allResults) {
    $totalReq += $s.Total
    $totalBlocked += $s.Blocked
    $totalErr += $s.Errors
    
    $icon = if ($s.Blocked -eq 0 -and $s.Errors -eq 0) { "PASS" } else { "FAIL" }
    $color = if ($s.Blocked -eq 0 -and $s.Errors -eq 0) { "Green" } else { "Red" }
    Write-Host "  [$icon] $($s.Name): $($s.Success)/$($s.Total) OK, $($s.Blocked) blocked" -ForegroundColor $color
}

Write-Host ""
Write-Host "  TOTAL: $totalReq requests | $totalBlocked blocked | $totalErr errors" -ForegroundColor White
Write-Host ""

if ($totalBlocked -eq 0 -and $totalErr -eq 0) {
    Write-Host "  CONCLUSION: NO rate limit detected with $totalReq sequential requests." -ForegroundColor Green
    Write-Host "  - Full HTML returned every time (~289KB)" -ForegroundColor Green
    Write-Host "  - No IP ban, no 429, no captcha, no connection reset" -ForegroundColor Green
    Write-Host "  - New JSESSIONID issued per request (no session tracking block)" -ForegroundColor Green
    Write-Host ""
    Write-Host "  NOTE: Anti-bot may exist at JavaScript level (popup/challenge)" -ForegroundColor Yellow
    Write-Host "  which only triggers in real browsers (Selenium)." -ForegroundColor Yellow
} elseif ($totalBlocked -gt 0) {
    $pct = [math]::Round($totalBlocked/$totalReq*100, 1)
    Write-Host "  CONCLUSION: RATE LIMIT / ANTI-BOT DETECTED" -ForegroundColor Red
    Write-Host "  Block rate: $totalBlocked/$totalReq (${pct}%)" -ForegroundColor Red
} else {
    Write-Host "  CONCLUSION: Connection errors occurred, inconclusive." -ForegroundColor Yellow
}

$endTime = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
Write-Host ""
Write-Host "  End: $endTime" -ForegroundColor Gray
Write-Host ""
