$ErrorActionPreference = 'Stop'
$logPath = 'D:\work\src\bituan\spot-matching\.runtime\kafka-repair.log'
Start-Transcript -Path $logPath -Append
try {
    $installRoot = (Resolve-Path -LiteralPath 'D:\Program Files\kafka\kafka_2.13-4.3.1').Path
    $dataRoot = (Resolve-Path -LiteralPath (Join-Path $installRoot 'data')).Path
    $partition = Join-Path $dataRoot 'matching.qa.perf.market-0'
    $dependents = @((Get-Service Kafka).DependentServices | Where-Object Status -eq 'Running' | Select-Object -ExpandProperty Name)
    if ((Get-Service Kafka).Status -ne 'Stopped') {
        Stop-Service -Name Kafka -Force -ErrorAction Continue
    }
    (Get-Service Kafka).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
    Start-Sleep -Seconds 3
    if (Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^java' -and $_.CommandLine -like '*kafka.Kafka*' }) { throw 'Kafka process still running; no files moved.' }
    if (Test-Path -LiteralPath $partition) {
        $resolved = (Resolve-Path -LiteralPath $partition).Path
        if ($resolved -ne (Join-Path $dataRoot 'matching.qa.perf.market-0')) { throw 'Unexpected source path' }
        $backup = Join-Path $installRoot ('quarantine-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
        if (-not $backup.StartsWith($installRoot + '\')) { throw 'Unexpected backup path' }
        New-Item -ItemType Directory -Path $backup | Out-Null
        Move-Item -LiteralPath $resolved -Destination $backup
        Write-Output "Preserved stray test partition at $backup"
    }
    Start-Service -Name Kafka
    (Get-Service Kafka).WaitForStatus('Running', [TimeSpan]::FromSeconds(30))
    foreach ($serviceName in $dependents) { Start-Service -Name $serviceName }
} catch { Write-Host ($_ | Out-String); throw }
finally { Stop-Transcript }
