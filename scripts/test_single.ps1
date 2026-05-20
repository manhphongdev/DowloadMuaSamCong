$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
try {
    $r = Invoke-WebRequest -Uri "https://muasamcong.mpi.gov.vn/" -TimeoutSec 30 -UseBasicParsing
    $stopwatch.Stop()
    Write-Host "Status: $($r.StatusCode)"
    Write-Host "Size: $($r.Content.Length) bytes"
    Write-Host "Time: $($stopwatch.ElapsedMilliseconds) ms"
    Write-Host "Headers:"
    $r.Headers.Keys | ForEach-Object { Write-Host "  $_`: $($r.Headers[$_])" }
    Write-Host ""
    Write-Host "First 300 chars:"
    Write-Host $r.Content.Substring(0, [Math]::Min(300, $r.Content.Length))
} catch {
    $stopwatch.Stop()
    Write-Host "Error after $($stopwatch.ElapsedMilliseconds) ms"
    Write-Host $_.Exception.Message
    if ($_.Exception.Response) {
        Write-Host "HTTP Status: $([int]$_.Exception.Response.StatusCode)"
    }
}
