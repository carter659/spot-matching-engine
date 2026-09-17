$ErrorActionPreference = 'Stop'
$report = Join-Path (Split-Path $PSScriptRoot -Parent) '.runtime/kafka-repair-result.txt'
$root = (Resolve-Path -LiteralPath 'D:/Program Files/kafka/kafka_2.13-4.3.1').Path
$source = Join-Path $root 'data/matching.qa.admin.20260916.market-0'
$backup = Join-Path $root ('recovery-backup/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
try {
    if (-not (Test-Path -LiteralPath $source)) { throw 'Expected orphan partition is absent; inspect before retrying.' }
    $source = (Resolve-Path -LiteralPath $source).Path
    if (-not $source.StartsWith($root + '\') -or -not $backup.StartsWith($root + '\')) { throw 'Paths outside Kafka home' }
    Stop-Service -Name KafkaUI
    Stop-Service -Name Kafka
    (Get-Service Kafka).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
    New-Item -ItemType Directory -Path $backup -Force | Out-Null
    Move-Item -LiteralPath $source -Destination $backup
    Start-Service -Name Kafka
    Start-Service -Name KafkaUI
    "SUCCESS: orphan partition preserved at $backup" | Set-Content -LiteralPath $report
} catch {
    $_ | Out-String | Set-Content -LiteralPath $report
    Start-Service -Name Kafka -ErrorAction SilentlyContinue
    Start-Service -Name KafkaUI -ErrorAction SilentlyContinue
    exit 1
}
